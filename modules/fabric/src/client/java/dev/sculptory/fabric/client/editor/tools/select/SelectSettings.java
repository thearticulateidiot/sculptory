package dev.sculptory.fabric.client.editor.tools.select;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tools.brush.MixPatternSettings;
import java.util.ArrayList;
import java.util.List;

/**
 * The Select tool's settings: how a click selects (a box and the shape inside it, or magic select and what it
 * matches), what Fill and Walls write, which blocks Fill writes into, the wall thickness for Hollow and Walls, and
 * the entities Copy and Cut take along.
 */
public final class SelectSettings {
    /** How the left button selects. */
    public enum Mode {
        /** Drag a box; the shape setting says what inside it is selected. */
        BOX,
        /** Click a block to select the connected blocks that match it. */
        MAGIC,
        /** Drag a sphere over blocks to add them to the selection (Alt removes). */
        BRUSH,
        /** Drag a loop on the ground; the blocks inside it, some layers high, are selected. */
        LASSO
    }

    /** What a dragged box selects: the whole box, or the shape inscribed in it. */
    public enum SelectShape {
        BOX(null),
        SPHERE(ShapeKind.ELLIPSOID),
        CYLINDER(ShapeKind.CYLINDER),
        CONE(ShapeKind.CONE),
        PYRAMID(ShapeKind.PYRAMID);

        private final ShapeKind kind;

        SelectShape(ShapeKind kind) {
            this.kind = kind;
        }

        /** The contract's shape, or null for the whole box. */
        public ShapeKind kind() {
            return kind;
        }

        /** The choice that selects {@code kind}. */
        public static SelectShape of(ShapeKind kind) {
            for (SelectShape shape : values()) {
                if (shape.kind == kind) return shape;
            }
            throw new IllegalArgumentException("No shape for " + kind);
        }
    }

    /** A cylinder's axis: a cylinder facing either way along an axis holds the same cells. */
    public enum Axis {
        VERTICAL(Facing.UP),
        EAST_WEST(Facing.EAST),
        NORTH_SOUTH(Facing.SOUTH);

        private final Facing facing;

        Axis(Facing facing) {
            this.facing = facing;
        }

        /** The facing a cylinder along this axis is sent with. */
        public Facing facing() {
            return facing;
        }

        /** The axis a facing runs along. */
        public static Axis of(Facing facing) {
            return switch (facing.axis()) {
                case 0 -> EAST_WEST;
                case 1 -> VERTICAL;
                default -> NORTH_SOUTH;
            };
        }
    }

    /** What Fill and Walls write. */
    public enum FillWith { ACTIVE_BLOCK, PALETTE }

    public static final BlockDescriptor STONE = BlockDescriptor.of(new NamespacedId("minecraft:stone"));

    /** Magic select's default limit, in blocks. */
    public static final int DEFAULT_LIMIT = 100_000;

    /** The most cells a magic selection may hold (its limit, and what adding to a selection may make). */
    public static final int MAX_CELLS = 16_777_216;

    public static final SettingDef.Enum<Mode> MODE = new SettingDef.Enum<>("mode",
            "sculptory.setting.select.mode", Mode.class, Mode.BOX);

    public static final SettingDef.Enum<SelectShape> SHAPE = new SettingDef.Enum<>("shape",
            "sculptory.setting.select.shape", SelectShape.class, SelectShape.BOX,
            values -> values.get(SelectSettings.MODE) == Mode.BOX);

    public static final SettingDef.Enum<Axis> AXIS = new SettingDef.Enum<>("axis",
            "sculptory.setting.select.axis", Axis.class, Axis.VERTICAL,
            values -> values.get(SelectSettings.MODE) == Mode.BOX
                    && values.get(SelectSettings.SHAPE) == SelectShape.CYLINDER);

    public static final SettingDef.Enum<Facing> FACING = new SettingDef.Enum<>("facing",
            "sculptory.setting.select.facing", Facing.class, Facing.UP,
            values -> values.get(SelectSettings.MODE) == Mode.BOX && pointed(values.get(SelectSettings.SHAPE)));

    public static final SettingDef.Enum<MagicSelect.Match> MATCH = new SettingDef.Enum<>("match",
            "sculptory.setting.select.match", MagicSelect.Match.class, MagicSelect.Match.SAME_BLOCK,
            SelectSettings::magic);

    public static final SettingDef.Enum<MagicSelect.Connect> CONNECT = new SettingDef.Enum<>("connect",
            "sculptory.setting.select.connect", MagicSelect.Connect.class, MagicSelect.Connect.FACES,
            SelectSettings::magic);

    /**
     * Magic select's limit; the server's op limit caps it (players who may bypass limits excepted), and so does the most
     * blocks it takes in a selection sent to it ({@code Limits.maxSelectionCells}, which nobody bypasses).
     */
    public static final SettingDef.Int LIMIT = new SettingDef.Int("limit", "sculptory.setting.select.limit",
            DEFAULT_LIMIT, 1, MAX_CELLS,
            limits -> (int) Math.min(Integer.MAX_VALUE, Math.min(limits.maxOpVolume(), limits.maxSelectionCells())),
            SelectSettings::magic);

    /** The selection brush's sphere radius in blocks (Brush mode; Ctrl+Scroll changes it). */
    public static final int MIN_BRUSH_RADIUS = 1;
    public static final int MAX_BRUSH_RADIUS = 32;
    public static final SettingDef.Int BRUSH_RADIUS = new SettingDef.Int("brush_radius",
            "sculptory.setting.select.brush_radius", 4, MIN_BRUSH_RADIUS, MAX_BRUSH_RADIUS, null, SelectSettings::brush);

