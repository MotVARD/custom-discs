package com.motvard.customdiscs.server;

import com.motvard.customdiscs.client.audio.MusicSource;
import com.motvard.customdiscs.util.CustomDiscFiles;
import com.motvard.customdiscs.util.ModPaths;

import java.io.File;
import java.util.Optional;

/** Server-side lookup of uploaded track files referenced by a disc's {@code server://} source. */
public final class UploadedTracks {

    private UploadedTracks() {}

    /** The upload file name a disc source points to, or null if it is not a valid public upload. */
    public static String fileName(String source) {
        if (source == null || !source.startsWith(MusicSource.SERVER_SCHEME)) return null;
        String name = CustomDiscFiles.sanitize(source.substring(MusicSource.SERVER_SCHEME.length()));
        if (CustomDiscFiles.isSuspiciousPath(name) || !CustomDiscFiles.isPublicUpload(name)) return null;
        return name;
    }

    /**
     * Version stamp of the uploaded file a disc plays, or empty if the file no longer exists
     * (deleted with /disc delete): such a disc must not play from anyone's cache either.
     */
    public static Optional<String> stamp(String source) {
        String name = fileName(source);
        if (name == null) return Optional.empty();
        File file = ModPaths.UPLOADS_DIR.resolve(name).toFile();
        if (!file.isFile()) return Optional.empty();
        return Optional.of(CustomDiscFiles.stamp(file.length(), file.lastModified()));
    }
}
