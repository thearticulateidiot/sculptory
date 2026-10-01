package dev.sculptory.fabric.client.editor.ui.layout;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.UiContext;

/** Children drawn on top of each other, each filling the stack's bounds. Later children are on top. */
public final class Stack extends Container {
    public static Stack of(Node... children) {
        Stack stack = new Stack();
        stack.add(children);
        return stack;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int width = 0;
        int height = 0;
        for (Node child : visibleChildren()) {
            Size size = child.measure(ctx, maxWidth);
            width = Math.max(width, size.width());
            height = Math.max(height, size.height());
        }
        return new Size(width, height);
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        for (Node child : visibleChildren()) {
            child.layout(ctx, bounds);
        }
    }
}
