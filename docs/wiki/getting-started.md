# Getting started

[Home](home.md) · [Start here](home.md#start-here)

Sculptory is a world editor that runs inside Minecraft: you open it over your world, edit with tools and windows, and close it to play on. This page installs it and makes a first edit in five minutes.

## What you need

Sculptory comes in three products, all for Minecraft: Java Edition 1.21.1 and Java 21 or newer:

| Product | For | Needs |
|---|---|---|
| Sculptory for Fabric | Your game, and Fabric servers | Fabric Loader 0.16.14 or newer, Fabric API 0.116.4+1.21.1 or newer |
| Sculptory for NeoForge | Your game, and NeoForge servers | NeoForge 21.1.264 or newer |
| Sculptory for Paper | Paper servers only (a plugin) | Paper 1.21.1; players edit with the Fabric or NeoForge mod |

To edit in your own game you need the mod for your loader: Fabric or NeoForge. The Fabric mod is released; the NeoForge mod and the Paper plugin are coming soon and can be built from source today (the repository's README says how).

## Install

1. Install Fabric Loader for 1.21.1 with the Fabric installer, or NeoForge for 1.21.1 with the NeoForge installer.
2. Put the Sculptory jar for that loader in the profile's `mods` folder; on Fabric, Fabric API too.
3. Start the game and open a world in Creative mode: you need Creative to fly, and flying is how you get around while editing.

Download the jar from the project's releases on GitHub, Modrinth or CurseForge. To edit on a server, the server needs Sculptory too (the mod on Fabric or NeoForge, the plugin on Paper): see [Server setup](server-setup.md).

## Your first edit

1. Press `B`. The editor opens over the world: menus at the top, a tool palette at the bottom, the **Tool Settings** window at the right. The status dot in the top bar should read **Connected**.
2. Hold the right mouse button and drag to look around. Fly with your usual movement keys; jump and sneak go up and down.
3. Press `2` for the **Raise** brush. A ring appears where you aim. Left-drag over the ground and a mound grows.
4. Press `Ctrl+Z` to undo it and `Ctrl+Y` to put it back. Every edit is one undo step.
5. Press `B` again to leave. The world is exactly as you left it.

## Where to go next

- The **Quick start** card shows on your first visit; its **Start tutorial** button opens the [Tutorial](tutorial.md), which teaches every tool in short lessons.
- Lost? `Ctrl+K` finds any command by name and `F1` lists every key: see [Finding things](finding-things.md).
- Each tool's page in this wiki says what it does, when to use it and exactly which keys and clicks it takes.

## Related

- [Tutorial](tutorial.md)
- [Editor mode](editor-mode.md)
- [Tools at a glance](tools.md)
