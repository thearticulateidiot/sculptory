package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.region.Facing;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.protocol.v2.Limits;
import java.util.ArrayList;
import java.util.List;

/**
 * The Shape brush's settings ({@link ShapeBrushTool}): what a click does (Draw: place shapes, or add a point to a line
 * swept with the shape, with the line's path and sag), the solid (sphere, cylinder, cone, cube or pyramid; a cone's and a
 * pyramid's tip points along the facing, as a selection shape's does), its radius (1-32, capped by the server's
 * {@code maxBrushRadius}) and height (the diameter unless set, 1-65, capped at the server's largest diameter), facing,
 * anchor, mode, hollow thickness, the blocks (the active block or a weighted mix of up to
 * {@value BrushSettings#MAX_PALETTE_ENTRIES}), "Only inside selection", and a Symmetry section like the terrain
 * brushes'. Height and facing show for the solids that use them; the blocks do not show while carving.
 */
public final class ShapeSettings {
    /** The facing setting: a fixed one, or the face the press begins on. */
    public enum FacingChoice {
        UP(Facing.UP),
        DOWN(Facing.DOWN),
        NORTH(Facing.NORTH),
        SOUTH(Facing.SOUTH),
        EAST(Facing.EAST),
        WEST(Facing.WEST),
        /** Away from the face the press begins on: a click on a wall gives a horizontal cylinder. */
        CLICKED_FACE(null);

        private final Facing facing;

        FacingChoice(Facing facing) {
            this.facing = facing;
        }

        /** The fixed facing, or {@code null} for {@link #CLICKED_FACE}. */
        public Facing facing() {
            return facing;
        }
    }

    /** Where the shape sits against the block the cursor is on. */
    public enum Anchor {
        /** The shape's centre is the block's centre. */
        CENTRE,
        /** The shape rests on the face the cursor is on, outside the block. */
        SURFACE
    }

    /** The blocks placed. */
    public enum Blocks { ACTIVE_BLOCK, PALETTE }

    /**
     * What a click does: place the shape (a drag paints shapes along it), or add a point to a line the shape is swept
     * along (Enter builds it).
     */
    public enum Draw { SHAPES, LINE }

    private static final String PREFIX = "sculptory.setting.shape.";
    private static final String BRUSH = "sculptory.setting.brush.";

    public static final SettingDef.Enum<Draw> DRAW = new SettingDef.Enum<>("draw", PREFIX + "draw", Draw.class, Draw.SHAPES);
    public static final SettingDef.Enum<PathKind> LINE_PATH = new SettingDef.Enum<>("line.path", PREFIX + "line_path",
            PathKind.class, PathKind.STRAIGHT, ShapeSettings::drawsLine);
    public static final SettingDef.Int LINE_SAG = new SettingDef.Int("line.sag", PREFIX + "line_sag", 4, 0,
            (int) PathSpec.MAX_SAG, null, values -> drawsLine(values) && values.get(ShapeSettings.LINE_PATH) == PathKind.HANGING);
    public static final SettingDef.Enum<ShapeSpec.Kind> KIND = new SettingDef.Enum<>("kind", PREFIX + "kind",
            ShapeSpec.Kind.class, ShapeSpec.Kind.SPHERE);
    public static final SettingDef.Int RADIUS = new SettingDef.Int("radius", BRUSH + "radius", 4, BrushSpec.MIN_RADIUS,
            BrushSpec.MAX_RADIUS, Limits::maxBrushRadius, SettingDef.ALWAYS);
    public static final SettingDef.Bool HEIGHT_AUTO = new SettingDef.Bool("height.auto", PREFIX + "height_auto", true,
            ShapeSettings::usesHeight);
    public static final SettingDef.Int HEIGHT = new SettingDef.Int("height", PREFIX + "height", 9, ShapeSpec.MIN_HEIGHT,
            ShapeSpec.MAX_HEIGHT, ShapeSettings::maxHeight, values -> usesHeight(values) && !values.get(ShapeSettings.HEIGHT_AUTO));
    public static final SettingDef.Enum<FacingChoice> FACING = new SettingDef.Enum<>("facing", PREFIX + "facing",
            FacingChoice.class, FacingChoice.UP, ShapeSettings::usesHeight);
    public static final SettingDef.Enum<Anchor> ANCHOR = new SettingDef.Enum<>("anchor", PREFIX + "anchor", Anchor.class,
            Anchor.SURFACE);
    public static final SettingDef.Enum<ShapeSpec.Mode> MODE = new SettingDef.Enum<>("mode", PREFIX + "mode",
            ShapeSpec.Mode.class, ShapeSpec.Mode.PLACE);
    public static final SettingDef.Bool HOLLOW = new SettingDef.Bool("hollow", PREFIX + "hollow", false);
    public static final SettingDef.Int THICKNESS = new SettingDef.Int("hollow.thickness", PREFIX + "thickness", 1, 1,
            ShapeSpec.MAX_HOLLOW, null, values -> values.get(ShapeSettings.HOLLOW));
    public static final SettingDef.Enum<Blocks> BLOCKS = new SettingDef.Enum<>("blocks", PREFIX + "blocks", Blocks.class,
            Blocks.ACTIVE_BLOCK, values -> values.get(ShapeSettings.MODE) != ShapeSpec.Mode.CARVE);
    public static final SettingDef.WeightedBlocks PALETTE = new SettingDef.WeightedBlocks("palette", BRUSH + "palette",
            List.of(new SettingDef.WeightedBlock(block("minecraft:stone"), 4),
                    new SettingDef.WeightedBlock(block("minecraft:andesite"), 2),
                    new SettingDef.WeightedBlock(block("minecraft:cobblestone"), 1)),
            BrushSettings.MAX_PALETTE_ENTRIES,
            values -> values.get(ShapeSettings.MODE) != ShapeSpec.Mode.CARVE && values.get(ShapeSettings.BLOCKS) == Blocks.PALETTE);
    /**
     * How the mix is laid out: shown with the mix; Steepness is greyed out (the Shape brush
     * doesn't measure the ground's slope).
     */
    public static final MixPatternSettings PATTERN = new MixPatternSettings(
            values -> values.get(ShapeSettings.MODE) != ShapeSpec.Mode.CARVE
                    && values.get(ShapeSettings.BLOCKS) == Blocks.PALETTE, false);
    /** Writes only inside the selection box (captured when the press starts). */
    public static final SettingDef.Bool INSIDE_SELECTION = new SettingDef.Bool("selection", BRUSH + "mask.selection", false);
    /** How each dab is replicated, as for the terrain brushes (the centre is {@link SymmetryCentre}). */
    public static final SettingDef.Enum<Symmetry.Mode> SYMMETRY = new SettingDef.Enum<>("symmetry", BRUSH + "symmetry",
            Symmetry.Mode.class, Symmetry.Mode.OFF);

