package dev.sculptory.fabric.client.editor.tutorial;

import static dev.sculptory.fabric.client.editor.tutorial.Arg.key;
import static dev.sculptory.fabric.client.editor.tutorial.Arg.text;
import static dev.sculptory.fabric.client.editor.tutorial.Arg.vanilla;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.activeBlockChanged;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.any;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.anySettingsChanged;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.anyToolSettingChanged;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.blocksSelected;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.boxSelected;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.builderPowerOn;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.clipboardChanged;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.commandSearchOpenedThenClosed;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.edited;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.exportDialogOpen;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.exported;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.flySpeedChanged;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.gradientLineSet;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.hasSelection;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.keySheetOpenedThenClosed;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.keyboardInWindow;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.libraryChanged;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.looked;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.menuOpen;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.moved;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.noBuilderPower;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.noSelection;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.pathNodes;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.placementUpsideDown;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.placing;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.presetsChanged;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.redone;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.reentered;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.scatterAreaPainted;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.scatterMixChanged;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.selectionChanged;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.setting;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.settingChanged;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.settingNot;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.settingReset;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.shapeSelected;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.strokeEnded;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.symmetryCentreSet;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.tinkerAimed;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.toolActive;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.uiSizeChanged;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.undone;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.windowShown;
import static dev.sculptory.fabric.client.editor.tutorial.Conditions.windowsHiddenThenShown;

import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.brush.WeatherSpec;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.fabric.client.editor.commands.CommandMenu;
import dev.sculptory.fabric.client.editor.commands.EditorCommands;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.select.SelectSettings;
import dev.sculptory.fabric.client.editor.tutorial.Arg.VanillaKey;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The tutorial's lessons, in order and in three groups. Their text is in
 * {@code en_us.json} under {@code sculptory.tutorial.*}; keys are filled in as the player has them bound. Lesson
 * ids are kept in the progress file, so they never change.
 */
public final class Lessons {
    /** The wiki page ids a step may link to (the frozen ones, plus the other pages lessons link to). */
    public static final List<String> WIKI_PAGES = List.of("home", "getting-started", "editor-mode",
            "screen-and-windows", "finding-things", "tutorial", "tool-settings", "presets", "select",
            "selection-operations", "terrain-brushes", "masks", "shape-brush", "generate", "extrude", "fluid", "scatter",
            "clipboard", "place", "library", "palettes", "symmetry", "history", "keys", "server-setup", "permissions",
            "troubleshooting", "faq", "tools", "tinker", "builder-mode", "weather", "navigation");

    /** A group of lessons in the Tutorial window; its title is {@code sculptory.tutorial.group.<id>}. */
    public record Group(String id, List<Lesson> lessons) {
        public Group {
            Objects.requireNonNull(id);
            lessons = List.copyOf(lessons);
        }

        public String titleKey() {
            return Lesson.PREFIX + "group." + id;
        }
    }

    private static final List<Group> GROUPS = List.of(
            new Group("basics", List.of(gettingAround(), menus(), select(), edit(), history(), settings())),
            new Group("brushes", List.of(terrain(), weather(), paint(), patterns(), shapes(), symmetry())),
            new Group("building", List.of(library(), flip(), export(), build(), scatter(), tinker(), builder())));

    private static final List<Lesson> ALL = GROUPS.stream().flatMap(group -> group.lessons().stream()).toList();

    private Lessons() {}

    public static List<Lesson> all() {
        return ALL;
    }

    public static List<Group> groups() {
        return GROUPS;
    }

    /** The group {@code lesson} is in, if it is one of {@link #all}. */
    public static Optional<Group> groupOf(Lesson lesson) {
        return GROUPS.stream().filter(group -> group.lessons().contains(lesson)).findFirst();
    }

    public static Optional<Lesson> byId(String id) {
        return ALL.stream().filter(lesson -> lesson.id().equals(id)).findFirst();
    }

    /** The lesson after {@code lesson}, if there is one. */
    public static Optional<Lesson> after(Lesson lesson) {
        int index = ALL.indexOf(lesson);
        return index >= 0 && index + 1 < ALL.size() ? Optional.of(ALL.get(index + 1)) : Optional.empty();
    }

    private static Target palette(int slot) {
        return new Target.PaletteSlot(slot);
    }

    private static Target row(ToolId tool, String key) {
        return new Target.SettingRow(tool, key);
    }

    private static Target menuRow(CommandMenu menu, String commandId) {
        return new Target.MenuRow(menu, commandId);
    }

    // ---- Basics ----

