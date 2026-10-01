package dev.sculptory.fabric.client.editor;

import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.world.Ray;
import java.util.Objects;
import java.util.Optional;

/** The cursor ray (when the camera is known) and where it met the world. */
public record CursorPick(Optional<Ray> ray, WorldCursor cursor) {
    /** No camera yet: no ray, and a miss at the origin. */
    public static final CursorPick NONE = new CursorPick(Optional.empty(), WorldCursor.miss(0, 0, 0));

    public CursorPick {
        Objects.requireNonNull(ray);
        Objects.requireNonNull(cursor);
    }
}
