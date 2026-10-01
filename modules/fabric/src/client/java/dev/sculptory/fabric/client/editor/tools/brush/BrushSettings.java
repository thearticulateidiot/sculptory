package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.brush.WeatherSpec;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.protocol.v2.Limits;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One brush tool's settings: radius (1-32, capped by the server's {@code maxBrushRadius}), strength (0-1),
 * falloff and shape; the sculpting brushes' Mode ({@link SculptMode}); Paint's material block or Palette Paint's
 * weighted blocks (up to {@value #MAX_PALETTE_ENTRIES}), with the layer depth; a collapsed Mask section: the
 * brush's own rule list ({@link #maskRules}) and "only inside selection", with the
 * legacy mask keys of older presets kept hidden behind the rules; and a Symmetry section with the symmetry mode (the
 * centre is shared by the brushes and not a setting: {@link SymmetryCentre}). Each tool has its own instance (the
 * defaults differ).
 */
public final class BrushSettings {
    public static final int MAX_MASK_BLOCKS = 16;
    /**
     * Palette Paint's mix: as many blocks as a weighted pattern carries on the wire, which is also what a named palette
     * and the Scatter mix hold, so a palette always loads whole.
     */
    public static final int MAX_PALETTE_ENTRIES = Pattern.Weighted.MAX_ENTRIES;
    /** Surface heights the Y mask can express: the overworld's build range. */
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 319;
    /** Steepest step, in blocks, the slope mask can express. */
    public static final int MAX_SLOPE = 16;
    public static final double STRENGTH_STEP = 0.05;

    private static final String PREFIX = "sculptory.setting.brush.";
    /** The legacy mask keys are never shown. */
    private static final Predicate<SettingsValues> HIDDEN = values -> false;

    public final SettingDef.Int radius;
    public final SettingDef.Decimal strength;
    public final SettingDef.Enum<Falloff> falloff;
    public final SettingDef.Enum<Shape> shape;
    /**
     * Raise, Lower, Smooth and Flatten only, otherwise {@code null}: "Surface (any direction)" (the default) or "Terrain (from above)", the column brush as before.
     */
    public final SettingDef.Enum<SculptMode> mode;
    /** The Weather brush only, otherwise {@code null}: what it does (Erode, Fill in, Roughen, Melt). */
    public final SettingDef.Enum<WeatherSpec.Mode> weatherMode;
    /** Paint only, otherwise {@code null}. */
    public final SettingDef.Block material;
    /** Palette Paint only, otherwise {@code null}. */
    public final SettingDef.WeightedBlocks palette;
    /**
     * Palette Paint only, otherwise {@code null}: how the mix is laid out (Random, Patches, Gradient,
     * Steepness).
     */
    public final MixPatternSettings mixPattern;
    /** Paint and Palette Paint only, otherwise {@code null}. */
    public final SettingDef.Int depth;
    /**
     * The brush's own mask as rules, tested at the surface block. Unset (a preset
     * saved before rules), it stands for the legacy keys below, which are kept, hidden, and read only then.
     */
    public final SettingDef.MaskRules maskRules;
    /** Legacy (hidden; read while {@link #maskRules} is unset): surface blocks. */
    public final SettingDef.BlockList maskBlocks;
    /** Legacy (hidden): match {@link #maskBlocks} by exact state instead of by block type. */
    public final SettingDef.Bool maskExactStates;
    public final SettingDef.IntRange maskY;
    public final SettingDef.IntRange maskSlope;
    /** Legacy (hidden): inverts the blocks, Y and slope mask together; no effect while none is set. */
    public final SettingDef.Bool maskInvert;
    /** Writes only inside the selection box (captured when the press starts). */
    public final SettingDef.Bool insideSelection;
    /** How each dab is replicated (Off by default, and for presets saved before symmetry existed). */
    public final SettingDef.Enum<Symmetry.Mode> symmetry;
    private final SettingsSchema schema;

    private BrushSettings(BrushTool tool) {
        Objects.requireNonNull(tool);
        boolean painting = tool == BrushTool.PAINT || tool == BrushTool.PALETTE;
        radius = new SettingDef.Int("radius", PREFIX + "radius", painting ? 4 : 5, 1, 32, Limits::maxBrushRadius,
                SettingDef.ALWAYS);
        strength = new SettingDef.Decimal("strength", PREFIX + "strength", defaultStrength(tool), 0, 1, STRENGTH_STEP);
        falloff = new SettingDef.Enum<>("falloff", PREFIX + "falloff", Falloff.class,
                painting ? Falloff.CONSTANT : Falloff.SMOOTH);
        shape = new SettingDef.Enum<>("shape", PREFIX + "shape", Shape.class, Shape.CIRCLE);
        mode = SculptMode.surfaceTool(tool)
                ? new SettingDef.Enum<>("mode", PREFIX + "mode", SculptMode.class, SculptMode.SURFACE)
                : null;
        weatherMode = tool == BrushTool.WEATHER
                ? new SettingDef.Enum<>("weather.mode", PREFIX + "weather.mode", WeatherSpec.Mode.class,
                        WeatherSpec.Mode.ERODE)
                : null;
        material = tool == BrushTool.PAINT
                ? new SettingDef.Block("material", PREFIX + "material", block("minecraft:coarse_dirt"))
                : null;
        palette = tool == BrushTool.PALETTE
                ? new SettingDef.WeightedBlocks("palette", PREFIX + "palette", List.of(
                        new SettingDef.WeightedBlock(block("minecraft:grass_block"), 4),
                        new SettingDef.WeightedBlock(block("minecraft:coarse_dirt"), 2),
                        new SettingDef.WeightedBlock(block("minecraft:moss_block"), 1)), MAX_PALETTE_ENTRIES)
                : null;
        mixPattern = tool == BrushTool.PALETTE ? new MixPatternSettings(SettingDef.ALWAYS, true) : null;
        depth = painting ? new SettingDef.Int("depth", PREFIX + "depth", 1, 1, 32) : null;
        maskRules = new SettingDef.MaskRules("mask.rules", PREFIX + "mask.rules", this::legacyRules,
                SettingDef.ALWAYS);
        // The mask keys of presets saved before rules: kept (and saved) as they are, never shown.
        maskBlocks = new SettingDef.BlockList("mask.blocks", PREFIX + "mask.blocks", List.of(), MAX_MASK_BLOCKS, HIDDEN);
        maskExactStates = new SettingDef.Bool("mask.exact", PREFIX + "mask.exact", false, HIDDEN);
        maskY = new SettingDef.IntRange("mask.y", PREFIX + "mask.y", new SettingDef.IntSpan(MIN_Y, MAX_Y), MIN_Y, MAX_Y,
                HIDDEN);
        maskSlope = new SettingDef.IntRange("mask.slope", PREFIX + "mask.slope", new SettingDef.IntSpan(0, MAX_SLOPE),
                0, MAX_SLOPE, HIDDEN);
        maskInvert = new SettingDef.Bool("mask.invert", PREFIX + "mask.invert", false, HIDDEN);
        insideSelection = new SettingDef.Bool("mask.selection", PREFIX + "mask.selection", false);
        symmetry = new SettingDef.Enum<>("symmetry", PREFIX + "symmetry", Symmetry.Mode.class, Symmetry.Mode.OFF);

        List<SettingDef<?>> main = new ArrayList<>(List.of(radius, strength, falloff, shape));
        if (mode != null) main.add(mode);
        if (weatherMode != null) main.add(weatherMode);
        if (material != null) main.add(material);
        if (palette != null) main.add(palette);
        if (mixPattern != null) main.addAll(mixPattern.defs());
        if (depth != null) main.add(depth);
        schema = new SettingsSchema(List.of(
                new Section("", main),
                new Section(PREFIX + "mask",
                        List.of(maskRules, insideSelection, maskBlocks, maskExactStates, maskY, maskSlope, maskInvert), true),
                new Section(PREFIX + "symmetry_section", List.of(symmetry), true)));
    }

    public static BrushSettings forTool(BrushTool tool) {
        return new BrushSettings(tool);
    }

    public SettingsSchema schema() {
        return schema;
    }

    /**
     * The surface mask the Mask section describes, in the rule form ({@link SurfaceMask.Rules}): the rule list once set,
     * else the legacy keys as rules ({@link #legacyRules}, exactly what {@link #legacyMask} tests);
     * {@link SurfaceMask#ANY} when nothing is limited. "Only inside selection" is not part of the mask: it is the
     * spec's clip box.
     */
    public SurfaceMask mask(SettingsValues values, StateSpace states) {
        Objects.requireNonNull(states);
        SettingDef.MaskValue set = values.get(maskRules);
        EditMask rules = set.set() ? set.mask() : legacyRules(values, states);
        return rules.isOff() ? SurfaceMask.ANY : new SurfaceMask.Rules(rules);
    }

    /**
     * The legacy keys as rules, testing exactly what {@link #legacyMask} tests: "Only on these surface blocks" as "is
     * one of" (by type, or with "Match exact block states" each block's state as {@code states} resolves it, so a block
     * listed without its properties names its default state, as before), the height and slope ranges when narrowed,
     * "Invert mask" as invert-all (and nothing while no part is set). With {@code states} {@code null} (for display) an
     * exact block listed without properties is named as the block.
     */
    public EditMask legacyRules(SettingsValues values, StateSpace states) {
        List<MaskEntry> entries = new ArrayList<>();
        List<BlockDescriptor> blocks = values.get(maskBlocks);
        if (!blocks.isEmpty()) {
            Set<BlockSet.Entry> set = new LinkedHashSet<>();
            boolean exact = values.get(maskExactStates);
            for (BlockDescriptor block : blocks) {
                set.add(exact ? exactEntry(block, states) : new BlockSet.Block(block.block()));
            }
            entries.add(MaskEntry.of(new MaskRule.Is(new BlockSet(List.copyOf(set)))));
        }
        SettingDef.IntSpan y = values.get(maskY);
        if (y.min() > MIN_Y || y.max() < MAX_Y) entries.add(MaskEntry.of(new MaskRule.Height(y.min(), y.max())));
        SettingDef.IntSpan slope = values.get(maskSlope);
        if (slope.min() > 0 || slope.max() < MAX_SLOPE) {
            entries.add(MaskEntry.of(new MaskRule.Slope(slope.min(), slope.max())));
        }
        return new EditMask(entries, values.get(maskInvert) && !entries.isEmpty());
    }

    /** An exact-state entry of the legacy list: the state {@code states} resolves it to, with every property. */
    private static BlockSet.Entry exactEntry(BlockDescriptor block, StateSpace states) {
        BlockDescriptor exact = block;
        if (states != null) {
            int handle = states.resolve(block);
            if (handle >= 0) exact = states.describe(handle);
        }
        return exact.properties().isEmpty() ? new BlockSet.Block(exact.block()) : new BlockSet.State(exact);
    }

    /**
     * The surface mask the legacy keys described before rules: blocks by type, or with "Match exact block states" by
     * the listed states resolved in {@code states} (states it does not know match nothing; see
     * {@link #unknownMaskState}); "Invert mask" wraps the whole mask in {@link SurfaceMask.Not}, and does nothing while
     * the mask is {@link SurfaceMask#ANY}. Kept to prove {@link #mask} gives the same result.
     */
    public SurfaceMask legacyMask(SettingsValues values, StateSpace states) {
        Objects.requireNonNull(states);
        List<SurfaceMask> parts = new ArrayList<>();
        List<BlockDescriptor> blocks = values.get(maskBlocks);
        if (!blocks.isEmpty()) {
            CellMask cells;
            if (values.get(maskExactStates)) {
                Set<Integer> handles = new LinkedHashSet<>();
                for (BlockDescriptor block : blocks) {
                    int handle = states.resolve(block);
                    if (handle >= 0) handles.add(handle);
                }
                cells = new CellMask.States(handles.stream().mapToInt(Integer::intValue).toArray());
            } else {
                Set<NamespacedId> ids = new LinkedHashSet<>();
                for (BlockDescriptor block : blocks) ids.add(block.block());
                cells = new CellMask.Blocks(List.copyOf(ids));
            }
            parts.add(new SurfaceMask.SurfaceBlocks(cells));
        }
        SettingDef.IntSpan y = values.get(maskY);
        if (y.min() > MIN_Y || y.max() < MAX_Y) {
            parts.add(new SurfaceMask.Elevation(y.min(), y.max()));
        }
        SettingDef.IntSpan slope = values.get(maskSlope);
        if (slope.min() > 0 || slope.max() < MAX_SLOPE) {
            parts.add(new SurfaceMask.Slope(slope.min(), slope.max()));
        }
        SurfaceMask mask = switch (parts.size()) {
            case 0 -> SurfaceMask.ANY;
            case 1 -> parts.get(0);
            default -> new SurfaceMask.And(parts);
        };
        return values.get(maskInvert) && !(mask instanceof SurfaceMask.Any) ? new SurfaceMask.Not(mask) : mask;
    }

    /**
     * With "Match exact block states" on, the first listed state {@code states} does not know (it matches
     * nothing), so the tool can say so; empty otherwise.
     */
    public Optional<BlockDescriptor> unknownMaskState(SettingsValues values, StateSpace states) {
        if (!values.get(maskExactStates)) return Optional.empty();
        for (BlockDescriptor block : values.get(maskBlocks)) {
            if (states.resolve(block) < 0) return Optional.of(block);
        }
        return Optional.empty();
    }

    private static double defaultStrength(BrushTool tool) {
        return switch (tool) {
            case RAISE, LOWER -> 0.5;
            case SMOOTH, FLATTEN, WEATHER -> 0.6;
            case PAINT, PALETTE, SHAPE -> 1.0;
        };
    }

    private static BlockDescriptor block(String id) {
        return BlockDescriptor.of(new NamespacedId(id));
    }
}
