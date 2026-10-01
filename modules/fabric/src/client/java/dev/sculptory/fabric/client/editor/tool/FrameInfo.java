package dev.sculptory.fabric.client.editor.tool;

import java.util.Objects;

/** Per-frame input for {@link Tool#frame}: time, mouse position (scaled pixels) and the cursor ray result. */
public record FrameInfo(long nanoTime, float tickDelta, double mouseX, double mouseY, WorldCursor cursor) {
    public FrameInfo {
        Objects.requireNonNull(cursor);
    }
}
