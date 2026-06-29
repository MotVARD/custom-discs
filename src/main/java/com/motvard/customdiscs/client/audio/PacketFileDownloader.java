package com.motvard.customdiscs.client.audio;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.network.RequestFilePacket;
import com.motvard.customdiscs.util.BlockingInputStream;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
     */
    public static InputStream requestStream(String safeName) throws IOException {
        Path finalPath = ModPaths.CACHE_DIR.resolve(safeName);
        if (Files.isRegularFile(finalPath) && Files.size(finalPath) > 0) {
            CustomDiscsMod.LOGGER.debug("[PacketDownloader] Cache hit: {}", safeName);
            return Files.newInputStream(finalPath);
        }

        if (!ModPaths.ensureDir(ModPaths.CACHE_DIR)) {
            throw new IOException("Could not create cache directory");
        }

        long timeoutMs = Config.CLIENT.packetDownloadTimeoutSeconds.get() * 1000L;

        boolean[] created = {false};
        ActiveDownload active = ACTIVE.compute(safeName, (name, existing) -> {
            if (existing != null) return existing;
            try {
                Path partial = ModPaths.CACHE_DIR.resolve(safeName + ModPaths.PARTIAL_SUFFIX);
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
                return new ActiveDownload(queue, writer, partial, finalPath, applyExecutor);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        // The BlockingInputStream is a single-consumer pipe. If a download for this track is
        // already in flight (another jukebox started the same uncached track), do not share its
        // pipe and do not send a duplicate request: wait for that download to finalize the cache
        // file, then read from it.
        if (!created[0]) {
            return awaitCachedFile(safeName, finalPath, timeoutMs);
        }

        try {
            PacketDistributor.sendToServer(new RequestFilePacket(safeName));
            CustomDiscsMod.LOGGER.debug("[PacketDownloader] Streaming start: {}", safeName);
        } catch (Exception e) {
            failDownload(safeName, new IOException("Request packet failed", e));
            throw new IOException("Request packet failed", e);
        }
        return active.queue;
    }

    /** Wait (up to {@code timeoutMs}) for an in-flight download of the same track to finalize its
     *  cache file, then open it. Runs on the sound's background-decode thread, so blocking is fine. */
    private static InputStream awaitCachedFile(String safeName, Path finalPath, long timeoutMs) throws IOException {
        CustomDiscsMod.LOGGER.debug("[PacketDownloader] Awaiting in-flight download of {}", safeName);
        long deadline = System.currentTimeMillis() + Math.max(1000L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(finalPath) && Files.size(finalPath) > 0) {
                return Files.newInputStream(finalPath);
            }
            if (!ACTIVE.containsKey(safeName)) {
                // The other download finished or failed; the cache file is the only source of truth.
                if (Files.isRegularFile(finalPath) && Files.size(finalPath) > 0) {
                    return Files.newInputStream(finalPath);
                }
                throw new IOException("Concurrent download of " + safeName + " produced no cache file");
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
            CustomDiscsMod.LOGGER.warn("[PacketDownloader] Chunk for inactive download: {}", safeName);
            return;
        }
        try {
            active.applyExecutor.execute(() -> applyChunk(safeName, active, data, offset, totalSize));
        } catch (RejectedExecutionException ignored) {
            // Download already finished or was cancelled; nothing to do.
        }
    }

    private static void applyChunk(String safeName, ActiveDownload active, byte[] data, int offset, int totalSize) {
        try {
            active.diskWriter.write(ByteBuffer.wrap(data), offset);
            active.queue.offer(data);
            long written = active.written.addAndGet(data.length);
            if (written >= totalSize) {
                completeDownload(safeName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failDownload(safeName, new IOException("Interrupted while buffering chunk", e));
        } catch (IOException e) {
            failDownload(safeName, e);
        }
    }

    /** All bytes written: close the pipe and move partial to final. */
    public static void completeDownload(String safeName) {
        ActiveDownload active = ACTIVE.remove(safeName);
        if (active == null) return;
        active.applyExecutor.shutdown();
        try {
            active.diskWriter.close();
            move(active.partialPath, active.finalPath);
            CustomDiscsMod.LOGGER.debug("[PacketDownloader] Download complete: {} ({} bytes)",
                    safeName, Files.size(active.finalPath));
        } catch (IOException e) {
            CustomDiscsMod.LOGGER.error("[PacketDownloader] Could not finalise {}: {}", safeName, e.getMessage());
        } finally {
            active.queue.finish();
        }
    }

    /** Error path: abort and surface to the reader. */
    public static void failDownload(String safeName, IOException err) {
        ActiveDownload active = ACTIVE.remove(safeName);
        if (active == null) return;
        active.applyExecutor.shutdownNow();
        CustomDiscsMod.LOGGER.warn("[PacketDownloader] Download failed for {}: {}", safeName, err.getMessage());
        try { active.diskWriter.close(); } catch (IOException ignored) {}
        try { Files.deleteIfExists(active.partialPath); } catch (IOException ignored) {}
        active.queue.fail(err);
    }

    /** Cancel every in-flight download (e.g. world unload). */
    public static void cancelAll() {
        for (String name : ACTIVE.keySet().toArray(new String[0])) {
            failDownload(name, new IOException("cancelled"));
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
        int maxMb = Config.CLIENT.maxCacheSizeMb.get();
        if (maxMb <= 0) return;
        long maxBytes = (long) maxMb * 1024 * 1024;

        File dir = ModPaths.CACHE_DIR.toFile();
        if (!dir.exists()) return;

        File[] files = dir.listFiles(f -> f.isFile() && !f.getName().endsWith(ModPaths.PARTIAL_SUFFIX));
        if (files == null || files.length == 0) return;

        long total = Arrays.stream(files).mapToLong(File::length).sum();
        if (total <= maxBytes) return;

        Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));

        for (File f : files) {
            if (total <= maxBytes) break;
            // Never evict a file a download is actively writing/reading.
            if (ACTIVE.containsKey(f.getName())) continue;
            long size = f.length();
            if (f.delete()) {
                total -= size;
                CustomDiscsMod.LOGGER.debug("[Cache] Deleted old cache file: {} ({} KB)", f.getName(), size / 1024);
            }
        }
    }

    private record ActiveDownload(BlockingInputStream queue, FileChannel diskWriter,
                                  Path partialPath, Path finalPath,
                                  ExecutorService applyExecutor, AtomicLong written) {
        ActiveDownload(BlockingInputStream queue, FileChannel diskWriter,
                       Path partialPath, Path finalPath, ExecutorService applyExecutor) {
            this(queue, diskWriter, partialPath, finalPath, applyExecutor, new AtomicLong());
        }
    }
}
