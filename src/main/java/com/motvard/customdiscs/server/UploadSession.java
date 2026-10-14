package com.motvard.customdiscs.server;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.util.ModPaths;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side state for in-flight uploads: per-player cooldowns, per-file mutual
 * exclusion and storage quota enforcement. Lives here so {@link com.motvard.customdiscs.network.UploadChunkPacket}
 * stays a packet record without global mutable state.
 */
public final class UploadSession {

    /** An upload that received no chunk for this long is abandoned (client stopped sending
     *  without disconnecting) and its file name is released. */
    static final long STALE_UPLOAD_MS = 60_000L;

    private record Upload(UUID owner, long lastChunkMs) {}

    private static final Map<String, Upload> IN_PROGRESS = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> UPLOAD_COOLDOWNS = new ConcurrentHashMap<>();

    private UploadSession() {}

    public sealed interface StartResult {
        record Ok() implements StartResult {}
        record CooldownRemaining(long seconds) implements StartResult {}
        record AlreadyInProgress() implements StartResult {}
        record QuotaExceeded() implements StartResult {}
        record PerPlayerLimitReached(int limit) implements StartResult {}
    }

    public static StartResult tryStart(UUID uploader, String uploaderName, String safeName, int totalSize) {
        long now = System.currentTimeMillis();
        long cooldownMs = Config.SERVER.uploadCooldownSeconds.get() * 1000L;
        if (cooldownMs > 0) {
            long last = UPLOAD_COOLDOWNS.getOrDefault(uploader, 0L);
            long remainingSeconds = (last + cooldownMs - now) / 1000;
            if (remainingSeconds > 0) {
                return new StartResult.CooldownRemaining(remainingSeconds);
            }
        }

        if (exceedsStorageQuota(totalSize)) {
            return new StartResult.QuotaExceeded();
        }

        int perPlayerLimit = Config.SERVER.perPlayerFileLimit.get();
        if (perPlayerLimit > 0 && UploadAttribution.countOwnedBy(uploader, uploaderName) >= perPlayerLimit) {
            return new StartResult.PerPlayerLimitReached(perPlayerLimit);
        }

        if (!begin(uploader, safeName, now)) {
            return new StartResult.AlreadyInProgress();
        }
        return new StartResult.Ok();
    }

    /**
     * Claim the in-progress slot for {@code safeName}. A slot whose owner has sent nothing for
     * {@link #STALE_UPLOAD_MS} is taken over (its partial file is removed first).
     */
    static boolean begin(UUID uploader, String safeName, long nowMs) {
        Upload current = IN_PROGRESS.get(safeName);
        if (current != null && isStale(current, nowMs)) {
            abandon(safeName, current);
        }
        return IN_PROGRESS.putIfAbsent(safeName, new Upload(uploader, nowMs)) == null;
    }

    /**
     * True if {@code uploader} currently holds the in-progress slot for {@code safeName}, and if
     * so records the chunk as activity so the upload is not treated as abandoned.
     */
    public static boolean touch(UUID uploader, String safeName) {
        return touch(uploader, safeName, System.currentTimeMillis());
    }

    static boolean touch(UUID uploader, String safeName, long nowMs) {
        Upload current = IN_PROGRESS.get(safeName);
        if (current == null || !current.owner().equals(uploader)) return false;
        return IN_PROGRESS.replace(safeName, current, new Upload(uploader, nowMs));
    }

    /** Release every abandoned upload and delete its partial file. Called periodically. */
    public static void expireStale() {
        expireStale(System.currentTimeMillis());
    }

    static void expireStale(long nowMs) {
        IN_PROGRESS.forEach((name, upload) -> {
            if (isStale(upload, nowMs)) abandon(name, upload);
        });
    }

    private static boolean isStale(Upload upload, long nowMs) {
        return nowMs - upload.lastChunkMs() >= STALE_UPLOAD_MS;
    }

    private static void abandon(String safeName, Upload upload) {
        if (IN_PROGRESS.remove(safeName, upload)) {
            ModPaths.UPLOADS_DIR.resolve(partialName(safeName, upload.owner())).toFile().delete();
            CustomDiscsMod.LOGGER.info("[UploadSession] Abandoned upload of {} expired (no data for {} s)",
                    safeName, STALE_UPLOAD_MS / 1000);
        }
    }

    /** True if adding {@code additionalBytes} would exceed the configured storage cap (0 = unlimited). */
    public static boolean exceedsStorageQuota(int additionalBytes) {
        int maxTotalMb = Config.SERVER.maxTotalStorageMb.get();
        if (maxTotalMb <= 0) return false;
        long limitBytes = (long) maxTotalMb * 1024 * 1024;
        return currentUploadFolderSize() + additionalBytes > limitBytes;
    }

    /** Name of a player's in-progress partial file. Namespaced by UUID so two players uploading
     *  the same file name cannot write into each other's partial. */
    public static String partialName(String safeName, UUID uploader) {
        return safeName + "." + uploader + ModPaths.PARTIAL_SUFFIX;
    }

    public static void markComplete(UUID uploader, String safeName) {
        IN_PROGRESS.remove(safeName);
        UPLOAD_COOLDOWNS.put(uploader, System.currentTimeMillis());
    }

    public static void markFailed(String safeName) {
        IN_PROGRESS.remove(safeName);
    }

    public static void cleanupOnDisconnect(UUID uploader) {
        IN_PROGRESS.entrySet().removeIf(e -> {
            if (!e.getValue().owner().equals(uploader)) return false;
            ModPaths.UPLOADS_DIR.resolve(partialName(e.getKey(), uploader)).toFile().delete();
            CustomDiscsMod.LOGGER.info("[UploadSession] Cleaned up partial upload for disconnected player: {}", e.getKey());
            return true;
        });
    }

    /** Drop all in-flight upload state. Called when the server stops so it does not leak into a
     *  later world load in the same JVM (singleplayer). */
    public static void clear() {
        IN_PROGRESS.clear();
        UPLOAD_COOLDOWNS.clear();
    }

    private static long currentUploadFolderSize() {
        File[] files = ModPaths.UPLOADS_DIR.toFile().listFiles(f ->
                f.isFile() && !f.getName().startsWith(".") && !f.getName().endsWith(ModPaths.PARTIAL_SUFFIX));
        if (files == null) return 0L;
        long total = 0L;
        for (File f : files) {
            total += f.length();
        }
        return total;
    }
}
