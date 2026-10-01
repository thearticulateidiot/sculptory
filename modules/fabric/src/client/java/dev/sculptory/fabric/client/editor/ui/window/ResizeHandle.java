package dev.sculptory.fabric.client.editor.ui.window;

import dev.sculptory.fabric.client.editor.ui.UiCursor;

/** The edge or corner of a window that a resize drags; the opposite edges stay put. */
public enum ResizeHandle {
    N(false, true, false, false, UiCursor.RESIZE_NS),
    S(false, false, false, true, UiCursor.RESIZE_NS),
    W(true, false, false, false, UiCursor.RESIZE_EW),
    E(false, false, true, false, UiCursor.RESIZE_EW),
    NW(true, true, false, false, UiCursor.RESIZE_NWSE),
    NE(false, true, true, false, UiCursor.RESIZE_NESW),
    SW(true, false, false, true, UiCursor.RESIZE_NESW),
    SE(false, false, true, true, UiCursor.RESIZE_NWSE);

    private final boolean left;
    private final boolean top;
    private final boolean right;
    private final boolean bottom;
    private final UiCursor cursor;

    ResizeHandle(boolean left, boolean top, boolean right, boolean bottom, UiCursor cursor) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.cursor = cursor;
    }

    public boolean left() {
        return left;
    }

    public boolean top() {
        return top;
    }

    public boolean right() {
        return right;
    }

    public boolean bottom() {
        return bottom;
    }

    /** The resize cursor shown over this handle and while dragging it. */
    public UiCursor cursor() {
        return cursor;
    }
}
