package dev.sculptory.fabric.client.editor.ui;

/** A vertical scrollbar driving a {@link ScrollModel}: thumb drag, track paging and drawing. */
public final class Scrollbar {
    private final ScrollModel model;
    private double grabOffset = -1;

    public Scrollbar(ScrollModel model) {
        this.model = model;
    }

    /** The scrollbar track along the right edge of {@code viewport}. */
    public static Rect track(Rect viewport, Theme theme) {
        return new Rect(viewport.right() - theme.scrollbarWidth, viewport.y(), theme.scrollbarWidth,
                viewport.height());
    }

    public Rect thumb(Rect track, Theme theme) {
        int length = model.thumbLength(track.height(), theme.minThumbLength);
        int start = model.thumbStart(track.height(), theme.minThumbLength);
        return new Rect(track.x(), track.y() + start, track.width(), length);
    }

    /** Starts a thumb drag or pages the view. Returns false if the point isn't on the scrollbar. */
    public boolean mouseDown(Rect track, Theme theme, double x, double y) {
        if (!model.isScrollable() || !track.contains(x, y)) {
            return false;
        }
        Rect thumb = thumb(track, theme);
        if (thumb.contains(x, y)) {
            grabOffset = y - thumb.y();
        } else {
            model.scrollBy(y < thumb.y() ? -model.viewportSize() : model.viewportSize());
        }
        return true;
    }

    public boolean mouseDrag(Rect track, Theme theme, double y) {
        if (grabOffset < 0) {
            return false;
        }
        int start = (int) Math.round(y - grabOffset) - track.y();
        model.setOffset(model.offsetForThumbStart(start, track.height(), theme.minThumbLength));
        return true;
    }

    public void mouseUp() {
        grabOffset = -1;
    }

    public boolean isDragging() {
        return grabOffset >= 0;
    }

    public void render(UiGraphics g, UiContext ctx, Rect track) {
        if (!model.isScrollable()) {
            return;
        }
        Theme theme = ctx.theme();
        g.fill(track, theme.scrollTrack);
        Rect thumb = thumb(track, theme);
        boolean hot = isDragging() || thumb.contains(ctx.mouseX(), ctx.mouseY());
        g.fill(thumb.x() + 1, thumb.y(), thumb.width() - 1, thumb.height(),
                hot ? theme.scrollThumbHover : theme.scrollThumb);
    }
}
