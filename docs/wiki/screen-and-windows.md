# The screen and windows

[Home](home.md) · [Start here](home.md#start-here)

The top bar holds the menus, the palette and hint line sit at the bottom, toasts appear above the palette, and windows go in between. You can move, resize, hide and fade all of it, and the arrangement is kept per UI size.

![The editor screen](images/screen-and-windows-overview.png)

| Part | What it is |
|---|---|
| Top bar | Menus, the active block, Undo, Redo, the water toggle, fly speed, the status dot, UI size |
| Active block | What Fill, Walls and the Shape brush place: click it to choose, or middle-click a block in the world |
| Palette | The 15 tools: click a slot or press its key; a greyed slot names the missing permission in its tooltip |
| Hint line | The active tool's keys for what you can do right now |
| Toasts | Results and refusals; **View > Notifications** keeps the last 100 |
| Windows | Tool Settings, Selection, Clipboard, Library, History, Keys, Tutorial, Wiki, Notifications, Tinker |

![The top bar, left side](images/screen-and-windows-top-bar.png)

## Arrange windows

1. Show or hide a window from the **View** menu (`L` and `H` toggle Library and History).
2. Drag its title bar to move it; drag an edge or a corner to resize it. The fold button in its title bar folds it to the bar.
3. Press `Tab` to hide every window at once, and again to show them.
4. **View > Reset layout** puts every window back at its default place and size for this UI size. Open windows stay open and closed ones closed.

Each UI size keeps its own arrangement. `F6` puts the keyboard into the next window, `Esc` gives it back.

## Editor UI size

`Ctrl+-` makes the editor's UI smaller, `Ctrl+=` larger, `Ctrl+0` puts it back to 100%; or pick a size in **View > UI size**. On a small screen a smaller size gives the windows room; the wiki's text stays readable at every size.

## Opacity

**View > Opacity…** (or `Ctrl+K` "opacity") makes the editor see-through so you can watch the world behind it. Changes apply at once and are remembered.

| Setting | What it does |
|---|---|
| Panels | 20–100%: fades the backgrounds of windows, the top bar, palette, hint line and toasts; text, borders and the selected controls stay solid |
| Fade only when not hovered | The panel under the mouse, or the one holding the keyboard, shows fully opaque |
| Tool outlines | 10–100%: fades what tools draw in the world: the selection box, brush rings, previews and ghosts |

Menus, tooltips, dialogs, the key sheet and the tutorial cards always stay opaque. A faded window shows the world behind it, never the windows under it.

## Related

- [Finding things](finding-things.md)
- [Tool Settings](tool-settings.md)
- [Keys](keys.md)
