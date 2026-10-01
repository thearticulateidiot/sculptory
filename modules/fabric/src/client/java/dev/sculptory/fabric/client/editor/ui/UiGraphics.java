package dev.sculptory.fabric.client.editor.ui;

import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/**
 * The drawing surface nodes render into. The in-game implementation
 * ({@code render.DrawContextGraphics}) forwards to {@code DrawContext}; tests use a recording fake.
 * Coordinates are scaled GUI pixels, or UI units inside {@link #pushScale}; colours are ARGB.
 */
public interface UiGraphics {
    void fill(int x, int y, int width, int height, int argb);

    void text(String text, int x, int y, int argb, boolean shadow);

    /** Restricts drawing to {@code clip}, intersected with any enclosing clip. */
    void pushClip(Rect clip);

    void popClip();

    /** Raises subsequent drawing by {@code z}, so it covers earlier layers (including item icons). */
    void pushLayer(int z);

    void popLayer();

    /**
     * Scales subsequent drawing (text, items and clips included) by {@code factor} about the
     * screen's top-left corner: the editor UI size (see {@link UiScale#draw}).
     */
    void pushScale(float factor);

    /**
     * Scales subsequent drawing by {@code factor} with the origin moved to (x, y): what is then drawn at (0, 0) lands
     * at (x, y) of the coordinates before. Popped with {@link #popScale}. The wiki draws its pages with it, a little
     * larger than the rest of the UI. By default it is {@link #pushScale(float)} with the origin left where it is, for
     * test fakes that don't follow positions.
     */
    default void pushScale(int x, int y, float factor) {
        pushScale(factor);
    }

    void popScale();

    /**
     * Framebuffer pixels per unit at the current scale (in game: the GUI scale times the scales pushed); 1 by default.
     * The wiki uses it to show pictures at their own size and draw its larger text on whole pixels.
     */
    default double pixelScale() {
        return 1;
    }

    /** Draws a 16x16 item icon with its top-left corner at (x, y). */
    void item(ItemStack stack, int x, int y);

    /** Renders a vanilla widget that a node wraps (used by {@code TextInput}). */
    void widget(ClickableWidget widget, int mouseX, int mouseY, float delta);

    /**
     * Draws the whole of a registered texture of {@code textureWidth} x {@code textureHeight} pixels scaled into the
     * rectangle (the wiki's pictures). Does nothing by default: test fakes that don't record textures leave it out.
     */
    default void texture(Identifier texture, int x, int y, int width, int height, int textureWidth, int textureHeight) {
    }

    default void fill(Rect rect, int argb) {
        fill(rect.x(), rect.y(), rect.width(), rect.height(), argb);
    }

    /** A one-pixel outline inside the rectangle. */
    default void outline(int x, int y, int width, int height, int argb) {
        if (width <= 0 || height <= 0) {
            return;
        }
        fill(x, y, width, 1, argb);
        fill(x, y + height - 1, width, 1, argb);
        fill(x, y + 1, 1, height - 2, argb);
        fill(x + width - 1, y + 1, 1, height - 2, argb);
    }

    default void outline(Rect rect, int argb) {
        outline(rect.x(), rect.y(), rect.width(), rect.height(), argb);
    }

    /**
     * A shadow two units below and right of the rectangle, drawn only where it shows beside it (a strip along the
     * right edge and one along the bottom), so a see-through surface over it isn't darkened by the shadow under it.
     */
    default void dropShadow(Rect rect, int argb) {
        if (rect.width() <= 0 || rect.height() <= 0) {
            return;
        }
        fill(rect.right(), rect.y() + 2, 2, rect.height(), argb);
        fill(rect.x() + 2, rect.bottom(), rect.width() - 2, 2, argb);
    }

    /** A downward-pointing triangle, {@code 2 * halfWidth + 1} pixels wide at the top. */
    default void triangleDown(int centerX, int top, int halfWidth, int argb) {
        for (int row = 0; row <= halfWidth; row++) {
            fill(centerX - halfWidth + row, top + row, (halfWidth - row) * 2 + 1, 1, argb);
        }
    }

    /** A right-pointing triangle, {@code 2 * halfHeight + 1} pixels tall at the left. */
    default void triangleRight(int left, int centerY, int halfHeight, int argb) {
        for (int column = 0; column <= halfHeight; column++) {
            fill(left + column, centerY - halfHeight + column, 1, (halfHeight - column) * 2 + 1, argb);
        }
    }
}
