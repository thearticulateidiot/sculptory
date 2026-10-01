package dev.sculptory.fabric.client.editor.tools.fluid;

import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tools.brush.ShapeSettings;
import dev.sculptory.fabric.client.editor.tools.select.SelectSettings;
import dev.sculptory.protocol.v2.Limits;
import java.util.List;

/**
 * The Fluid tool's settings ({@link FluidTool}): the mode
 * (Flood, Drain or Fluid ball), the fluid (water or lava), the searches' limit, whether a flood waterlogs the blocks on
 * its rim, whether a drain takes waterlogged blocks along, the ball's radius and whether it waterlogs, and a collapsed
 * Symmetry section like the brushes'. Each setting shows for the modes that use it; the waterlogging switches show for
 * water only (lava cannot waterlog).
 */
public final class FluidSettings {
    /** What a click does. */
    public enum Mode {
        /** Fills the air pocket beside the aimed block, up to that level. */
        FLOOD,
        /** Removes the body of water or lava under the cursor. */
        DRAIN,
        /** Paints balls of the fluid, as the Shape brush paints spheres. */
        BALL
    }

    /** The fluid placed or removed. */
    public enum Fluid {
        WATER("minecraft:water"),
        LAVA("minecraft:lava");

        private final String block;

        Fluid(String block) {
            this.block = block;
        }

        /** The fluid block's id. */
        public String block() {
            return block;
        }
    }

    private static final String PREFIX = "sculptory.setting.fluid.";
    private static final String BRUSH = "sculptory.setting.brush.";

    public static final SettingDef.Enum<Mode> MODE = new SettingDef.Enum<>("mode", PREFIX + "mode", Mode.class, Mode.FLOOD);
    public static final SettingDef.Enum<Fluid> FLUID = new SettingDef.Enum<>("fluid", PREFIX + "fluid", Fluid.class,
            Fluid.WATER);
    /**
     * The most cells a flood or drain collects; the server's op limit and the most cells it takes in one selection cap
     * it (players who may bypass limits excepted), as for magic select.
     */
    public static final SettingDef.Int LIMIT = new SettingDef.Int("limit", PREFIX + "limit", SelectSettings.DEFAULT_LIMIT, 1,
            SelectSettings.MAX_CELLS,
            limits -> (int) Math.min(Integer.MAX_VALUE, Math.min(limits.maxOpVolume(), limits.maxSelectionCells())),
            values -> values.get(FluidSettings.MODE) != Mode.BALL);
    /** Flood: waterloggable blocks touching the pocket at or below the level are waterlogged too. */
    public static final SettingDef.Bool WATERLOG_RIM = new SettingDef.Bool("waterlog_rim", PREFIX + "waterlog_rim", true,
            values -> values.get(FluidSettings.MODE) == Mode.FLOOD && values.get(FluidSettings.FLUID) == Fluid.WATER);
    /** Drain: waterlogged blocks (and water plants) connected to the water lose their water too. */
    public static final SettingDef.Bool DRAIN_WATERLOGGED = new SettingDef.Bool("drain_waterlogged",
            PREFIX + "drain_waterlogged", true,
            values -> values.get(FluidSettings.MODE) == Mode.DRAIN && values.get(FluidSettings.FLUID) == Fluid.WATER);
    /** The ball's radius (1-32, capped by the server's {@code maxBrushRadius}; Ctrl+Scroll). */
    public static final SettingDef.Int RADIUS = new SettingDef.Int("radius", BRUSH + "radius", 4, BrushSpec.MIN_RADIUS,
            BrushSpec.MAX_RADIUS, Limits::maxBrushRadius, values -> values.get(FluidSettings.MODE) == Mode.BALL);
    /** Fluid ball: waterloggable blocks inside the ball are waterlogged instead of left alone. */
    public static final SettingDef.Bool WATERLOG_BALL = new SettingDef.Bool("waterlog_ball", PREFIX + "waterlog_ball", true,
            values -> values.get(FluidSettings.MODE) == Mode.BALL && values.get(FluidSettings.FLUID) == Fluid.WATER);
    /** Symmetry for the flood and drain ops and the ball's dabs, around the shared centre (key M). */
    public static final SettingDef.Enum<Symmetry.Mode> SYMMETRY = new SettingDef.Enum<>("symmetry", BRUSH + "symmetry",
            Symmetry.Mode.class, Symmetry.Mode.OFF);

    public static final SettingsSchema SCHEMA = new SettingsSchema(List.of(
            new Section("", List.of(MODE, FLUID, LIMIT, WATERLOG_RIM, DRAIN_WATERLOGGED, RADIUS, WATERLOG_BALL)),
            new Section(BRUSH + "symmetry_section", List.of(SYMMETRY), true)));

    private FluidSettings() {}

    /** Whether a flood waterlogs its rim: the switch, and the fluid is water. */
    public static boolean floodWaterlogs(SettingsValues values) {
        return values.get(FLUID) == Fluid.WATER && values.get(WATERLOG_RIM);
    }

    /** Whether a drain takes waterlogged blocks and water plants along: the switch, and the fluid is water. */
    public static boolean drainsWaterlogged(SettingsValues values) {
        return values.get(FLUID) == Fluid.WATER && values.get(DRAIN_WATERLOGGED);
    }

    /** Whether the ball waterlogs what it can: the switch, and the fluid is water. */
    public static boolean ballWaterlogs(SettingsValues values) {
        return values.get(FLUID) == Fluid.WATER && values.get(WATERLOG_BALL);
    }

    /** The ball's radius within the server's limit. */
    public static int radius(SettingsValues values, Limits limits) {
        int max = Math.max(BrushSpec.MIN_RADIUS, Math.min(BrushSpec.MAX_RADIUS, limits.maxBrushRadius()));
        return Math.max(BrushSpec.MIN_RADIUS, Math.min(max, values.get(RADIUS)));
    }

    /**
     * The chosen fluid's source state in {@code states} ({@link Pattern#isFluidSource}), or -1 when this game does not
     * know the fluid block.
     */
    public static int fluidSource(SettingsValues values, StateSpace states) {
        int block = states.parse(values.get(FLUID).block());
        if (block < 0) return -1;
        int source = states.fluidSource(block);
        return Pattern.isFluidSource(states, source) ? source : -1;
    }

    /**
     * The Shape brush's settings the fluid ball runs with: a sphere of {@link #RADIUS} resting on the clicked face,
     * mode Place with a {@code Waterlog} material when the ball waterlogs (the pattern leaves other blocks alone), else
     * Air only with the plain fluid, the tool's symmetry, no hollow and no clip.
     */
    public static SettingsValues shapeSettings(SettingsValues values) {
        return SettingsValues.defaults(ShapeSettings.SCHEMA)
                .with(ShapeSettings.KIND, ShapeSpec.Kind.SPHERE)
                .with(ShapeSettings.RADIUS, values.get(RADIUS))
                .with(ShapeSettings.HEIGHT_AUTO, true)
                .with(ShapeSettings.ANCHOR, ShapeSettings.Anchor.SURFACE)
                .with(ShapeSettings.MODE, ballWaterlogs(values) ? ShapeSpec.Mode.PLACE : ShapeSpec.Mode.PLACE_IN_AIR)
                .with(ShapeSettings.HOLLOW, false)
                .with(ShapeSettings.INSIDE_SELECTION, false)
                .with(ShapeSettings.SYMMETRY, values.get(SYMMETRY));
    }
}
