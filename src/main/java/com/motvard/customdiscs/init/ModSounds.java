package com.motvard.customdiscs.init;

import com.motvard.customdiscs.CustomDiscsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, CustomDiscsMod.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> SILENT = registerSound("silent");
    public static final DeferredHolder<SoundEvent, SoundEvent> TRACK_STREAM = registerSound("track_stream");

    private static DeferredHolder<SoundEvent, SoundEvent> registerSound(String name) {
        return SOUNDS.register(name, () ->
                SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(CustomDiscsMod.MOD_ID, name))
        );
    }
}
