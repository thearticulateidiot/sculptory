# Permissions

[Home](home.md) · [Run a server](home.md#run-a-server)

Every permission node is `sculptory.<name>`. A permissions mod such as LuckPerms decides who holds which; without one, operators of level 2 or higher hold them all (the level is `permissionFallbackOpLevel` in the server settings). The owner of a singleplayer world holds them all.

## Nodes

| Node | Allows |
|---|---|
| `use` | Opening the editor at all; every other node needs it |
| `brush` | The terrain brushes, the Weather brush, the Shape brush, the Fluid ball |
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
| Builder | `use`, `brush`, `region`, `clipboard`, `scatter`, `builder`, `navigate` |
| Lead builder | Builder, plus `schematic.import`, `schematic.export`, `library.write` |
| Admin | `sculptory.*` |

## LuckPerms

LuckPerms isn't bundled: install it on the server if you want it. A builder group:

1. `/lp creategroup builder`
2. `/lp group builder permission set sculptory.use true`, then the same for each of the group's nodes.
3. `/lp user Steve parent add builder`

More examples:

- A terrain-only helper: `/lp user Alex permission set sculptory.use true` and `/lp user Alex permission set sculptory.brush true`.
- Keep an operator out of the editor: `/lp user Guest permission set sculptory.use false`.
- Everything for admins: `/lp group admin permission set sculptory.* true`.

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
