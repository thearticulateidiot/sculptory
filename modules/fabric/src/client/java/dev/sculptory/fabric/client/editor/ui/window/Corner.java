package dev.sculptory.fabric.client.editor.ui.window;

import dev.sculptory.fabric.client.editor.ui.Rect;

/**
 * The screen corner a window is anchored to. A window stores its distance from this corner, so it
 * keeps its place relative to that corner when the GUI scale or resolution changes.
 */
public enum Corner {
    TOP_LEFT(false, false),
    TOP_RIGHT(true, false),
    BOTTOM_LEFT(false, true),
    BOTTOM_RIGHT(true, true);

    private final boolean right;
    private final boolean bottom;

    Corner(boolean right, boolean bottom) {
        this.right = right;
        this.bottom = bottom;
    }

    /** Whether this is a bottom corner: the window's bottom edge keeps its distance from the screen's. */
    public boolean isBottom() {
        return bottom;
    }

    public static Corner of(boolean right, boolean bottom) {
        return right ? (bottom ? BOTTOM_RIGHT : TOP_RIGHT) : (bottom ? BOTTOM_LEFT : TOP_LEFT);
    }

    /** The corner nearest the rectangle's centre. */
    public static Corner nearest(Rect rect, int screenWidth, int screenHeight) {
        boolean right = rect.x() * 2 + rect.width() > screenWidth;
        boolean bottom = rect.y() * 2 + rect.height() > screenHeight;
        return of(right, bottom);
    }

    /** Left edge of a window {@code width} wide whose edge is {@code offsetX} from this corner. */
    public int x(int offsetX, int width, int screenWidth) {
        return right ? screenWidth - offsetX - width : offsetX;
    }

    public int y(int offsetY, int height, int screenHeight) {
        return bottom ? screenHeight - offsetY - height : offsetY;
    }

    /** Horizontal distance from this corner to the matching edge of a window at {@code x}. */
    public int offsetX(int x, int width, int screenWidth) {
        return right ? screenWidth - x - width : x;
    }

    public int offsetY(int y, int height, int screenHeight) {
        return bottom ? screenHeight - y - height : y;
    }
}
