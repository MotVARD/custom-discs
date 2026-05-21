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

    private static final Map<String, UUID> IN_PROGRESS = new ConcurrentHashMap<>();
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
        long cooldownMs = Config.SERVER.uploadCooldownSeconds.get() * 1000L;
        if (cooldownMs > 0) {
            long last = UPLOAD_COOLDOWNS.getOrDefault(uploader, 0L);
            long remainingSeconds = (last + cooldownMs - System.currentTimeMillis()) / 1000;
            if (remainingSeconds > 0) {
                return new StartResult.CooldownRemaining(remainingSeconds);
            }
        }

        int maxTotalMb = Config.SERVER.maxTotalStorageMb.get();
        if (maxTotalMb > 0) {
            long limitBytes = (long) maxTotalMb * 1024 * 1024;
            if (currentUploadFolderSize() + totalSize > limitBytes) {
                return new StartResult.QuotaExceeded();
            }
        }

        int perPlayerLimit = Config.SERVER.perPlayerFileLimit.get();
        if (perPlayerLimit > 0 && uploaderName != null) {
            long owned = UploadAttribution.snapshot().values().stream()
                    .filter(uploaderName::equals)
                    .count();
            if (owned >= perPlayerLimit) {
                return new StartResult.PerPlayerLimitReached(perPlayerLimit);
            }
        }

        if (IN_PROGRESS.putIfAbsent(safeName, uploader) != null) {
            return new StartResult.AlreadyInProgress();
        }
        return new StartResult.Ok();
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
            if (!e.getValue().equals(uploader)) return false;
            ModPaths.UPLOADS_DIR.resolve(e.getKey() + ModPaths.PARTIAL_SUFFIX).toFile().delete();
            CustomDiscsMod.LOGGER.info("[UploadSession] Cleaned up partial upload for disconnected player: {}", e.getKey());
            return true;
        });
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
