package com.motvard.customdiscs.network;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.component.CustomDiscData;
import com.motvard.customdiscs.init.ModDataComponents;
import com.motvard.customdiscs.init.ModItems;
import com.motvard.customdiscs.server.UploadAttribution;
import com.motvard.customdiscs.server.UploadSession;
import com.motvard.customdiscs.util.CustomDiscFiles;
import com.motvard.customdiscs.util.ModPaths;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public record UploadChunkPacket(String fileName, String title, byte[] data, int offset, int totalSize) implements CustomPacketPayload {

    public static final Type<UploadChunkPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CustomDiscsMod.MOD_ID, "upload_chunk"));

    public static final StreamCodec<FriendlyByteBuf, UploadChunkPacket> STREAM_CODEC = CustomPacketPayload.codec(
            UploadChunkPacket::write,
            UploadChunkPacket::new
    );

    private UploadChunkPacket(FriendlyByteBuf buf) {
        this(buf.readUtf(), buf.readUtf(), buf.readByteArray(), buf.readInt(), buf.readInt());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(fileName);
        buf.writeUtf(title);
        buf.writeByteArray(data);
        buf.writeInt(offset);
        buf.writeInt(totalSize);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public void handle(IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player().level().isClientSide) return;

            ServerPlayer player = (ServerPlayer) context.player();
            String safeName = CustomDiscFiles.sanitize(fileName);

            if (!CustomDiscFiles.hasAllowedExtension(safeName)) {
                CustomDiscsMod.LOGGER.warn("Player {} tried to upload disallowed file type: {}",
                        player.getName().getString(), safeName);
                player.sendSystemMessage(Component.translatable("customdiscs.error.disallowed_type"));
                return;
            }

            int maxSize = Config.SERVER.maxUploadBytes.get();
            if (totalSize <= 0 || totalSize > maxSize) {
                CustomDiscsMod.LOGGER.warn("Player {} sent totalSize={} (limit {})",
                        player.getName().getString(), totalSize, maxSize);
                player.sendSystemMessage(Component.translatable(
                        "customdiscs.error.file_too_large", maxSize / 1024 / 1024));
                return;
            }

            if (!CustomDiscFiles.isValidChunk(offset, data.length, totalSize)) {
                CustomDiscsMod.LOGGER.warn("Player {} sent chunk with invalid offset={} len={} total={}",
                        player.getName().getString(), offset, data.length, totalSize);
                return;
            }

            if (!ModPaths.ensureDir(ModPaths.UPLOADS_DIR)) return;
            File uploadDir = ModPaths.UPLOADS_DIR.toFile();
            File partial = new File(uploadDir, safeName + ModPaths.PARTIAL_SUFFIX);
            File finalFile = new File(uploadDir, safeName);

            if (offset == 0) {
                // Deduplication: file already exists — give disc immediately, no cooldown spent.
                if (finalFile.exists() && finalFile.length() > 0) {
                    giveDisc(player, safeName);
                    player.sendSystemMessage(Component.translatable("customdiscs.info.file_exists", title));
                    PacketDistributor.sendToPlayer(player, new UploadCompletePacket(true, title));
                    return;
                }

                String uploaderName = player.getName().getString();
                UploadSession.StartResult start = UploadSession.tryStart(player.getUUID(), uploaderName, safeName, totalSize);
                switch (start) {
                    case UploadSession.StartResult.CooldownRemaining(long secs) -> {
                        player.sendSystemMessage(Component.translatable("customdiscs.error.upload_cooldown", secs));
                        return;
                    }
                    case UploadSession.StartResult.QuotaExceeded ignored -> {
                        CustomDiscsMod.LOGGER.warn("Player {} upload rejected — storage full", uploaderName);
                        player.sendSystemMessage(Component.translatable("customdiscs.error.storage_full"));
                        return;
                    }
                    case UploadSession.StartResult.PerPlayerLimitReached(int limit) -> {
                        CustomDiscsMod.LOGGER.warn("Player {} upload rejected — per-player file limit reached ({})",
                                uploaderName, limit);
                        player.sendSystemMessage(Component.translatable("customdiscs.error.upload_quota_exceeded", limit));
                        return;
                    }
                    case UploadSession.StartResult.AlreadyInProgress ignored -> {
                        CustomDiscsMod.LOGGER.warn("Player {} started a second upload of {} — ignoring",
                                uploaderName, safeName);
                        return;
                    }
                    case UploadSession.StartResult.Ok ignored -> {
                        if (partial.exists() && !partial.delete()) {
                            CustomDiscsMod.LOGGER.warn("Failed to delete leftover {}", partial.getName());
                        }
                    }
                }
            }

            try (RandomAccessFile raf = new RandomAccessFile(partial, "rw")) {
                raf.seek(offset);
                raf.write(data);

                if ((offset + data.length) >= totalSize) {
                    raf.setLength(totalSize);
                    raf.close();

                    Files.move(partial.toPath(), finalFile.toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    UploadSession.markComplete(player.getUUID(), safeName);
                    UploadAttribution.record(safeName, player.getName().getString());

                    CustomDiscsMod.LOGGER.info("File {} fully uploaded by {} ({} bytes)",
                            safeName, player.getName().getString(), totalSize);

                    giveDisc(player, safeName);
                    player.sendSystemMessage(Component.translatable("customdiscs.success.disc_created", title));
                    PacketDistributor.sendToPlayer(player, new UploadCompletePacket(true, title));
                }
            } catch (IOException e) {
                CustomDiscsMod.LOGGER.error("Error writing chunk for {}: {}", safeName, e.getMessage());
                UploadSession.markFailed(safeName);
                player.sendSystemMessage(Component.translatable("customdiscs.error.save_failed", e.getMessage()));
                PacketDistributor.sendToPlayer(player, new UploadCompletePacket(false, e.getMessage()));
            }
        });
    }

    private void giveDisc(ServerPlayer player, String safeName) {
        ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.DISC_DATA.get(), new CustomDiscData("server://" + safeName, title));
        if (!player.getInventory().add(disc)) {
            player.drop(disc, false);
        }
    }
}