    static Lesson gettingAround() {
        return Lesson.builder("getting_around")
                .read("welcome").wiki("tutorial")
                .step("look", looked()).args(text("sculptory.help.mouse.right_drag")).wiki("editor-mode")
                .step("move", moved(3)).args(vanilla(VanillaKey.MOVE), vanilla(VanillaKey.UP), vanilla(VanillaKey.DOWN),
                        key(KeyAction.JUMP), key(KeyAction.JUMP_THROUGH)).wiki("navigation")
                .step("fly_speed", flySpeedChanged()).args(key(KeyAction.FLY_SPEED))
                        .target(new Target.TopBar(Target.Item.FLY_SPEED))
                .read("esc").args(text("sculptory.help.key.esc"))
                .step("leave", reentered()).args(vanilla(VanillaKey.TOGGLE), vanilla(VanillaKey.TOGGLE))
                        .wiki("editor-mode")
                .build();
    }

    static Lesson menus() {
        return Lesson.builder("menus")
                .step("view_menu", menuOpen(CommandMenu.VIEW)).target(new Target.MenuTitle(CommandMenu.VIEW))
                        .wiki("finding-things")
                .step("notifications", windowShown(EditorWindows.NOTIFICATIONS))
                        .target(menuRow(CommandMenu.VIEW, EditorCommands.NOTIFICATIONS))
                .step("search", commandSearchOpenedThenClosed()).args(key(KeyAction.COMMAND_SEARCH))
                        .wiki("finding-things")
                .step("key_sheet", keySheetOpenedThenClosed()).args(key(KeyAction.HELP), key(KeyAction.HELP))
                        .wiki("keys")
                .step("f6", keyboardInWindow()).args(key(KeyAction.FOCUS_NEXT_WINDOW))
                .step("hide", windowsHiddenThenShown())
                        .args(text("sculptory.help.key.esc"), key(KeyAction.HIDE_WINDOWS))
                .read("windows").target(menuRow(CommandMenu.VIEW, EditorCommands.RESET_LAYOUT))
                        .wiki("screen-and-windows")
                .build();
    }

    static Lesson select() {
        return Lesson.builder("select")
                .step("pick", toolActive(ToolId.SELECT)).args(key(KeyAction.TOOL_1)).target(palette(1))
                .step("box", boxSelected()).wiki("select")
                .step("resize", selectionChanged()).args(key(KeyAction.TOOL_SIZE))
                .step("nudge", selectionChanged()).args(key(KeyAction.NUDGE_FORWARD), key(KeyAction.NUDGE_BACK),
                        key(KeyAction.NUDGE_LEFT), key(KeyAction.NUDGE_RIGHT), key(KeyAction.NUDGE_UP),
                        key(KeyAction.NUDGE_DOWN))
                .step("shape", setting(ToolId.SELECT, "shape", SelectSettings.SelectShape.SPHERE))
                        .target(row(ToolId.SELECT, "shape"))
                .step("magic", Conditions.all(setting(ToolId.SELECT, "mode", SelectSettings.Mode.MAGIC),
                                blocksSelected()))
                        .target(row(ToolId.SELECT, "mode")).wiki("select")
                .step("box_again", Conditions.all(setting(ToolId.SELECT, "mode", SelectSettings.Mode.BOX),
                                setting(ToolId.SELECT, "shape", SelectSettings.SelectShape.BOX)))
                        .target(row(ToolId.SELECT, "mode"))
                .step("clear", noSelection()).args(key(KeyAction.DESELECT))
                .build();
    }

    static Lesson edit() {
        return Lesson.builder("edit")
                .step("box", hasSelection()).args(key(KeyAction.TOOL_1))
                .step("block", activeBlockChanged()).args(key(KeyAction.EYEDROPPER))
                        .target(new Target.TopBar(Target.Item.ACTIVE_BLOCK))
                .step("fill", edited()).target(menuRow(CommandMenu.SELECTION, EditorCommands.FILL))
                        .wiki("selection-operations")
                .step("replace", edited()).target(menuRow(CommandMenu.SELECTION, EditorCommands.REPLACE))
                .step("copy", clipboardChanged()).args(key(KeyAction.COPY)).wiki("clipboard")
                .step("paste", edited()).args(key(KeyAction.PASTE), key(KeyAction.COMMIT)).wiki("place")
                .step("move", edited()).args(key(KeyAction.COMMIT))
                        .target(menuRow(CommandMenu.SELECTION, EditorCommands.MOVE))
                .step("stack", edited()).args(key(KeyAction.TOOL_SIZE), key(KeyAction.COMMIT))
                        .target(menuRow(CommandMenu.SELECTION, EditorCommands.STACK))
                .step("erase", edited()).args(key(KeyAction.ERASE_SELECTION))
                .build();
    }

