package dev.sculptory.fabric.client.editor.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tutorial.TutorialRunner.Phase;
import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The lesson state machine: steps ticking themselves off, Back, Skip, Next, Exit and Resume, the end, progress. */
class TutorialRunnerTest {
    private final FakeProbe probe = new FakeProbe();
    private final LessonEdits edits = new LessonEdits();
    private final List<String> undoLabels = new ArrayList<>();
    private final List<String> redoLabels = new ArrayList<>();
    private int undoPresses;
    private boolean historyBusy;
    private final LessonUndo.History history = new LessonUndo.History() {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public boolean busy() {
            return historyBusy;
        }

        @Override
        public void undo() {
            undoPresses++;
            redoLabels.add(0, undoLabels.remove(0));
            edits.observe(new LessonEdits.Snapshot(undoLabels, redoLabels));
        }
    };
    private final Lesson first = Lesson.builder("first")
            .step("pick", Conditions.toolActive(ToolId.SELECT))
            .read("explain")
            .step("edit", Conditions.edited())
            .build();
    private final Lesson second = Lesson.builder("second")
            .step("fly", Conditions.flySpeedChanged())
            .step("edit", Conditions.edited())
            .build();
    @TempDir
    Path dir;

    private int sounds;
    private TutorialRunner runner;
    private long now = 1_000;

    @BeforeEach
    void build() {
        runner = runner(TutorialProgress.NONE);
    }

    /** A runner on the progress file, as a new game makes it; {@code stored} is saved there first unless NONE. */
    private TutorialRunner runner(TutorialProgress stored) {
        TutorialStore store = TutorialStore.of(new ConfigFile(dir.resolve(TutorialStore.FILE_NAME), new AtomicFileStore(),
                problem -> { }));
        if (stored != TutorialProgress.NONE) {
            store.load();
            store.save(stored);
        }
        TutorialRunner made = new TutorialRunner(List.of(first, second), probe, edits, history);
        made.setStore(store);
        made.setStepSound(() -> sounds++);
        return made;
    }

    private void frame() {
        runner.frame(now);
        now += 16;
    }

    /** Frames past the done step's check. */
    private void settle() {
        frame();
        now += TutorialRunner.DONE_SHOW_MS;
        frame();
    }

    private void edit(String label) {
        undoLabels.add(0, label);
        redoLabels.clear();
        edits.observe(new LessonEdits.Snapshot(undoLabels, redoLabels));
        probe.pushes = edits.pushes();
    }

    @Test
    void aStepTicksItselfOffShowsItsCheckThenTheNextBegins() {
        runner.start(first);
        assertEquals(Phase.STEP, runner.phase());
        assertEquals(0, runner.stepIndex());
        frame();
        assertFalse(runner.stepDone(), "Select isn't active");
        probe.activeTool = ToolId.SELECT;
        frame();
        assertTrue(runner.stepDone(), "the check shows");
        assertEquals(1, sounds, "with the tick");
        assertEquals(0, runner.stepIndex(), "still on the step for a moment");
        now += TutorialRunner.DONE_SHOW_MS;
        frame();
        assertEquals(1, runner.stepIndex());
        assertTrue(runner.step().orElseThrow().isRead());
        frame();
        assertEquals(1, runner.stepIndex(), "a step that only explains waits for Next");
        runner.next();
        assertEquals(2, runner.stepIndex());
        assertEquals(1, sounds, "Next makes no tick");
    }

    @Test
    void backSkipAndNext() {
        runner.start(first);
        runner.back();
        assertEquals(0, runner.stepIndex(), "no step before the first");
        runner.next();
        assertEquals(0, runner.stepIndex(), "Next only on a step that explains or is done");
        runner.skip();
        assertEquals(1, runner.stepIndex());
        runner.back();
        assertEquals(0, runner.stepIndex());
        probe.activeTool = ToolId.SELECT;
        frame();
        runner.next();
        assertEquals(1, runner.stepIndex(), "Next goes on at once once a step is done");
        runner.skip();
        runner.skip();
        assertEquals(Phase.AFTER, runner.phase(), "skipping the last step ends the lesson");
        assertTrue(runner.progress().isCompleted("first"), "finished, even with steps skipped");
    }

    @Test
    void backStartsTheStepAfreshSoItsConditionLooksAtWhatHappensFromThen() {
        runner.start(second);
        probe.flySpeed = 2;
        settle();
        assertEquals(1, runner.stepIndex());
        runner.back();
        frame();
        assertFalse(runner.stepDone(), "the speed changed before Back: the step waits for a new change");
        probe.flySpeed = 4;
        frame();
        assertTrue(runner.stepDone());
    }

    @Test
    void exitPausesAndResumeGoesOnAtTheSameStep() {
        runner.start(first);
        runner.skip();
        runner.skip();
        runner.exit();
        assertTrue(runner.paused());
        assertFalse(runner.shown(), "the card hides");
        assertFalse(edits.isRecording(), "edits while paused are not the lesson's");
        edit("While paused");
        frame();
        assertFalse(runner.stepDone(), "nothing is checked while paused");
        assertEquals(Optional.of(new TutorialProgress.Current("first", 2)), runner.resumable());

        runner.resume();
        assertTrue(runner.shown());
        assertEquals(2, runner.stepIndex());
        assertTrue(edits.isRecording());
        frame();
        assertFalse(runner.stepDone(), "the edit made while paused doesn't count");
        edit("Lesson edit");
        frame();
        assertTrue(runner.stepDone());
    }

