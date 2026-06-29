package com.motvard.customdiscs.server;

import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.util.ModPaths;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Records which player uploaded each file, in a sidecar properties file inside the
 * upload folder. All public methods are synchronized: callers come from the server
 * tick thread (uploads), the command thread (/disc list, /disc delete) and the
 * network executor (disconnect cleanup), so concurrent access is possible.
 */
public final class UploadAttribution {

    private static final File STORE = ModPaths.ATTRIBUTION_STORE.toFile();

    private UploadAttribution() {}

    public static synchronized void record(String fileName, String uploader) {
        Properties props = load();
        props.setProperty(fileName, uploader);
        save(props);
    }

    public static synchronized void remove(String fileName) {
        Properties props = load();
        if (props.remove(fileName) != null) {
            save(props);
        }
    }

    /** Immutable snapshot of filename → uploader entries. Safe to iterate without locking. */
    public static synchronized Map<String, String> snapshot() {
        Properties props = load();
        Map<String, String> out = new HashMap<>(props.size());
        for (String key : props.stringPropertyNames()) {
            out.put(key, props.getProperty(key));
        }
        return Map.copyOf(out);
    }

    private static Properties load() {
        Properties props = new Properties();
        if (STORE.isFile()) {
            try (Reader r = Files.newBufferedReader(STORE.toPath(), StandardCharsets.UTF_8)) {
                props.load(r);
            } catch (IOException e) {
                CustomDiscsMod.LOGGER.warn("[UploadAttribution] Could not read {}: {}", STORE.getName(), e.getMessage());
            }
        }
        return props;
    }

    private static void save(Properties props) {
        try (Writer w = Files.newBufferedWriter(STORE.toPath(), StandardCharsets.UTF_8)) {
            props.store(w, "Custom Discs file uploader attribution");
        } catch (IOException e) {
            CustomDiscsMod.LOGGER.warn("[UploadAttribution] Could not write {}: {}", STORE.getName(), e.getMessage());
        }
    }
}
