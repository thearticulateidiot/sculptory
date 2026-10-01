# Jump and Through

[Home](home.md) · [Build faster](home.md#build-faster)

**Jump** (`J`) takes you to the block you look at, standing on top of it. **Through** (`Shift+J`) takes you through the wall you face, to the first open space behind it. Both work in the editor (at the block under the cursor) and in normal play (at the block under the crosshair), as far as you can see.

## Use them

1. Look at a block, or point the cursor at it in the editor.
2. Press `J` to stand on top of it. When other blocks are piled on it, you land on top of the first gap tall enough to stand in.
3. Press `Shift+J` to pass through the wall you look at: you land in the first open space behind it, standing on the floor when you look straight ahead, or just under the ceiling when you look down through a floor.

You keep facing the way you faced. The server picks the spot: always two free blocks for your feet and head, never inside blocks, in lava or fire, or over the void. When there is no such spot, nothing happens and a message says why.

## Keys

- In the editor: `J` and `Shift+J`, changeable in the [key settings](keys.md).
- In normal play: **Options > Controls > Sculptory > Jump**, `J` by default; hold `Shift` for Through.

## Limits

- You need Creative or Spectator mode and the `navigate` [permission](permissions.md) (given like `brush`).
- The block must be within `navigate.maxDistance` blocks of your eyes (256 by default) and in a loaded chunk.
- Through looks at most `navigate.maxThroughDepth` blocks past the block you look at (64 by default).

## Related

- [Editor mode](editor-mode.md)
- [Builder mode](builder-mode.md)
