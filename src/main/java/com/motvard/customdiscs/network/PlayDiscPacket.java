package com.motvard.customdiscs.network;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.client.DiscPlaybackManager;
import com.motvard.customdiscs.util.CustomDiscFiles;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** {@code stamp} identifies the version of the uploaded file, see {@link CustomDiscFiles#stamp}. */
public record PlayDiscPacket(BlockPos pos, String source, String title, long startOffsetMs, String stamp)
        implements CustomPacketPayload {

    public static final Type<PlayDiscPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CustomDiscsMod.MOD_ID, "play_disc"));

    public static final StreamCodec<ByteBuf, PlayDiscPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC,        PlayDiscPacket::pos,
            ByteBufCodecs.STRING_UTF8,    PlayDiscPacket::source,
            ByteBufCodecs.STRING_UTF8,    PlayDiscPacket::title,
            ByteBufCodecs.VAR_LONG,       PlayDiscPacket::startOffsetMs,
            ByteBufCodecs.STRING_UTF8,    PlayDiscPacket::stamp,
            PlayDiscPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PlayDiscPacket packet, IPayloadContext context) {
        if (!Config.CLIENT.enableMusicDownload.get()) return;
        if (!CustomDiscFiles.isValidStamp(packet.stamp())) {
            CustomDiscsMod.LOGGER.warn("[Packet] PlayDiscPacket with invalid file stamp '{}' for {}", packet.stamp(), packet.source());
            return;
        }
        CustomDiscsMod.LOGGER.debug("[Packet] Received PlayDiscPacket: {} at {}, offset={}ms", packet.title(), packet.pos(), packet.startOffsetMs());
        context.enqueueWork(() -> DiscPlaybackManager.play(
                packet.pos(), packet.source(), packet.stamp(), packet.title(), packet.startOffsetMs()));
    }
}
