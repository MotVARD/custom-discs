package com.motvard.customdiscs.server;

import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.util.ModPaths;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Records which player uploaded each file, in a sidecar properties file inside the
 * upload folder. All public methods are synchronized: callers come from the server
 * tick thread (uploads), the command thread (/disc list, /disc delete) and the
 * network executor (disconnect cleanup), so concurrent access is possible.
 *
 * <p>Each value is {@code name|uuid}. Files uploaded by versions before 1.2.0 store only the
 * name; those still count towards a player's file limit by (case-insensitive) name.
 */
public final class UploadAttribution {

    private static final File STORE = ModPaths.ATTRIBUTION_STORE.toFile();

    /** Uploader of a file. {@code uuid} is null for entries written before 1.2.0. */
    public record Uploader(String name, @Nullable UUID uuid) {

        boolean isOwnedBy(UUID player, String playerName) {
            return uuid != null ? uuid.equals(player) : name.equalsIgnoreCase(playerName);
        }
    }

    private UploadAttribution() {}

    public static synchronized void record(String fileName, UUID uploader, String uploaderName) {
        Properties props = load();
        props.setProperty(fileName, format(new Uploader(uploaderName, uploader)));
        save(props);
    }

    public static synchronized void remove(String fileName) {
        Properties props = load();
        if (props.remove(fileName) != null) {
            save(props);
        }
    }

    /** Immutable snapshot of filename → uploader entries. Safe to iterate without locking. */
    public static synchronized Map<String, Uploader> snapshot() {
        Properties props = load();
        Map<String, Uploader> out = new HashMap<>(props.size());
        for (String key : props.stringPropertyNames()) {
            out.put(key, parse(props.getProperty(key)));
        }
        return Map.copyOf(out);
    }

    /** Number of uploaded files owned by this player (by UUID, or by name for old entries). */
    public static long countOwnedBy(UUID player, String playerName) {
        return snapshot().values().stream().filter(u -> u.isOwnedBy(player, playerName)).count();
    }

    static String format(Uploader uploader) {
        return uploader.uuid() == null ? uploader.name() : uploader.name() + "|" + uploader.uuid();
    }

    /** Parse a stored value; anything without a valid {@code |uuid} suffix is a name-only entry. */
    static Uploader parse(String value) {
        if (value == null) return new Uploader("?", null);
        int sep = value.lastIndexOf('|');
        if (sep >= 0) {
            try {
                return new Uploader(value.substring(0, sep), UUID.fromString(value.substring(sep + 1)));
            } catch (IllegalArgumentException ignored) {
                // Not a UUID: treat the whole value as a name.
            }
        }
        return new Uploader(value, null);
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
