package dev.sculptory.fabric.client.editor.demo;

import java.util.ArrayList;
import java.util.List;

/**
 * The demo's caption texts: every one is a translation key under {@link #PREFIX} in {@code en_us.json}, so the words
 * can be changed without touching the script. {@link #NAME} is the mod's name as the captions show it (one key to
 * change if the name changes). {@link #keys} lists them all for the test that every key has English.
 */
public final class DemoCaptions {
    public static final String PREFIX = "sculptory.demo.";
    private static final List<String> KEYS = new ArrayList<>();

    /** The mod's name in the title and closing captions. */
    public static final String NAME = key("name");
    public static final String PREPARING = key("preparing");
    public static final String READY = key("ready");
    public static final String TITLE = key("title");
    public static final String OPEN_EDITOR = key("open_editor");

    public static final String CHAPTER_BUILDER = key("chapter.builder");
    public static final String CHAPTER_LEARNING = key("chapter.learning");
    public static final String CHAPTER_LIBRARY = key("chapter.library");
    public static final String CHAPTER_LAND = key("chapter.land");
    public static final String CHAPTER_BUILDING = key("chapter.building");
    public static final String CHAPTER_DETAILING = key("chapter.detailing");
    public static final String CHAPTER_CLOSING = key("chapter.closing");

    public static final String SCREEN_PALETTE = key("screen.palette");
    public static final String SCREEN_SETTINGS = key("screen.settings");
    public static final String SCREEN_TOP_BAR = key("screen.top_bar");
    public static final String SCREEN_MASK = key("screen.mask");
    public static final String SCREEN_OPACITY = key("screen.opacity");
    public static final String SCREEN_OUTLINES = key("screen.outlines");
    public static final String SCREEN_UI_SIZE = key("screen.ui_size");
    public static final String SCREEN_DRAG_WINDOW = key("screen.drag_window");
    public static final String SCREEN_RESET_LAYOUT = key("screen.reset_layout");
    public static final String SCREEN_KEYS = key("screen.keys");
    public static final String SCREEN_FIND = key("screen.find");

    public static final String SELECT_DRAW = key("select.draw");
    public static final String SELECT_FILL = key("select.fill");
    public static final String SELECT_HOLLOW = key("select.hollow");
    public static final String SELECT_WALLS = key("select.walls");
    public static final String SELECT_REPLACE = key("select.replace");
    public static final String SELECT_FAMILY = key("select.family");
    public static final String SELECT_UNDO = key("select.undo");

    public static final String BRUSH_JUMP = key("brush.jump");
    public static final String BRUSH_RAISE = key("brush.raise");
    public static final String BRUSH_RAISE_HELD = key("brush.raise_held");
    public static final String BRUSH_SMOOTH = key("brush.smooth");
    public static final String BRUSH_FLATTEN = key("brush.flatten");
    public static final String BRUSH_LOWER = key("brush.lower");
    public static final String BRUSH_SURFACE = key("brush.surface");
    public static final String BRUSH_TERRAIN = key("brush.terrain");

    public static final String PAINT_MIX = key("paint.mix");
    public static final String PAINT_PATCHES = key("paint.patches");
    public static final String PAINT_GRADIENT_LINE = key("paint.gradient_line");
    public static final String PAINT_GRADIENT = key("paint.gradient");
    public static final String PAINT_SAVED = key("paint.saved");
    public static final String PAINT_MASK = key("paint.mask");
    public static final String PAINT_MASK_STROKE = key("paint.mask_stroke");
    public static final String PAINT_MASK_OFF = key("paint.mask_off");

    public static final String SHAPE_SPHERE = key("shape.sphere");
    public static final String SHAPE_CYLINDER = key("shape.cylinder");
    public static final String SHAPE_LINE = key("shape.line");
    public static final String SHAPE_LINE_BUILT = key("shape.line_built");

    public static final String OVERLAY_SELECT = key("overlay.select");
    public static final String OVERLAY_NATURALIZE = key("overlay.naturalize");
    public static final String OVERLAY_OVERLAY = key("overlay.overlay");

    public static final String SYMMETRY_CENTRE = key("symmetry.centre");
    public static final String SYMMETRY_MIRROR = key("symmetry.mirror");

