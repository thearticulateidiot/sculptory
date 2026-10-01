package dev.sculptory.fabric.client.editor.presets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tools.brush.BrushSettings;
import dev.sculptory.protocol.v2.Limits;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class PresetResolverTest {
    private static final Limits RADIUS_16 = new Limits(2_097_152L, 2_097_152L, 16, 20, 32L << 20, 2);
    private static final Predicate<BlockDescriptor> ALL = block -> true;
    private static final Predicate<BlockDescriptor> VANILLA = block -> block.block().value().startsWith("minecraft:");
    private static final BlockDescriptor STONE = block("minecraft:stone");
    private static final BlockDescriptor DIRT = block("minecraft:dirt");
    private static final BlockDescriptor CASING = block("create:andesite_casing");

    /** A plain section and a filter section (it holds a range and a block list), independent of any tool. */
    private static final SettingDef.Int COUNT = new SettingDef.Int("count", "label.count", 5, 1, 10);
    private static final SettingDef.Decimal SHARE = new SettingDef.Decimal("share", "label.share", 0.5, 0, 1, 0.05);
    private static final SettingDef.WeightedBlocks PALETTE = new SettingDef.WeightedBlocks("palette", "label.palette",
            List.of(new SettingDef.WeightedBlock(STONE, 1)), 2);
    private static final SettingDef.IntRange HEIGHT = new SettingDef.IntRange("height", "label.height",
            new SettingDef.IntSpan(-64, 319), -64, 319);
    private static final SettingDef.BlockList MASK = new SettingDef.BlockList("mask", "label.mask", List.of(), 4);
    private static final SettingDef.Bool INVERT = new SettingDef.Bool("invert", "label.invert", false);
    private static final Section FILTERS = new Section("section.filters", List.of(HEIGHT, MASK, INVERT));
    private static final SettingsSchema SCHEMA = new SettingsSchema(List.of(
            new Section("", List.of(COUNT, SHARE, PALETTE)), FILTERS));

    private final BrushSettings paint = BrushSettings.forTool(BrushTool.PAINT);
    private final BrushSettings palette = BrushSettings.forTool(BrushTool.PALETTE);

    private static BlockDescriptor block(String id) {
        return BlockDescriptor.of(new NamespacedId(id));
    }

    private static PresetResolver.Result resolve(SettingsSchema schema, Map<String, String> saved,
                                                 Predicate<BlockDescriptor> available) {
        return PresetResolver.resolve(SettingsValues.defaults(schema), saved, RADIUS_16, available);
    }

    @Test
    void savedValuesComeBackExactly() {
        SettingsValues values = SettingsValues.defaults(paint.schema())
                .with(paint.radius, 12)
                .with(paint.strength, 0.35)
                .with(paint.falloff, Falloff.LINEAR)
                .with(paint.material, STONE)
                .with(paint.maskBlocks, List.of(DIRT))
                .with(paint.maskInvert, true);
        PresetResolver.Result result = resolve(paint.schema(), values.encode(), ALL);
        assertEquals(values, result.values());
        assertTrue(result.clean());
    }

    @Test
    void aRadiusAboveTheServerMaximumIsClamped() {
        SettingsValues values = SettingsValues.defaults(paint.schema()).with(paint.radius, 32);
        PresetResolver.Result result = resolve(paint.schema(), values.encode(), ALL);
        assertEquals(16, result.values().get(paint.radius));
        assertEquals(List.of(new PresetResolver.Adjusted(paint.radius, "32", "16")), result.adjusted());
        assertEquals(List.of(), result.skipped());

        PresetResolver.Result unknownServer = PresetResolver.resolve(SettingsValues.defaults(paint.schema()),
                values.encode(), null, ALL);
        assertEquals(32, unknownServer.values().get(paint.radius), "without server limits only the setting's own apply");
        assertTrue(unknownServer.clean());
    }

    @Test
    void valuesOutsideTheSettingsOwnBoundsAreClampedToo() {
        String three = "1:minecraft:stone;1:minecraft:dirt;1:minecraft:sand";
        PresetResolver.Result result = resolve(SCHEMA, Map.of("count", "0", "share", "1.5", "palette", three), ALL);
        assertEquals(1, result.values().get(COUNT));
        assertEquals(1.0, result.values().get(SHARE));
        assertEquals(2, result.values().get(PALETTE).size(), "a palette is shortened: that only changes what is placed");
        assertEquals(List.of(new PresetResolver.Adjusted(COUNT, "0", "1"), new PresetResolver.Adjusted(SHARE, "1.5", "1.0"),
                new PresetResolver.Adjusted(PALETTE, "3", "2")), result.adjusted());
    }

    @Test
    void aValueThatDoesNotFitEvenClampedIsSkippedNotAdjusted() {
        SettingDef.Int size = new SettingDef.Int("size", "label.size", 5, 5, 10, Limits::maxBrushRadius,
                SettingDef.ALWAYS);
        Limits capTwo = new Limits(2_097_152L, 2_097_152L, 2, 20, 32L << 20, 2);
        PresetResolver.Result result = PresetResolver.resolve(SettingsValues.defaults(SettingsSchema.of(size)),
                Map.of("size", "8"), capTwo, ALL);
        assertEquals(5, result.values().get(size), "the default stays");
        assertEquals(List.of("size"), result.skipped());
        assertEquals(List.of(), result.adjusted(), "no toast claims it was changed to fit");
    }

    @Test
    void unknownKeysAndUndecodableValuesAreSkippedAndTheSettingKeepsItsDefault() {
        Map<String, String> saved = new TreeMap<>(SettingsValues.defaults(paint.schema()).with(paint.radius, 9).encode());
        saved.put("brush.size", "7");
        saved.put("falloff", "WOBBLY");
        saved.put("depth", "deep");
        saved.put("strength", "NaN");
        PresetResolver.Result result = resolve(paint.schema(), saved, ALL);
        assertEquals(List.of("strength", "falloff", "depth", "brush.size"), result.skipped(),
                "schema order, then the unknown keys");
        SettingsValues defaults = SettingsValues.defaults(paint.schema());
        assertEquals(defaults.get(paint.falloff), result.values().get(paint.falloff));
        assertEquals(defaults.get(paint.depth), result.values().get(paint.depth));
        assertEquals(defaults.get(paint.strength), result.values().get(paint.strength));
        assertEquals(9, result.values().get(paint.radius), "the rest of the preset still applies");
        assertEquals(List.of(), result.withheld());
    }

    @Test
    void settingsAPresetDoesNotMentionTakeTheirDefaultSilently() {
        PresetResolver.Result result = resolve(paint.schema(), Map.of("radius", "3"), ALL);
        assertEquals(SettingsValues.defaults(paint.schema()).with(paint.radius, 3), result.values());
        assertTrue(result.clean(), "a setting added after the preset was saved is not a problem");
    }

    @Test
    void missingBlocksAreLeftOutOfWhatIsPlaced() {
        SettingsValues values = SettingsValues.defaults(palette.schema()).with(palette.palette,
                List.of(new SettingDef.WeightedBlock(STONE, 3), new SettingDef.WeightedBlock(CASING, 1)));
        PresetResolver.Result result = resolve(palette.schema(), values.encode(), VANILLA);
        assertEquals(List.of(new SettingDef.WeightedBlock(STONE, 3)), result.values().get(palette.palette));
        assertEquals(List.of("create:andesite_casing"), result.unavailable());
        assertEquals(List.of(), result.skipped());

        SettingsValues material = SettingsValues.defaults(paint.schema()).with(paint.material, CASING);
        PresetResolver.Result single = resolve(paint.schema(), material.encode(), VANILLA);
        assertEquals(SettingsValues.defaults(paint.schema()).get(paint.material), single.values().get(paint.material),
                "a single missing block keeps the default");
        assertEquals(List.of("create:andesite_casing"), single.unavailable());
        assertEquals(List.of("material"), single.skipped(), "and the setting is reported as not used");

        SettingsValues onlyMissing = SettingsValues.defaults(palette.schema())
                .with(palette.palette, List.of(new SettingDef.WeightedBlock(CASING, 1)));
        PresetResolver.Result empty = resolve(palette.schema(), onlyMissing.encode(), VANILLA);
        assertEquals(SettingsValues.defaults(palette.schema()).get(palette.palette), empty.values().get(palette.palette),
                "a palette left empty is never used empty: the default stays");
        assertEquals(List.of("palette"), empty.skipped());
    }

    @Test
    void missingBlocksStayInMasksAndFiltersSoTheyNeverWiden() {
        PresetResolver.Result list = resolve(SCHEMA, Map.of("mask", "create:andesite_casing;minecraft:stone"), VANILLA);
        assertEquals(List.of(CASING, STONE), list.values().get(MASK), "kept: here it matches nothing");
        assertEquals(List.of("create:andesite_casing"), list.unmatched());
        assertEquals(List.of(), list.unavailable());
        assertEquals(List.of(), list.withheld(), "matching nothing is what the preset means here: it applies");

        PresetResolver.Result only = resolve(SCHEMA, Map.of("mask", "create:andesite_casing", "invert", "true"), VANILLA);
        assertEquals(List.of(CASING), only.values().get(MASK), "never the empty list, which would mean any block");
        assertTrue(only.values().get(INVERT));
    }

    @Test
    void aFilterValueThatWouldChangeWithholdsTheWholeSection() {
        SettingsValues current = SettingsValues.defaults(SCHEMA).with(MASK, List.of(DIRT))
                .with(HEIGHT, new SettingDef.IntSpan(0, 100));
        String five = String.join(";", Collections.nCopies(5, "minecraft:stone"));
        for (Map<String, String> saved : List.of(
                Map.of("count", "7", "mask", five, "invert", "true"),
                Map.of("count", "7", "mask", "minecraft:stone;Not A Block[", "invert", "true"),
                Map.of("count", "7", "height", "-100..500", "invert", "true"),
                Map.of("count", "7", "height", "9..3", "invert", "true"),
                Map.of("count", "7", "mask", "minecraft:stone", "invert", "maybe"))) {
            PresetResolver.Result result = PresetResolver.resolve(current, saved, RADIUS_16, ALL);
            assertEquals(7, result.values().get(COUNT), "the rest of the preset applies: " + saved);
            assertEquals(List.of(DIRT), result.values().get(MASK), "the filters stay as they were: " + saved);
            assertEquals(new SettingDef.IntSpan(0, 100), result.values().get(HEIGHT), saved.toString());
            assertFalse(result.values().get(INVERT), "not even the invert flag is taken: " + saved);
            assertEquals(1, result.withheld().size());
            assertEquals(FILTERS, result.withheld().get(0).section());
            assertEquals(List.of(), result.skipped(), "reported as withheld, not twice");
            assertEquals(List.of(), result.adjusted());
            assertFalse(result.clean());
        }
    }

    @Test
    void sectionsWithABlockListOrARangeAreFilters() {
        assertTrue(PresetResolver.isFilter(FILTERS));
        assertFalse(PresetResolver.isFilter(SCHEMA.sections().get(0)));
        assertTrue(PresetResolver.isFilter(paint.schema().sections().get(1)), "the brush Mask section");
        assertFalse(PresetResolver.isFilter(paint.schema().sections().get(0)));
    }
}
