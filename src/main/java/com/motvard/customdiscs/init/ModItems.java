package com.motvard.customdiscs.init;

import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.item.CustomMusicDiscItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, CustomDiscsMod.MOD_ID);

    public static final Supplier<CustomMusicDiscItem> CUSTOM_MUSIC_DISC = ITEMS.register("custom_music_disc",
            () -> new CustomMusicDiscItem(
                    new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)
                            // Tooltip hidden: the song description would only repeat the item name.
                            .component(DataComponents.JUKEBOX_PLAYABLE,
                                    new JukeboxPlayable(new EitherHolder<>(ModJukeboxSongs.DISC_SONG), false))
            ));
}
