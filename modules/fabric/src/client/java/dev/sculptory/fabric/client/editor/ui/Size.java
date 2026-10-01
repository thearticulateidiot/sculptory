package dev.sculptory.fabric.client.editor.ui;

/** A measured size in scaled GUI pixels. Never negative. */
public record Size(int width, int height) {
    public static final Size ZERO = new Size(0, 0);

    public Size {
        width = Math.max(0, width);
        height = Math.max(0, height);
    }
}
