package dev.sculptory.fabric.client.editor.ui;

import java.util.Objects;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/**
 * Draws a panel faded (View > Opacity…): fills in one of the theme's panel surface colours
 * ({@link Theme#fadesWithPanels}) get their alpha multiplied by the panel's opacity; text, icons, pictures, outlines and
 * every other fill are passed on unchanged, so what the panel shows stays as readable as before. Below
 * {@link Theme#fadedTextShadowBelow} text also gets a drop shadow, to read over the world behind the panel.
 * Everything else goes straight to the graphics it wraps.
 */
public final class FadedGraphics implements UiGraphics {
    private final UiGraphics target;
    private final Theme theme;
    private final float alpha;

    private FadedGraphics(UiGraphics target, Theme theme, float alpha) {
        this.target = Objects.requireNonNull(target);
        this.theme = Objects.requireNonNull(theme);
        this.alpha = alpha;
    }

    /** {@code target} drawing panels at {@code alpha} (0 to 1); {@code target} itself when that is fully opaque. */
    public static UiGraphics of(UiGraphics target, Theme theme, float alpha) {
        return alpha >= 1.0F ? target : new FadedGraphics(target, theme, Math.max(0.0F, alpha));
    }

    /** {@code argb} with its alpha multiplied by {@code alpha} (0 to 1). */
    public static int fade(int argb, float alpha) {
        int scaled = Math.round((argb >>> 24) * Math.max(0.0F, Math.min(1.0F, alpha)));
        return (scaled << 24) | (argb & 0x00FFFFFF);
    }

    public float alpha() {
        return alpha;
    }

    @Override
    public void fill(int x, int y, int width, int height, int argb) {
        target.fill(x, y, width, height, theme.fadesWithPanels(argb) ? fade(argb, alpha) : argb);
    }

    @Override
    public void outline(int x, int y, int width, int height, int argb) {
        target.outline(x, y, width, height, argb);
    }

    @Override
    public void text(String text, int x, int y, int argb, boolean shadow) {
        target.text(text, x, y, argb, shadow || alpha < theme.fadedTextShadowBelow);
    }

    @Override
    public void pushClip(Rect clip) {
        target.pushClip(clip);
    }

    @Override
    public void popClip() {
        target.popClip();
    }

    @Override
    public void pushLayer(int z) {
        target.pushLayer(z);
    }

    @Override
    public void popLayer() {
        target.popLayer();
    }

    @Override
    public void pushScale(float factor) {
        target.pushScale(factor);
    }

    @Override
    public void pushScale(int x, int y, float factor) {
        target.pushScale(x, y, factor);
    }

    @Override
    public void popScale() {
        target.popScale();
    }

    @Override
    public double pixelScale() {
        return target.pixelScale();
    }

    @Override
    public void item(ItemStack stack, int x, int y) {
        target.item(stack, x, y);
    }

    @Override
    public void widget(ClickableWidget widget, int mouseX, int mouseY, float delta) {
        target.widget(widget, mouseX, mouseY, delta);
    }

    @Override
    public void texture(Identifier texture, int x, int y, int width, int height, int textureWidth, int textureHeight) {
        target.texture(texture, x, y, width, height, textureWidth, textureHeight);
    }
}
