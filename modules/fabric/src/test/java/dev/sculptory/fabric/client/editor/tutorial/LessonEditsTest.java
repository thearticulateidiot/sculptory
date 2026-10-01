package dev.sculptory.fabric.client.editor.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.tutorial.LessonEdits.Change;
import dev.sculptory.fabric.client.editor.tutorial.LessonEdits.Snapshot;
import dev.sculptory.fabric.client.session.HistoryMirror.Cause;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Following the history from its labels: which entries a lesson made, through new edits, undo, redo, entries dropped
 * or loaded at the bottom, and changes nothing explains (then nothing is the lesson's).
 */
class LessonEditsTest {
    private final LessonEdits edits = new LessonEdits();
    /** The history as a server would hold it: undo newest first, redo nearest first. */
    private final List<String> undo = new ArrayList<>();
    private final List<String> redo = new ArrayList<>();

    private Snapshot now() {
        return new Snapshot(undo, redo);
    }

    private Change push(String label) {
        undo.add(0, label);
        redo.clear();
        return edits.observe(now());
    }

    private Change undoOne() {
        redo.add(0, undo.remove(0));
        return edits.observe(now());
    }

    private Change redoOne() {
        undo.add(0, redo.remove(0));
        return edits.observe(now());
    }

    private void startLesson() {
        edits.reset(now());
        edits.beginLesson();
        edits.setRecording(true);
    }

    @Test
    void newEditsDuringTheLessonAreItsOwnAndThoseBeforeAreNot() {
        push("Fill");
        push("Raise stroke");
        startLesson();
        assertEquals(0, edits.owned());
        assertEquals(Change.PUSH, push("Paste"));
        assertEquals(Change.PUSH, push("Erase"));
        assertEquals(2, edits.owned());
        assertEquals(2, edits.ownedOnTop());
        assertTrue(edits.topOwned());
        assertEquals(4, edits.pushes(), "the counter counts every new edit, the lesson's or not");
    }

    /**
     * The builder-mode lesson: a placement outside the editor is a new entry the session marks as not its own undo or
     * redo step, and Ctrl+Z out there is the session's own undo step. Both are read as the lesson's while it records
     * (the editor being closed changes nothing here), and the undone entry leaves nothing on the undo list to undo at
     * the lesson's end.
     */
    @Test
    void aBuilderModePlacementAndItsUndoOutsideTheEditorReadAsAPushAndAnUndo() {
        push("Fill");
        startLesson();
        undo.add(0, "Place");
        assertEquals(Change.PUSH, edits.observe(now(), Cause.OTHER));
        assertEquals(1, edits.owned());
        assertEquals(2, edits.pushes());
        redo.add(0, undo.remove(0));
        assertEquals(Change.UNDO, edits.observe(now(), Cause.UNDO_STEP));
        assertEquals(1, edits.undos());
        assertEquals(0, edits.owned(), "undone: nothing left for Undo what this lesson made");
        assertEquals(0, edits.ownedOnTop());
        undo.add(0, redo.remove(0));
        assertEquals(Change.REDO, edits.observe(now(), Cause.REDO_STEP));
        assertEquals(1, edits.owned(), "Ctrl+Y out there brings the lesson's entry back as its own");
    }

    @Test
    void undoAndRedoCarryTheFlagAcross() {
        push("Before");
        startLesson();
        push("A");
        push("B");
        assertEquals(Change.UNDO, undoOne());
        assertEquals(1, edits.owned(), "B went to the redo list, still the lesson's");
        assertEquals(1, edits.lastOwnedMoved());
        assertEquals(Change.UNDO, undoOne());
        assertEquals(0, edits.owned());
        assertEquals(Change.UNDO, undoOne());
        assertEquals(0, edits.lastOwnedMoved(), "Before was not the lesson's");
        assertEquals(Change.REDO, redoOne());
        assertFalse(edits.topOwned(), "Before, redone, is still not the lesson's");
        assertEquals(Change.REDO, redoOne());
        assertEquals(Change.REDO, redoOne());
        assertEquals(2, edits.ownedOnTop(), "A and B are back on top, the lesson's");
        assertEquals(3, edits.undos());
        assertEquals(3, edits.redos());
    }

    @Test
    void undoingIntoWhatWasThereBeforeThenEditingLeavesOnlyTheNewEntriesAsTheLessons() {
        push("Old 1");
        push("Old 2");
        startLesson();
        push("Lesson 1");
        undoOne();
        undoOne();
        assertEquals(Change.PUSH, push("Lesson 2"), "a new edit clears the redo list");
        assertEquals(1, edits.owned());
        assertEquals(1, edits.ownedOnTop());
        assertEquals(List.of("Lesson 2", "Old 1"), undo);
    }

    @Test
    void anEditMadeWhileNotRecordingIsNotTheLessonsAndHidesTheOnesBelow() {
        startLesson();
        push("Lesson");
        edits.setRecording(false);
        push("Paused");
        assertEquals(1, edits.owned(), "the lesson's entry is still on the list");
        assertEquals(0, edits.ownedOnTop(), "but under one it didn't make");
        assertFalse(edits.topOwned());
    }

    @Test
    void severalStepsInOneStateAreReadAsOneChange() {
        startLesson();
        push("A");
        push("B");
        push("C");
        // A jump back two steps arrives as one state.
        redo.add(0, undo.remove(0));
        redo.add(0, undo.remove(0));
        assertEquals(Change.UNDO, edits.observe(now()));
        assertEquals(2, edits.lastCount());
        assertEquals(2, edits.undos());
        assertEquals(1, edits.owned());
        undo.add(0, redo.remove(0));
        undo.add(0, redo.remove(0));
        assertEquals(Change.REDO, edits.observe(now()));
        assertEquals(3, edits.ownedOnTop());
        undo.add(0, "E");
        undo.add(0, "D");
        assertEquals(Change.PUSH, edits.observe(now()), "two new entries at once");
        assertEquals(2, edits.pushes() - 3);
        assertEquals(0, edits.ownedOnTop(), "only saved entries loading arrive so: never the lesson's");
        assertEquals(3, edits.owned());
    }

    @Test
    void savedEntriesLoadedUnderAnEmptyHistoryAreNotTheLessons() {
        startLesson();
        undo.addAll(List.of("Saved 3", "Saved 2", "Saved 1"));
        assertEquals(Change.TAIL, edits.observe(now()), "loaded under nothing");
        assertEquals(0, edits.pushes(), "not edits");
        assertEquals(0, edits.owned());
        assertEquals(Change.PUSH, push("Lesson"));
        assertEquals(1, edits.ownedOnTop(), "the lesson's own edit after them is");
    }

    @Test
    void theSameLabelTwiceIsStillFollowedByPosition() {
        push("Fill");
        startLesson();
        push("Raise stroke");
        push("Raise stroke");
        assertEquals(2, edits.ownedOnTop());
        assertEquals(Change.UNDO, undoOne());
        assertEquals(1, edits.owned());
        assertEquals(Change.REDO, redoOne());
        assertEquals(2, edits.ownedOnTop());
        assertEquals(Change.PUSH, push("Raise stroke"));
        assertEquals(3, edits.ownedOnTop());
        assertEquals(3, edits.owned(), "Fill, from before, is not");
    }

    @Test
    void aNewEditThatEmptiesARedoListOfRepeatedLabelsIsNotReadAsARedoOfSeveral() {
        // All labels alike: a Shape click in the air, the same size each time.
        push("Shape");
        startLesson();
        push("Shape");
        push("Shape");
        undoOne();
        undoOne();
        assertEquals(List.of("Shape"), undo);
        assertEquals(List.of("Shape", "Shape"), redo);
        assertEquals(Change.PUSH, push("Shape"), "undo list [Shape, Shape]: one new entry, not a redo of two");
        assertEquals(1, edits.pushes() - 3);
        assertEquals(1, edits.ownedOnTop());
        assertEquals(1, edits.owned(), "the entry from before the lesson stays not the lesson's");
    }

    @Test
    void aRedoAmongRepeatedLabelsIsReadAsARedoNotAnUndo() {
        startLesson();
        push("Before");
        edits.beginLesson();
        push("Shape");
        push("Shape");
        push("Shape");
        undoOne();
        undoOne();
        long undosBefore = edits.undos();
        assertEquals(Change.REDO, redoOne(), "one more on the undo list and one less on the redo list");
        assertEquals(undosBefore, edits.undos());
        assertEquals(2, edits.ownedOnTop());
        assertEquals(1, edits.redos());
    }

    @Test
    void anUndoListOfOneRepeatedLabelMakesAnEditAmbiguousAndTheOlderEntryIsNotTakenAsTheLessons() {
        // [R] to [R, R] is a new R on top, or an older R loaded at the bottom: every reading must agree.
        startLesson();
        push("R");
        assertEquals(Change.PUSH, push("R"));
        assertTrue(edits.topOwned(), "the new one is the lesson's either way");
        assertEquals(1, edits.owned(), "the older one only in one reading: not taken as the lesson's");
    }

    @Test
    void afterAReconnectNewEntriesWaitForTheSavedHistoryToLoad() {
        startLesson();
        // A reconnect: the server's first state is empty, the saved history still loading.
        edits.holdForLoad();
        edits.reset(now());
        assertEquals(Change.PUSH, push("One saved entry, or one new edit: they look alike"));
        assertEquals(0, edits.owned());
        undo.add("Older saved entry");
        assertEquals(Change.TAIL, edits.observe(now()), "the load");
        assertFalse(edits.heldForLoad(), "loaded: new entries are the lesson's again");
        push("Lesson");
        assertEquals(1, edits.ownedOnTop());

        edits.holdForLoad();
        edits.setLoadsPossible(false);
        assertFalse(edits.heldForLoad(), "the time for a load is over");
    }

    @Test
    void entriesWhileHeldAreNotTheLessonsUntilReleased() {
        startLesson();
        edits.hold();
        assertEquals(Change.PUSH, push("A big fill started before the lesson"));
        assertEquals(0, edits.owned());
        edits.release();
        push("Lesson");
        assertEquals(1, edits.ownedOnTop());
        edits.hold();
        edits.setRecording(false);
        assertFalse(edits.held(), "stopping recording ends a hold");
    }

    @Test
    void undoAndRedoAmongRepeatedLabelsKeepTheLessonsFlags() {
        push("Before");
        startLesson();
        push("S");
        push("S");
        undoOne();
        assertEquals(Change.UNDO, undoOne(), "the undo list shortens and the redo list grows");
        assertEquals(List.of("Before"), undo);
        assertEquals(2, edits.redos() + edits.undos());
        assertEquals(Change.REDO, redoOne());
        assertEquals(Change.REDO, redoOne());
        assertEquals(2, edits.ownedOnTop(), "both came back as the lesson's");
    }

    @Test
    void entriesDroppedOrLoadedAtTheBottomKeepTheFlagsAbove() {
        push("Old");
        startLesson();
        push("A");
        undo.add("Older, loaded after joining");
        long pushes = edits.pushes();
        assertEquals(Change.TAIL, edits.observe(now()));
        assertEquals(pushes, edits.pushes(), "not an edit");
        assertEquals(1, edits.ownedOnTop());
        undo.remove(undo.size() - 1);
        undo.remove(undo.size() - 1);
        assertEquals(Change.TAIL, edits.observe(now()), "the oldest dropped for memory");
        assertEquals(List.of("A"), undo);
        assertEquals(1, edits.owned());
    }

    @Test
    void theListsHoldAtMost64LabelsAndAFullListStillFollows() {
        for (int i = 0; i < 64; i++) {
            push("Old " + i);
        }
        startLesson();
        undo.add(0, "Lesson");
        undo.remove(undo.size() - 1);
        assertEquals(Change.PUSH, edits.observe(now()), "one new on top, the 64th oldest label no longer sent");
        assertEquals(1, edits.ownedOnTop());
        assertEquals(64, undo.size());
        redo.add(0, undo.remove(0));
        undo.add("Old 0 again, sent now that there is room");
        assertEquals(Change.UNDO, edits.observe(now()));
        assertEquals(1, edits.lastOwnedMoved());
    }

    @Test
    void aChangeNothingExplainsLeavesNothingTheLessons() {
        push("Old");
        startLesson();
        push("A");
        push("B");
        // A history with nothing in common with the one before (another world's, say).
        undo.clear();
        undo.addAll(List.of("X", "Y"));
        assertEquals(Change.UNKNOWN, edits.observe(now()));
        assertEquals(0, edits.owned(), "nothing is undone by mistake");
        // Two steps back and a new edit arriving as one state isn't one of the changes either.
        startLesson();
        push("P");
        push("Q");
        undo.remove(0);
        undo.remove(0);
        undo.add(0, "R");
        assertEquals(Change.UNKNOWN, edits.observe(now()), "two undone and a new edit in one state");
        assertEquals(0, edits.owned());
    }

    @Test
    void anotherSessionStartsOverWithNothingTheLessons() {
        startLesson();
        push("A");
        assertEquals(1, edits.owned());
        edits.reset(new Snapshot(List.of("A"), List.of()));
        assertEquals(0, edits.owned(), "the same label on a new session's history is not known to be the lesson's");
        assertEquals(Change.NONE, edits.observe(new Snapshot(List.of("A"), List.of())), "nothing changed");
    }

    @Test
    void aRedoAndANewEditWithTheSameLabelLookAlikeAndTheEntryIsTheLessonsOnlyIfBothSaySo() {
        push("Before");
        undoOne();
        startLesson();
        // The player makes a new edit labelled like the entry on the redo list.
        undo.add(0, redo.remove(0));
        assertEquals(Change.REDO, edits.observe(now()), "counted as the redo");
        assertEquals(0, edits.owned(), "the redo reading says it's from before: undo at the end leaves it alone");

        push("Lesson");
        undoOne();
        edits.setRecording(false);
        undo.add(0, redo.remove(0));
        assertEquals(Change.REDO, edits.observe(now()));
        assertEquals(0, edits.owned(), "the new-edit reading says it's not the lesson's (not recording): not taken");
    }

    // ---- With what the session knows moved the history (HistoryMirror.Cause) ----

    private Change pushBy(String label) {
        undo.add(0, label);
        redo.clear();
        return edits.observe(now(), Cause.OTHER);
    }

    private Change undoStep() {
        redo.add(0, undo.remove(0));
        return edits.observe(now(), Cause.UNDO_STEP);
    }

    private Change redoStep() {
        undo.add(0, redo.remove(0));
        return edits.observe(now(), Cause.REDO_STEP);
    }

    @Test
    void aNewEditLabelledLikeTheOnlyRedoEntryIsTheLessonsWhenTheSessionSaysItIsntARedo() {
        pushBy("Fill");
        undoStep();
        startLesson();
        // Labels alone read this as a redo of the entry from before, or a new edit: the session knows it was an edit.
        assertEquals(Change.PUSH, pushBy("Fill"));
        assertTrue(edits.topOwned());
        assertEquals(1, edits.owned());
    }

    @Test
    void aRedoStepKeepsTheEntrysFlagWhateverTheLabelsAndWhetherTheLessonRecords() {
        startLesson();
        pushBy("Fill");
        assertEquals(Change.UNDO, undoStep());
        edits.setRecording(false); // paused: a new edit now wouldn't be the lesson's
        assertEquals(Change.REDO, redoStep());
        assertTrue(edits.topOwned(), "the lesson's entry, redone: still the lesson's");
        assertEquals(1, edits.lastOwnedMoved());

        // And one from before the lesson (a fresh follow: nothing is the lesson's), redone while it records, stays not
        // the lesson's, although a new edit would be.
        edits.setRecording(true);
        assertEquals(Change.UNDO, undoStep());
        edits.reset(now());
        edits.setRecording(true);
        assertEquals(Change.REDO, redoStep());
        assertFalse(edits.topOwned());
    }

    /**
     * The session says {@link Cause#UNKNOWN} for a move that left the history's bytes as they were but that no step
     * of its own explains (a redo made some other way: a command's). A redo of the only redo entry looks, by the
     * labels, like a new edit with that label too; the entry then keeps only the flag both readings give, so a redo
     * of the player's own edit is never taken for an edit of the lesson's (as it would be with {@link Cause#OTHER}).
     */
    @Test
    void aRedoMadeAnotherWayWithTheBytesUnchangedIsNeverTakenForANewEditOfTheLessons() {
        pushBy("Fill");
        undoStep();
        startLesson();
        undo.add(0, redo.remove(0));
        assertEquals(Change.REDO, edits.observe(now(), Cause.UNKNOWN), "the first reading");
        assertFalse(edits.topOwned(), "the player's own edit, redone: not the lesson's");
        assertEquals(0, edits.owned());

        // The same labels as a new edit certainly (the bytes changed, so the session says OTHER): the lesson's.
        undoStep();
        undo.add(0, "Fill");
        redo.clear();
        assertEquals(Change.PUSH, edits.observe(now(), Cause.OTHER));
        assertTrue(edits.topOwned());
    }

    @Test
    void aMoveNotMadeByThisClientsStepIsNeverTakenForAnUndoOrRedoAndLeavesNothingTheLessons() {
        startLesson();
        pushBy("A");
        pushBy("B");
        assertEquals(2, edits.owned());
        // B moves to the redo list, but not by an undo of this client (a command, say).
        redo.add(0, undo.remove(0));
        assertEquals(Change.UNKNOWN, edits.observe(now(), Cause.OTHER));
        assertEquals(0, edits.owned(), "nothing is undone at the end by mistake");
    }

    /**
     * The lesson after the time saved entries may load after joining ({@link LessonEdits#setLoadsPossible}): within it
     * a new edit on an undo list of one repeated label may still be an older entry loaded under it, and is read
     * conservatively (the known limit that remains).
     */
    private void startLessonAfterLoad() {
        startLesson();
        edits.setLoadsPossible(false);
    }

    @Test
    void anUndoStepAmongRepeatedLabelsMovesOneEntryAndKeepsTheOthersFlags() {
        pushBy("R");
        startLessonAfterLoad();
        pushBy("R");
        pushBy("R");
        assertEquals(2, edits.ownedOnTop());
        assertEquals(Change.UNDO, undoStep());
        assertEquals(1, edits.lastCount());
        assertEquals(1, edits.lastOwnedMoved());
        assertEquals(1, edits.ownedOnTop(), "the other lesson entry is still on top, the one from before under it");
        assertEquals(Change.REDO, redoStep());
        assertEquals(2, edits.ownedOnTop());
    }

    @Test
    void theBuildersOwnEditsWhileTheLessonIsPausedAreNotTheLessonsAmongSameLabels() {
        startLessonAfterLoad();
        pushBy("Fill"); // the lesson's
        edits.setRecording(false); // Exit: paused
        pushBy("Fill"); // the builder's own
        edits.setRecording(true); // Resume
        pushBy("Fill"); // the lesson's
        assertEquals(2, edits.owned());
        assertEquals(1, edits.ownedOnTop(), "the undo at the end stops at the builder's own edit");
        assertEquals(Change.UNDO, undoStep());
        assertFalse(edits.topOwned());
    }
}
