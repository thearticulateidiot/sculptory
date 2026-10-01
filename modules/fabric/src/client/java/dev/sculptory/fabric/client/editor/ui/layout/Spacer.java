package dev.sculptory.fabric.client.editor.ui.layout;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.UiContext;

/** Empty space: a fixed gap, or a flexible one that pushes siblings apart. Never hit by the mouse. */
public final class Spacer extends Node {
    private final int width;
    private final int height;

    public Spacer(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** A spacer that absorbs all spare space in its row or column. */
    public static Spacer flexible() {
        Spacer spacer = new Spacer(0, 0);
        spacer.setGrow(1);
        return spacer;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        return new Size(width, height);
    }

    @Override
    public Node hitTest(double x, double y) {
        return null;
    }
}
