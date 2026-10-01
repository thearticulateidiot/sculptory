package dev.sculptory.fabric.client.editor.tool;

import java.util.Objects;

/**
 * A mouse event routed to the active tool, in scaled screen pixels, with the world cursor under it.
 *
 * @param button GLFW button number (0 left, 1 right, 2 middle); -1 for MOVE
 * @param modifiers {@link Modifiers} bits
 */
public record PointerEvent(Kind kind, int button, double mouseX, double mouseY, int modifiers, WorldCursor cursor) {
    public static final int LEFT = 0;
    public static final int RIGHT = 1;
    public static final int MIDDLE = 2;

    public enum Kind {
        PRESS,
        RELEASE,
        DRAG,
        MOVE
    }

    public PointerEvent {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(cursor);
    }
}
