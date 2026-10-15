package com.motvard.customdiscs.network;

import com.motvard.customdiscs.CustomDiscsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record OpenUploadScreenPacket() implements CustomPacketPayload {

    public static final Type<OpenUploadScreenPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(CustomDiscsMod.MOD_ID, "open_upload_screen"));

    public static final StreamCodec<FriendlyByteBuf, OpenUploadScreenPacket> STREAM_CODEC = CustomPacketPayload.codec(
            (buf, payload) -> {},
            (buf) -> new OpenUploadScreenPacket()
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public void handle(IPayloadContext context) {
        ClientPacketHandler.handleOpenUploadScreen(this, context);
    }
}
