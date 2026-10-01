package dev.sculptory.fabric.client.editor.ui;

/** Placement of a child along an axis where it has more room than it needs. */
public enum Align {
    START,
    CENTER,
    END,
    /** Fill the available space. Behaves like {@link #START} where stretching doesn't apply. */
    STRETCH;

    /** Offset of an item of {@code size} inside {@code available} pixels. */
    public int offset(int available, int size) {
        return switch (this) {
            case START, STRETCH -> 0;
            case CENTER -> (available - size) / 2;
            case END -> available - size;
        };
    }
}
