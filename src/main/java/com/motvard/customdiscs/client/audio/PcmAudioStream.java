package com.motvard.customdiscs.client.audio;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Adapter from a canonical 16-bit signed little-endian PCM {@link AudioInputStream}
 * (the format {@link AudioStreamFactory#openAsPcm} produces) into Minecraft's
 * {@link AudioStream}. Always emits mono — stereo input is downmixed by averaging
 * the left and right samples, because OpenAL only does 3D positioning on mono sources.
 */
public final class PcmAudioStream implements AudioStream {

    private final AudioInputStream pcm;
    private final AudioFormat outputFormat;
    private final int srcChannels;
    private final int srcFrameSize;
    private boolean closed;

    public PcmAudioStream(AudioInputStream pcm) {
        this.pcm = pcm;
        AudioFormat in = pcm.getFormat();
        if (in.getSampleSizeInBits() != 16 || in.isBigEndian()
                || in.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) {
            throw new IllegalArgumentException("Expected 16-bit signed LE PCM, got " + in);
        }
        this.srcChannels = in.getChannels();
        this.srcFrameSize = srcChannels * 2;
        this.outputFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                in.getSampleRate(),
                16,
                1,                     // always mono out
                2,                     // mono frame size = 2 bytes
                in.getSampleRate(),
                false                  // little-endian
        );
    }

    @Override
    public AudioFormat getFormat() {
        return outputFormat;
    }

    @Override
    public ByteBuffer read(int requestedBytes) throws IOException {
        if (closed) return emptyBuffer();

        // Mono output frame = 2 bytes. Round request down to whole frames.
        int outBytes = requestedBytes - (requestedBytes & 1);
        if (outBytes <= 0) return emptyBuffer();

        int outFrames = outBytes / 2;
        int srcBytes = outFrames * srcFrameSize;
        byte[] srcBuf = new byte[srcBytes];

        int totalRead = 0;
        while (totalRead < srcBytes) {
            int n = pcm.read(srcBuf, totalRead, srcBytes - totalRead);
            if (n <= 0) break;
            totalRead += n;
        }

        int srcFrames = totalRead / srcFrameSize;
        if (srcFrames == 0) return emptyBuffer();

        int actualOutBytes = srcFrames * 2;
        ByteBuffer out = ByteBuffer.allocateDirect(actualOutBytes).order(ByteOrder.LITTLE_ENDIAN);

        if (srcChannels == 1) {
            out.put(srcBuf, 0, actualOutBytes);
        } else {
            for (int i = 0; i < srcFrames; i++) {
                int p = i * srcFrameSize;
                int leftLo  = srcBuf[p]     & 0xFF;
                int leftHi  = srcBuf[p + 1];                 // signed → sign-extends on shift
                int rightLo = srcBuf[p + 2] & 0xFF;
                int rightHi = srcBuf[p + 3];
                int left  = (leftHi  << 8) | leftLo;
                int right = (rightHi << 8) | rightLo;
                int mono  = (left + right) >> 1;             // avg of two int16 always fits
                out.put((byte) (mono & 0xFF));
                out.put((byte) ((mono >> 8) & 0xFF));
            }
        }

        out.flip();
        return out;
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        pcm.close();
    }

    private static ByteBuffer emptyBuffer() {
        return ByteBuffer.allocate(0).order(ByteOrder.LITTLE_ENDIAN);
    }
}
