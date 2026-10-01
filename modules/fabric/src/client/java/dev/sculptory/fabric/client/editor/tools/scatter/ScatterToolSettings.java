package dev.sculptory.fabric.client.editor.tools.scatter;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.protocol.v2.C2S;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The Scatter tool's settings and how they map onto a {@code ScatterPreview}: the painting radius; seed, density (a
 * percentage of the eligible columns or a target count) and spacing; a collapsed Filters section (slope, elevation,
 * substrate blocks; a range left at its full span means "no limit", as for the brushes' mask); Transforms (allowed
 * quarter turns and mirroring); and Fit (block variants only where they can survive, fluids, minimum support, how tall
 * column plants grow). The variant mix is not a setting: it lives in {@link ScatterMix}.
 */
public final class ScatterToolSettings {
    /** How the density is given. */
    public enum DensityMode {
        PERCENT,
        COUNT
    }

    public static final int MAX_SUBSTRATE_BLOCKS = 16;
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 319;
    /** Steepest step, in blocks, the slope filter can express; the full range means no limit. */
    public static final int MAX_SLOPE = 16;

    private static final String PREFIX = "sculptory.setting.scatter.";

    public final SettingDef.Int radius;
    public final SettingDef.Seed seed;
    public final SettingDef.Enum<DensityMode> densityMode;
    public final SettingDef.Decimal densityPercent;
    public final SettingDef.Int densityCount;
    public final SettingDef.Int spacing;
    public final SettingDef.IntRange slope;
    public final SettingDef.IntRange elevation;
    public final SettingDef.BlockList substrate;
    public final SettingDef.Bool turn0;
    public final SettingDef.Bool turn90;
    public final SettingDef.Bool turn180;
    public final SettingDef.Bool turn270;
    public final SettingDef.Bool mirror;
    public final SettingDef.Bool allowInFluid;
    public final SettingDef.Bool survive;
    public final SettingDef.Int support;
    /** How tall column plants (sugar cane, cactus, bamboo, kelp) grow: 1-1 by default, so one block. */
    public final SettingDef.IntRange columnHeight;
    private final SettingsSchema schema;

    public ScatterToolSettings() {
        radius = new SettingDef.Int("radius", PREFIX + "radius", 8, 1, ScatterArea.MAX_RADIUS);
        seed = new SettingDef.Seed("seed", PREFIX + "seed", 1L);
        densityMode = new SettingDef.Enum<>("density_mode", PREFIX + "density_mode", DensityMode.class, DensityMode.PERCENT);
        densityPercent = new SettingDef.Decimal("density", PREFIX + "density", 10.0, 0.1, 100.0, 0.1,
                values -> values.get(densityMode) == DensityMode.PERCENT);
        densityCount = new SettingDef.Int("count", PREFIX + "count", 50, 1, ScatterSettings.MAX_PLACEMENTS, null,
                values -> values.get(densityMode) == DensityMode.COUNT);
        spacing = new SettingDef.Int("spacing", PREFIX + "spacing", 4, 0, ScatterSettings.MAX_SPACING);
        slope = new SettingDef.IntRange("filter.slope", PREFIX + "filter.slope", new SettingDef.IntSpan(0, MAX_SLOPE),
                0, MAX_SLOPE);
        elevation = new SettingDef.IntRange("filter.elevation", PREFIX + "filter.elevation",
                new SettingDef.IntSpan(MIN_Y, MAX_Y), MIN_Y, MAX_Y);
        substrate = new SettingDef.BlockList("filter.substrate", PREFIX + "filter.substrate", List.of(),
                MAX_SUBSTRATE_BLOCKS);
        turn0 = new SettingDef.Bool("turn.0", PREFIX + "turn.0", true);
        turn90 = new SettingDef.Bool("turn.90", PREFIX + "turn.90", true);
        turn180 = new SettingDef.Bool("turn.180", PREFIX + "turn.180", true);
        turn270 = new SettingDef.Bool("turn.270", PREFIX + "turn.270", true);
        mirror = new SettingDef.Bool("mirror", PREFIX + "mirror", true);
        allowInFluid = new SettingDef.Bool("fit.fluid", PREFIX + "fit.fluid", false);
        survive = new SettingDef.Bool("fit.survive", PREFIX + "fit.survive", ScatterSettings.Fit.DEFAULT.survive());
        support = new SettingDef.Int("fit.support", PREFIX + "fit.support",
                (int) Math.round(ScatterSettings.Fit.DEFAULT.minSupportFraction() * 100), 0, 100);
        columnHeight = new SettingDef.IntRange("fit.column", PREFIX + "fit.column",
                new SettingDef.IntSpan(ScatterSettings.ColumnHeight.ONE.min(), ScatterSettings.ColumnHeight.ONE.max()), 1,
                ScatterSettings.MAX_COLUMN_HEIGHT);
        schema = new SettingsSchema(List.of(
                new Section("", List.of(radius, seed, densityMode, densityPercent, densityCount, spacing)),
                new Section(PREFIX + "filters", List.of(slope, elevation, substrate), true),
                new Section(PREFIX + "transforms", List.of(turn0, turn90, turn180, turn270, mirror), true),
                new Section(PREFIX + "fit", List.of(survive, allowInFluid, support, columnHeight), true)));
    }

