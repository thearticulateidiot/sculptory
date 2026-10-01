package dev.sculptory.fabric.client.editor.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.fabric.client.editor.settings.SettingDef.AssetWeight;
import dev.sculptory.fabric.client.editor.settings.SettingDef.IntSpan;
import dev.sculptory.fabric.client.editor.settings.SettingDef.WeightedBlock;
import dev.sculptory.protocol.v2.Limits;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import org.junit.jupiter.api.Test;

class SettingsValuesTest {
    private static final BlockDescriptor STONE = BlockDescriptor.parse("minecraft:stone");
    private static final BlockDescriptor STAIRS =
            BlockDescriptor.parse("minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]");

    static final SettingDef.Bool MASKED = new SettingDef.Bool("masked", "label.masked", false);
    static final SettingDef.Int RADIUS = new SettingDef.Int("radius", "label.radius", 8, 1, 32,
            Limits::maxBrushRadius, SettingDef.ALWAYS);
    static final SettingDef.Decimal STRENGTH = new SettingDef.Decimal("strength", "label.strength", 0.5, 0, 1, 0.05);
    static final SettingDef.Enum<Falloff> FALLOFF =
            new SettingDef.Enum<>("falloff", "label.falloff", Falloff.class, Falloff.SMOOTH);
    static final SettingDef.Block MATERIAL = new SettingDef.Block("material", "label.material", STONE);
    static final SettingDef.BlockList MASK_BLOCKS = new SettingDef.BlockList("mask.blocks", "label.mask", List.of(), 8,
            values -> values.get(MASKED));
    static final SettingDef.WeightedBlocks PALETTE = new SettingDef.WeightedBlocks("palette", "label.palette",
            List.of(new WeightedBlock(STONE, 1)), 4);
    static final SettingDef.IntRange HEIGHTS = new SettingDef.IntRange("heights", "label.heights",
            new IntSpan(-64, 320), -64, 320);
    static final SettingDef.AssetMix ASSETS = new SettingDef.AssetMix("assets", "label.assets", List.of(), 4);
    static final SettingDef.Seed SEED = new SettingDef.Seed("seed", "label.seed", 42L);

    static final SettingsSchema SCHEMA = new SettingsSchema(List.of(
            new Section("", List.of(RADIUS, STRENGTH, FALLOFF, MATERIAL, PALETTE)),
            new Section("section.mask", List.of(MASKED, MASK_BLOCKS, HEIGHTS), true),
            new Section("section.scatter", List.of(ASSETS, SEED))));

    @Test
    void defaults() {
        SettingsValues values = SettingsValues.defaults(SCHEMA);
        assertEquals(8, values.get(RADIUS));
        assertEquals(0.5, values.get(STRENGTH));
        assertEquals(Falloff.SMOOTH, values.get(FALLOFF));
        assertEquals(STONE, values.get(MATERIAL));
        assertEquals(List.of(), values.get(MASK_BLOCKS));
        assertEquals(List.of(new WeightedBlock(STONE, 1)), values.get(PALETTE));
        assertEquals(new IntSpan(-64, 320), values.get(HEIGHTS));
        assertEquals(42L, values.get(SEED));
        assertFalse(values.get(MASKED));
        assertEquals(10, SCHEMA.defs().size());
    }

