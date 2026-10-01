# Troubleshooting

[Home](home.md) · [Help](home.md#help)

Most problems announce themselves as a toast. Missed one? **View > Notifications** lists the last 100. This page takes the common messages and problems and says what to do.

## The editor won't open

- **This server doesn't run Sculptory:** install it on the server; see [Server setup](server-setup.md).
- **Editing is off here — ask an operator:** you lack `sculptory.use`; see [Permissions](permissions.md).
- **This server runs a different Sculptory version, so the editor can't open:** use the server's build (`/sculptory version` names it).
- **`B` does nothing:** another mod may use the key; rebind "Toggle editor" in Options > Controls > Key Binds.

## When something is refused

- **You don't have permission to do that here:** ask for the node the toast names.
- **That area is protected:** spawn protection, a claim or the world border.
- **That area is being edited — try again in a moment:** another edit or a scatter preview holds it.
- **Set the symmetry centre first (M):** press `M`, or turn Symmetry off in the tool's settings.
- **Make a selection first (only inside selection is on):** select, or turn that Mask setting off.
- **Alt+drag a line in the world first:** the Gradient pattern needs its line; see [Mix patterns](mix-patterns.md).
- **Too large for this server:** the edit is over the server's size limit; split it, or ask for `limit.bypass`.
- **Slow down (builder mode):** the server's block budget per second is used up; the refused blocks stay.

## Common problems

- **A tool is greyed out:** you lack its permission; hover it to see which.
- **I can't fly:** switch to Creative.
- **The brush does nothing:** brushes skip stairs, fences, slabs and chests; check the Mask section, and that the ring isn't dashed with the change still on its way.
- **Undo left some blocks:** they changed after your edit; click **Undo anyway** in the toast.
- **Magic select, Extrude or a flood stopped early:** fly closer so the chunks load, or raise their Limit or Size.
- **A key doesn't work:** a red key in the Keys window collides with another; while a text field has the keyboard, press `Esc`.
- **A window is gone:** press `Tab` (it hides every window), or open it again from the **View** menu. **View > Reset layout** puts every open window back in its place.
- **A warning names two Sculptory builds:** the server and your client differ but can still edit together; install the server's build.

## Related

- [FAQ](faq.md)
- [Permissions](permissions.md)
- [History](history.md)
