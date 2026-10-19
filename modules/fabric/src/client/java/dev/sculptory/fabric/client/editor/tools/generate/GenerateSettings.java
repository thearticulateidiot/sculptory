package dev.sculptory.fabric.client.editor.tools.generate;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.generate.LineKernel;
import dev.sculptory.core.generate.RoadKernel;
import dev.sculptory.core.generate.RoofKernel;
import dev.sculptory.core.generate.SmartSurfaceKernel;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.fabric.client.editor.heightmap.HeightmapLibrary;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tools.brush.BrushSettings;
import dev.sculptory.fabric.client.editor.tools.brush.ShapeSettings;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The Generate tool's settings ({@link GenerateTool}): which generator, then the Path generator's (width, material or
 * mix, border, height mode, level across, fill below, clear above) and the Roof generator's (style, ridge or low side,
 * pitch, overhang, thickness, gable walls, inside, the three materials) and the Line's (path: straight, curve or hanging,
 * with its sag; thickness and profile; a block or a mix) and the Heightmap import's (picture, Min Y and Max Y, turn,
 * invert). Each generator's settings show only while it is chosen; presets
 * save them all.
 */
public final class GenerateSettings {
    /**
     * What the tool generates. Protocol 6 appends Sweep (the selection swept along a path), Stamp (the clipboard or an
     * asset copied at each clicked point) and Heightmap (a height image over the selection), all built now; then
     * Smart Surface (a terrain-like sheet filled inside a closed outline of points). Presets save the name.
     */
    public enum Kind { PATH, ROOF, LINE, SWEEP, STAMP, HEIGHTMAP, SMART_SURFACE }

    /**
     * The kinds not built yet, with why they can't be chosen (a key such as
     * {@code sculptory.setting.generate.kind.coming_soon}): none now, Smart Surface being built.
     */
    private static final Map<Kind, String> KINDS_NOT_BUILT = Map.of();
    /** The kinds not built yet that the Kind dropdown leaves out altogether (not promised): none now. */
    private static final Set<Kind> KINDS_HIDDEN = Set.of();

    /** The Heightmap import's turn of its picture, clockwise seen from above, in quarter turns. */
    public enum Turn {
        NONE, QUARTER, HALF, THREE_QUARTERS;

        /** The quarter turns clockwise. */
        public int quarterTurns() {
            return ordinal();
        }
    }

    /** The lowest and highest surface the Heightmap import's Min Y and Max Y can name. */
    public static final int MIN_HEIGHTMAP_Y = -2048;
    public static final int MAX_HEIGHTMAP_Y = 2047;

    /** What a road is paved with: one block, or a weighted mix. */
    public enum Material { BLOCK, PALETTE }

    /** What Stamp copies: the clipboard, or the asset last picked in the Library with its Stamp button. */
    public enum StampSource { CLIPBOARD, LIBRARY }

    private static final String PREFIX = "sculptory.setting.generate.";
    private static final Predicate<SettingsValues> PATH = values -> values.get(GenerateSettings.KIND) == Kind.PATH;
    private static final Predicate<SettingsValues> ROOF = values -> values.get(GenerateSettings.KIND) == Kind.ROOF;
    private static final Predicate<SettingsValues> LINE = values -> values.get(GenerateSettings.KIND) == Kind.LINE;
    private static final Predicate<SettingsValues> SWEEP = values -> values.get(GenerateSettings.KIND) == Kind.SWEEP;
    private static final Predicate<SettingsValues> STAMP = values -> values.get(GenerateSettings.KIND) == Kind.STAMP;
    private static final Predicate<SettingsValues> HEIGHTMAP_KIND = values -> values.get(GenerateSettings.KIND) == Kind.HEIGHTMAP;
    private static final Predicate<SettingsValues> SMART = values -> values.get(GenerateSettings.KIND) == Kind.SMART_SURFACE;

    public static final SettingDef.Enum<Kind> KIND = new SettingDef.Enum<>("kind", PREFIX + "kind", Kind.class, Kind.PATH,
            SettingDef.ALWAYS, KINDS_NOT_BUILT, KINDS_HIDDEN);

