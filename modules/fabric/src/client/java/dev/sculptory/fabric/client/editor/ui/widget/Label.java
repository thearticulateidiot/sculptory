package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Align;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.List;
import java.util.Objects;

/**
 * Text. Single-line labels are cut with "..." when narrow and then show the full text as their
 * tooltip; wrapping labels grow downwards instead.
 */
public class Label extends Node {
    public enum Style { NORMAL, DIM, HEADING }

    private String text;
    private Style style = Style.NORMAL;
    private Align align = Align.START;
    private boolean wrap;
    private int color;
    private boolean truncated;

    public Label(String text) {
        this.text = Objects.requireNonNull(text);
    }

    public static Label of(String text) {
        return new Label(text);
    }

    public static Label dim(String text) {
        return new Label(text).setStyle(Style.DIM);
    }

    public static Label heading(String text) {
        return new Label(text).setStyle(Style.HEADING);
    }

    public String text() {
        return text;
    }

    public Label setText(String text) {
        this.text = Objects.requireNonNull(text);
        return this;
    }

    public Label setStyle(Style style) {
        this.style = style;
        return this;
    }

    public Label setAlign(Align align) {
        this.align = align;
        return this;
    }

    /** Wrap onto several lines instead of cutting with "...". */
    public Label setWrap(boolean wrap) {
        this.wrap = wrap;
        return this;
    }

    /** Overrides the style's colour; 0 restores it. */
    public Label setColor(int argb) {
        this.color = argb;
        return this;
    }

    @Override
    public String tooltip() {
        String explicit = super.tooltip();
        return explicit != null ? explicit : truncated ? text : null;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        TextMeasure measure = ctx.text();
        if (wrap && maxWidth != Integer.MAX_VALUE) {
            List<String> lines = TextLayout.wrap(measure, text, maxWidth);
            int width = 0;
            for (String line : lines) {
                width = Math.max(width, measure.width(line));
            }
            return new Size(width, linesHeight(ctx, lines.size()));
        }
        return new Size(measure.width(text), measure.lineHeight());
    }

    private static int linesHeight(UiContext ctx, int lines) {
        return lines * ctx.text().lineHeight() + Math.max(0, lines - 1) * ctx.theme().lineSpacing;
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        truncated = !wrap && ctx.text().width(text) > bounds.width();
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        TextMeasure measure = ctx.text();
        int argb = color != 0 ? color : switch (style) {
            case NORMAL -> theme.text;
            case DIM -> theme.textDim;
            case HEADING -> theme.titleText;
        };
        if (!isEffectivelyEnabled()) {
            argb = theme.textDisabled;
        }
        boolean shadow = style == Style.HEADING ? theme.titleShadow : theme.textShadow;
        List<String> lines = wrap
                ? TextLayout.wrap(measure, text, bounds.width())
                : List.of(TextLayout.ellipsize(measure, text, bounds.width()));
        int blockHeight = linesHeight(ctx, lines.size());
        int y = bounds.y() + Math.max(0, (bounds.height() - blockHeight + 1) / 2);
        for (String line : lines) {
            int x = bounds.x() + align.offset(bounds.width(), measure.width(line));
            g.text(line, x, y, argb, shadow);
            y += measure.lineHeight() + theme.lineSpacing;
        }
    }
}
