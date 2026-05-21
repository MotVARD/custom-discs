package com.motvard.customdiscs.util;

import java.util.Locale;

/**
 * Pure file-name and transfer-chunk validation helpers shared by the upload,
 * download and request packets. Kept free of Minecraft types so it can be unit tested.
 */
public final class CustomDiscFiles {

    private static final String[] ALLOWED_EXTENSIONS = {".wav", ".mp3", ".ogg"};

    private CustomDiscFiles() {}

    /**
     * Strips any directory components, returning just the bare file name.
     * Handles both '/' and '\' regardless of the host OS — File.getName() only
     * recognises the platform separator, which would miss '\' on a Linux server.
     */
    public static String sanitize(String name) {
        if (name == null) return "";
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return cut >= 0 ? name.substring(cut + 1) : name;
    }

    /** True if the name ends with an allowed audio extension (case-insensitive). */
    public static boolean hasAllowedExtension(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * True if the raw name looks like a path-traversal or absolute-path attempt.
     * Intended to be checked on the sanitized name as a defence-in-depth guard.
     */
    public static boolean isSuspiciousPath(String name) {
        return name == null || name.isEmpty()
                || name.contains("..") || name.contains("/") || name.contains("\\");
    }

    /** Validates a transfer chunk against the declared total file size. */
    public static boolean isValidChunk(int offset, int dataLength, int totalSize) {
        return totalSize > 0
                && offset >= 0
                && dataLength >= 0
                && offset < totalSize
                && (long) offset + dataLength <= totalSize;
    }
}
