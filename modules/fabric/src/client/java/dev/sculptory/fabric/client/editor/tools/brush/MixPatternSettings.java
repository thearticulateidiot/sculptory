package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.core.edit.MixLayout;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.session.Notice;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The Pattern setting of a tool's block mix and the values that go with it,
 * for Palette Paint, the Shape brush (Blocks: Mix) and Fill (Fill with: Palette):
 * <ul>
 *   <li><b>Pattern</b>: Random (the default: each block picked by weight, exactly as before, so presets saved earlier
 *       load unchanged), Patches, Gradient or Steepness. Steepness needs the ground's slope, which only Palette Paint
 *       measures: in the other tools it is shown greyed out, saying so.</li>
 *   <li><b>Patch size</b> (Patches, 1-32 blocks), <b>Edge</b> (Gradient, 0-32 blocks), <b>Edge</b> (Steepness, 0-45
 *       degrees), each shown with its pattern;</li>
 *   <li><b>Seed</b> with a <b>Re-roll</b> button (every pattern but Random; Random picks a new seed each stroke or
 *       Fill, as it always did).</li>
 * </ul>
 * The mix's order (the Gradient's and Steepness' order, first to last) is the mix setting's own order, changed by
 * dragging its rows. Presets keep these as any setting (a preset saved before them loads as Random); palettes keep them
 * as a {@link PalettePattern}. Keys: {@code pattern}, {@code pattern.size}, {@code pattern.edge},
 * {@code pattern.steepness_edge}, {@code pattern.seed}.
 */
public final class MixPatternSettings {
    private static final String PREFIX = "sculptory.setting.pattern.";
    /** Why Steepness is greyed out where the ground's slope isn't measured. */
    public static final String STEEPNESS_UNAVAILABLE = PREFIX + "steepness_unavailable";

    public final SettingDef.Enum<PalettePattern.Kind> pattern;
    public final SettingDef.Int patchSize;
    public final SettingDef.Int edge;
    public final SettingDef.Int steepnessEdge;
    public final SettingDef.Seed seed;
    private final boolean steepness;

    /**
     * @param shown whether the tool uses its mix now (the settings show only then)
     * @param steepness whether the tool measures the ground's slope (Palette Paint); elsewhere Steepness is greyed out
     */
    public MixPatternSettings(Predicate<SettingsValues> shown, boolean steepness) {
        Objects.requireNonNull(shown);
        this.steepness = steepness;
        pattern = new SettingDef.Enum<>("pattern", PREFIX + "kind", PalettePattern.Kind.class,
                PalettePattern.Kind.RANDOM, shown,
                steepness ? Map.of() : Map.of(PalettePattern.Kind.STEEPNESS, STEEPNESS_UNAVAILABLE));
        SettingDef.Enum<PalettePattern.Kind> kind = pattern;
        patchSize = new SettingDef.Int("pattern.size", PREFIX + "size", MixLayout.Patches.DEFAULT_SIZE,
                MixLayout.Patches.MIN_SIZE, MixLayout.Patches.MAX_SIZE, null,
                values -> shown.test(values) && values.get(kind) == PalettePattern.Kind.PATCHES);
        edge = new SettingDef.Int("pattern.edge", PREFIX + "edge", MixLayout.Gradient.DEFAULT_EDGE,
                MixLayout.Gradient.MIN_EDGE, MixLayout.Gradient.MAX_EDGE, null,
                values -> shown.test(values) && values.get(kind) == PalettePattern.Kind.GRADIENT);
        steepnessEdge = new SettingDef.Int("pattern.steepness_edge", PREFIX + "steepness_edge",
                MixLayout.Steepness.DEFAULT_EDGE, MixLayout.Steepness.MIN_EDGE, MixLayout.Steepness.MAX_EDGE, null,
                values -> steepness && shown.test(values) && values.get(kind) == PalettePattern.Kind.STEEPNESS);
        seed = new SettingDef.Seed("pattern.seed", PREFIX + "seed", 0L,
                values -> shown.test(values) && values.get(kind) != PalettePattern.Kind.RANDOM);
    }

    /** The settings in the order they are shown, after the mix. */
    public List<SettingDef<?>> defs() {
        return List.of(pattern, patchSize, edge, steepnessEdge, seed);
    }

    /** Whether this tool measures the ground's slope (Steepness can be chosen). */
    public boolean steepness() {
        return steepness;
    }

    /**
     * The pattern the settings give for {@code mix} (its blocks in order and weights; {@code mix}'s own seed is the
     * stroke's or Fill's random one): Random is {@code mix} itself; the others lay it out, with the Seed setting as the
     * noise's seed. A Gradient takes {@code line}: without one the player is told to draw it and this is {@code null}.
     */
    public Pattern pattern(ToolContext c, SettingsValues values, Pattern.Weighted mix, GradientLine line) {
        PalettePattern.Kind kind = values.get(pattern);
        if (kind == PalettePattern.Kind.RANDOM || kind == PalettePattern.Kind.STEEPNESS && !steepness) {
            return mix;
        }
        Pattern.Weighted seeded = new Pattern.Weighted(mix.states(), mix.weights(), values.get(seed));
        MixLayout layout = switch (kind) {
            case PATCHES -> new MixLayout.Patches(clamp(values.get(patchSize), MixLayout.Patches.MIN_SIZE,
                    MixLayout.Patches.MAX_SIZE));
            case GRADIENT -> line.layout(clamp(values.get(edge), MixLayout.Gradient.MIN_EDGE, MixLayout.Gradient.MAX_EDGE))
                    .orElse(null);
            case STEEPNESS -> new MixLayout.Steepness(clamp(values.get(steepnessEdge), MixLayout.Steepness.MIN_EDGE,
                    MixLayout.Steepness.MAX_EDGE));
            case RANDOM -> throw new AssertionError("handled above");
        };
        if (layout == null) {
            c.notify(Notice.of(Notice.Level.WARNING, GradientDrag.NO_LINE));
            return null;
        }
        return new Pattern.Arranged(seeded, layout);
    }

    /** Whether the settings pick the Gradient pattern (the tool draws its line and takes Alt+drag for it). */
    public boolean gradient(SettingsValues values) {
        return values.get(pattern) == PalettePattern.Kind.GRADIENT;
    }

    /** The pattern values as a palette keeps them. */
    public PalettePattern palettePattern(SettingsValues values) {
        return new PalettePattern(values.get(pattern),
                clamp(values.get(patchSize), MixLayout.Patches.MIN_SIZE, MixLayout.Patches.MAX_SIZE),
                clamp(values.get(edge), MixLayout.Gradient.MIN_EDGE, MixLayout.Gradient.MAX_EDGE),
                clamp(values.get(steepnessEdge), MixLayout.Steepness.MIN_EDGE, MixLayout.Steepness.MAX_EDGE),
                values.get(seed));
    }

    /**
     * {@code values} with a palette's pattern values; a Steepness palette loads as Random where Steepness can't be
     * chosen (its other values still load).
     */
    public SettingsValues withPalettePattern(SettingsValues values, PalettePattern loaded) {
        PalettePattern.Kind kind = loaded.kind() == PalettePattern.Kind.STEEPNESS && !steepness
                ? PalettePattern.Kind.RANDOM : loaded.kind();
        return values.with(pattern, kind)
                .with(patchSize, loaded.patchSize())
                .with(edge, loaded.edge())
                .with(steepnessEdge, loaded.steepnessEdge())
                .with(seed, loaded.seed());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
