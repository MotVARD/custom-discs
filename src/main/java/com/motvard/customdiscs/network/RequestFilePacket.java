package com.motvard.customdiscs.network;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.server.FileTransfers;
import com.motvard.customdiscs.util.CustomDiscFiles;
import com.motvard.customdiscs.util.ModPaths;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.File;

public record RequestFilePacket(String fileName) implements CustomPacketPayload {

    public static final Type<RequestFilePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CustomDiscsMod.MOD_ID, "request_file"));

    public static final StreamCodec<FriendlyByteBuf, RequestFilePacket> STREAM_CODEC = CustomPacketPayload.codec(
            RequestFilePacket::write,
            RequestFilePacket::new
    );

    private RequestFilePacket(FriendlyByteBuf buf) { this(buf.readUtf()); }
    private void write(FriendlyByteBuf buf) { buf.writeUtf(fileName); }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().level().isClientSide) return;

            ServerPlayer player = (ServerPlayer) context.player();

            String safeName = CustomDiscFiles.sanitize(fileName);
            if (CustomDiscFiles.isSuspiciousPath(safeName) || !CustomDiscFiles.isPublicUpload(safeName)) {
                CustomDiscsMod.LOGGER.warn("[RequestFilePacket] Player {} requested non-public path: {}",
                        player.getName().getString(), fileName);
                return;
            }

            File file = ModPaths.UPLOADS_DIR.resolve(safeName).toFile();
            if (!file.exists() || !file.isFile()) {
                CustomDiscsMod.LOGGER.warn("[RequestFilePacket] Player {} requested non-existent file: {}",
                        player.getName().getString(), safeName);
                return;
            }

            long length = file.length();
            int maxSize = Config.SERVER.maxUploadBytes.get();
            if (length <= 0 || length > maxSize) {
                CustomDiscsMod.LOGGER.warn("[RequestFilePacket] File {} outside size limit ({} bytes)", safeName, length);
                return;
            }

            FileTransfers.request(player, safeName, file, (int) length);
        });
    }
}
