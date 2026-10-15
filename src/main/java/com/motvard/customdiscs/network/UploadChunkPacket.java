package com.motvard.customdiscs.network;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.component.CustomDiscData;
import com.motvard.customdiscs.init.ModDataComponents;
import com.motvard.customdiscs.init.ModItems;
import com.motvard.customdiscs.server.UploadAttribution;
import com.motvard.customdiscs.server.TrackDurations;
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

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
            String uploaderName = player.getName().getString();
            String safeName = CustomDiscFiles.sanitize(fileName);

            // /disc upload only gates opening the screen; a modified client can send chunks
            // directly, so the permission must be enforced on the packet itself.
            if (!player.hasPermissions(Config.SERVER.uploadPermLevel.get())) {
                if (offset == 0) {
                    CustomDiscsMod.LOGGER.warn("Player {} sent an upload chunk without upload permission", uploaderName);
                }
                reject(player, Component.translatable("customdiscs.error.no_permission"), "no permission");
                return;
            }

            // isPublicUpload also refuses dotfiles and .partial names: such a file could never be
            // played or listed, and would not count towards the storage quota.
            if (CustomDiscFiles.isSuspiciousPath(safeName) || !CustomDiscFiles.isPublicUpload(safeName)) {
                if (offset == 0) {
                    CustomDiscsMod.LOGGER.warn("Player {} tried to upload disallowed name: {}", uploaderName, fileName);
                }
                reject(player, Component.translatable("customdiscs.error.disallowed_type"), "type not allowed");
                return;
            }

            int maxSize = Config.SERVER.maxUploadBytes.get();
            if (totalSize <= 0 || totalSize > maxSize) {
                if (offset == 0) {
                    CustomDiscsMod.LOGGER.warn("Player {} sent totalSize={} (limit {})", uploaderName, totalSize, maxSize);
                }
                reject(player, Component.translatable("customdiscs.error.file_too_large", maxSize / 1024 / 1024), "too large");
                return;
            }

            if (!CustomDiscFiles.isValidChunk(offset, data.length, totalSize)) {
                CustomDiscsMod.LOGGER.warn("Player {} sent chunk with invalid offset={} len={} total={}",
                        uploaderName, offset, data.length, totalSize);
                return;
            }

            if (!ModPaths.ensureDir(ModPaths.UPLOADS_DIR)) return;
            File uploadDir = ModPaths.UPLOADS_DIR.toFile();
            File partial = new File(uploadDir, UploadSession.partialName(safeName, player.getUUID()));
            File finalFile = new File(uploadDir, safeName);

            if (offset == 0) {
                // Deduplication: the same file already exists, give disc immediately, no cooldown
                // spent. A different file under a taken name (different size or first chunk) must
                // not silently get the old track.
                if (finalFile.exists() && finalFile.length() > 0) {
                    if (finalFile.length() != totalSize || !startsWith(finalFile, data)) {
                        reject(player, Component.translatable("customdiscs.error.name_taken", safeName), "name taken");
                        return;
                    }
                    giveDisc(player, safeName);
                    player.sendSystemMessage(Component.translatable("customdiscs.info.file_exists", title));
                    PacketDistributor.sendToPlayer(player, new UploadCompletePacket(true, title));
                    return;
                }

                UploadSession.StartResult start = UploadSession.tryStart(player.getUUID(), uploaderName, safeName, totalSize);
                switch (start) {
                    case UploadSession.StartResult.CooldownRemaining(long secs) -> {
                        reject(player, Component.translatable("customdiscs.error.upload_cooldown", secs), "cooldown");
                        return;
                    }
                    case UploadSession.StartResult.QuotaExceeded ignored -> {
                        CustomDiscsMod.LOGGER.warn("Player {} upload rejected, storage full", uploaderName);
                        reject(player, Component.translatable("customdiscs.error.storage_full"), "storage full");
                        return;
                    }
                    case UploadSession.StartResult.PerPlayerLimitReached(int limit) -> {
                        CustomDiscsMod.LOGGER.warn("Player {} upload rejected, per-player file limit reached ({})",
                                uploaderName, limit);
                        reject(player, Component.translatable("customdiscs.error.upload_quota_exceeded", limit), "file limit");
                        return;
                    }
                    case UploadSession.StartResult.AlreadyInProgress ignored -> {
                        CustomDiscsMod.LOGGER.warn("Player {} started a second upload of {}, ignoring", uploaderName, safeName);
                        reject(player, Component.translatable("customdiscs.error.upload_in_progress", safeName), "in progress");
                        return;
                    }
                    case UploadSession.StartResult.Ok ignored -> {
                        if (partial.exists() && !partial.delete()) {
                            CustomDiscsMod.LOGGER.warn("Failed to delete leftover {}", partial.getName());
                        }
                    }
                }
            } else if (!UploadSession.touch(player.getUUID(), safeName)) {
                // No session was started for this name by this player: a later chunk must not
                // be able to write into (or finalize) an upload that skipped tryStart. Chunks the
                // client sent before it saw a rejection end up here, so this is not worth a warning.
                CustomDiscsMod.LOGGER.debug("Player {} sent chunk for {} without an active upload session", uploaderName, safeName);
                return;
            }

            boolean complete = (offset + data.length) >= totalSize;
            try (RandomAccessFile raf = new RandomAccessFile(partial, "rw")) {
                raf.seek(offset);
                raf.write(data);
                if (complete) raf.setLength(totalSize);
            } catch (IOException e) {
                CustomDiscsMod.LOGGER.error("Error writing chunk for {}: {}", safeName, e.getMessage());
                UploadSession.markFailed(safeName);
                partial.delete();
                player.sendSystemMessage(Component.translatable("customdiscs.error.save_failed", e.getMessage()));
                PacketDistributor.sendToPlayer(player, new UploadCompletePacket(false, e.getMessage()));
                return;
            }

            if (!complete) return;

            // Final chunk written and the file handle is closed: re-check the storage quota
            // (it could have filled since tryStart) before promoting the partial to a real file.
            if (UploadSession.exceedsStorageQuota(totalSize)) {
                CustomDiscsMod.LOGGER.warn("Player {} upload rejected at finalize, storage full", uploaderName);
                UploadSession.markFailed(safeName);
                partial.delete();
                player.sendSystemMessage(Component.translatable("customdiscs.error.storage_full"));
                PacketDistributor.sendToPlayer(player, new UploadCompletePacket(false, "storage full"));
                return;
            }

            try {
                moveIntoPlace(partial.toPath(), finalFile.toPath());
                UploadSession.markComplete(player.getUUID(), safeName);
                UploadAttribution.record(safeName, player.getUUID(), uploaderName);
                // Measure the new track now, in the background, so its first play knows its length.
                TrackDurations.invalidate(safeName);
                TrackDurations.durationMs("server://" + safeName);

                CustomDiscsMod.LOGGER.info("File {} fully uploaded by {} ({} bytes)", safeName, uploaderName, totalSize);

                giveDisc(player, safeName);
                player.sendSystemMessage(Component.translatable("customdiscs.success.disc_created", title));
                PacketDistributor.sendToPlayer(player, new UploadCompletePacket(true, title));
            } catch (IOException e) {
                CustomDiscsMod.LOGGER.error("Error finalising {}: {}", safeName, e.getMessage());
                UploadSession.markFailed(safeName);
                partial.delete();
                player.sendSystemMessage(Component.translatable("customdiscs.error.save_failed", e.getMessage()));
                PacketDistributor.sendToPlayer(player, new UploadCompletePacket(false, e.getMessage()));
            }
        });
    }

    /**
     * Tell the player why the upload was refused and end it on their client. Only for the first
     * chunk: the client stops sending on this reply, and later chunks that were already in
     * flight must not repeat the message once each.
     */
    private void reject(ServerPlayer player, Component message, String reason) {
        if (offset != 0) return;
        player.sendSystemMessage(message);
        PacketDistributor.sendToPlayer(player, new UploadCompletePacket(false, reason));
    }

    /** True if {@code file} begins with exactly the bytes of {@code head}. */
    private static boolean startsWith(File file, byte[] head) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (raf.length() < head.length) return false;
            byte[] existing = new byte[head.length];
            raf.readFully(existing);
            return Arrays.equals(existing, head);
        } catch (IOException e) {
            return false;
        }
    }

    /** Atomic move where the filesystem allows it, otherwise a plain replace. */
    private static void moveIntoPlace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void giveDisc(ServerPlayer player, String safeName) {
        ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.DISC_DATA.get(), new CustomDiscData("server://" + safeName, title));
        if (!player.getInventory().add(disc)) {
            player.drop(disc, false);
        }
    }
}
