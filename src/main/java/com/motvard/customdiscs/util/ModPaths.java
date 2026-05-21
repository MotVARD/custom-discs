package com.motvard.customdiscs.util;

import com.motvard.customdiscs.CustomDiscsMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ModPaths {

    public static final Path UPLOADS_DIR        = Path.of("customdiscs_uploads");
    public static final Path CACHE_DIR          = Path.of("customdiscs_cache");
    public static final Path ATTRIBUTION_STORE  = UPLOADS_DIR.resolve(".uploaders.properties");
    public static final String PARTIAL_SUFFIX   = ".partial";

    private ModPaths() {}

    public static boolean ensureDir(Path dir) {
        if (Files.isDirectory(dir)) return true;
        try {
            Files.createDirectories(dir);
            return true;
        } catch (IOException e) {
            CustomDiscsMod.LOGGER.error("[ModPaths] Failed to create {}: {}", dir, e.getMessage());
            return false;
        }
    }
}