    // Path
    public static final SettingDef.Int WIDTH = new SettingDef.Int("path.width", PREFIX + "width", 3, RoadKernel.MIN_WIDTH,
            RoadKernel.MAX_WIDTH, null, PATH);
    public static final SettingDef.Enum<Material> MATERIAL = new SettingDef.Enum<>("path.material", PREFIX + "material",
            Material.class, Material.BLOCK, PATH);
    public static final SettingDef.Block BLOCK = new SettingDef.Block("path.block", PREFIX + "block",
            block("minecraft:cobblestone"), values -> PATH.test(values) && values.get(GenerateSettings.MATERIAL) == Material.BLOCK);
    public static final SettingDef.WeightedBlocks PALETTE = new SettingDef.WeightedBlocks("path.palette", PREFIX + "palette",
            List.of(new SettingDef.WeightedBlock(block("minecraft:cobblestone"), 4),
                    new SettingDef.WeightedBlock(block("minecraft:stone_bricks"), 2),
                    new SettingDef.WeightedBlock(block("minecraft:andesite"), 1)),
            BrushSettings.MAX_PALETTE_ENTRIES,
            values -> PATH.test(values) && values.get(GenerateSettings.MATERIAL) == Material.PALETTE);
    public static final SettingDef.Bool BORDER = new SettingDef.Bool("path.border", PREFIX + "border", false, PATH);
    public static final SettingDef.Block BORDER_BLOCK = new SettingDef.Block("path.border.block", PREFIX + "border_block",
            block("minecraft:stone_bricks"), values -> PATH.test(values) && values.get(GenerateSettings.BORDER));
    public static final SettingDef.Enum<RoadKernel.HeightMode> HEIGHT = new SettingDef.Enum<>("path.height", PREFIX + "height",
            RoadKernel.HeightMode.class, RoadKernel.HeightMode.FOLLOW_TERRAIN, PATH);
    public static final SettingDef.Bool LEVEL_ACROSS = new SettingDef.Bool("path.level", PREFIX + "level_across", true,
            values -> PATH.test(values) && values.get(GenerateSettings.HEIGHT) == RoadKernel.HeightMode.FOLLOW_TERRAIN);
    public static final SettingDef.Int FILL_BELOW = new SettingDef.Int("path.fill", PREFIX + "fill_below", 0, 0,
            RoadKernel.MAX_FILL_BELOW, null, PATH);
    public static final SettingDef.Int CLEAR_ABOVE = new SettingDef.Int("path.clear", PREFIX + "clear_above", 0, 0,
            RoadKernel.MAX_CLEAR_ABOVE, null, PATH);
    /**
     * On sloping stretches the step cells get stairs (facing along the climb) or slabs of the road's block family instead
     * of full blocks, when it has them ({@code core.generate.SlopeSteps}; the family from {@code StairSlabVariants}).
     */
    public static final SettingDef.Bool PATH_STAIRS_AND_SLABS = new SettingDef.Bool("path.stairs_slabs",
            PREFIX + "path_stairs_slabs", false, PATH);

    // Roof
    public static final SettingDef.Enum<RoofKernel.Style> STYLE = new SettingDef.Enum<>("roof.style", PREFIX + "style",
            RoofKernel.Style.class, RoofKernel.Style.GABLE, ROOF);
    public static final SettingDef.Enum<RoofKernel.Ridge> RIDGE = new SettingDef.Enum<>("roof.ridge", PREFIX + "ridge",
            RoofKernel.Ridge.class, RoofKernel.Ridge.AUTO,
            values -> ROOF.test(values) && values.get(GenerateSettings.STYLE) == RoofKernel.Style.GABLE);
    public static final SettingDef.Enum<RoofKernel.Side> LOW_SIDE = new SettingDef.Enum<>("roof.low_side", PREFIX + "low_side",
            RoofKernel.Side.class, RoofKernel.Side.NORTH,
            values -> ROOF.test(values) && values.get(GenerateSettings.STYLE) == RoofKernel.Style.SHED);
    public static final SettingDef.Enum<RoofKernel.Pitch> PITCH = new SettingDef.Enum<>("roof.pitch", PREFIX + "pitch",
            RoofKernel.Pitch.class, RoofKernel.Pitch.NORMAL, ROOF);
    public static final SettingDef.Int OVERHANG = new SettingDef.Int("roof.overhang", PREFIX + "overhang", 1, 0,
            RoofKernel.MAX_OVERHANG, null, ROOF);
    public static final SettingDef.Int THICKNESS = new SettingDef.Int("roof.thickness", PREFIX + "thickness", 1,
            RoofKernel.MIN_THICKNESS, RoofKernel.MAX_THICKNESS, null, ROOF);
    public static final SettingDef.Bool GABLE_WALLS = new SettingDef.Bool("roof.gable_walls", PREFIX + "gable_walls", true,
            values -> ROOF.test(values) && values.get(GenerateSettings.STYLE) != RoofKernel.Style.HIP);
    public static final SettingDef.Enum<RoofKernel.Inside> INSIDE = new SettingDef.Enum<>("roof.inside", PREFIX + "inside",
            RoofKernel.Inside.class, RoofKernel.Inside.LEAVE, ROOF);
    public static final SettingDef.Block STAIRS = new SettingDef.Block("roof.stairs", PREFIX + "stairs",
            block("minecraft:oak_stairs"), ROOF);
    public static final SettingDef.Block SLAB = new SettingDef.Block("roof.slab", PREFIX + "slab", block("minecraft:oak_slab"),
            ROOF);
    public static final SettingDef.Block FULL = new SettingDef.Block("roof.full", PREFIX + "full", block("minecraft:oak_planks"),
            ROOF);

