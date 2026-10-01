# Weather brush

[Home](home.md) · [Shape the land](home.md#shape-the-land)

The **Weather** brush (`\`, the last palette slot) makes built or generated terrain look worn by time. It works on the blocks at the surface, the way wind, rain and gravity would: **Erode** wears exposed blocks away, **Fill in** closes cracks and holes, **Roughen** breaks up a surface that is too smooth, and **Melt** lets steep edges and overhangs slump down. It takes its blocks only from the blocks around, and never touches stairs, fences, slabs, chests or the ground they stand on.

## Use it

1. Press `\`.
2. In Tool Settings, pick the **Weather**: Erode, Fill in, Roughen or Melt.
3. Point at a surface and left-drag over it, as with the [terrain brushes](terrain-brushes.md). Hold the button still and it keeps working.
4. `Ctrl+Scroll` changes the radius (`Shift`: steps of 4), `Alt+Scroll` the strength.

Each press is one undo step (changing the radius or strength mid-press starts a new one).

## The four weathers

| Weather | What happens |
|---|---|
| Erode | A block with two or more open sides (air, water or plants beside it) is worn away. The more open sides, the sooner: spikes and loose blocks first, then corners, ridges and overhang lips, then straight edges. Flat ground stays. |
| Fill in | An air gap with three or more solid sides is filled with the block most common around it: holes and pits first, then cracks from the bottom up and tight inside corners. |
| Roughen | Seeded noise digs small dents and adds small lumps along the surface. Each stroke has its own pattern; bigger brushes make bigger lumps (up to 4 blocks deep). Holding it still stops once its lumps are carved. |
| Melt | Blocks slump: one with air under it falls to the ground below, and the top of a drop steeper than 45° slides off to its foot. Blocks move rather than vanish, so a cliff sags into a slope and an overhang drips into a column. |

## Settings

| Setting | What it does |
|---|---|
| Weather | Erode, Fill in, Roughen or Melt |
| Mode | **Surface (any direction)**: every surface in the ball, walls and ceilings too. **Terrain (from above)**: only what the sky sees; caves and the undersides of overhangs stay |
| Radius | 1–32 blocks (the server may allow less) |
| Strength | How fast it works, 0–1: at 1 a block at the centre changes on every dab, at the default 0.6 on about three dabs in five |
| Falloff | How the strength fades to the edge: None, Linear, Smooth, Sphere |
| Shape | A circle (ball) or a square (cube) |
| Mask, Symmetry | Where the brush may work ([Masks](masks.md)), and mirrored copies ([Symmetry](symmetry.md)) |

## Tips

- Weather a cliff in steps: Melt to bring its face down to a slope, Erode to round the edges, Fill in to close the gaps it leaves, then Roughen once for texture.
- Use a low strength and the Smooth falloff for a gentle, blended look; strength 1 with no falloff for a hard edge.
- A brush mask of grass keeps Erode off the stone under the grass (the brush mask sees the block a change takes or gives); the [mask](masks.md) rule "is water" with Not (`Ctrl+M`) keeps Fill in out of a lake.
- The brush needs the `brush` [permission](permissions.md).

## Related

- [Terrain brushes](terrain-brushes.md)
- [Masks](masks.md)
- [Symmetry](symmetry.md)
