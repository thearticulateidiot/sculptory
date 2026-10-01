package dev.sculptory.fabric.client.editor.tools.generate;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.generate.LineKernel;
import dev.sculptory.core.generate.RoadKernel;
import dev.sculptory.core.generate.RoofKernel;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tools.brush.BrushSettings;
import java.util.List;
import java.util.function.Predicate;

/**
 * The Generate tool's settings ({@link GenerateTool}): which generator, then the Path generator's (width, material or
 * mix, border, height mode, level across, fill below, clear above) and the Roof generator's (style, ridge or low side,
 * pitch, overhang, thickness, gable walls, inside, the three materials) and the Line's (path: straight, curve or hanging,
 * with its sag; thickness and profile; a block or a mix). Each generator's settings show only while it is chosen; presets
 * save them all.
 */
public final class GenerateSettings {
    /** What the tool generates. */
    public enum Kind { PATH, ROOF, LINE }

    /** What a road is paved with: one block, or a weighted mix. */
    public enum Material { BLOCK, PALETTE }

    private static final String PREFIX = "sculptory.setting.generate.";
    private static final Predicate<SettingsValues> PATH = values -> values.get(GenerateSettings.KIND) == Kind.PATH;
    private static final Predicate<SettingsValues> ROOF = values -> values.get(GenerateSettings.KIND) == Kind.ROOF;
    private static final Predicate<SettingsValues> LINE = values -> values.get(GenerateSettings.KIND) == Kind.LINE;

    public static final SettingDef.Enum<Kind> KIND = new SettingDef.Enum<>("kind", PREFIX + "kind", Kind.class, Kind.PATH);

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

    public static final SettingsSchema SCHEMA = new SettingsSchema(List.of(
            new Section("", List.of(KIND)),
            new Section(PREFIX + "path_section", List.of(WIDTH, MATERIAL, BLOCK, PALETTE, BORDER, BORDER_BLOCK, HEIGHT,
                    LEVEL_ACROSS, FILL_BELOW, CLEAR_ABOVE)),
            new Section(PREFIX + "roof_section", List.of(STYLE, RIDGE, LOW_SIDE, PITCH, OVERHANG, THICKNESS, GABLE_WALLS,
                    INSIDE, STAIRS, SLAB, FULL)),
            new Section(PREFIX + "line_section", List.of(LINE_PATH, LINE_SAG, LINE_THICKNESS, LINE_PROFILE, LINE_MATERIAL,
                    LINE_BLOCK, LINE_PALETTE))));

    private GenerateSettings() {}

    /** The roof's materials as set. */
    public static RoofKernel.Materials materials(SettingsValues values) {
        return new RoofKernel.Materials(values.get(STAIRS), values.get(SLAB), values.get(FULL));
    }

    private static BlockDescriptor block(String id) {
        return BlockDescriptor.of(new NamespacedId(id));
    }
}