    /** Whether the selection brush adds only blocks that aren't air (skipping chunks the client hasn't loaded). */
    public static final SettingDef.Bool SOLID_ONLY = new SettingDef.Bool("solid_only",
            "sculptory.setting.select.solid_only", true, SelectSettings::brush);

    /** How many layers up from the pressed block a lasso selects (Lasso mode; Alt+Scroll changes it). */
    public static final int MIN_LASSO_HEIGHT = 1;
    public static final int MAX_LASSO_HEIGHT = 384;
    public static final SettingDef.Int LASSO_HEIGHT = new SettingDef.Int("lasso_height",
            "sculptory.setting.select.lasso_height", 1, MIN_LASSO_HEIGHT, MAX_LASSO_HEIGHT, null, SelectSettings::lasso);

    public static final SettingDef.Enum<FillWith> FILL_WITH = new SettingDef.Enum<>("fill_with",
            "sculptory.setting.select.fill_with", FillWith.class, FillWith.ACTIVE_BLOCK);

    public static final SettingDef.WeightedBlocks PALETTE = new SettingDef.WeightedBlocks("palette",
            "sculptory.setting.select.palette", List.of(new SettingDef.WeightedBlock(STONE, 1)), 16,
            values -> values.get(SelectSettings.FILL_WITH) == FillWith.PALETTE);

    /**
     * How Fill lays the palette out: Random, Patches or Gradient; Steepness
     * is greyed out (a Fill doesn't measure the ground's slope). Shown with the palette.
     */
    public static final MixPatternSettings PATTERN = new MixPatternSettings(
            values -> values.get(SelectSettings.FILL_WITH) == FillWith.PALETTE, false);

    /**
     * Which blocks Fill writes into (client only: sent as the op's mask):
     * everything, only existing blocks (not air) or only air. Walls and Hollow carry no mask and write everything.
     * Presets from before it load as Everything.
     */
    public static final SettingDef.Enum<PasteOptions.Into> INTO = new SettingDef.Enum<>("into",
            "sculptory.setting.select.into", PasteOptions.Into.class, PasteOptions.Into.EVERYTHING);

    public static final SettingDef.Int THICKNESS = new SettingDef.Int("thickness",
            "sculptory.setting.select.thickness", 1, 1, 16);

    /**
     * Which entities Copy and Cut take along with the blocks: none, decorations
     * (frames, paintings, armor stands, displays, minecarts, boats...) or decorations and mobs. Never players.
     */
    public static final SettingDef.Enum<EntityFilter> ENTITIES = new SettingDef.Enum<>("entities",
            "sculptory.setting.select.entities", EntityFilter.class, EntityFilter.DECORATIONS);

    /**
     * Symmetry for Fill, Replace, Erase, Hollow and Walls:
     * the op runs on the selection and on each image of it around the shared centre ({@code SymmetryCentre}, set with
     * M; an op with a mode on and no centre set is refused). The brushes' modes and preset key, so presets keep it.
     */
    public static final SettingDef.Enum<Symmetry.Mode> SYMMETRY = new SettingDef.Enum<>("symmetry",
            "sculptory.setting.select.symmetry", Symmetry.Mode.class, Symmetry.Mode.OFF);

    public static final SettingsSchema SCHEMA = new SettingsSchema(List.of(
            new Section("", List.of(MODE, SHAPE, AXIS, FACING, MATCH, CONNECT, LIMIT)),
            new Section("sculptory.setting.select.section.brush", List.of(BRUSH_RADIUS, SOLID_ONLY)),
            new Section("sculptory.setting.select.section.lasso", List.of(LASSO_HEIGHT)),
            new Section("sculptory.setting.select.section.ops", opsSettings()),
            new Section("sculptory.setting.select.copy_section", List.of(ENTITIES)),
            new Section("sculptory.setting.select.symmetry_section", List.of(SYMMETRY), true)));

    private SelectSettings() {}

    /** The operations section: what Fill writes (the active block or the palette and its pattern), Into, thickness. */
    private static List<SettingDef<?>> opsSettings() {
        List<SettingDef<?>> ops = new ArrayList<>(List.of(FILL_WITH, PALETTE));
        ops.addAll(PATTERN.defs());
        ops.addAll(List.of(INTO, THICKNESS));
        return ops;
    }

    /** Whether the shape has a tip whose direction the facing sets. */
    public static boolean pointed(SelectShape shape) {
        return shape == SelectShape.CONE || shape == SelectShape.PYRAMID;
    }

    /** The region a box selects with these settings: the box itself, or the shape inscribed in it. */
    public static Region region(Box box, SettingsValues values) {
        SelectShape shape = values.get(SHAPE);
        if (shape == SelectShape.BOX) return new Region.Cuboid(box);
        return new Region.Shape(box, shape.kind(), facing(values));
    }

    /** The facing a shape is sent with: the cylinder's axis, the cone's or pyramid's tip, or UP (a sphere ignores it). */
    public static Facing facing(SettingsValues values) {
        SelectShape shape = values.get(SHAPE);
        if (shape == SelectShape.CYLINDER) return values.get(AXIS).facing();
        return pointed(shape) ? values.get(FACING) : Facing.UP;
    }

    private static boolean magic(SettingsValues values) {
        return values.get(MODE) == Mode.MAGIC;
    }

    private static boolean brush(SettingsValues values) {
        return values.get(MODE) == Mode.BRUSH;
    }

    private static boolean lasso(SettingsValues values) {
        return values.get(MODE) == Mode.LASSO;
    }
}
