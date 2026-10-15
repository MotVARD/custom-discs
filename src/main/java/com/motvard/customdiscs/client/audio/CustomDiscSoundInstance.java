package com.motvard.customdiscs.client.audio;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.init.ModSounds;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

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
    private final String stamp;
    private final String title;
    private final long startOffsetMs;
    private final BlockPos jukeboxPos;
    /** Entity the sound follows (a worn backpack), or -1 for a fixed position. */
    private final int entityId;

    public CustomDiscSoundInstance(BlockPos pos, String source, String stamp, String title, long startOffsetMs) {
        this(Vec3.atCenterOf(pos), -1, source, stamp, title, startOffsetMs);
    }

    /**
     * A disc played by a storage jukebox (Sophisticated Backpacks): at a fixed position for a
     * placed backpack, or following {@code entityId} for a worn one.
     */
    public static CustomDiscSoundInstance forStorage(Vec3 pos, int entityId, String source, String stamp, String title) {
        return new CustomDiscSoundInstance(pos, entityId, source, stamp, title, 0L);
    }

    private CustomDiscSoundInstance(Vec3 pos, int entityId, String source, String stamp, String title, long startOffsetMs) {
        super(ModSounds.TRACK_STREAM.get(), SoundSource.RECORDS, SoundInstance.createUnseededRandom());
        this.jukeboxPos = BlockPos.containing(pos);
        this.entityId = entityId;
        this.source = source;
        this.stamp = stamp;
        this.title = title != null ? title : "";
        this.startOffsetMs = Math.max(0L, startOffsetMs);
        this.x = pos.x;
        this.y = pos.y;
        this.z = pos.z;
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

        Minecraft mc = Minecraft.getInstance();
        if (entityId >= 0 && mc.player != null && mc.player.getId() == entityId) {
            // The local player wears the backpack: play it at the listener, like Sophisticated
            // Backpacks does for its own discs, so it never pans as the camera turns.
            this.relative = true;
            this.attenuation = Attenuation.NONE;
            this.x = 0;
            this.y = 0;
            this.z = 0;
        }
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
            InputStream raw = null;
            try {
                raw = MusicSource.open(source, stamp);
                AudioInputStream pcm = AudioStreamFactory.openAsPcm(raw, source);
                if (startOffsetMs > 0) {
                    skipMillis(pcm, startOffsetMs);
                }
                return (AudioStream) new PcmAudioStream(pcm);
            } catch (Exception e) {
                CustomDiscsMod.LOGGER.error("[CustomDiscSoundInstance] Could not open {}: {}", source, e.getMessage(), e);
                // Close what was opened: a download pipe nobody reads would otherwise block its
                // download on backpressure.
                if (raw != null) {
                    try { raw.close(); } catch (IOException ignored) {}
                }
                throw new CompletionException(e);
            }
        }, Util.backgroundExecutor());
    }

    @Override
    public void tick() {
        // Lifecycle (stopping when finished or when the jukebox is gone) is handled by
        // DiscPlaybackManager.stopOrphaned() and explicit StopDiscPacket from the server, or by
        // Sophisticated Backpacks for a storage jukebox.
        if (!Config.CLIENT.enableMusicDownload.get()) {
            // Player turned the opt-out on while a track is playing, so stop it.
            stop();
            return;
        }
        if (entityId < 0 || relative) return;
        var level = Minecraft.getInstance().level;
        Entity entity = level != null ? level.getEntity(entityId) : null;
        if (entity == null || !entity.isAlive()) {
            stop();
            return;
        }
        this.x = entity.getX();
        this.y = entity.getY() + entity.getBbHeight() / 2.0;
        this.z = entity.getZ();
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
