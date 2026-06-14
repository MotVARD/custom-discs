package com.motvard.customdiscs.server;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.component.CustomDiscData;
import com.motvard.customdiscs.event.JukeboxEventHandler;
import com.motvard.customdiscs.init.ModDataComponents;
import com.motvard.customdiscs.init.ModItems;
import com.motvard.customdiscs.network.StopDiscPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class JukeboxTicker {

    private record JukeboxKey(ResourceKey<Level> dim, BlockPos pos) {}

    private record DiscSession(Set<UUID> listeners, long startedAtMs) {
        static DiscSession create() {
            return new DiscSession(ConcurrentHashMap.newKeySet(), System.currentTimeMillis());
        }
    }

    private static final Map<JukeboxKey, ItemStack> PREV_ITEM = new ConcurrentHashMap<>();
    private static final Map<JukeboxKey, DiscSession> SESSIONS = new ConcurrentHashMap<>();

    public static void tick(Level level, BlockPos pos, JukeboxBlockEntity blockEntity) {
        if (!(level instanceof ServerLevel serverLevel)) return;
        if (level.getGameTime() % 10 != 0) return;
        evaluate(serverLevel, pos, blockEntity);
    }

    /**
     * Run the per-jukebox sync logic now, ignoring the 10-tick polling gate.
     * Safe to call from {@code UseItemOnBlockEvent} (scheduled on +1 tick so the
     * block-entity item is already updated) or any other immediate trigger.
     */
    public static void tickNow(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof JukeboxBlockEntity blockEntity) {
            evaluate(level, pos, blockEntity);
        }
    }

    private static void evaluate(ServerLevel serverLevel, BlockPos pos, JukeboxBlockEntity blockEntity) {
        JukeboxKey key = new JukeboxKey(serverLevel.dimension(), pos);
        ItemStack currentItem = blockEntity.getTheItem();
        ItemStack prevItem = PREV_ITEM.getOrDefault(key, ItemStack.EMPTY);

        boolean wasDisc = !prevItem.isEmpty() && prevItem.is(ModItems.CUSTOM_MUSIC_DISC.get());
        boolean isDisc  = !currentItem.isEmpty() && currentItem.is(ModItems.CUSTOM_MUSIC_DISC.get());

        if (isDisc) {
            CustomDiscData currentData = currentItem.get(ModDataComponents.DISC_DATA.get());
            CustomDiscData prevData = wasDisc ? prevItem.get(ModDataComponents.DISC_DATA.get()) : null;

            boolean discChanged = !wasDisc || !Objects.equals(currentData, prevData);

            if (discChanged) {
                CustomDiscsMod.LOGGER.debug("[JukeboxTicker] Disc changed/appeared @ {} → {}",
                        pos, currentData != null ? currentData.title() : "?");

                DiscSession old = SESSIONS.remove(key);
                if (old != null && !old.listeners().isEmpty()) {
                    StopDiscPacket stopPkt = new StopDiscPacket(pos);
                    for (ServerPlayer p : serverLevel.players()) {
                        if (old.listeners().contains(p.getUUID())) {
                            PacketDistributor.sendToPlayer(p, stopPkt);
                        }
                    }
                }
                SESSIONS.put(key, DiscSession.create());
            }

            syncWithNearbyPlayers(serverLevel, pos, currentItem, key);

        } else if (wasDisc) {
            SESSIONS.remove(key);
            JukeboxEventHandler.onDiscRemoved(serverLevel, pos);
        }

        if (currentItem.isEmpty()) {
            PREV_ITEM.remove(key);
        } else {
            PREV_ITEM.put(key, currentItem.copy());
        }
    }

    private static void syncWithNearbyPlayers(ServerLevel level, BlockPos pos, ItemStack disc, JukeboxKey key) {
        DiscSession session = SESSIONS.get(key);
        if (session == null) {
            CustomDiscsMod.LOGGER.warn("[JukeboxTicker] Missing session for {}", pos);
            return;
        }

        double radius = Config.SERVER.broadcastRadius.get();
        double radiusSq = radius * radius;
        long now = System.currentTimeMillis();

        for (ServerPlayer player : level.players()) {
            double distSq = player.blockPosition().distSqr(pos);
            boolean inRadius = distSq <= radiusSq;
            UUID uuid = player.getUUID();

            if (inRadius && !session.listeners().contains(uuid)) {
                JukeboxEventHandler.sendToPlayer(player, pos, disc, now - session.startedAtMs());
                session.listeners().add(uuid);
            } else if (!inRadius && session.listeners().contains(uuid)) {
                session.listeners().remove(uuid);
                PacketDistributor.sendToPlayer(player, new StopDiscPacket(pos));
            }
        }
    }

    public static void invalidate(ResourceKey<Level> dim, BlockPos pos) {
        JukeboxKey key = new JukeboxKey(dim, pos);
        PREV_ITEM.remove(key);
        SESSIONS.remove(key);
    }

    /** True if a playback session is currently active for the jukebox at this position. */
    public static boolean hasSession(ResourceKey<Level> dim, BlockPos pos) {
        return SESSIONS.containsKey(new JukeboxKey(dim, pos));
    }

    /**
     * A tracked jukebox no longer exists at this position (explosion, piston, /setblock,
     * world edit — none of which fire BlockEvent.BreakEvent). Stop playback for every
     * listener and drop all tracking state.
     */
    public static void handleRemoved(ServerLevel level, BlockPos pos) {
        JukeboxKey key = new JukeboxKey(level.dimension(), pos);
        PREV_ITEM.remove(key);
        DiscSession session = SESSIONS.remove(key);

        if (session != null && !session.listeners().isEmpty()) {
            StopDiscPacket stop = new StopDiscPacket(pos);
            for (ServerPlayer p : level.players()) {
                if (session.listeners().contains(p.getUUID())) {
                    PacketDistributor.sendToPlayer(p, stop);
                }
            }
        }

        JukeboxTracker.untrack(level.dimension(), pos);
        CustomDiscsMod.LOGGER.debug("[JukeboxTicker] Jukebox removed @ {} — playback stopped", pos);
    }

    /** Clear all tick state. Called when the server is stopping to release references. */
    public static void clear() {
        PREV_ITEM.clear();
        SESSIONS.clear();
    }
}
