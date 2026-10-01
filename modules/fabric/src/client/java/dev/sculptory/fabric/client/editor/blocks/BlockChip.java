package dev.sculptory.fabric.client.editor.blocks;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.widget.AbstractButton;
import java.util.Objects;
import net.minecraft.item.ItemStack;

/** A button showing a block's icon and name with a drop-down caret; clicking it opens a block picker. */
public class BlockChip extends AbstractButton {
    public static final int HEIGHT = 18;
    private static final int CARET_ROOM = 12;

    private final BlockCatalog catalog;
    private BlockDescriptor block;
    private int maxTextWidth = 110;

    public BlockChip(BlockCatalog catalog, BlockDescriptor block, Runnable onClick) {
        super(onClick);
        this.catalog = Objects.requireNonNull(catalog);
        this.block = Objects.requireNonNull(block);
    }

    public BlockDescriptor block() {
        return block;
    }

    public BlockChip setBlock(BlockDescriptor block) {
        this.block = Objects.requireNonNull(block);
        return this;
    }

    /** Longest name drawn before it is cut with "...". */
    public BlockChip setMaxTextWidth(int width) {
        this.maxTextWidth = Math.max(20, width);
        return this;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int text = Math.min(maxTextWidth, ctx.text().width(catalog.name(block)));
        return new Size(HEIGHT + 4 + text + CARET_ROOM, HEIGHT);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        boolean enabled = isEffectivelyEnabled();
        boolean hovered = enabled && ctx.isHovered(this);
        g.fill(bounds, !enabled ? theme.controlDisabled : isPressed() && hovered ? theme.controlPressed
                : hovered ? theme.controlHover : theme.control);
        g.outline(bounds, ctx.isFocused(this) ? theme.focusRing : theme.controlBorder);
        ItemStack icon = catalog.icon(block);
        if (icon != null && !icon.isEmpty()) {
            g.item(icon, bounds.x() + 1, bounds.y() + (bounds.height() - 16) / 2);
        }
        int textX = bounds.x() + HEIGHT + 2;
        int room = bounds.right() - CARET_ROOM - textX;
        String name = TextLayout.ellipsize(ctx.text(), catalog.name(block), room);
        int textY = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
        g.text(name, textX, textY, enabled ? theme.text : theme.textDisabled, theme.textShadow);
        g.triangleDown(bounds.right() - 7, bounds.y() + bounds.height() / 2 - 1, 3,
                enabled ? theme.textDim : theme.textDisabled);
    }

    @Override
    public String tooltip() {
        String explicit = super.tooltip();
        return explicit != null ? explicit : block.format();
    }
}
