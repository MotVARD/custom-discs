package com.motvard.customdiscs.client.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class PcmAudioStreamTest {

    private static AudioInputStream pcm16le(byte[] data, int channels, float rate) {
        AudioFormat fmt = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, channels * 2, rate, false);
        return new AudioInputStream(new ByteArrayInputStream(data), fmt, data.length / (channels * 2));
    }

    /** Pack a sequence of signed 16-bit samples into a little-endian byte array. */
    private static byte[] samplesLe(short... samples) {
        byte[] out = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            out[i * 2]     = (byte) (samples[i] & 0xFF);
            out[i * 2 + 1] = (byte) ((samples[i] >> 8) & 0xFF);
        }
        return out;
    }

    /** Read a ByteBuffer back into shorts (LE). */
    private static short[] readShorts(ByteBuffer buf) {
        short[] out = new short[buf.remaining() / 2];
        for (int i = 0; i < out.length; i++) {
            int lo = buf.get() & 0xFF;
            int hi = buf.get();
            out[i] = (short) ((hi << 8) | lo);
        }
        return out;
    }

    @Test
    void monoPassesThroughUnchanged() throws Exception {
        byte[] data = samplesLe((short) 0, (short) 32767, (short) -32768, (short) 1234);
        try (PcmAudioStream s = new PcmAudioStream(pcm16le(data, 1, 44100))) {
            assertEquals(1, s.getFormat().getChannels());
            ByteBuffer chunk = s.read(8);
            short[] got = readShorts(chunk);
            assertArrayEquals(new short[]{0, 32767, -32768, 1234}, got);
        }
    }

    @Test
    void stereoAveragesIntoMono() throws Exception {
        // Frames: (L=100, R=200) → mono=150;  (L=-1000, R=1000) → 0;  (L=10, R=20) → 15
        byte[] data = samplesLe(
                (short) 100, (short) 200,
                (short) -1000, (short) 1000,
                (short) 10, (short) 20
        );
        try (PcmAudioStream s = new PcmAudioStream(pcm16le(data, 2, 44100))) {
            assertEquals(1, s.getFormat().getChannels());
            ByteBuffer chunk = s.read(64);
            short[] got = readShorts(chunk);
            assertArrayEquals(new short[]{150, 0, 15}, got);
        }
    }

    @Test
    void readsAllBytesAcrossMultipleCalls() throws Exception {
        short[] expected = new short[256];
        for (int i = 0; i < expected.length; i++) expected[i] = (short) (i * 100);
        byte[] data = samplesLe(expected);
        try (PcmAudioStream s = new PcmAudioStream(pcm16le(data, 1, 44100))) {
            short[] received = new short[expected.length];
            int idx = 0;
            while (idx < received.length) {
                ByteBuffer chunk = s.read(64);
                if (chunk.remaining() == 0) break;
                short[] part = readShorts(chunk);
                System.arraycopy(part, 0, received, idx, part.length);
                idx += part.length;
            }
            assertArrayEquals(expected, received);
        }
    }

    @Test
    void emptyBufferAtEof() throws Exception {
        try (PcmAudioStream s = new PcmAudioStream(pcm16le(samplesLe((short) 7), 1, 44100))) {
            // First read consumes the only sample
            ByteBuffer first = s.read(64);
            assertEquals(2, first.remaining());
            // Second read returns empty buffer (EOF)
            ByteBuffer second = s.read(64);
            assertEquals(0, second.remaining());
        }
    }

    @Test
    void rejectsNonCanonicalFormat() {
        AudioFormat eightBit = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, 44100, 8, 1, 1, 44100, false);
        AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(new byte[10]), eightBit, 10);
        assertThrows(IllegalArgumentException.class, () -> new PcmAudioStream(in));
    }
}
