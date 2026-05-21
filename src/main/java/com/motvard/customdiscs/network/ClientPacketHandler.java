package com.motvard.customdiscs.network;

import com.motvard.customdiscs.client.gui.UploadScreen;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public class ClientPacketHandler {

    public static void handleOpenUploadScreen(OpenUploadScreenPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft.getInstance().setScreen(new UploadScreen());
        });
    }
}
