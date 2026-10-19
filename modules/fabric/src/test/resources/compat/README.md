# Saved-file samples (frozen)

Real files written by released Sculptory builds, one folder per version. `CompatSamplesTest` and
`CompatSchematicTest` read each of them with the current code and check every stored value, so a build that would
reset or lose a player's settings after an update fails `check`.

**Never edit these files.** A change to a saved format adds a new sample set (a new version folder, written by that
build) and a migration that reads the older sets, with tests; the older samples stay exactly as they are.

Each set was written by that version's own stores and codecs (a throwaway test run from a checkout of that version:
`v0.3.0-alpha` for 0.3.0, the 0.4.0-beta line for 0.4.0), with the same distinctive values:

| File | What it holds |
|---|---|
| `editor-ui.json` | UI size 80%, quick start card dismissed, panels 55% opaque, fade when not hovered, tool outlines 35%; 0.4.0 also fly speed 2.5x (0.3.0 had none: it reads as 1x) |
| `editor-layout.json` | five windows (Selection open and collapsed, Library closed), three moved and resized at UI size 80%, two at 125% |
| `editor-keys.json` | Undo on Ctrl+U, Redo on Ctrl+Shift+U and F7, Library unbound, Jump on Alt+J, the rest as that build's defaults |
| `editor-presets.json` | Raise "Soft hills" (radius 12, strength 0.8, linear), Shape "Big cone" (radius 30, cone), Scatter "Forest" (an asset and a fern) |
| `editor-tool-settings.json` | every tool of that build; Raise at radius 9 (its preset since changed), Shape and the Scatter mix as in their presets |
| `editor-sections.json` | Raise's mask section open and symmetry section closed, Select's copy section closed |
| `editor-mask.txt` | five global mask rules, one of them "inside the selection" |
| `editor-tutorial.json` | three lessons done, the fourth at step 3 |
| `compat.schem` | a 12 x 4 x 12 export: grass, a sign with text, a chest with items, a stone pedestal and a named armor stand; 0.4.0 adds the biomes (cherry grove) |

All of them were written for Minecraft 1.21.1 (data version 3955).
