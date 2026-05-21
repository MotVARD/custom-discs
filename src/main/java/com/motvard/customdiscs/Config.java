package com.motvard.customdiscs;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class Config {
    public static class Server {

        // ----- Network -----
        public final ModConfigSpec.IntValue maxUploadBytes;
        public final ModConfigSpec.IntValue maxTotalStorageMb;
        public final ModConfigSpec.IntValue chunkSendDelayMs;

        // ----- Audio -----
        public final ModConfigSpec.DoubleValue maxAudibleDistance;
        public final ModConfigSpec.DoubleValue broadcastRadius;

        // ----- Privacy -----
        public final ModConfigSpec.IntValue uploadPermLevel;
        public final ModConfigSpec.IntValue listPermLevel;
        public final ModConfigSpec.IntValue deletePermLevel;
        public final ModConfigSpec.IntValue uploadCooldownSeconds;
        public final ModConfigSpec.IntValue perPlayerFileLimit;

        public Server(ModConfigSpec.Builder builder) {
            builder.comment("Server-side settings for Custom Discs. Synced to all clients in the world.").push("network");
            maxUploadBytes = builder
                    .comment("Maximum size of a single upload in bytes. Default: 100 MB.")
                    .defineInRange("maxUploadBytes", 100 * 1024 * 1024, 1024 * 1024, Integer.MAX_VALUE);
            maxTotalStorageMb = builder
                    .comment("Maximum combined size of the server's upload folder in MB.",
                             "A new upload that would push the folder past this limit is rejected.",
                             "0 = unlimited.")
                    .defineInRange("maxTotalStorageMb", 5120, 0, 1024 * 1024);
            chunkSendDelayMs = builder
                    .comment("Pause in milliseconds between audio chunks when streaming a track to a client.",
                             "Lower = faster downloads but more bandwidth load. Higher = gentler on slow links.",
                             "0 = no pacing (saturate the connection).")
                    .defineInRange("chunkSendDelayMs", 10, 0, 500);
            builder.pop();

            builder.push("audio");
            maxAudibleDistance = builder
                    .comment("Distance (blocks) at which client-side volume drops to zero.")
                    .defineInRange("maxAudibleDistance", 64.0, 8.0, 256.0);
            broadcastRadius = builder
                    .comment("Radius (blocks) within which the server sends playback packets to a player.",
                             "Keep this >= maxAudibleDistance + ~16 so a moving player does not briefly",
                             "lose audio while crossing the threshold.")
                    .defineInRange("broadcastRadius", 80.0, 16.0, 320.0);
            builder.pop();

            builder.push("privacy");
            uploadPermLevel = builder
                    .comment("Minimum vanilla permission level (0-4) required to use /disc upload.",
                             "0 = all players, 2 = operators, 4 = server console only.")
                    .defineInRange("uploadPermLevel", 0, 0, 4);
            listPermLevel = builder
                    .comment("Minimum permission level required to use /disc list.")
                    .defineInRange("listPermLevel", 2, 0, 4);
            deletePermLevel = builder
                    .comment("Minimum permission level required to use /disc delete.")
                    .defineInRange("deletePermLevel", 2, 0, 4);
            uploadCooldownSeconds = builder
                    .comment("Cooldown in seconds between successful uploads, per player.",
                             "0 = no cooldown.")
                    .defineInRange("uploadCooldownSeconds", 30, 0, 3600);
            perPlayerFileLimit = builder
                    .comment("Maximum number of uploaded tracks a single player may own on the server.",
                             "Counted from the upload-attribution sidecar. 0 = unlimited.")
                    .defineInRange("perPlayerFileLimit", 0, 0, 1000);
            builder.pop();
        }
    }

    public static class Client {
        public final ModConfigSpec.BooleanValue enableMusicDownload;
        public final ModConfigSpec.IntValue maxCacheSizeMb;
        public final ModConfigSpec.IntValue packetDownloadTimeoutSeconds;

        public Client(ModConfigSpec.Builder builder) {
            builder.comment("Per-player client settings for Custom Discs.").push("privacy");
            enableMusicDownload = builder
                    .comment("If false, this client will not download or play music from jukeboxes.",
                             "Useful for players who do not want to receive audio from the server.")
                    .define("enableMusicDownload", true);
            maxCacheSizeMb = builder
                    .comment("Maximum size of the local music cache in MB. Oldest files are deleted first.",
                             "0 = disabled (no caching, every play downloads).")
                    .defineInRange("maxCacheSizeMb", 500, 0, 10240);
            builder.pop();

            builder.push("network");
            packetDownloadTimeoutSeconds = builder
                    .comment("How long the client waits for a track download from the server before giving up.")
                    .defineInRange("packetDownloadTimeoutSeconds", 30, 5, 300);
            builder.pop();
        }
    }

    public static final ModConfigSpec SERVER_SPEC;
    public static final Server SERVER;

    public static final ModConfigSpec CLIENT_SPEC;
    public static final Client CLIENT;

    static {
        final Pair<Server, ModConfigSpec> serverPair = new ModConfigSpec.Builder().configure(Server::new);
        SERVER_SPEC = serverPair.getRight();
        SERVER = serverPair.getLeft();

        final Pair<Client, ModConfigSpec> clientPair = new ModConfigSpec.Builder().configure(Client::new);
        CLIENT_SPEC = clientPair.getRight();
        CLIENT = clientPair.getLeft();
    }
}
