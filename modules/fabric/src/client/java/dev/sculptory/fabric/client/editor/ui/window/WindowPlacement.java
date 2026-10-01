package dev.sculptory.fabric.client.editor.ui.window;

import java.util.Objects;

/**
 * Where the user put a window (moved or resized it since the last reset): the screen corner it is anchored to, its
 * distances from that corner and its expanded size, in UI units. {@code overReserved} says the user left it over the
 * screen edges kept for the HUD (see {@link WindowManager#setReserved}). A window without a placement sits at its
 * default place. The offsets are those of the window as drawn when it was placed: collapsed to its title bar, or not.
 */
public record WindowPlacement(Corner anchor, int offsetX, int offsetY, int width, int height, boolean overReserved) {
    public WindowPlacement {
        Objects.requireNonNull(anchor);
    }

    /**
     * This placement for the window drawn {@code toHeight} tall instead of {@code fromHeight} (collapsed to its title
     * bar or expanded again), its top edge where it was: anchored at the bottom, its offset changes by the difference.
     */
    public WindowPlacement reshaped(int fromHeight, int toHeight) {
        if (!anchor.isBottom() || fromHeight == toHeight) {
            return this;
        }
        return new WindowPlacement(anchor, offsetX, offsetY + fromHeight - toHeight, width, height, overReserved);
    }
}
