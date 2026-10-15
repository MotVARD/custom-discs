# Custom Discs - NeoForge 1.21.1

![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-green)
![NeoForge](https://img.shields.io/badge/NeoForge-21.1.x-orange)
![License](https://img.shields.io/badge/License-MIT-blue)

Upload your own audio files and play them in Minecraft jukeboxes.

> [Русская версия](README.ru.md)

---

## Features

- Upload `.wav`, `.mp3` or `.ogg` from the in-game screen
- 3D positional audio; works with Sound Physics Remastered (reverb/occlusion apply on top)
- Configurable audible distance (the range at which a disc fades to silence)
- Synchronised playback for everyone in range
- Configurable upload limits, per-player file limit, cooldown, and command permissions
- Local client-side track cache with a size cap
- Plays in the Sophisticated Backpacks / Storage jukebox upgrade (Sophisticated Core 1.4.2+)

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

Two config files are generated on first run, and every option is documented
inline in the file itself:

- `customdiscs-server.toml`: upload limits, cooldown, audible distance and
  command permissions. Synced to clients, stored per-world.
- `customdiscs-client.toml`: local cache size and an opt-out for receiving music.

---

## License

Mod source under MIT. The shipped JAR also bundles MP3/OGG decoder libraries
(mp3spi, jlayer, vorbisspi, tritonus-share) under GNU LGPL v2.1; their license
and attribution are inside the JAR at `licenses/`.
