# Server and library samples (frozen)

Real files written by released Sculptory builds, one folder per version. `CompatSamplesTest` reads each with the
current code and checks every stored value, so a build that would lose library palettes, access grants or the server
config after an update fails `check`.

**Never edit these files.** A change to a saved format adds a new sample set (a new version folder, written by that
build) and a migration that reads the older sets, with tests; the older samples stay exactly as they are.

Each set was written by that version's own codecs (a throwaway test run from a checkout of that version):

| File | What it holds |
|---|---|
| `mossy.palette.json` | three weighted blocks (one with block state properties) and a Patches mix pattern with its seed |
| `.access.json` | a schematic shared with two players, a palette with one, an open entry (not written) |
| `server.json` | that build's whole config with 17 values changed from the defaults (0.4.0 also the Heightmap brush limits) |
