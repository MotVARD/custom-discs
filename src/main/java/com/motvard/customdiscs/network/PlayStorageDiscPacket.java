package com.motvard.customdiscs.network;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.compat.sophisticated.client.StorageDiscPlayback;
import com.motvard.customdiscs.util.CustomDiscFiles;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

/**
 * A custom disc started in a storage jukebox (Sophisticated Backpacks jukebox upgrade).
 * {@code entityId} is the entity wearing the storage, or -1 for a placed one at {@code pos}.
 * {@code stamp} identifies the version of the uploaded file, see {@link CustomDiscFiles#stamp}.
 */
public record PlayStorageDiscPacket(UUID storageUuid, String source, String stamp, String title, Vec3 pos, int entityId)
        implements CustomPacketPayload {

    private static final StreamCodec<ByteBuf, Vec3> VEC3 = StreamCodec.of(
            (buf, v) -> {
                buf.writeDouble(v.x);
                buf.writeDouble(v.y);
                buf.writeDouble(v.z);
            },
            buf -> new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble())
    );

    public static final Type<PlayStorageDiscPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(CustomDiscsMod.MOD_ID, "play_storage_disc"));

    public static final StreamCodec<ByteBuf, PlayStorageDiscPacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC,     PlayStorageDiscPacket::storageUuid,
            ByteBufCodecs.STRING_UTF8, PlayStorageDiscPacket::source,
            ByteBufCodecs.STRING_UTF8, PlayStorageDiscPacket::stamp,
            ByteBufCodecs.STRING_UTF8, PlayStorageDiscPacket::title,
            VEC3,                      PlayStorageDiscPacket::pos,
            ByteBufCodecs.VAR_INT,     PlayStorageDiscPacket::entityId,
            PlayStorageDiscPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(PlayStorageDiscPacket packet, IPayloadContext context) {
        if (!Config.CLIENT.enableMusicDownload.get()) return;
        if (!ModList.get().isLoaded("sophisticatedcore")) return;
        if (!CustomDiscFiles.isValidStamp(packet.stamp())) {
            CustomDiscsMod.LOGGER.warn("[Packet] PlayStorageDiscPacket with invalid file stamp '{}' for {}", packet.stamp(), packet.source());
            return;
        }
        CustomDiscsMod.LOGGER.debug("[Packet] Received PlayStorageDiscPacket: {} for storage {}", packet.title(), packet.storageUuid());
        context.enqueueWork(() -> StorageDiscPlayback.play(
                packet.storageUuid(), packet.pos(), packet.entityId(),
                packet.source(), packet.stamp(), packet.title()));
    }
}
