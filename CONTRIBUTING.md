# Contributing to Sculptory

Thanks for helping. Bug reports, ideas and pull requests are all welcome.

## Licence of your contribution

There is no contributor licence agreement (CLA). What you contribute is licensed the same way as the project, and
nobody gets extra rights over it:

- code and build files: **AGPL-3.0-or-later**, with the section 7 additional permission and terms in `NOTICE.md`;
- documentation and wiki pages and pictures (`docs/`, `docs/wiki/`): **CC BY-SA 4.0**.

`docs/LICENSING.md` explains which licence covers which files; that is "the open source license indicated in the
file" in the certificate below. You keep the copyright in your work. Only add code, text or pictures you have the
right to share under these licences (no copied code from closed or incompatible projects, no game assets you didn't
make).

## Sign off your commits (DCO)

Each commit must be signed off to certify the Developer Certificate of Origin below. Add the sign-off with `-s`:

```
git commit -s -m "brush: Smooth keeps the ball footprint"
```

This adds a line at the end of the message, taken from your git identity:

```
Signed-off-by: Your Name <you@example.com>
```

Use a name you are known by (it need not be your legal name) and an email that reaches you; a GitHub no-reply
address is fine. The sign-off is public and stays in the history. Forgot it? `git commit --amend -s` fixes the last
commit, and `git rebase --signoff main` fixes a branch.

```
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```

## Where the code lives

The code is in seven Gradle modules under `modules/`:

- `core`: the editing engine without Minecraft: regions, masks, brushes, edit programs, scatter planning, undo
  history and its saved format, schematic and NBT reading. Plain Java, tested with JUnit.
- `protocol`: the client-server network protocol (messages, codecs, the handshake), also free of Minecraft
  imports, with golden wire samples in its tests.
- `server`: the server side that every platform shares (Fabric, NeoForge and Paper): the edit,
  clipboard and scatter services, the job executor, undo history, the library, config, permission and protection
  rules and file sanitizing. No Minecraft imports either: it reaches the game through the interfaces in
  `dev.sculptory.server.platform`, which each platform implements.
- `common`: the Minecraft side that needs no mod loader, in Mojang's official names (`dev.sculptory.mc`): `src/main`
  is the server platform those interfaces describe (world access, builder mode, Tinker, the command tree, the payload
  codecs, the server mixins), `src/client` the editor (windows, tools, rendering, the tutorial and the wiki reader,
  the client mixins). It compiles against vanilla Minecraft with no loader API on its classpath, so the compiler
  proves it is loader-free; the game's events, networking, key bindings and folders reach it through the small
  `dev.sculptory.mc.loader` interfaces (`ServerLoader`, `ClientLoader`), which each loader implements. This module
  ships no jar: each loader module compiles these sources into its own. `src/gametest` holds the GameTests every
  loader runs (compiled here with no loader API too; what they need from a loader is `GameTestLoader`), and
  `src/fidelity` the mod-fidelity GameTests, which only the loader modules compile, each against its own build of the
  tested mods.
- `fabric`: the Fabric mod: the entrypoints, `FabricServerLoader` and `FabricClientLoader` (Fabric API events,
  networking, permissions api, key bindings), the client commands typed on Fabric's command source, `fabric.mod.json`;
  `src/test` the JUnit tests (for the common code too), and `src/gametest` and `src/fidelity` the Fabric test mods
  that run the shared GameTests.
- `neoforge`: the NeoForge mod, built with ModDevGradle beside Loom in the same Gradle build: `SculptoryNeoForge` (the
  one `@Mod` entrypoint), `NeoForgeServerLoader` and `NeoForgeClientLoader` over NeoForge's events, networking,
  permission API and key mappings, and a few mixins of its own where NeoForge has no event with the meaning the seams
  promise (join, server stopping, after-save, the client's interaction hooks). Common's sources compile into its
  single source set; the access transformer is generated from common's access widener at build time, so the two
  never drift, and the engine (`core`, `protocol`, `server`) is packed into the jar as classes. `src/gametest` and
  `src/fidelity` register the shared GameTests with NeoForge's GameTest server (`src/gametestSupport` is their
  `GameTestLoader`).
