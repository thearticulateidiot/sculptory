package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;

/** Shows progress from 0 to 1, or an animated bar when the total is unknown, with optional text. */
public class ProgressBar extends Node {
    private double progress;
    private boolean indeterminate;
    private String text;

    public double progress() {
        return progress;
    }

    /** Clamped to 0..1. Also switches off indeterminate mode. */
    public ProgressBar setProgress(double progress) {
        this.progress = Double.isNaN(progress) ? 0 : Math.max(0, Math.min(1, progress));
        this.indeterminate = false;
        return this;
    }

    public ProgressBar setIndeterminate(boolean indeterminate) {
        this.indeterminate = indeterminate;
        return this;
    }

    /** Text drawn over the bar (for example "42% - 12,000 blocks"), or {@code null} for none. */
    public ProgressBar setText(String text) {
        this.text = text;
        return this;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int height = text == null ? 6 : ctx.theme().controlHeight;
        int width = text == null ? 60 : ctx.text().width(text) + 2 * ctx.theme().controlPaddingX;
        return new Size(width, height);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        g.fill(bounds, theme.progressTrack);
        int inner = bounds.width() - 2;
        int color = isEffectivelyEnabled() ? theme.progressFill : theme.textDisabled;
        if (indeterminate) {
            int segment = Math.max(8, inner / 4);
            long period = 1200;
            double phase = (ctx.now() % period) / (double) period;
            int start = (int) Math.round(phase * (inner + segment)) - segment;
            int left = Math.max(0, start);
            int right = Math.min(inner, start + segment);
            if (right > left) {
                g.fill(bounds.x() + 1 + left, bounds.y() + 1, right - left, bounds.height() - 2, color);
            }
        } else {
            int filled = (int) Math.round(progress * inner);
            if (filled > 0) {
                g.fill(bounds.x() + 1, bounds.y() + 1, filled, bounds.height() - 2, color);
            }
        }
        g.outline(bounds, theme.controlBorder);
        if (text != null && !text.isEmpty()) {
            String shown = TextLayout.ellipsize(ctx.text(), text, bounds.width() - 4);
            int x = bounds.x() + (bounds.width() - ctx.text().width(shown)) / 2;
            int y = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
            g.text(shown, x, y, theme.textOnAccent, true);
        }
    }
}
