package com.motvard.customdiscs.init;

import com.motvard.customdiscs.CustomDiscsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.JukeboxSong;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModJukeboxSongs {

    public static final DeferredRegister<JukeboxSong> JUKEBOX_SONGS =
            DeferredRegister.create(Registries.JUKEBOX_SONG, CustomDiscsMod.MOD_ID);

    public static final DeferredHolder<JukeboxSong, JukeboxSong> DISC_SONG = JUKEBOX_SONGS.register("disc_song",
            () -> new JukeboxSong(
                    ModSounds.SILENT,
                    Component.translatable("item.customdiscs.custom_music_disc"),
                    1200.0f,
                    15
            ));
}
