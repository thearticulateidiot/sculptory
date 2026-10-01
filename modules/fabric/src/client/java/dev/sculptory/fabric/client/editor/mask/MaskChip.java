package dev.sculptory.fabric.client.editor.mask;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.blocks.BlockChip;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.widget.AbstractButton;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The top bar's Mask chip, beside the active block: "Mask" and its rule count,
 * glowing orange while the mask is on. A click opens the Mask window; the tooltip says what the mask does and which
 * key switches it.
 */
public final class MaskChip extends AbstractButton {
    /** The glow while the mask is on, and a lighter one under the mouse. */
    public static final int ON = 0xFFE07B1A;
    public static final int ON_HOVER = 0xFFF0943A;
    public static final int ON_TEXT = 0xFF1A1206;
    /** Toasts. */
    public static final String NOTICE_ON = "sculptory.mask.notice.on";
    public static final String NOTICE_OFF = "sculptory.mask.notice.off";
    public static final String NOTICE_NO_RULES = "sculptory.mask.notice.no_rules";

    private final EditMaskModel model;
    private final BlockCatalog blocks;
    private final Translator tr;
    private final Supplier<String> toggleKey;

    public MaskChip(EditMaskModel model, BlockCatalog blocks, Translator translator, Supplier<String> toggleKey,
            Runnable onClick) {
        super(onClick);
        this.model = Objects.requireNonNull(model);
        this.blocks = Objects.requireNonNull(blocks);
        this.tr = Objects.requireNonNull(translator);
        this.toggleKey = Objects.requireNonNull(toggleKey);
    }

    /** What the chip shows: "Mask", or "Mask 3" with three rules. */
    public String text() {
        int rules = model.rules().size();
        return rules == 0 ? tr.translate("sculptory.mask.chip") : tr.translate("sculptory.mask.chip.rules", rules);
    }

    /** Whether it glows (the mask is on and has rules). */
    public boolean glowing() {
        return model.active();
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        return new Size(ctx.text().width(text()) + 2 * ctx.theme().controlPaddingX, BlockChip.HEIGHT);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        boolean hovered = isEffectivelyEnabled() && ctx.isHovered(this);
        boolean on = glowing();
        int background = on ? (hovered ? ON_HOVER : ON)
                : isPressed() && hovered ? theme.controlPressed : hovered ? theme.controlHover : theme.control;
        g.fill(bounds, background);
        g.outline(bounds, ctx.isFocused(this) ? theme.focusRing : on ? ON_HOVER : theme.controlBorder);
        int y = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
        g.text(text(), bounds.x() + theme.controlPaddingX, y, on ? ON_TEXT : theme.text, false);
    }

    @Override
    public String tooltip() {
        String key = toggleKey.get();
        if (model.rules().isEmpty()) return tr.translate("sculptory.mask.chip.tooltip.empty", key);
        String rules = MaskSummary.rules(model.rules(), blocks, tr);
        return tr.translate(model.active() ? "sculptory.mask.chip.tooltip.on" : "sculptory.mask.chip.tooltip.off",
                rules, key);
    }
}
