package dev.sculptory.fabric.client.editor.mask;

import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.blocks.BlockPicker;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Dropdown;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.widget.Toggle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The rule list of a mask, as the Mask window and a brush's Mask section show it:
 * one block per rule (its kind, a Not switch, a remove button, and what the rule needs: the blocks it names, a range,
 * a percent), then "+ Add rule". Every rule must match. Each edit calls {@code onChange} with the new list; a change of
 * the list's shape (a rule added, removed or of another kind) rebuilds the rows, a slider moving does not.
 */
public final class RuleListEditor {
    /** What the editor may offer. */
    public record Options(boolean inside, boolean invertAll) {
        /** The global mask's: every rule, no "invert all". */
        public static final Options GLOBAL = new Options(true, false);
        /**
         * A brush's own mask: tested at the surface cell, and clipped to the selection by its own setting, so no inside
         * rule; "invert the whole mask" (the old Invert mask) is offered.
         */
        public static final Options BRUSH = new Options(false, true);
    }

    private final Supplier<UiContext> ui;
    private final BlockCatalog blocks;
    private final Translator tr;
    private final Options options;
    private final LongSupplier seeds;
    private final Consumer<EditMask> onChange;
    private final Column root = new Column();
    private List<MaskEntry> rules;
    private boolean invertAll;

    public RuleListEditor(Supplier<UiContext> ui, BlockCatalog blocks, Translator translator, Options options,
            LongSupplier seeds, EditMask initial, Consumer<EditMask> onChange) {
        this.ui = Objects.requireNonNull(ui);
        this.blocks = Objects.requireNonNull(blocks);
        this.tr = Objects.requireNonNull(translator);
        this.options = Objects.requireNonNull(options);
        this.seeds = Objects.requireNonNull(seeds);
        this.onChange = Objects.requireNonNull(onChange);
        this.rules = new ArrayList<>(initial.entries());
        this.invertAll = initial.invertAll();
        root.setGap(4);
        rebuild();
    }

    public Node node() {
        return root;
    }

    /** The mask the editor shows. */
    public EditMask value() {
        return new EditMask(rules, invertAll);
    }

    /** Shows {@code mask} (a change made elsewhere). */
    public void show(EditMask mask) {
        rules = new ArrayList<>(mask.entries());
        invertAll = mask.invertAll();
        rebuild();
    }

    private void changed(boolean shape) {
        onChange.accept(value());
        if (shape) rebuild();
    }

    private void rebuild() {
        root.clear();
        if (rules.isEmpty()) {
            Label none = Label.dim(tr.translate("sculptory.mask.no_rules"));
            none.setWrap(true);
            root.add(none);
        }
        for (int i = 0; i < rules.size(); i++) root.add(row(i));
        List<RuleKind> kinds = new ArrayList<>(Arrays.asList(RuleKind.values()));
        if (!options.inside()) kinds.remove(RuleKind.INSIDE);
        if (rules.size() < EditMask.MAX_ENTRIES) {
            // "+ Add rule" is the list's first entry and never a rule: picking a kind adds one and rebuilds the list.
            List<Optional<RuleKind>> choices = new ArrayList<>();
            choices.add(Optional.empty());
            for (RuleKind kind : kinds) choices.add(Optional.of(kind));
            Dropdown<Optional<RuleKind>> add = new Dropdown<>(choices, Optional.empty(), choice -> choice
                    .map(kind -> tr.translate(kind.nameKey())).orElse(tr.translate("sculptory.mask.add_rule")),
                    choice -> choice.ifPresent(kind -> {
                        rules.add(MaskEntry.of(kind.create(null, seeds)));
                        changed(true);
                    }));
            add.setListOnly(true);
            add.setTooltip(tr.translate("sculptory.mask.add_rule.tooltip"));
            root.add(add);
        }
        if (options.invertAll()) {
            Toggle invert = new Toggle(tr.translate("sculptory.mask.invert_all"), invertAll, on -> {
                invertAll = on;
                changed(false);
            });
            invert.setTooltip(tr.translate("sculptory.mask.invert_all.tooltip"));
            root.add(invert);
        }
    }

    /** One rule's block: kind, Not, remove; then what the rule needs. */
    private Node row(int index) {
        MaskEntry entry = rules.get(index);
        RuleKind kind = RuleKind.of(entry.rule());
        List<RuleKind> kinds = new ArrayList<>(Arrays.asList(RuleKind.values()));
        if (!options.inside() && kind != RuleKind.INSIDE) kinds.remove(RuleKind.INSIDE);
        Dropdown<RuleKind> which = new Dropdown<>(kinds, kind, k -> tr.translate(k.nameKey()), k -> {
            if (k == null || k == RuleKind.of(rules.get(index).rule())) return;
            BlockSet named = RuleKind.blocksOf(rules.get(index).rule());
            rules.set(index, new MaskEntry(k.create(named, seeds), rules.get(index).not()));
            changed(true);
        });
        which.setGrow(1);
        Toggle not = new Toggle(tr.translate("sculptory.mask.not"), entry.not(), on -> {
            rules.set(index, new MaskEntry(rules.get(index).rule(), on));
            changed(false);
        });
        not.setTooltip(tr.translate("sculptory.mask.not.tooltip"));
        Button remove = new Button("×", () -> {
            rules.remove(index);
            changed(true);
        });
        remove.setStyle(Button.Style.FLAT);
        remove.setTooltip(tr.translate("sculptory.mask.remove_rule"));
        Row head = Row.of(which, not, remove);
        head.setGap(4);
        Column block = Column.of(head);
        block.setGap(2);
        Node details = details(index, entry.rule());
        if (details != null) block.add(details);
        return block;
    }

