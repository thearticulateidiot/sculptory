# Server configuration

[Home](home.md) · [Run a server](home.md#run-a-server)

For server admins: every key of `config/sculptory/server.json`, the `/sculptory` commands and the limits built into the server. [Server setup](server-setup.md) is the short version, and [Permissions](permissions.md) covers who may edit.

## The settings file

- Sculptory writes `config/sculptory/server.json` with the defaults on its first start and never rewrites it. On a dedicated server the path is in the server folder; in singleplayer, in the game folder.
- A key missing from the file uses its default, and unknown keys are ignored. Keys added by a newer build aren't added to your file: the start-up log lists, in one line, the keys your file doesn't set.
- An existing file keeps its values, even the old defaults of the build that wrote it. Delete the file to have the current defaults written.
- A value out of range is clamped into it, with a warning in the log.
- Write switches as `true` or `false`, without quotes: a quoted `"true"` is on, any other text is off.
- If the file can't be read or isn't valid JSON at start, Sculptory starts with editing off and the log names the file. Fix it or delete it, then run `/sculptory reload`.

A job is one server-side edit: a box operation, a paste, move or stack, a scatter commit, an undo or a redo. A dab is one step of a brush stroke. Sizes are in bytes: 16777216 is 16 MiB, 268435456 is 256 MiB and 1073741824 is 1 GiB.

## Reloading

`/sculptory reload` reads the file again without a restart. It needs `admin`, and the console and RCON can run it too.

- Everything except the `history`, `library` and `transform` sections applies at once, and every player's editor learns the new limits.
- `history`, `library` and `transform` need a restart: the command lists such changes, and the running values stay until then.
- Edits already running keep the limits they started with. Nothing running is cancelled, except the jobs of a player who loses a permission.
- A file that can't be used changes nothing: the running settings stay and the command says why. A reload never writes the file.
- The answer, also written to the log, lists every changed setting with its old and new value.

## General

| Key | Default | Range | What it does |
|---|---|---|---|
| `editingEnabled` | `true` | `true`, `false` | The master switch. When off, every edit is refused with "Editing is switched off on this server". |
| `permissionFallbackOpLevel` | `2` | 0–4 | The op level that holds a node no permissions mod decides. `0` lets every player use everything not denied. |
| `singleplayerHostAlwaysAllowed` | `true` | `true`, `false` | The singleplayer world's owner holds every node. LAN guests are always checked. |
| `unloadedChunks` | `"LOAD"` | `"LOAD"`, `"REFUSE"` | What box operations, pastes and moves do where the area isn't loaded: load the chunks (for players with `edit.unloaded`) or refuse. Brushes always need loaded chunks. |

## Executor: how fast edits run

The `executor` section. Sculptory never holds up a tick to finish an edit: it writes for at most the tick budget, then carries on next tick, so big edits take more ticks instead of lowering the tick rate.

| Key | Default | Range | What it does |
|---|---|---|---|
| `tickBudgetMsDedicated` | `10` | 1–1000 | Milliseconds per tick (of 50) edits may use on a dedicated server. Raise it for faster big edits; lower it on a busy server. |
| `tickBudgetMsIntegrated` | `20` | 1–1000 | The same in singleplayer and for LAN hosts. |
| `maxBlocksPerTick` | `0` | 0 or more | A fixed cap on blocks per tick for box operations; each entity counts as 64 blocks. `0` is no cap beyond the time budget. |
| `brushLaneShare` | `0.4` | 0.05–1.0 | The share of the budget brush strokes get first, so sculpting stays responsive during a big fill. |
| `maxActiveJobsGlobal` | `8` | 1–256 | Jobs running at once for everyone. More wait in the queue. |
| `maxQueuedJobs` | `32` | 1–4096 | Jobs waiting for everyone. Beyond it new ones are refused ("Too many edits waiting"). |
| `maxQueuedJobsPerPlayer` | `8` | 1–4096 | Jobs one player may have waiting, so one player can't fill the queue. |
| `maxChunkTicketsPerJob` | `64` | 1–1024 | Chunks one job may keep loaded at a time. |
| `maxQueuedBrushWork` | `1024` | 1–65536 | Brush dabs queued for everyone. Beyond it new dabs are refused ("slow down"). |
| `maxColumnsPerJob` | `16384` | 1–1048576 | Chunk columns one job may touch: 16,384 columns is a 2048 × 2048 block area. |

## History: undo and saving it

The `history` section (needs a restart).

| Key | Default | Range | What it does |
|---|---|---|---|
| `maxEntriesPerPlayer` | `256` | 1–10000 | Undo steps kept per player. The oldest go first. |
| `maxBytesPerPlayer` | `268435456` | 1 MiB or more | Memory one player's history may use. An edit bigger than this can't be undone, and the player is told. |
| `maxBytesTotal` | `1073741824` | 1 MiB or more | Memory all histories together may use, offline players' included. |
| `persist` | `true` | `true`, `false` | Save every player's history with the world, so it survives restarts, crashes and leaving. Off keeps it in memory only. |
| `maxDiskBytes` | `2147483648` | 16 MiB or more | Disk all saved history files may take. The oldest steps go first. |
| `maxAgeDays` | `30` | 0–36500 | Saved steps older than this are dropped. `0` keeps them. |

Saved history is in `<world>/sculptory/history/`, one file per player, so it travels with world backups. After a crash, an edit the crash cut off is one step marked "(interrupted)" that undoes what it had written. A damaged file never stops the server: the readable part is used and the rest is kept aside as a `.damaged` or `.corrupt` copy. Don't edit these files; to start everyone afresh, stop the server and delete the `history` folder. `/sculptory history` says where history is saved and whether saving is behind.

## Limits: what players may do at once

The `limits` section. Each client learns these when it connects, so the editor can check them before sending anything. Players with `limit.bypass` may go over `maxOpVolume`, `maxClipboardVolume` and `maxBrushRadius`.

| Key | Default | Range | What it does |
|---|---|---|---|
| `maxOpVolume` | `2097152` | 1 or more | The largest operation, paste or scatter commit, in blocks. A shape counts its own blocks, not its box. |
| `maxClipboardVolume` | `2097152` | 1 or more | The largest copy, cut, library load, imported file or generated clipboard, in blocks. A copy counts the box around the selection. |
| `maxBrushRadius` | `32` | 1–32 | The largest brush radius. The editor's slider stops there. |
| `maxDabRate` | `20` | 1–1000 | Brush dabs per second a client sends at most. |
| `maxUploadBytes` | `16777216` | 1 B to 64 MiB | The largest schematic file a player may import, compressed. |
| `maxJobsPerPlayer` | `2` | 1–64 | Jobs one player may have running at once. More wait. |
| `maxSelectionCells` | `2097152` | 1–67108864 | The most blocks in one magic selection sent to the server. |
| `maxSelectionSections` | `65536` | 1–1048576 | The most 16 × 16 × 16 sections one sent selection may touch. |
| `maxSelectionStoreBytes` | `67108864` | 1 MiB to 4 GiB | What one player's sent selections may take on the server. It keeps their last 4. |
| `maxSelectionStoreBytesTotal` | `536870912` | up to 64 GiB | What all players' sent selections may take together. |

## Library: the shared schematic folder

The `library` section (needs a restart). The library is `sculptory/library/` in the server folder: copy `.schem`, `.litematic` or `.nbt` files in and they show up. Each player has a folder of their own, `_players/<uuid>/`.

| Key | Default | Range | What it does |
|---|---|---|---|
| `maxFileBytes` | `33554432` | 1 KiB to 1 GiB | The largest schematic the library reads or writes. A palette file is at most 64 KiB. |
| `maxTotalBytes` | `1073741824` | 1 KiB or more | All library files together. |
| `maxPlayerBytes` | `67108864` | 1 KiB or more | One player's own folder. |
| `trashDays` | `30` | 0–3650 | Days a deleted asset stays in `.trash/`, where moving it back restores it. `0` keeps it as long as space allows. |
| `maxTrashBytes` | `1073741824` | `maxFileBytes` or more | What the trash may hold of the shared folders' deletions. The oldest go first. |
| `maxPlayerTrashBytes` | `67108864` | a player folder's largest file or more | What the trash may hold of each player folder's deletions. |

Fixed caps: 20,000 files and 4,096 folders in the whole library, 512 files and 64 folders per player folder.

## Scatter previews

The `scatter` section. These apply to every player, `limit.bypass` included.

| Key | Default | Range | What it does |
|---|---|---|---|
| `maxSourceVolume` | `262144` | 1–2097152 | The largest scatter variant, by its box (262,144 is 64 × 64 × 64). |
| `maxWork` | `50000000` | 1000 or more | Planning work per preview, in cell checks. A plan that reaches it stops placing. |
| `tickShare` | `0.25` | 0.05–1.0 | The share of the tick budget scatter planning may use. |
| `maxPlanningMillis` | `3000` | 100–600000 | Planning time one preview may use. Beyond it the preview is refused as too large. |
| `holdBudgetSeconds` | `30.0` | 1–3600 | How long one player's open previews may hold up other players' edits in their area. When it's used up, the next preview is refused ("slow down"). |
| `holdRefillShare` | `0.5` | 0.01–1.0 | Seconds of hold budget regained per second. |
| `maxFeatureCells` | `1048576` | 1–16777216 | The most blocks the vanilla trees and features of one preview may grow. |

Fixed: one preview per player and 8 at once for everyone; a plan expires after 10 minutes.

## Transform, entities, builder mode and navigation

| Key | Default | Range | What it does |
|---|---|---|---|
| `transform.moddedFacingFallback` | `true` | `true`, `false` | Turn and mirror modded blocks that can't turn themselves by their `facing`, `axis` or `rotation`, as WorldEdit does. Vanilla blocks always turn as the game turns them. Needs a restart. |
| `entities.maxPerClipboard` | `4096` | 0–65536 | The most entities one copy, cut, imported file or library load may hold. `0` refuses every copy with entities. |
| `entities.maxPerJob` | `16384` | 0–1048576 | The most entities one paste, move or stack may place; a stack counts every copy. |
| `builder.maxReach` | `64` | 5–64 | How far builder mode places and breaks, in blocks. |
| `builder.maxBlocksPerSecond` | `100` | 1–10000 | Blocks one player may place or break per second in builder mode; bursts of twice as many are allowed. |
| `navigate.maxDistance` | `256` | 8–1024 | How far away the block Jump (`J`) lands on may be. |
| `navigate.maxThroughDepth` | `64` | 1–256 | How deep a wall Through (`Shift+J`) goes through. |

The entity caps count passengers and may be passed with `limit.bypass`. Players, dropped items, projectiles and other short-lived entities, withers and the ender dragon are never copied.

## Commands

`/sculptory cancel` and `/sculptory version` need only `use`; the rest need `admin`. Players see only the commands they may use. The console can run them for a player with `execute as <player> run sculptory …`.

| Command | Needs | What it does |
|---|---|---|
| `/sculptory cancel` | `use` | Cancels all your jobs, like the job bars' Cancel button. What they've done stays and can be undone. |
| `/sculptory version` | `use` | This server's build and protocol, and your client's. Admins and the console also see every online player's. |
| `/sculptory cancel <player>` | `admin` | Cancels all of another player's jobs, online or not, by name or UUID. The console can run it directly. |
| `/sculptory reload` | `admin` | Reads `server.json` again (see [Reloading](#reloading)). The console can run it directly. |
| `/sculptory jobs` | `admin` | Your running jobs, and the server's active and queued jobs and dabs. |
| `/sculptory history` | `admin` | Your undo and redo steps, the memory they use, and where history is saved. |
| `/sculptory fill <from> <to> <block>` | `admin` | Fills a box, as a normal job with history. |
| `/sculptory undo`, `/sculptory redo` | `admin` | Undoes or redoes your last step. |

## Built-in limits

These aren't in the file. A request over its rate is refused with "Too many edits at once — slow down a little"; normal use stays well inside them.

| Kind of request | Per second | Burst |
|---|---|---|
| Brush dab messages | 25 | 40 |
| Starting a brush stroke | 10 | 20 |
| Edits, undo, redo, copy, previews, library, saves, exports, imports | 5 | 10 |
| Tinker changes | 10 | 20 |
| Uploaded file data | 1 MiB | 1 MiB |

- A client that keeps sending malformed messages, or keeps flooding past its rates, is disconnected ("Sculptory: too many invalid messages").
- File imports: at most `limits.maxUploadBytes` compressed and 2 at a time per player, decoded off the main thread with caps on the decoded size.
- A stack makes at most 256 copies; brush dabs waiting per player are capped at 32.
- Two edits that touch the same area run in order. A brush stroke that reaches an area a job is writing is refused ("That area is being edited").
- When a player leaves, their clipboard and scatter preview are dropped, their history is kept, their running jobs finish and their waiting ones are cancelled.

## Related

- [Server setup](server-setup.md)
- [Permissions](permissions.md)
- [Troubleshooting](troubleshooting.md)
