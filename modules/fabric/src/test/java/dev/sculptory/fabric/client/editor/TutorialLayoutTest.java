package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.tutorial.Lesson;
import dev.sculptory.fabric.client.editor.tutorial.LessonCard;
import dev.sculptory.fabric.client.editor.tutorial.Lessons;
import dev.sculptory.fabric.client.editor.tutorial.Tutorial;
import dev.sculptory.fabric.client.editor.tutorial.TutorialRunner;
import dev.sculptory.fabric.client.editor.tutorial.TutorialWindow;
import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.widget.AbstractButton;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * The lesson card, the Tutorial window and the quick start card's buttons on the reference screen (427x249 GUI pixels:
 * 854x498 units at UI 50%, 427x249 at 100%), in English measured like Minecraft's font: every step's card stays on
 * screen between the top bar and the hint line, its title and buttons whole and its text at most six lines; each
 * lesson in the Tutorial window is one slim row, its title whole, inside the window.
 */
class TutorialLayoutTest {
    private static final int[] OWNERS_SIZES = {50, 100};
    private static final int SCREEN_WIDTH = 427;
    private static final int SCREEN_HEIGHT = 249;
    private static final int MAX_LINES = 6;

    private EditorTestRig rig = new EditorTestRig();
    private final UiScale scale = new UiScale();
    private EditorUi ui;
    private Tutorial tutorial;
    private long now = 1_000;

