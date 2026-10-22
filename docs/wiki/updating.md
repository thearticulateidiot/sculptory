# Updating Sculptory

[Home](home.md) · [Help](home.md#help)

A newer Sculptory for the same Minecraft version keeps what you saved: your editor settings, the undo history, the library and the world. This page says how to update, what carries over, what doesn't, and where the files are.

## How to update

1. Close the game. On a server, stop the server.
2. In the `mods` folder (on a Paper server, `plugins`), take out the old Sculptory jar and put the new one in. Keep only one Sculptory jar there.
3. On a server, update the server and every client that edits together. Players who don't edit need nothing. Fabric and NeoForge players each take the jar for their own loader, and on Paper the server takes the plugin.
4. Start as usual. Nothing needs converting.

`/sculptory version` names the server's build and yours. A client with a different build may not be able to open the editor on that server: see [Server setup](server-setup.md#updating).

The products have their own versions: Sculptory for Fabric, for NeoForge and for Paper are each released on their own schedule, so the newest Fabric mod and the newest Paper plugin can have different version numbers. What a client and a server must share is the network protocol, which the changelog names for each release.

## What carries over

Your editor settings, saved on your computer for every world and server:

- UI size, panel opacity, **Fade only when not hovered** and tool outline opacity
- Fly speed
- Window layouts, one for each UI size
- Which sections of the windows are open or folded
- Keys: those in the Keys window and Sculptory's keys in Minecraft's Controls
- Presets and every tool's last settings
- The rules of the [Mask](masks.md) (every game still starts with the mask off)
- Tutorial progress

On the server, or in singleplayer in your game folder and world:

- Undo history, within the server's history limits: steps older than its age limit, or over its disk limit, go first as always (see [Server configuration](config.md#history-undo-and-saving-it))
- The library's schematics and palettes, and who each one is shared with
- The server settings in `config/sculptory/server.json` (on Paper `plugins/Sculptory/server.json`); a setting a newer build adds uses its default until you set it

The world itself never depends on the mod: blocks placed with Sculptory are ordinary blocks.

## What doesn't carry over

- **Undo history across a Minecraft update.** Every step belongs to the Minecraft version it was made in. When the world moves to another Minecraft version, older steps are dropped. Undo what you want undone before you update Minecraft.
- **Going back to an older Sculptory.** An older build leaves files written by a newer one as they are and never damages them, but it may not use them, or settings it doesn't know.

## Files Sculptory can't read

A file Sculptory can't read is never deleted: it is left as it is, or copied aside first.

- A settings file that doesn't read is left alone, or copied next to itself as `<name>.bad` before a fresh one takes its place.
- A file from a newer Sculptory is left exactly as it is.
- A damaged history file still gives back its readable part; the rest is kept aside as a `.damaged` or `.corrupt` copy.
- If `server.json` can't be read at start, editing is off and the log says why; the file is not replaced.

## Where the files live

| Path | Holds |
|---|---|
| `config/sculptory/` in your game folder | Your editor settings (`editor-*.json`, `editor-mask.txt`) |
| `config/sculptory/server.json` | The server settings (in the server folder, or the game folder in singleplayer; on Paper `plugins/Sculptory/server.json`) |
| `sculptory/library/` in the server folder | The library: schematics, palettes and who may use them (in singleplayer, in the game folder; on Paper `plugins/Sculptory/library/`) |
| `<world>/sculptory/history/` | The saved undo history, one file per player |
| `sculptory/heightmaps/` in your game folder | Your own heightmap pictures |
| `options.txt` in your game folder | Sculptory's keys in Minecraft's Controls, saved by Minecraft |

Keep a copy of these folders with your backups. To take your editor settings to another game folder or computer, copy `config/sculptory/` across.

## Related

- [Server setup](server-setup.md)
- [History](history.md)
- [Troubleshooting](troubleshooting.md)
- [FAQ](faq.md)
