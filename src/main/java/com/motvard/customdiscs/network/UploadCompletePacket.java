package com.motvard.customdiscs.network;

import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.client.gui.UploadScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record UploadCompletePacket(boolean success, String message) implements CustomPacketPayload {

    public static final Type<UploadCompletePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CustomDiscsMod.MOD_ID, "upload_complete"));

    public static final StreamCodec<FriendlyByteBuf, UploadCompletePacket> STREAM_CODEC = CustomPacketPayload.codec(
            UploadCompletePacket::write,
            UploadCompletePacket::new
    );

    private UploadCompletePacket(FriendlyByteBuf buf) {
        this(buf.readBoolean(), buf.readUtf());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(success);
        buf.writeUtf(message);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public static void handle(UploadCompletePacket pkt, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (Minecraft.getInstance().screen instanceof UploadScreen screen) {
                screen.onServerResponse(pkt.success(), pkt.message());
            }
        });
    }
}