    @BeforeEach
    void build() {
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, McFontText.INSTANCE,
                new ToastStack(() -> 0L), new EditorUi.Services(English.INSTANCE, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Caps Lock", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, scale));
        rig.controller.setUi(ui);
        ui.setEditing(true);
        rig.mode.enter();
        tutorial = ui.tutorial();
    }

    private void frame() {
        ui.layout(SCREEN_WIDTH, SCREEN_HEIGHT);
        ui.render(new RecordingGraphics(), 0, 0, now, false);
        // Lay out again with this frame's card, as the next frame does.
        ui.layout(SCREEN_WIDTH, SCREEN_HEIGHT);
        now += 16;
    }

    @Test
    void everyStepsCardFitsBetweenTheTopBarAndTheHintLine() {
        for (int percent : OWNERS_SIZES) {
            scale.set(percent);
            for (Lesson lesson : Lessons.all()) {
                tutorial.runner().start(lesson);
                for (int step = 0; step < lesson.size(); step++) {
                    frame();
                    String at = lesson.id() + "." + lesson.step(step).name() + " at " + percent + "%";
                    checkCard(at);
                    clearOfTitleBars(at);
                    tutorial.runner().skip();
                }
                // The lesson's end, with and without changes to undo.
                frame();
                checkCard(lesson.id() + " done at " + percent + "%");
            }
        }
    }

    @Test
    void theEndCardsWithUndoFitToo() {
        for (int percent : OWNERS_SIZES) {
            // A fresh editor each time: the same edit again after an undo would read as its redo.
            rig = new EditorTestRig();
            build();
            scale.set(percent);
            Lesson edit = Lessons.byId("edit").orElseThrow();
            tutorial.runner().start(edit);
            rig.ctx.setSelection(dev.sculptory.core.Box.of(new dev.sculptory.core.BlockPos(0, 60, 0)));
            long before = rig.session.history().version();
            rig.actions.fill();
            for (int i = 0; i < 200 && rig.session.history().version() == before; i++) {
                rig.session.tick();
            }
            frame();
            for (int step = 0; step < edit.size(); step++) {
                tutorial.runner().skip();
            }
            frame();
            assertEquals(TutorialRunner.Phase.FINISHED, tutorial.runner().phase());
            checkCard("Undo or keep at " + percent + "%");
            tutorial.card().undoButton().click();
            frame();
            frame();
            checkCard("undone at " + percent + "%");
        }
    }

    private void checkCard(String at) {
        LessonCard card = tutorial.card();
        Rect bounds = card.node().bounds();
        int width = ui.uiWidth();
        int margin = Theme.DARK.sheetMargin;
        assertTrue(bounds.x() >= margin && bounds.right() <= width - margin, "on screen: " + bounds + " " + at);
        Rect topBar = ui.topBarRow().bounds();
        Rect hint = ui.hintLine().bounds();
        assertTrue(bounds.y() >= topBar.bottom(), "under the top bar: " + bounds + " " + at);
        assertTrue(bounds.bottom() <= hint.y() - 2, "above the hint line " + hint + ": " + bounds + " " + at);
        whole(card.titleLabel(), "title " + at);
        whole(card.progressLabel(), "step count " + at);
        Label body = card.bodyLabel();
        int lines = TextLayout.wrap(McFontText.INSTANCE, body.text(), body.bounds().width()).size();
        assertTrue(lines <= MAX_LINES, lines + " lines: \"" + body.text() + "\" " + at);
        assertTrue(body.bounds().right() <= bounds.right(), "text inside the card " + at);
        for (Button button : card.shownButtons()) {
            inside(button, bounds, at);
        }
        assertTrue(!card.shownButtons().isEmpty(), "buttons " + at);
    }

    /**
     * The card leaves the open windows' title bars free (Selection and Tool Settings reach from the top bar to the hint
     * line at UI 100% on this screen: the card goes over their lower part instead of their tops).
     */
    private void clearOfTitleBars(String at) {
        Rect card = tutorial.card().node().bounds();
        for (Window window : ui.windows().windows()) {
            if (window.isOpen()) {
                assertFalse(card.intersects(window.titleBarRect(Theme.DARK)),
                        card + " covers " + window.id() + "'s title bar " + at);
            }
        }
    }

    private static void whole(Label label, String what) {
        assertTrue(McFontText.INSTANCE.width(label.text()) <= label.bounds().width(),
                what + ": \"" + label.text() + "\" in " + label.bounds().width());
    }

    private static void inside(Button button, Rect area, String at) {
        Rect rect = button.bounds();
        assertTrue(rect.x() >= area.x() && rect.right() <= area.right() && rect.y() >= area.y()
                && rect.bottom() <= area.bottom(), "\"" + button.text() + "\" " + rect + " inside " + area + " " + at);
        assertTrue(McFontText.INSTANCE.width(button.text()) + 2 * Theme.DARK.controlPaddingX <= rect.width(),
                "\"" + button.text() + "\" whole " + at);
    }

    @Test
    void theTutorialWindowShowsOneSlimRowPerLessonWithItsTitleWhole() {
        tutorial.runner().start(Lessons.byId("select").orElseThrow());
        tutorial.runner().exit();
        for (int percent : OWNERS_SIZES) {
            scale.set(percent);
            ui.windows().resetLayout();
            ui.tutorial().open();
            frame();
            Window window = ui.windows().window(EditorWindows.TUTORIAL).orElseThrow();
            assertTrue(window.isOpen());
            Rect content = window.contentRect(Theme.DARK);
            String at = " at " + percent + "%";
            assertTrue(window.rect().right() <= ui.uiWidth() && window.rect().x() >= 0, "on screen" + at);
            TutorialWindow lessons = tutorial.window();
            assertTrue(lessons.resumeShown(), "Resume, for the lesson paused" + at);
            assertFalse(lessons.introLabel().isShown(), "the banner instead of the intro" + at);
            inside(lessons.resumeButton().bounds(), content, "Resume" + at);
            assertEquals(lessons.resumeText(), lessons.resumeShownText(), "Resume's text whole" + at);
            assertEquals(TutorialWindow.ROW_HEIGHT, lessons.resumeButton().bounds().height(), "one slim banner" + at);
            int shown = 0;
            for (Lesson lesson : Lessons.all()) {
                AbstractButton row = lessons.lessonRow(lesson.id());
                assertTrue(row.children().isEmpty(), "no button of its own: the row starts the lesson");
                assertTrue(row.tooltip().startsWith(English.INSTANCE.translate(lesson.summaryKey()) + "\n"),
                        "the summary as the row's tooltip: " + row.tooltip());
                if (!row.isShown() || row.bounds().bottom() > content.bottom()) {
                    continue; // scrolled out of view
                }
                shown++;
                assertEquals(TutorialWindow.ROW_HEIGHT, row.bounds().height(), lesson.id() + at);
                inside(row.bounds(), content, lesson.id() + at);
                assertEquals(lessons.title(lesson.id()), lessons.shownTitle(lesson.id()), lesson.id() + " whole" + at);
            }
            assertTrue(shown >= 5, shown + " lessons in view" + at);
            ui.windows().close(EditorWindows.TUTORIAL);
        }
    }

    @Test
    void theLessonsTakeTabAndEnter() {
        ui.tutorial().open();
        frame();
        TutorialWindow lessons = tutorial.window();
        Lesson first = Lessons.all().get(0);
        Lesson second = Lessons.all().get(1);
        ui.windows().context().setFocus(lessons.lessonRow(first.id()));
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0));
        assertTrue(ui.windows().context().isFocused(lessons.lessonRow(second.id())), "Tab to the next lesson");
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0));
        assertEquals(second.id(), tutorial.runner().lesson().orElseThrow().id(), "Enter starts it");
        assertFalse(ui.windows().isOpen(EditorWindows.TUTORIAL));
    }

    private static void inside(Rect rect, Rect area, String what) {
        assertTrue(rect.x() >= area.x() && rect.right() <= area.right() && rect.y() >= area.y()
                && rect.bottom() <= area.bottom(), what + ": " + rect + " inside " + area);
    }

    @Test
    void theQuickStartCardHoldsBothButtons() {
        for (int percent : OWNERS_SIZES) {
            scale.set(percent);
            ui.showQuickStart();
            frame();
            Rect card = ui.quickStart().node().bounds();
            inside(ui.quickStart().startTutorialButton(), card, "at " + percent + "%");
            inside(ui.quickStart().gotItButton(), card, "at " + percent + "%");
            Rect start = ui.quickStart().startTutorialButton().bounds();
            Rect gotIt = ui.quickStart().gotItButton().bounds();
            assertTrue(start.right() < gotIt.x(), "Start tutorial at the left, Got it at the right");
            assertEquals(start.y(), gotIt.y(), "on one line");
        }
    }
}
