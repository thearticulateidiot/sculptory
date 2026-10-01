package dev.sculptory.fabric.client.editor.ui;

/**
 * Text measured like Minecraft's default font draws it (the advance of each ASCII glyph of {@code ascii.png}, 6 for
 * most letters, 2 for "i" and ".", 4 for a space; 6 for anything else), for layout tests that ask whether a text fits
 * as it would in game. {@code FakeTextMeasure}-style fakes at 6 per character overestimate by about a sixth.
 */
public final class McFontText implements TextMeasure {
    public static final McFontText INSTANCE = new McFontText();

    private McFontText() {
    }

    /** One character's advance. */
    public static int advance(char c) {
        return switch (c) {
            case ' ', 't', 'I', '[', ']' -> 4;
            case 'i', '.', ',', ':', ';', '!', '|', '\'' -> 2;
            case 'l', '`' -> 3;
            case 'f', 'k', '(', ')', '<', '>', '{', '}', '"', '*' -> 5;
            case '@', '~' -> 7;
            default -> 6;
        };
    }

    @Override
    public int width(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += advance(text.charAt(i));
        }
        return width;
    }

    @Override
    public int lineHeight() {
        return 9;
    }

    @Override
    public String trimToWidth(String text, int maxWidth) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += advance(text.charAt(i));
            if (width > maxWidth) {
                return text.substring(0, i);
            }
        }
        return text;
    }
}
