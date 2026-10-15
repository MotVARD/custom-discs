package com.motvard.customdiscs.client.audio;

import org.junit.jupiter.api.Test;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies AudioStreamFactory.openAsPcm always yields the canonical format PcmAudioStream
 * accepts (16-bit signed little-endian PCM), or fails cleanly, and never returns a
 * foreign-format stream that PcmAudioStream would reject.
 */
class AudioStreamFactoryTest {

    private static boolean isCanonical(AudioFormat f) {
        return f.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                && f.getSampleSizeInBits() == 16
                && !f.isBigEndian();
    }

    private static byte[] encode(AudioFormat format, AudioFileFormat.Type type, int frames) throws IOException {
        byte[] pcm = new byte[frames * format.getFrameSize()];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i & 0xFF);
        }
        AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AudioSystem.write(ais, type, out);
        return out.toByteArray();
    }

    @Test
    void canonical16BitWavPassesThrough() throws Exception {
        AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, 2, 4, 44100f, false);
        byte[] wav = encode(fmt, AudioFileFormat.Type.WAVE, 1000);
        try (AudioInputStream pcm = AudioStreamFactory.openAsPcm(new ByteArrayInputStream(wav), "16bit-wav")) {
            assertTrue(isCanonical(pcm.getFormat()), "expected canonical 16-bit, got " + pcm.getFormat());
        }
    }

    @Test
    void eightBitWavConvertedTo16Bit() throws Exception {
        AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, 22050f, 8, 1, 1, 22050f, false);
        byte[] wav = encode(fmt, AudioFileFormat.Type.WAVE, 1000);
        try (AudioInputStream pcm = AudioStreamFactory.openAsPcm(new ByteArrayInputStream(wav), "8bit-wav")) {
            assertTrue(isCanonical(pcm.getFormat()),
                    "8-bit audio must be converted to 16-bit, got " + pcm.getFormat());
        }
    }

    @Test
    void bigEndian16BitAiffConvertedToLittleEndian() throws Exception {
        AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100f, 16, 2, 4, 44100f, true);
        byte[] aiff = encode(fmt, AudioFileFormat.Type.AIFF, 1000);
        try (AudioInputStream pcm = AudioStreamFactory.openAsPcm(new ByteArrayInputStream(aiff), "be-aiff")) {
            assertTrue(isCanonical(pcm.getFormat()),
                    "big-endian audio must be converted to little-endian 16-bit, got " + pcm.getFormat());
        }
    }

    @Test
    void twentyFourBitNeverReturnsForeignFormatSilently() throws Exception {
        AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, 44100f, 24, 1, 3, 44100f, false);
        byte[] wav;
        try {
            wav = encode(fmt, AudioFileFormat.Type.WAVE, 1000);
        } catch (IllegalArgumentException | IOException e) {
            return; // this JRE cannot author 24-bit WAV, nothing to assert
        }
        try (AudioInputStream pcm = AudioStreamFactory.openAsPcm(new ByteArrayInputStream(wav), "24bit-wav")) {
            // Either it converts cleanly to canonical 16-bit ...
            assertTrue(isCanonical(pcm.getFormat()),
                    "24-bit must be converted, never returned as-is: " + pcm.getFormat());
        } catch (UnsupportedAudioFileException e) {
            // ... or it refuses with a clean error. Both are acceptable; silently
            // returning a non-16-bit stream (the old bug) is not.
        }
    }
}
