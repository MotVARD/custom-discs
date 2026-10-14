package com.motvard.customdiscs.server;

import com.motvard.customdiscs.Config;
import com.motvard.customdiscs.CustomDiscsMod;
import com.motvard.customdiscs.network.DownloadChunkPacket;
import io.netty.channel.Channel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streams uploaded tracks to clients. Each player gets at most {@link #MAX_ACTIVE} transfers at
 * once; further requests wait in a bounded per-player queue instead of being dropped (the client
 * would otherwise wait forever for a track it asked for, e.g. on joining near several jukeboxes).
 * A repeated request for a file that is being sent restarts it: the client only asks again when
 * it gave up on the previous download (timeout, dimension change).
 *
 * <p>Sending is paced by the connection: a transfer waits while the channel's outbound buffer is
 * full, so parallel transfers share the link (each track starts arriving at once) and a slow
 * client cannot make the server buffer whole files in memory.
 */
public final class FileTransfers {

    private static final int CHUNK_SIZE = 256 * 1024;
    private static final int MAX_ACTIVE = 4;
    /** Bounds the work a client can queue by spamming requests. */
    private static final int MAX_PENDING = 32;
    /** A repeat request restarts a transfer at most this often (clients only repeat after their
     *  own download timeout), so request spam cannot pile up restarts. */
    private static final long MIN_RESTART_INTERVAL_MS = 1_000L;

    /** Threads mostly wait on connection writability, so a few slow clients must not starve the
     *  others: sized for several players' concurrent transfers. */
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(16, new java.util.concurrent.ThreadFactory() {
        final AtomicInteger seq = new AtomicInteger();
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "CustomDiscs-FileTransfer-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    private static final class Transfer {
        final ServerPlayer player;
        final String name;
        final File file;
        final int totalSize;
        final long createdMs = System.currentTimeMillis();
        volatile boolean cancelled;

        Transfer(ServerPlayer player, String name, File file, int totalSize) {
            this.player = player;
            this.name = name;
            this.file = file;
            this.totalSize = totalSize;
        }
    }

    private static final class PlayerTransfers {
        final Map<String, Transfer> active = new HashMap<>();
        final LinkedHashMap<String, Transfer> pending = new LinkedHashMap<>();
    }

    /** Guarded by {@code FileTransfers.class}. */
    private static final Map<UUID, PlayerTransfers> TRANSFERS = new HashMap<>();

    private FileTransfers() {}

    /** Queue {@code file} for sending to {@code player}. The file has been validated by the caller. */
    public static synchronized void request(ServerPlayer player, String safeName, File file, int totalSize) {
        PlayerTransfers transfers = TRANSFERS.computeIfAbsent(player.getUUID(), k -> new PlayerTransfers());
        Transfer transfer = new Transfer(player, safeName, file, totalSize);

        Transfer running = transfers.active.get(safeName);
        if (running != null) {
            if (System.currentTimeMillis() - running.createdMs < MIN_RESTART_INTERVAL_MS) return;
            // The client dropped its download of this file and asked again: restart it in place.
            running.cancelled = true;
            start(transfers, transfer);
            return;
        }
        if (transfers.active.size() < MAX_ACTIVE) {
            start(transfers, transfer);
            return;
        }
        if (transfers.pending.containsKey(safeName)) return; // already queued
        if (transfers.pending.size() >= MAX_PENDING) {
            CustomDiscsMod.LOGGER.warn("[FileTransfers] Player {} has too many queued downloads, dropping {}",
                    player.getName().getString(), safeName);
            return;
        }
        transfers.pending.put(safeName, transfer);
    }

    /** Stop everything queued for a player who left. */
    public static synchronized void forget(UUID player) {
        PlayerTransfers transfers = TRANSFERS.remove(player);
        if (transfers == null) return;
        transfers.active.values().forEach(t -> t.cancelled = true);
        transfers.pending.clear();
    }

    private static void start(PlayerTransfers transfers, Transfer transfer) {
        transfers.active.put(transfer.name, transfer);
        CustomDiscsMod.LOGGER.debug("[FileTransfers] Sending {} ({} bytes) to {}",
                transfer.name, transfer.totalSize, transfer.player.getName().getString());
        EXECUTOR.submit(() -> {
            try {
                send(transfer);
            } finally {
                finished(transfer);
            }
        });
    }

    private static synchronized void finished(Transfer transfer) {
        UUID id = transfer.player.getUUID();
        PlayerTransfers transfers = TRANSFERS.get(id);
        // A restarted transfer was replaced under its name: its slot belongs to the new one.
        if (transfers == null || !transfers.active.remove(transfer.name, transfer)) return;
        Iterator<Transfer> next = transfers.pending.values().iterator();
        if (next.hasNext()) {
            Transfer queued = next.next();
            next.remove();
            start(transfers, queued);
        } else if (transfers.active.isEmpty()) {
            TRANSFERS.remove(id);
        }
    }

    private static void send(Transfer transfer) {
        ServerPlayer player = transfer.player;
        long delayMs = Config.SERVER.chunkSendDelayMs.get();
        Channel channel = player.connection.getConnection().channel();
        try (FileInputStream fis = new FileInputStream(transfer.file)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int offset = 0;
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                // Wait for the connection to drain what was already queued (pacing by delivery).
                while (channel != null && !channel.isWritable() && !transfer.cancelled && !player.hasDisconnected()) {
                    Thread.sleep(5L);
                }
                if (transfer.cancelled) return;
                if (player.hasDisconnected()) {
                    CustomDiscsMod.LOGGER.info("[FileTransfers] Player {} disconnected during transfer of {}",
                            player.getName().getString(), transfer.name);
                    return;
                }

                // Always copy: the packet is serialised asynchronously, so the next read()
                // must not be able to overwrite the bytes in flight.
                byte[] chunk = Arrays.copyOf(buffer, bytesRead);
                PacketDistributor.sendToPlayer(player, new DownloadChunkPacket(transfer.name, chunk, offset, transfer.totalSize));
                offset += bytesRead;

                if (delayMs > 0) Thread.sleep(delayMs);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            CustomDiscsMod.LOGGER.error("[FileTransfers] Error sending {} to player {}: {}",
                    transfer.name, player.getName().getString(), e.getMessage());
        }
    }
}
