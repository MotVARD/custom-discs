package com.motvard.customdiscs.server;

import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.client.audio.AudioStreamFactory;
import com.motvard.customdiscs.client.audio.MusicSource;
import com.motvard.customdiscs.util.CustomDiscFiles;
import com.motvard.customdiscs.util.ModPaths;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Server-side playback length of uploaded tracks, so the server knows when a disc has really
 * finished (vanilla jukebox redstone signal, Sophisticated Backpacks playlist advance).
 *
 * <p>The length is measured once by decoding the file to PCM on a background thread (headers
 * are unreliable for VBR MP3 and OGG) and kept in a sidecar properties file inside the upload
 * folder, together with the file size so a replaced file is measured again.
 */
public final class TrackDurations {

    private static final File STORE = ModPaths.UPLOADS_DIR.resolve(".durations.properties").toFile();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CustomDiscs-Duration");
        t.setDaemon(true);
        return t;
    });

    /** Known or in-progress measurements, by sanitized file name. */
    private static final Map<String, CompletableFuture<Long>> DURATIONS = new ConcurrentHashMap<>();

    private TrackDurations() {}

    /** Track length in milliseconds if already known. Starts measuring it otherwise. */
    public static OptionalLong get(String source) {
        CompletableFuture<Long> future = durationMs(source);
        if (future == null || !future.isDone() || future.isCompletedExceptionally()) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(future.join());
    }

    /**
     * Track length in milliseconds, measuring it in the background on first use. Returns null
     * for a source that is not a server-hosted upload. The future fails if the file is missing
     * or cannot be decoded; that result is kept (no retry storm from the jukebox ticker) until
     * {@link #invalidate} is called for the file.
     */
    public static CompletableFuture<Long> durationMs(String source) {
        String name = UploadedTracks.fileName(source);
        if (name == null) return null;
        return DURATIONS.computeIfAbsent(name, k -> CompletableFuture.supplyAsync(() -> loadOrMeasure(k), EXECUTOR));
    }

    /** Forget what is known about a file that was deleted or (re)uploaded. */
    public static void invalidate(String safeName) {
        DURATIONS.remove(safeName);
        synchronized (TrackDurations.class) {
            Properties props = load();
            if (props.remove(safeName) != null) save(props);
        }
    }

    /** Set a known length directly. Used by GameTests, which have no real audio file. */
    public static void record(String safeName, long millis) {
        DURATIONS.put(safeName, CompletableFuture.completedFuture(millis));
    }

    /** Measure every upload that has no stored length yet, so the first play already knows it. */
    public static void warmUp() {
        File[] files = ModPaths.UPLOADS_DIR.toFile().listFiles(f -> f.isFile() && CustomDiscFiles.isPublicUpload(f.getName()));
        if (files == null) return;
        for (File f : files) {
            durationMs(MusicSource.SERVER_SCHEME + f.getName());
        }
    }

    /** Length of the decoded audio in milliseconds. */
    public static long measure(InputStream raw, String sourceTag) throws Exception {
        try (AudioInputStream pcm = AudioStreamFactory.openAsPcm(raw, sourceTag)) {
            AudioFormat format = pcm.getFormat();
            if (format.getFrameSize() <= 0 || format.getFrameRate() <= 0) {
                throw new IOException("Unknown frame size or rate: " + format);
            }
            byte[] buf = new byte[65536];
            long bytes = 0;
            int n;
            while ((n = pcm.read(buf)) > 0) {
                bytes += n;
            }
            return (long) (bytes / (double) format.getFrameSize() / format.getFrameRate() * 1000.0);
        }
    }

    private static long loadOrMeasure(String name) {
        Path file = ModPaths.UPLOADS_DIR.resolve(name);
        try {
            long size = Files.size(file);
            Long stored = stored(name, size);
            if (stored != null) return stored;

            long millis;
            try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 20)) {
                millis = measure(in, name);
            }
            synchronized (TrackDurations.class) {
                Properties props = load();
                props.setProperty(name, size + ":" + millis);
                save(props);
            }
            CustomDiscsMod.LOGGER.debug("[TrackDurations] {} is {} ms", name, millis);
            return millis;
        } catch (Exception e) {
            CustomDiscsMod.LOGGER.warn("[TrackDurations] Could not measure {}: {}", name, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private static synchronized Long stored(String name, long size) {
        String value = load().getProperty(name);
        if (value == null) return null;
        String[] parts = value.split(":");
        try {
            if (parts.length == 2 && Long.parseLong(parts[0]) == size) {
                return Long.parseLong(parts[1]);
            }
        } catch (NumberFormatException ignored) {
            // Corrupt entry: measure again.
        }
        return null;
    }

    private static Properties load() {
        Properties props = new Properties();
        if (STORE.isFile()) {
            try (Reader r = Files.newBufferedReader(STORE.toPath(), StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                CustomDiscsMod.LOGGER.warn("[TrackDurations] Could not read {}: {}", STORE.getName(), e.getMessage());
            }
        }
        return props;
    }

    private static void save(Properties props) {
        if (!ModPaths.ensureDir(ModPaths.UPLOADS_DIR)) return;
        try (Writer w = Files.newBufferedWriter(STORE.toPath(), StandardCharsets.UTF_8)) {
            props.store(w, "Custom Discs track lengths (file size:milliseconds)");
        } catch (IOException e) {
            CustomDiscsMod.LOGGER.warn("[TrackDurations] Could not write {}: {}", STORE.getName(), e.getMessage());
        }
    }
}
