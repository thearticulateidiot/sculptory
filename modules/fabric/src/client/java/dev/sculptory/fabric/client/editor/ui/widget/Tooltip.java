package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.List;

/**
 * The hover tooltip box. Any node's {@code tooltip()} text is shown by the window manager after
 * the theme's hover delay; wrapping and placement are pure so they can be tested.
 */
public final class Tooltip {
    private static final int PADDING = 4;

    private Tooltip() {
    }

    /** Lines of {@code text} wrapped to the theme's tooltip width. */
    public static List<String> lines(TextMeasure measure, Theme theme, String text) {
        return TextLayout.wrap(measure, text, theme.tooltipMaxWidth - 2 * PADDING);
    }

    /** The tooltip box for {@code lines}, placed near the cursor and kept on screen. */
    public static Rect bounds(TextMeasure measure, Theme theme, List<String> lines, int mouseX, int mouseY,
            int screenWidth, int screenHeight) {
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, measure.width(line));
        }
        width += 2 * PADDING;
        int height = lines.size() * measure.lineHeight() + (lines.size() - 1) * theme.lineSpacing + 2 * PADDING;
        return place(mouseX, mouseY, width, height, screenWidth, screenHeight, theme.tooltipOffset);
    }

    /** Below-right of the cursor; flipped left or up when it would leave the screen. */
    public static Rect place(int mouseX, int mouseY, int width, int height, int screenWidth, int screenHeight,
            int offset) {
        int x = mouseX + offset;
        if (x + width > screenWidth) {
            x = mouseX - offset - width;
        }
        int y = mouseY + offset;
        if (y + height > screenHeight) {
            y = mouseY - offset - height;
        }
        x = Math.max(0, Math.min(x, screenWidth - width));
        y = Math.max(0, Math.min(y, screenHeight - height));
        return new Rect(x, y, width, height);
    }

    public static void render(UiGraphics g, UiContext ctx, String text, int mouseX, int mouseY) {
        Theme theme = ctx.theme();
        TextMeasure measure = ctx.text();
        List<String> lines = lines(measure, theme, text);
        Rect box = bounds(measure, theme, lines, mouseX, mouseY, ctx.screenWidth(), ctx.screenHeight());
        g.fill(box, theme.tooltipBackground);
        g.outline(box, theme.tooltipBorder);
        int y = box.y() + PADDING;
        for (String line : lines) {
            g.text(line, box.x() + PADDING, y, theme.text, theme.textShadow);
            y += measure.lineHeight() + theme.lineSpacing;
        }
    }
}
