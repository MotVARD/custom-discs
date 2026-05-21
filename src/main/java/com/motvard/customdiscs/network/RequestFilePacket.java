package com.motvard.customdiscs.network;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.util.CustomDiscFiles;
import com.motvard.customdiscs.util.ModPaths;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public record RequestFilePacket(String fileName) implements CustomPacketPayload {

    private static final int CHUNK_SIZE = 256 * 1024;

    private static final ExecutorService FILE_TRANSFER_EXECUTOR = Executors.newFixedThreadPool(
            4, new ThreadFactory() {
                final AtomicInteger seq = new AtomicInteger();
                @Override public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "CustomDiscs-FileTransfer-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

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
            if (CustomDiscFiles.isSuspiciousPath(safeName)) {
                CustomDiscsMod.LOGGER.warn("[RequestFilePacket] Player {} requested suspicious path: {}",
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

            CustomDiscsMod.LOGGER.debug("[RequestFilePacket] Sending {} ({} bytes) to player {}",
                    safeName, length, player.getName().getString());
            int totalSize = (int) length;

            FILE_TRANSFER_EXECUTOR.submit(() -> sendFile(player, safeName, file, totalSize));
        });
    }

    private static void sendFile(ServerPlayer player, String safeName, File file, int totalSize) {
        long delayMs = Config.SERVER.chunkSendDelayMs.get();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int offset = 0;
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                if (player.hasDisconnected()) {
                    CustomDiscsMod.LOGGER.info("[RequestFilePacket] Player {} disconnected during transfer of {}",
                            player.getName().getString(), safeName);
                    return;
                }

                byte[] chunk = (bytesRead == buffer.length) ? buffer : Arrays.copyOf(buffer, bytesRead);
                PacketDistributor.sendToPlayer(player, new DownloadChunkPacket(safeName, chunk, offset, totalSize));
                offset += bytesRead;

                if (delayMs > 0) Thread.sleep(delayMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            CustomDiscsMod.LOGGER.error("[RequestFilePacket] Error sending {} to player {}: {}",
                    safeName, player.getName().getString(), e.getMessage());
        }
    }
}
