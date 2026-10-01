package dev.sculptory.fabric.client.editor.ui.layout;

import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import java.util.List;

/** Wraps one child with space around it. */
public final class Padding extends Node {
    private final Insets insets;
    private final Node child;

    public Padding(Insets insets, Node child) {
        this.insets = insets;
        this.child = adopt(child);
    }

    public static Padding all(int amount, Node child) {
        return new Padding(Insets.all(amount), child);
    }

    @Override
    public List<Node> children() {
        return List.of(child);
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int inner = maxWidth == Integer.MAX_VALUE ? maxWidth : Math.max(0, maxWidth - insets.horizontal());
        Size size = child.isVisible() ? child.measure(ctx, inner) : Size.ZERO;
        return new Size(size.width() + insets.horizontal(), size.height() + insets.vertical());
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        child.layout(ctx, bounds.inset(insets));
    }
}
