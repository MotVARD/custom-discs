package com.motvard.customdiscs.client;

import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.client.audio.CustomDiscSoundInstance;
import com.motvard.customdiscs.client.audio.PacketFileDownloader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.JukeboxBlock;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side registry of active sound instances per jukebox. Playback is handed to
 * Minecraft's {@link SoundManager}, so OpenAL handles 3D positioning, distance attenuation,
 * panning and occlusion.
 */
public class DiscPlaybackManager {

    private static final Map<BlockPos, CustomDiscSoundInstance> ACTIVE = new ConcurrentHashMap<>();

    private DiscPlaybackManager() {}

    public static void play(BlockPos pos, String source, String stamp, String title, long startOffsetMs) {
        CustomDiscSoundInstance instance = new CustomDiscSoundInstance(pos, source, stamp, title, startOffsetMs);

        CustomDiscSoundInstance previous = ACTIVE.put(pos.immutable(), instance);
        if (previous != null) {
            Minecraft.getInstance().getSoundManager().stop(previous);
        }

        CustomDiscsMod.LOGGER.debug("[DiscPlaybackManager] Start: '{}' @ {} (offset={}ms)", title, pos, startOffsetMs);
        Minecraft.getInstance().getSoundManager().play(instance);
        showNowPlaying(instance.title());
    }

    /**
     * Vanilla-style "Now Playing" overlay, shown once when a track starts for this player
     * (including on walking into range of a playing jukebox) instead of repeating every second.
     * It also replaces the generic "Custom Music Disc" vanilla shows for the placeholder song.
     */
    public static void showNowPlaying(String title) {
        if (title == null || title.isEmpty()) return;
        Minecraft.getInstance().gui.setNowPlaying(Component.literal(title));
    }

    public static void stop(BlockPos pos) {
        CustomDiscSoundInstance instance = ACTIVE.remove(pos.immutable());
        if (instance != null) {
            CustomDiscsMod.LOGGER.debug("[DiscPlaybackManager] Stop @ {}", pos);
            Minecraft.getInstance().getSoundManager().stop(instance);
        }
    }

    public static void stopAll() {
        // Fail in-flight downloads before stopping sounds. Otherwise SoundEngine.destroy() can
        // deadlock waiting on a streaming read() that nobody will ever satisfy.
        PacketFileDownloader.cancelAll();
        SoundManager mgr = Minecraft.getInstance().getSoundManager();
        for (CustomDiscSoundInstance instance : ACTIVE.values()) {
            mgr.stop(instance);
        }
        ACTIVE.clear();
        CustomDiscsMod.LOGGER.info("[DiscPlaybackManager] All tracks stopped");
    }

    /**
     * Client-side safety net: stop any track whose jukebox no longer exists, plus drop
     * already-completed instances from the table so the map does not grow without bound.
     */
    public static void stopOrphaned(ClientLevel level) {
        SoundManager mgr = Minecraft.getInstance().getSoundManager();
        ACTIVE.entrySet().removeIf(e -> {
            BlockPos pos = e.getKey();
            CustomDiscSoundInstance instance = e.getValue();
            if (!mgr.isActive(instance)) {
                return true; // finished naturally
            }
            if (level.isLoaded(pos) && !(level.getBlockState(pos).getBlock() instanceof JukeboxBlock)) {
                CustomDiscsMod.LOGGER.debug("[DiscPlaybackManager] Jukebox gone @ {}, stopping orphaned track", pos);
                mgr.stop(instance);
                return true;
            }
            return false;
        });
    }
}
