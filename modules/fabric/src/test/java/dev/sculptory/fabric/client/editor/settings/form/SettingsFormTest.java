package dev.sculptory.fabric.client.editor.settings.form;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.blocks.BlockChip;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm.Kind;
import dev.sculptory.fabric.client.editor.tools.brush.BrushSettings;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.CollapsibleSection;
import dev.sculptory.fabric.client.editor.ui.widget.Dropdown;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.ui.widget.Toggle;
import dev.sculptory.protocol.v2.Limits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class SettingsFormTest {
    enum Shape { SPHERE, CYLINDER, CUBE }

    enum Mode { A, B, C, D, E, F }

    private static final BlockDescriptor STONE = BlockDescriptor.of(new NamespacedId("minecraft:stone"));
    private static final BlockDescriptor DIRT = BlockDescriptor.of(new NamespacedId("minecraft:dirt"));

    private static final SettingDef.Int RADIUS = new SettingDef.Int("radius", "label.radius", 8, 1, 32,
            Limits::maxBrushRadius, SettingDef.ALWAYS);
    private static final SettingDef.Decimal STRENGTH = new SettingDef.Decimal("strength", "label.strength", 0.5, 0, 1, 0.05);
    private static final SettingDef.Enum<Shape> SHAPE = new SettingDef.Enum<>("shape", "label.shape", Shape.class, Shape.SPHERE);
    private static final SettingDef.Enum<Mode> MODE = new SettingDef.Enum<>("mode", "label.mode", Mode.class, Mode.A);
    private static final SettingDef.Bool ADVANCED = new SettingDef.Bool("advanced", "label.advanced", false);
    private static final SettingDef.Int FALLOFF = new SettingDef.Int("falloff", "label.falloff", 3, 0, 10, null,
            values -> values.get(SettingsFormTest.ADVANCED));
    private static final SettingDef.Block BLOCK = new SettingDef.Block("block", "label.block", STONE);
    private static final SettingDef.BlockList MASK = new SettingDef.BlockList("mask", "label.mask", List.of(), 8);
    private static final SettingDef.WeightedBlocks PALETTE = new SettingDef.WeightedBlocks("palette", "label.palette",
            List.of(new SettingDef.WeightedBlock(STONE, 1)), 8);
    private static final SettingDef.IntRange HEIGHT = new SettingDef.IntRange("height", "label.height",
            new SettingDef.IntSpan(0, 10), -64, 320);
    private static final SettingDef.Seed SEED = new SettingDef.Seed("seed", "label.seed", 1234L);
    private static final SettingDef.AssetMix ASSETS = new SettingDef.AssetMix("assets", "label.assets", List.of(), 4);

    private static final SettingsSchema SCHEMA = new SettingsSchema(List.of(
            new Section("", List.of(RADIUS, STRENGTH, SHAPE, MODE, ADVANCED, FALLOFF)),
            new Section("section.blocks", List.of(BLOCK, MASK, PALETTE)),
            new Section("section.more", List.of(HEIGHT, SEED, ASSETS), true)));

    private static final Limits LIMITS = new Limits(2_097_152L, 2_097_152L, 16, 20, 32L << 20, 2);

    /** Every character is 6 px wide, lines 9 px tall. */
    private static final TextMeasure TEXT = new TextMeasure() {
        @Override
        public int width(String text) {
            return text.length() * 6;
        }

        @Override
        public int lineHeight() {
            return 9;
        }

        @Override
        public String trimToWidth(String text, int maxWidth) {
            return text.substring(0, Math.max(0, Math.min(text.length(), maxWidth / 6)));
        }
    };

    private final List<SettingsValues> changes = new ArrayList<>();
    private final List<BlockDescriptor> pickerRequests = new ArrayList<>();
    private Consumer<BlockDescriptor> pickerCallback;
    private final UiContext ui = new UiContext(TEXT, Theme.DARK);

    private final SettingsForm.Services services = new SettingsForm.Services() {
        @Override
        public Translator translator() {
            return Translator.KEYS;
        }

        @Override
        public BlockCatalog blocks() {
            return BlockCatalog.EMPTY;
        }

        @Override
        public void pickBlock(Rect anchor, BlockDescriptor current, Consumer<BlockDescriptor> onPick) {
            pickerRequests.add(current);
            pickerCallback = onPick;
        }

        @Override
        public Limits limits() {
            return LIMITS;
        }
    };

    private SettingsForm form() {
        return SettingsForm.build(SettingsValues.defaults(SCHEMA), changes::add, services);
    }

    private SettingsValues last() {
        return changes.get(changes.size() - 1);
    }

    @Test
    void settingsWithATooltipTranslationShowItOnTheirControl() {
        Translator withTooltip = new Translator() {
            @Override
            public String translate(String key, Object... args) {
                return key.equals("label.advanced.tooltip") ? "Shows the advanced settings" : key;
            }

            @Override
            public boolean has(String key) {
                return key.equals("label.advanced.tooltip");
            }
        };
        SettingsForm.Services tooltips = new SettingsForm.Services() {
            @Override
            public Translator translator() {
                return withTooltip;
            }

            @Override
            public BlockCatalog blocks() {
                return BlockCatalog.EMPTY;
            }

            @Override
            public void pickBlock(Rect anchor, BlockDescriptor current, Consumer<BlockDescriptor> onPick) {}

            @Override
            public Limits limits() {
                return LIMITS;
            }
        };
        SettingsForm form = SettingsForm.build(SettingsValues.defaults(SCHEMA), changes::add, tooltips);
        assertEquals("Shows the advanced settings", form.control("advanced").orElseThrow().tooltip());
        assertEquals(null, form.control("radius").orElseThrow().tooltip(), "no translation, no tooltip");
    }

    @Test
    void eachSettingTypeGetsItsWidget() {
        SettingsForm form = form();
        assertEquals(Kind.SLIDER, form.kind("radius").orElseThrow());
        assertInstanceOf(Slider.class, form.control("radius").orElseThrow());
        assertEquals(Kind.SLIDER, form.kind("strength").orElseThrow());
        assertInstanceOf(Slider.class, form.control("strength").orElseThrow());
        assertEquals(Kind.SEGMENTED, form.kind("shape").orElseThrow(), "three choices: all visible at once");
        assertInstanceOf(SegmentedControl.class, form.control("shape").orElseThrow());
        assertEquals(Kind.DROPDOWN, form.kind("mode").orElseThrow(), "six choices: a dropdown");
        assertInstanceOf(Dropdown.class, form.control("mode").orElseThrow());
        assertEquals(Kind.TOGGLE, form.kind("advanced").orElseThrow());
        assertInstanceOf(Toggle.class, form.control("advanced").orElseThrow());
        assertEquals(Kind.BLOCK, form.kind("block").orElseThrow());
        assertInstanceOf(BlockChip.class, form.control("block").orElseThrow());
        assertEquals(Kind.BLOCK_LIST, form.kind("mask").orElseThrow());
        assertEquals(Kind.WEIGHTED_BLOCKS, form.kind("palette").orElseThrow());
        assertEquals(Kind.INT_RANGE, form.kind("height").orElseThrow());
        assertInstanceOf(Slider.class, form.control("height").orElseThrow());
        assertEquals(Kind.SEED, form.kind("seed").orElseThrow());
        assertInstanceOf(TextInput.class, form.control("seed").orElseThrow());
        assertEquals(Kind.ASSET_MIX, form.kind("assets").orElseThrow());
        assertTrue(form.kind("nope").isEmpty());
    }

    @Test
    void titledSectionsBecomeCollapsible() {
        List<Node> sections = form().node().children();
        assertEquals(3, sections.size());
        assertInstanceOf(Column.class, sections.get(0), "an untitled section is a plain column");
        CollapsibleSection blocks = assertInstanceOf(CollapsibleSection.class, sections.get(1));
        CollapsibleSection more = assertInstanceOf(CollapsibleSection.class, sections.get(2));
        assertTrue(blocks.isExpanded());
        assertFalse(more.isExpanded(), "collapsedByDefault is honoured");
    }

    @Test
    void aTitledSectionHidesWhileNoneOfItsSettingsApply() {
        SettingsSchema schema = new SettingsSchema(List.of(
                new Section("", List.of(ADVANCED)),
                new Section("section.advanced", List.of(FALLOFF))));
        SettingsForm form = SettingsForm.build(SettingsValues.defaults(schema), changes::add, services);
        CollapsibleSection advanced = assertInstanceOf(CollapsibleSection.class, form.node().children().get(1));
        assertFalse(advanced.isVisible(), "its only setting is hidden");
        ((Toggle) form.control("advanced").orElseThrow()).toggle();
        assertTrue(advanced.isVisible());
        assertTrue(form.field("falloff").orElseThrow().isVisible());
    }

    @Test
    void theServerLimitCapsTheSlider() {
        Slider radius = (Slider) form().control("radius").orElseThrow();
        assertEquals(1, radius.min());
        assertEquals(16, radius.max(), "the schema says 32, the server allows 16");
    }

    @Test
    void settingsHideAndShowWithTheirCondition() {
        SettingsForm form = form();
        Node falloff = form.field("falloff").orElseThrow();
        assertFalse(falloff.isVisible());
        ((Toggle) form.control("advanced").orElseThrow()).toggle();
        assertTrue(last().get(ADVANCED));
        assertTrue(falloff.isVisible());
    }

    @Test
    void editingAControlProducesNewValues() {
        SettingsForm form = form();
        Slider radius = (Slider) form.control("radius").orElseThrow();
        radius.keyPressed(ui, GLFW.GLFW_KEY_RIGHT, 0, 0);
        assertEquals(9, last().get(RADIUS));
        @SuppressWarnings("unchecked")
        SegmentedControl<Shape> shape = (SegmentedControl<Shape>) form.control("shape").orElseThrow();
        shape.choose(Shape.CUBE);
        assertEquals(Shape.CUBE, last().get(SHAPE));
        assertEquals(9, last().get(RADIUS), "earlier edits are kept");
        assertEquals(last(), form.values());
    }

    @Test
    void refreshShowsOutsideChangesWithoutCallingBack() {
        SettingsForm form = form();
        form.refresh(form.values().with(RADIUS, 12).with(SHAPE, Shape.CYLINDER));
        assertEquals(12, ((Slider) form.control("radius").orElseThrow()).intValue());
        @SuppressWarnings("unchecked")
        SegmentedControl<Shape> shape = (SegmentedControl<Shape>) form.control("shape").orElseThrow();
        assertEquals(Shape.CYLINDER, shape.selected());
        assertEquals(List.of(), changes);
    }

    @Test
    void validationProblemsShowUnderTheSetting() {
        SettingsForm form = form();
        assertEquals("", form.message("radius"));
        form.refresh(form.values().with(RADIUS, 30));
        assertEquals("sculptory.setting.server_limit[16]", form.message("radius"));
        assertEquals("sculptory.setting.empty", form.message("assets"), "an empty asset mix is a warning");
    }

    @Test
    void aBlockChipOpensThePickerAndKeepsTheChoice() {
        SettingsForm form = form();
        BlockChip chip = (BlockChip) form.control("block").orElseThrow();
        chip.click();
        assertEquals(List.of(STONE), pickerRequests);
        pickerCallback.accept(DIRT);
        assertEquals(DIRT, last().get(BLOCK));
        assertEquals(DIRT, chip.block());
    }

    @Test
    void weightedRowsAddAndRemoveBlocks() {
        SettingsForm form = form();
        Column rows = (Column) form.control("palette").orElseThrow();
        assertEquals(1, rows.children().size());
        Node body = form.field("palette").orElseThrow().children().get(0);
        Button add = (Button) body.children().get(2);
        Button removeOnly = (Button) rows.children().get(0).children().get(3);
        assertFalse(removeOnly.isEnabled(), "a palette can't lose its last block");

        add.click();
        pickerCallback.accept(DIRT);
        assertEquals(List.of(new SettingDef.WeightedBlock(STONE, 1), new SettingDef.WeightedBlock(DIRT, 1)),
                last().get(PALETTE));
        assertEquals(2, rows.children().size());

        Button removeFirst = (Button) rows.children().get(0).children().get(3);
        assertTrue(removeFirst.isEnabled());
        removeFirst.click();
        assertEquals(List.of(new SettingDef.WeightedBlock(DIRT, 1)), last().get(PALETTE));
    }

    /**
     * A mix's rows move by dragging their ≡ handle (the order is a Gradient's and Steepness' order): down past the last
     * row, up to the top, and a drop where it started changes nothing; a one-block mix shows no handle.
     */
    @Test
    void weightedRowsReorderByDraggingTheirHandle() {
        BlockDescriptor sand = BlockDescriptor.of(new NamespacedId("minecraft:sand"));
        SettingsForm form = SettingsForm.build(SettingsValues.defaults(SCHEMA).with(PALETTE, List.of(
                new SettingDef.WeightedBlock(STONE, 3), new SettingDef.WeightedBlock(DIRT, 2),
                new SettingDef.WeightedBlock(sand, 1))), changes::add, services);
        Node root = form.node();
        root.measure(ui, 220);
        root.layout(ui, new Rect(0, 0, 220, 900));
        Column rows = (Column) form.control("palette").orElseThrow();
        SettingsForm.ReorderHandle first = (SettingsForm.ReorderHandle) rows.children().get(0).children().get(0);
        assertTrue(first.isVisible());
        Rect last = rows.children().get(2).bounds();
        int x = first.bounds().x() + 1;
        assertTrue(first.mouseDown(ui, x, first.bounds().y() + 1, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        first.mouseDrag(ui, x, last.bottom() + 1, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertTrue(first.dragging());
        assertEquals(3, first.target(), "past the last row");
        RecordingGraphics g = new RecordingGraphics();
        first.render(g, ui);
        first.mouseUp(ui, x, last.bottom() + 1, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertEquals(List.of(new SettingDef.WeightedBlock(DIRT, 2), new SettingDef.WeightedBlock(sand, 1),
                new SettingDef.WeightedBlock(STONE, 3)), last().get(PALETTE), "stone moved to the end, weights kept");

        // The rows were rebuilt: sand (now second) goes to the top.
        root.measure(ui, 220);
        root.layout(ui, new Rect(0, 0, 220, 900));
        SettingsForm.ReorderHandle second = (SettingsForm.ReorderHandle) rows.children().get(1).children().get(0);
        int top = rows.children().get(0).bounds().y();
        second.mouseDown(ui, x, second.bounds().y() + 1, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        second.mouseUp(ui, x, top + 1, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertEquals(List.of(new SettingDef.WeightedBlock(sand, 1), new SettingDef.WeightedBlock(DIRT, 2),
                new SettingDef.WeightedBlock(STONE, 3)), last().get(PALETTE));

        // A drop on its own row changes nothing.
        int before = changes.size();
        root.measure(ui, 220);
        root.layout(ui, new Rect(0, 0, 220, 900));
        SettingsForm.ReorderHandle still = (SettingsForm.ReorderHandle) rows.children().get(1).children().get(0);
        still.mouseDown(ui, x, still.bounds().y() + 1, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        still.mouseUp(ui, x, still.bounds().y() + 2, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertEquals(before, changes.size(), "dropped where it was");

        SettingsForm single = form();
        Column one = (Column) single.control("palette").orElseThrow();
        assertFalse(one.children().get(0).children().get(0).isVisible(), "one block: nothing to reorder");
    }

    /**
     * An enum option a tool can't use is greyed out and says why: a click on it changes nothing, the arrows step past it,
     * its tooltip is the reason, and a value holding it doesn't validate.
     */
    @Test
    void unavailableOptionsAreGreyedOutWithTheirReason() {
        SettingDef.Enum<Shape> limited = new SettingDef.Enum<>("shape", "label.shape", Shape.class, Shape.SPHERE,
                SettingDef.ALWAYS, Map.of(Shape.CYLINDER, "reason.no_cylinder"));
        SettingsSchema schema = new SettingsSchema(List.of(new Section("", List.of(limited))));
        SettingsForm form = SettingsForm.build(SettingsValues.defaults(schema), changes::add, services);
        @SuppressWarnings("unchecked")
        SegmentedControl<Shape> control = (SegmentedControl<Shape>) form.control("shape").orElseThrow();
        assertFalse(control.isOptionEnabled(Shape.CYLINDER));
        assertTrue(control.isOptionEnabled(Shape.CUBE));
        control.choose(Shape.CYLINDER);
        assertEquals(Shape.SPHERE, control.selected());
        assertTrue(changes.isEmpty(), "choosing it did nothing");
        control.keyPressed(ui, GLFW.GLFW_KEY_RIGHT, 0, 0);
        assertEquals(Shape.CUBE, control.selected(), "the arrow steps past it");
        assertEquals(Shape.CUBE, last().get(limited));
        Node root = form.node();
        root.measure(ui, 300);
        root.layout(ui, new Rect(0, 0, 300, 200));
        Rect segment = control.bounds();
        control.mouseMove(ui, segment.x() + segment.width() / 2.0, segment.y() + 1);
        assertEquals("reason.no_cylinder", control.tooltip(), "the middle segment's tooltip is the reason");
        assertFalse(limited.validate(Shape.CYLINDER, null).isValid());
        assertTrue(limited.validate(Shape.CUBE, null).isValid());
        assertTrue(limited.available(Shape.SPHERE));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new SettingDef.Enum<>("shape",
                "label.shape", Shape.class, Shape.SPHERE, SettingDef.ALWAYS, Map.of(Shape.SPHERE, "reason")),
                "the default must be available");
    }

    /** A seed's button says "Random" unless the setting names it (a pattern's "Re-roll"). */
    @Test
    void aSeedsButtonCanBeNamed() {
        Translator named = new Translator() {
            @Override
            public String translate(String key, Object... args) {
                return key.equals("label.seed.button") ? "Re-roll" : key;
            }

            @Override
            public boolean has(String key) {
                return key.equals("label.seed.button");
            }
        };
        SettingsForm form = SettingsForm.build(SettingsValues.defaults(SCHEMA), changes::add, servicesWith(named));
        Node row = form.field("seed").orElseThrow().children().get(0).children().get(1);
        Button button = (Button) row.children().get(1);
        assertEquals("Re-roll", button.text());
        SettingsForm plain = form();
        Button random = (Button) plain.field("seed").orElseThrow().children().get(0).children().get(1).children().get(1);
        assertEquals("sculptory.form.random", random.text());
    }

    @Test
    void aFullPalettePaintMixShowsEveryRowAndScrollsInTheToolSettingsPane() {
        BrushSettings brush = BrushSettings.forTool(BrushTool.PALETTE);
        List<SettingDef.WeightedBlock> mix = new ArrayList<>();
        for (int i = 0; i < BrushSettings.MAX_PALETTE_ENTRIES; i++) {
            mix.add(new SettingDef.WeightedBlock(BlockDescriptor.of(new NamespacedId("minecraft:block_" + i)), i + 1));
        }
        assertEquals(64, mix.size());
        SettingsForm form = SettingsForm.build(SettingsValues.defaults(brush.schema()).with(brush.palette, mix),
                changes::add, services);
        assertEquals("", form.message("palette"), "64 blocks are valid");
        Column rows = (Column) form.control("palette").orElseThrow();
        assertEquals(64, rows.children().size());
        Node body = form.field("palette").orElseThrow().children().get(0);
        Button add = (Button) body.children().get(2);
        assertFalse(add.isEnabled(), "the mix is full at 64");

        // The Tool Settings window wraps its form in a ScrollPane: the last row scrolls into view.
        ScrollPane pane = new ScrollPane(form.node());
        pane.measure(ui, 220);
        pane.layout(ui, new Rect(0, 0, 220, 300));
        assertTrue(pane.scroll().isScrollable(), "64 rows are taller than the window");
        pane.scroll().setOffset(pane.scroll().maxOffset());
        pane.layout(ui, new Rect(0, 0, 220, 300));
        Rect last = rows.children().get(63).bounds();
        assertTrue(last.y() >= 0 && last.y() + last.height() <= 300, "the 64th row in view: " + last);

        // One removed: adding is possible again, up to 64.
        ((Button) rows.children().get(63).children().get(3)).click();
        assertEquals(63, last().get(brush.palette).size());
        assertTrue(add.isEnabled());
        SettingsForm over = SettingsForm.build(SettingsValues.defaults(brush.schema()).with(brush.palette,
                concat(mix, new SettingDef.WeightedBlock(STONE, 1))), changes::add, services);
        assertFalse(over.message("palette").isEmpty(), "65 blocks are too many");
    }

    private static <T> List<T> concat(List<T> list, T more) {
        List<T> all = new ArrayList<>(list);
        all.add(more);
        return all;
    }

    @Test
    void theFormLaysOutWithoutAGame() {
        SettingsForm form = form();
        form.node().measure(ui, 200);
        form.node().layout(ui, new Rect(0, 0, 200, 2000));
        Node radius = form.control("radius").orElseThrow();
        assertTrue(radius.bounds().width() > 40, "the slider takes the row's spare width");
        assertTrue(form.control("strength").orElseThrow().bounds().y() > radius.bounds().y());
    }

    @Test
    void anEmptySchemaBuildsAnEmptyForm() {
        SettingsForm form = SettingsForm.build(SettingsValues.defaults(SettingsSchema.EMPTY), changes::add, services);
        assertEquals(List.of(), form.node().children());
    }

    // ---- Numbers, reset, sections, reveal, option tooltips ----

    private static List<Node> subtree(Node node) {
        List<Node> all = new ArrayList<>();
        all.add(node);
        for (Node child : node.children()) {
            all.addAll(subtree(child));
        }
        return all;
    }

    @Test
    void aNumberIsItsSliderAloneWithTheValueTypedInPlace() {
        SettingsForm form = form();
        for (String key : List.of("radius", "strength")) {
            List<Node> row = subtree(form.field(key).orElseThrow());
            assertTrue(row.stream().noneMatch(TextInput.class::isInstance), key + " has no separate number field");
        }
        Slider radius = (Slider) form.control("radius").orElseThrow();
        radius.startEditing(ui);
        radius.editor().orElseThrow().setText("12");
        radius.finishEditing(ui, true);
        assertEquals(12, last().get(RADIUS), "a typed value goes through the normal change");
        radius.startEditing(ui);
        radius.editor().orElseThrow().setText("99");
        radius.finishEditing(ui, true);
        assertEquals(16, last().get(RADIUS), "clamped to what the server allows");
    }

    @Test
    void theResetButtonShowsWhileASettingDiffersFromItsDefault() {
        SettingsForm form = form();
        Button reset = form.resetButton("radius").orElseThrow();
        assertFalse(reset.isVisible(), "at its default: no reset");
        ((Slider) form.control("radius").orElseThrow()).keyPressed(ui, GLFW.GLFW_KEY_RIGHT, 0, 0);
        assertTrue(reset.isVisible());
        assertEquals(SettingsForm.RESET, reset.text());
        assertEquals("sculptory.form.reset.tooltip[8]", reset.tooltip(), "the tooltip names the default");

        reset.click();
        assertEquals(8, last().get(RADIUS), "back to the default through onChange");
        assertEquals(8, ((Slider) form.control("radius").orElseThrow()).intValue(), "and shown");
        assertFalse(reset.isVisible());
    }

    @Test
    void aChangeFromElsewhereShowsTheResetButtonToo() {
        SettingsForm form = form();
        form.refresh(form.values().with(SHAPE, Shape.CUBE).with(MASK, List.of(DIRT)));
        assertTrue(form.resetButton("shape").orElseThrow().isVisible());
        assertTrue(form.resetButton("mask").orElseThrow().isVisible());
        assertFalse(form.resetButton("radius").orElseThrow().isVisible());

        form.resetButton("mask").orElseThrow().click();
        assertEquals(List.of(), last().get(MASK));
        assertEquals(0, ((Column) form.control("mask").orElseThrow()).children().size(), "the rows follow");
    }

    @Test
    void theResetTooltipDescribesEachKindOfDefault() {
        SettingsForm form = form();
        assertEquals("sculptory.form.reset.tooltip[0.50]", form.resetButton("strength").orElseThrow().tooltip());
        assertEquals("sculptory.form.reset.tooltip[Sphere]", form.resetButton("shape").orElseThrow().tooltip());
        assertEquals("sculptory.form.reset.tooltip[sculptory.form.off]",
                form.resetButton("advanced").orElseThrow().tooltip());
        assertEquals("sculptory.form.reset.tooltip[minecraft:stone]",
                form.resetButton("block").orElseThrow().tooltip());
        assertEquals("sculptory.form.reset.tooltip[sculptory.form.none]",
                form.resetButton("mask").orElseThrow().tooltip());
        assertEquals("sculptory.form.reset.tooltip[minecraft:stone (1)]",
                form.resetButton("palette").orElseThrow().tooltip());
        assertEquals("sculptory.form.reset.tooltip[sculptory.form.range[0,10]]",
                form.resetButton("height").orElseThrow().tooltip());
        assertEquals("sculptory.form.reset.tooltip[1234]", form.resetButton("seed").orElseThrow().tooltip());
    }

    @Test
    void aHiddenResetButtonLeavesNoGapAndAShownOneSitsOverTheSlidersEnd() {
        SettingsForm form = form();
        layout(form, 200);
        Slider radius = (Slider) form.control("radius").orElseThrow();
        Toggle advanced = (Toggle) form.control("advanced").orElseThrow();
        assertEquals(200, radius.bounds().right(), "the slider reaches the right edge: nothing kept for a hidden reset");
        assertEquals(200, advanced.bounds().right(), "so does the toggle");
        Rect sliderBefore = radius.bounds();

        radius.keyPressed(ui, GLFW.GLFW_KEY_RIGHT, 0, 0);
        advanced.toggle();
        layout(form, 200);
        Button reset = form.resetButton("radius").orElseThrow();
        assertTrue(reset.isVisible());
        assertEquals(sliderBefore, radius.bounds(), "the slider keeps its length, so a drag maps the same");
        assertEquals(new Rect(200 - Theme.DARK.resetButtonWidth, sliderBefore.y(), Theme.DARK.resetButtonWidth,
                sliderBefore.height()), reset.bounds(), "over the slider's right end");
        assertSame(reset, form.node().hitTest(reset.bounds().x() + 3, reset.bounds().y() + 3), "and takes the click");
        RecordingGraphics g = new RecordingGraphics();
        form.node().render(g, ui);
        Rect value = g.textAnchor("9");
        assertTrue(value.x() + TEXT.width("9") <= reset.bounds().x(), "the value moves left of it: " + value);

        Button toggleReset = form.resetButton("advanced").orElseThrow();
        assertEquals(200, toggleReset.bounds().right());
        assertTrue(advanced.bounds().right() <= toggleReset.bounds().x(), "beside the toggle: " + advanced.bounds());
    }

    @Test
    void theResetButtonDrawsItsArrowAsAPixelIconReadableAtSmallSizes() {
        SettingsForm form = form();
        ((Slider) form.control("radius").orElseThrow()).keyPressed(ui, GLFW.GLFW_KEY_RIGHT, 0, 0);
        layout(form, 200);
        Button reset = form.resetButton("radius").orElseThrow();
        RecordingGraphics g = new RecordingGraphics();
        reset.render(g, ui);
        assertEquals(List.of(), g.drawnTexts(), "not the font's thin glyph");
        List<Rect> pixels = g.filledRects();
        int left = pixels.stream().mapToInt(Rect::x).min().orElseThrow();
        int right = pixels.stream().mapToInt(Rect::right).max().orElseThrow();
        int top = pixels.stream().mapToInt(Rect::y).min().orElseThrow();
        int bottom = pixels.stream().mapToInt(Rect::bottom).max().orElseThrow();
        assertEquals(new Rect(left, top, 9, 9), Rect.ofEdges(left, top, right, bottom), "a 9x9 icon");
        assertTrue(reset.bounds().contains(left, top) && reset.bounds().contains(right - 1, bottom - 1));
    }

    @Test
    void namesTogglesAndOptionsThatDontFitWrapInsteadOfBeingCutShort() {
        SegmentedControl<String> options = new SegmentedControl<>(List.of("Everything", "Only existing blocks",
                "Only air"), "Everything", option -> option, null);
        assertEquals(new Size(160, 3 * Theme.DARK.controlHeight), options.measure(ui, 160),
                "72 + 132 + 60 units: one option a line");
        assertEquals(2, options.lineCount(ui, 200), "the last two share a line where they fit");
        options.layout(ui, new Rect(0, 0, 200, 2 * Theme.DARK.controlHeight));
        RecordingGraphics g = new RecordingGraphics();
        options.render(g, ui);
        assertEquals(List.of("Everything", "Only existing blocks", "Only air"), g.drawnTexts(), "whole");
        assertTrue(options.mouseDown(ui, 20, Theme.DARK.controlHeight + 4, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        assertEquals("Only existing blocks", options.selected(), "the second line takes clicks");
        assertEquals(new Size(3 * 132, Theme.DARK.controlHeight), options.measure(ui, 400), "one line where it fits");

        Toggle toggle = new Toggle("Include air (clears blocks where the clipboard has air)", false, null);
        Size size = toggle.measure(ui, 160);
        assertEquals(160, size.width());
        assertTrue(size.height() > Theme.DARK.controlHeight, "two lines or more: " + size);
        toggle.layout(ui, new Rect(0, 0, 160, size.height()));
        g = new RecordingGraphics();
        toggle.render(g, ui);
        assertEquals("Include air (clears blocks where the clipboard has air)", String.join(" ", g.drawnTexts()));

        SettingsForm form = form();
        Node heading = form.field("mask").orElseThrow().children().get(0).children().get(0);
        assertEquals(2 * TEXT.lineHeight() + Theme.DARK.lineSpacing, heading.measure(ui, TEXT.width("label.mask") - 6)
                .height(), "a setting's name above its control wraps too");
    }

    @Test
    void wrappedOptionsShareTheLinesEvenlyInsteadOfLeavingOneStretchedAlone() {
        // "None" 36, "Linear" 48, "Smooth" 48, "Sphere" 48 units: three fit a 150 line, but four don't.
        SegmentedControl<String> falloff = new SegmentedControl<>(List.of("None", "Linear", "Smooth", "Sphere"), "None",
                option -> option, null);
        assertEquals(new Size(150, 2 * Theme.DARK.controlHeight), falloff.measure(ui, 150));
        falloff.layout(ui, new Rect(0, 0, 150, 2 * Theme.DARK.controlHeight));
        assertEquals(List.of(2, 2), falloff.optionsPerLine(), "two and two, not three and a stretched \"Sphere\"");
        assertEquals(0, falloff.indexAt(10, 4));
        assertEquals(1, falloff.indexAt(140, 4));
        assertEquals(2, falloff.indexAt(10, Theme.DARK.controlHeight + 4));
        assertEquals(3, falloff.indexAt(140, Theme.DARK.controlHeight + 4));
        assertEquals(falloff.indexAt(74, 4) + 2, falloff.indexAt(74, Theme.DARK.controlHeight + 4),
                "equal segments line up in a grid");
        RecordingGraphics g = new RecordingGraphics();
        falloff.render(g, ui);
        assertEquals(List.of("None", "Linear", "Smooth", "Sphere"), g.drawnTexts(), "whole, in order");

        assertArrayEquals(new int[] {3, 2}, SegmentedControl.lineSizes(new int[] {50, 50, 50, 50, 50}, 210),
                "five: three and two, not four and one");
        assertArrayEquals(new int[] {3, 2, 2}, SegmentedControl.lineSizes(new int[] {50, 50, 50, 50, 50, 50, 50}, 160));
        assertArrayEquals(new int[] {1, 2}, SegmentedControl.lineSizes(new int[] {72, 132, 60}, 200),
                "where only one split fits, that one");
        assertArrayEquals(new int[] {1, 2}, SegmentedControl.lineSizes(new int[] {300, 40, 40}, 100),
                "an option wider than the control alone on its line");
        assertArrayEquals(new int[] {4}, SegmentedControl.lineSizes(new int[] {36, 48, 48, 48}, 192), "one line");
    }

    private void layout(SettingsForm form, int width) {
        form.node().measure(ui, width);
        form.node().layout(ui, new Rect(0, 0, width, 2000));
    }

    /** Remembers toggles like the Tool Settings window's store. */
    private static final class Memory implements SettingsForm.SectionMemory {
        final Map<String, Boolean> states = new HashMap<>();

        @Override
        public Optional<Boolean> expanded(String titleKey) {
            return Optional.ofNullable(states.get(titleKey));
        }

        @Override
        public void toggled(String titleKey, boolean expanded) {
            states.put(titleKey, expanded);
        }
    }

    @Test
    void sectionsOpenAsRememberedAndReportTheirToggles() {
        Memory memory = new Memory();
        memory.states.put("section.more", true);
        memory.states.put("section.blocks", false);
        SettingsForm form = SettingsForm.build(SettingsValues.defaults(SCHEMA), changes::add, services, memory);
        CollapsibleSection blocks = form.section("section.blocks").orElseThrow();
        CollapsibleSection more = form.section("section.more").orElseThrow();
        assertFalse(blocks.isExpanded(), "closed as the player left it, though open by default");
        assertTrue(more.isExpanded(), "open as the player left it, though closed by default");

        blocks.toggle();
        assertEquals(true, memory.states.get("section.blocks"));
        more.toggle();
        assertEquals(false, memory.states.get("section.more"));
        assertTrue(form.section("").isEmpty(), "an untitled section is not collapsible");
    }

    @Test
    void revealingASettingOpensItsSectionAndFocusesIt() {
        Memory memory = new Memory();
        SettingsForm form = SettingsForm.build(SettingsValues.defaults(SCHEMA), changes::add, services, memory);
        CollapsibleSection more = form.section("section.more").orElseThrow();
        assertFalse(more.isExpanded());

        Node row = form.revealSetting("height", ui).orElseThrow();
        assertSame(form.field("height").orElseThrow(), row);
        assertTrue(more.isExpanded());
        assertEquals(true, memory.states.get("section.more"), "remembered like a click");
        assertSame(form.control("height").orElseThrow(), ui.focused(), "the keyboard is on its first slider");

        Node seedRow = form.revealSetting("seed", ui).orElseThrow();
        assertTrue(ui.focused().isDescendantOf(seedRow));
        assertTrue(form.revealSetting("mask", ui).isPresent(), "a list without rows focuses its Add button");
        assertInstanceOf(Button.class, ui.focused());

        assertTrue(form.revealSetting("falloff", ui).isEmpty(), "hidden while Advanced is off");
        assertTrue(form.revealSetting("nope", ui).isEmpty());
    }

    private static Translator translating(Set<String> keys) {
        return new Translator() {
            @Override
            public String translate(String key, Object... args) {
                return keys.contains(key) ? "text of " + key : Translator.KEYS.translate(key, args);
            }

            @Override
            public boolean has(String key) {
                return keys.contains(key);
            }
        };
    }

    private SettingsForm.Services servicesWith(Translator translator) {
        return new SettingsForm.Services() {
            @Override
            public Translator translator() {
                return translator;
            }

            @Override
            public BlockCatalog blocks() {
                return BlockCatalog.EMPTY;
            }

            @Override
            public void pickBlock(Rect anchor, BlockDescriptor current, Consumer<BlockDescriptor> onPick) {}

            @Override
            public Limits limits() {
                return LIMITS;
            }
        };
    }

    @Test
    void aSettingsTooltipShowsOverItsWholeRowAndOptionsHaveTheirOwn() {
        SettingsForm form = SettingsForm.build(SettingsValues.defaults(SCHEMA), changes::add, servicesWith(
                translating(Set.of("label.shape.tooltip", "label.shape.cube.tooltip"))));
        Node row = form.field("shape").orElseThrow();
        assertEquals("text of label.shape.tooltip", row.tooltip());
        row.measure(ui, 300);
        row.layout(ui, new Rect(0, 0, 300, 40));
        Label name = subtree(row).stream().filter(Label.class::isInstance).map(Label.class::cast)
                .filter(label -> label.text().equals("label.shape")).findFirst().orElseThrow();
        assertEquals("text of label.shape.tooltip", name.tooltip(), "over its name");

        @SuppressWarnings("unchecked")
        SegmentedControl<Shape> shape = (SegmentedControl<Shape>) form.control("shape").orElseThrow();
        Rect bounds = shape.bounds();
        shape.mouseMove(ui, bounds.right() - 2, bounds.y() + 2);
        assertEquals("text of label.shape.cube.tooltip", shape.tooltip(), "over Cube: its own");
        shape.mouseMove(ui, bounds.x() + 2, bounds.y() + 2);
        assertEquals("text of label.shape.tooltip", shape.tooltip(), "over Sphere, which has none: the setting's");
    }
}
