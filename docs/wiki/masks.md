# Masks

[Home](home.md) · [Shape the land](home.md#shape-the-land)

A mask limits which blocks an edit may change. There are two:

- **The Mask** in the top bar applies to every edit: fills, Replace, pastes and stacks, brushes, the Shape brush, Extrude, Generate, Fluid, Scatter and builder mode. Switch it on, and every edit changes only the blocks its rules let through.
- **A brush's own mask**, in the brush's Tool Settings, applies to that brush only.

When both are on, a block must pass both.

## The Mask (every edit)

1. Click **Mask** in the top bar, next to the active block. The Mask window opens.
2. Click **+ Add rule** and pick a rule. Add more if you like: **every rule must match**.
3. Tick **Not** on a rule to flip it ("Not on top of grass").
4. Switch **Mask on**. The chip glows orange while the mask is on.

**Ctrl+M** switches the mask on and off from anywhere in the editor. The rules are kept between games; every game
starts with the mask off.

| Rule | A block passes when |
|---|---|
| Is one of | It is one of the listed blocks or block tags (up to 16). Type a state such as `oak_log[axis=y]` in the picker for an exact state |
| Sits on top of | The block below it is one of the listed ones |
| Is under | The block above it is one of the listed ones |
| Is next to | One of the six blocks around it is one of the listed ones |
| Touches air | One of the six blocks around it is air |
| Not air | It is not air |
| Solid only | It is a full, solid block (stone, dirt; not stairs, fences or plants) |
| Height between | Its height is in the range |
| Slope between | The ground under it is that steep: the steepest step, in blocks, from its column's surface to a neighbouring column's (looked for up to 16 blocks above and below it) |
| Inside the selection | It is inside the selection as it is when you edit. With nothing selected it matches no block |
| Random percentage | A random share of blocks, the same ones every time; **Re-roll** picks another pattern |

Each block is tested as the world was **before** the edit, so a fill under "Sits on top of stone" makes one layer,
not a tower. The ghosts show what the mask lets through.

**Move, Cut and Copy** test where the blocks are taken from: only matching blocks are lifted (the rest stay where they
are), and a moved block lands wherever it goes. **Undo and redo are never masked**: they always restore exactly.

In **builder mode** a placement the mask refuses is not made (the server says why); breaking skips the blocks the mask
keeps.

## A brush's own mask

1. Pick a terrain brush (`2` to `7`).
2. In Tool Settings, open the **Mask** section and click **Edit…** under "Brush only where".
3. Add rules as in the Mask window. **Invert the whole mask** brushes only where the rules do not all match.

A brush's mask is tested at the ground block the brush works on (the top of a column, or in **Surface** mode each
block that changes), and its slope is the brush's own measure (a wall counts as 16). **Only inside selection** keeps
the brush inside the selection box as it is when the stroke starts; Invert does not flip it.

Presets saved before rules existed keep working exactly as before: their old block, height, slope and invert settings
become rules.

## Tips

- A path only on grass: **Paint** with gravel and the brush mask "Is one of: Grass Block".
- Snow only on the peaks: the Mask with "Height between 120 and 319", then paint.
- Moss only where it touches air: the Mask with "Touches air" and "Is one of: Stone", then Replace stone with moss.
- Keep a build safe while you terraform: the Mask with Not "Inside the selection" around it.
- Thin out a fill: "Random percentage" at 30.

## Related

- [Terrain brushes](terrain-brushes.md)
- [Selection operations](selection-operations.md)
- [Mix patterns](mix-patterns.md)
- [Presets](presets.md)
