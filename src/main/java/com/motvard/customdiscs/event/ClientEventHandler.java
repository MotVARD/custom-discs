package com.motvard.customdiscs.event;

import com.motvard.customdiscs.client.DiscPlaybackManager;
import com.motvard.customdiscs.client.audio.PacketFileDownloader;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import static com.motvard.customdiscs.CustomDiscsMod.MOD_ID;

@EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class ClientEventHandler {

    @SubscribeEvent
    public static void onWorldUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) return;
        DiscPlaybackManager.stopAll();
    }

    @SubscribeEvent
    public static void onWorldLoad(LevelEvent.Load event) {
        if (!event.getLevel().isClientSide()) return;
        PacketFileDownloader.cleanCache();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (mc.level.getGameTime() % 5 == 0) {
            DiscPlaybackManager.stopOrphaned(mc.level);
        }
    }
}
