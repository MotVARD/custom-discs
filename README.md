# Custom Discs — NeoForge 1.21.1

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-orange)
![License](https://img.shields.io/badge/License-MIT-blue)

Upload your own audio files and play them in Minecraft jukeboxes.

> [Русская версия](README.ru.md)

---

## Features

- Upload `.wav`, `.mp3` or `.ogg` from the in-game screen
- 3D positional audio with distance falloff and panning; compatible with Sound Physics Remastered
- Plays while downloading — no waiting for the full file
- Synchronised playback for everyone in range
- Configurable upload size cap, total storage cap, per-player file limit, cooldown, and permission levels for each command
- Local client-side track cache with size cap

---

## Getting started

1. Install [NeoForge 21.1.x](https://neoforged.net/) for Minecraft 1.21.1
2. Drop `customdiscs-<version>.jar` into `mods/`
3. Launch the game and run `/disc upload`

---

## Commands

| Command | Description | Default permission |
|---------|-------------|--------------------|
| `/disc upload` | Open the upload screen | Everyone |
| `/disc list` | Show uploaded files with size and uploader | Operators |
| `/disc delete <filename>` | Remove an uploaded file | Operators |

---

## Configuration

`customdiscs-server.toml` — synced to clients, stored per-world under `<world>/serverconfig/`:

```toml
[network]
  # Single-upload size limit, bytes.
  maxUploadBytes = 104857600

  # Total upload folder size in MB. 0 = unlimited.
  maxTotalStorageMb = 5120

  # Delay between audio chunks while streaming to a client.
  chunkSendDelayMs = 10

[audio]
  # Distance in blocks where volume reaches zero.
  maxAudibleDistance = 64.0

  # Players outside this radius don't receive playback packets.
  broadcastRadius = 80.0

[privacy]
  # 0 = everyone, 2 = operators, 4 = console only.
  uploadPermLevel = 0
  listPermLevel   = 2
  deletePermLevel = 2

  # Seconds between uploads per player. 0 = no cooldown.
  uploadCooldownSeconds = 30

  # Max tracks owned by a single player. 0 = unlimited.
  perPlayerFileLimit = 0
```

`customdiscs-client.toml`:

```toml
[privacy]
  enableMusicDownload = true
  maxCacheSizeMb = 500

[network]
  packetDownloadTimeoutSeconds = 30
```

---

## File storage

- Server uploads: `customdiscs_uploads/`
- Client cache: `customdiscs_cache/` (LRU-evicted by `maxCacheSizeMb`)

---

## License

Mod source under MIT. The shipped JAR also bundles MP3/OGG decoder libraries
(mp3spi, jlayer, vorbisspi, tritonus-share) under GNU LGPL v2.1 — their
license and attribution are inside the JAR at `licenses/`.
