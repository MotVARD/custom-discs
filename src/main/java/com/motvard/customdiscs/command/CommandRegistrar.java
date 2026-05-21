package com.motvard.customdiscs.command;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static com.motvard.customdiscs.CustomDiscsMod.MOD_ID;

@EventBusSubscriber(modid = MOD_ID)
public class CommandRegistrar {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        DiscCommand.register(event.getDispatcher());
    }
}
