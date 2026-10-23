# Server setup

[Home](home.md) · [Run a server](home.md#run-a-server)

For server admins: install Sculptory on a Fabric, NeoForge or Paper server, decide who may edit, and tune the limits. This page is the short version; [Server configuration](config.md) has every setting, command and limit.

## Install

Sculptory runs on three kinds of server, all for Minecraft 1.21.1. Pick the section for yours; the rest of this page applies to all three unless it says otherwise.

| Server | What goes on the server | Players who edit need |
|---|---|---|
| Fabric | Sculptory for Fabric and Fabric API, in `mods` | The Sculptory mod for their loader |
| NeoForge | Sculptory for NeoForge, in `mods` | The Sculptory mod for their loader |
| Paper | Sculptory for Paper, in `plugins` | The Sculptory mod for their loader |

Players who don't edit join as usual, with nothing installed. A client and a server need the same network protocol, not the same loader: a Fabric client can edit on a NeoForge server, and the other way round.

To check, join and press `B`: the status dot should read **Connected**. `/sculptory version` names the server's build and yours; admins also see every online player's.

### Fabric

1. Set up a Fabric server for Minecraft 1.21.1 (Loader 0.16.14 or newer).
2. Put Fabric API and the Sculptory for Fabric jar in its `mods` folder and start it.
3. Players who want to edit install Sculptory on their client.
4. Only operators of level 2 or higher can edit by default: give others [permissions](permissions.md).

### NeoForge

1. Set up a NeoForge server for Minecraft 1.21.1 (NeoForge 21.1.264 or newer).
2. Put the Sculptory for NeoForge jar in its `mods` folder and start it. Nothing else is needed.
3. Players who want to edit install Sculptory on their client.
4. Only operators of level 2 or higher can edit by default. For per-player permissions install LuckPerms's 5.4.150 build (the build labelled for 1.21.1 kicks players at login): see [Permissions](permissions.md).

### Paper

1. Set up a Paper server for Minecraft 1.21.1 (build 133, the last 1.21.1 build). On another Minecraft version the plugin refuses to enable, with a log line naming both versions; Spigot and Folia are not supported (see [Known limits on Paper](#known-limits-on-paper)).
2. Put the Sculptory for Paper jar in its `plugins` folder and start it. The log says `Sculptory for Paper <build> enabling`.
3. Players who want to edit install the Sculptory mod for their loader (Fabric or NeoForge) on their client: the plugin is the server side only.
4. Optional: WorldGuard (with WorldEdit, which it needs) to protect regions, and LuckPerms or another permissions plugin to decide who may edit.
5. Only operators of level 2 or higher can edit by default: give others [permissions](permissions.md).

The next section says what is different on Paper.

## On Paper

- **Files.** The settings are `plugins/Sculptory/server.json` and the library is `plugins/Sculptory/library/`. The undo history is saved with each world, in `<world>/sculptory/history/`, as on Fabric and NeoForge.
- **WorldGuard** is optional (it needs WorldEdit). With it installed, an edit only changes blocks where the player may both place and break under WorldGuard's rules (region membership and build flags); the rest is skipped, and a builder mode click there is refused. Spawn protection and the world border apply either way.
- **Other claim plugins don't limit bulk edits.** Fills, brushes, pastes, undo and the other bulk edits fire no block events, like WorldEdit's, so GriefPrevention, Lands, Towny, PlotSquared and other claim plugins neither see nor stop them: only WorldGuard regions, spawn protection and the world border do. Builder mode is different: its hand placing and breaking fires the block events, so those plugins can refuse it (below). On a server that protects land with another claim plugin, give the bulk-edit permissions (`brush`, `region`, `clipboard`, `scatter`, `biome`) only to players you trust everywhere.
- **WorldEdit** isn't needed. Both can run on one server; they share only `.schem` files.
- **Permissions** are Bukkit permission nodes, the same `sculptory.*` names as on [Permissions](permissions.md). A node nothing sets falls back to the operator level (`permissionFallbackOpLevel`). A wildcard such as `sculptory.*` needs a permissions plugin that understands wildcards; LuckPerms does. Changes apply at once, LuckPerms' too.
- **Builder mode** fires Paper's block place and break events for each block it changes, so WorldGuard, claim plugins and logging plugins see builder mode actions and can cancel them: the action is then put back and refused. Bulk edits fire no block events (above).
- **Jump and Through** teleport the Paper way: a plugin that cancels a teleport stops them too, with a message.
- **Commands.** `/sculptory` needs `use`, as on Fabric and NeoForge. It has `version` (the server's build and yours; admins and the console also see every online player's), and with `admin` `fill`, `undo` and `redo`. `cancel`, `jobs`, `history` and `reload` are not there yet: after changing `server.json`, restart the server. If you use Paper's `/reload confirm` instead, builders keep their connection but must rejoin before editing again; the editor tells them the next time they use it.

### Known limits on Paper

- **After a crash, the last edits may not undo.** Paper saves chunks in the background without telling plugins first, so a chunk it saved shortly before a crash can hold up to about a second of bulk edits (about five seconds of brush strokes, more while the server is busy saving history) whose undo steps were not written yet. Normal stops and saves are not affected.
- **Undoing water and lava.** Undo takes back what a fluid edit set off: the flow, level changes, blocks the fluid formed or melted, fire from lava and the blocks right next to each change. It doesn't take back chain reactions further away (a redstone line losing power) or another plugin's changes to those blocks. The record of fluid changes is saved every five seconds, so a crash can lose the last few seconds of it.
- **Builder mode records the blocks within two of each change.** Redstone wires that a faster redstone setting (Paper's Alternate Current or Eigencraft redstone) changes further away are not recorded, and one action records at most 32768 watched blocks: past that, the chain reaction still happens but undo doesn't take it back. The blocks the action itself places or breaks are always recorded. A container destroyed by a chain reaction comes back empty on undo, and a wither skull placed with Keep shape builds no wither.
- **Paper 1.21.1 only** for now, tested on build 133. On another Minecraft version (1.21.2 and newer included) the plugin refuses to enable and says why. It doesn't load on Spigot, which lacks Paper's own code, and Folia refuses it, since it isn't made for Folia's threads. Paper forks such as Purpur are untested.

## Upgrading from Builder Suite

Sculptory used to be called Builder Suite (a Fabric mod). On its first start it moves the old `buildersuite` folders (config, library, each world's history) to their `sculptory` names; an existing `sculptory` folder is never overwritten.

- Grants under `buildersuite.*` don't carry over: grant them again as `sculptory.*`.
- Players' rebinds of Sculptory's keys in Controls reset to the defaults.
- Close any other instance still running an old build on the same library before the first start.
- If a folder can't be moved, the old one is used where it is and the next start tries again; the log says so.

## Settings

`config/sculptory/server.json` (on Paper `plugins/Sculptory/server.json`) is written on first start. After editing it, run `/sculptory reload` (admins) on Fabric and NeoForge; on Paper, restart the server (see [On Paper](#on-paper) about `/reload confirm`). Changes under `history`, `library` and `transform` still need a restart. If the file can't be read at start, editing is off and the log says why; `/sculptory reload` with a broken file keeps the running settings and says why.

| Key | Default | What it does |
|---|---|---|
| `editingEnabled` | `true` | The master switch |
| `permissionFallbackOpLevel` | `2` | Op level that may edit without a permissions mod |
| `limits.maxOpVolume` | `2097152` | Largest edit, in blocks |
| `limits.maxBrushRadius` | `32` | Largest brush radius (the Heightmap brush has `limits.maxHeightmapRadius`, `128`) |
| `limits.maxUploadBytes` | `16777216` | Largest schematic import (up to 64 MiB) |
| `executor.tickBudgetMsDedicated` | `10` | Milliseconds per tick spent on edits |
| `history.maxEntriesPerPlayer` | `256` | Undo steps per player (1–10,000) |
| `builder.maxReach` | `64` | Builder mode's Long reach, in blocks |
| `builder.maxBlocksPerSecond` | `100` | Builder mode's block budget per player |

## Files and commands

| Path | Holds |
|---|---|
| `sculptory/library/` (on Paper `plugins/Sculptory/library/`) | The shared library: copy `.schem`, `.litematic` or `.nbt` files in and they show up |
| `sculptory/library/.trash/` (on Paper `plugins/Sculptory/library/.trash/`) | Deleted assets for 30 days: move one back to restore it |
| `<world>/sculptory/history/` | The saved undo history |

`/sculptory cancel` cancels your own running edits and `/sculptory version` names the builds. With `admin`: `/sculptory cancel <player>`, `/sculptory jobs` (your jobs and the executor's counts), `/sculptory history` (every player's entries) and `/sculptory reload`. Paper has fewer commands so far: see [On Paper](#on-paper).

## Which build is this?

Every build has a build id: its version and the Minecraft version it is for. A release has those alone (`0.5.0-beta+1.21.1`, or `0.5.0+1.21.1` once out of beta), a build from source adds the commit (`0.5.0-dev+1.21.1.6a043a85`); releases up to 0.4.0-beta had the version alone (`0.4.0-beta`). The id is in the jar's name, the mod or plugin list and the log line (`Sculptory for Fabric 0.5.0-beta+1.21.1 initializing`, likewise for NeoForge; `Sculptory for Paper 0.1.0-beta+1.21.1 enabling`), and `/sculptory version` names the server's build and yours.

## Updating

Replace the jar on the server (in `plugins` on Paper) and on every client that edits. [Updating Sculptory](updating.md) lists what carries over and where the files are.

- What counts is the network protocol: a client of the same protocol can edit, whatever its loader. Another build of the same protocol gets a warning that names both builds.
- Another protocol can join and play but not edit: the editor says "This server runs a different Sculptory version, so the editor can't open".
- Everything Sculptory saves is kept: the settings file, the saved undo history, the library and each player's editor settings. A build never overwrites a file in a newer format than its own, so going back to an older build is safe too.

## Removing the mod

Take the Sculptory jar out of the `mods` folders (`plugins` on Paper). The blocks it placed are ordinary blocks, so worlds need nothing else. Left behind, to delete or keep: `config/sculptory/`, `sculptory/library/` (on Paper `plugins/Sculptory/`), the client's `sculptory/exports/` and each world's `sculptory/history/`. Chunks where water or lava was edited also keep a small `sculptory:fluid_marks` entry in their saved data; it is harmless, since nothing reads it without Sculptory.

## Other mods

- WorldEdit isn't needed and shares nothing with Sculptory but `.schem` files. Sculptory's undo leaves alone blocks changed after its edit, so it won't undo work done with another tool.
- On Fabric and NeoForge, claim mods that hook the game's own build check are respected: protected blocks are skipped. On Paper only WorldGuard regions are, for bulk edits; other claim plugins see builder mode only: see [On Paper](#on-paper).
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
