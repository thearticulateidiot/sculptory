package dev.sculptory.fabric.client.editor.tools.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.fabric.client.editor.presets.PresetResolver;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tools.select.SelectSettings;
import dev.sculptory.protocol.v2.Limits;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Pattern settings in Palette Paint, the Shape brush and Fill: what shows
 * when, presets from before them and with bad values, and a palette's pattern in and out.
 */
class MixPatternSettingsTest {
    private final BrushSettings palette = BrushSettings.forTool(BrushTool.PALETTE);

    private static SettingsValues defaults(SettingsSchema schema) {
        return SettingsValues.defaults(schema);
    }

    @Test
    void eachPatternShowsItsOwnSettings() {
        MixPatternSettings p = palette.mixPattern;
        SettingsValues values = defaults(palette.schema());
        assertEquals(PalettePattern.Kind.RANDOM, values.get(p.pattern));
        assertTrue(values.isVisible(p.pattern));
        assertFalse(values.isVisible(p.patchSize) || values.isVisible(p.edge) || values.isVisible(p.steepnessEdge)
                || values.isVisible(p.seed), "Random has nothing more");
        SettingsValues patches = values.with(p.pattern, PalettePattern.Kind.PATCHES);
        assertTrue(patches.isVisible(p.patchSize) && patches.isVisible(p.seed) && !patches.isVisible(p.edge));
        SettingsValues gradient = values.with(p.pattern, PalettePattern.Kind.GRADIENT);
        assertTrue(gradient.isVisible(p.edge) && gradient.isVisible(p.seed) && !gradient.isVisible(p.patchSize));
        SettingsValues steep = values.with(p.pattern, PalettePattern.Kind.STEEPNESS);
        assertTrue(steep.isVisible(p.steepnessEdge) && !steep.isVisible(p.edge));
        assertTrue(p.pattern.available(PalettePattern.Kind.STEEPNESS), "Palette Paint measures the slope");
        assertTrue(steep.isValid(null));
        // Only Palette Paint among the brushes has one.
        assertEquals(null, BrushSettings.forTool(BrushTool.PAINT).mixPattern);
        assertEquals(null, BrushSettings.forTool(BrushTool.RAISE).mixPattern);
        // Fill shows it with the palette only, the Shape brush with its mix only.
        SettingsValues select = defaults(SelectSettings.SCHEMA);
        assertFalse(select.isVisible(SelectSettings.PATTERN.pattern), "with the active block");
        assertTrue(select.with(SelectSettings.FILL_WITH, SelectSettings.FillWith.PALETTE).isVisible(SelectSettings.PATTERN.pattern));
        SettingsValues shape = defaults(ShapeSettings.SCHEMA).with(ShapeSettings.BLOCKS, ShapeSettings.Blocks.PALETTE);
        assertTrue(shape.isVisible(ShapeSettings.PATTERN.pattern));
        assertFalse(shape.with(ShapeSettings.MODE, dev.sculptory.core.brush.ShapeSpec.Mode.CARVE)
                .isVisible(ShapeSettings.PATTERN.pattern), "carving places no blocks");
        assertFalse(SelectSettings.PATTERN.pattern.available(PalettePattern.Kind.STEEPNESS));
        assertFalse(ShapeSettings.PATTERN.pattern.available(PalettePattern.Kind.STEEPNESS));
        assertFalse(shape.with(ShapeSettings.PATTERN.pattern, PalettePattern.Kind.STEEPNESS).isValid(null));
    }

