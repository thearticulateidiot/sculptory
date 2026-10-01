package dev.sculptory.fabric.client.editor.settings.form;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.blocks.BlockChip;
import dev.sculptory.fabric.client.editor.mask.MaskSummary;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.settings.Validation;
import dev.sculptory.fabric.client.editor.ui.FocusTraversal;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.CollapsibleSection;
import dev.sculptory.fabric.client.editor.ui.widget.Dropdown;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.ui.widget.Toggle;
import dev.sculptory.protocol.v2.Limits;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Builds a tool's settings window from its {@link SettingsSchema}. Each setting type maps to a
 * widget:
 * <ul>
 *   <li>Int, Decimal: a slider with the value in it (double-click, Ctrl+click or Enter types an exact value);</li>
 *   <li>Enum: a segmented control (up to four options) or a dropdown;</li>
 *   <li>Bool: a toggle;</li>
 *   <li>Block: a block chip that opens the picker;</li>
 *   <li>BlockList, WeightedBlocks, AssetMix: rows with add/remove (and weights);</li>
 *   <li>IntRange: min and max sliders;</li>
 *   <li>Seed: a number field with a Random button.</li>
 * </ul>
 * Sections with a title become collapsible; whether each is open is kept by a {@link SectionMemory}. Settings hide
 * and show with their {@code visibleWhen}, and validation problems show under the setting. A setting explains itself
 * in a tooltip when its label key plus {@value #TOOLTIP_SUFFIX} has a translation (over its label and control), and an
 * enum option when {@link #optionTooltipKey} has one. A setting whose value differs from its default shows a reset
 * button ("↺") at its right, beside the control or over a slider's right end ({@link SettingLine}). Names and
 * options that don't fit the window's width wrap rather than being cut short. Every edit produces new values through
 * {@code onChange}; {@link #refresh} pushes values changed elsewhere (Ctrl+Scroll) into the controls;
 * {@link #revealSetting} opens, focuses and returns a setting's row.
 */
public final class SettingsForm {
    /** The widget a setting became. */
    public enum Kind { SLIDER, SEGMENTED, DROPDOWN, TOGGLE, BLOCK, BLOCK_LIST, WEIGHTED_BLOCKS, INT_RANGE, SEED, ASSET_MIX, MASK_RULES }

    /** What the form needs from the editor. */
    public interface Services {
        Translator translator();

        BlockCatalog blocks();

        /** Opens the block picker below {@code anchor}. */
        void pickBlock(Rect anchor, BlockDescriptor current, Consumer<BlockDescriptor> onPick);

        /** The server limits for validation, or null when unknown. */
        Limits limits();

        /**
         * Opens the rule editor of a mask setting below {@code anchor}, starting from
         * {@code current}; {@code onChange} runs with each edit. Does nothing where no editor is offered.
         */
        default void editMask(Rect anchor, EditMask current, Consumer<EditMask> onChange) {}

        /** The client's block states, for a mask setting's legacy keys ({@code null} when none). */
        default StateSpace states() {
            return null;
        }
    }

    /** Whether each titled section is open, remembered by whoever builds the form (per tool). */
    public interface SectionMemory {
        /** Nothing remembered: every section starts as its schema says. */
        SectionMemory NONE = new SectionMemory() {
            @Override
            public Optional<Boolean> expanded(String titleKey) {
                return Optional.empty();
            }

            @Override
            public void toggled(String titleKey, boolean expanded) {
            }
        };

        /** Whether the section titled {@code titleKey} was left open, or empty for its default. */
        Optional<Boolean> expanded(String titleKey);

        /** The player opened or closed the section. */
        void toggled(String titleKey, boolean expanded);
    }

    private static final int ERROR_COLOR = 0xFFE5655D;
    private static final int WARNING_COLOR = 0xFFE0B040;
    private static final BlockDescriptor DEFAULT_BLOCK = BlockDescriptor.of(new NamespacedId("minecraft:stone"));
    private static final String REMOVE = "×";
    /** The reset button's symbol. */
    public static final String RESET = "↺";
    /** How many entries of a list default the reset tooltip names before "…". */
    private static final int DESCRIBED_ENTRIES = 3;
    /** A setting whose label key {@code k} has a translation {@code k + ".tooltip"} shows it on its control. */
    public static final String TOOLTIP_SUFFIX = ".tooltip";
    /** A seed whose label key {@code k} has a translation {@code k + ".button"} names its button so (else "Random"). */
    public static final String BUTTON_SUFFIX = ".button";

    /** The translation key of an enum option's own tooltip: {@code <labelKey>.<option>.tooltip}. */
    public static String optionTooltipKey(String labelKey, java.lang.Enum<?> option) {
        return labelKey + "." + option.name().toLowerCase(Locale.ROOT) + TOOLTIP_SUFFIX;
    }

    private final SettingsSchema schema;
    private final Services services;
    private final Consumer<SettingsValues> onChange;
    private final SectionMemory memory;
    private final Map<String, Field<?>> fields = new LinkedHashMap<>();
    private final List<SectionNode> sections = new ArrayList<>();
    /** The titled section each setting is in (settings of untitled sections are absent). */
    private final Map<String, SectionNode> sectionOf = new HashMap<>();
    private final Column root = new Column();
    private final Random random = new Random();
    private SettingsValues values;

    private SettingsForm(SettingsValues values, Consumer<SettingsValues> onChange, Services services,
            SectionMemory memory) {
        this.schema = values.schema();
        this.values = values;
        this.onChange = Objects.requireNonNull(onChange);
        this.services = Objects.requireNonNull(services);
        this.memory = Objects.requireNonNull(memory);
    }

    /** Builds the form for {@code values}' schema, every section as the schema says. */
    public static SettingsForm build(SettingsValues values, Consumer<SettingsValues> onChange, Services services) {
        return build(values, onChange, services, SectionMemory.NONE);
    }

    /** Builds the form for {@code values}' schema, with sections open or closed as {@code memory} remembers. */
    public static SettingsForm build(SettingsValues values, Consumer<SettingsValues> onChange, Services services,
            SectionMemory memory) {
        SettingsForm form = new SettingsForm(Objects.requireNonNull(values), onChange, services, memory);
        form.buildTree();
        return form;
    }

    public Node node() {
        return root;
    }

    public SettingsValues values() {
        return values;
    }

    public Optional<Kind> kind(String key) {
        return Optional.ofNullable(fields.get(key)).map(field -> field.kind);
    }

    /** The main control of a setting (the slider, toggle, chip...). */
    public Optional<Node> control(String key) {
        return Optional.ofNullable(fields.get(key)).map(field -> field.control);
    }

    /** The node holding a setting's widgets and message; hidden while {@code visibleWhen} is false. */
    public Optional<Node> field(String key) {
        return Optional.ofNullable(fields.get(key)).map(field -> field.root);
    }

    /** A setting's reset button: shown while its value differs from the default. */
    public Optional<Button> resetButton(String key) {
        return Optional.ofNullable(fields.get(key)).map(field -> field.reset);
    }

    /** The collapsible section titled {@code titleKey}, if the schema has one. */
    public Optional<CollapsibleSection> section(String titleKey) {
        return sections.stream().filter(section -> section.section().titleKey().equals(titleKey))
                .map(SectionNode::node).findFirst();
    }

    /** The validation message shown under a setting, or "" when none. */
    public String message(String key) {
        Field<?> field = fields.get(key);
        return field == null || !field.message.isVisible() ? "" : field.message.text();
    }

    /** Shows values that changed outside the form, without calling {@code onChange}. */
    public void refresh(SettingsValues external) {
        if (!external.schema().equals(schema)) {
            throw new IllegalArgumentException("Values for another schema");
        }
        SettingsValues before = values;
        values = external;
        for (Field<?> field : fields.values()) {
            field.showIfChanged(before);
        }
        updateState();
    }

    /**
     * Brings setting {@code key} forward for the player: opens its section (remembered like a click on it) and puts
     * the keyboard on its control (the first focusable node of its row when the control takes none). Returns the row
     * to scroll into view, or empty when the form has no such setting or it is hidden now ({@code visibleWhen}).
     */
    public Optional<Node> revealSetting(String key, UiContext ctx) {
        Field<?> field = fields.get(key);
        if (field == null || !values.isVisible(field.def)) {
            return Optional.empty();
        }
        SectionNode section = sectionOf.get(key);
        if (section != null && !section.node().isExpanded()) {
            section.node().setExpanded(true);
            memory.toggled(section.section().titleKey(), true);
        }
        Node target = field.control.isFocusable() && field.control.isShown()
                ? field.control
                : FocusTraversal.next(field.root, null);
        ctx.setFocus(target);
        return Optional.of(field.root);
    }

    // ---- Building ----

    private void buildTree() {
        root.setGap(6);
        for (Section section : schema.sections()) {
            Column column = new Column();
            column.setGap(6);
            for (SettingDef<?> def : section.settings()) {
                Field<?> field = create(def);
                fields.put(def.key(), field);
                column.add(field.root);
            }
            if (section.titleKey().isEmpty()) {
                root.add(column);
            } else {
                String titleKey = section.titleKey();
                boolean expanded = memory.expanded(titleKey).orElse(!section.collapsedByDefault());
                CollapsibleSection node = new CollapsibleSection(tr(titleKey), column, expanded);
                node.setOnToggle(open -> memory.toggled(titleKey, open));
                SectionNode entry = new SectionNode(section, node);
                sections.add(entry);
                for (SettingDef<?> def : section.settings()) {
                    sectionOf.put(def.key(), entry);
                }
                root.add(node);
            }
        }
        updateState();
    }

    /** A titled section and its node, hidden while none of its settings apply. */
    private record SectionNode(Section section, CollapsibleSection node) {}

    private Field<?> create(SettingDef<?> def) {
        return switch (def) {
            case SettingDef.Int d -> new IntField(d);
            case SettingDef.Decimal d -> new DecimalField(d);
            case SettingDef.Bool d -> new BoolField(d);
            case SettingDef.Enum<?> d -> enumField(d);
            case SettingDef.Block d -> new BlockField(d);
            case SettingDef.BlockList d -> new BlockListField(d);
            case SettingDef.WeightedBlocks d -> new WeightedField(d);
            case SettingDef.IntRange d -> new RangeField(d);
            case SettingDef.Seed d -> new SeedField(d);
            case SettingDef.AssetMix d -> new AssetField(d);
            case SettingDef.MaskRules d -> new MaskRulesField(d);
        };
    }

    private <E extends java.lang.Enum<E>> Field<E> enumField(SettingDef.Enum<E> def) {
        return new EnumField<>(def);
    }

    private <T> void set(SettingDef<T> def, T value) {
        SettingsValues next;
        try {
            next = values.with(def, value);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        if (next.equals(values)) {
            return;
        }
        values = next;
        updateState();
        onChange.accept(next);
    }

    private void updateState() {
        Map<String, Validation> validation = values.validate(services.limits());
        for (SectionNode section : sections) {
            section.node().setVisible(section.section().settings().stream().anyMatch(values::isVisible));
        }
        for (Field<?> field : fields.values()) {
            field.root.setVisible(values.isVisible(field.def));
            field.reset.setVisible(field.changed());
            Validation result = validation.get(field.def.key());
            boolean show = result != null && result.level() != Validation.Level.OK;
            field.message.setVisible(show);
            if (show) {
                field.message.setText(services.translator().translate(result.messageKey(), result.args()));
                field.message.setColor(result.level() == Validation.Level.ERROR ? ERROR_COLOR : WARNING_COLOR);
            }
        }
    }

    private String tr(String key) {
        return services.translator().translate(key);
    }

    private static String pretty(String constant) {
        String lower = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException malformed) {
            return null;
        }
    }

    private static TextInput numberInput(String text, int width, Consumer<String> onChange) {
        TextInput input = new TextInput(text, onChange);
        input.setFixedWidth(width);
        input.setMaxLength(20);
        return input;
    }

    /** An enum option's label: its translation, or its constant name in sentence case. */
    private <E extends java.lang.Enum<E>> String optionLabel(SettingDef.Enum<E> def, E option) {
        return services.translator().translateOr(def.labelKey() + "." + option.name().toLowerCase(Locale.ROOT),
                pretty(option.name()));
    }

    // ---- Describing a default (the reset tooltip) ----

    /** A setting's default as the player would read it: "5", "0.60", "On", "Smooth", "Stone", "none". */
    String describeDefault(SettingDef<?> def) {
        return switch (def) {
            case SettingDef.Int d -> Integer.toString(d.defaultValue());
            case SettingDef.Decimal d -> String.format(Locale.ROOT, "%." + decimals(d.step()) + "f", d.defaultValue());
            case SettingDef.Bool d -> tr(d.defaultValue() ? "sculptory.form.on" : "sculptory.form.off");
            case SettingDef.Enum<?> d -> describeOption(d);
            case SettingDef.Block d -> services.blocks().name(d.defaultValue());
            case SettingDef.BlockList d -> describeList(d.defaultValue().stream().map(services.blocks()::name).toList());
            case SettingDef.WeightedBlocks d -> describeList(d.defaultValue().stream()
                    .map(entry -> services.blocks().name(entry.block()) + " (" + entry.weight() + ")").toList());
            case SettingDef.IntRange d -> tr("sculptory.form.range", d.defaultValue().min(), d.defaultValue().max());
            case SettingDef.Seed d -> Long.toString(d.defaultValue());
            case SettingDef.AssetMix d -> describeList(d.defaultValue().stream()
                    .map(entry -> entry.asset() + " (" + entry.weight() + ")").toList());
            case SettingDef.MaskRules d -> tr("sculptory.setting.brush.mask.rules.none");
        };
    }

    private <E extends java.lang.Enum<E>> String describeOption(SettingDef.Enum<E> def) {
        return optionLabel(def, def.defaultValue());
    }

    private String tr(String key, Object... args) {
        return services.translator().translate(key, args);
    }

    private String describeList(List<String> names) {
        if (names.isEmpty()) {
            return tr("sculptory.form.none");
        }
        List<String> shown = names.subList(0, Math.min(DESCRIBED_ENTRIES, names.size()));
        return String.join(", ", shown) + (names.size() > shown.size() ? ", …" : "");
    }

    private static int decimals(double step) {
        return Math.max(0, BigDecimal.valueOf(step).stripTrailingZeros().scale());
    }

    // ---- Fields ----

    private abstract class Field<T> {
        final SettingDef<T> def;
        final Kind kind;
        final Column root = new Column();
        final Label message = Label.of("");
        /** Resets the setting to its default; shown only while the value differs from it. */
        final Button reset;
        final String tooltip;
        Node control;

        Field(SettingDef<T> def, Kind kind) {
            this.def = def;
            this.kind = kind;
            root.setGap(2);
            message.setWrap(true);
            message.setVisible(false);
            String tooltipKey = def.labelKey() + TOOLTIP_SUFFIX;
            tooltip = services.translator().has(tooltipKey) ? tr(tooltipKey) : null;
            reset = new ResetButton(this::resetToDefault);
            reset.setTooltip(tr("sculptory.form.reset.tooltip", describeDefault(def)));
            reset.setVisible(false);
        }

        /** {@code main} as wide as the line, the reset button at its right while it shows: a setting's first line. */
        SettingLine line(Node main) {
            return new SettingLine(main, reset);
        }

        /** The setting's name above its control (wrapping, never cut short), with the reset button at its right. */
        SettingLine heading() {
            Label label = Label.dim(tr(def.labelKey()));
            label.setWrap(true);
            label.setTooltip(tooltip);
            return line(label);
        }

        void finish(Node body, Node control) {
            this.control = control;
            if (tooltip != null) {
                root.setTooltip(tooltip);
                control.setTooltip(tooltip);
            }
            root.add(body, message);
        }

        T value() {
            return values.get(def);
        }

        void set(T value) {
            SettingsForm.this.set(def, value);
        }

        boolean changed() {
            return !Objects.equals(value(), def.canonical(def.defaultValue()));
        }

        /** Back to the default, through the normal change path (presets see the change). */
        void resetToDefault() {
            T initial = def.canonical(def.defaultValue());
            set(initial);
            show(value());
        }

        void showIfChanged(SettingsValues before) {
            T now = values.get(def);
            if (!Objects.equals(before.get(def), now)) {
                show(now);
            }
        }

        abstract void show(T value);
    }

    private final class IntField extends Field<Integer> {
        private Slider slider;
        private Label fixedValue;

        IntField(SettingDef.Int def) {
            super(def, Kind.SLIDER);
            Limits limits = services.limits();
            int cap = def.max();
            if (def.serverMax() != null && limits != null) {
                cap = Math.max(def.min(), Math.min(cap, def.serverMax().applyAsInt(limits)));
            }
            int min = def.min();
            int max = cap;
            int value = Math.max(min, Math.min(max, value()));
            if (max > min) {
                slider = Slider.ofInt(tr(def.labelKey()), min, max, value, this::set);
                finish(line(slider), slider);
            } else {
                // Only one value is possible (the server allows no more): show it, nothing to drag.
                Label label = Label.dim(tr(def.labelKey()));
                fixedValue = Label.of(Integer.toString(value));
                Row row = Row.of(label, fixedValue);
                finish(line(row), row);
            }
        }

        @Override
        void show(Integer value) {
            if (slider != null) {
                slider.setValue(value);
            } else {
                fixedValue.setText(Integer.toString(value));
            }
        }
    }

    private final class DecimalField extends Field<Double> {
        private final Slider slider;

        DecimalField(SettingDef.Decimal def) {
            super(def, Kind.SLIDER);
            slider = Slider.ofDecimal(tr(def.labelKey()), def.min(), def.max(), def.step(), value(), this::set);
            finish(line(slider), slider);
        }

        @Override
        void show(Double value) {
            slider.setValue(value);
        }
    }

    private final class BoolField extends Field<Boolean> {
        private final Toggle toggle;

        BoolField(SettingDef.Bool def) {
            super(def, Kind.TOGGLE);
            toggle = new Toggle(tr(def.labelKey()), value(), this::set);
            finish(line(toggle), toggle);
        }

        @Override
        void show(Boolean value) {
            toggle.setValue(value);
        }
    }

    private final class EnumField<E extends java.lang.Enum<E>> extends Field<E> {
        private SegmentedControl<E> segmented;
        private Dropdown<E> dropdown;

        EnumField(SettingDef.Enum<E> def) {
            super(def, def.type().getEnumConstants().length <= 4 ? Kind.SEGMENTED : Kind.DROPDOWN);
            List<E> options = List.of(def.type().getEnumConstants());
            Function<E, String> labeler = option -> optionLabel(def, option);
            Function<E, String> optionTooltips = option -> {
                // An option this tool can't use says why.
                String reason = def.unavailable().get(option);
                if (reason != null) {
                    return tr(reason);
                }
                String key = optionTooltipKey(def.labelKey(), option);
                return services.translator().has(key) ? tr(key) : null;
            };
            Node chooser;
            if (kind == Kind.SEGMENTED) {
                segmented = new SegmentedControl<>(options, value(), labeler, this::set);
                segmented.setOptionTooltips(optionTooltips);
                segmented.setOptionEnabled(def::available);
                chooser = segmented;
            } else {
                dropdown = new Dropdown<>(options, value(), labeler, this::set);
                dropdown.setOptionTooltips(optionTooltips);
                chooser = dropdown;
            }
            finish(Column.of(heading(), chooser), chooser);
        }

        @Override
        void show(E value) {
            if (segmented != null) {
                segmented.setSelected(value);
            } else {
                dropdown.setSelected(value);
            }
        }
    }

    private final class BlockField extends Field<BlockDescriptor> {
        private final BlockChip chip;

        BlockField(SettingDef.Block def) {
            super(def, Kind.BLOCK);
            chip = new BlockChip(services.blocks(), value(), null);
            chip.setOnClick(() -> services.pickBlock(chip.bounds(), chip.block(), block -> {
                chip.setBlock(block);
                set(block);
            }));
            finish(Column.of(heading(), chip), chip);
        }

        @Override
        void show(BlockDescriptor value) {
            chip.setBlock(value);
        }
    }

    private final class BlockListField extends Field<List<BlockDescriptor>> {
        private final Column rows = new Column();
        private final Button add;
        private final SettingDef.BlockList listDef;

        BlockListField(SettingDef.BlockList def) {
            super(def, Kind.BLOCK_LIST);
            this.listDef = def;
            rows.setGap(2);
            add = new Button(tr("sculptory.form.add_block"), null);
            add.setOnClick(() -> services.pickBlock(add.bounds(), DEFAULT_BLOCK, block -> {
                List<BlockDescriptor> next = new ArrayList<>(value());
                next.add(block);
                set(next);
                show(value());
            }));
            show(value());
            finish(Column.of(heading(), rows, add), rows);
        }

        @Override
        void show(List<BlockDescriptor> value) {
            rows.clear();
            for (int i = 0; i < value.size(); i++) {
                int index = i;
                BlockChip chip = new BlockChip(services.blocks(), value.get(i), null);
                chip.setOnClick(() -> services.pickBlock(chip.bounds(), chip.block(), block -> {
                    List<BlockDescriptor> next = new ArrayList<>(value());
                    next.set(index, block);
                    set(next);
                    show(value());
                }));
                chip.setGrow(1);
                Button remove = new Button(REMOVE, () -> {
                    List<BlockDescriptor> next = new ArrayList<>(value());
                    next.remove(index);
                    set(next);
                    show(value());
                });
                rows.add(Row.of(chip, remove));
            }
            add.setEnabled(value.size() < listDef.maxEntries());
        }
    }

    private final class WeightedField extends Field<List<SettingDef.WeightedBlock>> {
        private final Column rows = new Column();
        private final Button add;
        private final SettingDef.WeightedBlocks weightedDef;

        WeightedField(SettingDef.WeightedBlocks def) {
            super(def, Kind.WEIGHTED_BLOCKS);
            this.weightedDef = def;
            rows.setGap(2);
            add = new Button(tr("sculptory.form.add_block"), null);
            add.setOnClick(() -> services.pickBlock(add.bounds(), DEFAULT_BLOCK, block -> {
                List<SettingDef.WeightedBlock> next = new ArrayList<>(value());
                next.add(new SettingDef.WeightedBlock(block, 1));
                set(next);
                show(value());
            }));
            show(value());
            finish(Column.of(heading(), rows, add), rows);
        }

        @Override
        void show(List<SettingDef.WeightedBlock> value) {
            rows.clear();
            for (int i = 0; i < value.size(); i++) {
                int index = i;
                SettingDef.WeightedBlock entry = value.get(i);
                BlockChip chip = new BlockChip(services.blocks(), entry.block(), null);
                chip.setOnClick(() -> services.pickBlock(chip.bounds(), chip.block(), block -> {
                    List<SettingDef.WeightedBlock> next = new ArrayList<>(value());
                    next.set(index, new SettingDef.WeightedBlock(block, next.get(index).weight()));
                    set(next);
                    show(value());
                }));
                chip.setGrow(1);
                TextInput weight = numberInput(Integer.toString(entry.weight()), 30, text -> {
                    Integer parsed = parseInt(text);
                    if (parsed != null && parsed >= 1 && parsed <= Pattern.Weighted.MAX_WEIGHT) {
                        List<SettingDef.WeightedBlock> next = new ArrayList<>(value());
                        next.set(index, new SettingDef.WeightedBlock(next.get(index).block(), parsed));
                        set(next);
                    }
                });
                weight.setTooltip(tr("sculptory.form.weight"));
                Button remove = new Button(REMOVE, () -> {
                    List<SettingDef.WeightedBlock> next = new ArrayList<>(value());
                    next.remove(index);
                    set(next);
                    show(value());
                });
                remove.setEnabled(value.size() > 1);
                ReorderHandle handle = new ReorderHandle(this, index);
                handle.setTooltip(tr("sculptory.form.reorder"));
                handle.setVisible(value.size() > 1);
                rows.add(Row.of(handle, chip, weight, remove));
            }
            add.setEnabled(value.size() < weightedDef.maxEntries());
        }

        /** The rows, in order (for the handles). */
        List<Node> rows() {
            return rows.children();
        }

        /** Moves entry {@code from} to before entry {@code before} (the list's size: to the end). */
        void move(int from, int before) {
            List<SettingDef.WeightedBlock> next = new ArrayList<>(value());
            if (from < 0 || from >= next.size() || before < 0 || before > next.size()) {
                return;
            }
            int to = before > from ? before - 1 : before;
            if (to == from) {
                return;
            }
            next.add(to, next.remove(from));
            set(next);
            show(value());
        }
    }

    /**
     * The ≡ at the left of a mix row: drag it up or down to move the block (the order is the Gradient's and
     * Steepness' order, first to last). While dragging, a line shows where the block goes; it moves on release.
     */
    static final class ReorderHandle extends Node {
        private static final String SYMBOL = "≡";
        private final WeightedField field;
        private final int index;
        private boolean dragging;
        /** The row the block would go before (the row count: after the last). */
        private int target;

        ReorderHandle(WeightedField field, int index) {
            this.field = field;
            this.index = index;
        }

        /** Whether a drag is under way. */
        boolean dragging() {
            return dragging;
        }

        /** Where the block would go now: before this row (the row count: to the end). */
        int target() {
            return target;
        }

        @Override
        protected dev.sculptory.fabric.client.editor.ui.Size measureContent(UiContext ctx, int maxWidth) {
            return new dev.sculptory.fabric.client.editor.ui.Size(ctx.text().width(SYMBOL) + 4,
                    ctx.theme().controlHeight);
        }

        @Override
        public void render(dev.sculptory.fabric.client.editor.ui.UiGraphics g, UiContext ctx) {
            var theme = ctx.theme();
            boolean hot = dragging || isEffectivelyEnabled() && ctx.isHovered(this);
            int y = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
            g.text(SYMBOL, bounds.x() + 2, y, hot ? theme.text : theme.textDim, theme.textShadow);
            if (!dragging) {
                return;
            }
            // A line in the gap where the block would go, across the whole list.
            List<Node> rows = field.rows();
            if (rows.isEmpty()) {
                return;
            }
            Rect first = rows.get(0).bounds();
            int lineY = target < rows.size() ? rows.get(target).bounds().y() - 1
                    : rows.get(rows.size() - 1).bounds().bottom();
            g.fill(first.x(), lineY, first.width(), 1, theme.accent);
        }

        @Override
        public boolean mouseDown(UiContext ctx, double x, double y, int button) {
            if (button != org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT || !isEffectivelyEnabled()) {
                return false;
            }
            dragging = true;
            target = index;
            return true;
        }

        @Override
        public void mouseDrag(UiContext ctx, double x, double y, int button) {
            if (dragging) {
                target = targetAt(y);
            }
        }

        @Override
        public void mouseUp(UiContext ctx, double x, double y, int button) {
            if (!dragging) {
                return;
            }
            dragging = false;
            field.move(index, targetAt(y));
        }

        /** The row a drop at {@code y} goes before: the first whose middle lies below it (the row count past them all). */
        int targetAt(double y) {
            List<Node> rows = field.rows();
            for (int i = 0; i < rows.size(); i++) {
                Rect row = rows.get(i).bounds();
                if (y < row.y() + row.height() / 2.0) {
                    return i;
                }
            }
            return rows.size();
        }
    }

    private final class RangeField extends Field<SettingDef.IntSpan> {
        private final Slider low;
        private final Slider high;

        RangeField(SettingDef.IntRange def) {
            super(def, Kind.INT_RANGE);
            int min = def.min();
            int max = Math.max(def.max(), def.min() + 1);
            SettingDef.IntSpan span = value();
            low = Slider.ofInt(tr("sculptory.form.min"), min, max, span.min(), v -> {
                SettingDef.IntSpan current = value();
                set(new SettingDef.IntSpan(Math.min(v, def.max()), Math.max(Math.min(v, def.max()), current.max())));
                show(value());
            });
            high = Slider.ofInt(tr("sculptory.form.max"), min, max, span.max(), v -> {
                SettingDef.IntSpan current = value();
                int top = Math.min(v, def.max());
                set(new SettingDef.IntSpan(Math.min(current.min(), top), top));
                show(value());
            });
            low.setGrow(1);
            high.setGrow(1);
            finish(Column.of(heading(), Row.of(low, high)), low);
        }

        @Override
        void show(SettingDef.IntSpan value) {
            low.setValue(value.min());
            high.setValue(value.max());
        }
    }


    /** A mask's rule list: what it lets through, and a button opening the rule editor. */
    private final class MaskRulesField extends Field<SettingDef.MaskValue> {
        private final SettingDef.MaskRules rulesDef;
        private final Label summary = Label.of("");
        private final Button edit;

        MaskRulesField(SettingDef.MaskRules def) {
            super(def, Kind.MASK_RULES);
            this.rulesDef = def;
            summary.setWrap(true);
            edit = new Button(tr("sculptory.setting.brush.mask.rules.edit"), null);
            edit.setOnClick(() -> services.editMask(edit.bounds(), rulesDef.shown(values, services.states()),
                    mask -> set(SettingDef.MaskValue.of(mask))));
            show(value());
            finish(Column.of(heading(), summary, edit), edit);
        }

        /** The summary also follows the settings the unset value falls back on (a preset loaded). */
        @Override
        void showIfChanged(SettingsValues before) {
            show(value());
        }

        @Override
        void show(SettingDef.MaskValue value) {
            EditMask mask = rulesDef.shown(values, services.states());
            String rules = MaskSummary.rules(mask.entries(), services.blocks(), services.translator());
            if (mask.entries().isEmpty()) {
                summary.setText(tr("sculptory.setting.brush.mask.rules.none"));
            } else {
                summary.setText(mask.invertAll() ? tr("sculptory.setting.brush.mask.rules.inverted", rules) : rules);
            }
        }
    }

    private final class SeedField extends Field<Long> {
        private final TextInput input;

        SeedField(SettingDef.Seed def) {
            super(def, Kind.SEED);
            input = new TextInput(Long.toString(value()), text -> {
                try {
                    set(Long.parseLong(text.trim()));
                } catch (NumberFormatException malformed) {
                    // keep typing
                }
            });
            input.setMaxLength(20);
            input.setGrow(1);
            // The button says "Random" unless the setting names it (a pattern's "Re-roll").
            String buttonKey = def.labelKey() + BUTTON_SUFFIX;
            String buttonText = services.translator().has(buttonKey) ? tr(buttonKey) : tr("sculptory.form.random");
            Button randomize = new Button(buttonText, () -> {
                long seed = random.nextLong();
                set(seed);
                show(seed);
            });
            finish(Column.of(heading(), Row.of(input, randomize)), input);
        }

        @Override
        void show(Long value) {
            input.setText(Long.toString(value));
        }
    }

    private final class AssetField extends Field<List<SettingDef.AssetWeight>> {
        private final Column rows = new Column();
        private final Button add;
        private final SettingDef.AssetMix mixDef;

        AssetField(SettingDef.AssetMix def) {
            super(def, Kind.ASSET_MIX);
            this.mixDef = def;
            rows.setGap(2);
            add = new Button(tr("sculptory.form.add_asset"), () -> {
                List<SettingDef.AssetWeight> next = new ArrayList<>(value());
                next.add(new SettingDef.AssetWeight("asset_" + (next.size() + 1), 1));
                set(next);
                show(value());
            });
            show(value());
            finish(Column.of(heading(), rows, add), rows);
        }

        @Override
        void show(List<SettingDef.AssetWeight> value) {
            rows.clear();
            for (int i = 0; i < value.size(); i++) {
                int index = i;
                SettingDef.AssetWeight entry = value.get(i);
                TextInput asset = new TextInput(entry.asset(), text -> {
                    try {
                        List<SettingDef.AssetWeight> next = new ArrayList<>(value());
                        next.set(index, new SettingDef.AssetWeight(text.trim(), next.get(index).weight()));
                        set(next);
                    } catch (IllegalArgumentException invalid) {
                        // keep typing
                    }
                });
                asset.setGrow(1);
                TextInput weight = numberInput(Integer.toString(entry.weight()), 30, text -> {
                    Integer parsed = parseInt(text);
                    if (parsed != null && parsed >= 1 && parsed <= Pattern.Weighted.MAX_WEIGHT) {
                        List<SettingDef.AssetWeight> next = new ArrayList<>(value());
                        next.set(index, new SettingDef.AssetWeight(next.get(index).asset(), parsed));
                        set(next);
                    }
                });
                Button remove = new Button(REMOVE, () -> {
                    List<SettingDef.AssetWeight> next = new ArrayList<>(value());
                    next.remove(index);
                    set(next);
                    show(value());
                });
                rows.add(Row.of(asset, weight, remove));
            }
            add.setEnabled(value.size() < mixDef.maxEntries());
        }
    }
}
