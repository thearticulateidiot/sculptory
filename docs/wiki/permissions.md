# Permissions

[Home](home.md) · [Run a server](home.md#run-a-server)

Every permission node is `sculptory.<name>`. A permissions mod or plugin such as LuckPerms decides who holds which (on NeoForge a specific LuckPerms build, see [below](#neoforge-and-paper)); without one, operators of level 2 or higher hold them all (the level is `permissionFallbackOpLevel` in the server settings). The owner of a singleplayer world holds them all.

## Nodes

| Node | Allows |
|---|---|
| `use` | Opening the editor at all; every other node needs it |
| `brush` | The terrain brushes, the Weather brush, the Shape brush, the Fluid ball |
| `biome` | The Biome tool (painting and filling biomes), and the Place tool's **Biomes** box: pasting a clipboard's biomes, or carrying biomes along with a move or stack |
| `region` | Select and its operations, Move, Stack, Extrude, Flood, Drain, Tinker, Generate and the Shape brush's Line (with `clipboard`) |
| `clipboard` | Copy, paste, the library, Generate and the Shape brush's Line (with `region`) |
| `builder` | Builder mode's powers outside the editor |
| `navigate` | Jump and Through (`J`, `Shift+J`), in Creative or Spectator |
| `schematic.import` | Importing schematic files by drag and drop |
| `schematic.export` | Exporting the clipboard to a file |
| `library.write` | Saving to and managing the shared library folders |
| `scatter` | Scatter (single blocks, trees and features also need `brush` or `region`) |
| `physics` | Placing with block updates |
| `nbt.operator` | Keeping operator-only data (command blocks and the like) in pastes |
| `limit.bypass` | Going over the size limits |
| `edit.unloaded` | Box operations that load chunks |
| `admin` | The admin `/sculptory` commands and every player's library folder |

## Roles

A useful split for a build server.

| Role | Nodes |
|---|---|
| Builder | `use`, `brush`, `biome`, `region`, `clipboard`, `scatter`, `builder`, `navigate` |
| Lead builder | Builder, plus `schematic.import`, `schematic.export`, `library.write` |
| Admin | `sculptory.*` |

## LuckPerms

LuckPerms isn't bundled: install it on the server if you want it (on NeoForge its 5.4.150 build, see [below](#neoforge-and-paper)). A builder group:

1. `/lp creategroup builder`
2. `/lp group builder permission set sculptory.use true`, then the same for each of the group's nodes.
3. `/lp user Steve parent add builder`

More examples:

- A terrain-only helper: `/lp user Alex permission set sculptory.use true` and `/lp user Alex permission set sculptory.brush true`.
- Keep an operator out of the editor: `/lp user Guest permission set sculptory.use false`.
- Everything for admins: `/lp group admin permission set sculptory.* true`.

## NeoForge and Paper

The nodes are the same on every server; only what decides them differs.

- **Fabric:** a permissions mod that works with Fabric's permissions API, such as LuckPerms.
- **NeoForge:** NeoForge's permission system, which LuckPerms plugs into. Install LuckPerms's **5.4.150** build (labelled for 1.21.4; it declares no version range and runs on 1.21.1). The build labelled for 1.21.1, 5.4.140, kicks every player at login on current NeoForge 21.1 releases ([LuckPerms issue #4259](https://github.com/LuckPerms/LuckPerms/issues/4259)), and the 5.5 builds need a newer Minecraft. Checked on NeoForge 21.1.264: grants and denials in LuckPerms decide the nodes, a denial beats operator status, and a node LuckPerms doesn't set falls back to operator level. No other permissions mod has been tried on NeoForge.
- **Paper:** Bukkit permission nodes, set with LuckPerms or another permissions plugin (the `/lp` commands above work as they are). A wildcard such as `sculptory.*` needs a permissions plugin that understands wildcards; LuckPerms does.
- **`sculptory.selftest`** (Paper) is for the developers' self-test, which only a developer build of the plugin has. It does nothing in a released plugin and is false by default, operators included.

Without a permissions mod or plugin, operators of level 2 or higher hold the nodes on all three.

Permissions decide who may edit, not where: on Paper, claim plugins other than WorldGuard don't limit bulk edits (see [On Paper](server-setup.md#on-paper)).

## Tips

- Upgrading from Builder Suite: grants under `buildersuite.*` don't carry over. Grant them again as `sculptory.*`.
- To keep an operator out of the editor, set `sculptory.use` to `false` for them: a denial beats the op level.
- Changes apply at once. A player who loses `use` has the editor closed; a tool whose node they lack is greyed out in the palette, with the node named in its tooltip.
- Without `library.write`, saves still work: they go to the player's own folder in the library.
- The [Mask](masks.md) needs no node of its own: it only narrows the edits a player may already make.
- Every server setting, and the limits that apply to all players, are on [Server configuration](config.md).

## Related

- [Server setup](server-setup.md)
- [Tools at a glance](tools.md)
- [Builder mode](builder-mode.md)
