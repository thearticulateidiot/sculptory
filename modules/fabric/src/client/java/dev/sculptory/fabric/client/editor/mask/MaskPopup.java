package dev.sculptory.fabric.client.editor.mask;

import dev.sculptory.core.mask.EditMask;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.Consumer;

/** A brush's own mask edited in a popup below its setting. */
public final class MaskPopup {
    public static final int WIDTH = 220;

    private MaskPopup() {}

    /** Opens the rule editor ({@link RuleListEditor.Options#BRUSH}); {@code onChange} runs with each edit. */
    public static PopupLayer.Popup open(UiContext ctx, Rect anchor, BlockCatalog blocks, Translator tr, EditMask current,
            Consumer<EditMask> onChange) {
        Objects.requireNonNull(onChange);
        SplittableRandom seeds = new SplittableRandom();
        RuleListEditor editor = new RuleListEditor(() -> ctx, blocks, tr, RuleListEditor.Options.BRUSH, seeds::nextLong,
                current, onChange);
        Label about = Label.dim(tr.translate("sculptory.setting.brush.mask.rules.tooltip"));
        about.setWrap(true);
        Column content = Column.of(about, editor.node());
        content.setGap(4);
        content.setFixedWidth(WIDTH - 2);
        return ctx.popups().open(null, content, anchor, WIDTH, null);
    }
}
