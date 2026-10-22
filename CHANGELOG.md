# Changelog

All notable changes to Sculptory. From 0.5.0 on, each product has its own version and section, headed
`## <Product> <version>+<Minecraft version>` (`## Fabric 0.5.0+1.21.1`); the releases up to 0.4.0-beta are the Fabric
mod's. Client and server need the same network protocol (each section says which).

## Unreleased

### Changed

- **Generate roads**: the ends of curved roads now follow the road's true end direction; straight roads round
  half-block steps the same wherever they are built, and Use Stairs and Slabs on a 45° road always steps along x.

## 0.4.0-beta — first beta

For Minecraft 1.21.1 with Fabric Loader 0.16.14+ and Fabric API 0.116.4+1.21.1, like 0.3.0-alpha. Beta means the main
tools are done and what you save carries over when you update Sculptory on the same Minecraft version: see
[Updating Sculptory](https://github.com/thearticulateidiot/sculptory/blob/main/docs/wiki/updating.md). Network
protocol 6.

### New since 0.3.0-alpha

- **Free rotation**: turn a paste or a move by any angle around any axis.
  - Three rings on the ghost snap every 15°; hold `Ctrl` to turn freely, or type the angles in Tool Settings.
  - **Rotate…** in the Selection window opens the same ghost on the selection, turning about the symmetry centre (M)
    or the selection's middle. It is a move: with **Biomes** ticked the biomes turn along.
  - Blocks that face a way take the nearest facing, including tipped onto their side or upside down, and 90° turns are
    exact.
  - A **Method** setting picks how a free turn lands on the block grid: Nearest (the default), Exact count (every
    block lands once) or Smooth (fuller shapes); **Smooth slopes** puts stairs and slabs on turned slopes.
  - Doors, beds and tall plants stay whole, or are left out whole when they have no room.
- **Heightmap brush** (`;`): stamp or paint terrain from grayscale pictures, using the built-in set or your own PNGs in
  `sculptory/heightmaps`, up to radius 128 (stamps 257 blocks wide; the server builds big stamps over several seconds,
  a little each tick).
- **Biome tool** (`'`): paint biomes, or fill the selection with one, as whole columns or in 3D.
  - Biomes travel with copy and `.schem`; Paste, Move and Stack share a Biomes tick box (off by default) that carries
    them along.
  - View > Biome view tints the ground by biome.
  - Needs the new `sculptory.biome` permission.
- **Generate Sweep, Stamp and Heightmap**:
  - Sweep carries the selection as a profile along a path.
  - Stamp places the clipboard or a library asset at each point you click, turned along the path.
  - Heightmap shapes a selected area from a grayscale picture.
- **Generate > Smart Surface**: click points round an area for an outline, and inside it for hills and hollows; a
  terrain-like sheet fills the outline through every point, smooth or faceted, with a top layer and a body, as a sheet
  of a set thickness or filled down to the ground. Keep existing fills air only; Mesh view shows the triangles.
- **Extrude along a path and to a point**: Extrude gets a Direction setting. Carry a face along a curve you click, or
  taper it to a tip for pyramids, spires and cones.
- **Path point editor**, shared by Generate's Road, Line, Sweep, Stamp and Smart Surface, Extrude along a path and to
  a point, and the Shape brush's Line:
  - Click a point to select it. Its gizmo drags it along an axis (the arrows), in a plane (the squares) or freely at
    its distance (the white cube; `Alt+Scroll` while dragging pushes it out or pulls it in). A dragged point never
    snaps onto terrain, and comes along if you fly while holding it.
  - While the end point is selected, a guide line runs from it to where the next click puts a point.
  - Insert a point by clicking the path between two points, or press `E` on a selected point to add one after it that
    follows the cursor until you click (with no point selected, `E` still opens the inventory; the key can be changed).
  - `Backspace` (or `Delete`) removes the selected point.
- **Points in the air**: Extrude along a path and to a point, and Generate's Road, Line, Sweep, Stamp and Smart
  Surface, take points in the sky. Aim at open sky and click the marker; `Alt+Scroll` sets how far ahead it floats, and
  a point dragged into the sky keeps its distance. Aiming at a point in the sky lights it up instead of the marker (a
  click picks it).
  - A road through the air is a floating deck: with Height **Straight between points**, Fill below only reaches down
    where something lies under the deck within its depth, and over chunks your game hasn't loaded the road is a bare
    deck (a message counts those columns) instead of refusing to build. Follow terrain fills as before.
- **Smoother Curve**: Curve lines, roads, sweeps, Extrude paths and the Shape brush's curves now use a centripetal
  curve, which no longer overshoots or dips after a short step followed by a long climb. Lines, roads and Shape brush
  curves built from the same points as in 0.3.0-alpha can come out slightly different.
- **Extend to ground** (Line, Sweep, Smart Surface): fill each column straight down to the ground under it, looking
  through plants, leaves, tree trunks, water and lava, so a beam becomes a wall and a surface a solid hill.
- **Use stairs and slabs** (Line, Road): the steps of a slope become stairs facing up the climb, or slabs, of the
  block's own family.
- **Fly speed command**: `/sculptory flyspeed <0.25-8>` (or `reset`), and `/flyspeed` where the server has no command
  of that name, set the fly speed without operator rights. The speed is saved and also applies when you fly in
  Creative or Spectator outside the editor.
- Selection box handles show faintly through blocks and can still be grabbed there.
- Servers can cap the Heightmap brush's size and height with `limits.maxHeightmapRadius` and
  `limits.maxHeightmapHeight`; a stamp's blocks count against `limits.maxOpVolume`.
- Under the hood, the server side moved into a module of its own, free of Minecraft code, as groundwork for other
  platforms. Nothing changes in game.

### What carries over when you update

Updating Sculptory on the same Minecraft version keeps:

- **Your editor settings**: UI size, panel opacity, fade when not hovered, tool outline opacity, fly speed, window
  layouts (one per UI size), open and folded sections, keys, presets, every tool's last settings, the Mask's rules and
  tutorial progress.
- **Server and shared data**: undo history (within the server's age and disk limits), the library's schematics and
  palettes, who each asset is shared with, and `config/sculptory/server.json`.
- **The world**: blocks placed with Sculptory are ordinary blocks and never depend on the mod.

Not kept: undo history across a Minecraft update (steps belong to the Minecraft version they were made in). Going back
to an older Sculptory leaves files from a newer one as they are and never damages them, but it may not use them.

Saved files are handled more carefully:

- A file Sculptory can't read is never deleted: it is left as it is or copied aside first.
- A saved settings file that can't be read is now copied aside (as `<name>.bad`) instead of being replaced, and a
  Mask file from a newer version is left as it is.
- Settings this build doesn't know, written by a newer or older build, are kept when it saves.

### Updating from 0.3.0-alpha

- Replace the jar on the server and on every client that edits: client and server need the same protocol. A
  0.3.0-alpha client can join a server running 0.4.0-beta (and the other way round) and play, but the editor won't
  open.
- Your editor settings, window layouts, presets, keys, saved undo history and the library carry over. The saved fly
  speed is new and starts at 1×.
- If you go back to 0.3.0-alpha: a `.schem` with biomes opens there with all its blocks but without its biomes. A
  player whose saved history holds biome edits has no saved history while 0.3.0-alpha runs (it leaves the file alone
  and doesn't save that player's new edits); nothing is damaged, and the history is back when you return to
  0.4.0-beta.

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
