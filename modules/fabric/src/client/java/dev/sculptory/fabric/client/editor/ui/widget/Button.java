package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.Objects;

/** A text button. Fires on release over the button, or on Enter/Space while focused. */
public class Button extends AbstractButton {
    public enum Style { DEFAULT, PRIMARY, DANGER, FLAT }

    private String text;
    private Style style = Style.DEFAULT;
    private int textColor;

    public Button(String text, Runnable onClick) {
        super(onClick);
        this.text = Objects.requireNonNull(text);
    }

    public String text() {
        return text;
    }

    public Button setText(String text) {
        this.text = Objects.requireNonNull(text);
        return this;
    }

    public Button setStyle(Style style) {
        this.style = style;
        return this;
    }

    public Style style() {
        return style;
    }

    /** A text colour in place of the theme's for a default or flat button (a key shown in red); 0 restores the theme's. */
    public Button setTextColor(int argb) {
        this.textColor = argb;
        return this;
    }

    public int textColor() {
        return textColor;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        Theme theme = ctx.theme();
        return new Size(ctx.text().width(text) + 2 * theme.controlPaddingX, theme.controlHeight);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        boolean enabled = isEffectivelyEnabled();
        boolean hovered = enabled && ctx.isHovered(this);
        boolean down = isPressed() && hovered;
        int background;
        int foreground = theme.text;
        switch (style) {
            case PRIMARY -> {
                background = down ? theme.accentDim : hovered ? theme.accentHover : theme.accent;
                foreground = theme.textOnAccent;
            }
            case DANGER -> {
                background = hovered && !down ? theme.dangerHover : theme.danger;
                foreground = theme.textOnAccent;
            }
            case FLAT -> background = down ? theme.controlPressed : hovered ? theme.controlHover : 0;
            default -> background = down ? theme.controlPressed : hovered ? theme.controlHover : theme.control;
        }
        if (textColor != 0 && (style == Style.DEFAULT || style == Style.FLAT)) {
            foreground = textColor;
        }
        if (!enabled) {
            background = style == Style.FLAT ? 0 : theme.controlDisabled;
            foreground = theme.textDisabled;
        }
        if (background != 0) {
            g.fill(bounds, background);
        }
        if (style == Style.DEFAULT) {
            g.outline(bounds, theme.controlBorder);
        }
        if (ctx.isFocused(this)) {
            g.outline(bounds, theme.focusRing);
        }
        // Text that fits the button but not its padding (a one-symbol button) is shown whole, not cut.
        int room = ctx.text().width(text) <= bounds.width() - 2
                ? bounds.width() - 2
                : bounds.width() - 2 * theme.controlPaddingX;
        String shown = TextLayout.ellipsize(ctx.text(), text, room);
        int x = bounds.x() + (bounds.width() - ctx.text().width(shown)) / 2;
        int y = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2 + (down ? 1 : 0);
        g.text(shown, x, y, foreground, theme.textShadow);
    }
}
