package dev.sculptory.mc.client.editor.ui.render;

import dev.sculptory.mc.client.editor.ui.TextMeasure;
import net.minecraft.client.gui.Font;

/** {@link TextMeasure} backed by Minecraft's GUI font. */
public final class MinecraftTextMeasure implements TextMeasure {
    private final Font textRenderer;

    public MinecraftTextMeasure(Font textRenderer) {
        this.textRenderer = textRenderer;
    }

    @Override
    public int width(String text) {
        return textRenderer.width(text);
    }

    @Override
    public int lineHeight() {
        return textRenderer.lineHeight;
    }

    @Override
    public String trimToWidth(String text, int maxWidth) {
        return textRenderer.plainSubstrByWidth(text, Math.max(0, maxWidth));
    }
}