    // Line
    public static final SettingDef.Enum<PathKind> LINE_PATH = new SettingDef.Enum<>("line.path", PREFIX + "line_path",
            PathKind.class, PathKind.STRAIGHT, LINE);
    public static final SettingDef.Int LINE_SAG = new SettingDef.Int("line.sag", PREFIX + "line_sag", 4, 0,
            (int) PathSpec.MAX_SAG, null, values -> LINE.test(values) && values.get(GenerateSettings.LINE_PATH) == PathKind.HANGING);
    public static final SettingDef.Int LINE_THICKNESS = new SettingDef.Int("line.thickness", PREFIX + "line_thickness", 1,
            LineKernel.MIN_THICKNESS, LineKernel.MAX_THICKNESS, null, LINE);
    public static final SettingDef.Enum<LineKernel.Profile> LINE_PROFILE = new SettingDef.Enum<>("line.profile",
            PREFIX + "line_profile", LineKernel.Profile.class, LineKernel.Profile.ROUND,
            values -> LINE.test(values) && values.get(GenerateSettings.LINE_THICKNESS) > 1);
    public static final SettingDef.Enum<Material> LINE_MATERIAL = new SettingDef.Enum<>("line.material",
            PREFIX + "line_material", Material.class, Material.BLOCK, LINE);
    public static final SettingDef.Block LINE_BLOCK = new SettingDef.Block("line.block", PREFIX + "line_block",
            block("minecraft:stone_bricks"),
            values -> LINE.test(values) && values.get(GenerateSettings.LINE_MATERIAL) == Material.BLOCK);
    public static final SettingDef.WeightedBlocks LINE_PALETTE = new SettingDef.WeightedBlocks("line.palette",
            PREFIX + "line_palette",
            List.of(new SettingDef.WeightedBlock(block("minecraft:stone_bricks"), 3),
                    new SettingDef.WeightedBlock(block("minecraft:mossy_stone_bricks"), 1),
                    new SettingDef.WeightedBlock(block("minecraft:cracked_stone_bricks"), 1)),
            BrushSettings.MAX_PALETTE_ENTRIES,
            values -> LINE.test(values) && values.get(GenerateSettings.LINE_MATERIAL) == Material.PALETTE);
    /**
     * Every column the line occupies is filled down to the terrain below (the ground rule Road uses), so walls, supports
     * and ridges come out solid ({@code core.generate.GroundFill}, each fill cell of the line's material); capped like
     * other generated volumes.
     */
    public static final SettingDef.Bool LINE_EXTEND_TO_GROUND = new SettingDef.Bool("line.extend_to_ground",
            PREFIX + "line_extend_to_ground", false, LINE);
    /**
     * Stairs and slabs on the line's sloping stretches (its top and its underside), as Road's
     * {@link #PATH_STAIRS_AND_SLABS}.
     */
    public static final SettingDef.Bool LINE_STAIRS_AND_SLABS = new SettingDef.Bool("line.stairs_slabs",
            PREFIX + "line_stairs_slabs", false, LINE);

    // Sweep
    public static final SettingDef.Enum<PathKind> SWEEP_PATH = new SettingDef.Enum<>("sweep.path", PREFIX + "sweep_path",
            PathKind.class, PathKind.CURVE, SWEEP);
    public static final SettingDef.Int SWEEP_SAG = new SettingDef.Int("sweep.sag", PREFIX + "sweep_sag", 4, 0,
            (int) PathSpec.MAX_SAG, null, values -> SWEEP.test(values) && values.get(GenerateSettings.SWEEP_PATH) == PathKind.HANGING);
    public static final SettingDef.Bool SWEEP_FOLLOW_SLOPE = new SettingDef.Bool("sweep.follow_slope",
            PREFIX + "sweep_follow_slope", false, SWEEP);
    /**
     * The sweep's columns filled down to the terrain, as the Line's {@link #LINE_EXTEND_TO_GROUND}; each column's fill
     * takes the state of its lowest swept cell.
     */
    public static final SettingDef.Bool SWEEP_EXTEND_TO_GROUND = new SettingDef.Bool("sweep.extend_to_ground",
            PREFIX + "sweep_extend_to_ground", false, SWEEP);

