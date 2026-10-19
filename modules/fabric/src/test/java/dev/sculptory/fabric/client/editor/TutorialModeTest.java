package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.transform.Resampling;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.commands.CommandMenu;
import dev.sculptory.fabric.client.editor.commands.EditorCommands;
import dev.sculptory.fabric.client.editor.heightmap.HeightmapRef;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.QuickStart;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.input.InputRouter;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.brush.BrushSettings;
import dev.sculptory.fabric.client.editor.tools.brush.TerrainBrushTool;
import dev.sculptory.fabric.client.editor.tools.extrude.ExtrudeSettings;
import dev.sculptory.fabric.client.editor.tools.generate.GenerateSettings;
import dev.sculptory.fabric.client.editor.tools.generate.GenerateTool;
import dev.sculptory.fabric.client.editor.tools.generate.PathModel;
import dev.sculptory.fabric.client.editor.tools.place.GhostBaker;
import dev.sculptory.fabric.client.editor.tools.place.PlaceSettings;
import dev.sculptory.fabric.client.editor.tools.points.PointEditor;
import dev.sculptory.fabric.client.editor.tools.select.SelectSettings;
import dev.sculptory.fabric.client.editor.windows.ExportDialog;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.editor.tutorial.LessonUndo;
import dev.sculptory.fabric.client.editor.tutorial.Lessons;
import dev.sculptory.fabric.client.editor.tutorial.Tutorial;
import dev.sculptory.fabric.client.editor.tutorial.TutorialRunner;
import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Menu;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.session.Notice;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * Tutorial mode on the whole editor UI: Help > Tutorial, View > Tutorial and the quick
 * start card's Start tutorial; a lesson played with real keys and editor state; the step highlight on each kind of
 * target; Exit and Resume; leaving the editor mid-lesson; and "Undo what this lesson made" through the real undo.
 */
class TutorialModeTest {
    private final EditorTestRig rig = new EditorTestRig();
    private final UiScale scale = new UiScale();
    private long now = 1_000;
    private final ToastStack toasts = new ToastStack(() -> now);
    private EditorUi ui;
    private Tutorial tutorial;
    private InputRouter router;

