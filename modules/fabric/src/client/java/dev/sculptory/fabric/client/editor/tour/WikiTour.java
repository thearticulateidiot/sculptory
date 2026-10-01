package dev.sculptory.fabric.client.editor.tour;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.commands.EditorCommands;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import java.util.ArrayList;
import java.util.List;

/**
 * The wiki pictures: the screenshot tour's wiki mode ({@code scripts/playtest.ps1 -Tour
 * -Wiki}) plays these steps and writes each picture, cropped to the part that helps a reader, straight into
 * {@code docs/wiki/images/<step name>.png}. A step's name is its picture's file name: the page id it belongs to, then
 * what it shows. Every step runs at the run's base UI size and layout (the reference view); as in the ordinary tour, each
 * starts from {@link TourContext#reset}, and the active tool, the selection and the clipboard carry over.
 *
 * <p>The wiki keeps a picture only where it saves words: a tool's Tool Settings or a window, one or
 * two per page. A picture no page shows has no step here.
 */
public final class WikiTour {
    /** The server answers a copy, and the ghost's preview arrives, within a few frames; wait well past that. */
    private static final long COPY_WAIT_MS = 2_500;
    private static final long GHOST_WAIT_MS = 4_000;
    /** The ring opens on the next tick after the key press; wait well past that. */
    private static final long RING_WAIT_MS = 1_500;
    /** The whole screen, scaled down to show the layout. */
    private static final int OVERVIEW_WIDTH = 800;
    /** A crop of the world (many colours) stays under 300 KB at this width; the thumbnail is far smaller anyway. */
    private static final int WORLD_WIDTH = 600;
    /** The ring over the world, the same trade. */
    private static final int RING_WIDTH = 640;
    /** How far up and south of the stairs the player is moved for the world pictures: they fill the crop. */
    private static final double OVERLOOK_HEIGHT = 6.5;

    private WikiTour() {}

    /** The wiki pictures, in the order they are taken. */
    public static List<TourStep<TourContext>> steps() {
        List<TourStep<TourContext>> steps = new ArrayList<>();
        addScreenSteps(steps);
        addSelectionSteps(steps);
        addSettingsSteps(steps);
        addToolSteps(steps);
        addWindowSteps(steps);
        addBatchSteps(steps);
        return steps;
    }

    /**
     * Newer pages: the mix pattern row, the export dialog, Tinker's label on a stair and builder mode's
     * ring. The stairs are placed in the tour world under the pointer; the ring step leaves the editor
     * and comes last, since the next reset (or the end of the tour) reopens it.
     */
    private static void addBatchSteps(List<TourStep<TourContext>> steps) {
        steps.add(step("mix-patterns-row", "Palette Paint: a three-block mix with the Gradient pattern chosen",
                tour -> {
                    // The stairs for the Tinker picture go into the world now (off this picture), so they are there
                    // by the time that step runs.
                    tour.placeStairsUnderPointer(3);
                    tour.selectTool(ToolId.PALETTE);
                    tour.setMixSetting(ToolId.PALETTE, "palette",
                            List.of("minecraft:grass_block", "minecraft:coarse_dirt", "minecraft:stone"),
                            List.of(3, 2, 1));
                    tour.setChoiceSetting(ToolId.PALETTE, "pattern", "GRADIENT");
                    tour.scrollIntoView(tour.toolSettingsForm().field("pattern.seed").orElseThrow());
                }, tour -> tour.windowRowsArea(EditorWindows.TOOL_SETTINGS,
                        tour.toolSettingsForm().field("pattern").orElseThrow(), 78, 72)));
        steps.add(step("import-export-dialog", "The Export dialog for the copied box: the file name and the format row",
                tour -> tour.runCommand(EditorCommands.EXPORT_CLIPBOARD), TourContext::popupArea));
        // The stairs were placed two steps ago. Tinker's label hangs to the lower right of the pointer (under the
        // coordinate readout), so the pointer sits in the crop's upper left.
        steps.add(step("tinker-label", "Tinker aimed at an oak stair: the orange outline and the label beside the pointer",
                tour -> {
                    tour.clearSelection();
                    Box stairs = tour.placeStairsUnderPointer(3);
                    tour.selectTool(ToolId.TINKER);
                    // Down from the tour's height the stairs are a few pixels: move in over them (put back at the end).
                    tour.overlook(stairs, OVERLOOK_HEIGHT);
                }, tour -> tour.pointerArea(250, 140, 0.25, 0.3)).withMinMillis(COPY_WAIT_MS).withFrames(40)
                .withMaxWidth(WORLD_WIDTH));
        // Pictures of the pasted 3x3 and of it flipped with V were tried and dropped: from above, a translucent ghost
        // of stairs reads as a flat slab, and the flip shows no difference (three reviews).
        steps.add(step("builder-mode-ring", "Builder mode's ring of powers over the world, held open with G",
                TourContext::openRing, TourContext::ringArea).withMinMillis(RING_WAIT_MS).withFrames(30)
                .withMaxWidth(RING_WIDTH));
    }

    private static TourStep<TourContext> step(String name, String description, TourStep.Action<TourContext> action,
            TourStep.Crop<TourContext> crop) {
        return TourStep.of(name, description, action).cropped(crop);
    }