    /**
     * Presets: one saved before patterns existed loads as Random with every other value at its default; one saved with
     * them loads them back; an unknown pattern name is skipped (the default stays), values out of range are brought
     * into range, and a Steepness preset for Fill (hand-edited: Fill can't choose it) is skipped.
     */
    @Test
    void presetsOldAndNewAndBadLoadAsExpected() {
        MixPatternSettings p = palette.mixPattern;
        SettingsValues current = defaults(palette.schema()).with(p.pattern, PalettePattern.Kind.PATCHES);
        Map<String, String> old = Map.of("radius", "6", "palette", "3:minecraft:stone;1:minecraft:dirt");
        PresetResolver.Result fromOld = PresetResolver.resolve(current, old, Limits.DEFAULTS, block -> true);
        assertEquals(PalettePattern.Kind.RANDOM, fromOld.values().get(p.pattern), "a preset from before patterns");
        assertEquals(p.patchSize.defaultValue(), fromOld.values().get(p.patchSize));
        assertTrue(fromOld.skipped().isEmpty() && fromOld.adjusted().isEmpty());

        SettingsValues saved = defaults(palette.schema()).with(p.pattern, PalettePattern.Kind.GRADIENT)
                .with(p.edge, 9).with(p.patchSize, 20).with(p.steepnessEdge, 30).with(p.seed, -77L);
        Map<String, String> encoded = saved.encode();
        assertEquals("GRADIENT", encoded.get("pattern"));
        assertEquals("9", encoded.get("pattern.edge"));
        assertEquals(saved, PresetResolver.resolve(defaults(palette.schema()), encoded, Limits.DEFAULTS, block -> true)
                .values());

        Map<String, String> bad = new HashMap<>(encoded);
        bad.put("pattern", "STRIPES");
        bad.put("pattern.size", "99");
        bad.put("pattern.edge", "-4");
        bad.put("pattern.steepness_edge", "90");
        bad.put("pattern.seed", "not a number");
        PresetResolver.Result resolved = PresetResolver.resolve(defaults(palette.schema()), bad, Limits.DEFAULTS,
                block -> true);
        assertEquals(PalettePattern.Kind.RANDOM, resolved.values().get(p.pattern), "an unknown name keeps the default");
        assertEquals(32, resolved.values().get(p.patchSize), "clamped");
        assertEquals(0, resolved.values().get(p.edge), "clamped");
        assertEquals(45, resolved.values().get(p.steepnessEdge), "clamped");
        assertEquals(0L, resolved.values().get(p.seed), "an unreadable seed keeps the default");
        assertTrue(resolved.skipped().containsAll(List.of("pattern", "pattern.seed")), "skipped: " + resolved.skipped());
        assertEquals(3, resolved.adjusted().size(), "adjusted: " + resolved.adjusted());

        Map<String, String> steepFill = Map.of("fill_with", "PALETTE", "pattern", "STEEPNESS");
        PresetResolver.Result fill = PresetResolver.resolve(defaults(SelectSettings.SCHEMA), steepFill, Limits.DEFAULTS,
                block -> true);
        assertEquals(PalettePattern.Kind.RANDOM, fill.values().get(SelectSettings.PATTERN.pattern));
        assertTrue(fill.skipped().contains("pattern"));
    }

    /**
     * A palette keeps the pattern values; loading one sets them all, and a Steepness palette loads into the Shape brush
     * (and Fill) as Random, its other values kept.
     */
    @Test
    void aPalettesPatternGoesInAndOut() {
        MixPatternSettings p = palette.mixPattern;
        SettingsValues values = defaults(palette.schema()).with(p.pattern, PalettePattern.Kind.STEEPNESS)
                .with(p.patchSize, 3).with(p.edge, 5).with(p.steepnessEdge, 7).with(p.seed, 11L);
        PalettePattern pattern = p.palettePattern(values);
        assertEquals(new PalettePattern(PalettePattern.Kind.STEEPNESS, 3, 5, 7, 11L), pattern);
        assertEquals(values, p.withPalettePattern(defaults(palette.schema()), pattern));
        SettingsValues shape = ShapeSettings.PATTERN.withPalettePattern(defaults(ShapeSettings.SCHEMA), pattern);
        assertEquals(PalettePattern.Kind.RANDOM, shape.get(ShapeSettings.PATTERN.pattern));
        assertEquals(3, shape.get(ShapeSettings.PATTERN.patchSize));
        assertEquals(11L, shape.get(ShapeSettings.PATTERN.seed));
        assertEquals(PalettePattern.RANDOM, p.palettePattern(defaults(palette.schema())), "the defaults are Random's");
    }
}
