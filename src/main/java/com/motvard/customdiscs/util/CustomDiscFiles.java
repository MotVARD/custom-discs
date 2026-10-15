package com.motvard.customdiscs.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure file-name and transfer-chunk validation helpers shared by the upload,
 * download and request packets. Kept free of Minecraft types so it can be unit tested.
 */
public final class CustomDiscFiles {

    private static final String[] ALLOWED_EXTENSIONS = {".wav", ".mp3", ".ogg"};

    /** Version stamp of an uploaded file: {@code <size>-<lastModifiedMillis>}. */
    private static final Pattern STAMP = Pattern.compile("\\d{1,19}-\\d{1,19}");

    private CustomDiscFiles() {}

    /**
     * Strips any directory components, returning just the bare file name.
     * Handles both '/' and '\' regardless of the host OS, since File.getName() only
     * recognises the platform separator and would miss '\' on a Linux server.
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

    /**
     * True if the name is a real, finalized upload a client may request: a regular audio
     * file, not a dotfile (e.g. the attribution sidecar) and not an in-progress .partial.
     */
    public static boolean isPublicUpload(String name) {
        return name != null
                && !name.isEmpty()
                && !name.startsWith(".")
                && !name.endsWith(ModPaths.PARTIAL_SUFFIX)
                && hasAllowedExtension(name);
    }

    /**
     * Version stamp the server sends with every play packet. It changes when a file is replaced
     * under the same name, so a client never plays an outdated cached copy.
     */
    public static String stamp(long size, long lastModifiedMillis) {
        return size + "-" + lastModifiedMillis;
    }

    /** True if {@code stamp} has the {@link #stamp} format (and so is safe in a file name). */
    public static boolean isValidStamp(String stamp) {
        return stamp != null && STAMP.matcher(stamp).matches();
    }

    /** File size encoded in a valid stamp. */
    public static long stampSize(String stamp) {
        return Long.parseLong(stamp.substring(0, stamp.indexOf('-')));
    }

    /** Client cache file for one version of a track: {@code <stamp>_<name>}. */
    public static String cacheFileName(String stamp, String safeName) {
        return stamp + "_" + safeName;
    }

    /** True if a cache file name carries a version stamp (files cached before 1.2.0 do not). */
    public static boolean isStampedCacheName(String fileName) {
        int sep = fileName == null ? -1 : fileName.indexOf('_');
        return sep > 0 && isValidStamp(fileName.substring(0, sep));
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