    // Stamp
    public static final SettingDef.Enum<StampSource> STAMP_SOURCE = new SettingDef.Enum<>("stamp.source",
            PREFIX + "stamp_source", StampSource.class, StampSource.CLIPBOARD, STAMP);
    // Heightmap
    public static final SettingDef.Heightmap HEIGHTMAP_IMAGE = new SettingDef.Heightmap("heightmap.image",
            PREFIX + "heightmap_image", HeightmapLibrary.DEFAULT, HEIGHTMAP_KIND);
    /** The surface where the picture is black; set to the selection's bottom whenever the selection changes. */
    public static final SettingDef.Int HEIGHTMAP_MIN_Y = new SettingDef.Int("heightmap.min_y", PREFIX + "heightmap_min_y", 60,
            MIN_HEIGHTMAP_Y, MAX_HEIGHTMAP_Y, null, HEIGHTMAP_KIND);
    /** The surface where the picture is white; set to the selection's top whenever the selection changes. */
    public static final SettingDef.Int HEIGHTMAP_MAX_Y = new SettingDef.Int("heightmap.max_y", PREFIX + "heightmap_max_y", 80,
            MIN_HEIGHTMAP_Y, MAX_HEIGHTMAP_Y, null, HEIGHTMAP_KIND);
    public static final SettingDef.Enum<Turn> HEIGHTMAP_TURN = new SettingDef.Enum<>("heightmap.turn",
            PREFIX + "heightmap_turn", Turn.class, Turn.NONE, HEIGHTMAP_KIND);
    public static final SettingDef.Bool HEIGHTMAP_INVERT = new SettingDef.Bool("heightmap.invert", PREFIX + "heightmap_invert",
            false, HEIGHTMAP_KIND);

    // Smart Surface
    /** The deepest top layer (blocks); 0 leaves the top layer out (all body). */
    public static final int SMART_MAX_TOP_DEPTH = SmartSurfaceKernel.MAX_TOP_DEPTH;
    /** The thickest sheet (blocks) when Extend to Ground is off. */
    public static final int SMART_MAX_THICKNESS = 64;
    /** Flat triangles of a Delaunay triangulation of the points instead of the smooth surface through them. */
    public static final SettingDef.Bool SMART_FACETED = new SettingDef.Bool("smart.faceted", PREFIX + "smart_faceted", false,
            SMART);
    /** The top layer's blocks: the active block (the top bar's) or the weighted mix below. */
    public static final SettingDef.Enum<ShapeSettings.Blocks> SMART_TOP_BLOCKS = new SettingDef.Enum<>("smart.top.blocks",
            PREFIX + "smart_top_blocks", ShapeSettings.Blocks.class, ShapeSettings.Blocks.ACTIVE_BLOCK, SMART);
    public static final SettingDef.WeightedBlocks SMART_TOP_PALETTE = new SettingDef.WeightedBlocks("smart.top.palette",
            PREFIX + "smart_top_palette",
            List.of(new SettingDef.WeightedBlock(block("minecraft:grass_block"), 4),
                    new SettingDef.WeightedBlock(block("minecraft:moss_block"), 1)),
            BrushSettings.MAX_PALETTE_ENTRIES,
            values -> SMART.test(values) && values.get(GenerateSettings.SMART_TOP_BLOCKS) == ShapeSettings.Blocks.PALETTE);
    /** How many blocks deep the top layer is, from each column's surface down. */
    public static final SettingDef.Int SMART_TOP_DEPTH = new SettingDef.Int("smart.top.depth", PREFIX + "smart_top_depth", 1,
            0, SMART_MAX_TOP_DEPTH, null, SMART);
    /** The body's blocks (under the top layer): the active block or the weighted mix below. */
    public static final SettingDef.Enum<ShapeSettings.Blocks> SMART_BODY_BLOCKS = new SettingDef.Enum<>("smart.body.blocks",
            PREFIX + "smart_body_blocks", ShapeSettings.Blocks.class, ShapeSettings.Blocks.ACTIVE_BLOCK, SMART);
    public static final SettingDef.WeightedBlocks SMART_BODY_PALETTE = new SettingDef.WeightedBlocks("smart.body.palette",
            PREFIX + "smart_body_palette",
            List.of(new SettingDef.WeightedBlock(block("minecraft:dirt"), 3),
                    new SettingDef.WeightedBlock(block("minecraft:coarse_dirt"), 1)),
            BrushSettings.MAX_PALETTE_ENTRIES,
            values -> SMART.test(values) && values.get(GenerateSettings.SMART_BODY_BLOCKS) == ShapeSettings.Blocks.PALETTE);
    /** Each column filled down to the terrain under it (Road's ground rule); off: a sheet {@link #SMART_THICKNESS} thick. */
    public static final SettingDef.Bool SMART_EXTEND_TO_GROUND = new SettingDef.Bool("smart.extend_to_ground",
            PREFIX + "smart_extend_to_ground", false, SMART);
    public static final SettingDef.Int SMART_THICKNESS = new SettingDef.Int("smart.thickness", PREFIX + "smart_thickness", 1, 1,
            SMART_MAX_THICKNESS, null, values -> SMART.test(values) && !values.get(GenerateSettings.SMART_EXTEND_TO_GROUND));
    /** Gap-free steep slopes: each column reaches down to at least its lowest neighbour's surface + 1. */
    public static final SettingDef.Bool SMART_GAP_FREE = new SettingDef.Bool("smart.gap_free", PREFIX + "smart_gap_free", true,
            values -> SMART.test(values) && !values.get(GenerateSettings.SMART_EXTEND_TO_GROUND));
    /** Fill air only: blocks already there stay. */
    public static final SettingDef.Bool SMART_KEEP_EXISTING = new SettingDef.Bool("smart.keep_existing",
            PREFIX + "smart_keep_existing", false, SMART);
    /** The preview also draws the surface's triangles. */
    public static final SettingDef.Bool SMART_MESH_VIEW = new SettingDef.Bool("smart.mesh_view", PREFIX + "smart_mesh_view",
            false, SMART);

