package dev.sculptory.fabric.client.editor.tools.extrude;

import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.tools.select.MagicSelect;
import java.util.List;

/**
 * The Extrude tool's settings ({@link ExtrudeTool}): which blocks join the hovered face (match, diagonals), how large
 * a face may be, how many layers one drag may pull out or carve, and the symmetry the ops run with (the shared centre,
 * as Select and Place). Presets save them all.
 */
public final class ExtrudeSettings {
    private static final String PREFIX = "sculptory.setting.extrude.";

    /** The most cells a hovered face may hold by default, and at most. */
    public static final int DEFAULT_SIZE = 4_096;
    public static final int MAX_SIZE = 262_144;
    /** The most layers one drag pulls out or carves by default, and at most (a stack makes at most 256 copies). */
    public static final int DEFAULT_MAX_LAYERS = 32;
    public static final int MAX_MAX_LAYERS = 256;

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
            DEFAULT_MAX_LAYERS, 1, MAX_MAX_LAYERS);

    /** The symmetry the extrusions, carves and smears run with, around the shared centre (M). */
    public static final SettingDef.Enum<Symmetry.Mode> SYMMETRY = new SettingDef.Enum<>("symmetry", PREFIX + "symmetry",
            Symmetry.Mode.class, Symmetry.Mode.OFF);

    public static final SettingsSchema SCHEMA = new SettingsSchema(List.of(
            new Section("", List.of(MATCH, DIAGONALS, SIZE, MAX_LAYERS)),
            new Section(PREFIX + "symmetry_section", List.of(SYMMETRY), true)));

    private ExtrudeSettings() {}
}