    @Test
    void progressIsSavedAtEachStepAndAnotherGameResumesIt() {
        runner.start(first);
        runner.skip();
        assertEquals(Optional.of(new TutorialProgress.Current("first", 1)), runner.progress().current());

        TutorialRunner later = runner(runner.progress());
        assertEquals(Phase.IDLE, later.phase(), "a new game doesn't start the lesson by itself");
        assertEquals(Optional.of(new TutorialProgress.Current("first", 1)), later.resumable());
        later.resume();
        assertEquals(Phase.STEP, later.phase());
        assertEquals("first", later.lesson().orElseThrow().id());
        assertEquals(1, later.stepIndex());
    }

    @Test
    void theEndMarksTheLessonDoneAndOffersToUndoWhatItMade() {
        edit("Before");
        runner.start(first);
        runner.skip();
        runner.skip();
        edit("Fill");
        edit("Paste");
        settle();
        assertEquals(Phase.FINISHED, runner.phase());
        assertEquals(2, runner.made());
        assertEquals(Set.of("first"), runner.progress().completed());
        assertEquals(Optional.empty(), runner.progress().current());
        assertFalse(edits.isRecording(), "edits after the end are not the lesson's");

        runner.undoLesson(now);
        frame();
        frame();
        assertEquals(Phase.AFTER, runner.phase());
        assertEquals(LessonUndo.Outcome.DONE, runner.undo().orElseThrow().outcome());
        assertEquals(2, runner.undo().orElseThrow().undone());
        assertEquals(List.of("Before"), undoLabels, "only the lesson's own");
        assertEquals(2, undoPresses);

        runner.nextLesson();
        assertEquals("second", runner.lesson().orElseThrow().id());
        assertEquals(0, runner.stepIndex());
    }

    @Test
    void keepLeavesWhatItMadeAndALessonThatMadeNothingSkipsTheQuestion() {
        runner.start(first);
        runner.skip();
        runner.skip();
        edit("Fill");
        settle();
        runner.keep();
        assertEquals(Phase.AFTER, runner.phase());
        assertEquals(List.of("Fill"), undoLabels);
        assertEquals(0, undoPresses);

        runner.start(second);
        runner.skip();
        runner.skip();
        assertEquals(Phase.AFTER, runner.phase(), "nothing to undo: straight to next lesson / back to lessons");
        assertEquals(0, runner.made());
        runner.nextLesson();
        assertEquals(Phase.AFTER, runner.phase(), "no lesson after the last");
        runner.close();
        assertEquals(Phase.IDLE, runner.phase());
        assertEquals(Set.of("first", "second"), runner.progress().completed());
    }

    @Test
    void restartingAFinishedLessonStartsAtItsFirstStepAndStartingAnotherReplacesTheOneInProgress() {
        runner.start(first);
        runner.skip();
        runner.start(second);
        assertEquals("second", runner.lesson().orElseThrow().id());
        assertEquals(Optional.of(new TutorialProgress.Current("second", 0)), runner.progress().current());
        runner.start(first);
        assertEquals(0, runner.stepIndex());
    }

    @Test
    void editsStillRunningWhenALessonStartsAreNotItsOwnWhenTheyLand() {
        boolean[] working = {true};
        runner.setEarlierWork(() -> working[0]);
        runner.start(first);
        assertTrue(edits.held());
        edit("A big fill started before the lesson");
        frame();
        assertEquals(0, edits.owned());
        working[0] = false;
        frame();
        assertTrue(edits.held(), "a finished job's history arrives a moment after its end");
        now += TutorialRunner.HOLD_QUIET_MS;
        frame();
        assertFalse(edits.held(), "released once nothing from before has run for a moment");
        edit("The lesson's fill");
        assertEquals(1, edits.ownedOnTop());

        working[0] = true;
        runner.exit();
        runner.resume();
        assertTrue(edits.held(), "resuming holds too");
    }

    @Test
    void noLessonStartsWhileTheLessonsChangesAreBeingUndone() {
        runner.start(first);
        runner.skip();
        runner.skip();
        edit("Fill");
        settle();
        historyBusy = true;
        runner.undoLesson(now);
        frame();
        assertEquals(Phase.UNDOING, runner.phase());
        runner.start(second);
        runner.resume();
        assertEquals(Phase.UNDOING, runner.phase(), "the undo goes on");
        assertEquals("first", runner.lesson().orElseThrow().id());
        historyBusy = false;
        for (int i = 0; i < 40 && runner.phase() == Phase.UNDOING; i++) {
            frame();
        }
        assertEquals(Phase.AFTER, runner.phase());
        assertEquals(1, undoPresses);
    }

    @Test
    void aSavedLessonThisVersionDoesNotHaveIsNotOfferedToResume() {
        TutorialRunner later = runner(TutorialProgress.NONE.withCurrent("from_the_future", 3));
        assertEquals(Optional.empty(), later.resumable());
        later.resume();
        assertEquals(Phase.IDLE, later.phase());

        TutorialRunner pastTheEnd = runner(TutorialProgress.NONE.withCurrent("second", 99));
        pastTheEnd.resume();
        assertEquals(1, pastTheEnd.stepIndex(), "a step past the end resumes at the last step");
    }
}
