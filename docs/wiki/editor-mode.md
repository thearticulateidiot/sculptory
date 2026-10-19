# Editor mode

[Home](home.md) · [Start here](home.md#start-here)

The editor swaps the normal controls for a free mouse cursor, menus, a tool palette and windows. The game keeps running underneath: other players move, the sun sets, mobs wander. You edit with the tools, then leave to play on.

## Open, move, leave

1. Press `B` to open the editor (a Minecraft key binding: change it in Options > Controls > Key Binds).
2. Hold the right mouse button and drag to look around. The cursor is free the rest of the time, for the tools and windows.
3. Move with your movement keys; jump and sneak fly up and down. You need Creative mode to fly.
4. Scroll over the world to change the fly speed. The top bar shows the speed.
5. Press `B` to leave. `Esc` first closes whatever is open (a menu, a popup, a window's text field), then leaves.

The editor closes by itself when you die, disconnect, change dimension, lose the permission to edit or open another screen such as the inventory. Your tools and settings are kept for the next time.

## Fly speed

The fly speed goes from 0.25× to 8× your normal speed. Scroll over the world in the editor to step it, or type it in chat with `/sculptory flyspeed`, which works on any server without operator rights. Where the server has no `/flyspeed` command of its own (and in single player), `/flyspeed` does the same; where it has one, `/flyspeed` is the server's.

| Command | What it does |
|---|---|
| `/sculptory flyspeed` | Shows the speed |
| `/sculptory flyspeed 2.5` | Sets it, from 0.25 to 8 (a number outside is brought into the range) |
| `/sculptory flyspeed reset` | Back to 1× |

The speed is kept between games. It works in the editor and whenever you fly in Creative or Spectator with the editor closed; in Survival and Adventure your normal speed is used.

- The speed multiplies your own: when a server or a plugin sets your fly speed, that speed is what 2× doubles.
- Paper and Bukkit servers only tell your game about the commands you may use: without the server's own fly speed permission, `/flyspeed` is Sculptory's there.
- Some servers check how fast players move and pull back a player who flies too fast. If you are pulled back while flying, lower the speed there.

## Aim at water and lava

Tools aim through water and lava by default, so a brush on a lake shapes the lake bed. To work on the surface instead, click the **lily pad** button in the top bar or use **View > Aim at water and lava**. It stays on until you switch it back.

## The status dot

The dot at the right of the top bar says whether you can edit.

| Status | Meaning |
|---|---|
| Connected | You can edit |
| Connecting… | The handshake with the server isn't finished yet: wait a moment |
| No permission | You may not edit here: ask an operator for `sculptory.use` |
| Offline | The server doesn't run Sculptory |

## Tips

- On a server, everyone who edits needs the same Sculptory build as the server; players who don't edit need nothing.
- The editor never touches your inventory or your hotbar: what you place comes from the tools' settings, not from what you hold.
- To build with a few powers in normal play instead of the full editor, see [Builder mode](builder-mode.md).

## Related

- [The screen and windows](screen-and-windows.md)
- [Getting started](getting-started.md)
- [Troubleshooting](troubleshooting.md#the-editor-won-t-open)
