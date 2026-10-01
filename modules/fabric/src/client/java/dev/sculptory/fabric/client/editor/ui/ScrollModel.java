package dev.sculptory.fabric.client.editor.ui;

/** Pure scroll state along one axis: content size, viewport size, offset and scrollbar geometry. */
public final class ScrollModel {
    private int contentSize;
    private int viewportSize;
    private int offset;

    /** Updates the extents and clamps the offset. */
    public void setExtent(int contentSize, int viewportSize) {
        this.contentSize = Math.max(0, contentSize);
        this.viewportSize = Math.max(0, viewportSize);
        setOffset(offset);
    }

    public int contentSize() {
        return contentSize;
    }

    public int viewportSize() {
        return viewportSize;
    }

    public int offset() {
        return offset;
    }

    public int maxOffset() {
        return Math.max(0, contentSize - viewportSize);
    }

    public boolean isScrollable() {
        return contentSize > viewportSize;
    }

    public void setOffset(int value) {
        offset = Math.max(0, Math.min(value, maxOffset()));
    }

    public void scrollBy(int delta) {
        setOffset(offset + delta);
    }

    /** Scrolls the least amount that brings content range [start, end) into view. */
    public void ensureVisible(int start, int end) {
        if (start < offset) {
            setOffset(start);
        } else if (end > offset + viewportSize) {
            setOffset(end - viewportSize);
        }
    }

    public int thumbLength(int track, int minThumb) {
        if (!isScrollable() || contentSize == 0) {
            return track;
        }
        int length = (int) ((long) track * viewportSize / contentSize);
        return Math.max(Math.min(minThumb, track), Math.min(length, track));
    }

    public int thumbStart(int track, int minThumb) {
        int travel = track - thumbLength(track, minThumb);
        if (travel <= 0 || maxOffset() == 0) {
            return 0;
        }
        return (int) Math.round((double) offset * travel / maxOffset());
    }

    /** The offset that puts the thumb's top at {@code thumbStart} pixels down the track. */
    public int offsetForThumbStart(int thumbStart, int track, int minThumb) {
        int travel = track - thumbLength(track, minThumb);
        if (travel <= 0) {
            return 0;
        }
        long value = Math.round((double) thumbStart * maxOffset() / travel);
        return (int) Math.max(0, Math.min(value, maxOffset()));
    }
}
