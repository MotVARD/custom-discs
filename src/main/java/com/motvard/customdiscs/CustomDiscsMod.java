package com.motvard.customdiscs;

import com.motvard.customdiscs.init.*;
import com.motvard.customdiscs.network.*;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

@Mod(CustomDiscsMod.MOD_ID)
public class CustomDiscsMod {

    public static final String MOD_ID = "customdiscs";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CustomDiscsMod(IEventBus modEventBus) {
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.SERVER, Config.SERVER_SPEC);
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);

        ModSounds.SOUNDS.register(modEventBus);
        ModJukeboxSongs.JUKEBOX_SONGS.register(modEventBus);
        ModDataComponents.DATA_COMPONENTS.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);

        modEventBus.addListener(CustomDiscsMod::registerPackets);

        LOGGER.info("Custom Discs loaded!");
    }

    private static void registerPackets(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToClient(
                PlayDiscPacket.TYPE,
                PlayDiscPacket.STREAM_CODEC,
                PlayDiscPacket::handle
        );

        registrar.playToClient(
                StopDiscPacket.TYPE,
                StopDiscPacket.STREAM_CODEC,
                StopDiscPacket::handle
        );

        registrar.playToServer(
                UploadChunkPacket.TYPE,
                UploadChunkPacket.STREAM_CODEC,
                UploadChunkPacket::handle
        );

        registrar.playToClient(
                OpenUploadScreenPacket.TYPE,
                OpenUploadScreenPacket.STREAM_CODEC,
                OpenUploadScreenPacket::handle
        );

        registrar.playToServer(
                RequestFilePacket.TYPE,
                RequestFilePacket.STREAM_CODEC,
                RequestFilePacket::handle
        );

        registrar.playToClient(
                DownloadChunkPacket.TYPE,
                DownloadChunkPacket.STREAM_CODEC,
                DownloadChunkPacket::handle
        );

        registrar.playToClient(
                UploadCompletePacket.TYPE,
                UploadCompletePacket.STREAM_CODEC,
                UploadCompletePacket::handle
        );
    }
}
