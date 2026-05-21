package com.motvard.customdiscs.client.audio;

import com.motvard.customdiscs.CustomDiscsMod;
import javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider;
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader;
import javazoom.spi.vorbis.sampled.convert.VorbisFormatConversionProvider;
import javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Opens an {@link AudioInputStream} with PCM output regardless of the source format.
 *
 * Direct instantiation of MP3/OGG readers is required because build.gradle excludes
 * META-INF/services to avoid split-package conflicts with JPMS named modules — without
 * service files, AudioSystem cannot discover the SPI providers via ServiceLoader.
 */
public final class AudioStreamFactory {

    private static final int SNIFF_LIMIT = 2 * 1024 * 1024;

    private AudioStreamFactory() {}

    public static AudioInputStream openAsPcm(InputStream raw, String sourceTag) throws IOException, UnsupportedAudioFileException {
        BufferedInputStream sniffable = (raw instanceof BufferedInputStream b) ? b : new BufferedInputStream(raw);
        sniffable.mark(SNIFF_LIMIT);

        AudioInputStream encoded = tryReaders(sniffable, sourceTag);
        AudioFormat baseFormat = encoded.getFormat();

        // TrackPlayer.applyGainInPlace only handles 16-bit signed little-endian PCM.
        // Pass through only that exact format; 8/24-bit WAV, big-endian AIFF, MP3 and
        // OGG all fall through to conversion below.
        if (isCanonicalPcm(baseFormat)) {
            return encoded;
        }

        float sampleRate = baseFormat.getSampleRate() > 0 ? baseFormat.getSampleRate() : 44100.0f;
        int channels = baseFormat.getChannels() > 0 ? baseFormat.getChannels() : 2;

        AudioFormat targetFormat = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                channels,
                channels * 2,
                sampleRate,
                false
        );

        MpegFormatConversionProvider mpegConv = new MpegFormatConversionProvider();
        if (mpegConv.isConversionSupported(targetFormat, baseFormat)) {
            return mpegConv.getAudioInputStream(targetFormat, encoded);
        }

        VorbisFormatConversionProvider vorbisConv = new VorbisFormatConversionProvider();
        if (vorbisConv.isConversionSupported(targetFormat, baseFormat)) {
            return vorbisConv.getAudioInputStream(targetFormat, encoded);
        }

        if (AudioSystem.isConversionSupported(targetFormat, baseFormat)) {
            return AudioSystem.getAudioInputStream(targetFormat, encoded);
        }

        throw new UnsupportedAudioFileException(
                "Cannot convert format to PCM: " + baseFormat + " (source: " + sourceTag + ")");
    }

    private static boolean isCanonicalPcm(AudioFormat f) {
        return f.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                && f.getSampleSizeInBits() == 16
                && !f.isBigEndian();
    }

    private static AudioInputStream tryReaders(BufferedInputStream bis, String sourceTag)
            throws IOException, UnsupportedAudioFileException {

        UnsupportedAudioFileException lastError = null;

        try {
            return new MpegAudioFileReader().getAudioInputStream(bis);
        } catch (UnsupportedAudioFileException e) {
            lastError = e;
            bis.reset();
        }

        try {
            return new VorbisAudioFileReader().getAudioInputStream(bis);
        } catch (UnsupportedAudioFileException e) {
            lastError = e;
            bis.reset();
        }

        try {
            return AudioSystem.getAudioInputStream(bis);
        } catch (UnsupportedAudioFileException e) {
            CustomDiscsMod.LOGGER.error("[AudioStreamFactory] Unrecognized format: {} (previous error: {})",
                    sourceTag, lastError != null ? lastError.getMessage() : "—");
            throw e;
        }
    }
}
