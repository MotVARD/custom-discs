package com.motvard.customdiscs.compat.sophisticated;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.component.CustomDiscData;
import com.motvard.customdiscs.init.ModDataComponents;
import com.motvard.customdiscs.init.ModItems;
import com.motvard.customdiscs.network.PlayStorageDiscPacket;
import com.motvard.customdiscs.server.TrackDurations;
import com.motvard.customdiscs.server.UploadedTracks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.p3pp3rf1y.sophisticatedcore.api.IDiscHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.ServerStorageSoundHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.StopDiscPlaybackPayload;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plays custom discs in the Sophisticated Backpacks / Storage jukebox upgrade.
 *
 * <p>Sophisticated's own vanilla handler would accept the disc (it has the jukebox_playable
 * component) but can only play the silent placeholder song for its fixed length. This handler
 * instead registers the playback with {@link ServerStorageSoundHandler#putSoundInfo} using the
 * track's real length, so Sophisticated keeps handling keep-alive, stop and playlist advance,
 * and streams the actual audio to nearby clients.
 */
final class CustomDiscHandler implements IDiscHandler<CustomDiscData> {

    /** Used until the background measurement of a never-played track finishes. */
    private static final long FALLBACK_LENGTH_MS = 10 * 60 * 1000L;

    /** How long a disc whose file was deleted "plays" before the playlist advances. */
    private static final int MISSING_TRACK_TICKS = 20;

    /** A custom disc playing in a storage. Identity marks "still the storage's current track".
     *  {@code entityId} is the wearer of a worn storage, or -1. */
    private record Playback(String source, ServerLevel level, Vec3 pos, int entityId, Runnable onFinished, long finishTick) {}

    /** Latest playback per storage: a late length measurement must not touch a newer one, and a
     *  track deleted while playing must be ended (see {@link #onServerTick}). */
    private final Map<UUID, Playback> currentPlayback = new ConcurrentHashMap<>();

    @Override
    public boolean supports(ItemStack stack) {
        return stack.is(ModItems.CUSTOM_MUSIC_DISC.get()) && stack.has(ModDataComponents.DISC_DATA.get());
    }

    @Override
    public Optional<CustomDiscData> getSongInfo(ItemStack stack, Level level) {
        return Optional.ofNullable(stack.get(ModDataComponents.DISC_DATA.get()));
    }

    @Override
    public Optional<Integer> getMusicLengthInTicks(ItemStack stack, Level level) {
        return getSongInfo(stack, level).flatMap(data -> {
            OptionalLong ms = TrackDurations.get(data.source());
            return ms.isPresent() ? Optional.of(toTicks(ms.getAsLong())) : Optional.empty();
        });
    }

    @Override
    public void playDisc(ServerLevel level, BlockPos pos, UUID storageUuid, ItemStack disc, Runnable onFinished) {
        play(level, Vec3.atCenterOf(pos), storageUuid, disc, -1, onFinished);
    }

    @Override
    public void playDisc(ServerLevel level, Vec3 pos, UUID storageUuid, ItemStack disc, int entityId, Runnable onFinished) {
        play(level, pos, storageUuid, disc, entityId, onFinished);
    }

    @Override
    public Optional<ItemStack> getRandomDisc(RandomSource random) {
        return Optional.empty();
    }

    @Override
    public int getMusicDiscSize() {
        return 0;
    }

    private void play(ServerLevel level, Vec3 pos, UUID storageUuid, ItemStack disc, int entityId, Runnable onFinished) {
        CustomDiscData data = disc.get(ModDataComponents.DISC_DATA.get());
        if (data == null) return;

        long startTick = level.getGameTime();

        Optional<String> stamp = UploadedTracks.stamp(data.source());
        if (stamp.isEmpty()) {
            // Track deleted from the server: play nothing and let the playlist move on shortly.
            currentPlayback.remove(storageUuid);
            ServerStorageSoundHandler.putSoundInfo(level, storageUuid, onFinished, pos, startTick + MISSING_TRACK_TICKS);
            return;
        }

        OptionalLong known = TrackDurations.get(data.source());
        long finishTick = startTick + toTicks(known.orElse(FALLBACK_LENGTH_MS));
        Playback playback = new Playback(data.source(), level, pos, entityId, onFinished, finishTick);
        currentPlayback.put(storageUuid, playback);
        ServerStorageSoundHandler.putSoundInfo(level, storageUuid, onFinished, pos, finishTick);

        if (known.isEmpty()) {
            CompletableFuture<Long> measuring = TrackDurations.durationMs(data.source());
            if (measuring != null) {
                measuring.thenAccept(ms -> level.getServer().execute(() -> {
                    // Only correct the finish time if this is still the storage's current track.
                    Playback measured = new Playback(data.source(), level, pos, entityId, onFinished, startTick + toTicks(ms));
                    if (!currentPlayback.replace(storageUuid, playback, measured)) return;
                    ServerStorageSoundHandler.putSoundInfo(level, storageUuid, onFinished, pos, measured.finishTick());
                }));
            }
        }

        PlayStorageDiscPacket packet = new PlayStorageDiscPacket(storageUuid, data.source(), stamp.get(), data.title(), pos, entityId);
        double radius = Config.effectiveBroadcastRadius();
        double radiusSq = radius * radius;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(pos) <= radiusSq) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
    }

    /**
     * Once a second: end storage playbacks whose track was deleted from the server meanwhile
     * (stop the audio for nearby players, then let the playlist advance), and forget playbacks
     * that have finished.
     */
    void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 0 || currentPlayback.isEmpty()) return;
        currentPlayback.forEach((uuid, playback) -> {
            ServerLevel level = playback.level();
            if (level.getServer() != event.getServer() || level.getGameTime() > playback.finishTick() + 40) {
                // Finished, or left over from a server that has stopped (singleplayer world switch).
                currentPlayback.remove(uuid, playback);
                return;
            }
            if (UploadedTracks.stamp(playback.source()).isPresent()) return;
            if (!currentPlayback.remove(uuid, playback)) return;
            // Where the storage is now: a worn backpack has moved since the track started.
            Entity wearer = playback.entityId() >= 0 ? level.getEntity(playback.entityId()) : null;
            Vec3 at = wearer != null ? wearer.position() : playback.pos();
            // Only act if Sophisticated still plays it (the user may have stopped it already);
            // this keep-alive is refreshed by the storage itself every few ticks anyway.
            boolean[] stillPlaying = {true};
            ServerStorageSoundHandler.updateKeepAlive(uuid, level, at, () -> stillPlaying[0] = false);
            if (!stillPlaying[0]) return;
            ServerStorageSoundHandler.stopPlayingDisc(level, at, uuid);
            // Our play packet may have reached players beyond Sophisticated's 128-block stop
            // radius (broadcastRadius goes up to 320); the stop is a no-op for anyone else.
            PacketDistributor.sendToPlayersInDimension(level, new StopDiscPlaybackPayload(uuid));
            ServerStorageSoundHandler.putSoundInfo(level, uuid, playback.onFinished(), at, level.getGameTime() + 1);
        });
    }

    /** The storage started a disc handled by another handler: it no longer plays ours. */
    void forget(UUID storageUuid) {
        currentPlayback.remove(storageUuid);
    }

    /** The server is stopping: drop everything (records hold its levels and callbacks). */
    void clear() {
        currentPlayback.clear();
    }

    private static int toTicks(long millis) {
        return (int) Math.min(Integer.MAX_VALUE, (millis + 49) / 50);
    }
}
