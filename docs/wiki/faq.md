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

Yes: in Creative (with the builder permission on a server), every block you place with a block item or break outside the editor already goes into the history, and so does every item frame, painting and armor stand you hang, place, change or break (the Record power, on from the start), so `Ctrl+Z` takes it back. Buckets, bone meal and other item uses stay vanilla's. Hold `G` for more powers. See [Builder mode](builder-mode.md).

### Do my settings survive a restart or an update?

Yes: every tool's settings, your keys, the UI size, opacity and window layouts are saved on your computer and carry over to newer Sculptory versions. The symmetry centre and the gradient line are not kept: see [Tool Settings](tool-settings.md) and [Updating Sculptory](updating.md).

## Servers

### Do players need the mod to join?

No. Only players who edit need Sculptory on their client, speaking the same network protocol as the server's. Their loader may differ from the server's, and on a Paper server they use the Fabric or NeoForge mod: see [Server setup](server-setup.md).

### Who can edit by default?

Operators of level 2 or higher, and the owner of a singleplayer world: see [Permissions](permissions.md).

### Can I use LuckPerms?

Yes, on all three: the nodes are `sculptory.*`, see [Permissions](permissions.md). On NeoForge install LuckPerms's **5.4.150** build (labelled for 1.21.4; it runs on 1.21.1). The build labelled for 1.21.1, 5.4.140, kicks every player at login on current NeoForge 21.1 releases, and other permissions mods are untested on NeoForge.

### Will big edits lag the server?

Edits get a fixed time per tick (10 ms by default) and spread over more ticks, so the tick rate holds.

### Does it respect claims and spawn protection?

Spawn protection and the world border, everywhere: protected blocks are skipped, and a click there in builder mode is refused. Claims depend on the server:

- **Fabric and NeoForge:** claim mods that hook the game's own build check are respected the same way.
- **Paper:** WorldGuard regions are respected by every edit. Other claim plugins (GriefPrevention, Lands, Towny, PlotSquared and the like) are **not** respected by bulk edits (fills, brushes, pastes, undo), which fire no block events, like WorldEdit's. Builder mode places and breaks by hand through the block events, so those plugins can refuse it. See [On Paper](server-setup.md#on-paper).

### Which file formats work?

`.schem` (WorldEdit), `.litematic` (Litematica) and `.nbt` (structure blocks), in and out: see [Import and export](import-export.md).

### Is there a Paper, Forge or NeoForge version?

NeoForge and Paper, yes, for Minecraft 1.21.1, both betas: the NeoForge mod works like the Fabric one, and the Paper plugin is the server side, with players editing through the mod for their loader. Fabric and NeoForge players can edit on each other's servers. Forge isn't there yet. The README's Install section says where to get each; see [Server setup](server-setup.md) for servers.

### Does the Paper plugin run on Spigot, Purpur or Folia?

It is made for Paper 1.21.1 and tested on build 133. On Spigot it doesn't load, since it needs Paper's own code. Folia refuses it: it isn't made for Folia's threads. On a Paper for another Minecraft version (1.21.2 or newer, say) the plugin refuses to enable and says why in the log. Paper forks such as Purpur are untested.

## Known limits

Sculptory is a beta. The limits you are most likely to meet:

- Minecraft 1.21.1 only, on Fabric, NeoForge and Paper: no Forge or other Minecraft versions yet.
- On Paper, undo after a crash, undo of water and lava, builder mode's chain reactions and the commands differ a little from Fabric and NeoForge: see [Known limits on Paper](server-setup.md#known-limits-on-paper). Claim plugins other than WorldGuard don't limit bulk edits there.
- On NeoForge, LuckPerms needs its 5.4.150 build (the build labelled for 1.21.1 kicks players at login); other permissions mods are untested there.
- The server and every editing client need the same network protocol, and the same build to avoid a warning; see [Server setup](server-setup.md#updating).
- Most of it is covered by automated tests, but two players editing at once, and Sodium and Iris, haven't been tried much in a real game yet. Keep backups of worlds you care about.
- With an Iris shader pack on, ghost previews are drawn as outlines.
- WorldEdit and Sculptory share only `.schem` files: selections, clipboards and undo histories are separate.
- Leaving a server drops your clipboard and scatter preview; your undo history is kept.
- No command undoes or inspects another player's edits.
- `/sculptory reload` doesn't apply the `history`, `library` and `transform` settings: those need a restart.
- [Generate](generate.md#stamp-copies)'s Stamp copies always turn with the Nearest method, whatever [Free rotation](free-rotation.md)'s **Method** says.
- **Use stairs and slabs** (Line, Road) places straight stairs only, never corner shapes.
- A [Heightmap brush](heightmap.md) stamp at radius 128 needs a lot of server memory: it can bring a 4 GB server close to full memory and make it pause briefly.
- `Ctrl+K` finds commands, tools, windows and the active tool's settings, but not library assets or blocks. The menus have no keyboard shortcuts of their own.

## Related

- [Troubleshooting](troubleshooting.md)
- [Getting started](getting-started.md)
- [Tools at a glance](tools.md)
