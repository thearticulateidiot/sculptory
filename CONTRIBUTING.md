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

The code is in five Gradle modules under `modules/`:

- `core`: the editing engine without Minecraft: regions, masks, brushes, edit programs, scatter planning, undo
  history and its saved format, schematic and NBT reading. Plain Java, tested with JUnit.
- `protocol`: the client-server network protocol (messages, codecs, the handshake), also free of Minecraft
  imports, with golden wire samples in its tests.
- `server`: the server side that every platform shares (Fabric now; Paper and NeoForge are planned): the edit,
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
scripts/gradle.ps1 build          # compile, test and assemble: modules/fabric/build/libs/sculptory-<build id>.jar
scripts/gradle.ps1 assemble       # the jar only, without tests
scripts/gradle.ps1 runClient      # dev client (singleplayer); add -PbsWorld="<world>" to open a world directly
scripts/gradle.ps1 runServer      # dev server in .local/run/server
scripts/gradle.ps1 :neoforge:runClient -PbsPlaytest -PbsWorld="<world>"  # NeoForge dev client, with the playtest setup
```

`scripts/playtest.ps1` and `scripts/playtest-server.ps1` wrap the dev client and a dev server on port 25580; their
headers list the options (the screenshot tour, the play check, the scripted demo). By default both apply the playtest
setup: the player is op at level 4 (even in a singleplayer world without cheats), the time is locked at day, mob spawning
is off and hostile mobs are removed; `-NoSetup` leaves the world as it is. On the run tasks it is `-PbsPlaytest`
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
version per product (`sculptory_fabric_version` for the Fabric mod; NeoForge, Paper and Forge get their own when they
exist). A product's **build id** is its version plus the Minecraft version it is built for; it names the jar, is the
version in the mod list and is shown by client and server when a player joins (`/sculptory version`):

- A **dev build** (any ordinary build) adds the commit: `0.5.0-dev+1.21.1.6a043a85`, with `.dirty` for uncommitted
  changes and `nogit` without git. Its jar is `sculptory-fabric-0.5.0-dev+1.21.1.6a043a85.jar`.
- A **release build** is the version and the Minecraft version alone: `0.5.0+1.21.1`, in
  `sculptory-fabric-0.5.0+1.21.1.jar`. Gradle makes one of every product with `-Prelease`
  (`scripts/gradle.ps1 :fabric:remapJar -Prelease`), and one of a single product when it runs in GitHub Actions for
  that product's tag, `<product>/v<build id>` (`fabric/v0.5.0+1.21.1`). The tag must match `gradle.properties`, or
  the build stops; a bare `v…` tag stops it too. A local release build from a checkout with uncommitted changes
  warns, since its id no longer says so.

To release a product: set its version in `gradle.properties`, add a `CHANGELOG.md` section headed
`## <Product> <build id>` (for example `## Fabric 0.5.0+1.21.1`), commit, and push the tag `<product>/v<build id>`.
The release workflow (`.github/workflows/release.yml`) runs `check`, builds that product's release jar, attaches it
to a GitHub Release with the changelog entry (only the Fabric mod's releases become "Latest"), and uploads it to the
product's Modrinth and CurseForge projects when their tokens are set up. A client and a server need the same
protocol, not the same product version: a Fabric client can edit on any server whose Sculptory speaks its protocol.

## Pull requests

Keep a pull request to one coherent change, and say in its description what changed and how you checked it (tests,
or what you tried in game).