    /** The screen and windows, finding things. */
    private static void addScreenSteps(List<TourStep<TourContext>> steps) {
        steps.add(step("screen-and-windows-overview",
                "The whole editor: top bar, windows, a box selected with Select, palette and hint line",
                tour -> {
                    tour.selectTool(ToolId.SELECT);
                    tour.setChoiceSetting(ToolId.SELECT, "shape", "BOX");
                    tour.selectBoxAtPointer(7, 4, 7);
                }, TourContext::screenArea).withMaxWidth(OVERVIEW_WIDTH));
        steps.add(step("screen-and-windows-top-bar", "The top bar's left side: the menus, the active block, Undo, Redo",
                tour -> { }, tour -> tour.topBarArea(true)));
        steps.add(step("finding-things-command-search", "Find a command (Ctrl+K) with \"sel\" typed",
                tour -> tour.openCommandSearch("sel"), TourContext::popupArea));
    }

    /** Select, selection operations, clipboard, place. The overview's box is still selected. */
    private static void addSelectionSteps(List<TourStep<TourContext>> steps) {
        steps.add(step("select-settings", "Tool Settings for Select (Box mode)",
                tour -> tour.selectTool(ToolId.SELECT), tour -> tour.windowArea(EditorWindows.TOOL_SETTINGS)));
        steps.add(step("selection-operations-window", "The Selection window with the box selected",
                tour -> tour.openWindow(EditorWindows.SELECTION), tour -> tour.windowArea(EditorWindows.SELECTION)));
        steps.add(step("clipboard-window", "A 5x4x5 box of ground copied (Ctrl+C); the Clipboard window shows it",
                tour -> {
                    tour.selectTool(ToolId.SELECT);
                    tour.setChoiceSetting(ToolId.SELECT, "shape", "BOX");
                    tour.selectGroundBoxAtPointer(5, 4, 5);
                    tour.copySelection();
                    tour.resizeWindow(EditorWindows.CLIPBOARD, 190, 210);
                }, tour -> tour.windowArea(EditorWindows.CLIPBOARD)).withMinMillis(COPY_WAIT_MS));
        // Pictures of the Place ghost and of a sphere selection were dropped: from the tour's height they read as a
        // wireframe and a box of labels (two reviews). The settings show the Place tool while it places the copy.
        steps.add(step("place-settings", "Tool Settings for Place while it places the copied box",
                tour -> {
                    tour.clearSelection();
                    tour.selectTool(ToolId.PLACE);
                }, tour -> tour.windowArea(EditorWindows.TOOL_SETTINGS)).withMinMillis(GHOST_WAIT_MS));
    }

    /** Tool Settings, masks, symmetry. */
    private static void addSettingsSteps(List<TourStep<TourContext>> steps) {
        steps.add(step("tool-settings-raise", "Tool Settings for the Raise brush",
                tour -> tour.selectTool(ToolId.RAISE), tour -> tour.windowArea(EditorWindows.TOOL_SETTINGS)));
        steps.add(step("masks-section", "Raise: Tool Settings with the Mask section open",
                tour -> {
                    tour.selectTool(ToolId.RAISE);
                    tour.expandSectionOf("mask.rules");
                }, tour -> tour.windowArea(EditorWindows.TOOL_SETTINGS)));
        steps.add(step("symmetry-section", "Raise: the Symmetry section open, Mirror both ways (4) chosen",
                tour -> {
                    tour.selectTool(ToolId.RAISE);
                    tour.setChoiceSetting(ToolId.RAISE, "symmetry", "MIRROR_XZ");
                    tour.expandSectionOf("symmetry");
                }, tour -> tour.windowArea(EditorWindows.TOOL_SETTINGS)));
    }

    /** Each tool's settings. */
    private static void addToolSteps(List<TourStep<TourContext>> steps) {
        steps.add(step("terrain-brushes-settings", "Tool Settings for Palette Paint",
                tour -> {
                    tour.setChoiceSetting(ToolId.RAISE, "symmetry", "OFF");
                    tour.selectTool(ToolId.PALETTE);
                }, tour -> tour.windowArea(EditorWindows.TOOL_SETTINGS)));
        steps.add(settingsOf(ToolId.SHAPE, "shape-brush-settings", "Tool Settings for the Shape brush"));
        steps.add(settingsOf(ToolId.GENERATE, "generate-settings", "Tool Settings for Generate (Path)"));
        steps.add(settingsOf(ToolId.EXTRUDE, "extrude-settings", "Tool Settings for Extrude"));
        steps.add(settingsOf(ToolId.FLUID, "fluid-settings", "Tool Settings for the Fluid tool (Flood)"));
        steps.add(settingsOf(ToolId.SCATTER, "scatter-settings", "Tool Settings for Scatter, with an empty mix"));
    }

    /** The windows of their own pages. */
    private static void addWindowSteps(List<TourStep<TourContext>> steps) {
        steps.add(windowStep(EditorWindows.LIBRARY, "library-window", "The Library window"));
        steps.add(windowStep(EditorWindows.HISTORY, "history-window", "The History window"));
        steps.add(windowStep(EditorWindows.KEYS, "keys-window", "The Keys window"));
    }

    private static TourStep<TourContext> settingsOf(ToolId tool, String name, String description) {
        return step(name, description, tour -> tour.selectTool(tool),
                tour -> tour.windowArea(EditorWindows.TOOL_SETTINGS));
    }

    private static TourStep<TourContext> windowStep(String window, String name, String description) {
        return step(name, description, tour -> tour.openWindow(window), tour -> tour.windowArea(window));
    }
}
