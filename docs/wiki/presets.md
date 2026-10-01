# Presets

[Home](home.md) · [Build faster](home.md#build-faster)

A preset saves all of one tool's settings under a name: a "cliff smoothing" Smooth brush, a "cobble road" Generate setup. It is kept on your computer and works in every world and on every server. Use presets when you keep switching between two setups of the same tool.

## Save a preset

1. Set the tool up in **Tool Settings**: radius, strength, mask, symmetry, everything.
2. In the preset row at the top of Tool Settings, click **Save as…**.
3. Type a name and press `Enter`.

## Load a preset

1. Pick the preset from the drop-down in the preset row. Every setting of the tool changes at once.
2. Change what you like: "(modified)" appears after the name.
3. Pick the preset again to drop your changes, or click **Save** to overwrite it with them.

**Rename** and **Delete** do what they say. `Ctrl+K` also finds a preset of the active tool by name.

## What a preset keeps

| Kept | Not kept |
|---|---|
| Every setting in Tool Settings, sections included | The symmetry centre (a point in one world) |
| The Mask section and the Symmetry mode | The active block in the top bar |
| Palette Paint's mix and the Shape brush's mix | Scatter's clipboard variants: save them to the [library](library.md) first |

## Tips

- Settings are kept across restarts anyway; a preset is for switching between several setups of one tool.
- A [palette](palettes.md) is the opposite: it keeps only the blocks, on the server, for everyone and every tool.
- To share presets with another player, copy `config/sculptory/editor-presets.json` from your game folder into theirs (it replaces their presets).
- The **↺** button beside a changed setting resets that one setting; picking the preset again resets them all.

## Related

- [Tool Settings](tool-settings.md)
- [Palettes](palettes.md)
