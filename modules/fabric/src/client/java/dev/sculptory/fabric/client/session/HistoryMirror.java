package dev.sculptory.fabric.client.session;

import java.util.List;

/** The client's view of the server-held history, from {@code HistoryState}. Labels are "" when empty. */
public interface HistoryMirror {
    /**
     * What moved the history at its last change, as far as this client can tell. Only the labels come from the server;
     * where they repeat, one change can read as an undo, a redo or a new edit alike, and this says which it was when
     * the client knows (tutorial: "Undo what this lesson made").
     */
    enum Cause {
        /**
         * This mirror doesn't tell: the change is read from the labels alone. Also a move that left the history's
         * byte total as it was but that no step of this client explains (an undo or redo made some other way).
         */
        UNKNOWN,
        /** This client's own undo step moved one entry from the undo list to the redo list (the bytes unchanged). */
        UNDO_STEP,
        /** This client's own redo step moved one entry back (the bytes unchanged). */
        REDO_STEP,
        /**
         * Certainly not an undo or redo step alone: the history's byte total changed (a new edit's entry, entries
         * dropped for memory or loaded after joining, the history cleared).
         */
        OTHER
    }

    /** What moved the history at the last change ({@link Cause#UNKNOWN} when this mirror doesn't tell). */
    default Cause lastCause() {
        return Cause.UNKNOWN;
    }

    boolean canUndo();

    boolean canRedo();

    String undoLabel();

    String redoLabel();

    /** Bytes the server holds for this player's history. */
    long bytes();

    Subscription onChange(Runnable listener);

    /**
     * Changes whenever the server history changes (every {@code HistoryState} received: an edit pushed by any tool, an
     * undo, a redo, an eviction), so a tool with undo steps of its own can tell whether the history moved since.
     */
    default long version() {
        return 0;
    }

    /** Undoable entries, newest first (at most {@code HistoryState.MAX_LABELS}). */
    default List<String> undoLabels() {
        return List.of();
    }

    /** Redoable entries, nearest first (at most {@code HistoryState.MAX_LABELS}). */
    default List<String> redoLabels() {
        return List.of();
    }

    /** The {@link EditorSession#jumpTo} target that undoes every entry up to {@code undoLabels().get(index)}. */
    static long undoTarget(int index) {
        return -(index + 1L);
    }

    /** The {@link EditorSession#jumpTo} target that redoes every entry up to {@code redoLabels().get(index)}. */
    static long redoTarget(int index) {
        return index + 1L;
    }
}
