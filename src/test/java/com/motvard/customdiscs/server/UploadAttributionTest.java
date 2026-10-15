package com.motvard.customdiscs.server;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Attribution entries are owned by UUID; entries from before 1.2.0 by name. */
class UploadAttributionTest {

    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    @Test
    void roundTripWithUuid() {
        UploadAttribution.Uploader u = UploadAttribution.parse(
                UploadAttribution.format(new UploadAttribution.Uploader("Steve", STEVE)));
        assertEquals("Steve", u.name());
        assertEquals(STEVE, u.uuid());
    }

    @Test
    void legacyNameOnlyEntry() {
        UploadAttribution.Uploader u = UploadAttribution.parse("Steve");
        assertEquals("Steve", u.name());
        assertNull(u.uuid());
        assertTrue(u.isOwnedBy(STEVE, "steve"), "old entries match by name, ignoring case");
        assertFalse(u.isOwnedBy(STEVE, "Alex"));
    }

    @Test
    void uuidEntryIgnoresName() {
        UploadAttribution.Uploader u = new UploadAttribution.Uploader("OldName", STEVE);
        assertTrue(u.isOwnedBy(STEVE, "NewName"), "a renamed player still owns their uploads");
        assertFalse(u.isOwnedBy(UUID.randomUUID(), "OldName"), "taking someone's old name does not");
    }

    @Test
    void pipeWithoutUuidIsAName() {
        UploadAttribution.Uploader u = UploadAttribution.parse("we|ird");
        assertEquals("we|ird", u.name());
        assertNull(u.uuid());
    }
}
