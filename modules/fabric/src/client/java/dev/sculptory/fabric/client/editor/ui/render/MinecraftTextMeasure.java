package dev.sculptory.fabric.client.editor.ui.render;

import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import net.minecraft.client.font.TextRenderer;

/** {@link TextMeasure} backed by Minecraft's GUI font. */
public final class MinecraftTextMeasure implements TextMeasure {
    private final TextRenderer textRenderer;

    public MinecraftTextMeasure(TextRenderer textRenderer) {
        this.textRenderer = textRenderer;
    }

    @Override
    public int width(String text) {
        return textRenderer.getWidth(text);
    }

    @Override
    public int lineHeight() {
        return textRenderer.fontHeight;
    }

    @Override
    public String trimToWidth(String text, int maxWidth) {
        return textRenderer.trimToWidth(text, Math.max(0, maxWidth));
    }
}
