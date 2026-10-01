package dev.sculptory.fabric.client.editor.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Drawing a panel only where nothing see-through lies over it (View > Opacity…). A faded window lets the world show
 * through its background; were the panel under it drawn there too, its text would show through as well. So a panel
 * under faded ones is drawn clipped to what they leave of it ({@link #uncovered}), one clip per part: within the UI a
 * window hides what is under it, whatever its opacity, while the world still shows through.
 */
public final class Covers {
    private Covers() {}

    /**
     * {@code area} less every rectangle of {@code covers}: disjoint rectangles that together are exactly the part of
     * {@code area} no cover overlaps (none when the covers hide all of it; {@code area} itself when none overlaps it).
     */
    public static List<Rect> uncovered(Rect area, List<Rect> covers) {
        List<Rect> parts = new ArrayList<>();
        if (area.isEmpty()) {
            return parts;
        }
        parts.add(area);
        for (Rect cover : covers) {
            if (cover.isEmpty()) {
                continue;
            }
            List<Rect> next = new ArrayList<>();
            for (Rect part : parts) {
                if (!part.intersects(cover)) {
                    next.add(part);
                    continue;
                }
                Rect hidden = part.intersect(cover);
                // The bands above and below the hidden part, full width, then its left and right in between.
                if (hidden.y() > part.y()) {
                    next.add(Rect.ofEdges(part.x(), part.y(), part.right(), hidden.y()));
                }
                if (hidden.bottom() < part.bottom()) {
                    next.add(Rect.ofEdges(part.x(), hidden.bottom(), part.right(), part.bottom()));
                }
                if (hidden.x() > part.x()) {
                    next.add(Rect.ofEdges(part.x(), hidden.y(), hidden.x(), hidden.bottom()));
                }
                if (hidden.right() < part.right()) {
                    next.add(Rect.ofEdges(hidden.right(), hidden.y(), part.right(), hidden.bottom()));
                }
            }
            parts = next;
        }
        return parts;
    }

    /**
     * Runs {@code draw}, which draws inside {@code area}, clipped to the part of it that {@code covers} leave: once
     * without a clip when no cover overlaps the area, once per uncovered part otherwise, not at all when it is hidden.
     */
    public static void drawUncovered(UiGraphics g, Rect area, List<Rect> covers, Runnable draw) {
        List<Rect> over = new ArrayList<>();
        for (Rect cover : covers) {
            if (!cover.isEmpty() && cover.intersects(area)) {
                over.add(cover);
            }
        }
        if (over.isEmpty()) {
            draw.run();
            return;
        }
        for (Rect part : uncovered(area, over)) {
            g.pushClip(part);
            draw.run();
            g.popClip();
        }
    }
}
