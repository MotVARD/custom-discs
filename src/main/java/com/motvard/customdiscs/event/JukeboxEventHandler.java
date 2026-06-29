package com.motvard.customdiscs.event;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.component.CustomDiscData;
import com.motvard.customdiscs.init.ModDataComponents;
import com.motvard.customdiscs.init.ModItems;
import com.motvard.customdiscs.network.PlayDiscPacket;
import com.motvard.customdiscs.network.StopDiscPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public class JukeboxEventHandler {

    private JukeboxEventHandler() {}

    public static void sendToPlayer(ServerPlayer player, BlockPos pos, ItemStack disc, long startOffsetMs) {
        if (!disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) return;
        CustomDiscData data = disc.get(ModDataComponents.DISC_DATA.get());
        if (data == null) return;

        PacketDistributor.sendToPlayer(player, new PlayDiscPacket(pos, data.source(), data.title(), startOffsetMs));
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
