# Licensing in plain words

This page is a summary. The legal texts are `LICENSE` (GNU AGPL version 3), `NOTICE.md` (the copyright notice, the
section 7 additional permission and terms, and third-party notices), `LICENSES/CC-BY-SA-4.0.txt` and
`TRADEMARKS.md`. Where this page and those texts differ, the texts win.

## What covers what

| Part | Licence |
|---|---|
| Code, build files and scripts (`modules/`, `scripts/`, the Gradle files) | **AGPL-3.0-or-later**, with the game-linking permission and the name and attribution terms in `NOTICE.md` |
| Documentation and wiki (`docs/`, `docs/wiki/` with its pictures, and the wiki pages inside the mod jar) | **CC BY-SA 4.0**. The screenshots show Minecraft's textures and interface, which belong to Mojang and are not ours to license. |
| Third-party parts (fabric-permissions-api inside the jar; the game, Fabric and tools outside it) | Their own licences, listed in `NOTICE.md` |
| The Sculptory name, icon and logo (`branding/`, the mod icon) | All rights reserved: not under the AGPL or CC BY-SA, even where they sit beside the docs; `TRADEMARKS.md` says how you may use them |

Copyright (C) 2026 Sculptory contributors. Contributions come in under the same licences, signed off with the
Developer Certificate of Origin (see `CONTRIBUTING.md`).

## What you may do

Anyone, players and server owners included, may:

- **use** Sculptory for anything, in single player or on any server, public or private, free or paid;
- **modify** it, privately or publicly;
- **share** it, modified or not, and **sell** copies or charge for hosting or support;
- **host** it on a server, including a modified version;
- put it in **modpacks** next to any other mods, free or not;
- build **addons** on it, as long as they are open source under an AGPL-compatible licence (see below).

## What modified or forked versions must do

If you **give someone a copy** of a modified version (a download, a modpack, a jar in a chat):

- license it under AGPL-3.0-or-later as well, keep `LICENSE` and `NOTICE.md`, and offer the complete source code of
  your version;
- mark it as modified, keep the copyright notices and the "based on Sculptory" attribution with the link to the
  original project;
- **give it a name of its own**: not "Sculptory" or a confusingly similar name, and not the logo. It may say "based
  on Sculptory". The mod id, namespaces and network channel ids may stay the same where compatibility needs it.

If you **run a modified version on a server** that other people play on, the AGPL's network clause (section 13)
applies: your version must offer the players who use it the source code of your version, for example a link in the
server's MOTD, its website or a chat command. It does not need a new name, as long as players aren't told it is the
official build. Running the **unmodified** mod on a server needs nothing: it is already public.

Private changes that only you use, and that no one else plays with, carry no duties.

## The game-linking permission

Minecraft, Fabric's loader and API, and many mods are not under the AGPL, and some are not free software at all. The
additional permission in `NOTICE.md` makes it clear that you may combine Sculptory with them, and share the
combination (a modpack, a server pack), without those works falling under the AGPL and without having to publish
their source. Other mods and packs are covered only if they merely run in the same game and don't use Sculptory's
code or interfaces: a modpack with Sculptory next to closed-source mods is fine.

**Addons get no exception.** A mod or plugin that calls into, extends or depends on Sculptory is built on it: if you
share it, it must be under a licence compatible with the AGPL (in practice AGPL-3.0-or-later), with its source. The
permission also never covers Sculptory's own code, or code copied from it: that stays AGPL.

## Docs and wiki under CC BY-SA 4.0

You may copy, translate, adapt and share the docs and wiki pages, also commercially, if you credit Sculptory (with
a link), say what you changed, and share your adapted version under CC BY-SA 4.0 too.

## Headers in source files

Source files carry no licence headers yet. If headers are added, this form is suggested:

```
// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sculptory contributors. Additional permission and terms under AGPL section 7: see NOTICE.md.
```

The second line matters: section 7 asks that the additional terms be stated in the source files, or that the files
point to where they are.
