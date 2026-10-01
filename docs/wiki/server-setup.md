# Server setup

[Home](home.md) · [Run a server](home.md#run-a-server)

For server admins: install the mod, decide who may edit, and tune the limits. This page is the short version; [Server configuration](config.md) has every setting, command and limit.

## Install

1. Set up a Fabric server for Minecraft 1.21.1 (Loader 0.16.14 or newer).
2. Put Fabric API and the Sculptory jar in its `mods` folder and start it.
3. Players who want to edit install the same Sculptory build; everyone else joins as usual, with nothing installed.
4. Only operators of level 2 or higher can edit by default: give others [permissions](permissions.md).

To check, join and press `B`: the status dot should read **Connected**. `/sculptory version` names the server's build and every player's.

## Upgrading from Builder Suite

Sculptory used to be called Builder Suite. On its first start it moves the old `buildersuite` folders (config, library, each world's history) to their `sculptory` names; an existing `sculptory` folder is never overwritten.

- Grants under `buildersuite.*` don't carry over: grant them again as `sculptory.*`.
- Players' rebinds of Sculptory's keys in Controls reset to the defaults.
- Close any other instance still running an old build on the same library before the first start.
- If a folder can't be moved, the old one is used where it is and the next start tries again; the log says so.

## Settings

`config/sculptory/server.json` is written on first start. After editing it, run `/sculptory reload` (admins); changes under `history`, `library` and `transform` still need a restart. If the file can't be read at start, editing is off and the log says why; `/sculptory reload` with a broken file keeps the running settings and says why.

| Key | Default | What it does |
|---|---|---|
| `editingEnabled` | `true` | The master switch |
| `permissionFallbackOpLevel` | `2` | Op level that may edit without a permissions mod |
| `limits.maxOpVolume` | `2097152` | Largest edit, in blocks |
| `limits.maxBrushRadius` | `32` | Largest brush radius |
| `limits.maxUploadBytes` | `16777216` | Largest schematic import (up to 64 MiB) |
| `executor.tickBudgetMsDedicated` | `10` | Milliseconds per tick spent on edits |
| `history.maxEntriesPerPlayer` | `256` | Undo steps per player (1–10,000) |
| `builder.maxReach` | `64` | Builder mode's Long reach, in blocks |
| `builder.maxBlocksPerSecond` | `100` | Builder mode's block budget per player |

## Files and commands

| Path | Holds |
|---|---|
| `sculptory/library/` | The shared library: copy `.schem`, `.litematic` or `.nbt` files in and they show up |
| `sculptory/library/.trash/` | Deleted assets for 30 days: move one back to restore it |
| `<world>/sculptory/history/` | The saved undo history |

`/sculptory cancel` cancels your own running edits and `/sculptory version` names the builds. With `admin`: `/sculptory cancel <player>`, `/sculptory jobs` (your jobs and the executor's counts), `/sculptory history` (every player's entries) and `/sculptory reload`.

## Which build is this?

Every build has a build id: a release has its version alone (`0.3.0-alpha`), a build from source adds the commit (`0.3.0-alpha+6a043a85`). The id is in the jar's name, the mod list and the log line `Sculptory 0.3.0-alpha initializing`, and `/sculptory version` names the server's build and yours.

## Updating

Replace the jar on the server and on every client with the same build.

- Another build of the same protocol can still edit, with a warning that names both builds.
- Another protocol can join and play but not edit: the editor says "This server runs a different Sculptory version, so the editor can't open".
- Everything Sculptory saves is kept: the settings file, the saved undo history, the library and each player's editor settings. A build never overwrites a file in a newer format than its own, so going back to an older build is safe too.

## Removing the mod

Take the Sculptory jar out of the `mods` folders. The blocks it placed are ordinary blocks, so worlds need nothing else. Left behind, to delete or keep: `config/sculptory/`, `sculptory/library/`, the client's `sculptory/exports/` and each world's `sculptory/history/`.

## Other mods

- WorldEdit isn't needed and shares nothing with Sculptory but `.schem` files. Sculptory's undo leaves alone blocks changed after its edit, so it won't undo work done with another tool.
- Claim mods that hook the game's own build check are respected: protected blocks are skipped.
- Blocks from other mods work with every tool.

## Tips

- Update the jar on the server and every client together: a client of another protocol can't edit, and a different build of the same protocol gets a warning toast.
- Big edits spread over ticks instead of lowering the tick rate: raise the tick budget for speed, lower it for a busy survival server.
- Undo history is saved with the world, so a backup of the world keeps it.

## Related

- [Permissions](permissions.md)
- [Server configuration](config.md)
- [Troubleshooting](troubleshooting.md)
- [Getting started](getting-started.md)
