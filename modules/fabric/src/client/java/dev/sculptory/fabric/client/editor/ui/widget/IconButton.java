package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import net.minecraft.item.ItemStack;

/**
 * A square button showing an item icon, with an optional selected state (palettes, tool bars).
 * Disabled buttons are dimmed but still show their tooltip, so it can say why.
 */
public class IconButton extends AbstractButton {
    private ItemStack icon;
    private boolean selected;

    /** {@code icon} may be null or empty for a blank button. */
    public IconButton(ItemStack icon, Runnable onClick) {
        super(onClick);
        this.icon = icon;
    }

    public ItemStack icon() {
        return icon;
    }

    public IconButton setIcon(ItemStack icon) {
        this.icon = icon;
        return this;
    }

    public boolean isSelected() {
        return selected;
    }

    public IconButton setSelected(boolean selected) {
        this.selected = selected;
        return this;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int size = ctx.theme().iconButtonSize;
        return new Size(size, size);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        boolean enabled = isEffectivelyEnabled();
        boolean hovered = enabled && ctx.isHovered(this);
        int background = isPressed() && hovered ? theme.controlPressed
                : hovered ? theme.controlHover
                : selected ? theme.accentDim
                : theme.control;
        g.fill(bounds, background);
        g.outline(bounds, selected ? theme.accent : theme.controlBorder);
        if (ctx.isFocused(this)) {
            g.outline(bounds.inset(1), theme.focusRing);
        }
        if (icon != null && !icon.isEmpty()) {
            g.item(icon, bounds.x() + (bounds.width() - 16) / 2, bounds.y() + (bounds.height() - 16) / 2);
        }
        if (!enabled) {
            // Item icons draw at z ~150, so the dimming overlay has to sit above them.
            g.pushLayer(theme.itemOverlayZ);
            g.fill(bounds.inset(1), theme.disabledOverlay);
            g.popLayer();
        }
    }
}
