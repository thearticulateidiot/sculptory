# FAQ

[Home](home.md) · [Help](home.md#help)

Short answers, each with the page that says more.

## Using the editor

### Do I need WorldEdit?

No. Both can be installed together, and `.schem` files move between them: see [Import and export](import-export.md).

### Does it work in survival?

Yes, but you can only fly in Creative, and [builder mode](builder-mode.md) needs Creative. Edits never use your inventory.

### I forgot a key.

Press `F1` for the key sheet, or `Ctrl+K` and type what you want to do: see [Finding things](finding-things.md).

### Can I undo someone else's edit?

No. Each player undoes only their own edits: see [History](history.md).

### Why doesn't sand fall after an edit?

Edits skip block updates so they land exactly as asked. Place's **Block updates (physics)** turns them on for a paste, with the `physics` permission: see [Place](place.md).

### How do I change one block without breaking it?

Point at it with [Tinker](tinker.md) (`]`) and scroll through its properties, or click it for the full panel.

### How do I get a natural mix of blocks?

Palette Paint with a weighted mix and a [pattern](mix-patterns.md): Patches for clumps, Gradient for a fade, Steepness for grass on flats and stone on slopes.

### Can I build by hand with the editor's undo?

Yes: hold `G` outside the editor and switch a power on. See [Builder mode](builder-mode.md).

### Do my settings survive a restart?

Yes: every tool's settings, your keys, the UI size and opacity are saved on your computer. The symmetry centre and the gradient line are not: see [Tool Settings](tool-settings.md).

## Servers

### Do players need the mod to join?

No. Only players who edit need the same Sculptory build as the server: see [Server setup](server-setup.md).

### Who can edit by default?

Operators of level 2 or higher, and the owner of a singleplayer world: see [Permissions](permissions.md).

### Will big edits lag the server?

Edits get a fixed time per tick (10 ms by default) and spread over more ticks, so the tick rate holds.

### Does it respect claims and spawn protection?

Yes: protected blocks are skipped, and a click there in builder mode is refused.

### Which file formats work?

`.schem` (WorldEdit), `.litematic` (Litematica) and `.nbt` (structure blocks), in and out: see [Import and export](import-export.md).

### Is there a Paper, Forge or NeoForge version?

Not yet. A Paper port is planned.

## Known limits

Sculptory is an alpha. The limits you are most likely to meet:

- Fabric 1.21.1 only: no Paper, Forge, NeoForge or other Minecraft versions yet.
- The server and every editing client need the same build; see [Server setup](server-setup.md#updating).
- Most of it is covered by automated tests, but two players editing at once, and Sodium and Iris, haven't been tried much in a real game yet. Keep backups of worlds you care about.
- With an Iris shader pack on, ghost previews are drawn as outlines.
- WorldEdit and Sculptory share only `.schem` files: selections, clipboards and undo histories are separate.
- Leaving a server drops your clipboard and scatter preview; your undo history is kept.
- No command undoes or inspects another player's edits.
- `/sculptory reload` doesn't apply the `history`, `library` and `transform` settings: those need a restart.
- `Ctrl+K` finds commands, tools, windows and the active tool's settings, but not library assets or blocks. The menus have no keyboard shortcuts of their own.

## Related

- [Troubleshooting](troubleshooting.md)
- [Getting started](getting-started.md)
- [Tools at a glance](tools.md)
