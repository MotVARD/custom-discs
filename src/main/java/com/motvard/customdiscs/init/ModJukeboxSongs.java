package com.motvard.customdiscs.init;

import com.motvard.customdiscs.CustomDiscsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.JukeboxSong;

public class ModJukeboxSongs {

    /**
     * jukebox_song is a datapack registry, so the song itself is defined in
     * data/customdiscs/jukebox_song/disc_song.json; a DeferredRegister entry would silently
     * never be registered. The vanilla jukebox uses it to emit redstone power and comparator
     * output while a custom disc plays; JukeboxTicker stops it when the real track ends.
     */
    public static final ResourceKey<JukeboxSong> DISC_SONG = ResourceKey.create(Registries.JUKEBOX_SONG,
            ResourceLocation.fromNamespaceAndPath(CustomDiscsMod.MOD_ID, "disc_song"));
}
