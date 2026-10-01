package dev.sculptory.fabric.client.editor.settings.form;

import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.widget.Button;

/**
 * A setting's reset button: a "↺" drawn as a {@value #ICON_SIZE}-unit pixel icon (the font's own glyph comes from the
 * fallback font, a thin outline a few units wide, hard to make out at small UI sizes), flat until hovered. Its text
 * stays {@link SettingsForm#RESET}.
 */
final class ResetButton extends Button {
    static final int ICON_SIZE = 9;
    /** An anticlockwise open circle, its arrowhead at the top left pointing left. */
    private static final String[] ICON = {
            "..X......",
            ".XXXXXX..",
            "..X....X.",
            "........X",
            "X.......X",
            "X.......X",
            "X.......X",
            ".X.....X.",
            "..XXXXX..",
    };

    ResetButton(Runnable onClick) {
        super(SettingsForm.RESET, onClick);
        setStyle(Style.FLAT);
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        return new Size(ctx.theme().resetButtonWidth, ctx.theme().controlHeight);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        boolean enabled = isEffectivelyEnabled();
        boolean hovered = enabled && ctx.isHovered(this);
        boolean down = isPressed() && hovered;
        if (hovered) {
            g.fill(bounds, down ? theme.controlPressed : theme.controlHover);
        }
        if (ctx.isFocused(this)) {
            g.outline(bounds, theme.focusRing);
        }
        int color = enabled ? theme.text : theme.textDisabled;
        int left = bounds.x() + (bounds.width() - ICON_SIZE) / 2;
        int top = bounds.y() + (bounds.height() - ICON_SIZE + 1) / 2 + (down ? 1 : 0);
        for (int row = 0; row < ICON.length; row++) {
            String line = ICON[row];
            int start = -1;
            for (int column = 0; column <= line.length(); column++) {
                boolean on = column < line.length() && line.charAt(column) == 'X';
                if (on && start < 0) {
                    start = column;
                } else if (!on && start >= 0) {
                    g.fill(left + start, top + row, column - start, 1, color);
                    start = -1;
                }
            }
        }
    }
}