    @BeforeEach
    void build() {
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, McFontText.INSTANCE, toasts,
                new EditorUi.Services(English.INSTANCE, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, scale));
        rig.controller.setUi(ui);
        rig.confirmer = ui;
        ui.setEditing(true);
        rig.mode.enter();
        tutorial = ui.tutorial();
        router = new InputRouter(ui, rig.controller, rig.controller, new InputRouter.Look() {
            @Override
            public boolean isLooking() {
                return false;
            }

            @Override
            public void begin() {}

            @Override
            public void end() {}
        }, new InputRouter.Movement() {
            @Override
            public boolean isMovementKey(int key, int scanCode) {
                return false;
            }

            @Override
            public void press(int key, int scanCode) {}
        }, rig.keymap);
        frame();
    }

    /** One frame at 640x360 (100%). */
    private void frame() {
        frame(false);
    }

    private void frame(boolean looking) {
        ui.layout(640, 360);
        ui.render(new RecordingGraphics(), 0, 0, now, looking);
        now += 16;
    }

    /** Frames until the step's check has shown and the next step began. */
    private void settle() {
        frame();
        now += TutorialRunner.DONE_SHOW_MS;
        frame();
    }

    private void press(int key, int modifiers) {
        router.keyPressed(key, 0, modifiers);
        router.keyReleased(key, 0, modifiers);
    }

    private TutorialRunner runner() {
        return tutorial.runner();
    }

    private String step() {
        return runner().step().map(step -> step.name()).orElse(runner().phase().toString());
    }

    private void start(String lesson) {
        ui.commands().run(EditorCommands.TUTORIAL);
        tutorial.window().refresh();
        tutorial.window().lessonRow(lesson).click();
        frame();
    }

    /** Skips steps until {@code name} shows. */
    private void skipTo(String name) {
        for (int i = 0; i < 12 && !step().equals(name); i++) {
            tutorial.card().skipButton().click();
            frame();
        }
        assertEquals(name, step());
    }

    // ---- Opening it ----

    @Test
    void helpTutorialViewTutorialAndTheQuickStartCardOpenTheLessons() {
        assertTrue(ui.commands().run(EditorCommands.TUTORIAL).enabled(), "Help > Tutorial");
        assertTrue(ui.windows().isOpen(EditorWindows.TUTORIAL));
        assertTrue(ui.commands().get(EditorCommands.TUTORIAL_WINDOW)
                .map(command -> ui.commands().isVisible(command)).orElse(false), "View > Tutorial");
        ui.commands().run(EditorCommands.TUTORIAL_WINDOW);
        assertFalse(ui.windows().isOpen(EditorWindows.TUTORIAL), "View > Tutorial toggles it");
        assertTrue(ui.commands().menuItems(CommandMenu.HELP).stream().anyMatch(item -> item.label().equals("Tutorial")));

        boolean[] seen = {false};
        ui.setQuickStartFlag(new QuickStart.Flag() {
            @Override
            public boolean seen() {
                return seen[0];
            }

            @Override
            public void markSeen() {
                seen[0] = true;
            }
        });
        ui.showQuickStartIfNew();
        assertTrue(ui.quickStart().isShown());
        ui.quickStart().startTutorialButton().click();
        assertFalse(ui.quickStart().isShown(), "the card's next step is the tutorial");
        assertTrue(seen[0], "and it counts as seen");
        assertTrue(ui.windows().isOpen(EditorWindows.TUTORIAL));
        tutorial.window().refresh();
        assertEquals(Lessons.all().size(), Lessons.all().stream().filter(lesson ->
                !tutorial.window().checked(lesson.id())).count(), "nothing finished yet");
    }

    @Test
    void startingALessonClosesTheWindowAndShowsTheCardUnderTheTopBar() {
        start("select");
        assertFalse(ui.windows().isOpen(EditorWindows.TUTORIAL), "the world and the card are in view");
        assertTrue(tutorial.cardShown());
        Rect card = tutorial.card().node().bounds();
        assertTrue(card.width() > 0 && card.height() > 0, "laid out: " + card);
        assertEquals((ui.uiWidth() - card.width()) / 2, card.x(), "centred");
        assertTrue(card.y() > 0 && card.y() < 40, "under the top bar: " + card);
        assertEquals("Select", tutorial.card().titleLabel().text());
        assertEquals("Step 1 of 8", tutorial.card().progressLabel().text());
        assertTrue(ui.isOverUi(card.x() + 2, card.y() + 2), "the card takes the mouse");
        assertFalse(ui.hasKeyboardFocus(), "but not the keyboard: keys go to the editor");
    }

    // ---- A lesson with real keys and state ----

    @Test
    void theSelectLessonTicksOffAsThePlayerDoesEachStep() {
        press(GLFW.GLFW_KEY_2, 0);
        start("select");
        frame();
        assertFalse(runner().stepDone(), "Raise is active");
        assertEquals(ui.paletteSlotBounds(1), tutorial.highlight(), "slot 1 is outlined");
        press(GLFW.GLFW_KEY_1, 0);
        frame();
        assertTrue(runner().stepDone());
        assertTrue(tutorial.card().bodyLabel().text().startsWith("✓ "), "the check shows");
        assertEquals(Optional.empty(), tutorial.highlight(), "no outline while the check shows");
        now += TutorialRunner.DONE_SHOW_MS;
        frame();
        assertEquals("box", step());

        rig.platform.pickBlockAt(5, 6, 7);
        rig.controller.pointer(dev.sculptory.fabric.client.editor.tool.PointerEvent.Kind.PRESS,
                dev.sculptory.fabric.client.editor.tool.PointerEvent.LEFT, 10, 10, 0);
        rig.controller.pointer(dev.sculptory.fabric.client.editor.tool.PointerEvent.Kind.RELEASE,
                dev.sculptory.fabric.client.editor.tool.PointerEvent.LEFT, 10, 10, 0);
        settle();
        assertEquals("resize", step());
        press(GLFW.GLFW_KEY_UP, 0);
        settle();
        assertEquals("nudge", step());
        press(GLFW.GLFW_KEY_PAGE_UP, 0);
        settle();
        assertEquals("shape", step());
        rig.ctx.updateSettings(ToolId.SELECT, rig.ctx.settings(ToolId.SELECT)
                .with(SelectSettings.SHAPE, SelectSettings.SelectShape.SPHERE));
        settle();
        assertEquals("magic", step());
        rig.ctx.updateSettings(ToolId.SELECT, rig.ctx.settings(ToolId.SELECT)
                .with(SelectSettings.MODE, SelectSettings.Mode.MAGIC));
        frame();
        assertFalse(runner().stepDone(), "Magic is set, but nothing was selected with it yet");
        rig.ctx.setSelectionRegion(new Region.Cells(CellSet.builder().add(1, 2, 3).add(1, 3, 3).build()));
        settle();
        assertEquals("box_again", step());
        rig.ctx.updateSettings(ToolId.SELECT, rig.ctx.settings(ToolId.SELECT)
                .with(SelectSettings.MODE, SelectSettings.Mode.BOX).with(SelectSettings.SHAPE,
                        SelectSettings.SelectShape.BOX));
        settle();
        assertEquals("clear", step());
        press(GLFW.GLFW_KEY_D, GLFW.GLFW_MOD_CONTROL);
        settle();
        assertEquals(TutorialRunner.Phase.AFTER, runner().phase(), "no edits: straight to the lesson's end");
        assertEquals("This lesson made no changes to your world.", tutorial.card().bodyLabel().text());
        assertTrue(runner().progress().isCompleted("select"));
        tutorial.card().nextLessonButton().click();
        frame();
        assertEquals("edit", runner().lesson().orElseThrow().id());
    }

    @Test
    void theMenusLessonFollowsTheMenuBarTheSearchTheKeySheetAndTheWindows() {
        start("menus");
        Rect viewTitle = ui.menuBar().titleBounds(CommandMenu.VIEW.ordinal());
        assertEquals(Optional.of(viewTitle), tutorial.highlight());
        ui.openMenu(EditorUi.MENU_VIEW);
        settle();
        assertEquals("notifications", step());
        Menu view = ui.menuBar().openMenu().orElseThrow();
        int row = -1;
        for (int i = 0; i < view.items().size(); i++) {
            if (view.items().get(i).label().equals("Notifications")) {
                row = i;
            }
        }
        assertEquals(Optional.of(view.rowBounds(row)), tutorial.highlight(), "the row in the open menu");
        ui.commands().run(EditorCommands.NOTIFICATIONS);
        ui.menuBar().close();
        settle();
        assertEquals("search", step());
        press(GLFW.GLFW_KEY_K, GLFW.GLFW_MOD_CONTROL);
        frame();
        assertTrue(ui.commandSearch().isOpen());
        assertFalse(runner().stepDone(), "open, not closed yet");
        Rect card = tutorial.card().node().bounds();
        assertTrue(card.y() > ui.uiHeight() / 2, "the card moved down out of the search's way: " + card);
        press(GLFW.GLFW_KEY_ESCAPE, 0);
        settle();
        assertEquals("key_sheet", step());
        // The search closed: the card goes back where it covers no window's title bar (Notifications opened just
        // before sits in the space between Selection and Tool Settings, under the top place).
        Rect back = tutorial.card().node().bounds();
        for (Window window : ui.windows().windows()) {
            if (window.isOpen()) {
                assertFalse(back.intersects(window.titleBarRect(Theme.DARK)),
                        back + " covers the title bar of " + window.id());
            }
        }
        press(GLFW.GLFW_KEY_F1, 0);
        frame();
        press(GLFW.GLFW_KEY_F1, 0);
        settle();
        assertEquals("f6", step());
        press(GLFW.GLFW_KEY_F6, 0);
        settle();
        assertEquals("hide", step());
        press(GLFW.GLFW_KEY_ESCAPE, 0);
        press(GLFW.GLFW_KEY_TAB, 0);
        frame();
        assertTrue(ui.windows().isAllHidden());
        assertTrue(tutorial.cardShown(), "the card is not a window: it stays");
        press(GLFW.GLFW_KEY_TAB, 0);
        settle();
        assertEquals("windows", step());
        assertTrue(rig.mode.isActive(), "no key of the lesson left the editor");
    }

    // ---- Highlights ----

    @Test
    void aSettingRowIsOutlinedInToolSettingsOrItsClosedSectionsHeader() {
        start("select");
        skipTo("shape");
        SettingsForm form = ui.toolSettings().form().orElseThrow();
        Window settings = ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow();
        Rect row = form.field("shape").orElseThrow().bounds().intersect(settings.contentRect(dev.sculptory
                .fabric.client.editor.ui.Theme.DARK));
        assertEquals(Optional.of(row), tutorial.highlight());

        start("symmetry");
        press(GLFW.GLFW_KEY_2, 0);
        settle();
        assertEquals("mode", step());
        frame();
        Rect header = ui.toolSettings().form().orElseThrow().section("sculptory.setting.brush.symmetry_section")
                .orElseThrow().headerRect(ui.windows().context());
        assertEquals(Optional.of(header), tutorial.highlight(), "the Symmetry section is closed at first");

        ui.toggleWindow(EditorWindows.TOOL_SETTINGS);
        frame();
        assertEquals(Optional.empty(), tutorial.highlight(), "a closed window: nothing to outline");
    }

    @Test
    void theTopBarsItemsAndWindowsAreOutlined() {
        start("getting_around");
        skipTo("fly_speed");
        Optional<Rect> fly = tutorial.highlight();
        assertTrue(fly.isPresent());
        assertTrue(fly.get().y() < 20, "in the top bar: " + fly.get());
        rig.controller.flySpeed(1, 0);
        settle();
        assertEquals("esc", step());

        start("history");
        skipTo("jump");
        assertEquals(Optional.empty(), tutorial.highlight(), "the History window is closed");
        press(GLFW.GLFW_KEY_H, 0);
        frame();
        assertEquals(ui.windows().window(EditorWindows.HISTORY).map(Window::rect), tutorial.highlight());
    }

    @Test
    void learnMoreOpensTheStepsPageInTheWikiWindow() {
        start("paint");
        assertTrue(tutorial.card().shownButtons().contains(tutorial.card().learnMoreButton()));
        tutorial.card().learnMoreButton().click();
        assertTrue(ui.windows().isOpen(EditorWindows.WIKI), "EditorUi.openWiki");
        assertTrue(tutorial.cardShown(), "the lesson goes on");

        List<String> opened = new ArrayList<>();
        tutorial.setWiki((page, anchor) -> opened.add(page + "#" + anchor));
        tutorial.card().learnMoreButton().click();
        assertEquals(List.of("terrain-brushes#paint"), opened, "the page and section of Paint");

        tutorial.setWiki(dev.sculptory.fabric.client.editor.tutorial.WikiOpener.NONE);
        start("paint");
        assertFalse(tutorial.card().shownButtons().contains(tutorial.card().learnMoreButton()), "no wiki, no button");
    }

    // ---- Exit, Resume, leaving the editor ----

    @Test
    void exitPausesTheLessonAndResumeGoesOnAtTheSameStep() {
        start("select");
        skipTo("nudge");
        tutorial.card().exitButton().click();
        frame();
        assertFalse(tutorial.cardShown());
        assertFalse(tutorial.card().node().isVisible(), "the card is gone");
        assertEquals(Notice.of(Notice.Level.INFO, Tutorial.NOTICE_PAUSED), rig.notices.get(rig.notices.size() - 1));
        ui.commands().run(EditorCommands.TUTORIAL);
        tutorial.window().refresh();
        assertTrue(tutorial.window().resumeShown());
        assertEquals("Select, step 4 of 8", tutorial.window().resumeText());
        tutorial.window().resumeButton().click();
        frame();
        assertTrue(tutorial.cardShown());
        assertEquals("nudge", step());
        assertFalse(ui.windows().isOpen(EditorWindows.TUTORIAL));
    }

    @Test
    void leavingTheEditorPausesTheLessonAndComingBackTicksTheLeaveStep() {
        start("getting_around");
        skipTo("leave");
        rig.mode.exit(ExitReason.TOGGLED);
        ui.setEditing(false);
        ui.layout(640, 360);
        assertFalse(tutorial.card().node().isVisible(), "no card outside the editor");
        assertFalse(runner().stepDone(), "nothing is checked while the editor is closed");
        rig.mode.enter();
        ui.setEditing(true);
        frame();
        assertTrue(runner().stepDone(), "left and came back");
        assertTrue(tutorial.card().node().isVisible());
    }

    @Test
    void lookingAroundAndMovingTickTheFirstLesson() {
        start("getting_around");
        tutorial.card().skipButton().click();
        frame();
        assertEquals("look", step());
        frame(true);
        settle();
        assertEquals("move", step());
        rig.platform.eyeAt(0, 64, 0);
        frame();
        rig.platform.eyeAt(0, 64, 2);
        frame();
        assertFalse(runner().stepDone(), "2 blocks");
        rig.platform.eyeAt(0, 64, 4);
        frame();
        assertTrue(runner().stepDone(), "4 blocks");
    }

    // ---- The lesson's end ----

    /**
     * An edit of a box at {@code x}, run to the end on the mock server: one history entry. The mock labels entries by
     * their op alone ("Fill"), so edits alternate Fill and Walls: a history of one repeated label is a case the
     * tutorial reads conservatively (LessonEditsTest), and the real server's labels carry block counts.
     */
    private void fill(int x) {
        rig.ctx.setSelection(new Box(new BlockPos(x, 60, 0), new BlockPos(x + 2, 62, 2)));
        long before = rig.session.history().version();
        if (x % 2 == 0) {
            rig.actions.fill();
        } else {
            rig.actions.walls();
        }
        for (int i = 0; i < 200 && rig.session.history().version() == before; i++) {
            rig.session.tick();
        }
        assertTrue(rig.session.history().version() > before, "the edit finished");
    }

    @Test
    void undoWhatThisLessonMadeUndoesOnlyItsOwnEditsThroughTheRealUndo() {
        fill(0);
        fill(1);
        List<String> before = rig.session.history().undoLabels();
        assertEquals(2, before.size());
        start("edit");
        skipTo("fill");
        fill(2);
        settle();
        assertEquals("replace", step());
        fill(3);
        fill(4);
        skipTo("erase");
        tutorial.card().skipButton().click();
        frame();
        assertEquals(TutorialRunner.Phase.FINISHED, runner().phase());
        assertEquals(3, runner().made());
        assertEquals("This lesson made 3 changes to your world. Undo them, or keep what you built?",
                tutorial.card().bodyLabel().text());
        assertEquals(List.of("Undo what this lesson made", "Keep it"),
                tutorial.card().shownButtons().stream().map(button -> button.text()).toList());

        tutorial.card().undoButton().click();
        for (int i = 0; i < 10 && runner().phase() == TutorialRunner.Phase.UNDOING; i++) {
            frame();
        }
        assertEquals(TutorialRunner.Phase.AFTER, runner().phase());
        LessonUndo undo = runner().undo().orElseThrow();
        assertEquals(LessonUndo.Outcome.DONE, undo.outcome());
        assertEquals(3, undo.undone());
        assertEquals(before, rig.session.history().undoLabels(), "exactly the two fills from before the lesson stay");
        assertEquals(3, rig.session.history().redoLabels().size(), "the lesson's three are undone, and can be redone");
        assertEquals("Undid all 3 changes this lesson made.", tutorial.card().bodyLabel().text());
        assertTrue(rig.notices.contains(Notice.of(Notice.Level.INFO, Tutorial.NOTICE_UNDID, "3", "3")));
    }

    /**
     * An edit labelled like the only entry on the redo list (a Fill undone before the lesson, then a Fill in it): from
     * the labels alone it reads as a redo of the entry from before as well, and was left alone; the session says it
     * was an edit (HistoryMirror.Cause), so it is the lesson's and is undone at the end. A redo of the lesson's own
     * entry while the lesson is paused stays the lesson's.
     */
    @Test
    void anEditLabelledLikeTheOnlyRedoEntryIsTheLessonsAndIsUndoneAtTheEnd() {
        fill(0);
        rig.session.undo();
        assertEquals(List.of("Fill"), rig.session.history().redoLabels());
        start("edit");
        skipTo("fill");
        fill(2);
        settle();
        assertEquals(1, tutorial.edits().owned(), "the new Fill is the lesson's");
        tutorial.card().exitButton().click();
        rig.session.undo();
        rig.session.redo();
        frame();
        assertEquals(1, tutorial.edits().owned(), "undone and redone while paused: still the lesson's");
        tutorial.runner().resume();
        frame();
        skipTo("erase");
        tutorial.card().skipButton().click();
        frame();
        assertEquals(1, runner().made());
        tutorial.card().undoButton().click();
        for (int i = 0; i < 10 && runner().phase() == TutorialRunner.Phase.UNDOING; i++) {
            frame();
        }
        assertEquals(LessonUndo.Outcome.DONE, runner().undo().orElseThrow().outcome());
        assertEquals(1, runner().undo().orElseThrow().undone());
        assertEquals(List.of(), rig.session.history().undoLabels());
        assertEquals(List.of("Fill"), rig.session.history().redoLabels(),
                "the lesson's Fill, redoable (the new edit had dropped the one from before)");
    }

    @Test
    void anUndoOrRedoAlreadyRunningIsWaitedForThenTheLessonsEditsAreUndone() {
        start("edit");
        skipTo("fill");
        fill(0);
        fill(1);
        skipTo("erase");
        tutorial.card().skipButton().click();
        frame();
        rig.session.setHistoryBusy(true);
        tutorial.card().undoButton().click();
        for (int i = 0; i < 5; i++) {
            frame();
        }
        assertEquals(TutorialRunner.Phase.UNDOING, runner().phase(), "waits while another step runs");
        assertEquals(2, rig.session.history().undoLabels().size(), "nothing undone yet");
        assertEquals("Undoing 1 of 2…", tutorial.card().bodyLabel().text());
        rig.session.setHistoryBusy(false);
        frame();
        assertEquals(2, rig.session.history().undoLabels().size(),
                "that step's history may still be on its way: a moment of quiet first");
        for (int i = 0; i < 40 && runner().phase() == TutorialRunner.Phase.UNDOING; i++) {
            frame();
        }
        assertEquals(TutorialRunner.Phase.AFTER, runner().phase());
        assertEquals(2, runner().undo().orElseThrow().undone());
        assertEquals(List.of(), rig.session.history().undoLabels());
    }

    @Test
    void anEditMadeWhileTheLessonWasPausedStopsTheUndoAtItAndTheCardSaysSo() {
        start("edit");
        skipTo("fill");
        fill(0);
        tutorial.card().exitButton().click();
        fill(1);
        tutorial.runner().resume();
        frame();
        skipTo("erase");
        tutorial.card().skipButton().click();
        frame();
        assertEquals(1, runner().made(), "the edit made while paused is not the lesson's");
        tutorial.card().undoButton().click();
        frame();
        frame();
        assertEquals(TutorialRunner.Phase.AFTER, runner().phase());
        assertEquals(LessonUndo.Outcome.FOREIGN, runner().undo().orElseThrow().outcome());
        assertEquals(0, runner().undo().orElseThrow().undone());
        assertEquals(2, rig.session.history().undoLabels().size(), "nothing undone");
        assertEquals("Undid 0 of 1 changes, then stopped at a change this lesson didn't make.",
                tutorial.card().bodyLabel().text());
    }

    @Test
    void aReconnectStartsTheHistoryOverSoWhatTheServerSendsThenIsNotTheLessons() {
        start("edit");
        skipTo("fill");
        fill(0);
        assertEquals(1, tutorial.edits().owned());
        rig.session.setState(dev.sculptory.fabric.client.session.SessionState.HANDSHAKING);
        rig.session.undo();
        assertEquals(0, tutorial.edits().owned(), "not connected: the history followed from scratch");
        rig.session.setState(dev.sculptory.fabric.client.session.SessionState.READY);
        fill(1);
        assertEquals(0, tutorial.edits().owned(), "the connection's first history: none of it the lesson's");
        fill(2);
        assertEquals(0, tutorial.edits().owned(), "a single saved entry could still be loading: not taken");
        frame();
        now += Tutorial.LOAD_WINDOW_MS + 1;
        frame();
        fill(3);
        assertEquals(1, tutorial.edits().owned(), "once no load can come, the lesson's edits are its own again");
    }

    @Test
    void keepItLeavesTheLessonsEdits() {
        start("edit");
        skipTo("fill");
        fill(0);
        skipTo("erase");
        tutorial.card().skipButton().click();
        frame();
        tutorial.card().keepButton().click();
        frame();
        assertEquals(TutorialRunner.Phase.AFTER, runner().phase());
        assertEquals(1, rig.session.history().undoLabels().size());
        assertEquals("Kept what this lesson made.", tutorial.card().bodyLabel().text());
        tutorial.card().lessonsButton().click();
        frame();
        assertFalse(tutorial.cardShown());
        assertTrue(ui.windows().isOpen(EditorWindows.TUTORIAL), "Back to lessons");
        tutorial.window().refresh();
        assertTrue(tutorial.window().checked("edit"));
        String tooltip = tutorial.window().lessonRow("edit").tooltip();
        assertTrue(tooltip.endsWith("\nFinished · Click to do it again from its first step"), tooltip);
        tutorial.window().lessonRow("edit").click();
        frame();
        assertEquals("edit", runner().lesson().orElseThrow().id(), "a click on a finished lesson starts it again");
        assertEquals(TutorialRunner.Phase.STEP, runner().phase());
        assertEquals(0, runner().stepIndex());
        assertFalse(ui.windows().isOpen(EditorWindows.TUTORIAL));
    }

    // ---- Tinker, mix patterns, the flip, files, builder mode ----

    @Test
    void theTinkerLessonPicksTheToolThenCountsItsChangesAndItsPanel() {
        start("tinker");
        assertEquals(ui.paletteSlotBounds(14), tutorial.highlight(), "slot 14 is outlined");
        press(GLFW.GLFW_KEY_RIGHT_BRACKET, 0);
        settle();
        assertEquals("aim", step());
        // The mock world is air: nothing for Tinker to aim at here (ConditionsTest covers the aim condition).
        tutorial.card().skipButton().click();
        frame();
        assertEquals("scroll", step());
        fill(0); // a Tinker change is one history entry, as a fill is
        settle();
        assertEquals("panel", step());
        assertEquals(Optional.empty(), tutorial.highlight(), "nothing to point at until the panel opens");
        ui.toggleWindow(EditorWindows.TINKER);
        settle();
        assertEquals("change", step());
        frame();
        assertEquals(ui.windows().window(EditorWindows.TINKER).map(Window::rect), tutorial.highlight());
        fill(1);
        settle();
        assertEquals(TutorialRunner.Phase.FINISHED, runner().phase());
        assertEquals(2, runner().made());
        tutorial.card().undoButton().click();
        for (int i = 0; i < 10 && runner().phase() == TutorialRunner.Phase.UNDOING; i++) {
            frame();
        }
        assertEquals(LessonUndo.Outcome.DONE, runner().undo().orElseThrow().outcome());
        assertEquals(2, runner().undo().orElseThrow().undone());
        assertEquals(List.of(), rig.session.history().undoLabels(), "both Tinker changes undone");
    }

    @Test
    void theMixPatternsLessonFollowsThePatternSettingAndTheGradientLine() {
        start("patterns");
        press(GLFW.GLFW_KEY_7, 0);
        settle();
        assertEquals("patches", step());
        frame();
        assertEquals(settingRow("pattern"), tutorial.highlight(), "the Pattern row");
        setPattern(PalettePattern.Kind.PATCHES);
        settle();
        assertEquals("paint_patches", step());
        tutorial.card().skipButton().click(); // strokes are counted by EditorProbe.StrokeCount (StrokeCountTest)
        frame();
        assertEquals("gradient", step());
        setPattern(PalettePattern.Kind.GRADIENT);
        settle();
        assertEquals("line", step());
        assertFalse(runner().stepDone());
        TerrainBrushTool palette = (TerrainBrushTool) rig.ctx.tools().get(ToolId.PALETTE).orElseThrow();
        assertTrue(palette.symmetryCentre().gradientLine().set(new BlockPos(0, 64, 0), new BlockPos(10, 64, 0)));
        settle();
        assertEquals("paint_gradient", step());
        skipTo("random");
        assertFalse(runner().stepDone(), "Gradient is still set");
        setPattern(PalettePattern.Kind.RANDOM);
        settle();
        assertEquals(TutorialRunner.Phase.AFTER, runner().phase(), "no edits: straight to the lesson's end");
    }

    private Optional<Rect> settingRow(String key) {
        SettingsForm form = ui.toolSettings().form().orElseThrow();
        Window settings = ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow();
        return Optional.of(form.field(key).orElseThrow().bounds().intersect(settings.contentRect(Theme.DARK)));
    }

    private void setPattern(PalettePattern.Kind kind) {
        SettingsValues values = rig.ctx.settings(ToolId.PALETTE);
        @SuppressWarnings("unchecked")
        SettingDef<Object> def = (SettingDef<Object>) values.schema().def("pattern").orElseThrow();
        rig.ctx.updateSettings(ToolId.PALETTE, values.with(def, kind));
    }

    @Test
    void theFlipLessonSeesThePasteTheFlipAndThePlacement() {
        start("flip");
        rig.ctx.setSelection(new Box(new BlockPos(0, 60, 0), new BlockPos(2, 61, 2)));
        settle();
        assertEquals("copy", step());
        press(GLFW.GLFW_KEY_C, GLFW.GLFW_MOD_CONTROL);
        settle();
        assertEquals("paste", step());
        ClipboardCache.Entry entry = rig.session.clipboards().current().orElseThrow();
        rig.session.putPreview(new SourceRef.Clipboard(entry.clipboardId()), preview(entry, new BlockPos(3, 2, 3)));
        press(GLFW.GLFW_KEY_V, GLFW.GLFW_MOD_CONTROL);
        settle();
        assertEquals("flip", step());
        assertTrue(rig.place.placement().isPresent(), "a ghost of known size");
        // The ghost follows the cursor onto a block (a tool frame with a cursor hit), so Enter can place it.
        rig.platform.pickBlockAt(5, 60, 5);
        rig.controller.frame(10, 10, now * 1_000_000L, 0f);
        assertEquals(Optional.of(ui.menuBar().titleBounds(CommandMenu.EDIT.ordinal())), tutorial.highlight(),
                "the Edit menu holds Flip upside down");
        press(GLFW.GLFW_KEY_V, 0);
        frame();
        assertTrue(rig.place.placement().orElseThrow().transform().upsideDown());
        settle();
        assertEquals("place", step());
        press(GLFW.GLFW_KEY_ENTER, 0);
        for (int i = 0; i < 200 && !runner().stepDone(); i++) {
            rig.session.tick();
            frame();
        }
        assertTrue(runner().stepDone(), "the paste is one history entry");
        settle();
        assertEquals("more", step());
        tutorial.card().skipButton().click();
        frame();
        assertEquals(TutorialRunner.Phase.FINISHED, runner().phase());
        assertEquals(1, runner().made());
    }

    private ClipboardCache.Preview preview(ClipboardCache.Entry entry, BlockPos dims) {
        BlockBuffer cells = new BlockBuffer();
        cells.set(0, 0, 0, rig.states.parse("minecraft:oak_stairs[facing=north]"));
        GhostVolume volume = GhostVolume.of(cells, GhostBaker.air(rig.states));
        volume.setFrame(new Box(BlockPos.ORIGIN, dims.offset(-1, -1, -1)));
        return new ClipboardCache.Preview(entry.clipboardId().toString(), dims, entry.anchor(), 2, volume, 1024, 0);
    }

    @Test
    void theExportLessonOpensTheDialogFromTheFileMenuAndCountsTheExport() {
        start("export");
        rig.ctx.setSelection(Box.of(new BlockPos(1, 60, 1)));
        press(GLFW.GLFW_KEY_C, GLFW.GLFW_MOD_CONTROL);
        settle();
        assertEquals("open", step());
        assertEquals(Optional.of(ui.menuBar().titleBounds(CommandMenu.FILE.ordinal())), tutorial.highlight(),
                "the File menu holds Export…");
        ui.commands().run(EditorCommands.EXPORT_CLIPBOARD);
        frame();
        assertTrue(ui.windows().context().popups().isOpen(), "the Export… dialog");
        settle();
        assertEquals("export", step());
        Optional<Rect> dialog = tutorial.highlight();
        assertTrue(dialog.isPresent(), "the dialog is outlined");
        assertEquals(ExportDialog.bounds(ui.windows().context().popups()), dialog);
        rig.clipboard.exportClipboard(SchematicFormat.LITEMATIC, "tutorial");
        ui.windows().context().popups().closeAll();
        settle();
        assertEquals("import", step());
        assertEquals(Optional.empty(), tutorial.highlight(), "closed: nothing to outline");
        assertTrue(rig.session.calls().stream().anyMatch(call -> call.kind().equals("export")), "exported");
        tutorial.card().skipButton().click();
        frame();
        assertEquals(TutorialRunner.Phase.AFTER, runner().phase(), "an export changes nothing in the world");
    }

    @Test
    void theBuilderModeLessonIsCheckedWhenThePlayerComesBackIntoTheEditor() {
        boolean[] power = {false};
        tutorial.setBuilderMode(() -> power[0], () -> "G");
        start("builder");
        assertEquals("intro", step());
        tutorial.card().skipButton().click();
        frame();
        assertEquals("power", step());
        assertTrue(tutorial.card().bodyLabel().text().contains("Hold G:"), tutorial.card().bodyLabel().text());
        leaveTheEditor();
        power[0] = true;
        assertFalse(runner().stepDone(), "nothing is checked while the editor is closed");
        comeBack();
        settle();
        assertEquals("place_undo", step());
        leaveTheEditor();
        fill(0); // a placement with a power on: one history entry, made while the editor is closed
        rig.session.undo(); // Ctrl+Z out there: the session's own undo step
        comeBack();
        settle();
        assertEquals("off", step());
        leaveTheEditor();
        comeBack();
        assertFalse(runner().stepDone(), "back, but the power is still on");
        leaveTheEditor();
        power[0] = false;
        comeBack();
        settle();
        assertEquals(TutorialRunner.Phase.AFTER, runner().phase(),
                "the placement was undone out there: nothing is left to undo");
        assertEquals("This lesson made no changes to your world. That was the last lesson.",
                tutorial.card().bodyLabel().text());
        assertEquals(List.of("Fill"), rig.session.history().redoLabels(), "the undone placement, redoable");
    }

    // ---- Generate's road, path points and Smart Surface; free rotation; the Heightmap brush; biomes ----

    private GenerateTool generate() {
        return (GenerateTool) rig.ctx.tools().get(ToolId.GENERATE).orElseThrow();
    }

    private void setGenerate(GenerateSettings.Kind kind) {
        set(ToolId.GENERATE, "kind", kind);
    }

    /** Sets a tool's setting {@code key}, as its control in Tool Settings does. */
    private void set(ToolId tool, String key, Object value) {
        SettingsValues values = rig.ctx.settings(tool);
        @SuppressWarnings("unchecked")
        SettingDef<Object> def = (SettingDef<Object>) values.schema().def(key).orElseThrow();
        rig.ctx.updateSettings(tool, values.with(def, value));
    }

    /** A left click on block (x, y, z) with the active tool, through the editor's pointer input. */
    private void click(int x, int y, int z) {
        rig.platform.pickBlockAt(x, y, z);
        rig.controller.pointer(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 10, 10, 0);
        rig.controller.pointer(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 10, 10, 0);
    }

    /** The shown button labelled {@code text} in an open window. */
    private Button button(String windowId, String text) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(ui.windows().window(windowId).orElseThrow().content());
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node instanceof Button button && button.text().equals(text)) {
                return button;
            }
            nodes.addAll(node.children());
        }
        throw new AssertionError("no " + text + " button in " + windowId);
    }

    /** Presses Enter (or clicks a button, say) and ticks the session until the step is done. */
    private void untilDone(Runnable action) {
        action.run();
        for (int i = 0; i < 200 && !runner().stepDone(); i++) {
            rig.session.tick();
            rig.controller.frame(10, 10, now * 1_000_000L, 0f);
            frame();
        }
        assertTrue(runner().stepDone(), step() + " is done");
        settle();
    }

    @Test
    void theBuildLessonSetsGenerateToPathAndCountsOnlyTheNodesClickedDuringTheStep() {
        // An earlier game: a road's three nodes still there, Generate left on Line, Extrude left on Along a path.
        press(GLFW.GLFW_KEY_MINUS, 0);
        click(0, 60, 0);
        click(6, 60, 0);
        click(12, 60, 0);
        assertEquals(3, generate().path().size());
        setGenerate(GenerateSettings.Kind.LINE);
        set(ToolId.EXTRUDE, "direction", ExtrudeSettings.Direction.ALONG_PATH);
        press(GLFW.GLFW_KEY_1, 0);
        start("build");
        press(GLFW.GLFW_KEY_MINUS, 0);
        settle();
        assertEquals("kind", step());
        frame();
        assertEquals(settingRow("kind"), tutorial.highlight(), "the Generate row");
        setGenerate(GenerateSettings.Kind.PATH);
        settle();
        assertEquals("nodes", step());
        frame();
        assertFalse(runner().stepDone(), "the nodes left from the earlier road don't count");
        click(18, 60, 0);
        frame();
        assertFalse(runner().stepDone(), "one node clicked");
        click(24, 60, 0);
        settle();
        assertEquals("road", step());
        assertEquals(5, generate().path().size(), "added to the road there was");
        // As the road step says: Esc deselects the node, a second Esc clears them all, and the editor stays open.
        press(GLFW.GLFW_KEY_ESCAPE, 0);
        assertEquals(5, generate().path().size());
        assertEquals(-1, generate().path().selected(), "deselected");
        press(GLFW.GLFW_KEY_ESCAPE, 0);
        assertTrue(generate().path().isEmpty(), "cleared");
        assertTrue(rig.mode.isActive(), "still in the editor");
        skipTo("extrude_pick");
        press(GLFW.GLFW_KEY_EQUAL, 0);
        settle();
        assertEquals("direction", step(), "Extrude is on Along a path: the lesson asks for Straight");
        frame();
        assertEquals(settingRow("direction"), tutorial.highlight());
        set(ToolId.EXTRUDE, "direction", ExtrudeSettings.Direction.STRAIGHT);
        settle();
        assertEquals("extrude", step());
    }

    @Test
    void thePathPointsLessonFollowsTheLinesPointsClickedSelectedDraggedInsertedAndDeselected() {
        start("path_points");
        press(GLFW.GLFW_KEY_MINUS, 0);
        settle();
        assertEquals("kind", step());
        setGenerate(GenerateSettings.Kind.LINE);
        settle();
        assertEquals("points", step());
        PointEditor line = generate().line().editor();
        click(0, 60, 0);
        click(4, 60, 0);
        frame();
        assertFalse(runner().stepDone(), "two points of three");
        click(8, 60, 0);
        settle();
        assertEquals("select", step());
        assertEquals(2, line.selected(), "the end point clicked last is selected");
        click(4, 60, 0);
        settle();
        assertEquals("drag", step(), "a click on the middle point selects it");
        ToolContext c = rig.ctx.contextFor(ToolId.GENERATE);
        PathModel.PointRef middle = new PathModel.PointRef(PathModel.Role.OUTLINE, 1);
        line.move(c, middle, new BlockPos(4, 62, 0), false);
        frame();
        assertFalse(runner().stepDone(), "a drag still on");
        line.move(c, middle, new BlockPos(4, 63, 0), true);
        settle();
        assertEquals("insert", step());
        press(GLFW.GLFW_KEY_E, 0);
        assertTrue(line.placingInserted(), "E: a new point follows the cursor");
        frame();
        assertFalse(runner().stepDone(), "not inserted until a click puts it down");
        click(6, 60, 0);
        assertEquals(4, line.points().size(), "the click put it down, selected");
        settle();
        assertEquals("deselect", step());
        frame();
        assertFalse(runner().stepDone(), "the point put down is selected");
        press(GLFW.GLFW_KEY_ESCAPE, 0);
        settle();
        assertEquals("build", step());
        assertEquals(4, line.points().size(), "Esc only deselected");
    }

    @Test
    void theSmartSurfaceLessonFollowsTheOutlineAnInsidePointAndItsRise() {
        start("smart_surface");
        press(GLFW.GLFW_KEY_MINUS, 0);
        settle();
        setGenerate(GenerateSettings.Kind.SMART_SURFACE);
        settle();
        assertEquals("outline", step());
        PointEditor smart = generate().smartSurface().editor();
        click(0, 60, 0);
        click(10, 60, 0);
        click(10, 60, 10);
        frame();
        assertFalse(runner().stepDone(), "three outline points of four");
        click(0, 60, 10);
        settle();
        assertEquals("inside", step());
        assertEquals(4, smart.points().size());
        click(5, 60, 5);
        settle();
        assertEquals("raise", step());
        assertEquals(1, smart.points().insideCount(), "a click inside the outline is an inside point");
        smart.move(rig.ctx.contextFor(ToolId.GENERATE), new PathModel.PointRef(PathModel.Role.INSIDE, 0),
                new BlockPos(5, 66, 5), true);
        settle();
        assertEquals("settings", step());
        frame();
        assertTrue(tutorial.highlight().isPresent(), "the Top layer row");
        tutorial.card().skipButton().click();
        frame();
        assertEquals("build", step());
    }

    @Test
    void theRotateLessonFollowsRotateTheRingGhostsTurnTheMethodAndThePlacement() {
        start("rotate");
        rig.ctx.setSelection(new Box(new BlockPos(0, 60, 0), new BlockPos(2, 62, 2)));
        settle();
        assertEquals("open", step());
        frame();
        assertEquals(ui.windows().window(EditorWindows.SELECTION).map(Window::rect), tutorial.highlight());
        button(EditorWindows.SELECTION, "Rotate…").click();
        settle();
        assertEquals("turn", step());
        assertTrue(rig.place.rotating());
        frame();
        assertFalse(runner().stepDone(), "not turned yet");
        assertTrue(rig.place.setAngles(4_500, 0, 0));
        settle();
        assertEquals("method", step());
        frame();
        assertTrue(tutorial.highlight().isPresent(), "the Method row, or the Free rotation header");
        set(ToolId.PLACE, PlaceSettings.METHOD_KEY, Resampling.EXACT);
        settle();
        assertEquals("place", step());
        untilDone(() -> press(GLFW.GLFW_KEY_ENTER, 0));
        assertEquals("paste", step());
        tutorial.card().skipButton().click();
        frame();
        assertEquals(TutorialRunner.Phase.FINISHED, runner().phase());
        assertEquals(1, runner().made(), "the turn is one change");
    }

    @Test
    void theHeightmapLessonFollowsThePictureHeightTurnAndMode() {
        start("heightmap");
        press(GLFW.GLFW_KEY_SEMICOLON, 0);
        settle();
        assertEquals("picture", step());
        frame();
        assertEquals(settingRow("heightmap.image"), tutorial.highlight());
        set(ToolId.HEIGHTMAP, "heightmap.image", HeightmapRef.builtIn("crater"));
        settle();
        assertEquals("height", step());
        set(ToolId.HEIGHTMAP, "heightmap.height", 24);
        settle();
        assertEquals("turn", step());
        assertTrue(tutorial.card().bodyLabel().text().startsWith("R turns the picture"),
                tutorial.card().bodyLabel().text());
        press(GLFW.GLFW_KEY_R, 0);
        settle();
        assertEquals("stamp", step(), "R turned the picture a quarter turn");
        SettingsValues heightmap = rig.ctx.settings(ToolId.HEIGHTMAP);
        assertEquals(90, heightmap.get(heightmap.schema().def("heightmap.angle").orElseThrow()));
        tutorial.card().skipButton().click(); // strokes are counted by EditorProbe.StrokeCount (StrokeCountTest)
        frame();
        assertEquals("lower", step());
        set(ToolId.HEIGHTMAP, "heightmap.mode", BrushSettings.StampMode.LOWER);
        settle();
        assertEquals("lower_stamp", step());
        tutorial.card().skipButton().click();
        frame();
        assertEquals("raise", step());
        set(ToolId.HEIGHTMAP, "heightmap.mode", BrushSettings.StampMode.RAISE);
        settle();
        assertEquals(TutorialRunner.Phase.AFTER, runner().phase(), "no edits: straight to the lesson's end");
    }

    @Test
    void theBiomesLessonFollowsTheBiomeTheViewTheBoxAndFillBiome() {
        start("biomes");
        press(GLFW.GLFW_KEY_APOSTROPHE, 0);
        settle();
        assertEquals("biome", step());
        set(ToolId.BIOME, "biome", "minecraft:swamp");
        settle();
        assertEquals("view", step());
        assertEquals(Optional.of(ui.menuBar().titleBounds(CommandMenu.VIEW.ordinal())), tutorial.highlight(),
                "the View menu holds Biome view");
        ui.commands().run(EditorCommands.VIEW_BIOMES);
        settle();
        assertEquals("paint", step());
        tutorial.card().skipButton().click();
        frame();
        assertEquals("box", step());
        press(GLFW.GLFW_KEY_1, 0);
        rig.ctx.setSelection(new Box(new BlockPos(0, 60, 0), new BlockPos(7, 62, 7)));
        settle();
        assertEquals("fill", step());
        untilDone(() -> button(EditorWindows.SELECTION, "Fill biome").click());
        assertEquals("move_stack", step());
        assertTrue(rig.session.history().undoLabels().get(0).startsWith("Biome fill"),
                rig.session.history().undoLabels().toString());
        tutorial.card().skipButton().click();
        frame();
        assertEquals("view_off", step());
        ui.commands().run(EditorCommands.VIEW_BIOMES);
        settle();
        assertEquals(TutorialRunner.Phase.FINISHED, runner().phase());
    }

    private void leaveTheEditor() {
        rig.mode.exit(ExitReason.TOGGLED);
        ui.setEditing(false);
        ui.layout(640, 360);
    }

    private void comeBack() {
        rig.mode.enter();
        ui.setEditing(true);
        frame();
    }
}
