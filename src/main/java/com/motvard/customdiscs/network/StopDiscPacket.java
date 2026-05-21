package com.motvard.customdiscs.network;

import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.client.DiscPlaybackManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record StopDiscPacket(BlockPos pos) implements CustomPacketPayload {

    public static final Type<StopDiscPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CustomDiscsMod.MOD_ID, "stop_disc"));

    public static final StreamCodec<ByteBuf, StopDiscPacket> STREAM_CODEC =
            BlockPos.STREAM_CODEC.map(StopDiscPacket::new, StopDiscPacket::pos);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(StopDiscPacket packet, IPayloadContext context) {
        context.enqueueWork(() -> DiscPlaybackManager.stop(packet.pos()));
    }
}
