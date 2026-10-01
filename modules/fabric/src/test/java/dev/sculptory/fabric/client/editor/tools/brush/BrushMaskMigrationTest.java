package dev.sculptory.fabric.client.editor.tools.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.ColumnFilter;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Brush masks move to the rule model: a preset saved with the old mask keys gives
 * exactly the result it gave before, for every state, height and slope; a rule list, once set, takes over.
 */
class BrushMaskMigrationTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();

    private static BlockDescriptor block(String text) {
        return BlockDescriptor.parse(text);
    }

    /** Whether two masks accept the same columns, over every state, height and slope. */
    private static String differs(SurfaceMask legacy, SurfaceMask rules) {
        FakeWorld world = new FakeWorld(STATES);
        ColumnFilter a = ColumnFilter.compile(legacy, STATES, world), b = ColumnFilter.compile(rules, STATES, world);
        for (int state = 0; state < STATES.size(); state++) {
            for (int y : new int[] {-64, -1, 0, 59, 60, 61, 70, 80, 81, 200, 319}) {
                for (int slope = 0; slope <= 17; slope++) {
                    if (a.test(3, y, 4, state, slope) != b.test(3, y, 4, state, slope)) {
                        return STATES.format(state) + " y " + y + " slope " + slope;
                    }
                }
            }
        }
        return "";
    }

    @Test
    void everyLegacyMaskGivesTheSameResultAsItsRules() {
        BrushSettings s = BrushSettings.forTool(BrushTool.PAINT);
        List<List<BlockDescriptor>> lists = List.of(List.of(),
                List.of(block("minecraft:grass_block"), block("minecraft:grass_block[snowy=true]")),
                List.of(block("minecraft:oak_log[axis=x]"), block("minecraft:stone"), block("minecraft:oak_log")),
                List.of(block("minecraft:oak_stairs"), block("minecraft:coarse_dirt"), block("minecraft:oak_log[axis=q]")));
        List<SettingDef.IntSpan> heights = List.of(new SettingDef.IntSpan(BrushSettings.MIN_Y, BrushSettings.MAX_Y),
                new SettingDef.IntSpan(60, 80), new SettingDef.IntSpan(BrushSettings.MIN_Y, 0));
        List<SettingDef.IntSpan> slopes = List.of(new SettingDef.IntSpan(0, BrushSettings.MAX_SLOPE),
                new SettingDef.IntSpan(0, 2), new SettingDef.IntSpan(3, BrushSettings.MAX_SLOPE));
        int cases = 0;
        for (List<BlockDescriptor> blocks : lists) {
            for (boolean exact : new boolean[] {false, true}) {
                for (SettingDef.IntSpan y : heights) {
                    for (SettingDef.IntSpan slope : slopes) {
                        for (boolean invert : new boolean[] {false, true}) {
                            SettingsValues values = SettingsValues.defaults(s.schema()).with(s.maskBlocks, blocks)
                                    .with(s.maskExactStates, exact).with(s.maskY, y).with(s.maskSlope, slope)
                                    .with(s.maskInvert, invert);
                            SurfaceMask legacy = s.legacyMask(values, STATES), rules = s.mask(values, STATES);
                            String what = blocks + " exact " + exact + " y " + y + " slope " + slope + " invert " + invert;
                            assertEquals(legacy instanceof SurfaceMask.Any, rules instanceof SurfaceMask.Any, what);
                            assertEquals("", differs(legacy, rules), what);
                            // Through a saved preset (no mask.rules key, as before rules existed): the same.
                            Map<String, String> saved = new HashMap<>(values.encode());
                            saved.remove("mask.rules");
                            assertEquals(rules, s.mask(SettingsValues.decode(s.schema(), saved), STATES), what);
                            cases++;
                        }
                    }
                }
            }
        }
        assertEquals(4 * 2 * 3 * 3 * 2, cases);
    }

    @Test
    void aRuleListOnceSetTakesOverAndRoundTrips() {
        BrushSettings s = BrushSettings.forTool(BrushTool.RAISE);
        SettingsValues legacy = SettingsValues.defaults(s.schema()).with(s.maskBlocks, List.of(block("minecraft:stone")))
                .with(s.maskInvert, true);
        EditMask rules = new EditMask(List.of(MaskEntry.of(new MaskRule.NextTo(BlockSet.parse("#minecraft:logs"))),
                new MaskEntry(new MaskRule.Height(0, 63), true)), false);
        SettingsValues set = legacy.with(s.maskRules, SettingDef.MaskValue.of(rules));
        assertEquals(new SurfaceMask.Rules(rules), s.mask(set, STATES), "the rules, not the legacy keys");
        assertEquals(List.of(block("minecraft:stone")), set.get(s.maskBlocks), "the legacy keys are kept");
        SettingsValues back = SettingsValues.decode(s.schema(), set.encode());
        assertEquals(set, back);
        SettingsValues cleared = set.with(s.maskRules, SettingDef.MaskValue.of(EditMask.NONE));
        assertEquals(SurfaceMask.ANY, s.mask(cleared, STATES), "an emptied rule list limits nothing");
        assertEquals("", SettingsValues.defaults(s.schema()).encode().get("mask.rules"), "unset saves as empty");
        assertTrue(set.isVisible(s.maskRules));
        assertFalse(set.isVisible(s.maskBlocks), "the legacy keys never show");
        assertEquals(s.legacyRules(legacy, STATES), s.maskRules.shown(legacy, STATES), "shown while unset: the legacy mask");
    }
}
