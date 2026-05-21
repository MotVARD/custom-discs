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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streaming downloader. Returns an {@link InputStream} the decoder can read from
 * immediately — chunks are routed into the stream as they arrive from the network
 * thread, and simultaneously persisted to the partial file for later cache reuse.
 *
 * <p>Decoupled from the network packet code: {@link com.motvard.customdiscs.network.DownloadChunkPacket}
 * calls {@link #acceptChunk}, {@link #completeDownload}, {@link #failDownload}.
 */
public final class PacketFileDownloader {

    /** Cap on bytes buffered between network and decoder before backpressure kicks in. */
    private static final int QUEUE_CAP_BYTES = 4 * 1024 * 1024;

    private static final Map<String, ActiveDownload> ACTIVE = new ConcurrentHashMap<>();

    private PacketFileDownloader() {}

    /**
     * Open a read-side stream for the named track. If the cache already has the file
     * it is returned as a plain {@link java.io.FileInputStream}; otherwise a
     * {@link BlockingInputStream} is created and a {@link RequestFilePacket} is sent
     * to the server. Decoder can begin reading immediately.
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

        ActiveDownload active = ACTIVE.compute(safeName, (name, existing) -> {
            if (existing != null) return existing;
            try {
                Path partial = ModPaths.CACHE_DIR.resolve(safeName + ModPaths.PARTIAL_SUFFIX);
                Files.deleteIfExists(partial);
                FileChannel writer = FileChannel.open(partial,
                        StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                BlockingInputStream queue = new BlockingInputStream(QUEUE_CAP_BYTES);
                return new ActiveDownload(queue, writer, partial, finalPath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        try {
            PacketDistributor.sendToServer(new RequestFilePacket(safeName));
            CustomDiscsMod.LOGGER.debug("[PacketDownloader] Streaming start: {}", safeName);
        } catch (Exception e) {
            failDownload(safeName, new IOException("Request packet failed", e));
            throw new IOException("Request packet failed", e);
        }
        return active.queue;
    }

    /**
     * Called from {@link com.motvard.customdiscs.network.DownloadChunkPacket} on the
     * Util.ioPool thread. Writes to disk by position and forwards into the queue.
     * Blocks while the queue is full — that is the backpressure signal.
     */
    public static void acceptChunk(String safeName, byte[] data, int offset, int totalSize) {
        ActiveDownload active = ACTIVE.get(safeName);
        if (active == null) {
            // No active download — chunk arrived after cancel or for a name we did not ask for.
            CustomDiscsMod.LOGGER.warn("[PacketDownloader] Chunk for inactive download: {}", safeName);
            return;
        }
        try {
            active.diskWriter.write(ByteBuffer.wrap(data), offset);
            active.queue.offer(data);
            if (offset + data.length >= totalSize) {
                completeDownload(safeName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failDownload(safeName, new IOException("Interrupted while buffering chunk", e));
        } catch (IOException e) {
            failDownload(safeName, e);
        }
    }

    /** Final chunk arrived — close pipe, atomic-move partial → final. */
    public static void completeDownload(String safeName) {
        ActiveDownload active = ACTIVE.remove(safeName);
        if (active == null) return;
        try {
            active.diskWriter.close();
            Files.move(active.partialPath, active.finalPath,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            CustomDiscsMod.LOGGER.debug("[PacketDownloader] Download complete: {} ({} bytes)",
                    safeName, Files.size(active.finalPath));
        } catch (IOException e) {
            CustomDiscsMod.LOGGER.error("[PacketDownloader] Could not finalise {}: {}", safeName, e.getMessage());
        } finally {
            active.queue.finish();
        }
    }

    /** Error path — abort and surface to the reader. */
    public static void failDownload(String safeName, IOException err) {
        ActiveDownload active = ACTIVE.remove(safeName);
        if (active == null) return;
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
            long size = f.length();
            if (f.delete()) {
                total -= size;
                CustomDiscsMod.LOGGER.debug("[Cache] Deleted old cache file: {} ({} KB)", f.getName(), size / 1024);
            }
        }
    }

    private record ActiveDownload(BlockingInputStream queue, FileChannel diskWriter,
                                  Path partialPath, Path finalPath) {}
}
