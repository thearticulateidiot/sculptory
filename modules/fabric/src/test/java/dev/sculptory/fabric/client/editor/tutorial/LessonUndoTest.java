package dev.sculptory.fabric.client.editor.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.tutorial.LessonUndo.Outcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * "Undo what this lesson made" drives undo, so its failure cases: it undoes exactly the lesson's entries, newest first,
 * one at a time as each comes back; it stops at an entry the lesson didn't make, at an undo that doesn't go through,
 * at a history that changes some other way meanwhile, and when stopped; and it says how many it undid.
 */
class LessonUndoTest {
    private final LessonEdits edits = new LessonEdits();
    private final ServerHistory server = new ServerHistory();
    private long now = 10_000;

    /** A server history: undo presses answered at once, later, or refused. */
    private final class ServerHistory implements LessonUndo.History {
        final List<String> undo = new ArrayList<>();
        final List<String> redo = new ArrayList<>();
        boolean available = true;
        /** Frames an undo stays in flight before its answer. */
        int delay;
        /** Undo presses refused from this many on (-1: none). */
        int refuseFrom = -1;
        int presses;
        private int inFlight = -1;

        void push(String label) {
            undo.add(0, label);
            redo.clear();
            edits.observe(snapshot());
        }

        LessonEdits.Snapshot snapshot() {
            return new LessonEdits.Snapshot(undo, redo);
        }

        void applyUndo() {
            redo.add(0, undo.remove(0));
            edits.observe(snapshot());
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean busy() {
            return inFlight >= 0 || playerBusy >= 0;
        }

        @Override
        public void undo() {
            presses++;
            if (refuseFrom >= 0 && presses > refuseFrom) {
                return; // refused: a toast, and the history stays as it was
            }
            if (delay == 0) {
                applyUndo();
            } else {
                inFlight = delay;
            }
        }

        /** Another undo or redo is in flight for {@code frames} frames. */
        void busyFor(int frames) {
            inFlight = frames;
        }

        /** Frames until the history of a step that ended (not busy any more) arrives, or -1. */
        private int late = -1;

        /** The player's own Ctrl+Z, queued: busy for {@code busy} frames, its history {@code late} frames after. */
        void playerUndo(int busy, int late) {
            playerBusy = busy;
            playerLate = late;
        }

        private int playerBusy = -1;
        private int playerLate;

        /** A frame passes on the server. */
        void frame() {
            if (playerBusy > 0) {
                playerBusy--;
            } else if (playerBusy == 0) {
                playerBusy = -1;
                late = playerLate;
            }
            if (late > 0) {
                late--;
            } else if (late == 0) {
                late = -1;
                applyUndo();
            }
            if (inFlight > 0) {
                inFlight--;
            } else if (inFlight == 0) {
                inFlight = -1;
                applyUndo();
            }
        }
    }

    private void beginLesson() {
        edits.reset(server.snapshot());
        edits.beginLesson();
        edits.setRecording(true);
    }

    private LessonUndo start() {
        edits.setRecording(false);
        return new LessonUndo(edits, server, now);
    }

    /** Frames until it ends (at most 1000). */
    private void run(LessonUndo undo) {
        for (int i = 0; i < 1000 && undo.running(); i++) {
            server.frame();
            undo.tick(now);
            now += 16;
        }
    }

    @Test
    void undoesExactlyTheLessonsEntriesNewestFirst() {
        server.push("Before 1");
        server.push("Before 2");
        beginLesson();
        server.push("Fill");
        server.push("Paste");
        server.push("Erase");
        LessonUndo undo = start();
        assertEquals(3, undo.total());
        run(undo);
        assertEquals(Outcome.DONE, undo.outcome());
        assertEquals(3, undo.undone());
        assertEquals(List.of("Before 2", "Before 1"), server.undo, "what was there before the lesson stays");
        assertEquals(List.of("Fill", "Paste", "Erase"), server.redo, "undone newest first");
        assertEquals(3, server.presses, "one press per entry");
    }

    @Test
    void eachUndoWaitsForTheOneBeforeToComeBack() {
        beginLesson();
        server.push("A");
        server.push("B");
        server.delay = 3;
        LessonUndo undo = start();
        undo.tick(now);
        assertEquals(1, server.presses);
        for (int i = 0; i < 3; i++) {
            server.frame();
            undo.tick(now += 500);
            assertEquals(1, server.presses, "no second press while the first is in flight");
            assertTrue(undo.running(), "a long undo is waited for, however long it runs");
        }
        run(undo);
        assertEquals(Outcome.DONE, undo.outcome());
        assertEquals(2, undo.undone());
        assertEquals(2, server.presses);
    }

