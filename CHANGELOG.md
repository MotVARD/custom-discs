# Changelog

## 1.2.0

Players and the server must both update: 1.2.0 cannot connect to 1.1.x.

### Fixed
- A jukebox playing a custom disc now emits redstone power and comparator output like a
  vanilla disc, and the power drops when the track really ends, so hopper auto-jukeboxes work
  (#4). Previously the disc's placeholder song was never registered.
- Players who walk up to a playing jukebox, or rejoin, now hear the track from where it is
  instead of from the start. A finished disc no longer starts over.
- Custom discs play in the Sophisticated Backpacks / Storage jukebox upgrade, placed or worn,
  including stop, repeat and playlist advance (#3). Needs Sophisticated Core 1.4.2 or newer.
- Jukeboxes from other mods are recognised, and a jukebox placed in an already loaded chunk and
  fed by a hopper is picked up without a chunk reload.
- A track deleted with `/disc delete` and uploaded again under the same name no longer plays the
  old version from players' caches. A deleted track is no longer played: in a jukebox it stops
  (and its redstone signal drops) within about a second, and a storage jukebox moves on to the
  next disc, also when the track is deleted while playing.
- A player who logs out, changes dimension or respawns in another dimension next to a playing
  jukebox hears it again on return instead of silence.
- A track a client asked for is no longer lost when the server is busy sending others (e.g. on
  joining near several jukeboxes playing uncached discs), and a stalled download is retried
  instead of blocking that track until the world is reloaded.
- "Now Playing" is shown once when a track starts, vanilla style, instead of overwriting the
  action bar every second.

### Upload
- The upload permission (`uploadPermLevel`) is enforced on the upload itself, not only on
  `/disc upload`.
- Uploading a different file under a name that is already taken is refused instead of handing
  out the existing track.
- File names starting with a dot are refused (they could never be played or listed and
  escaped the storage quota).
- The upload screen stops and shows the reason as soon as the server refuses an upload
  (cooldown, limits, permission, name taken) or already has the file.
- Only one upload runs at a time, also when the upload screen is closed and opened again.
- An upload whose client stops sending is released after 60 seconds instead of blocking that
  file name until the player disconnects.
- The per-player file limit counts uploads by player UUID, so renaming no longer resets it.
  Uploads from before 1.2.0 still count by name.

### Server
- Track lengths are measured once on the server (in the background) and stored in
  `customdiscs_uploads/.durations.properties`.
- File transfers to a player are limited, so a client cannot flood the server with download
  requests.
