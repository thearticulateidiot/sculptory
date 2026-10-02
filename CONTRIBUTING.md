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

The code is in four Gradle modules under `modules/`:

- `core`: the editing engine without Minecraft: regions, masks, brushes, edit programs, scatter planning, undo
  history and its saved format, schematic and NBT reading. Plain Java, tested with JUnit.
- `protocol`: the client-server network protocol (messages, codecs, the handshake), also free of Minecraft
  imports, with golden wire samples in its tests.
- `server`: the server side that every platform shares (Fabric now; Paper and NeoForge are planned): the library,
  config, permission and protection rules, file sanitizing and the engine's job and history services. No Minecraft
  imports either; it grows as server logic moves out of `fabric`.
- `fabric`: the mod itself. `src/main` is the Fabric side of the server (the engine host, world access, commands,
  networking), `src/client` the editor (windows, tools, rendering, the tutorial and the wiki reader), `src/test` the
  JUnit tests, and `src/gametest` and `src/fidelity` the GameTests.

The wiki lives in `docs/wiki/` and is bundled into the mod for the in-game reader. Its page ids are frozen
(`WikiPages`); `scripts/check-wiki.ps1` checks its links, pictures and Markdown subset, and `check` runs the same
rules in `WikiRepositoryTest`.

## Build and test

You need JDK 21. Nothing else needs installing: the Gradle wrapper fetches the rest.

- Linux and macOS: `./gradlew check`, with `JAVA_HOME` pointing to a JDK 21.
- Windows: `scripts/gradle.ps1 check`. The script finds a JDK 21 in `$env:SCULPTORY_JDK`, then in a
  `toolchains\jdk-21*` folder beside the repository (or beside one of its parent folders), then in `JAVA_HOME`.

`check` runs the JUnit tests and the headless Fabric GameTests (vanilla, and mod fidelity with Chipped and Farmer's
Delight, which it downloads) and must pass before a pull request is merged. GitHub Actions runs it on every push and
pull request (`.github/workflows/ci.yml`). Logic in `core`, `protocol` and `server` gets JUnit tests and stays free
of Minecraft imports; anything that changes a world gets a GameTest. Changes to world edits, undo and redo, saved formats, the
network protocol or permissions need tests for the failure cases too (a refused edit, a partial failure, an exact
undo).

Useful tasks (the examples use `scripts/gradle.ps1`; `./gradlew` takes the same arguments):

```powershell
scripts/gradle.ps1 build          # compile, test and assemble: modules/fabric/build/libs/sculptory-<build id>.jar
scripts/gradle.ps1 assemble       # the jar only, without tests
scripts/gradle.ps1 runClient      # dev client (singleplayer); add -PbsWorld="<world>" to open a world directly
scripts/gradle.ps1 runServer      # dev server in .local/run/server
```

`scripts/playtest.ps1` and `scripts/playtest-server.ps1` wrap the dev client and a dev server on port 25580; their
headers list the options (the screenshot tour, the play check, the scripted demo). Those dev-only harnesses run from
the source sets and are left out of the mod jar.

To run only some GameTests, pass `-PgameTestFilter` to `runGameTest` (vanilla tests) or `runFidelityGameTest`
(Chipped/Farmer's Delight tests). It takes comma-separated text matched, ignoring case, against each test's name
(`<class>.<method>`, as the log shows it) and its batch id. A filter that matches nothing fails the run.

```powershell
scripts/gradle.ps1 runGameTest -PgameTestFilter=CommandAccessGameTest                # one test class
scripts/gradle.ps1 runGameTest "-PgameTestFilter=scattergametest,sculptory_sync"     # a class and a batch-id prefix
scripts/gradle.ps1 runFidelityGameTest -PgameTestFilter=FidelityStateGameTest
```

In PowerShell, quote a filter that contains a comma. `test` also runs the whole vanilla GameTest suite (Loom makes it
depend on `runGameTest`), and `-PgameTestFilter` narrows that too. One filter applies to both runs, so with `check`
it must match tests in both, or one run fails. A filtered run is never the full `check`.

For render-mod checks, `runClient -PbsSodium` adds Sodium and `-PbsIris` adds Iris with Sodium
(`scripts/playtest.ps1 -Sodium` or `-Iris`). Their versions are pinned in `gradle.properties`, and neither is
bundled.

## Versions and releases

The version is `mod_version` in `gradle.properties` (for example `0.3.0-alpha`). Every build has a build id, which
names the jar, is the version in the mod list and is compared by client and server when a player joins
(`/sculptory version` shows both):

- A **dev build** (any ordinary build) adds the commit: `0.3.0-alpha+6a043a85`, with `.dirty` for uncommitted
  changes and `+nogit` without git. Its jar is `sculptory-0.3.0-alpha+6a043a85.jar`.
- A **release build** is `mod_version` alone: `0.3.0-alpha`, in `sculptory-0.3.0-alpha.jar`. Gradle makes one when
  it is given `-Prelease` (`scripts/gradle.ps1 :fabric:remapJar -Prelease`), or when it runs in GitHub Actions for a
  tag that starts with `v`. Such a tag must be `v` plus `mod_version` (`v0.3.0-alpha`), or the build stops. A local
  release build from a checkout with uncommitted changes warns, since its id no longer says so.

To release: set `mod_version`, update `CHANGELOG.md`, commit, and push the tag `v<mod_version>`. The release workflow
(`.github/workflows/release.yml`) runs `check`, builds the release jar, attaches it to a GitHub Release with the
changelog entry, and uploads it to Modrinth and CurseForge when their tokens are set up.

## Pull requests

Keep a pull request to one coherent change, and say in its description what changed and how you checked it (tests,
or what you tried in game).
