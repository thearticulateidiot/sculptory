# Free rotation

[Home](home.md) · [Copy, paste and share](home.md#copy-paste-and-share)

A paste, a move or the selection can turn by any angle around any axis: up (yaw), east-west (pitch) and north-south (roll). Blocks only sit on a grid, so a turn that isn't a multiple of 90° has to decide which block lands in each cell. The **Method** setting chooses how, and **Smooth slopes** can put stairs and slabs on the turned slopes.

## Turn something

1. Paste (`Ctrl+V`), move the selection, or click **Rotate…** in the Selection window. A ghost of the blocks appears; for Rotate… it stands where the selection is.
2. Drag a ring of the gizmo: green turns around up, red around east-west, blue around north-south. It turns in steps of 15°; hold `Ctrl` to turn freely.
3. Or type **Yaw**, **Pitch** and **Roll** in Tool Settings.
4. Pick the **Method** under **Free rotation** in Tool Settings. The ghost shows the result of the method you picked, exactly as it will be placed.
5. Press `Enter`. A Rotate… is one undo step named "Rotate", and afterwards the selection is exactly the turned blocks.

Rotate… turns the blocks around the symmetry centre (set with `M`) at the selection's middle height, or around the selection's middle when no centre is set: the gizmo sits there. Nudge the ghost with the arrow keys before `Enter` to put it somewhere else; a Rotate… that is only nudged is an ordinary move.

## Methods

| Method | What it does | Looks like |
|---|---|---|
| Nearest | Each cell takes the block nearest to where it turns back to in the original. The default | No holes in a turned solid, but along edges some blocks are doubled and others lost, so edges look jagged |
| Exact count | Every block of the original lands in exactly one cell: none doubled, none lost | The same number of blocks as the original. A block can sit a block or two from where an exact turn would put it, most at once around several axes |
| Smooth | Each cell weighs 27 points spread through it and takes the most common block when more than half of them fall on blocks; otherwise it stays empty | Fuller shapes and rounder edges. Thin details can be lost |

Turns of exactly 90°, 180° or 270° on any axis are the same in all three methods: every block moves to its own cell. The method is remembered and used for every free turn of a paste, a move or Rotate… until you change it.

Stairs, logs, pistons, observers and other blocks with a direction snap to the nearest facing in every method. A block that can't take that facing keeps its nearest valid form, and a toast counts them once the ghost has settled.

## Doors, beds and tall plants

Doors, beds and two-block plants (tall grass, sunflowers, small dripleaf) stay whole: each one is placed from its lower half (a bed from its foot), and the other half goes right next to it. A door or plant stays upright, and a bed turns with the build to the nearest of the four directions. When the other half has no room, both halves are left out, and a toast counts them.

## Smooth slopes

With **Smooth slopes** ticked, a full block on the top or the underside of a turned slope becomes the stair or slab of its own kind where that follows the slope better. Stone bricks become stone brick stairs or slabs, oak planks oak stairs or slabs, and so on. The tall side of each stair points up the slope. Blocks without stairs or slabs, such as dirt or glass, stay full. Smooth slopes only changes blocks, never adds or removes them, so with Exact count the number of blocks stays the same. It judges the slope by the exact turn, so with Exact count a stair can sit where the block grid is a block off the slope.

## Limits

- Biomes carried by a turned paste or move always use Nearest.
- A smooth turn costs four times as much of the server's operation limit as a nearest one, and Smooth slopes adds one more. A smooth move or paste can therefore be refused as too large where a nearest one is not. How much a smooth turn may move depends on its shape too: a solid build can use most of the limit, a wall one block thick about half of it, and a line or a few blocks spread over a large area about a tenth.
- With **Paste into** set to Only air or Only existing blocks, a door, bed or tall plant is placed only when both of its cells allow it. A move that cannot place one leaves it out, and its old place is cleared all the same, as for any block **Paste into** skips (undo brings it back).
- Where copies of a stamp or a symmetric paste overlap, the copy that wins a cell can take one half of a door from another copy.
- Stack and Scatter turn by quarter turns only.

## Related

- [Place](place.md)
- [Selection operations](selection-operations.md#rotate)
- [Symmetry](symmetry.md)
- [History](history.md)
