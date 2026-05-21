package com.motvard.customdiscs.event;

import com.motvard.customdiscs.server.JukeboxTracker;
import com.motvard.customdiscs.server.JukeboxTicker;
import com.motvard.customdiscs.server.UploadSession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.Set;
import java.util.UUID;

import static com.motvard.customdiscs.CustomDiscsMod.MOD_ID;

@EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class ServerEventHandler {

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        UploadSession.cleanupOnDisconnect(id);
    }

    @SubscribeEvent
    public static void onUseItemOnBlock(UseItemOnBlockEvent event) {
        if (event.getLevel().isClientSide()) return;
        BlockPos pos = event.getPos();
        if (!event.getLevel().getBlockState(pos).is(Blocks.JUKEBOX)) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        JukeboxTracker.track(serverLevel.dimension(), pos);

        // The vanilla jukebox updates its inventory *after* this event fires, so
        // schedule the sync for the next tick — by then getTheItem() is correct.
        serverLevel.getServer().tell(new TickTask(
                serverLevel.getServer().getTickCount() + 1,
                () -> JukeboxTicker.tickNow(serverLevel, pos)
        ));
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!event.getState().is(Blocks.JUKEBOX)) return;
        BlockPos pos = event.getPos();
        if (event.getLevel() instanceof Level level) {
            var dim = level.dimension();
            JukeboxTracker.untrack(dim, pos);
            JukeboxTicker.invalidate(dim, pos);
            if (level instanceof ServerLevel serverLevel) {
                JukeboxEventHandler.onDiscRemoved(serverLevel, pos);
            }
        }
    }

    @SubscribeEvent
    public static void onServerLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        Set<BlockPos> positions = JukeboxTracker.getAll(serverLevel.dimension());
        if (positions.isEmpty()) return;

        double radius = com.motvard.customdiscs.Config.SERVER.broadcastRadius.get();
        double radiusSq = radius * radius;
        var dim = serverLevel.dimension();

        positions.forEach(pos -> {
            if (serverLevel.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox) {
                // Proximity gating: skip jukeboxes with no nearby players AND no active session.
                // Removed-disc detection still runs through session-bearing jukeboxes; chunk
                // unload / block break / orphan detection handle the cleanup cases.
                if (!JukeboxTicker.hasSession(dim, pos) && !hasPlayerWithin(serverLevel, pos, radiusSq)) {
                    return;
                }
                JukeboxTicker.tick(serverLevel, pos, jukebox);
            } else if (serverLevel.isLoaded(pos)) {
                // Chunk is loaded but the jukebox is gone (explosion, piston, /setblock, ...) —
                // BlockEvent.BreakEvent never fired, so stop playback here.
                JukeboxTicker.handleRemoved(serverLevel, pos);
            }
        });
    }

    private static boolean hasPlayerWithin(ServerLevel level, BlockPos pos, double radiusSq) {
        for (var player : level.players()) {
            if (player.blockPosition().distSqr(pos) <= radiusSq) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide()) return;
        if (event.getLevel() instanceof Level level) {
            var dim = level.dimension();
            event.getChunk().getBlockEntitiesPos().forEach(pos -> {
                if (level.getBlockState(pos).is(Blocks.JUKEBOX)) {
                    JukeboxTracker.track(dim, pos);
                }
            });
        }
    }

    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        var dim = serverLevel.dimension();
        event.getChunk().getBlockEntitiesPos().forEach(pos -> {
            if (serverLevel.getBlockState(pos).is(Blocks.JUKEBOX)) {
                JukeboxTracker.untrack(dim, pos);
                JukeboxTicker.invalidate(dim, pos);
                JukeboxEventHandler.onDiscRemoved(serverLevel, pos);
            }
        });
    }
}
