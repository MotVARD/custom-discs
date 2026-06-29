package com.motvard.customdiscs.client.audio;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.init.ModSounds;
import net.minecraft.Util;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;

import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * SoundInstance wired into Minecraft's {@link SoundEngine}. Opens a server-streamed
 * audio source, converts it to canonical PCM via {@link AudioStreamFactory}, optionally
 * skips into the track to keep multiple listeners in sync, then exposes it through
 * {@link PcmAudioStream}. OpenAL handles distance attenuation, panning and occlusion.
 */
public final class CustomDiscSoundInstance extends AbstractTickableSoundInstance {

    private final String source;
    private final String title;
    private final long startOffsetMs;
    private final BlockPos jukeboxPos;

    public CustomDiscSoundInstance(BlockPos pos, String source, String title, long startOffsetMs) {
        super(ModSounds.TRACK_STREAM.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.jukeboxPos = pos.immutable();
        this.source = source;
        this.title = title != null ? title : "";
        this.startOffsetMs = Math.max(0L, startOffsetMs);
        this.x = pos.getX() + 0.5;
        this.y = pos.getY() + 0.5;
        this.z = pos.getZ() + 0.5;
        // The engine fades a LINEAR sound to silence at max(volume, 1) * attenuation_distance
        // blocks (attenuation_distance = 16 in sounds.json), so volume = maxAudibleDistance / 16
        // makes the disc fade out exactly at the configured distance. Loudness is separately
        // clamped to the Records category volume, and the disc stays an ordinary positional
        // record, so Sound Physics Remastered applies its reverb/occlusion on top.
        this.volume = (float) Math.max(1.0, Config.SERVER.maxAudibleDistance.get() / 16.0);
        this.pitch = 1.0f;
        this.attenuation = Attenuation.LINEAR;
        this.looping = false;
        this.relative = false;
    }

    public BlockPos jukeboxPos() {
        return jukeboxPos;
    }

    public String title() {
        return title;
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                InputStream raw = MusicSource.open(source);
                AudioInputStream pcm = AudioStreamFactory.openAsPcm(raw, source);
                if (startOffsetMs > 0) {
                    skipMillis(pcm, startOffsetMs);
                }
                return (AudioStream) new PcmAudioStream(pcm);
            } catch (Exception e) {
                CustomDiscsMod.LOGGER.error("[CustomDiscSoundInstance] Could not open {}: {}", source, e.getMessage(), e);
                throw new CompletionException(e);
            }
        }, Util.backgroundExecutor());
    }

    @Override
    public void tick() {
        // Position is fixed at the jukebox, so there is nothing to update each tick. Lifecycle
        // (stopping when finished or when the jukebox is gone) is handled by
        // DiscPlaybackManager.stopOrphaned() and explicit StopDiscPacket from the server.
        if (Config.CLIENT.enableMusicDownload.get()) return;
        // Player turned the opt-out on while a track is playing, so stop it.
        stop();
    }

    /** Drop {@code millis} of audio from the start of the stream to keep listeners in sync. */
    private static void skipMillis(AudioInputStream pcm, long millis) throws IOException {
        float sampleRate = pcm.getFormat().getSampleRate();
        int frameSize = pcm.getFormat().getFrameSize();
        if (sampleRate <= 0 || frameSize <= 0) return;

        long bytesToSkip = (long) (millis / 1000.0 * sampleRate) * frameSize;
        byte[] buf = new byte[65536];
        while (bytesToSkip > 0) {
            int n = pcm.read(buf, 0, (int) Math.min(buf.length, bytesToSkip));
            if (n <= 0) return;
            bytesToSkip -= n;
        }
    }
}
