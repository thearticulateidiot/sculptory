package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.Objects;

/** A coloured dot with a short label: the connection and permission state in the top bar. */
public final class StatusDot extends Node {
    public static final int GOOD = 0xFF4CC38A;
    public static final int WAIT = 0xFFE0B040;
    public static final int BAD = 0xFFE5655D;
    private static final int DOT = 7;

    private int color = WAIT;
    private String text = "";

    public StatusDot() {
        // Never narrower than the dot, which is drawn whatever the width.
        setMinSize(DOT, 0);
    }

    public void set(int color, String text, String tooltip) {
        this.color = color;
        this.text = Objects.requireNonNull(text);
        setTooltip(tooltip);
    }

    public int color() {
        return color;
    }

    public String text() {
        return text;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        return new Size(DOT + 4 + ctx.text().width(text), ctx.theme().controlHeight);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        int y = bounds.y() + (bounds.height() - DOT) / 2;
        int x = bounds.x();
        g.fill(x + 1, y, DOT - 2, DOT, color);
        g.fill(x, y + 1, DOT, DOT - 2, color);
        int textY = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
        // When the top bar is short of room the label gives way (the dot and its tooltip stay).
        String shown = TextLayout.ellipsize(ctx.text(), text, bounds.right() - (x + DOT + 4));
        if (shown.equals(text) || shown.length() > TextLayout.ELLIPSIS.length()) {
            g.text(shown, x + DOT + 4, textY, ctx.theme().textDim, ctx.theme().textShadow);
        }
    }
}