    static Lesson history() {
        return Lesson.builder("history")
                .step("change", edited()).args(key(KeyAction.TOOL_2))
                .step("undo", undone()).args(key(KeyAction.UNDO)).target(new Target.TopBar(Target.Item.UNDO))
                        .wiki("history")
                .step("redo", redone()).args(key(KeyAction.REDO)).target(new Target.TopBar(Target.Item.REDO))
                .step("window", windowShown(EditorWindows.HISTORY)).args(key(KeyAction.HISTORY))
                        .target(menuRow(CommandMenu.EDIT, EditorCommands.HISTORY))
                .step("jump", any(undone(), redone())).target(new Target.WindowFrame(EditorWindows.HISTORY))
                .read("anyway").wiki("history")
                .build();
    }

    static Lesson settings() {
        return Lesson.builder("settings")
                .read("hover").target(new Target.WindowFrame(EditorWindows.TOOL_SETTINGS)).wiki("tool-settings")
                .step("change", anySettingsChanged()).target(new Target.WindowFrame(EditorWindows.TOOL_SETTINGS))
                .step("reset", settingReset())
                .step("preset", presetsChanged()).wiki("presets")
                .step("keys", windowShown(EditorWindows.KEYS))
                        .target(menuRow(CommandMenu.HELP, EditorCommands.CHANGE_KEYS)).wiki("keys")
                .step("ui_size", uiSizeChanged()).args(key(KeyAction.UI_SMALLER), key(KeyAction.UI_LARGER))
                        .target(new Target.TopBar(Target.Item.UI_SIZE))
                .read("done").args(key(KeyAction.UI_RESET)).wiki("home")
                .build();
    }

    // ---- Brushes ----

    static Lesson terrain() {
        return Lesson.builder("terrain")
                .step("raise_pick", toolActive(ToolId.RAISE)).args(key(KeyAction.TOOL_2)).target(palette(2))
                        .wiki("terrain-brushes")
                .step("raise", strokeEnded(ToolId.RAISE))
                .step("radius", anyToolSettingChanged("radius")).args(key(KeyAction.TOOL_SIZE))
                        .target(row(ToolId.RAISE, "radius"))
                .step("strength", anyToolSettingChanged("strength")).args(key(KeyAction.TOOL_STRENGTH))
                        .target(row(ToolId.RAISE, "strength"))
                .step("lower", strokeEnded(ToolId.LOWER)).args(key(KeyAction.TOOL_3)).target(palette(3))
                .step("smooth", strokeEnded(ToolId.SMOOTH)).args(key(KeyAction.TOOL_4)).target(palette(4))
                .step("flatten", strokeEnded(ToolId.FLATTEN)).args(key(KeyAction.TOOL_5)).target(palette(5))
                        .wiki("terrain-brushes")
                .build();
    }

    /** The Weather brush (2026-09-30): Erode, then Melt. */
    static Lesson weather() {
        return Lesson.builder("weather")
                .step("pick", toolActive(ToolId.WEATHER)).args(key(KeyAction.TOOL_15)).target(palette(15))
                        .wiki("weather")
                .read("mode").target(row(ToolId.WEATHER, "weather.mode"))
                .step("erode_mode", setting(ToolId.WEATHER, "weather.mode", WeatherSpec.Mode.ERODE))
                        .target(row(ToolId.WEATHER, "weather.mode"))
                .step("erode", Conditions.all(setting(ToolId.WEATHER, "weather.mode", WeatherSpec.Mode.ERODE),
                        strokeEnded(ToolId.WEATHER)))
                .step("melt", setting(ToolId.WEATHER, "weather.mode", WeatherSpec.Mode.MELT))
                        .target(row(ToolId.WEATHER, "weather.mode"))
                .step("melt_stroke", strokeEnded(ToolId.WEATHER)).wiki("weather")
                .build();
    }

    static Lesson paint() {
        return Lesson.builder("paint")
                .step("pick", toolActive(ToolId.PAINT)).args(key(KeyAction.TOOL_6)).target(palette(6))
                        .wiki("terrain-brushes", "paint")
                .step("material", settingChanged(ToolId.PAINT, "material")).args(key(KeyAction.EYEDROPPER))
                        .target(row(ToolId.PAINT, "material"))
                .step("paint", strokeEnded(ToolId.PAINT))
                .step("palette_pick", toolActive(ToolId.PALETTE)).args(key(KeyAction.TOOL_7)).target(palette(7))
                .step("mix", settingChanged(ToolId.PALETTE, "palette")).args(key(KeyAction.EYEDROPPER))
                        .target(row(ToolId.PALETTE, "palette"))
                .step("paint_mix", strokeEnded(ToolId.PALETTE))
                .read("palettes").wiki("palettes")
                .step("mask", anyToolSettingChanged("mask.rules")).target(row(ToolId.PALETTE, "mask.rules"))
                        .wiki("masks")
                .step("mask_paint", any(strokeEnded(ToolId.PALETTE), strokeEnded(ToolId.PAINT)))
                .build();
    }

