package com.motvard.customdiscs.client.audio;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.network.RequestFilePacket;
import com.motvard.customdiscs.util.BlockingInputStream;
import com.motvard.customdiscs.util.CustomDiscFiles;
import com.motvard.customdiscs.util.ModPaths;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streaming downloader. Returns an {@link InputStream} the decoder can read from
 * immediately: chunks are routed into the stream as they arrive from the network
 * thread, and simultaneously persisted to the partial file for later cache reuse.
 *
 * <p>Each download applies its chunks on a dedicated single-thread executor, so chunks are
 * written and queued in arrival order (and backpressure never blocks the network thread).
 * {@link com.motvard.customdiscs.network.DownloadChunkPacket} calls {@link #acceptChunk}.
 */
public final class PacketFileDownloader {

    /** Cap on bytes buffered between network and decoder before backpressure kicks in. */
    private static final int QUEUE_CAP_BYTES = 4 * 1024 * 1024;

    private static final Map<String, ActiveDownload> ACTIVE = new ConcurrentHashMap<>();

    private PacketFileDownloader() {}

    /**
     * Open a read-side stream for the named track. A cached file is returned as a plain
     * {@link java.io.FileInputStream}; otherwise a {@link BlockingInputStream} is created and a
     * {@link RequestFilePacket} is sent to the server, and the decoder can begin reading at once.
     *
     * <p>Cache files are named after the server's version {@code stamp} of the file, so a track
     * that was deleted and uploaded again under the same name is downloaded afresh.
     */
    public static InputStream requestStream(String safeName, String stamp) throws IOException {
        return open(safeName, stamp, true);
    }

    /** @param mayRestart false on the one retry after a stalled or replaced download, so a
     *                    repeatedly failing download cannot recurse. */
    private static InputStream open(String safeName, String stamp, boolean mayRestart) throws IOException {
        Path finalPath = ModPaths.CACHE_DIR.resolve(CustomDiscFiles.cacheFileName(stamp, safeName));
        if (Files.isRegularFile(finalPath) && Files.size(finalPath) > 0) {
            CustomDiscsMod.LOGGER.debug("[PacketDownloader] Cache hit: {}", safeName);
            return Files.newInputStream(finalPath);
        }

        if (!ModPaths.ensureDir(ModPaths.CACHE_DIR)) {
            throw new IOException("Could not create cache directory");
        }

        long timeoutMs = Config.CLIENT.packetDownloadTimeoutSeconds.get() * 1000L;

        // A download that has received nothing for the timeout is dead (the server dropped or
        // never got the request): replace it instead of waiting on it forever. Failed before the
        // new one is created, because both use the same partial file.
        ActiveDownload stalled = ACTIVE.get(safeName);
        if (stalled != null && isStalled(stalled, timeoutMs)) {
            failDownload(safeName, stalled, new IOException("no data from the server for " + timeoutMs + " ms"));
        }

        boolean[] created = {false};
        ActiveDownload active = ACTIVE.compute(safeName, (name, existing) -> {
            if (existing != null) return existing;
            try {
                Path partial = finalPath.resolveSibling(finalPath.getFileName() + ModPaths.PARTIAL_SUFFIX);
                Files.deleteIfExists(partial);
                FileChannel writer = FileChannel.open(partial,
                        StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                BlockingInputStream queue = new BlockingInputStream(QUEUE_CAP_BYTES, timeoutMs);
                ExecutorService applyExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "CustomDiscs-ChunkApply-" + name);
                    t.setDaemon(true);
                    return t;
                });
                created[0] = true;
                return new ActiveDownload(queue, writer, partial, finalPath, applyExecutor,
                        CustomDiscFiles.stampSize(stamp));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        // The BlockingInputStream is a single-consumer pipe. If a download for this track is
        // already in flight (another jukebox started the same uncached track), do not share its
        // pipe and do not send a duplicate request: wait for that download to finalize the cache
        // file, then read from it.
        if (!created[0]) {
            return awaitCachedFile(safeName, stamp, active, finalPath, timeoutMs, mayRestart);
        }

        try {
            PacketDistributor.sendToServer(new RequestFilePacket(safeName));
            CustomDiscsMod.LOGGER.debug("[PacketDownloader] Streaming start: {}", safeName);
        } catch (Exception e) {
            failDownload(safeName, active, new IOException("Request packet failed", e));
            throw new IOException("Request packet failed", e);
        }
        return active.queue;
    }

    /** Wait (up to {@code timeoutMs}) for an in-flight download of the same track to finalize its
     *  cache file, then open it. Runs on the sound's background-decode thread, so blocking is fine. */
    private static InputStream awaitCachedFile(String safeName, String stamp, ActiveDownload awaited,
                                               Path finalPath, long timeoutMs, boolean mayRestart) throws IOException {
        CustomDiscsMod.LOGGER.debug("[PacketDownloader] Awaiting in-flight download of {}", safeName);
        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(finalPath) && Files.size(finalPath) > 0) {
                return Files.newInputStream(finalPath);
            }
            // A download leaves ACTIVE only after its cache file is in place (or it failed).
            if (ACTIVE.get(safeName) != awaited) {
                if (Files.isRegularFile(finalPath) && Files.size(finalPath) > 0) {
                    return Files.newInputStream(finalPath);
                }
                if (awaited.cancelled.get() || !mayRestart) {
                    // Cancelled (world unload): do not start a download for a sound that is going away.
                    throw new IOException("Concurrent download of " + safeName + " ended without our file");
                }
                // It fetched another version of the file (re-uploaded under the same name
                // meanwhile), or failed (stalled, I/O): use or start the replacement download.
                return open(safeName, stamp, false);
            }
            if (isStalled(awaited, timeoutMs)) {
                // The download we wait for gets no data: drop it and fetch the file ourselves.
                failDownload(safeName, awaited, new IOException("no data from the server for " + timeoutMs + " ms"));
                if (!mayRestart) throw new IOException("Download of " + safeName + " stalled again");
                return open(safeName, stamp, false);
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted awaiting download of " + safeName);
            }
        }
        throw new IOException("Timed out awaiting concurrent download of " + safeName);
    }

    /**
     * Called on the client network thread as chunks arrive. The actual disk write and queue
     * hand-off run on the download's single thread, preserving order and keeping backpressure
     * off the network thread.
     */
    public static void acceptChunk(String safeName, byte[] data, int offset, int totalSize) {
        ActiveDownload active = ACTIVE.get(safeName);
        if (active == null) {
            // Chunk arrived after cancel/completion, or for a name we did not ask for.
            CustomDiscsMod.LOGGER.debug("[PacketDownloader] Chunk for inactive download: {}", safeName);
            return;
        }
        // Another version of the file (re-uploaded under this name), or the tail of an abandoned
        // transfer: the server sends one file strictly in order over one connection.
        if (totalSize != active.expectedSize || offset != active.receivedNext.get()) {
            CustomDiscsMod.LOGGER.debug("[PacketDownloader] Ignoring chunk of {} at {}/{} (expected {}/{})",
                    safeName, offset, totalSize, active.receivedNext.get(), active.expectedSize);
            return;
        }
        // Runs on the single network thread, so this read-modify-write is not contended.
        active.receivedNext.addAndGet(data.length);
        active.lastProgressMs.set(System.currentTimeMillis());
        try {
            active.applyExecutor.execute(() -> applyChunk(safeName, active, data, offset, totalSize));
        } catch (RejectedExecutionException ignored) {
            // Download already finished or was cancelled; nothing to do.
        }
    }

    private static void applyChunk(String safeName, ActiveDownload active, byte[] data, int offset, int totalSize) {
        // The server sends a file strictly in order over one connection, so anything else is the
        // tail of an earlier transfer of this name that was abandoned (e.g. on a dimension change).
        if (offset != active.written.get()) {
            CustomDiscsMod.LOGGER.debug("[PacketDownloader] Ignoring out-of-order chunk of {} at {} (expected {})",
                    safeName, offset, active.written.get());
            return;
        }
        try {
            active.diskWriter.write(ByteBuffer.wrap(data), offset);
            active.queue.offer(data);
            long written = active.written.addAndGet(data.length);
            if (written >= totalSize) {
                completeDownload(safeName, active);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failDownload(safeName, active, new IOException("Interrupted while buffering chunk", e));
        } catch (IOException e) {
            failDownload(safeName, active, e);
        }
    }

    /**
     * All bytes written: close the pipe and move partial to final. The entry stays in ACTIVE until
     * the cache file is in place, so a waiter or a new request never sees neither of the two and
     * starts a second download over this one's partial file.
     */
    private static void completeDownload(String safeName, ActiveDownload active) {
        if (!active.done.compareAndSet(false, true)) return;
        active.applyExecutor.shutdown();
        try {
            active.diskWriter.close();
            move(active.partialPath, active.finalPath);
            active.succeeded.set(true);
            CustomDiscsMod.LOGGER.debug("[PacketDownloader] Download complete: {} ({} bytes)",
                    safeName, Files.size(active.finalPath));
        } catch (IOException e) {
            CustomDiscsMod.LOGGER.error("[PacketDownloader] Could not finalise {}: {}", safeName, e.getMessage());
            try { Files.deleteIfExists(active.partialPath); } catch (IOException ignored) {}
        } finally {
            ACTIVE.remove(safeName, active);
            active.queue.finish();
        }
    }

    /** Error path: abort and surface to the reader. */
    private static void failDownload(String safeName, ActiveDownload active, IOException err) {
        if (!active.done.compareAndSet(false, true)) return;
        active.applyExecutor.shutdownNow();
        CustomDiscsMod.LOGGER.warn("[PacketDownloader] Download failed for {}: {}", safeName, err.getMessage());
        try { active.diskWriter.close(); } catch (IOException ignored) {}
        try { Files.deleteIfExists(active.partialPath); } catch (IOException ignored) {}
        ACTIVE.remove(safeName, active);
        active.queue.fail(err);
    }

    /** Cancel every in-flight download (e.g. world unload). */
    public static void cancelAll() {
        for (String name : ACTIVE.keySet().toArray(new String[0])) {
            ActiveDownload active = ACTIVE.get(name);
            if (active == null) continue;
            active.cancelled.set(true); // before failing, so waiters see why it ended
            failDownload(name, active, new IOException("cancelled"));
        }
    }

    /** Move with an atomic move, falling back to a plain replace where the FS forbids it. */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void cleanCache() {
        File dir = ModPaths.CACHE_DIR.toFile();
        if (!dir.exists()) return;

        Set<String> inUse = activeCacheFileNames();
        // Files cached before 1.2.0 carry no version stamp and can never be matched again.
        File[] legacy = dir.listFiles(f -> f.isFile() && !f.getName().endsWith(ModPaths.PARTIAL_SUFFIX)
                && !CustomDiscFiles.isStampedCacheName(f.getName()) && !inUse.contains(f.getName()));
        if (legacy != null) {
            for (File f : legacy) {
                if (f.delete()) {
                    CustomDiscsMod.LOGGER.debug("[Cache] Deleted unversioned cache file: {}", f.getName());
                }
            }
        }

        int maxMb = Config.CLIENT.maxCacheSizeMb.get();
        if (maxMb <= 0) return;
        long maxBytes = (long) maxMb * 1024 * 1024;

        File[] files = dir.listFiles(f -> f.isFile() && !f.getName().endsWith(ModPaths.PARTIAL_SUFFIX));
        if (files == null || files.length == 0) return;

        long total = Arrays.stream(files).mapToLong(File::length).sum();
        if (total <= maxBytes) return;

        Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));

        for (File f : files) {
            if (total <= maxBytes) break;
            // Never evict a file a download is actively writing/reading.
            if (inUse.contains(f.getName())) continue;
            long size = f.length();
            if (f.delete()) {
                total -= size;
                CustomDiscsMod.LOGGER.debug("[Cache] Deleted old cache file: {} ({} KB)", f.getName(), size / 1024);
            }
        }
    }

    /** No data received for the timeout. A fully received download is never stalled: it may only
     *  be waiting for playback to consume its buffer (or a paused singleplayer game). */
    private static boolean isStalled(ActiveDownload active, long timeoutMs) {
        if (active.receivedNext.get() >= active.expectedSize) return false;
        return System.currentTimeMillis() - active.lastProgressMs.get() > timeoutMs;
    }

    /** Cache file names that in-flight downloads will write. */
    private static Set<String> activeCacheFileNames() {
        Set<String> names = new java.util.HashSet<>();
        for (ActiveDownload active : ACTIVE.values()) {
            names.add(active.finalPath.getFileName().toString());
        }
        return names;
    }

    /**
     * {@code written}: bytes applied (disk + pipe); {@code receivedNext}: bytes received in order;
     * {@code lastProgressMs}: creation or last chunk received; {@code done}: completed or failed
     * (only one of them finalises); {@code succeeded}: the cache file was written;
     * {@code cancelled}: dropped on world unload.
     */
    private record ActiveDownload(BlockingInputStream queue, FileChannel diskWriter,
                                  Path partialPath, Path finalPath,
                                  ExecutorService applyExecutor, long expectedSize,
                                  AtomicLong written, AtomicLong receivedNext, AtomicLong lastProgressMs,
                                  AtomicBoolean done, AtomicBoolean succeeded, AtomicBoolean cancelled) {
        ActiveDownload(BlockingInputStream queue, FileChannel diskWriter,
                       Path partialPath, Path finalPath, ExecutorService applyExecutor, long expectedSize) {
            this(queue, diskWriter, partialPath, finalPath, applyExecutor, expectedSize,
                    new AtomicLong(), new AtomicLong(), new AtomicLong(System.currentTimeMillis()),
                    new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean());
        }
    }
}