    @Test
    void stopsAtAnEntryTheLessonDidNotMake() {
        beginLesson();
        server.push("Lesson 1");
        edits.setRecording(false);
        server.push("Made while paused");
        edits.setRecording(true);
        server.push("Lesson 2");
        LessonUndo undo = start();
        assertEquals(2, undo.total(), "the lesson made two");
        run(undo);
        assertEquals(Outcome.FOREIGN, undo.outcome());
        assertEquals(1, undo.undone(), "Lesson 2 only: Made while paused is on top of Lesson 1");
        assertEquals(List.of("Made while paused", "Lesson 1"), server.undo);
    }

    @Test
    void anEntryNotTheLessonsOnTopStopsItBeforeAnyUndo() {
        beginLesson();
        server.push("Lesson");
        edits.setRecording(false);
        server.push("Someone's edit, after the lesson");
        LessonUndo undo = new LessonUndo(edits, server, now);
        run(undo);
        assertEquals(Outcome.FOREIGN, undo.outcome());
        assertEquals(0, undo.undone());
        assertEquals(0, server.presses, "nothing was undone");
    }

    @Test
    void anUndoThatDoesNotGoThroughStopsItAndSaysHowFarItGot() {
        beginLesson();
        server.push("A");
        server.push("B");
        server.push("C");
        server.refuseFrom = 1;
        LessonUndo undo = start();
        undo.tick(now);
        undo.tick(now);
        assertEquals(2, server.presses, "the first went through, the second was refused");
        undo.tick(now + LessonUndo.SETTLE_MS - 1);
        assertTrue(undo.running(), "the history may still arrive");
        undo.tick(now + LessonUndo.SETTLE_MS);
        assertEquals(Outcome.FAILED, undo.outcome());
        assertEquals(1, undo.undone());
        assertEquals(3, undo.total());
        undo.tick(now + 60_000);
        assertEquals(2, server.presses, "no more presses after a failure");
        assertEquals(List.of("B", "A"), server.undo);
    }

    @Test
    void theHistoryChangingSomeOtherWayMeanwhileStopsIt() {
        beginLesson();
        server.push("A");
        server.push("B");
        server.delay = 2;
        LessonUndo undo = start();
        undo.tick(now);
        // Before the answer, a new edit reaches the history (another tool's commit, say).
        server.push("New edit");
        run(undo);
        assertEquals(Outcome.INTERRUPTED, undo.outcome());
        assertEquals(0, undo.undone());
    }

    @Test
    void stopEndsItAfterTheUndoInFlight() {
        beginLesson();
        server.push("A");
        server.push("B");
        server.push("C");
        server.delay = 2;
        LessonUndo undo = start();
        undo.tick(now);
        undo.stop();
        assertTrue(undo.running(), "the undo in flight is still followed");
        run(undo);
        assertEquals(Outcome.STOPPED, undo.outcome());
        assertEquals(1, undo.undone(), "counted when it came back");
        assertEquals(1, server.presses);
        assertEquals(List.of("B", "A"), server.undo);
    }

    @Test
    void afterAStepItDidNotSendTheNextPressWaitsForThatStepsHistory() {
        server.push("Before the lesson");
        beginLesson();
        server.push("Lesson");
        // The player's own Ctrl+Z undoes the lesson's entry; the session is idle again a little before its history
        // arrives. The entry below is not the lesson's: pressing on the old history would undo it.
        server.playerUndo(3, 5);
        LessonUndo undo = start();
        run(undo);
        assertEquals(0, server.presses, "never pressed: the old top entry was not judged");
        assertEquals(Outcome.FOREIGN, undo.outcome());
        assertEquals(List.of("Before the lesson"), server.undo, "left alone");
    }

    @Test
    void withoutASessionItFailsWithoutPressingAnything() {
        beginLesson();
        server.push("A");
        server.available = false;
        LessonUndo undo = start();
        undo.tick(now);
        assertEquals(Outcome.FAILED, undo.outcome());
        assertEquals(0, server.presses);
    }

    @Test
    void aLessonThatMadeNothingIsDoneAtOnce() {
        server.push("Before");
        beginLesson();
        LessonUndo undo = start();
        assertFalse(undo.running());
        assertEquals(Outcome.DONE, undo.outcome());
        assertEquals(0, undo.total());
        assertEquals(0, server.presses);
    }

    @Test
    void anotherUndoOrRedoInFlightIsWaitedForBeforeTheFirstPress() {
        beginLesson();
        server.push("A");
        server.busyFor(2);
        LessonUndo undo = start();
        undo.tick(now);
        assertEquals(0, server.presses, "the history shown is not final while steps run");
        assertTrue(undo.running());
    }
}
