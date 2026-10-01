package dev.sculptory.fabric.client.editor.tutorial;

import java.util.Objects;

/**
 * "Undo what this lesson made": undoes the lesson's entries ({@link LessonEdits}) newest first, one at a time through
 * the editor's own undo (Ctrl+Z's path), each only once the one before has come back from the server. It stops at the
 * first entry on top that isn't the lesson's, when an undo doesn't go through (refused, nothing happened), when the
 * history changes some other way meanwhile, or when the player stops it; {@link #undone} says how many it undid.
 * {@link #tick} is called once per frame.
 */
public final class LessonUndo {
    /**
     * How long to wait, once the session no longer has an undo queued or running, for the history to show it. The
     * server sends the new history right after the step's result, so a longer silence means the undo failed.
     */
    public static final long SETTLE_MS = 2_000;
    /**
     * How long the session must have been idle, after an undo or redo that wasn't this one's, before the next press:
     * that step's history arrives a moment after the session stops being busy, and the top entry is judged from it.
     */
    public static final long QUIET_MS = 250;

    /** How it ended, or {@link #RUNNING}. */
    public enum Outcome {
        RUNNING,
        /** Every entry the lesson made was undone. */
        DONE,
        /** It reached an entry the lesson didn't make on top of the history. */
        FOREIGN,
        /** An undo didn't go through (the session said why), or there is no session. */
        FAILED,
        /** The history changed some other way while it ran (another undo, a new edit). */
        INTERRUPTED,
        /** The player stopped it. */
        STOPPED
    }

    /** The editor's history, as the undo needs it. */
    public interface History {
        /** Whether there is a session to undo through. */
        boolean available();

        /** Undo or redo steps are queued or running (the history the client shows lags behind). */
        boolean busy();

        /** Undoes one entry, as Ctrl+Z does. */
        void undo();
    }

    private final LessonEdits edits;
    private final History history;
    private final int total;
    private Outcome outcome = Outcome.RUNNING;
    private int undone;
    private boolean sent;
    private long changesAtSend;
    private long quietSince;
    /** When the session was last seen busy (undo or redo steps queued or running). */
    private long lastBusyAt = Long.MIN_VALUE / 2;
    /** The history just showed this undo's own step, with nothing else queued: it is up to date. */
    private boolean fresh;
    private boolean stopRequested;

    /** Starts undoing the lesson's entries now on the history. */
    public LessonUndo(LessonEdits edits, History history, long nowMs) {
        this.edits = Objects.requireNonNull(edits);
        this.history = Objects.requireNonNull(history);
        this.total = edits.owned();
        this.quietSince = nowMs;
        if (total == 0) {
            outcome = Outcome.DONE;
        }
    }

    /** The lesson's entries when it started: the most it undoes. */
    public int total() {
        return total;
    }

    public int undone() {
        return undone;
    }

    public Outcome outcome() {
        return outcome;
    }

    public boolean running() {
        return outcome == Outcome.RUNNING;
    }

    /** Stops after the undo in flight (if any) comes back. */
    public void stop() {
        stopRequested = true;
        if (running() && !sent) {
            outcome = Outcome.STOPPED;
        }
    }

    /** Sends the next undo, or follows the one in flight. */
    public void tick(long nowMs) {
        if (!running()) {
            return;
        }
        if (!history.available()) {
            outcome = Outcome.FAILED;
            return;
        }
        boolean busy = history.busy();
        if (busy) {
            lastBusyAt = nowMs;
        }
        if (sent) {
            follow(nowMs, busy);
            if (sent || !running()) {
                return;
            }
        }
        if (undone >= total) {
            outcome = Outcome.DONE;
            return;
        }
        if (stopRequested) {
            outcome = Outcome.STOPPED;
            return;
        }
        if (busy) {
            // Someone else's undo or redo is in flight: the history shown isn't final yet.
            return;
        }
        if (!fresh && nowMs - lastBusyAt < QUIET_MS) {
            // A step that wasn't this one's just ended: wait for its history before judging the top entry.
            return;
        }
        fresh = false;
        if (!edits.topOwned()) {
            outcome = Outcome.FOREIGN;
            return;
        }
        changesAtSend = edits.topChanges();
        quietSince = nowMs;
        sent = true;
        history.undo();
        // A session that answers at once (the mock) has already moved the history.
        follow(nowMs, history.busy());
    }

    private void follow(long nowMs, boolean busy) {
        // Entries dropped or loaded at the bottom meanwhile don't matter to the step; only a change at the top does.
        long seen = edits.topChanges() - changesAtSend;
        if (seen == 0) {
            if (busy) {
                quietSince = nowMs;
            } else if (nowMs - quietSince >= SETTLE_MS) {
                sent = false;
                outcome = Outcome.FAILED;
            }
            return;
        }
        sent = false;
        fresh = !busy;
        boolean mine = seen == 1 && edits.lastChange() == LessonEdits.Change.UNDO && edits.lastCount() == 1
                && edits.lastOwnedMoved() == 1;
        if (!mine) {
            outcome = Outcome.INTERRUPTED;
            return;
        }
        undone++;
        if (undone >= total) {
            outcome = Outcome.DONE;
        } else if (stopRequested) {
            outcome = Outcome.STOPPED;
        }
    }
}
