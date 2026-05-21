package com.motvard.customdiscs.client.audio;

import com.motvard.customdiscs.util.CustomDiscFiles;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class MusicSource {

    public static final String SERVER_SCHEME = "server://";

    private static final int PREFETCH_BYTES = 1_048_576;

    private MusicSource() {}

    public static InputStream open(String rawSource) throws IOException {
        if (rawSource == null || !rawSource.startsWith(SERVER_SCHEME)) {
            // Only server-hosted files are accepted, so a malicious server cannot make
            // the client fetch an arbitrary location.
            throw new IOException("Unsupported audio source — only " + SERVER_SCHEME + " is allowed: " + rawSource);
        }
        InputStream raw = openServerFile(rawSource.substring(SERVER_SCHEME.length()));
        return new BufferedInputStream(raw, PREFETCH_BYTES);
    }

    private static InputStream openServerFile(String rawName) throws IOException {
        String safeName = CustomDiscFiles.sanitize(rawName);
        // requestStream returns a FileInputStream when the cache already has the file,
        // and a BlockingInputStream that the decoder can read while bytes are still
        // arriving from the server otherwise. No blocking wait for the full transfer.
        return PacketFileDownloader.requestStream(safeName);
    }
}
