# Palettes

[Home](home.md) · [Paint, mix and plant](home.md#paint-mix-and-plant)

A palette is a named block mix, saved in the server's [library](library.md) for everyone: "mossy cobble", "beach", "dark roof". It keeps the blocks in order, their weights and the [pattern](mix-patterns.md). Loading one into a tool changes that tool's blocks and pattern, and nothing else. Use palettes to reuse a good mix across tools and players.

## Save a palette

1. Set up the mix in **Paint**, **Palette Paint**, the **Shape brush** or **Scatter**: the blocks, their weights, their order, and in Palette Paint or the Shape brush the pattern.
2. Click **Save palette…** above the tool's settings.
3. Type a path such as `palettes/moss` and click **Save**. The file is `moss.palette.json`.

## Load a palette

1. In any of those tools, click **Load palette…**.
2. Pick the palette. What it does depends on the tool:

| Tool | What Load palette… does |
|---|---|
| Paint | One block becomes the material; more than one block switches to Palette Paint |
| Palette Paint | Replaces the mix and sets the pattern |
| Shape brush | Replaces the mix, sets the pattern and sets Blocks to Mix |
| Scatter | Replaces the single blocks; assets and clipboards stay |

The Select tool's Fill palette has no save or load: set it up by hand or middle-click blocks.

## Tips

- Save your favourite ground mixes once and load them into every tool.
- Paint and Scatter save the pattern as Random; a Steepness palette loaded into the Shape brush becomes Random, with a toast.
- A Gradient palette doesn't carry its line: draw one with `Alt+drag` after loading.
- A [preset](presets.md) keeps all of a tool's settings on your computer; a palette keeps only the mix, on the server, for everyone.
- Palettes need the `clipboard` permission to load and `library.write` to save in shared folders, like other library files.

## Related

- [Mix patterns](mix-patterns.md)
- [Library](library.md)
- [Terrain brushes](terrain-brushes.md#paint)
