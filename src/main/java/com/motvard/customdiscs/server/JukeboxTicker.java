package com.motvard.customdiscs.server;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.component.CustomDiscData;
import com.motvard.customdiscs.event.JukeboxEventHandler;
import com.motvard.customdiscs.init.ModDataComponents;
import com.motvard.customdiscs.init.ModItems;
import com.motvard.customdiscs.network.StopDiscPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class JukeboxTicker {

    private record JukeboxKey(ResourceKey<Level> dim, BlockPos pos) {}

    private record DiscSession(Set<UUID> listeners) {
        static DiscSession create() {
            return new DiscSession(ConcurrentHashMap.newKeySet());
        }
    }

    /** Set in the jukebox's persistent data once its custom disc has played to the end. */
    private static final String FINISHED_TAG = "customdiscs_finished";

    /** How long a disc whose file was deleted "plays" before its redstone signal drops (same as
     *  for Sophisticated storage jukeboxes). */
    private static final int MISSING_TRACK_TICKS = 20;

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
            CustomDiscData prevData    = wasDisc ? prevItem.get(ModDataComponents.DISC_DATA.get()) : null;

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

            // The vanilla song player drives the redstone signal (15 while playing). Its song is a
            // silent placeholder with a fixed upper-bound length, so end it when the real track
            // ends: an auto-jukebox then sees the signal drop exactly like with a vanilla disc.
            JukeboxSongPlayer songPlayer = blockEntity.getSongPlayer();
            CompoundTag persistent = blockEntity.getPersistentData();
            if (songPlayer.isPlaying()) {
                if (persistent.contains(FINISHED_TAG)) {
                    persistent.remove(FINISHED_TAG);
                    blockEntity.setChanged();
                }
                OptionalLong lengthMs = currentData != null ? TrackDurations.get(currentData.source()) : OptionalLong.empty();
                long playedTicks = songPlayer.getTicksSinceSongStarted();
                // Track deleted from the server (or a broken disc): nothing is sent to clients, so do
                // not hold the redstone signal for the placeholder's full length either.
                boolean missing = lengthMs.isEmpty()
                        && (currentData == null || UploadedTracks.stamp(currentData.source()).isEmpty());
                if ((lengthMs.isPresent() && playedTicks * 50L >= lengthMs.getAsLong())
                        || (missing && playedTicks >= MISSING_TRACK_TICKS)) {
                    songPlayer.stop(serverLevel, blockEntity.getBlockState());
                    persistent.putBoolean(FINISHED_TAG, true);
                    blockEntity.setChanged();
                    if (missing) {
                        // Players still hearing the deleted track (from their cache) stop now.
                        stopListeners(serverLevel, pos, SESSIONS.get(key));
                    }
                }
            } else if (discChanged && !persistent.getBoolean(FINISHED_TAG)) {
                // A disc inserted with an older version of the mod, whose placeholder song never
                // started (and so was never saved as playing): start it now so it still plays.
                blockEntity.tryForcePlaySong();
            }

            // A finished disc stays in the jukebox but must not start over for players who walk up.
            if (songPlayer.isPlaying()) {
                // Use the jukebox's own play position (persisted across save/load and advanced by
                // vanilla each tick) as the playback offset, so late joiners sync to the real position
                // and a disc resumes where it left off instead of restarting on every world reload.
                long playOffsetMs = songPlayer.getTicksSinceSongStarted() * 50L;
                syncWithNearbyPlayers(serverLevel, pos, currentItem, key, playOffsetMs);
            }

        } else if (wasDisc) {
            if (blockEntity.getPersistentData().contains(FINISHED_TAG)) {
                blockEntity.getPersistentData().remove(FINISHED_TAG);
                blockEntity.setChanged();
            }
            SESSIONS.remove(key);
            JukeboxEventHandler.onDiscRemoved(serverLevel, pos);
        }

        if (currentItem.isEmpty()) {
            PREV_ITEM.remove(key);
        } else {
            PREV_ITEM.put(key, currentItem.copy());
        }
    }

    private static void syncWithNearbyPlayers(ServerLevel level, BlockPos pos, ItemStack disc, JukeboxKey key, long playOffsetMs) {
        DiscSession session = SESSIONS.get(key);
        if (session == null) {
            CustomDiscsMod.LOGGER.warn("[JukeboxTicker] Missing session for {}", pos);
            return;
        }

        double radius = Config.effectiveBroadcastRadius();
        double radiusSq = radius * radius;

        for (ServerPlayer player : level.players()) {
            double distSq = player.blockPosition().distSqr(pos);
            boolean inRadius = distSq <= radiusSq;
            UUID uuid = player.getUUID();

            if (inRadius && !session.listeners().contains(uuid)) {
                // Only count the player as listening if the disc was really sent, so a track that
                // is uploaded again later still reaches them.
                if (JukeboxEventHandler.sendToPlayer(player, pos, disc, playOffsetMs)) {
                    session.listeners().add(uuid);
                }
            } else if (!inRadius && session.listeners().contains(uuid)) {
                session.listeners().remove(uuid);
                PacketDistributor.sendToPlayer(player, new StopDiscPacket(pos));
            }
        }
    }

    private static void stopListeners(ServerLevel level, BlockPos pos, DiscSession session) {
        if (session == null || session.listeners().isEmpty()) return;
        StopDiscPacket stop = new StopDiscPacket(pos);
        for (ServerPlayer p : level.players()) {
            if (session.listeners().contains(p.getUUID())) {
                PacketDistributor.sendToPlayer(p, stop);
            }
        }
        session.listeners().clear();
    }

    /**
     * The player's client dropped every track (logout or dimension change unloads its level), so
     * stop counting them as a listener anywhere: on return they get the disc again at the current
     * position instead of silence.
     */
    public static void forgetListener(UUID player) {
        SESSIONS.values().forEach(session -> session.listeners().remove(player));
    }

    public static void invalidate(ResourceKey<Level> dim, BlockPos pos) {
        JukeboxKey key = new JukeboxKey(dim, pos);
        PREV_ITEM.remove(key);
        SESSIONS.remove(key);
    }

    /** Drop all per-jukebox tick state. Called when the server stops so it does not leak into a
     *  later world load in the same JVM (singleplayer). */
    public static void clear() {
        PREV_ITEM.clear();
        SESSIONS.clear();
    }

    /** True if a playback session is currently active for the jukebox at this position. */
    public static boolean hasSession(ResourceKey<Level> dim, BlockPos pos) {
        return SESSIONS.containsKey(new JukeboxKey(dim, pos));
    }

    /**
     * A tracked jukebox no longer exists at this position (explosion, piston, /setblock,
     * world edit, none of which fire BlockEvent.BreakEvent). Stop playback for every
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
        CustomDiscsMod.LOGGER.debug("[JukeboxTicker] Jukebox removed @ {}, playback stopped", pos);
    }
}
