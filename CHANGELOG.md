# Changelog

All notable changes to Sculptory. From 0.5.0 on, each product has its own version and section, headed
`## <Product> <version>+<Minecraft version>` (`## Fabric 0.5.0-beta+1.21.1`); the releases up to 0.4.0-beta are the Fabric
mod's. Client and server need the same network protocol (each section says which).

## Unreleased

Three releases are being prepared from this code, all betas: Fabric 0.5.0-beta and the first NeoForge and Paper
releases. The NeoForge mod and the Paper plugin are built on the same code as the Fabric mod, so the fixes and changes
listed for Fabric are in them too. Until they are released, NeoForge and Paper can be built from source (README,
"Install").

To release one, promote its planned section: move it below this Unreleased section, head it
`## <Product> <build id>` (`## Fabric 0.5.0-beta+1.21.1`, `## NeoForge 0.5.0-beta+1.21.1`,
`## Paper 0.1.0-beta+1.21.1`) without "(planned)", and lift its `####` headings to `###`. The release workflow takes
the notes from that heading down to the next `## ` heading or planned section, and refuses a heading that still says
"(planned)".

### Fabric 0.5.0-beta+1.21.1 (planned)

For Minecraft 1.21.1 with Fabric Loader 0.16.14+ and Fabric API 0.116.4+1.21.1, like 0.4.0-beta. Network protocol 6,
like 0.4.0-beta: the two work together, with one difference (below).

#### New

