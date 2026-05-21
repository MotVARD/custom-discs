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
        this.volume = 1.0f;
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
        // Position is fixed at the jukebox — nothing to update each tick.
        // Lifecycle (stopping when finished, when jukebox is gone) is handled by
        // DiscPlaybackManager.stopOrphaned() and explicit StopDiscPacket from the server.
        if (Config.CLIENT.enableMusicDownload.get()) return;
        // Player flipped the opt-out while a track is playing — stop respectfully.
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
