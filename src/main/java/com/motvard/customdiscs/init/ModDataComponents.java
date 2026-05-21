package com.motvard.customdiscs.init;

import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.component.CustomDiscData;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModDataComponents {

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, CustomDiscsMod.MOD_ID);

    public static final Supplier<DataComponentType<CustomDiscData>> DISC_DATA =
            DATA_COMPONENTS.register("disc_data",
                    () -> DataComponentType.<CustomDiscData>builder()
                            .persistent(CustomDiscData.CODEC)
                            .networkSynchronized(CustomDiscData.STREAM_CODEC)
                            .build()
            );
}
