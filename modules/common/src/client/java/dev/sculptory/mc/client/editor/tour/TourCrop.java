package dev.sculptory.mc.client.editor.tour;

import dev.sculptory.mc.client.editor.ui.Rect;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The arithmetic of a cropped tour picture (the wiki pictures): which framebuffer pixels a rect in UI units covers,
 * where its top and bottom edges fall among a window's rows, and the size the picture is written at. Pure; the game
 * side copies and scales the pixels ({@code UiTour}).
 */
public final class TourCrop {
    /** Pictures are scaled down to at most this width. */
    public static final int MAX_WIDTH = 1200;
    /** The most a wiki picture may take ({@code scripts/check-wiki.ps1}); a larger one is written smaller. */
    public static final int MAX_BYTES = 300 * 1024;

    /** A rectangle of framebuffer pixels, or the size of a picture (x and y 0). */
    public record Pixels(int x, int y, int width, int height) {
        public Pixels {
            if (width < 1 || height < 1) {
                throw new IllegalArgumentException("An empty picture: " + width + "x" + height);
            }
        }
    }

    /** The rows a crop's edge should not cut through: a widget's span from {@code top} to {@code bottom} (UI units). */
    public record Band(int top, int bottom) {
        public Band {
            if (bottom <= top) {
                throw new IllegalArgumentException("An empty band: " + top + ".." + bottom);
            }
        }

        int height() {
            return bottom - top;
        }
    }

    private TourCrop() {}

    /**
     * {@code crop} with its top and bottom moved off any row that the edge, or the edge of the {@code viewport} that
     * clips the rows (a scroll pane), would show only in part: a row mostly inside the crop and whole in the viewport is
     * taken in whole, any other is left out. The edge then sits in the gap beside the row, at most {@code margin} from
     * the nearest row it shows. {@code rows} may overlap (the widgets of one row); a row taller than
     * {@code maxRowHeight} (a list) is left cut, and a crop that would keep no row is returned as it is. The crop's
     * left and right edges never move.
     */
    public static Rect snapToRows(Rect crop, Rect viewport, List<Band> rows, int margin, int maxRowHeight) {
        Objects.requireNonNull(crop);
        Objects.requireNonNull(viewport);
        if (crop.isEmpty() || viewport.isEmpty() || crop.y() >= viewport.bottom() || crop.bottom() <= viewport.y()) {
            return crop;
        }
        List<Band> merged = merged(rows);
        int top = snappedTop(crop, viewport, merged, margin, maxRowHeight);
        int bottom = snappedBottom(crop, viewport, merged, margin, maxRowHeight);
        return bottom > top ? Rect.ofEdges(crop.x(), top, crop.right(), bottom) : crop;
    }

    private static int snappedTop(Rect crop, Rect viewport, List<Band> rows, int margin, int maxRowHeight) {
        int line = Math.max(crop.y(), viewport.y());
        int i = cutAt(rows, line, maxRowHeight);
        if (i < 0) {
            return crop.y();
        }
        Band cut = rows.get(i);
        if (cut.top() >= viewport.y() && line - cut.top() <= cut.bottom() - line) {
            int gapStart = i > 0 ? Math.max(rows.get(i - 1).bottom(), viewport.y()) : viewport.y();
            return Math.max(gapStart, cut.top() - margin);
        }
        return i + 1 < rows.size() ? Math.max(cut.bottom(), rows.get(i + 1).top() - margin) : crop.y();
    }

    private static int snappedBottom(Rect crop, Rect viewport, List<Band> rows, int margin, int maxRowHeight) {
        int line = Math.min(crop.bottom(), viewport.bottom());
        int i = cutAt(rows, line, maxRowHeight);
        if (i < 0) {
            return crop.bottom();
        }
        Band cut = rows.get(i);
        if (cut.bottom() <= viewport.bottom() && cut.bottom() - line <= line - cut.top()) {
            int gapEnd = i + 1 < rows.size() ? Math.min(rows.get(i + 1).top(), viewport.bottom()) : viewport.bottom();
            return Math.min(gapEnd, cut.bottom() + margin);
        }
        return i > 0 ? Math.min(cut.top(), rows.get(i - 1).bottom() + margin) : crop.bottom();
    }

    /** The index of the row {@code line} cuts through (not along its edge), or -1 when none or it is too tall. */
    private static int cutAt(List<Band> rows, int line, int maxRowHeight) {
        for (int i = 0; i < rows.size(); i++) {
            Band row = rows.get(i);
            if (row.top() < line && line < row.bottom()) {
                return row.height() <= maxRowHeight ? i : -1;
            }
        }
        return -1;
    }

    /** The rows sorted top to bottom, overlapping ones joined (rows that only touch stay apart). */
    static List<Band> merged(List<Band> rows) {
        List<Band> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt(Band::top));
        List<Band> merged = new ArrayList<>();
        for (Band row : sorted) {
            Band last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && row.top() < last.bottom()) {
                merged.set(merged.size() - 1, new Band(last.top(), Math.max(last.bottom(), row.bottom())));
            } else {
                merged.add(row);
            }
        }
        return merged;
    }

    /**
     * A smaller size for a {@code size} picture whose PNG took {@code bytes}, aimed to come in under {@code maxBytes}:
     * the area cut in proportion, with a tenth to spare, keeping the shape (at least one pixel each way).
     */
    public static Pixels shrunkForBytes(Pixels size, long bytes, long maxBytes) {
        if (maxBytes < 1 || bytes <= maxBytes) {
            throw new IllegalArgumentException("Nothing to shrink: " + bytes + " of " + maxBytes + " bytes");
        }
        double factor = Math.sqrt((double) maxBytes / bytes) * 0.95;
        int width = Math.max(1, Math.min(size.width() - 1, (int) Math.floor(size.width() * factor)));
        int height = Math.max(1, (int) Math.round(size.height() * (double) width / size.width()));
        return new Pixels(0, 0, width, height);
    }

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
