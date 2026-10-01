package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.List;

/** One child on a filled background with an optional border: the top bar, palette and hint line plates. */
public final class Panel extends Node {
    private final Node child;
    private final Insets padding;
    private final int background;
    private final int border;

    /** @param border ARGB border colour, or 0 for none */
    public Panel(Node child, Insets padding, int background, int border) {
        this.child = adopt(child);
        this.padding = padding;
        this.background = background;
        this.border = border;
    }

    public Node child() {
        return child;
    }

    @Override
    public List<Node> children() {
        return List.of(child);
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int inner = maxWidth == Integer.MAX_VALUE ? maxWidth : Math.max(0, maxWidth - padding.horizontal());
        Size size = child.isVisible() ? child.measure(ctx, inner) : Size.ZERO;
        return new Size(size.width() + padding.horizontal(), size.height() + padding.vertical());
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        child.layout(ctx, bounds.inset(padding));
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        g.fill(bounds, background);
        if (border != 0) {
            g.outline(bounds, border);
        }
        renderChildren(g, ctx);
    }
}