    public static final String PASTE_SELECT = key("paste.select");
    public static final String PASTE_COPY = key("paste.copy");
    public static final String PASTE_GHOST = key("paste.ghost");
    public static final String PASTE_ROTATE = key("paste.rotate");
    public static final String PASTE_MIRROR = key("paste.mirror");
    public static final String PASTE_FLIP = key("paste.flip");
    public static final String PASTE_PLACE = key("paste.place");
    public static final String PASTE_STACK = key("paste.stack");

    public static final String GENERATE_ROAD = key("generate.road");
    public static final String GENERATE_ROAD_BUILT = key("generate.road_built");
    public static final String GENERATE_LINE = key("generate.line");
    public static final String GENERATE_ROOF = key("generate.roof");
    public static final String GENERATE_ROOF_BUILT = key("generate.roof_built");

    public static final String EXTRUDE_OUT = key("extrude.out");
    public static final String EXTRUDE_CARVE = key("extrude.carve");
    public static final String EXTRUDE_SMEAR = key("extrude.smear");

    public static final String FLUID_FLOOD = key("fluid.flood");
    public static final String FLUID_DRAIN = key("fluid.drain");
    public static final String FLUID_BALL = key("fluid.ball");

    public static final String SCATTER_COPY = key("scatter.copy");
    public static final String SCATTER_TREES = key("scatter.trees");
    public static final String SCATTER_PAINT = key("scatter.paint");
    public static final String SCATTER_PLACE = key("scatter.place");

    public static final String TINKER_AIM = key("tinker.aim");
    public static final String TINKER_PANEL = key("tinker.panel");
    public static final String TINKER_ENTITY = key("tinker.entity");

    public static final String LIBRARY_SAVE = key("library.save");
    public static final String LIBRARY_WINDOW = key("library.window");
    public static final String LIBRARY_FOLDER = key("library.folder");
    public static final String LIBRARY_PLACE = key("library.place");
    public static final String LIBRARY_MORE = key("library.more");
    public static final String LIBRARY_PALETTE = key("library.palette");
    public static final String LIBRARY_EXPORT = key("library.export");

    public static final String HISTORY_WINDOW = key("history.window");
    public static final String HISTORY_UNDO = key("history.undo");
    public static final String HISTORY_REDO = key("history.redo");
    public static final String HISTORY_TOP_BAR = key("history.top_bar");

    public static final String TUTORIAL_OPEN = key("tutorial.open");
    public static final String TUTORIAL_GROUPS = key("tutorial.groups");
    public static final String TUTORIAL_LESSON = key("tutorial.lesson");
    public static final String TUTORIAL_LEARN_MORE = key("tutorial.learn_more");
    public static final String TUTORIAL_DONE = key("tutorial.done");
    public static final String TUTORIAL_EXIT = key("tutorial.exit");

    public static final String WIKI_OPEN = key("wiki.open");
    public static final String WIKI_HOME = key("wiki.home");
    public static final String WIKI_PAGE = key("wiki.page");
    public static final String WIKI_PICTURE = key("wiki.picture");
    public static final String WIKI_BACK = key("wiki.back");
    public static final String WIKI_SEARCH = key("wiki.search");
    public static final String WIKI_RESULT = key("wiki.result");

    public static final String BUILDER_INTRO = key("builder.intro");
    public static final String BUILDER_RING = key("builder.ring");
    public static final String BUILDER_POWERS_ON = key("builder.powers_on");
    public static final String BUILDER_PLACE = key("builder.place");
    public static final String BUILDER_UNDO = key("builder.undo");
    public static final String BUILDER_TINKER = key("builder.tinker");
    public static final String BUILDER_OFF = key("builder.off");

    public static final String CLOSING_UNDO = key("closing.undo");
    public static final String CLOSING_TITLE = key("closing.title");

    private DemoCaptions() {}

    private static String key(String suffix) {
        String key = PREFIX + suffix;
        KEYS.add(key);
        return key;
    }

    /** Every caption key, in the order declared. */
    public static List<String> keys() {
        return List.copyOf(KEYS);
    }
}
