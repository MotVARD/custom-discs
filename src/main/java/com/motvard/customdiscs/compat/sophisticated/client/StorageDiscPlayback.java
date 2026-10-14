package com.motvard.customdiscs.compat.sophisticated.client;

import com.motvard.customdiscs.client.DiscPlaybackManager;
import com.motvard.customdiscs.client.audio.CustomDiscSoundInstance;
import net.minecraft.world.phys.Vec3;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.StorageSoundHandler;

import java.util.UUID;

/**
 * Client side of the Sophisticated Backpacks compat. The sound is registered under the storage
 * UUID, so Sophisticated Backpacks' own stop / next-track / cleanup handling stops it too.
 * Only loaded when sophisticatedcore is present.
 */
public final class StorageDiscPlayback {

    private StorageDiscPlayback() {}

    public static void play(UUID storageUuid, Vec3 pos, int entityId, String source, String stamp, String title) {
        StorageSoundHandler.playStorageSound(storageUuid,
                CustomDiscSoundInstance.forStorage(pos, entityId, source, stamp, title));
        DiscPlaybackManager.showNowPlaying(title);
    }
}