    /** Mix patterns (2026-09-29): Patches, then a Gradient along an Alt+dragged line. */
    static Lesson patterns() {
        return Lesson.builder("patterns")
                .step("pick", toolActive(ToolId.PALETTE)).args(key(KeyAction.TOOL_7), key(KeyAction.EYEDROPPER))
                        .target(palette(7)).wiki("palettes")
                .step("patches", setting(ToolId.PALETTE, "pattern", PalettePattern.Kind.PATCHES))
                        .target(row(ToolId.PALETTE, "pattern"))
                .step("paint_patches", strokeEnded(ToolId.PALETTE)).target(row(ToolId.PALETTE, "pattern.size"))
                .step("gradient", setting(ToolId.PALETTE, "pattern", PalettePattern.Kind.GRADIENT))
                        .target(row(ToolId.PALETTE, "pattern"))
                .step("line", gradientLineSet())
                .step("paint_gradient", strokeEnded(ToolId.PALETTE)).target(row(ToolId.PALETTE, "pattern.edge"))
                .read("others").wiki("palettes")
                .step("random", setting(ToolId.PALETTE, "pattern", PalettePattern.Kind.RANDOM))
                        .target(row(ToolId.PALETTE, "pattern"))
                .build();
    }

    static Lesson shapes() {
        return Lesson.builder("shapes")
                .step("pick", toolActive(ToolId.SHAPE)).args(key(KeyAction.TOOL_10)).target(palette(10))
                        .wiki("shape-brush")
                .step("click", strokeEnded(ToolId.SHAPE))
                .step("kind", setting(ToolId.SHAPE, "kind", ShapeSpec.Kind.CYLINDER)).target(row(ToolId.SHAPE, "kind"))
                .step("size", any(settingChanged(ToolId.SHAPE, "radius"), settingChanged(ToolId.SHAPE, "height")))
                        .args(key(KeyAction.TOOL_SIZE), key(KeyAction.TOOL_STRENGTH))
                        .target(row(ToolId.SHAPE, "radius"))
                .step("drag", strokeEnded(ToolId.SHAPE))
                .step("select_cone", Conditions.all(setting(ToolId.SELECT, "shape", SelectSettings.SelectShape.CONE),
                                shapeSelected()))
                        .args(key(KeyAction.TOOL_1)).target(row(ToolId.SELECT, "shape")).wiki("select")
                .step("fill_cone", edited()).target(menuRow(CommandMenu.SELECTION, EditorCommands.FILL))
                .step("box_again", setting(ToolId.SELECT, "shape", SelectSettings.SelectShape.BOX))
                        .target(row(ToolId.SELECT, "shape"))
                .build();
    }

    static Lesson symmetry() {
        return Lesson.builder("symmetry")
                .step("pick", toolActive(ToolId.RAISE)).args(key(KeyAction.TOOL_2)).target(palette(2))
                .step("mode", settingNot(ToolId.RAISE, "symmetry", Symmetry.Mode.OFF))
                        .target(row(ToolId.RAISE, "symmetry")).wiki("symmetry")
                .step("centre", symmetryCentreSet())
                        .args(key(KeyAction.SET_SYMMETRY_CENTRE), key(KeyAction.SET_SYMMETRY_CENTRE))
                .step("stroke", strokeEnded(ToolId.RAISE))
                .read("others").wiki("symmetry")
                .step("off", setting(ToolId.RAISE, "symmetry", Symmetry.Mode.OFF)).target(row(ToolId.RAISE, "symmetry"))
                .build();
    }

    // ---- Building ----

    static Lesson library() {
        return Lesson.builder("library")
                .step("select", hasSelection()).args(key(KeyAction.TOOL_1))
                .step("copy", clipboardChanged()).args(key(KeyAction.COPY)).wiki("clipboard")
                .step("open", windowShown(EditorWindows.LIBRARY)).args(key(KeyAction.LIBRARY))
                        .target(menuRow(CommandMenu.FILE, EditorCommands.LIBRARY)).wiki("library")
                .step("save", libraryChanged()).target(new Target.WindowFrame(EditorWindows.LIBRARY))
                .step("load", clipboardChanged()).target(new Target.WindowFrame(EditorWindows.LIBRARY))
                .step("place", edited()).args(key(KeyAction.COMMIT)).wiki("place")
                .build();
    }

