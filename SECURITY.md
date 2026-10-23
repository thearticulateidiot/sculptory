# Security policy

Sculptory runs on servers and decides who may change a world, so security problems matter to us: a way around the
permission checks or the size and rate limits, a crafted packet or schematic file that crashes or stalls a server,
a schematic that brings commands into a world, a path that escapes the library folder, and the like.

## Reporting a vulnerability

Please **don't open a public issue** for a security problem. Report it privately through GitHub's security
advisories: on the repository page, open **Security > Advisories > Report a vulnerability**
(<https://github.com/thearticulateidiot/sculptory/security/advisories/new>).

Include:

- what you found, and the steps or a file that shows it;
- which product and build: Sculptory for Fabric, for NeoForge or for Paper, with its build id (`/sculptory version`,
  the jar's name or the log line, e.g. `0.5.0-beta+1.21.1`), on the server and on the client if one is involved;
- the Minecraft version and the loader or server with its version: Fabric Loader and Fabric API, NeoForge, or the
  Paper build, plus other mods or plugins that take part (a permissions mod or plugin, WorldGuard).

We'll answer in the advisory, agree on a fix and a date with you, and credit you in the release notes unless you'd
rather not be named.

## Supported versions

Sculptory comes as three products, each with its own release line: the Fabric mod, the NeoForge mod and the Paper
plugin. Sculptory is in beta. Fixes go into the newest release of each product line only (the newest Fabric
release, the newest NeoForge release, the newest Paper release); please check that the problem still happens there.
A problem in the shared engine affects every product line that includes it; the fix ships with each line's next release.
