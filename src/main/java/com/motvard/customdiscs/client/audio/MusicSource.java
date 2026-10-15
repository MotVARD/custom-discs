package com.motvard.customdiscs.client.audio;

import com.motvard.customdiscs.util.CustomDiscFiles;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class MusicSource {

    public static final String SERVER_SCHEME = "server://";

    private static final int PREFETCH_BYTES = 1_048_576;

    private MusicSource() {}

    /**
     * Open a server-hosted track. {@code stamp} is the server's version of the file, so a cached
     * copy of an older file with the same name is never used.
     */
    public static InputStream open(String rawSource, String stamp) throws IOException {
        if (rawSource == null || !rawSource.startsWith(SERVER_SCHEME)) {
            // Only server-hosted files are accepted, so a malicious server cannot make
            // the client fetch an arbitrary location.
            throw new IOException("Unsupported audio source, only " + SERVER_SCHEME + " is allowed: " + rawSource);
        }
        if (!CustomDiscFiles.isValidStamp(stamp)) {
            throw new IOException("Invalid file stamp for " + rawSource + ": " + stamp);
        }
        InputStream raw = openServerFile(rawSource.substring(SERVER_SCHEME.length()), stamp);
        return new BufferedInputStream(raw, PREFETCH_BYTES);
    }

    private static InputStream openServerFile(String rawName, String stamp) throws IOException {
        String safeName = CustomDiscFiles.sanitize(rawName);
        // requestStream returns a FileInputStream when the cache already has the file,
        // and a BlockingInputStream that the decoder can read while bytes are still
        // arriving from the server otherwise. No blocking wait for the full transfer.
        return PacketFileDownloader.requestStream(safeName, stamp);
    }
}
