# Sculptory notices

Copyright (C) 2026 Sculptory contributors

Sculptory is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option)
any later version, with the additional permission and the additional terms below.

Sculptory is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
details.

You should have received a copy of the GNU Affero General Public License along with Sculptory (the file `LICENSE`).
If not, see <https://www.gnu.org/licenses/>.

The documentation and the wiki (`docs/`, including `docs/wiki/` and its pictures) are licensed differently: under
the Creative Commons Attribution-ShareAlike 4.0 International licence (`LICENSES/CC-BY-SA-4.0.txt`). A plain-language
summary of what covers what is in `docs/LICENSING.md`.

The Sculptory icon and logo (`branding/` and `modules/fabric/src/main/resources/assets/sculptory/icon.png`, also
shipped in the mod jar) are covered by neither licence: all rights are reserved. You may use them only as
`TRADEMARKS.md` allows; a modified version must not use them.

## Additional permission under GNU AGPL version 3 section 7

If you modify this Program, or any covered work, by linking or combining it with any of the following works (or a
modified version of them), containing parts covered by the terms of licences other than the GNU AGPL, including
non-free licences, the licensors of this Program grant you additional permission to convey the resulting work:

1. Minecraft (Java Edition, client or server), and the libraries it ships with;
2. a platform that loads mods or plugins into Minecraft, its APIs and libraries, and the mappings used to build
   against Minecraft, such as Fabric Loader, Fabric API and the Yarn and intermediary mappings;
3. any other mod, plugin, data pack, resource pack or shader pack that merely runs in, or is loaded into, the same
   game client or server as this Program, provided that it is not derived from or based on this Program and does
   not use, call, extend or depend on this Program's code or interfaces. A modpack or server pack may therefore
   include this Program next to such works, whatever their licences.

This permission does not place those works under the GNU AGPL, and the Corresponding Source of such a combination
need not include their source code. It does not cover any part of this Program itself, any work that copies or
adapts code from it, or any addon that uses, calls or extends this Program's code or interfaces: those get no
exception, and the GNU AGPL version 3 or later, with this permission and the terms below, applies to them as it
does to any work based on this Program.

## Additional terms under GNU AGPL version 3 section 7

1. **Attribution (section 7(b)).** All copies and modified versions must keep intact the copyright notices of this
   Program, this notice, the author attributions in its source files, and a statement that the work is or is
   based on Sculptory with a link to <https://github.com/thearticulateidiot/sculptory>, in the places where this
   Program shows them (its source files, this file, and its mod metadata, credits or about screen).
2. **Marking modified versions (section 7(c)).** A modified version that you convey must carry a user-facing name
   other than "Sculptory" (as its mod name, project name and file names), must state that it is modified and based
   on Sculptory, and must not be presented as Sculptory, as an official release of it, or as endorsed by the
   Sculptory project. Internal identifiers (the mod id, resource namespaces and network channel ids) may stay
   unchanged where a modified version needs them for compatibility. Running a modified version on a server you
   operate is not conveying it; the Corresponding Source you offer its users under section 13 must state that it is
   modified.
3. **Names and logos (section 7(e)).** This licence grants no rights under trademark law to use the Sculptory name
   or logo, except to state truthfully that a work is, contains or is based on Sculptory, and as `TRADEMARKS.md`
   allows.

# What the Sculptory jar contains

Checked against the 0.3.0-alpha release jar on 2026-10-01.

| Inside the jar | Version | Licence | Compatible with AGPL-3.0-or-later | Notes |
|---|---|---|---|---|
| Sculptory (the mod, and its nested `core`, `protocol` and `server` jars) | the build id | AGPL-3.0-or-later with the section 7 permission and terms above | (this project) | This project's own code. Each jar carries `LICENSE`. |
| fabric-permissions-api (`me.lucko:fabric-permissions-api`), nested as `META-INF/jars/fabric-permissions-api-0.3.1.jar` | 0.3.1 | MIT: "Copyright (c) lucko (Luck) <luck@lucko.me>", "Copyright (c) contributors" | Yes (MIT is GPL-compatible) | Lets a permissions mod decide the mod's permission nodes. Source: https://github.com/lucko/fabric-permissions-api. Its own jar carries no licence text, so the Sculptory jar carries it as `META-INF/licenses/fabric-permissions-api-LICENSE.txt` (from `modules/fabric/src/main/resources`): the MIT text and copyright lines exactly as in the headers of that version's published sources jar. |
| Wiki pages and pictures (the mod's `wiki` assets, from `docs/wiki/`) | | CC BY-SA 4.0 (this project's text and screenshots) | Yes, shipped alongside the code (CC BY-SA 4.0 is also one-way compatible with GPLv3) | The pictures are screenshots of the game, so they show Minecraft's own textures and interface; those parts belong to Mojang and are not licensed by this project. |
| The Sculptory icon (`assets/sculptory/icon.png`, shown in the mod list) | | All rights reserved: not under the AGPL or CC BY-SA; use only as `TRADEMARKS.md` allows | (this project) | The same picture as the project icon on Modrinth and CurseForge. |

Nothing else is bundled: fastutil 8.5.12 (Apache-2.0) and Gson (Apache-2.0) are used from Minecraft, which ships them.

# Used but not bundled

| Component | Version | Licence (as stated) | How it is used |
|---|---|---|---|
| Minecraft: Java Edition | 1.21.1 | Mojang's EULA | The game; players install it. Never included. Covered by the section 7 permission above. |
| Fabric Loader | 0.16.14 or newer | Apache-2.0 | Installed by players. |
| Fabric API | 0.116.4+1.21.1 or newer | Apache-2.0 | Installed by players. |
| Yarn and intermediary mappings | 1.21.1+build.3 | CC0-1.0 | Build only: the jar refers to Minecraft by intermediary names. |
| LuckPerms, or another permissions mod | any for Fabric 1.21.1 | (LuckPerms: MIT) | Optional, installed by server admins. |
| Sodium | mc1.21.1-0.6.13 | Polyform-Shield-1.0.0 | Optional for players; the development client can load it (`-PbsSodium`). Never bundled. |
| Iris | 1.8.8+1.21.1 | LGPL-3.0-only | Optional for players; the development client can load it (`-PbsIris`). Never bundled. |
| Complementary Shaders - Reimagined | r5.9.3 | its own licence (not checked) | Downloaded into `.local/` for the Iris play check only. Never bundled or committed. |
| Chipped, Athena, Resourceful Lib (with yabn and bytecodecs), Farmer's Delight Refabricated (with Fabric ASM) | pinned in `gradle.properties` | Chipped: "All Rights Reserved" on Modrinth ("Terrarium Licence" in its `fabric.mod.json`); Fabric ASM MPL-2.0; the others MIT | Test-only, for the mod-fidelity GameTests. Never bundled. |
| Gradle (the wrapper, `gradle/wrapper/gradle-wrapper.jar`, is in the repository) | 8.12 | Apache-2.0 | Builds the project. |
| Fabric Loom, JUnit 5, fabric-loader-junit | pinned in the build files | MIT, EPL-2.0, Apache-2.0 | Build and test tools only; never combined into the jar. |

Sculptory is independent of Mojang Studios and Microsoft, and of the authors of the tools it was researched against.
"Minecraft" is a trademark of Mojang Synergies AB.
