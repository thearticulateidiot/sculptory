package dev.sculptory.fabric.client.editor.tool;

/** Screen-space HUD drawing for tools, in scaled pixels. Colours are ARGB. Implemented by the UI layer. */
public interface HudDraw {
    int width();

    int height();

    int textWidth(String text);

    void text(String text, int x, int y, int argb);

    void fill(int x1, int y1, int x2, int y2, int argb);
}
