package com.motvard.customdiscs.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomDiscFilesTest {

    // ----- sanitize -----

    @Test
    void sanitizeKeepsPlainName() {
        assertEquals("track.mp3", CustomDiscFiles.sanitize("track.mp3"));
    }

    @ParameterizedTest
    @CsvSource({
        "'../../etc/passwd', passwd",
        "'foo/bar/baz.mp3', baz.mp3",
        "'..\\..\\windows\\evil.mp3', evil.mp3",
        "'/absolute/song.ogg', song.ogg",
        "'C:\\Music\\song.wav', song.wav",
        "'mixed/path\\song.mp3', song.mp3"
    })
    void sanitizeStripsDirectories(String input, String expected) {
        assertEquals(expected, CustomDiscFiles.sanitize(input));
    }

    @Test
    void sanitizeHandlesNullAndEmpty() {
        assertEquals("", CustomDiscFiles.sanitize(null));
        assertEquals("", CustomDiscFiles.sanitize(""));
    }

    // ----- hasAllowedExtension -----

    @ParameterizedTest
    @ValueSource(strings = {"a.wav", "a.mp3", "a.ogg", "A.MP3", "song.Wav", "x.OgG"})
    void allowedExtensionsAccepted(String name) {
        assertTrue(CustomDiscFiles.hasAllowedExtension(name));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a.exe", "a.txt", "a.flac", "a.mp4", "noextension", "a.mp3.exe", ""})
    void disallowedExtensionsRejected(String name) {
        assertFalse(CustomDiscFiles.hasAllowedExtension(name));
    }

    @Test
    void hasAllowedExtensionHandlesNull() {
        assertFalse(CustomDiscFiles.hasAllowedExtension(null));
    }

    // ----- isSuspiciousPath -----

    @ParameterizedTest
    @ValueSource(strings = {"..", "../x", "a/b", "a\\b", "..\\..", ""})
    void suspiciousPathsDetected(String name) {
        assertTrue(CustomDiscFiles.isSuspiciousPath(name));
    }

    @Test
    void suspiciousPathDetectsNull() {
        assertTrue(CustomDiscFiles.isSuspiciousPath(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"track.mp3", "my song.ogg", "a.wav", "song_01-final.mp3"})
    void plainNamesNotSuspicious(String name) {
        assertFalse(CustomDiscFiles.isSuspiciousPath(name));
    }

    // ----- isValidChunk -----

    @Test
    void validChunksAccepted() {
        assertTrue(CustomDiscFiles.isValidChunk(0, 256, 1024));
        assertTrue(CustomDiscFiles.isValidChunk(768, 256, 1024), "final chunk reaching exactly totalSize");
        assertTrue(CustomDiscFiles.isValidChunk(0, 1, 1), "single-byte file");
    }

    @Test
    void negativeOffsetRejected() {
        assertFalse(CustomDiscFiles.isValidChunk(-1, 10, 1024));
    }

    @Test
    void offsetAtOrBeyondTotalRejected() {
        assertFalse(CustomDiscFiles.isValidChunk(1024, 0, 1024));
        assertFalse(CustomDiscFiles.isValidChunk(2000, 10, 1024));
    }

    @Test
    void chunkOverrunningTotalRejected() {
        assertFalse(CustomDiscFiles.isValidChunk(1000, 100, 1024));
    }

    @Test
    void nonPositiveTotalRejected() {
        assertFalse(CustomDiscFiles.isValidChunk(0, 0, 0));
        assertFalse(CustomDiscFiles.isValidChunk(0, 10, -5));
    }

    @Test
    void negativeDataLengthRejected() {
        assertFalse(CustomDiscFiles.isValidChunk(0, -1, 1024));
    }

    @Test
    void chunkOffsetOverflowRejected() {
        // offset + dataLength must not overflow int; the guard uses long arithmetic.
        assertFalse(CustomDiscFiles.isValidChunk(Integer.MAX_VALUE - 10, 256, Integer.MAX_VALUE));
    }
}