    @Test
    void withReturnsANewImmutableInstance() {
        SettingsValues defaults = SettingsValues.defaults(SCHEMA);
        List<BlockDescriptor> blocks = new ArrayList<>(List.of(STONE));
        SettingsValues changed = defaults.with(RADIUS, 16).with(MASK_BLOCKS, blocks);
        blocks.add(STAIRS);
        assertEquals(8, defaults.get(RADIUS));
        assertEquals(16, changed.get(RADIUS));
        assertEquals(List.of(STONE), changed.get(MASK_BLOCKS));
        assertThrows(UnsupportedOperationException.class, () -> changed.get(MASK_BLOCKS).add(STAIRS));
        assertNotEquals(defaults, changed);
        assertEquals(changed, defaults.with(RADIUS, 16).with(MASK_BLOCKS, List.of(STONE)));
        assertEquals(changed.hashCode(), defaults.with(RADIUS, 16).with(MASK_BLOCKS, List.of(STONE)).hashCode());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void withRejectsForeignDefsAndWrongTypes() {
        SettingsValues values = SettingsValues.defaults(SCHEMA);
        SettingDef.Int other = new SettingDef.Int("radius", "label.radius", 4, 1, 8);
        assertThrows(IllegalArgumentException.class, () -> values.with(other, 3));
        assertThrows(IllegalArgumentException.class, () -> values.get(other));
        SettingDef raw = RADIUS;
        assertThrows(IllegalArgumentException.class, () -> values.with(raw, "sixteen"));
        assertThrows(IllegalArgumentException.class, () -> values.with(RADIUS, null));
        SettingDef rawList = PALETTE;
        assertThrows(IllegalArgumentException.class, () -> values.with(rawList, List.of("minecraft:stone")));
    }

    @Test
    void encodeDecodeRoundTripsEveryType() {
        SettingsValues values = SettingsValues.defaults(SCHEMA)
                .with(RADIUS, 31)
                .with(STRENGTH, 0.125)
                .with(FALLOFF, Falloff.SPHERE)
                .with(MATERIAL, STAIRS)
                .with(MASKED, true)
                .with(MASK_BLOCKS, List.of(STONE, STAIRS))
                .with(PALETTE, List.of(new WeightedBlock(STONE, 3), new WeightedBlock(STAIRS, 1000)))
                .with(HEIGHTS, new IntSpan(-10, -2))
                .with(ASSETS, List.of(new AssetWeight("trees/oak_1.schem", 2), new AssetWeight("a:b", 1)))
                .with(SEED, Long.MIN_VALUE);
        SortedMap<String, String> encoded = values.encode();
        assertEquals("31", encoded.get("radius"));
        assertEquals("3:minecraft:stone;1000:" + STAIRS.format(), encoded.get("palette"));
        assertEquals("-10..-2", encoded.get("heights"));
        assertEquals("", SettingsValues.defaults(SCHEMA).encode().get("mask.blocks"));
        assertEquals(values, SettingsValues.decode(SCHEMA, encoded));
        assertEquals(SettingsValues.defaults(SCHEMA), SettingsValues.decode(SCHEMA, SettingsValues.defaults(SCHEMA).encode()));
    }

    @Test
    void decodeIsLenient() {
        Map<String, String> bad = new HashMap<>();
        bad.put("radius", "huge");
        bad.put("strength", "NaN");
        bad.put("falloff", "WOBBLY");
        bad.put("material", "minecraft:stone[");
        bad.put("masked", "yes");
        bad.put("palette", "stone");
        bad.put("heights", "5..-5");
        bad.put("assets", ":x");
        bad.put("seed", "12x");
        bad.put("unknown.key", "whatever");
        assertEquals(SettingsValues.defaults(SCHEMA), SettingsValues.decode(SCHEMA, bad));
        SettingsValues outOfRange = SettingsValues.decode(SCHEMA, Map.of("radius", "500", "strength", "0.25"));
        assertEquals(8, outOfRange.get(RADIUS), "values outside the def's bounds fall back");
        assertEquals(0.25, outOfRange.get(STRENGTH));
        assertEquals(SettingsValues.defaults(SCHEMA), SettingsValues.decode(SCHEMA, Map.of()));
    }

    @Test
    void validationUsesDefBoundsAndServerLimits() {
        SettingsValues values = SettingsValues.defaults(SCHEMA).with(RADIUS, 20);
        Limits small = new Limits(1000, 1000, 16, 20, 1000, 1);
        assertTrue(values.isValid(null));
        assertTrue(values.isValid(Limits.DEFAULTS));
        assertFalse(values.isValid(small));
        Validation radius = values.validate(small).get("radius");
        assertEquals(Validation.Level.ERROR, radius.level());
        assertEquals(List.of("16"), radius.args());
        SettingsValues emptyPalette = SettingsValues.defaults(SCHEMA).with(PALETTE, List.of());
        assertFalse(emptyPalette.validate(null).get("palette").isValid());
        assertFalse(SettingsValues.defaults(SCHEMA).with(RADIUS, 0).isValid(null));
        assertEquals(List.copyOf(SCHEMA.defs().stream().map(SettingDef::key).toList()),
                List.copyOf(values.validate(null).keySet()));
    }

    @Test
    void visibilityDependsOnOtherValues() {
        SettingsValues values = SettingsValues.defaults(SCHEMA);
        assertFalse(values.isVisible(MASK_BLOCKS));
        assertTrue(values.with(MASKED, true).isVisible(MASK_BLOCKS));
        assertTrue(values.isVisible(RADIUS));
    }

    @Test
    void schemaAndDefValidation() {
        assertThrows(IllegalArgumentException.class, () -> SettingsSchema.of(RADIUS, new SettingDef.Seed("radius", "x", 1L)));
        assertThrows(IllegalArgumentException.class, () -> new SettingDef.Int("Radius", "x", 1, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new SettingDef.Int("r", "x", 5, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new SettingDef.WeightedBlocks("p", "x", List.of(), 2));
        assertThrows(IllegalArgumentException.class, () -> new WeightedBlock(STONE, 0));
        assertThrows(IllegalArgumentException.class, () -> new AssetWeight("a;b", 1));
        assertEquals(RADIUS, SCHEMA.def("radius").orElseThrow());
        assertTrue(SCHEMA.def("missing").isEmpty());
        assertTrue(SettingsValues.defaults(SettingsSchema.EMPTY).encode().isEmpty());
    }
}
