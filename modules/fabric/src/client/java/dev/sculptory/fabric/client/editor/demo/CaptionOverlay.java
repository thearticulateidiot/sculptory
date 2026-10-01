package dev.sculptory.fabric.client.editor.demo;

import dev.sculptory.fabric.client.editor.ui.render.MinecraftTextMeasure;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Util;

/**
 * The demo's caption bar (a dark plate with large text at the bottom centre, cross-fading between captions) and a ring
 * around the pointer while a mouse button is held, drawn over everything else: over the editor's own UI, the ring of
 * powers and any other screen. Nothing else of the demo shows on screen. Written from the demo's thread, drawn on the
 * client thread.
 */
public final class CaptionOverlay {
    /** How long a caption takes to fade in, and the one before it to fade out. */
    static final long FADE_MS = 350;
    /** How long the pointer ring lingers after a release. */
    static final long RING_AFTER_RELEASE_MS = 250;
    private static final int PLATE = 0x101216;
    private static final int TEXT = 0xF2F4F8;
    private static final int RING = 0xFFD166;
    private static final int RING_RADIUS = 7;
    /** The overlay's depth: over the editor's windows and popups and vanilla's tooltips (400). */
    private static final float ABOVE_ALL = 900f;

    private final Object lock = new Object();
    private String current = "";
    private String previous = "";
    private long changedAt;
    private volatile double pointerX;
    private volatile double pointerY;
    private volatile boolean pointerShown;
    private volatile boolean held;
    private volatile long releasedAt = Long.MIN_VALUE;

    /** Shows {@code text} (fading the one before out); blank clears the bar. */
    public void show(String text) {
        synchronized (lock) {
            long now = Util.getMeasuringTimeMs();
            // A caption replaced mid-fade: what is on screen now becomes the one fading out.
            previous = alphaOf(now) < 1 ? blend(now) : current;
            current = text == null ? "" : text;
            changedAt = now;
        }
    }

    private String blend(long now) {
        // Half way through a fade the old caption is already the fainter one: keep the newer text as "previous".
        return alphaOf(now) >= 0.5 ? current : previous;
    }

    public void clear() {
        show("");
    }

    public String shown() {
        synchronized (lock) {
            return current;
        }
    }

    /** Where the pointer is (GUI pixels); the ring is drawn there while a button is held. */
    public void pointer(double x, double y) {
        pointerX = x;
        pointerY = y;
    }

    public void setPointerShown(boolean shown) {
        pointerShown = shown;
    }

    public void pointerDown() {
        held = true;
    }

    public void pointerUp() {
        held = false;
        releasedAt = Util.getMeasuringTimeMs();
    }

    private double alphaOf(long now) {
        return Math.min(1.0, (now - changedAt) / (double) FADE_MS);
    }

    /**
     * Draws the bar with its bottom edge at {@code bottomLimit} (GUI pixels) and the pointer ring. Client thread, at
     * the end of the frame.
     */
    public void render(DrawContext context, MinecraftClient client, int bottomLimit) {
        long now = Util.getMeasuringTimeMs();
        String text;
        String old;
        double alpha;
        synchronized (lock) {
            text = current;
            old = previous;
            alpha = alphaOf(now);
        }
        TextMeasure measure = new MinecraftTextMeasure(client.textRenderer);
        int width = context.getScaledWindowWidth();
        int height = context.getScaledWindowHeight();
        // Above everything: the editor layers its windows and popups in depth, and vanilla's tooltips sit at 400.
        context.getMatrices().push();
        context.getMatrices().translate(0, 0, ABOVE_ALL);
        if (!old.isEmpty() && alpha < 1) {
            draw(context, client, measure, CaptionLayout.of(width, height, bottomLimit, measure, old), 1 - alpha);
        }
        if (!text.isEmpty()) {
            draw(context, client, measure, CaptionLayout.of(width, height, bottomLimit, measure, text), alpha);
        }
        drawRing(context, now);
        context.getMatrices().pop();
    }

    private void draw(DrawContext context, MinecraftClient client, TextMeasure measure, CaptionLayout layout,
            double alpha) {
        int plateAlpha = (int) Math.round(alpha * 0xCC);
        int textAlpha = (int) Math.round(alpha * 0xFF);
        if (textAlpha < 8) {
            // Vanilla draws text with a near-zero alpha as opaque.
            return;
        }
        Rect plate = layout.plate();
        context.fill(plate.x(), plate.y(), plate.right(), plate.bottom(), (plateAlpha << 24) | PLATE);
        int scale = layout.scale();
        context.getMatrices().push();
        context.getMatrices().scale(scale, scale, 1);
        for (int i = 0; i < layout.lines().size(); i++) {
            int x = layout.lineX(i, measure) / scale;
            int y = layout.lineY(i, measure) / scale;
            context.drawText(client.textRenderer, layout.lines().get(i), x, y, (textAlpha << 24) | TEXT, true);
        }
        context.getMatrices().pop();
    }

    private void drawRing(DrawContext context, long now) {
        if (!pointerShown) {
            return;
        }
        double alpha;
        if (held) {
            alpha = 1;
        } else {
            long since = now - releasedAt;
            if (since > RING_AFTER_RELEASE_MS) {
                return;
            }
            alpha = 1 - since / (double) RING_AFTER_RELEASE_MS;
        }
        int argb = ((int) Math.round(alpha * 0xE0) << 24) | RING;
        int cx = (int) Math.round(pointerX);
        int cy = (int) Math.round(pointerY);
        for (int i = 0; i < 24; i++) {
            double angle = i * Math.PI / 12;
            int x = cx + (int) Math.round(Math.cos(angle) * RING_RADIUS);
            int y = cy + (int) Math.round(Math.sin(angle) * RING_RADIUS);
            context.fill(x - 1, y - 1, x + 1, y + 1, argb);
        }
    }
}
