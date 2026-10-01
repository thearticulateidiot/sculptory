package dev.sculptory.fabric.client.editor.mask;

import dev.sculptory.core.mask.EditMask;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.Toggle;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The Mask window, opened by the top bar's Mask chip: the switch (also Ctrl+M), the
 * rule list (every rule must match; each with Not) and Clear. Every edit changes the global mask at once; the rules are
 * saved, the switch is not. It says when an inside rule has no selection to use, and when the server cannot apply a
 * mask. Call {@link #refresh()} every frame while it is open.
 */
public final class MaskWindow {
    public static final int WIDTH = 210;
    public static final int HEIGHT = 250;

    private final EditMaskModel model;
    private final Supplier<UiContext> ui;
    private final Translator tr;
    private final Supplier<String> toggleKey;
    private final BooleanSupplier serverSupports;
    private final Toggle on;
    private final Label warning = Label.of("");
    private final RuleListEditor rules;
    private final Node root;
    /** The rules the editor shows, to tell a change made elsewhere (Clear, a saved file) from its own. */
    private List<?> shown;

    /**
     * @param serverSupports whether the server applies masks (no session, or one without {@code edit_mask}: false)
     */
    public MaskWindow(EditMaskModel model, Supplier<UiContext> ui, BlockCatalog blocks, Translator translator,
            Supplier<String> toggleKey, BooleanSupplier serverSupports) {
        this.model = Objects.requireNonNull(model);
        this.ui = Objects.requireNonNull(ui);
        this.tr = Objects.requireNonNull(translator);
        this.toggleKey = Objects.requireNonNull(toggleKey);
        this.serverSupports = Objects.requireNonNull(serverSupports);
        SplittableRandom seeds = new SplittableRandom();
        on = new Toggle(tr.translate("sculptory.mask.on"), model.on(), model::setOn);
        Button clear = new Button(tr.translate("sculptory.mask.clear"), () -> {
            model.setRules(List.of());
            refresh();
        });
        clear.setTooltip(tr.translate("sculptory.mask.clear.tooltip"));
        Row top = Row.of(on, Spacer.flexible(), clear);
        top.setGap(4);
        Label about = Label.dim(tr.translate("sculptory.mask.about"));
        about.setWrap(true);
        warning.setWrap(true);
        warning.setColor(MaskChip.ON_HOVER);
        warning.setVisible(false);
        rules = new RuleListEditor(ui, blocks, translator, RuleListEditor.Options.GLOBAL, seeds::nextLong,
                new EditMask(model.rules(), false), mask -> {
                    shown = mask.entries();
                    model.setRules(mask.entries());
                });
        shown = model.rules();
        Column content = Column.of(top, about, warning, rules.node());
        content.setGap(5);
        root = new ScrollPane(content);
        refresh();
    }

    public Node node() {
        return root;
    }

    /** Follows changes made elsewhere (Ctrl+M, Clear, the selection), unless one of its controls is in use. */
    public void refresh() {
        on.setValue(model.on());
        on.setTooltip(tr.translate("sculptory.mask.on.tooltip", toggleKey.get()));
        String problem = null;
        if (model.on() && !model.rules().isEmpty() && !serverSupports.getAsBoolean()) {
            problem = tr.translate("sculptory.mask.warning.server");
        } else if (model.insideWithoutSelection()) {
            problem = tr.translate("sculptory.mask.warning.no_selection");
        } else if (model.on() && model.rules().isEmpty()) {
            problem = tr.translate("sculptory.mask.warning.no_rules");
        }
        warning.setVisible(problem != null);
        if (problem != null) warning.setText(problem);
        if (!model.rules().equals(shown) && !inUse()) {
            shown = model.rules();
            rules.show(new EditMask(model.rules(), false));
        }
    }

    private boolean inUse() {
        UiContext ctx = ui.get();
        if (ctx == null) return false;
        Node focused = ctx.focused(), captured = ctx.captured();
        return (focused != null && focused.isDescendantOf(root)) || (captured != null && captured.isDescendantOf(root))
                || ctx.popups().isOpen();
    }
}
