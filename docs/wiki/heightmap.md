# Heightmap brush

[Home](home.md) · [Shape the land](home.md#shape-the-land)

The **Heightmap** brush (`;`) stamps a height picture onto the terrain: white parts raise the ground the most, black parts leave it as it is. Click once for a hill, a crater or a mesa where you point; drag to paint a ridge or a valley along your path. To shape a whole selected area from one picture, use [Generate](generate.md#import-a-heightmap)'s **Heightmap** instead.

## Use it

1. Press `;`.
2. In Tool Settings, click **Picture** and pick one: Hill, Mountain, Crater, Mesa, Ridge, Dunes, Volcano or Terraces, or one of your own (below).
3. Point at the ground. The stamp's square follows the cursor, tinted where the picture lifts the ground, with a tick pointing to the picture's top edge.
4. Click to stamp it, or left-drag to paint.

Each press is one undo step. Changing a setting mid-press starts a new one; so does holding the button still for 5 seconds, and a very large stroke (around a million blocks, a few tall stamps at the largest size) is saved as several steps.

| Key | What it does |
|---|---|
| Left-click | Stamp the picture once |
| Left-drag | Paint: a ridge (or a trench) the height of the picture along the drag |
| `R`, `Shift+R` | Turn the picture a quarter turn clockwise, or back |
| `Ctrl+Scroll` | Size (radius 1–32; `Shift`: steps of 4) |
| `Esc` | Stop the stroke (what it did stays, one undo step) |

## Raise and Lower

- **Raise** lifts each column by the picture's brightness times **Height**. The column keeps its own blocks: the grass stays on top and the dirt (or whatever lay under the top block) grows beneath it.
- **Lower** cuts each column down the same way. The new ground takes the column's top block, so a meadow cut down is still a meadow; a cut below a lake's surface fills with water.

A drag never goes higher (or deeper) than one stamp above the ground the stroke started on: going over the same spot again doesn't pile up, so you can paint a long, even ridge by dragging back and forth. To build higher, start a new stroke on top.

## Settings

| Setting | What it does |
|---|---|
| Radius | Half the stamp's width: the picture covers `2 × radius + 1` columns square (1–32; the server may allow less) |
| Picture | The height picture |
| Mode | **Raise** or **Lower** |
| Height (blocks) | How far a white pixel moves the ground, 1–128 |
| Angle (degrees) | How far the picture is turned, clockwise seen from above; `R` adds a quarter turn, or type any angle |
| Mask, Symmetry | Where the brush may work ([Masks](masks.md)), and mirrored copies ([Symmetry](symmetry.md)): each copy stamps the mirrored or turned picture |

## Your own pictures

Put grayscale PNG files in `.minecraft/sculptory/heightmaps/` (the picker shows the full folder and has an **Open folder** button). They appear in the picker the next time you open it, after the built-in ones, by file name. Any PNG works: colour pictures use their brightness, and see-through parts count as black. Pictures larger than 1,024 pixels a side are shrunk to fit as they are read. Make the picture's edges black so the stamp blends into the ground around it.

A file that can't be read (not a PNG, damaged, or larger than 4096 pixels a side) is skipped, and a message names it once. A preset that names a picture you don't have keeps the name and says it is missing when you use it.

## Tips

- A small radius and a low height make bumps, mounds and boulders; radius 32 and a height of 60 or more make a mountain in one click.
- Drag the **Ridge** picture along its length for a long mountain chain; turn it with `R` to follow your path.
- **Lower** with the **Crater** or **Hill** picture digs a basin; fill it with the [Fluid](fluid.md) tool for a lake.
- Smooth the result with the [terrain brushes](terrain-brushes.md), and weather it with the [Weather brush](weather.md).
- The brush works from above only: walls and the undersides of overhangs are left alone, as are stairs, fences and chests.
- The brush needs the `brush` [permission](permissions.md).

## Related

- [Terrain brushes](terrain-brushes.md)
- [Generate](generate.md)
- [Masks](masks.md)
- [Symmetry](symmetry.md)