- `paper`: the Paper plugin (a Bukkit `plugin.yml` plugin), built with paperweight-userdev against Paper's server in
  Mojang's names, so nothing is reobfuscated. It takes common's `src/main` as source too, but through a **patched
  copy**: the `commonForPaper` task copies those sources into `build/generated/commonForPaper`, leaves out what Paper
  can't run (the mixins, the loaders' command tree and engine wiring, the mixin-backed builder steps) and applies a
  handful of literal patches where no seam covers a Paper difference (the physics-off block write, the light marker,
  CraftBukkit's entity keys, the methods Paper's level interfaces add). Every patch must match, and drift guards
  forbid new uses of what the patches replace, so a change in `common` that moves one fails the Paper build instead
  of compiling the old behaviour. `src/commonReplacements` holds the one class Paper swaps in whole (the light
  batch), and the `probe` source set compiles all of common through the same patches as a check. The plugin wires the
  engine itself (`PaperEngine`, `PaperEditHost`, `PaperCommands`), protection through WorldGuard when it is
  installed, and builder mode through CraftBukkit's block events (`PaperBuilderSteps`).

The loader seams are the interfaces common code reaches a platform through, and the only place a platform plugs in:
`ServerLoader` and `ClientLoader` (`dev.sculptory.mc.loader`: events, networking, key bindings, folders),
`McInternals` (reads of members the access widener opens; Paper's is `PaperAccess`), `McPermissions` (permission
nodes and chunk permits), `McHost` (what Tinker and Navigate need from the host, such as a teleport), `BuilderSteps`
(how builder mode runs and records one action: the mixin capture on the loaders, CraftBukkit's events on Paper) and,
for the tests, `GameTestLoader`. New platform-specific behaviour goes behind one of these rather than into common.

Paper has no GameTests, so the plugin has a **self-test runner**: groups of checks against the real server (edits
and exact undo, physics-off writes, WorldGuard refusals, entities, builder mode, Tinker and Navigate, fluid trails,
scatter, permission changes, history saving, the join race). It is only in the plugin's **dev jar**:
`scripts/gradle.ps1 :paper:devJar` builds `modules/paper/build/libs/sculptory-paper-<build id>-dev.jar`, the release
jar plus the self-test; the release jar has none. In the dev jar it is active only when the server starts with
`-Dsculptory.selftest.enabled=true` (or the property below), and runs:

- by `/sculptory selftest [player]`, from the console or for a player with the `sculptory.selftest` permission (false
  by default, operators included);
- at start-up with `-Dsculptory.selftest=<report.xml>`, writing a JUnit-style XML report there; add
  `-Dsculptory.selftest.stop=true` to stop the server when it is done, and `-Dsculptory.selftest.player=<name>` to run
  as a real client that has joined instead of a fake player (some checks run only then).

The `paper-selftest` workflow (`.github/workflows/paper-selftest.yml`) runs the dev jar's self-test at start-up on
Paper 1.21.1 with WorldEdit and WorldGuard, once per redstone implementation, and fails on any failed check or on fewer
checks than it expects. CI runs it on every push, and the release workflow before a Paper release is published. Every
box a check touches is restored at the end.

The wiki lives in `docs/wiki/` and is bundled into the mod for the in-game reader. Its page ids are frozen
(`WikiPages`); `scripts/check-wiki.ps1` checks its links, pictures and Markdown subset, and `check` runs the same
rules in `WikiRepositoryTest`.

## Build and test

You need JDK 21. Nothing else needs installing: the Gradle wrapper fetches the rest.

- Linux and macOS: `./gradlew check`, with `JAVA_HOME` pointing to a JDK 21.
- Windows: `scripts/gradle.ps1 check`. The script finds a JDK 21 in `$env:SCULPTORY_JDK`, then in a
  `toolchains\jdk-21*` folder beside the repository (or beside one of its parent folders), then in `JAVA_HOME`.

`check` runs the JUnit tests and the headless GameTests on Fabric and on NeoForge (vanilla, and mod fidelity with
Chipped and Farmer's Delight, which it downloads) and must pass before a pull request is merged. GitHub Actions runs it
on every push and pull request (`.github/workflows/ci.yml`). Logic in `core`, `protocol` and `server` gets JUnit tests
and stays free of Minecraft imports; anything that changes a world gets a GameTest. Changes to world edits, undo and
redo, saved formats, the network protocol or permissions need tests for the failure cases too (a refused edit, a partial
failure, an exact undo).

Useful tasks (the examples use `scripts/gradle.ps1`; `./gradlew` takes the same arguments):

```powershell
scripts/gradle.ps1 build          # compile, test and build every product's jar (modules/<product>/build/libs)
scripts/gradle.ps1 assemble       # the jars only, without tests; :paper:assemble and so on for one product
scripts/gradle.ps1 runClient      # dev client (singleplayer); add -PbsWorld="<world>" to open a world directly
scripts/gradle.ps1 runServer      # dev server in .local/run/server
scripts/gradle.ps1 :neoforge:runClient -PbsPlaytest -PbsWorld="<world>"  # NeoForge dev client, with the playtest setup
```

`scripts/playtest.ps1` and `scripts/playtest-server.ps1` wrap the dev client and a dev server on port 25580; their
headers list the options (the screenshot tour, the play check, the scripted demo). By default both apply the playtest
setup: the player is op at level 4 (even in a singleplayer world without cheats), the time is locked at day, mob spawning
is off, the difficulty is peaceful and hostile mobs are removed; `-NoSetup` leaves the world as it is. On the run tasks it is `-PbsPlaytest`
(`:fabric:` or `:neoforge:` `runClient` and `runServer`), and the log shows "Sculptory playtest setup:" lines. These
dev-only harnesses and the setup run from the source sets and are left out of every jar.

Each loader module has two GameTest runs: `runGameTest` (vanilla tests) and `runFidelityGameTest` (Chipped/Farmer's
Delight tests), as `:fabric:runGameTest`, `:neoforge:runGameTest` and so on; a task name without the module runs both
loaders'. To run only some GameTests, pass `-PgameTestFilter` to them. It takes comma-separated text matched, ignoring
case, against each test's name (`<class>.<method>`, as the log shows it) and its batch id. A filter that matches nothing
fails the run.

```powershell
scripts/gradle.ps1 :fabric:runGameTest -PgameTestFilter=CommandAccessGameTest              # one test class
scripts/gradle.ps1 :neoforge:runGameTest "-PgameTestFilter=scattergametest,sculptory_sync" # a class, a batch-id prefix
scripts/gradle.ps1 runFidelityGameTest -PgameTestFilter=FidelityStateGameTest               # on both loaders
```

In PowerShell, quote a filter that contains a comma. On Fabric, `test` also runs the whole vanilla GameTest suite (Loom
makes it depend on `runGameTest`), and `-PgameTestFilter` narrows that too. One filter applies to every run, so with
`check` it must match tests in each, or one run fails. A filtered run is never the full `check`.

For render-mod checks, `runClient -PbsSodium` adds Sodium and `-PbsIris` adds Iris with Sodium
(`scripts/playtest.ps1 -Sodium` or `-Iris`). Their versions are pinned in `gradle.properties`, and neither is
bundled.

## Versions and releases

Sculptory is one engine with a product per platform, each released on its own schedule. `gradle.properties` holds the
engine's version (`sculptory_engine_version`, for the `core`, `protocol`, `server` and `common` modules) and one
version per product: `sculptory_fabric_version` (the Fabric mod), `sculptory_neoforge_version` (the NeoForge mod) and
`sculptory_paper_version` (the Paper plugin); Forge gets its own when it exists. A product's **build id** is its
version plus the Minecraft version it is built for; it names the jar, is the version in the mod or plugin list and is
shown by client and server when a player joins (`/sculptory version`):

- A **dev build** (any ordinary build) adds the commit: `0.5.0-dev+1.21.1.6a043a85`, with `.dirty` for uncommitted
  changes and `nogit` without git. Its jar is `sculptory-fabric-0.5.0-dev+1.21.1.6a043a85.jar` (and
  `sculptory-neoforge-…`, `sculptory-paper-…` for the others). The commit is what tells a dev build from a release,
  also while a product's version is already its next release's (`0.5.0-beta+1.21.1.6a043a85`).
- A **release build** is the version and the Minecraft version alone: `0.5.0+1.21.1`, in
  `sculptory-fabric-0.5.0+1.21.1.jar`. Gradle makes one of every product with `-Prelease`
  (`scripts/gradle.ps1 :fabric:remapJar :neoforge:jar :paper:jar -Prelease`), and one of a single product when it
  runs in GitHub Actions for that product's tag. The tag must match `gradle.properties`, or the build stops; a bare
  `v…` tag stops it too. A local release build from a checkout with uncommitted changes warns, since its id no longer
  says so.

The version rules:

- **A release never has a `-dev` version**, neither the product's nor the engine's: a release build stops on one
  (with `-Prelease`, on any product's), and so does the release workflow.
- **A pre-release has a suffix**: `-alpha` (an alpha on the stores), `-beta` or `-rc` (a beta on the stores; `-rc` is a
  release candidate). The first NeoForge and Paper releases are betas: `0.5.0-beta` and `0.1.0-beta`. A version
  without a suffix is a full release; the release workflow refuses any other suffix.
- **The engine version moves with shared code.** A release shows the engine version alone as the engine's build id,
  so bump `sculptory_engine_version` for every product release that includes changes to `core`, `protocol`, `server`
  or `common` since the last product release (of any product). Two releases built from different shared code then
  never show the same engine id. Between releases the engine version carries `-dev` (`0.6.0-dev`), which keeps every
  release on a deliberate engine version.

Each product has its own tag pattern, `<product>/v<build id>`:

| Product | Tag | Release jar | GitHub Release title |
|---|---|---|---|
| Fabric mod | `fabric/v<build id>` (`fabric/v0.5.0+1.21.1`) | `sculptory-fabric-<build id>.jar` | Sculptory for Fabric `<build id>` |
| NeoForge mod | `neoforge/v<build id>` (`neoforge/v0.5.0-beta+1.21.1`) | `sculptory-neoforge-<build id>.jar` | Sculptory for NeoForge `<build id>` |
| Paper plugin | `paper/v<build id>` (`paper/v0.1.0-beta+1.21.1`) | `sculptory-paper-<build id>.jar` | Sculptory for Paper `<build id>` |

To release a product: set its version (and, if shared code changed, the engine's) in `gradle.properties`, add a
`CHANGELOG.md` section headed `## <Product> <build id>` (`## Fabric 0.5.0+1.21.1`, `## NeoForge 0.5.0-beta+1.21.1`,
`## Paper 0.1.0-beta+1.21.1`), commit, and push the tag. The release workflow (`.github/workflows/release.yml`) runs
`check`, builds that product's release jar, runs the Paper self-test for the Paper plugin, attaches the jar to a GitHub
Release with the changelog entry (a pre-release for versions with a `-`; only the Fabric mod's releases become
"Latest"), and uploads it to the product's stores when their tokens are set up: the Fabric and NeoForge mods to one
Modrinth project and one CurseForge project (the loader tells their files apart), the Paper plugin to its own Modrinth
project, a CurseForge Bukkit plugin project and Hangar. A client and a server need the same protocol, not the same
product or version: a Fabric client can edit on any server whose Sculptory speaks its protocol.

## Pull requests

Keep a pull request to one coherent change, and say in its description what changed and how you checked it (tests,
or what you tried in game).