    public SettingsSchema schema() {
        return schema;
    }

    // ---- Mapping onto the preview ----

    /** The preview settings these values give. */
    public C2S.ScatterPreview.Settings previewSettings(SettingsValues values) {
        return new C2S.ScatterPreview.Settings(values.get(seed), values.get(spacing), density(values), surface(values),
                fit(values), columnHeight(values));
    }

    /** The column height range (kept within 1-{@value ScatterSettings#MAX_COLUMN_HEIGHT}). */
    public ScatterSettings.ColumnHeight columnHeight(SettingsValues values) {
        SettingDef.IntSpan span = values.get(columnHeight);
        int min = Math.max(1, Math.min(ScatterSettings.MAX_COLUMN_HEIGHT, span.min()));
        int max = Math.max(min, Math.min(ScatterSettings.MAX_COLUMN_HEIGHT, span.max()));
        return new ScatterSettings.ColumnHeight(min, max);
    }

    /** A share of the eligible columns (the percentage over 100), or a target placement count. */
    public ScatterSettings.Density density(SettingsValues values) {
        if (values.get(densityMode) == DensityMode.COUNT) {
            return new ScatterSettings.Density.Count(values.get(densityCount));
        }
        double fraction = Math.max(0, Math.min(1, values.get(densityPercent) / 100.0));
        return new ScatterSettings.Density.Fraction(fraction);
    }

    /** The surface filters; {@link SurfaceMask#ANY} when nothing is limited. */
    public SurfaceMask surface(SettingsValues values) {
        List<SurfaceMask> parts = new ArrayList<>();
        List<BlockDescriptor> blocks = values.get(substrate);
        if (!blocks.isEmpty()) {
            Set<NamespacedId> ids = new LinkedHashSet<>();
            for (BlockDescriptor block : blocks) ids.add(block.block());
            parts.add(new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.copyOf(ids))));
        }
        SettingDef.IntSpan y = values.get(elevation);
        if (y.min() > MIN_Y || y.max() < MAX_Y) parts.add(new SurfaceMask.Elevation(y.min(), y.max()));
        SettingDef.IntSpan steps = values.get(slope);
        if (steps.min() > 0 || steps.max() < MAX_SLOPE) parts.add(new SurfaceMask.Slope(steps.min(), steps.max()));
        return switch (parts.size()) {
            case 0 -> SurfaceMask.ANY;
            case 1 -> parts.get(0);
            default -> new SurfaceMask.And(parts);
        };
    }

    public ScatterSettings.Fit fit(SettingsValues values) {
        return new ScatterSettings.Fit(values.get(allowInFluid), values.get(support) / 100.0, values.get(survive));
    }

    /** The allowed turns and mirroring; with no turn ticked, placements keep their orientation. */
    public ScatterSettings.Transforms transforms(SettingsValues values) {
        return new ScatterSettings.Transforms(Math.max(1, turnMask(values)), values.get(mirror));
    }

    /** Bit k set when k clockwise quarter turns are allowed (0 when none is ticked). */
    public int turnMask(SettingsValues values) {
        int mask = 0;
        if (values.get(turn0)) mask |= 1;
        if (values.get(turn90)) mask |= 1 << 1;
        if (values.get(turn180)) mask |= 1 << 2;
        if (values.get(turn270)) mask |= 1 << 3;
        return mask;
    }

    /** Whether going from {@code before} to {@code after} changes the plan (the painting radius alone does not). */
    public boolean affectsPlan(SettingsValues before, SettingsValues after) {
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        return !before.with(radius, after.get(radius)).equals(after);
    }
}
