package com.motvard.customdiscs.init;

import com.motvard.customdiscs.CustomDiscsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, CustomDiscsMod.MOD_ID);

    public static final Supplier<CreativeModeTab> MUSIC_TAB = CREATIVE_MODE_TABS.register("music_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.customdiscs.music_tab"))
                    .icon(() -> new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(ModItems.CUSTOM_MUSIC_DISC.get());
                    })
                    .build()
    );
}
