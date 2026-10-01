package dev.sculptory.fabric.client.editor.wiki;

import dev.sculptory.fabric.client.editor.tool.ToolId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The wiki's frozen page ids (code links to them, the wiki may add pages but never renames
 * or drops these) and which page explains each tool (the Tool Settings window's <b>?</b>).
 */
public final class WikiPages {
    public static final String HOME = "home";
    public static final String GETTING_STARTED = "getting-started";
    public static final String EDITOR_MODE = "editor-mode";
    public static final String SCREEN_AND_WINDOWS = "screen-and-windows";
    public static final String FINDING_THINGS = "finding-things";
    public static final String TUTORIAL = "tutorial";
    public static final String TOOL_SETTINGS = "tool-settings";
    public static final String PRESETS = "presets";
    public static final String SELECT = "select";
    public static final String SELECTION_OPERATIONS = "selection-operations";
    public static final String TERRAIN_BRUSHES = "terrain-brushes";
    public static final String MASKS = "masks";
    public static final String SHAPE_BRUSH = "shape-brush";
    public static final String GENERATE = "generate";
    public static final String EXTRUDE = "extrude";
    public static final String FLUID = "fluid";
    public static final String SCATTER = "scatter";
    public static final String CLIPBOARD = "clipboard";
    public static final String PLACE = "place";
    public static final String LIBRARY = "library";
    public static final String PALETTES = "palettes";
    public static final String SYMMETRY = "symmetry";
    public static final String HISTORY = "history";
    public static final String KEYS = "keys";
    public static final String SERVER_SETUP = "server-setup";
    public static final String PERMISSIONS = "permissions";
    public static final String TROUBLESHOOTING = "troubleshooting";
    public static final String FAQ = "faq";
    public static final String TINKER = "tinker";
    public static final String BUILDER_MODE = "builder-mode";
    public static final String WEATHER = "weather";
    public static final String NAVIGATION = "navigation";

    /** Every frozen page id. */
    public static final List<String> FROZEN = List.of(HOME, GETTING_STARTED, EDITOR_MODE, SCREEN_AND_WINDOWS,
            FINDING_THINGS, TUTORIAL, TOOL_SETTINGS, PRESETS, SELECT, SELECTION_OPERATIONS, TERRAIN_BRUSHES, MASKS,
            SHAPE_BRUSH, GENERATE, EXTRUDE, FLUID, SCATTER, CLIPBOARD, PLACE, LIBRARY, PALETTES, SYMMETRY, HISTORY, KEYS,
            SERVER_SETUP, PERMISSIONS, TROUBLESHOOTING, FAQ, TINKER, BUILDER_MODE, WEATHER, NAVIGATION);

    /** A page, and the section on it ({@code null} for its top). */
    public record Target(String pageId, String anchor) {
        public Target {
            Objects.requireNonNull(pageId);
        }
    }

    private static final Target TERRAIN = new Target(TERRAIN_BRUSHES, null);
    private static final Target PAINT = new Target(TERRAIN_BRUSHES, "paint");

    private static final Map<ToolId, Target> TOOLS = Map.ofEntries(
            Map.entry(ToolId.SELECT, new Target(SELECT, null)),
            Map.entry(ToolId.RAISE, TERRAIN),
            Map.entry(ToolId.LOWER, TERRAIN),
            Map.entry(ToolId.SMOOTH, TERRAIN),
            Map.entry(ToolId.FLATTEN, TERRAIN),
            Map.entry(ToolId.PAINT, PAINT),
            Map.entry(ToolId.PALETTE, PAINT),
            Map.entry(ToolId.PLACE, new Target(PLACE, null)),
            Map.entry(ToolId.SCATTER, new Target(SCATTER, null)),
            Map.entry(ToolId.SHAPE, new Target(SHAPE_BRUSH, null)),
            Map.entry(ToolId.GENERATE, new Target(GENERATE, null)),
            Map.entry(ToolId.EXTRUDE, new Target(EXTRUDE, null)),
            Map.entry(ToolId.FLUID, new Target(FLUID, null)),
            Map.entry(ToolId.TINKER, new Target(TINKER, null)),
            Map.entry(ToolId.WEATHER, new Target(WEATHER, null)));

    private WikiPages() {}

    /** The page (and section) explaining {@code tool}; the home page for a tool the table doesn't know. */
    public static Target forTool(ToolId tool) {
        return TOOLS.getOrDefault(tool, new Target(HOME, null));
    }

    /** The tools the table knows, for tests. */
    static Map<ToolId, Target> tools() {
        return TOOLS;
    }
}
