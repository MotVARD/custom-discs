package com.motvard.customdiscs.event;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.component.CustomDiscData;
import com.motvard.customdiscs.init.ModDataComponents;
import com.motvard.customdiscs.init.ModItems;
import com.motvard.customdiscs.network.PlayDiscPacket;
import com.motvard.customdiscs.network.StopDiscPacket;
import com.motvard.customdiscs.server.UploadedTracks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;

public class JukeboxEventHandler {

    private JukeboxEventHandler() {}

    /** Start the disc for one player. Returns false if nothing was sent (e.g. the track was deleted). */
    public static boolean sendToPlayer(ServerPlayer player, BlockPos pos, ItemStack disc, long startOffsetMs) {
        if (!disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) return false;
        CustomDiscData data = disc.get(ModDataComponents.DISC_DATA.get());
        if (data == null) return false;

        // A deleted track must not keep playing from clients' caches.
        Optional<String> stamp = UploadedTracks.stamp(data.source());
        if (stamp.isEmpty()) return false;
        PacketDistributor.sendToPlayer(player, new PlayDiscPacket(pos, data.source(), data.title(), startOffsetMs, stamp.get()));
        return true;
    }

    public static void onDiscRemoved(ServerLevel level, BlockPos pos) {
        double radius = Config.effectiveBroadcastRadius();
        double radiusSq = radius * radius;
        StopDiscPacket packet = new StopDiscPacket(pos);
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= radiusSq) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
    }
}