    public static final SettingsSchema SCHEMA = new SettingsSchema(List.of(
            new Section("", List.of(KIND)),
            new Section(PREFIX + "path_section", List.of(WIDTH, MATERIAL, BLOCK, PALETTE, BORDER, BORDER_BLOCK, HEIGHT,
                    LEVEL_ACROSS, FILL_BELOW, CLEAR_ABOVE, PATH_STAIRS_AND_SLABS)),
            new Section(PREFIX + "roof_section", List.of(STYLE, RIDGE, LOW_SIDE, PITCH, OVERHANG, THICKNESS, GABLE_WALLS,
                    INSIDE, STAIRS, SLAB, FULL)),
            new Section(PREFIX + "line_section", List.of(LINE_PATH, LINE_SAG, LINE_THICKNESS, LINE_PROFILE, LINE_MATERIAL,
                    LINE_BLOCK, LINE_PALETTE, LINE_EXTEND_TO_GROUND, LINE_STAIRS_AND_SLABS)),
            new Section(PREFIX + "sweep_section", List.of(SWEEP_PATH, SWEEP_SAG, SWEEP_FOLLOW_SLOPE, SWEEP_EXTEND_TO_GROUND)),
            new Section(PREFIX + "stamp_section", List.of(STAMP_SOURCE)),
            new Section(PREFIX + "heightmap_section", List.of(HEIGHTMAP_IMAGE, HEIGHTMAP_MIN_Y, HEIGHTMAP_MAX_Y,
                    HEIGHTMAP_TURN, HEIGHTMAP_INVERT)),
            new Section(PREFIX + "smart_surface_section", List.of(SMART_FACETED, SMART_TOP_BLOCKS, SMART_TOP_PALETTE,
                    SMART_TOP_DEPTH, SMART_BODY_BLOCKS, SMART_BODY_PALETTE, SMART_EXTEND_TO_GROUND, SMART_THICKNESS,
                    SMART_GAP_FREE, SMART_KEEP_EXISTING, SMART_MESH_VIEW))));

    private GenerateSettings() {}

    /** The roof's materials as set. */
    public static RoofKernel.Materials materials(SettingsValues values) {
        return new RoofKernel.Materials(values.get(STAIRS), values.get(SLAB), values.get(FULL));
    }

    private static BlockDescriptor block(String id) {
        return BlockDescriptor.of(new NamespacedId(id));
    }
}
