package com.motvard.customdiscs.server;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The in-progress slot of an upload is released once its client stops sending. */
class UploadSessionTest {

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final long T0 = 1_000_000L;

    @AfterEach
    void reset() {
        UploadSession.clear();
    }

    @Test
    void activeUploadKeepsItsSlot() {
        assertTrue(UploadSession.begin(ALICE, "song.mp3", T0));
        assertTrue(UploadSession.touch(ALICE, "song.mp3", T0 + UploadSession.STALE_UPLOAD_MS - 1));
        assertFalse(UploadSession.begin(BOB, "song.mp3", T0 + UploadSession.STALE_UPLOAD_MS + 1),
                "a recent chunk keeps the upload alive");
        assertFalse(UploadSession.touch(BOB, "song.mp3", T0), "only the owner may continue the upload");
    }

    @Test
    void abandonedUploadIsTakenOver() {
        assertTrue(UploadSession.begin(ALICE, "song.mp3", T0));
        assertTrue(UploadSession.begin(BOB, "song.mp3", T0 + UploadSession.STALE_UPLOAD_MS),
                "a slot with no chunk for the timeout is released to the next uploader");
        assertFalse(UploadSession.touch(ALICE, "song.mp3", T0 + UploadSession.STALE_UPLOAD_MS + 1),
                "the old uploader's late chunks are rejected");
        assertTrue(UploadSession.touch(BOB, "song.mp3", T0 + UploadSession.STALE_UPLOAD_MS + 1));
    }

    @Test
    void periodicExpiryReleasesSlot() {
        assertTrue(UploadSession.begin(ALICE, "song.mp3", T0));
        UploadSession.expireStale(T0 + UploadSession.STALE_UPLOAD_MS - 1);
        assertTrue(UploadSession.touch(ALICE, "song.mp3", T0 + 1), "not expired before the timeout");
        UploadSession.expireStale(T0 + 1 + UploadSession.STALE_UPLOAD_MS);
        assertFalse(UploadSession.touch(ALICE, "song.mp3", T0 + 2 + UploadSession.STALE_UPLOAD_MS),
                "expired after the timeout");
        assertTrue(UploadSession.begin(BOB, "song.mp3", T0 + 2 + UploadSession.STALE_UPLOAD_MS));
    }
}
