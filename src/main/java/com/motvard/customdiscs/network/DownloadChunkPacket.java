package com.motvard.customdiscs.network;

import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.client.audio.PacketFileDownloader;
import com.motvard.customdiscs.util.CustomDiscFiles;
import net.minecraft.Util;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record DownloadChunkPacket(String fileName, byte[] data, int offset, int totalSize)
        implements CustomPacketPayload {

    public static final Type<DownloadChunkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CustomDiscsMod.MOD_ID, "download_chunk"));

    public static final StreamCodec<FriendlyByteBuf, DownloadChunkPacket> STREAM_CODEC = CustomPacketPayload.codec(
            DownloadChunkPacket::write,
            DownloadChunkPacket::new
    );

    private DownloadChunkPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(), buf.readByteArray(), buf.readInt(), buf.readInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(fileName);
        buf.writeByteArray(data);
        buf.writeInt(offset);
        buf.writeInt(totalSize);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext context) {
        // Validate on the network thread, then hand off to the I/O pool. We never block
        // the main client thread on chunk writing or queue backpressure.
        if (context.player() == null || !context.player().level().isClientSide) return;

        String safeName = CustomDiscFiles.sanitize(fileName);
        if (!CustomDiscFiles.isValidChunk(offset, data.length, totalSize)) {
            CustomDiscsMod.LOGGER.warn("[DownloadChunkPacket] Invalid chunk: file={} offset={} len={} total={}",
                    safeName, offset, data.length, totalSize);
            return;
        }

        Util.ioPool().execute(() -> PacketFileDownloader.acceptChunk(safeName, data, offset, totalSize));
    }
}
