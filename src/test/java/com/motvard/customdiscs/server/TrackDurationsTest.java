package com.motvard.customdiscs.server;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** TrackDurations.measure reports the decoded length, independent of the source format. */
class TrackDurationsTest {

    private static byte[] wav(AudioFormat format, int frames) throws IOException {
        byte[] pcm = new byte[frames * format.getFrameSize()];
        AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
        return out.toByteArray();
    }

    @Test
    void stereo16BitWavLength() throws Exception {
        AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, 2, 4, 44100f, false);
        // 2.5 seconds
        long ms = TrackDurations.measure(new ByteArrayInputStream(wav(fmt, 110250)), "stereo.wav");
        assertEquals(2500, ms, 1);
    }

    @Test
    void convertedEightBitMonoWavLength() throws Exception {
        AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, 22050f, 8, 1, 1, 22050f, false);
        // 3 seconds, goes through the 16-bit conversion path
        long ms = TrackDurations.measure(new ByteArrayInputStream(wav(fmt, 66150)), "mono8.wav");
        assertEquals(3000, ms, 1);
    }

    @Test
    void garbageIsRejected() {
        assertThrows(Exception.class,
                () -> TrackDurations.measure(new ByteArrayInputStream(new byte[4096]), "garbage.mp3"));
    }
}