    public static final SettingsSchema SCHEMA = new SettingsSchema(List.of(
            new Section("", mainSettings()),
            new Section(BRUSH + "symmetry_section", List.of(SYMMETRY), true)));

    private ShapeSettings() {}

    /** The untitled section: clicks or a line, the shape, the blocks and their pattern, "Only inside selection". */
    private static List<SettingDef<?>> mainSettings() {
        List<SettingDef<?>> main = new ArrayList<>(List.of(DRAW, LINE_PATH, LINE_SAG, KIND, RADIUS, HEIGHT_AUTO, HEIGHT,
                FACING, ANCHOR, MODE, HOLLOW, THICKNESS, BLOCKS, PALETTE));
        main.addAll(PATTERN.defs());
        main.add(INSIDE_SELECTION);
        return main;
    }

    /** Whether a click adds a point to a line rather than placing the shape. */
    public static boolean drawsLine(SettingsValues values) {
        return values.get(DRAW) == Draw.LINE;
    }

    /** Whether the solid has a height and a facing (all but the sphere). */
    public static boolean usesHeight(SettingsValues values) {
        return values.get(KIND) != ShapeSpec.Kind.SPHERE;
    }

    /** The tallest shape the server allows: its largest diameter. */
    public static int maxHeight(Limits limits) {
        return 2 * Math.max(BrushSpec.MIN_RADIUS, Math.min(BrushSpec.MAX_RADIUS, limits.maxBrushRadius())) + 1;
    }

    /** The radius within the server's limit. */
    public static int radius(SettingsValues values, Limits limits) {
        int max = Math.max(BrushSpec.MIN_RADIUS, Math.min(BrushSpec.MAX_RADIUS, limits.maxBrushRadius()));
        return Math.max(BrushSpec.MIN_RADIUS, Math.min(max, values.get(RADIUS)));
    }

    /** The height a shape gets: the diameter with "Height = diameter" (and for a sphere), else the setting, capped. */
    public static int height(SettingsValues values, Limits limits) {
        int diameter = 2 * radius(values, limits) + 1;
        if (!usesHeight(values) || values.get(HEIGHT_AUTO)) return diameter;
        return Math.max(ShapeSpec.MIN_HEIGHT, Math.min(maxHeight(limits), values.get(HEIGHT)));
    }

    /**
     * The shape the settings describe, with {@code facing} (the fixed facing, or the resolved clicked face): the height
     * above and the hollow thickness when "Hollow" is on.
     */
    public static ShapeSpec shape(SettingsValues values, Limits limits, Facing facing) {
        int thickness = Math.max(1, Math.min(ShapeSpec.MAX_HOLLOW, values.get(THICKNESS)));
        return new ShapeSpec(values.get(KIND), height(values, limits), facing, values.get(MODE),
                values.get(HOLLOW) ? thickness : 0);
    }

    private static BlockDescriptor block(String id) {
        return BlockDescriptor.of(new NamespacedId(id));
    }
}
