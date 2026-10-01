# Changelog

All notable changes to Sculptory. Versions follow `mod_version` in `gradle.properties`; client and server need the
same build.

## 0.3.0-alpha — first public release

The first public release of Sculptory, for Minecraft 1.21.1 with Fabric Loader 0.16.14+ and Fabric API
0.116.4+1.21.1. One jar serves singleplayer, dedicated servers and the clients that join them. Network protocol 5.

### What's in it

- **Editor mode** (`B`): floating, resizable windows, a menu bar, `Ctrl+K` command search, the `F1` key sheet,
  notifications, one window layout per UI size, opacity settings, and every key rebindable.
- **Selections**: boxes, spheres, cylinders, cones and pyramids, magic select (connected blocks), brush and lasso
  selections.
- **Selection operations**: Fill, Replace (16 blocks or tags, a block or a palette, Keep shape, Whole family), Erase,
  Hollow, Walls, Overlay, Naturalize and Update blocks, with progress and cancel; 2 million blocks per edit by default.
- **Masks** on every edit (`Ctrl+M`): blocks and tags, on top of, under, next to, touches air, solid, height, slope,
  inside the selection, random, each with "not"; per-brush masks too.
- **Terrain brushes**: Raise, Lower, Smooth, Flatten, Paint and Palette Paint, up to radius 32, with falloff, Surface
  mode for walls and overhangs, mix patterns (Random, Patches, Gradient, Steepness) and symmetry.
- **Weather brush**: Erode, Fill in, Roughen and Melt.
- **Shape brush**: spheres, cylinders, cones, cubes and pyramids, by click or drag, and swept along straight lines,
  curves and hanging lines.
- **Clipboard and Place**: copy, cut, paste, move and stack with rotate, flip and upside-down flip, ghost previews,
  Paste into (everything, only existing, only air), entities included.
- **Schematics**: `.schem` (Sponge v1–v3 import, v3 export, WorldEdit-compatible), `.litematic` and structure `.nbt`
  import and export. Command blocks and clickable sign commands from files are never kept.
- **Library**: the server's shared schematics and block palettes, managed in game, with per-asset access.
- **Scatter**: vanilla trees and features, schematics, clipboards and single blocks over an area or selection, with
  rotations, mirrors, a preview and `Alt+click` for one.
- **Generate** (roads, roofs, lines and curves), **Extrude** (extrude, carve, smear) and **Fluid** (flood, drain,
  balls of water or lava).
- **Tinker**: change a block's properties, a sign's text or an entity in place.
- **Builder mode** (hold `G`): editor powers in normal creative play. **Jump** (`J`) and **Through** (`Shift+J`).
- **History**: one undo history for every tool, a History window to jump to any point, "Undo anyway" after conflicts,
  undo that keeps blocks other players changed since, and history saved with the world (it survives restarts and
  crashes).
- **Servers**: permission nodes per tool (`sculptory.*`, through fabric-permissions-api, so LuckPerms works), operator
  fallback, size and rate limits, job queues, `/sculptory` commands and `/sculptory reload`.
- **Help**: an in-game tutorial (19 short lessons in your own world) and the wiki in game (Help > Wiki).

Known limits are listed in the wiki's [FAQ](docs/wiki/faq.md#known-limits).

### For users of the earlier, unreleased "Builder Suite" builds

Sculptory was called Builder Suite during development. When you replace an old build with this one:

- **Folders move.** On first use, the old `buildersuite` folders (`config/buildersuite/`, the game folder's
  `buildersuite/` library and each world's `buildersuite/` history) are moved to their `sculptory` names. An existing
  `sculptory` folder is never overwritten or merged; if a move fails, the old folder is used where it is and the next
  start tries again. Old schematics, palettes and saved history are still read.
- **The command is now `/sculptory`** (it was `/bs`).
- **Permission nodes are now `sculptory.*`** (they were `buildersuite.*`). Grants don't carry over: grant them again,
  for example `/lp group builder permission set sculptory.use true`.
- **Key rebinds in Controls reset** to their defaults (`B`, `G`, `J` have new key names). The editor's own keys in the
  Keys window are kept.
- The old mod ids are marked as incompatible, so remove the old jar.