- **Building by hand goes into the history by default**: builder mode has a ninth power, **Record**, on from the
  first start. In Creative (with the builder permission on servers), every block you place or break with a block
  item, and every item frame, painting and armor stand you hang, place, change or break, is one undo step (`Ctrl+Z`)
  without switching anything on. Buckets, flint and steel, bone meal, an axe, hoe or shovel used on a block, spawn
  eggs, doors and repeaters switched by hand and lily pads stay vanilla's and can't be undone (see
  [Builder mode](https://github.com/thearticulateidiot/sculptory/blob/main/docs/wiki/builder-mode.md#rules)).
  Switch Record off in the `G` ring, with every other power off too, to play the vanilla way with nothing recorded;
  it stays as you left it across restarts (the other powers are still forgotten). It shows no tag above the hotbar.
  Before, a block was recorded only while another power was on.
- **Item frames, paintings and armor stands in builder mode's history**: with a builder power on, hanging a frame or
  a painting, placing an armor stand, putting an item in a frame (or turning it), dressing a stand and breaking any
  of them are each one step ("Place · item frame", "Change · armor stand", "Break · painting"), undone and redone
  like a block. Where builder mode would refuse a block (out of reach, protected, outside the mask) nothing is placed.
  Mirror does not copy them.

#### Fixed

- **Placing blocks in Survival with a builder power on**: a player who switched to Survival (or lost the builder
  permission) with a power still on could not place blocks on a server with Sculptory, since the server held back the
  game's own placement as if builder mode were placing for them. It now does that only while builder mode acts for the
  player (Creative and the permission).
- **Building by hand on a server with editing switched off** (`editingEnabled` false, also what a broken
  `server.json` falls back to): a player with a builder power on had every place and break refused. The server now
  tells clients that editing isn't available while it is off, so their clicks go the vanilla way and the editor
  can't be opened (`B` says "Editing is off here"); a reload that switches it back on gives both back at once.
- **Frames and paintings no longer drop off when their wall goes**: an edit that takes away the block an item frame
  or painting hangs on (a fill, a brush, a paste, a builder-mode break) takes the frame into its step, and its undo
  hangs it back with its item. Undoing the step that built a wall takes along the frames hung on it since, and redo
  hangs them again, also after a restart. Before, the game dropped them off with their items and nothing brought
  them back.

- **Surface mode on thin walls**: Raise and Lower work the side you aim at when it belongs to a wall 3 or more blocks
  tall, however thin the wall and whatever it stands on (a wall one block thick, or one shorter than the brush, was
  often worked from above). Single blocks and steps 1 or 2 blocks high still count as ground.
- **Flatten and the cursor ring follow the same rule**: Flatten's plane and the ring under the cursor face the way
  Raise and Lower work, so Flatten aimed at a wall 3 or more blocks tall flattens sideways, along the wall's face, and
  the ring shows the side the brush will work.
- **Surface Smooth keeps thin walls and slabs**: walls 3 or more blocks tall and slabs one block thick keep their ends
  and corners, and Smooth does not pile blocks against a thin wall's foot or face, or above and below a one-block
  slab. A single block sitting on a wall, bumps, spikes, pillars and ridges under 3 blocks still smooth away.
- **Lines and roads are the same wherever they are built**: Generate's Line and Road work out their blocks from their
  first point, so the same line or road built somewhere else, moved by whole blocks, has exactly the same blocks.
  Straight lines and a Hanging line's chord, in Line, Sweep, Extrude along a path and the Shape brush's Line, are
  worked out exactly: a line climbing half a block per block steps at every other block. Curves are as in 0.4.0-beta.

#### Changed

- **The jar and the version have a new form**: the jar is `sculptory-fabric-0.5.0-beta+1.21.1.jar` (was
  `sculptory-0.4.0-beta.jar`), and the build id, the version plus the Minecraft version, is what the mod list shows
  and what client and server show each other when a player joins. Take the old jar out of `mods` when updating.
- **Generate roads**: the ends of curved roads now follow the road's true end direction; a road whose last two points
  stand one above the other in the same column keeps a round end. Straight roads round half-block steps the same
  wherever they are built, and Use Stairs and Slabs on a 45° road always steps along x.

#### With a 0.4.0-beta client or server

- A 0.4.0-beta client on a server with this version still predicts Surface Raise, Lower and Smooth near walls the old
  way, so its preview there can differ from the result; the server's result is what is built. Update the clients and
  the server together.
- On a 0.4.0-beta server, Record is greyed out in the ring ("needs a newer server") and plain clicks are recorded only
  while another power is on, as before. A 0.4.0-beta client on a server with this version has no Record power and
  works as it did.

### NeoForge 0.5.0-beta+1.21.1 (planned)

The first NeoForge release, a beta. For Minecraft 1.21.1 with NeoForge 21.1.218 or newer. Network protocol 6.

- **The whole editor on NeoForge**: every tool, window and key of the Fabric mod, in singleplayer and on NeoForge
  servers. Nothing else to install besides NeoForge.
- **Fabric and NeoForge work together**: a Fabric client can use the editor on a NeoForge server, and a NeoForge
  client on a Fabric server, as long as both speak the same network protocol.
- **Permissions** through NeoForge's permission system: the same `sculptory.*` nodes as on Fabric, operators by
  default, LuckPerms when installed (its 5.4.150 build; the build labelled for 1.21.1 kicks every player at login on
  current NeoForge 21.1 releases).
- Tried on a server next to Cobblemon, Create and JEI: no conflicts, and their blocks fill, undo and redo like vanilla
  ones.
- **Building by hand goes into the history by default** through builder mode's Record power, as on Fabric (see
  Fabric 0.5.0-beta, "New"), on NeoForge clients and servers alike.

### Paper 0.1.0-beta+1.21.1 (planned)

The first Paper release, a beta. For Paper 1.21.1 (build 133). The plugin is the server side only: players who edit
need the Sculptory mod for their loader (Fabric or NeoForge) on their client; everyone else joins with nothing
installed. Network protocol 6.

- **Sculptory as a Paper plugin**: put the jar in `plugins/`. The settings are in `plugins/Sculptory/server.json`, the
  library in `plugins/Sculptory/library/`; the undo history is saved with each world, as on Fabric.
- **WorldGuard regions are respected**: an edit changes only blocks where the player may both place and break, the
  rule WorldGuard applies to building by hand. WorldGuard (which needs WorldEdit) is optional.
- **Builder mode fires the server's block events**, so protection and logging plugins see every place and break it
  makes and can cancel one (the step is then put back and refused).
- **Building by hand goes into the history by default**: the plugin knows builder mode's Record power, so for a
  player with the Sculptory 0.5.0-beta mod and the builder permission, every block they place or break with a block
  item in Creative, and every item frame, painting and armor stand they hang, place, change or break, is recorded
  without switching a power on; other item uses stay vanilla's (see Fabric 0.5.0-beta, "New").
- **Other claim plugins are not respected by bulk edits**: fills, brushes and pastes fire no block events, like
  WorldEdit's, so GriefPrevention, Lands, Towny, PlotSquared and other claim plugins neither see nor stop them. Only
  WorldGuard regions, spawn protection and the world border limit bulk edits. Builder mode is covered (above).
- **Permissions** are Bukkit permission nodes, `sculptory.*`: LuckPerms or another permissions plugin decides, and
  without a grant operators may edit. Changes in LuckPerms apply at once.
- **Jump and Through** teleport the Paper way, so a plugin that blocks a teleport blocks them too.
- **Paper 1.21.1 only**: on another Minecraft version the plugin refuses to enable and says why. Spigot and Folia are
  not supported.
- Known limits: after a crash, up to about a second of edits (five for brush strokes) saved by Paper's background save
  can't be undone; undo of water and lava takes back flows next to the edit but not chain reactions further away;
  builder mode records the blocks within two of each change, so far-reaching redstone reactions are not undone; the
  `/sculptory` command has `version` and, for admins, `fill`, `undo` and `redo` only (restart the server after
  editing `server.json`).

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
