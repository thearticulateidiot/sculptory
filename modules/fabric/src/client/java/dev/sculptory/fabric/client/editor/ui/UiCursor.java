package dev.sculptory.fabric.client.editor.ui;

/**
 * The mouse cursor shape the editor UI asks for. The in-game implementation
 * ({@code render.GlfwCursors}) maps these to GLFW standard cursors.
 */
public enum UiCursor {
    /** The game's normal arrow. */
    DEFAULT,
    /** Left-right arrows: a window's left or right edge. */
    RESIZE_EW,
    /** Up-down arrows: a window's top or bottom edge. */
    RESIZE_NS,
    /** Diagonal arrows for the top-left and bottom-right corners. */
    RESIZE_NWSE,
    /** Diagonal arrows for the top-right and bottom-left corners. */
    RESIZE_NESW
}
