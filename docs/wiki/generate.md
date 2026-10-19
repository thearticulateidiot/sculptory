# Generate

[Home](home.md) · [Build faster](home.md#build-faster)

**Generate** (`-`) builds a **road** along points you click, a **roof** over the selection, a **line** through blocks you click, a **sweep** of the selection along a path, **stamps** of your clipboard on blocks you click, or a **heightmap**: terrain shaped from a picture over the selection. It shows the result first, and `Enter` builds it as one undo step named **Road**, **Roof**, **Line**, **Sweep**, **Stamp** or **Heightmap**. Use it for paths that hug the land, embanked highways, roofs of stairs and slabs that would take an evening by hand, cables, pipes, arches and beams, walls with a profile of your own, rows of lamp posts or trees, and whole landscapes from a picture.

![Tool Settings for Generate](images/generate-settings.png)

## Build a road

1. Press `-` and set **Generate** to **Path**.
2. Put [points](#points), the road's nodes, on the ground (or in the sky). From two nodes on, the road's footprint is drawn on the terrain with a centre line, and the road itself as a ghost.
3. Press `Enter` to build. `Esc` clears the path.

A road node put in the air sets **Height** to **Straight between points** (a message says so), so the road runs through its nodes: a floating deck with nothing under it. `Esc` clearing the road puts **Height** back. With **Straight between points**, **Fill below** only fills where something lies under the deck within its depth, so a ramp up from the ground keeps its embankment near the ground and becomes a bare deck higher up; where the road crosses chunks your game hasn't loaded it is a bare deck too, and a message counts those columns. With **Follow terrain** the fill is as before.

## Build a roof

1. Select the building with [Select](select.md): the box's bottom layer is where the eaves sit.
2. Press `-` and set **Generate** to **Roof**. The roof's outline shows over the selection.
3. Choose the **Style**, **Pitch** and blocks, then press `Enter`.

## Draw a line

1. Press `-` and set **Generate** to **Line**.
2. Put [points](#points) on blocks or in the sky; the line runs through their centres.
3. Pick the **Path**: **Straight** from point to point, **Curve** smoothly through every point (drag the last point onto the first for a closed loop, of four points or more with the closing one), or **Hanging**, sagging between each pair of points by **Sag** blocks, like a rope or a power line.
4. Set the **Thickness** (1–16, `Ctrl+Scroll`) and, from 2 up, the **Profile**: **Round** or **Square**.
5. Press `Enter` to build. `Esc` clears the points.

## Sweep a profile

1. Build the profile: a slice of the wall, bridge or tunnel you want, usually one block thick, and select it with [Select](select.md).
2. Press `-` and set **Generate** to **Sweep**.
3. Put [points](#points) for the path, as for a [line](#draw-a-line). The profile stands on them, its bottom middle on top of each block, and turns to follow the path, staying upright. Tick **Follow slope** to tilt it as the path climbs.
4. Press `Enter` to build: one undo step named **Sweep**.

A profile thicker than one block repeats along the path: a fence profile three blocks thick with a post in one slice puts a post every three blocks. Stairs and other blocks that face a way turn with the path to the nearest quarter turn. Air in the profile is not swept, and blocks with contents (chests, signs) are left out, with a message.

## Stamp copies

1. Copy something (`Ctrl+C`), or pick a saved build in the [Library](library.md) and click **Stamp**.
2. Press `-` and set **Generate** to **Stamp** (the Library's **Stamp** button does this for you and sets **Copy** to **Library asset**).
3. Put [points](#points): a copy stands on each, as a paste would. The first copy is as you copied it; the others turn with the path through the points, by any angle, as a pasted copy turned in the [Place](place.md) tool does (stairs and other blocks that face a way take the nearest facing). `R` turns every copy a quarter more.
4. Press `Enter` to build: one undo step named **Stamp**, at most 256 copies at once.

Stamps are pasted by the server from its own copy, so chests keep their contents and entities such as armor stands and item frames come along.

## Points

The Road, Line, Sweep and Stamp, and Extrude along a path or to a point, are built through points. A click puts a point on the block you aim at; aimed at open sky, it goes on the pale blue **marker** floating ahead of you, its distance beside the cursor (`Alt+Scroll` moves it nearer or farther, `Shift+Alt+Scroll` four blocks at a time, 2 to 256 blocks away). `Ctrl+Scroll` still sets the road's width and the line's thickness.

- Click a point to select it; drag a point to move it. A point dragged into the sky keeps its distance from you (look up and it rises; `Alt+Scroll` while dragging pushes it out or pulls it in); dragged onto a block, it goes on the block. `Esc` while dragging puts the point back.
- `Backspace` or `Delete` removes the selected point, or the last one when none is selected.

## Import a heightmap

1. Select the area with [Select](select.md). Its footprint is where the picture goes; its bottom and top become **Min Y** and **Max Y**.
2. Press `-` and set **Generate** to **Heightmap**.
3. Click **Picture** and pick one: a built-in or one of your own PNGs (as for the [Heightmap brush](heightmap.md#your-own-pictures)).
4. Set **Min Y** (the ground where the picture is black) and **Max Y** (where it is white). They start at the selection's bottom and top each time you select something new, and may reach beyond it.
5. **Turn** the picture in quarter turns, or **Invert** it (a hill becomes a pit). The ghost shows the result.
6. Press `Enter` to build.

The picture is stretched over the whole footprint, so a wide selection makes a wide hill. In each column the ground is set to the picture's height: everything above it, up to the top of the selection (or Max Y), becomes air, trees and water included; below it the column keeps its own blocks, as with the [Heightmap brush](heightmap.md#raise-and-lower): grass stays on top, the dirt beneath grows or is cut.

## Settings

| Setting | What it does |
|---|---|
| Width | Road width, 1–32; `Ctrl+Scroll` |
| Material | One **Block**, or a weighted **Mix** ([Mix patterns](mix-patterns.md)); **Border** adds a second block for the outer columns |
| Height | **Follow terrain** (smoothed along the road) or **Straight between points** |
| Level across | One height across the road's width, or each column on its own ground |
| Fill below, Clear above | Blocks filled under the road (Straight between points: only where they reach the ground), and cleared above it, 0–8 |
| Style | **Gable**, **Hip** or **Shed** (rising from the **Low side**) |
| Pitch | **45° (stairs)**, **Steep** (two up per one across, full blocks) or **Gentle** (one up per two across, slabs) |
| Ridge along, Overhang, Thickness | Which way a gable's ridge runs, how far the eaves reach past the box (0–4), layers under the surface |
| Stairs, Slab, Full block | The roof's blocks: picking the stairs fills in the slab and full block of the same wood or stone |
| Gable walls, Inside | Fill the triangular ends; leave or hollow the space under the roof |
| Path, Sag | The line's shape through its points; how far a hanging span drops at its middle, 0–64 |
| Thickness, Profile | The line's width across, 1–16; round (a tube) or square (a beam) |
| Material (Line) | One **Block**, or a weighted **Mix** |
| Path, Sag (Sweep) | The sweep's path through its points; how far a hanging span drops at its middle |
| Follow slope | The profile tilts as the path climbs, instead of staying upright |
| Copy | What Stamp copies: your **Clipboard**, or the **Library asset** picked with the Library's **Stamp** button |
| Picture | The height picture of a Heightmap import |
| Min Y, Max Y | The ground's height where the picture is black, and where it is white |
| Turn, Invert | Turn the picture 0°, 90°, 180° or 270° clockwise; swap high and low |

## Tips

- **Clear above** cuts the road through a forest; **Fill below** builds an embankment across a dip.
- Pick the **Stairs** block first: the slab and full block fill in when they share its name.
- Chunks the road crosses that your game hasn't loaded show as red boxes and stop the build: fly closer.
- A thin **Hanging** line between two towers makes a cable or a rope; a thick **Curve** of stone makes an arch. Blocks are placed as they are: fences and walls in a line don't connect to each other.
- A line reaching into chunks your game hasn't loaded won't build: fly closer.
- A sweep needs a selection: the profile is read when you click, so change the selection and the ghost follows.
- Where two stamped copies overlap, the one clicked later wins.
- A heightmap import clears everything above the new ground inside the selection: select only the land, not the houses next to it. Chests, furnaces, signs and other blocks with contents are left exactly as they are (a message counts them). A column's ground is its topmost solid block, so a tree trunk or a roof inside the selection is taken for ground (its block is what the column grows or is cut to): clear them first.
- Generate needs the `clipboard` and `region` [permissions](permissions.md).
- The [Shape brush](shape-brush.md#sweep-a-shape-along-a-line) sweeps any of its shapes along the same kind of line, and can carve with it.

## Related

- [Select](select.md)
- [Place](place.md)
- [Extrude](extrude.md)
- [Heightmap brush](heightmap.md)