    /** The upside-down flip (2026-09-29): a copy pasted turned over. */
    static Lesson flip() {
        return Lesson.builder("flip")
                .step("select", hasSelection()).args(key(KeyAction.TOOL_1))
                .step("copy", clipboardChanged()).args(key(KeyAction.COPY))
                .step("paste", placing()).args(key(KeyAction.PASTE)).wiki("place")
                .step("flip", placementUpsideDown()).args(key(KeyAction.FLIP_UPSIDE_DOWN))
                        .target(menuRow(CommandMenu.EDIT, EditorCommands.FLIP_UPSIDE_DOWN))
                .step("place", edited()).args(key(KeyAction.COMMIT))
                .read("more").args(key(KeyAction.ROTATE_CW), key(KeyAction.FLIP_LEFT_RIGHT)).wiki("place")
                .build();
    }

    /** Files (2026-09-29): an export as .litematic through the Export… dialog, and how files come in. */
    static Lesson export() {
        return Lesson.builder("export")
                .step("copy", clipboardChanged()).args(key(KeyAction.TOOL_1), key(KeyAction.COPY)).wiki("library")
                .step("open", exportDialogOpen()).target(menuRow(CommandMenu.FILE, EditorCommands.EXPORT_CLIPBOARD))
                .step("export", exported()).target(new Target.ExportDialog())
                .read("import").wiki("library")
                .build();
    }

    static Lesson build() {
        return Lesson.builder("build")
                .step("generate_pick", toolActive(ToolId.GENERATE)).args(key(KeyAction.TOOL_11)).target(palette(11))
                        .wiki("generate")
                .step("nodes", pathNodes(2))
                .step("road", edited()).args(key(KeyAction.COMMIT), key(KeyAction.UNDO))
                .step("extrude_pick", toolActive(ToolId.EXTRUDE)).args(key(KeyAction.TOOL_12)).target(palette(12))
                        .wiki("extrude")
                .step("extrude", edited())
                .step("fluid_pick", toolActive(ToolId.FLUID)).args(key(KeyAction.TOOL_13)).target(palette(13))
                        .wiki("fluid")
                .step("fluid", edited()).target(row(ToolId.FLUID, "mode"))
                .build();
    }

    static Lesson scatter() {
        return Lesson.builder("scatter")
                .step("pick", toolActive(ToolId.SCATTER)).args(key(KeyAction.TOOL_9)).target(palette(9))
                        .wiki("scatter")
                .step("mix", scatterMixChanged()).args(key(KeyAction.EYEDROPPER))
                        .target(new Target.WindowFrame(EditorWindows.TOOL_SETTINGS))
                .step("area", scatterAreaPainted())
                .read("preview").target(row(ToolId.SCATTER, "density"))
                .step("commit", edited()).args(key(KeyAction.COMMIT))
                .build();
    }

    /** Tinker (2026-09-29): one block's property by Scroll, then its panel. */
    static Lesson tinker() {
        return Lesson.builder("tinker")
                .step("pick", toolActive(ToolId.TINKER)).args(key(KeyAction.TOOL_14)).target(palette(14))
                        .wiki("tinker")
                .step("aim", tinkerAimed())
                .step("scroll", edited())
                .step("panel", windowShown(EditorWindows.TINKER))
                .step("change", edited()).target(new Target.WindowFrame(EditorWindows.TINKER)).wiki("tinker")
                .build();
    }

    /**
     * Builder mode (2026-09-29) happens outside the editor, where the card doesn't show: each step says what to do out
     * there and to come back, and is checked once the player is back (the history is followed meanwhile).
     */
    static Lesson builder() {
        return Lesson.builder("builder")
                .read("intro").wiki("builder-mode")
                .step("power", Conditions.all(reentered(), builderPowerOn()))
                        .args(vanilla(VanillaKey.TOGGLE), vanilla(VanillaKey.RING), vanilla(VanillaKey.TOGGLE))
                .step("place_undo", Conditions.all(edited(), undone()))
                        .args(vanilla(VanillaKey.TOGGLE), key(KeyAction.UNDO), key(KeyAction.REDO))
                .step("off", Conditions.all(reentered(), noBuilderPower()))
                        .args(vanilla(VanillaKey.RING), vanilla(VanillaKey.TOGGLE)).wiki("builder-mode")
                .build();
    }
}
