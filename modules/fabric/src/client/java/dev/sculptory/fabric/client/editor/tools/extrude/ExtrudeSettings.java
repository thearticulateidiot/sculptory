package dev.sculptory.fabric.client.editor.tools.extrude;

import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.generate.TaperKernel;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tools.select.MagicSelect;
import java.util.List;
import java.util.function.Predicate;

/**
 * The Extrude tool's settings ({@link ExtrudeTool}): which way it pulls (straight, along a path or to a point), which
 * blocks join the hovered face (match, diagonals), how large a face may be, how many layers one drag may pull out or
 * carve, the path's shape and whether a wall face follows its slope, the taper's curve, and the symmetry straight
 * extrusions run with (the shared centre, as Select and Place). Presets save them all.
 */
public final class ExtrudeSettings {
    private static final String PREFIX = "sculptory.setting.extrude.";

    /** The most cells a hovered face may hold by default, and at most. */
    public static final int DEFAULT_SIZE = 4_096;
    public static final int MAX_SIZE = 262_144;
    /** The most layers one drag pulls out or carves by default, and at most (a stack makes at most 256 copies). */
    public static final int DEFAULT_MAX_LAYERS = 32;
    public static final int MAX_MAX_LAYERS = 256;

    /** Which way the face is pulled. Presets save the name. */
    public enum Direction {
        /** Dragged straight out or in (and Ctrl+drag, Alt+drag), as before. */
        STRAIGHT,
        /** Carried along a path of clicked points. */
        ALONG_PATH,
        /** Tapered to a clicked tip. */
        TO_POINT
    }

    private static final Predicate<SettingsValues> STRAIGHT = values -> values.get(ExtrudeSettings.DIRECTION) == Direction.STRAIGHT;
    private static final Predicate<SettingsValues> ALONG = values -> values.get(ExtrudeSettings.DIRECTION) == Direction.ALONG_PATH;

    /** Which way the face is pulled. */
    public static final SettingDef.Enum<Direction> DIRECTION = new SettingDef.Enum<>("direction", PREFIX + "direction",
            Direction.class, Direction.STRAIGHT);

    /** Which blocks count as the hovered one (magic select's choices). */
    public static final SettingDef.Enum<MagicSelect.Match> MATCH = new SettingDef.Enum<>("match", PREFIX + "match",
            MagicSelect.Match.class, MagicSelect.Match.SAME_BLOCK);

    /** Whether blocks touching only at a corner of the face join it. */
    public static final SettingDef.Bool DIAGONALS = new SettingDef.Bool("diagonals", PREFIX + "diagonals", false);

    /**
     * The most blocks a hovered face may hold; the most the server takes in a selection sent to it caps it (nobody
     * bypasses that).
     */
    public static final SettingDef.Int SIZE = new SettingDef.Int("size", PREFIX + "size", DEFAULT_SIZE, 1, MAX_SIZE,
            limits -> (int) Math.min(Integer.MAX_VALUE, limits.maxSelectionCells()), values -> true);

    /** The most layers one drag pulls out or carves. */
    public static final SettingDef.Int MAX_LAYERS = new SettingDef.Int("max_layers", PREFIX + "max_layers",
            DEFAULT_MAX_LAYERS, 1, MAX_MAX_LAYERS, null, STRAIGHT);

    /** Along a path: how the path runs through its points (as Generate's Line). */
    public static final SettingDef.Enum<PathKind> PATH = new SettingDef.Enum<>("path", PREFIX + "path", PathKind.class,
            PathKind.CURVE, ALONG);

    /** Along a hanging path: how far each span sags. */
    public static final SettingDef.Int SAG = new SettingDef.Int("sag", PREFIX + "sag", 4, 0, (int) PathSpec.MAX_SAG, null,
            values -> ALONG.test(values) && values.get(ExtrudeSettings.PATH) == PathKind.HANGING);

    /** Along a path: whether a wall face tilts with the path's climb (else it stays upright). */
    public static final SettingDef.Bool FOLLOW_SLOPE = new SettingDef.Bool("follow_slope", PREFIX + "follow_slope", false,
            ALONG);

    /** To a point: how the face narrows toward the tip. */
    public static final SettingDef.Enum<TaperKernel.Curve> TAPER = new SettingDef.Enum<>("taper", PREFIX + "taper",
            TaperKernel.Curve.class, TaperKernel.Curve.STRAIGHT,
            values -> values.get(ExtrudeSettings.DIRECTION) == Direction.TO_POINT);

    /** The symmetry straight extrusions, carves and smears run with, around the shared centre (M). */
    public static final SettingDef.Enum<Symmetry.Mode> SYMMETRY = new SettingDef.Enum<>("symmetry", PREFIX + "symmetry",
            Symmetry.Mode.class, Symmetry.Mode.OFF, STRAIGHT);

    public static final SettingsSchema SCHEMA = new SettingsSchema(List.of(
            new Section("", List.of(DIRECTION, MATCH, DIAGONALS, SIZE, MAX_LAYERS, PATH, SAG, FOLLOW_SLOPE, TAPER)),
            new Section(PREFIX + "symmetry_section", List.of(SYMMETRY), true)));

    private ExtrudeSettings() {}
}
