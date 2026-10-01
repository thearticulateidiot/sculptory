package dev.sculptory.fabric.client.editor.ui;

/**
 * Text metrics used by layout. Layout and hit-testing depend on this interface rather than on
 * Minecraft's {@code TextRenderer}, so they can be unit-tested with a fixed-width fake.
 * The in-game implementation is {@code render.MinecraftTextMeasure}.
 */
public interface TextMeasure {
    /** Width of the string in scaled pixels. */
    int width(String text);

    /** Height of one line of text in scaled pixels. */
    int lineHeight();

    /** The longest prefix of {@code text} whose width is at most {@code maxWidth}. */
    String trimToWidth(String text, int maxWidth);
}
