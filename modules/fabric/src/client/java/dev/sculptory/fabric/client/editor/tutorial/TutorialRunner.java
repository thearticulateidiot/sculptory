package dev.sculptory.fabric.client.editor.tutorial;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The tutorial's state: which lesson runs, at which step, and the lesson's end. Pure logic, driven once per frame by
 * {@link #frame} with the editor's {@link TutorialProbe}:
 * <ul>
 *   <li><b>A step</b> ({@link Phase#STEP}) starts its condition as it begins; once the condition is met the step shows
 *       a check for {@link #DONE_SHOW_MS} (and the tick sound plays), then the next step begins. A step without a
 *       condition waits for {@link #next}. {@link #back} and {@link #skip} move a step either way at any time.</li>
 *   <li><b>Exit</b> ({@link #exit}) pauses the lesson: nothing is shown or checked until {@link #resume}, which goes
 *       on at the same step. Progress (lessons finished, the lesson in progress and its step) is saved at every step
 *       change, so {@link #resume} also continues a lesson from an earlier game.</li>
 *   <li><b>The end</b> ({@link Phase#FINISHED}) marks the lesson finished. If it made history entries
 *       ({@link LessonEdits}), the player chooses {@link #undoLesson} ({@link Phase#UNDOING}, a {@link LessonUndo})
 *       or {@link #keep}; then {@link Phase#AFTER} offers {@link #nextLesson} or {@link #close} (back to the
 *       lessons).</li>
 * </ul>
 * {@link #version} changes whenever anything shown changes, for the card to rebuild.
 */
public final class TutorialRunner {
    /** How long a done step shows its check before the next begins. */
    public static final long DONE_SHOW_MS = 700;
    /**
     * How long the session must have had no edits running from before a start or resume before new entries are the
     * lesson's: a finished job's history arrives a moment after the job's end.
     */
    public static final long HOLD_QUIET_MS = 500;

    public enum Phase {
        /** No lesson shown. */
        IDLE,
        /** A lesson's step. */
        STEP,
        /** The lesson is done; it made changes: undo them or keep them? */
        FINISHED,
        /** Undoing the lesson's changes. */
        UNDOING,
        /** Done: next lesson, or back to the lessons. */
        AFTER
    }

    private final List<Lesson> lessons;
    private final TutorialProbe probe;
    private final LessonEdits edits;
    private final LessonUndo.History history;
    private TutorialStore store = TutorialStore.inMemory();
    private TutorialProgress progress = TutorialProgress.NONE;
    private Runnable stepSound = () -> { };
    private BooleanSupplier earlierWork = () -> false;
    /** Since when nothing from before has run while held, or -1. */
    private long idleSince = -1;

    private Phase phase = Phase.IDLE;
    private Lesson lesson;
    private int step;
    private Condition.Check check;
    private long doneAt = -1;
    private boolean paused;
    private LessonUndo undo;
    /** The lesson's history entries when it ended. */
    private int made;
    private int version;

    public TutorialRunner(List<Lesson> lessons, TutorialProbe probe, LessonEdits edits, LessonUndo.History history) {
        this.lessons = List.copyOf(lessons);
        this.probe = Objects.requireNonNull(probe);
        this.edits = Objects.requireNonNull(edits);
        this.history = Objects.requireNonNull(history);
    }

    /** Where progress is kept; reads it now. */
    public void setStore(TutorialStore store) {
        this.store = Objects.requireNonNull(store);
        progress = store.load();
        changed();
    }

    /** Played when a step ticks itself off. */
    public void setStepSound(Runnable sound) {
        this.stepSound = Objects.requireNonNull(sound);
    }

    /**
     * Whether edits are still running on the server (jobs, undo or redo steps): when a lesson starts or resumes with
     * some, their history entries land during the lesson but aren't its own, so new entries count for the lesson only
     * once they are done ({@link LessonEdits#hold}).
     */
    public void setEarlierWork(BooleanSupplier earlierWork) {
        this.earlierWork = Objects.requireNonNull(earlierWork);
    }

    // ---- State ----

    public Phase phase() {
        return phase;
    }

    public Optional<Lesson> lesson() {
        return Optional.ofNullable(lesson);
    }

    /** The step shown (0-based). */
    public int stepIndex() {
        return step;
    }

    public Optional<Step> step() {
        return phase == Phase.STEP ? Optional.of(lesson.step(step)) : Optional.empty();
    }

    /** Whether the step shown was done (its check shows before the next step). */
    public boolean stepDone() {
        return phase == Phase.STEP && doneAt >= 0;
    }

    /** Paused with Exit: nothing shows until Resume. */
    public boolean paused() {
        return paused;
    }

    /** Whether the lesson card shows (a lesson runs, or its end shows, and it isn't paused). */
    public boolean shown() {
        return phase != Phase.IDLE && !paused;
    }

    public TutorialProgress progress() {
        return progress;
    }

    /** The lesson's history entries when it ended (what "Undo what this lesson made" undoes at most). */
    public int made() {
        return made;
    }

    public Optional<LessonUndo> undo() {
        return Optional.ofNullable(undo);
    }

    /** The lesson Resume continues: the one paused, or the one saved as in progress. */
    public Optional<TutorialProgress.Current> resumable() {
        if (phase == Phase.STEP && paused) {
            return Optional.of(new TutorialProgress.Current(lesson.id(), step));
        }
        if (phase != Phase.IDLE) {
            return Optional.empty();
        }
        return progress.current().filter(current -> find(current.lesson()).isPresent());
    }

    public List<Lesson> lessons() {
        return lessons;
    }

    public Optional<Lesson> find(String id) {
        return lessons.stream().filter(candidate -> candidate.id().equals(id)).findFirst();
    }

    public Optional<Lesson> nextAfter(Lesson current) {
        int index = lessons.indexOf(current);
        return index >= 0 && index + 1 < lessons.size() ? Optional.of(lessons.get(index + 1)) : Optional.empty();
    }

    public int version() {
        return version;
    }

    private void changed() {
        version++;
    }

    // ---- Starting and moving ----

    /** Starts (or restarts) {@code lesson} at its first step (not while the lesson's changes are being undone). */
    public void start(Lesson lesson) {
        if (phase != Phase.UNDOING) {
            startAt(lesson, 0);
        }
    }

    private void startAt(Lesson next, int at) {
        this.lesson = Objects.requireNonNull(next);
        this.undo = null;
        this.made = 0;
        this.paused = false;
        edits.beginLesson();
        record();
        begin(Math.max(0, Math.min(at, next.size() - 1)));
    }

    /** New history entries are the lesson's from now, once any edit still running from before has finished. */
    private void record() {
        edits.setRecording(true);
        if (earlierWork.getAsBoolean()) {
            edits.hold();
        }
    }

    /** Continues the paused lesson, or the one saved as in progress, at its step. */
    public void resume() {
        if (phase == Phase.STEP && paused) {
            paused = false;
            record();
            begin(step);
            return;
        }
        if (phase == Phase.UNDOING) {
            return;
        }
        resumable().ifPresent(current -> startAt(find(current.lesson()).orElseThrow(), current.step()));
    }

    /** Exit: pauses the lesson (Resume goes on at this step). At the lesson's end it closes the card instead. */
    public void exit() {
        if (phase == Phase.STEP) {
            paused = true;
            edits.setRecording(false);
            changed();
        } else {
            close();
        }
    }

    /** The step before (the first step stays). */
    public void back() {
        if (phase == Phase.STEP && step > 0) {
            begin(step - 1);
        }
    }

    /** Goes on without doing the step (after the last step: the lesson's end). */
    public void skip() {
        if (phase == Phase.STEP) {
            advance();
        }
    }

    /** Next, on a step that only explains (or to go on at once after a step is done). */
    public void next() {
        if (phase == Phase.STEP && (lesson.step(step).isRead() || doneAt >= 0)) {
            advance();
        }
    }

    private void advance() {
        if (step + 1 < lesson.size()) {
            begin(step + 1);
        } else {
            finish();
        }
    }

    private void begin(int index) {
        phase = Phase.STEP;
        step = index;
        doneAt = -1;
        Step shown = lesson.step(index);
        check = shown.isRead() ? null : shown.condition().start(probe);
        progress = progress.withCurrent(lesson.id(), index);
        store.save(progress);
        changed();
    }

    private void finish() {
        phase = Phase.FINISHED;
        check = null;
        edits.setRecording(false);
        progress = progress.withCompleted(lesson.id()).withoutCurrent();
        store.save(progress);
        made = edits.owned();
        if (made == 0) {
            phase = Phase.AFTER;
        }
        changed();
    }

    // ---- The lesson's end ----

    /** Undoes what the lesson made (newest first, stopping at anything else). */
    public void undoLesson(long nowMs) {
        if (phase == Phase.FINISHED) {
            phase = Phase.UNDOING;
            undo = new LessonUndo(edits, history, nowMs);
            undo.tick(nowMs);
            if (!undo.running()) {
                phase = Phase.AFTER;
            }
            changed();
        }
    }

    /** Keeps what the lesson made. */
    public void keep() {
        if (phase == Phase.FINISHED) {
            phase = Phase.AFTER;
            changed();
        }
    }

    /** Stops undoing after the undo in flight. */
    public void stopUndo() {
        if (phase == Phase.UNDOING && undo != null) {
            undo.stop();
            changed();
        }
    }

    /** Starts the lesson after this one. */
    public void nextLesson() {
        if (phase == Phase.AFTER) {
            lesson().flatMap(this::nextAfter).ifPresent(this::start);
        }
    }

    /** Hides the card (Back to lessons); a paused lesson stays resumable. */
    public void close() {
        if (phase == Phase.STEP) {
            exit();
            return;
        }
        phase = Phase.IDLE;
        lesson = null;
        check = null;
        undo = null;
        paused = false;
        edits.setRecording(false);
        changed();
    }

    // ---- Each frame ----

    /** Checks the step's condition (or follows the undo); the editor is open. */
    public void frame(long nowMs) {
        if (!edits.held() || earlierWork.getAsBoolean()) {
            idleSince = -1;
        } else if (idleSince < 0) {
            idleSince = nowMs;
        } else if (nowMs - idleSince >= HOLD_QUIET_MS) {
            edits.release();
            idleSince = -1;
        }
        switch (phase) {
            case STEP -> {
                if (paused) {
                    return;
                }
                if (doneAt >= 0) {
                    if (nowMs - doneAt >= DONE_SHOW_MS) {
                        advance();
                    }
                } else if (check != null && check.met(probe)) {
                    doneAt = nowMs;
                    stepSound.run();
                    changed();
                }
            }
            case UNDOING -> {
                int before = undo.undone();
                undo.tick(nowMs);
                if (!undo.running()) {
                    phase = Phase.AFTER;
                    changed();
                } else if (undo.undone() != before) {
                    changed();
                }
            }
            default -> {
            }
        }
    }
}
