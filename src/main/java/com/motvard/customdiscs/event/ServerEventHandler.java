package com.motvard.customdiscs.event;

import com.motvard.customdiscs.server.FileTransfers;
import com.motvard.customdiscs.server.JukeboxTracker;
import com.motvard.customdiscs.server.JukeboxTicker;
import com.motvard.customdiscs.server.TrackDurations;
import com.motvard.customdiscs.server.UploadSession;
import net.minecraft.core.BlockPos;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.Set;
import java.util.UUID;

import static com.motvard.customdiscs.CustomDiscsMod.MOD_ID;

@EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class ServerEventHandler {

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // Measure tracks uploaded before lengths were stored, in the background.
        TrackDurations.warmUp();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        // Static state would otherwise leak into the next world loaded in this JVM (singleplayer),
        // leaving stale jukebox sessions ticking and old cooldowns/locks in place.
        JukeboxTicker.clear();
        JukeboxTracker.clear();
        UploadSession.clear();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        // Release uploads whose client stopped sending without disconnecting.
        if (event.getServer().getTickCount() % 600 == 0) {
            UploadSession.expireStale();
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        UploadSession.cleanupOnDisconnect(id);
        FileTransfers.forget(id);
        JukeboxTicker.forgetListener(id);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        // The client unloads its old level and stops every track on a dimension change.
        JukeboxTicker.forgetListener(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.Clone event) {
        // Respawning in another dimension (e.g. dying in the Nether) also gives the client a new
        // level without a PlayerChangedDimensionEvent.
        if (event.getOriginal().level().dimension() != event.getEntity().level().dimension()) {
            JukeboxTicker.forgetListener(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public static void onUseItemOnBlock(UseItemOnBlockEvent event) {
        if (event.getLevel().isClientSide()) return;
        BlockPos pos = event.getPos();
        if (!(event.getLevel().getBlockState(pos).getBlock() instanceof JukeboxBlock)) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;

        JukeboxTracker.track(serverLevel.dimension(), pos);

        // The vanilla jukebox updates its inventory after this event fires, so schedule the
        // sync for the next tick, by which point getTheItem() is correct.
        serverLevel.getServer().tell(new TickTask(
                serverLevel.getServer().getTickCount() + 1,
                () -> JukeboxTicker.tickNow(serverLevel, pos)
        ));
    }

    /**
     * Fires for any block update with neighbour notification at this position: a jukebox placed
     * by a player or /setblock, and the HAS_RECORD flip when a hopper or another mod inserts a
     * disc. Without this, a jukebox placed in an already-loaded chunk and fed by a hopper would
     * never be tracked until its chunk reloads.
     */
    @SubscribeEvent
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getState().getBlock() instanceof JukeboxBlock)) return;
        if (!(event.getLevel() instanceof ServerLevel serverLevel)) return;
        BlockPos pos = event.getPos().immutable();
        JukeboxTracker.track(serverLevel.dimension(), pos);
        serverLevel.getServer().tell(new TickTask(
                serverLevel.getServer().getTickCount() + 1,
                () -> JukeboxTicker.tickNow(serverLevel, pos)
        ));
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getState().getBlock() instanceof JukeboxBlock)) return;
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

        double radius = com.motvard.customdiscs.Config.effectiveBroadcastRadius();
        double radiusSq = radius * radius;
        var dim = serverLevel.dimension();

        positions.forEach(pos -> {
            if (serverLevel.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox) {
                // Skip jukeboxes with no nearby players and no active session, unless a disc is
                // playing: its redstone signal must still end with the track (unattended
                // auto-jukeboxes in chunk-loaded areas).
                if (!JukeboxTicker.hasSession(dim, pos) && !hasPlayerWithin(serverLevel, pos, radiusSq)
                        && !jukebox.getSongPlayer().isPlaying()) {
                    return;
                }
                JukeboxTicker.tick(serverLevel, pos, jukebox);
            } else if (serverLevel.isLoaded(pos)) {
                // Chunk is loaded but the jukebox is gone (explosion, piston, /setblock, ...) and
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
            var chunk = event.getChunk();
            // Read from the chunk itself, never level.getBlockState: the latter re-resolves the
            // chunk through ServerChunkCache and can trigger a synchronous chunk load.
            chunk.getBlockEntitiesPos().forEach(pos -> {
                if (chunk.getBlockState(pos).getBlock() instanceof JukeboxBlock) {
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
        var chunk = event.getChunk();
        // Critical: read block states from the chunk being unloaded, not from the level.
        // serverLevel.getBlockState() re-resolves the chunk and forces a synchronous load, which
        // deadlocks the server thread while it processes chunk unloads during world save/shutdown.
        chunk.getBlockEntitiesPos().forEach(pos -> {
            if (chunk.getBlockState(pos).getBlock() instanceof JukeboxBlock) {
                JukeboxTracker.untrack(dim, pos);
                JukeboxTicker.invalidate(dim, pos);
                JukeboxEventHandler.onDiscRemoved(serverLevel, pos);
            }
        });
    }
}
