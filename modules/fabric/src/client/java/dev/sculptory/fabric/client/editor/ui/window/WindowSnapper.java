package dev.sculptory.fabric.client.editor.ui.window;

import dev.sculptory.fabric.client.editor.ui.Rect;

/**
 * Pure edge snapping for dragged windows. An edge snaps to an edge of the work area (the screen minus the edges kept
 * for the HUD, so a window dropped near the palette stops above it rather than on it), or to another window's edge
 * (touching or aligned) when the two windows are side by side or stacked, within a threshold. The nearest candidate
 * wins on each axis independently.
 */
public final class WindowSnapper {
    private WindowSnapper() {
    }

    /** Snaps {@code moving} to the edges of {@code workArea} and of {@code others}. */
    public static Rect snap(Rect moving, Iterable<Rect> others, Rect workArea, int threshold) {
        Best dx = new Best(threshold);
        Best dy = new Best(threshold);
        dx.consider(workArea.x() - moving.x());
        dx.consider(workArea.right() - moving.right());
        dy.consider(workArea.y() - moving.y());
        dy.consider(workArea.bottom() - moving.bottom());
        for (Rect other : others) {
            boolean besideVertically = moving.y() <= other.bottom() + threshold
                    && moving.bottom() >= other.y() - threshold;
            if (besideVertically) {
                dx.consider(other.right() - moving.x());
                dx.consider(other.x() - moving.right());
                dx.consider(other.x() - moving.x());
                dx.consider(other.right() - moving.right());
            }
            boolean besideHorizontally = moving.x() <= other.right() + threshold
                    && moving.right() >= other.x() - threshold;
            if (besideHorizontally) {
                dy.consider(other.bottom() - moving.y());
                dy.consider(other.y() - moving.bottom());
                dy.consider(other.y() - moving.y());
                dy.consider(other.bottom() - moving.bottom());
            }
        }
        return moving.translate(dx.value(), dy.value());
    }

    private static final class Best {
        private final int threshold;
        private int delta;
        private boolean found;

        Best(int threshold) {
            this.threshold = threshold;
        }

        void consider(int candidate) {
            if (Math.abs(candidate) <= threshold && (!found || Math.abs(candidate) < Math.abs(delta))) {
                delta = candidate;
                found = true;
            }
        }

        int value() {
            return found ? delta : 0;
        }
    }
}
