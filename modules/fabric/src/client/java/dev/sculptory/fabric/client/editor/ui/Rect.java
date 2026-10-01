package dev.sculptory.fabric.client.editor.ui;

/** An integer rectangle in scaled GUI pixels. Width and height are never negative. */
public record Rect(int x, int y, int width, int height) {
    public static final Rect EMPTY = new Rect(0, 0, 0, 0);

    public Rect {
        width = Math.max(0, width);
        height = Math.max(0, height);
    }

    public static Rect ofEdges(int left, int top, int right, int bottom) {
        return new Rect(left, top, right - left, bottom - top);
    }

    public int right() {
        return x + width;
    }

    public int bottom() {
        return y + height;
    }

    public boolean isEmpty() {
        return width == 0 || height == 0;
    }

    /** Half-open containment: the right and bottom edges are outside. */
    public boolean contains(double px, double py) {
        return px >= x && py >= y && px < right() && py < bottom();
    }

    public boolean intersects(Rect other) {
        return x < other.right() && other.x < right() && y < other.bottom() && other.y < bottom();
    }

    public Rect intersect(Rect other) {
        int left = Math.max(x, other.x);
        int top = Math.max(y, other.y);
        int r = Math.min(right(), other.right());
        int b = Math.min(bottom(), other.bottom());
        return new Rect(left, top, Math.max(0, r - left), Math.max(0, b - top));
    }

    public Rect inset(Insets insets) {
        return new Rect(x + insets.left(), y + insets.top(),
                width - insets.horizontal(), height - insets.vertical());
    }

    public Rect inset(int amount) {
        return new Rect(x + amount, y + amount, width - 2 * amount, height - 2 * amount);
    }

    public Rect translate(int dx, int dy) {
        return new Rect(x + dx, y + dy, width, height);
    }

    public Rect withPosition(int newX, int newY) {
        return new Rect(newX, newY, width, height);
    }

    public Rect withSize(int newWidth, int newHeight) {
        return new Rect(x, y, newWidth, newHeight);
    }
}
