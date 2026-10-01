package dev.sculptory.fabric.client.editor.tour;

import dev.sculptory.fabric.client.editor.ui.Rect;
import java.util.Objects;

/**
 * The arithmetic of a cropped tour picture (the wiki pictures): which framebuffer pixels a rect in UI units covers, and
 * the size the picture is written at. Pure; the game side copies and scales the pixels ({@code UiTour}).
 */
public final class TourCrop {
    /** Pictures are scaled down to at most this width. */
    public static final int MAX_WIDTH = 1200;

    /** A rectangle of framebuffer pixels, or the size of a picture (x and y 0). */
    public record Pixels(int x, int y, int width, int height) {
        public Pixels {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("An empty picture: " + width + "x" + height);
            }
        }
    }

    private TourCrop() {}

    /**
     * The framebuffer pixels {@code area} covers: UI units times {@code uiFactor} (the editor UI size, UI units to GUI
     * pixels: {@code UiScale.factor()}) times {@code windowScale} (GUI pixels to framebuffer pixels: the window's scale
     * factor), widened to whole pixels and clipped to the frame.
     *
     * @throws IllegalArgumentException when nothing of the area is on the frame
     */
    public static Pixels pixels(Rect area, double uiFactor, double windowScale, int frameWidth, int frameHeight) {
        Objects.requireNonNull(area);
        if (!(uiFactor > 0) || !(windowScale > 0)) {
            throw new IllegalArgumentException("Scale factors must be positive: " + uiFactor + ", " + windowScale);
        }
        double scale = uiFactor * windowScale;
        int left = clamp((int) Math.floor(area.x() * scale), frameWidth);
        int top = clamp((int) Math.floor(area.y() * scale), frameHeight);
        int right = clamp((int) Math.ceil(area.right() * scale), frameWidth);
        int bottom = clamp((int) Math.ceil(area.bottom() * scale), frameHeight);
        if (right <= left || bottom <= top) {
            throw new IllegalArgumentException("The crop " + area + " is not on the " + frameWidth + "x" + frameHeight
                    + " frame");
        }
        return new Pixels(left, top, right - left, bottom - top);
    }

    /**
     * The size a {@code width} × {@code height} picture is written at: as it is when it is at most {@code maxWidth}
     * wide, otherwise scaled down to {@code maxWidth}, keeping its shape (at least one pixel high).
     */
    public static Pixels fitted(int width, int height, int maxWidth) {
        if (maxWidth < 1) {
            throw new IllegalArgumentException("maxWidth must be positive: " + maxWidth);
        }
        if (width <= maxWidth) {
            return new Pixels(0, 0, width, height);
        }
        int scaledHeight = Math.max(1, (int) Math.round(height * (double) maxWidth / width));
        return new Pixels(0, 0, maxWidth, scaledHeight);
    }

    /** The smallest rect holding all of {@code rects} (empty ones ignored); empty when all are. */
    public static Rect union(Rect... rects) {
        int left = Integer.MAX_VALUE;
        int top = Integer.MAX_VALUE;
        int right = Integer.MIN_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (Rect rect : rects) {
            if (rect == null || rect.isEmpty()) {
                continue;
            }
            left = Math.min(left, rect.x());
            top = Math.min(top, rect.y());
            right = Math.max(right, rect.right());
            bottom = Math.max(bottom, rect.bottom());
        }
        return left == Integer.MAX_VALUE ? Rect.EMPTY : Rect.ofEdges(left, top, right, bottom);
    }

    /** {@code rect} grown by {@code margin} on every side. */
    public static Rect grow(Rect rect, int margin) {
        return Rect.ofEdges(rect.x() - margin, rect.y() - margin, rect.right() + margin, rect.bottom() + margin);
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }
}
