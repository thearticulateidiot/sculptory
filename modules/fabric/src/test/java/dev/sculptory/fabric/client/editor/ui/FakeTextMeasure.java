package dev.sculptory.fabric.client.editor.ui;

/** Fixed-width text metrics: every character is 6 px wide, lines are 9 px tall. */
final class FakeTextMeasure implements TextMeasure {
    static final int CHAR_WIDTH = 6;
    static final FakeTextMeasure INSTANCE = new FakeTextMeasure();

    @Override
    public int width(String text) {
        return text.length() * CHAR_WIDTH;
    }

    @Override
    public int lineHeight() {
        return 9;
    }

    @Override
    public String trimToWidth(String text, int maxWidth) {
        int chars = Math.max(0, Math.min(text.length(), maxWidth / CHAR_WIDTH));
        return text.substring(0, chars);
    }

    static UiContext context() {
        return new UiContext(INSTANCE, Theme.DARK);
    }
}
