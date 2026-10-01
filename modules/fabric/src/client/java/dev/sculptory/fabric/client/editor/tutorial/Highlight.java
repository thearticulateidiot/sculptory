package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;

/**
 * The outline around a step's target: two accent rings just outside it whose strength rises and falls every
 * {@link #PERIOD_MS} (a gentle pulse), drawn above the windows and menus.
 */
public final class Highlight {
    public static final long PERIOD_MS = 1_200;
    /** The pulse's faintest and strongest alpha. */
    static final int MIN_ALPHA = 0x50;
    static final int MAX_ALPHA = 0xFF;

    private Highlight() {}

    /** The alpha at {@code nowMs}: {@link #MIN_ALPHA} to {@link #MAX_ALPHA} and back over a period. */
    public static int alpha(long nowMs) {
        double phase = (Math.floorMod(nowMs, PERIOD_MS)) / (double) PERIOD_MS;
        double wave = 0.5 - 0.5 * Math.cos(2 * Math.PI * phase);
        return (int) Math.round(MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * wave);
    }

    /** Draws the outline around {@code target}. */
    public static void draw(UiGraphics g, Theme theme, Rect target, long nowMs) {
        if (target.isEmpty()) {
            return;
        }
        int alpha = alpha(nowMs);
        int rgb = theme.accent & 0xFFFFFF;
        g.outline(target.inset(-1), (alpha << 24) | rgb);
        g.outline(target.inset(-2), (alpha << 24) | rgb);
        g.outline(target.inset(-3), ((alpha / 3) << 24) | rgb);
    }
}