    /** What a rule needs besides its kind, or {@code null}. */
    private Node details(int index, MaskRule rule) {
        return switch (rule) {
            case MaskRule.Is is -> blocksButton(index, is.blocks());
            case MaskRule.OnTopOf on -> blocksButton(index, on.blocks());
            case MaskRule.Under under -> blocksButton(index, under.blocks());
            case MaskRule.NextTo next -> blocksButton(index, next.blocks());
            case MaskRule.Height height -> {
                int[] span = {height.minY(), height.maxY()};
                Slider low = Slider.ofInt(tr.translate("sculptory.form.min"), RuleKind.MIN_Y, RuleKind.MAX_Y, span[0], v -> {
                    span[0] = v;
                    span[1] = Math.max(span[1], v);
                    rules.set(index, new MaskEntry(new MaskRule.Height(span[0], span[1]), rules.get(index).not()));
                    changed(false);
                });
                Slider high = Slider.ofInt(tr.translate("sculptory.form.max"), RuleKind.MIN_Y, RuleKind.MAX_Y, span[1], v -> {
                    span[1] = v;
                    span[0] = Math.min(span[0], v);
                    rules.set(index, new MaskEntry(new MaskRule.Height(span[0], span[1]), rules.get(index).not()));
                    changed(false);
                });
                yield range(low, high);
            }
            case MaskRule.Slope slope -> {
                int[] span = {slope.minStep(), slope.maxStep()};
                Slider low = Slider.ofInt(tr.translate("sculptory.form.min"), 0, MaskRule.MAX_SLOPE, span[0], v -> {
                    span[0] = v;
                    span[1] = Math.max(span[1], v);
                    rules.set(index, new MaskEntry(new MaskRule.Slope(span[0], span[1]), rules.get(index).not()));
                    changed(false);
                });
                Slider high = Slider.ofInt(tr.translate("sculptory.form.max"), 0, MaskRule.MAX_SLOPE, span[1], v -> {
                    span[1] = v;
                    span[0] = Math.min(span[0], v);
                    rules.set(index, new MaskEntry(new MaskRule.Slope(span[0], span[1]), rules.get(index).not()));
                    changed(false);
                });
                yield range(low, high);
            }
            case MaskRule.Chance chance -> {
                long[] seed = {chance.seed()};
                Slider percent = Slider.ofInt(tr.translate("sculptory.mask.percent"), 1, 99, chance.percent(), v -> {
                    rules.set(index, new MaskEntry(new MaskRule.Chance(v, seed[0]), rules.get(index).not()));
                    changed(false);
                });
                percent.setGrow(1);
                Button reroll = new Button(tr.translate("sculptory.mask.reroll"), () -> {
                    seed[0] = seeds.getAsLong();
                    MaskRule.Chance now = (MaskRule.Chance) rules.get(index).rule();
                    rules.set(index, new MaskEntry(new MaskRule.Chance(now.percent(), seed[0]), rules.get(index).not()));
                    changed(false);
                });
                reroll.setTooltip(tr.translate("sculptory.mask.reroll.tooltip"));
                Row line = Row.of(percent, reroll);
                line.setGap(4);
                yield line;
            }
            case MaskRule.Inside inside -> {
                Label about = Label.dim(tr.translate("sculptory.mask.inside.about"));
                about.setWrap(true);
                yield about;
            }
            default -> null;
        };
    }

    private static Node range(Slider low, Slider high) {
        low.setGrow(1);
        high.setGrow(1);
        Row line = Row.of(low, high);
        line.setGap(4);
        return line;
    }

    /** The blocks a rule names, as a button opening the set picker. */
    private Node blocksButton(int index, BlockSet set) {
        Button button = new Button(MaskSummary.blocks(set, blocks), null);
        button.setTooltip(tr.translate("sculptory.mask.blocks.tooltip"));
        button.setOnClick(() -> BlockPicker.openSet(ui.get(), button.bounds(), blocks, tr, set, BlockSet.MAX_ENTRIES,
                picked -> {
                    MaskEntry current = rules.get(index);
                    rules.set(index, new MaskEntry(RuleKind.withBlocks(current.rule(), picked), current.not()));
                    changed(true);
                }));
        return button;
    }
}
