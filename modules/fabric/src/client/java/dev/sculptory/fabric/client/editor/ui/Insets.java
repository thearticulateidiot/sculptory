package dev.sculptory.fabric.client.editor.ui;

/** Spacing around a rectangle's four edges. */
public record Insets(int left, int top, int right, int bottom) {
    public static final Insets NONE = new Insets(0, 0, 0, 0);

    public static Insets all(int amount) {
        return new Insets(amount, amount, amount, amount);
    }

    public static Insets symmetric(int horizontal, int vertical) {
        return new Insets(horizontal, vertical, horizontal, vertical);
    }

    public int horizontal() {
        return left + right;
    }

    public int vertical() {
        return top + bottom;
    }
}
