package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.fabric.client.session.HistoryMirror;
import dev.sculptory.protocol.v2.S2C;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Which of the player's history entries a lesson made, followed from the history the server sends
 * ({@link HistoryMirror}: the undo and redo labels, newest first, at most {@link #SHOWN} each, and nothing else).
 * Every history state received goes to {@link #observe}, which finds each way the change from the state before can be
 * read:
 * <ul>
 *   <li><b>undo</b> of k entries: the top k undo labels moved, in reverse, to the front of the redo list, and nothing
 *       else changed (list lengths included, where a list was shown whole);</li>
 *   <li><b>redo</b> of k entries: the front k redo labels moved back on top of the undo list;</li>
 *   <li><b>a new entry</b> (an edit): one new label on top, the undo list below it (its oldest entries may be
 *       dropped for memory), the redo list emptied;</li>
 *   <li><b>a change at the bottom only</b>: entries dropped for memory (lists only shorten), or older saved entries
 *       loaded after joining (lists only grow, under entries already there, or under nothing at all).</li>
 * </ul>
 * Each entry on either list carries a flag, "made by the lesson". New entries get it while {@linkplain #setRecording
 * recording} (a lesson runs, isn't paused, and isn't {@linkplain #hold holding} for work begun before it); undo and
 * redo carry it across. Where labels repeat, one change can be read several ways (a redo of the only redo entry, or a
 * new edit with the same label; a new edit on an undo list of one repeated label, or an older one loaded under it):
 * an entry then keeps its flag only if every reading gives it, so nothing is taken as the lesson's that might not be.
 * The counters ({@link #pushes}, {@link #undos}, {@link #redos}) follow the first reading (undo, then redo, then a new
 * entry), for the step conditions. Several new entries on top in one state are never an edit's, so they are not the
 * lesson's; a change no reading explains (another world's history) leaves nothing the lesson's. Client thread only.
 */
public final class LessonEdits {
    /** The most labels a history state shows each way. */
    public static final int SHOWN = S2C.HistoryState.MAX_LABELS;

    /** What a history change was. */
    public enum Change { NONE, PUSH, UNDO, REDO, TAIL, UNKNOWN }

    /** A history as the mirror shows it: undo labels newest first, redo labels nearest first. */
    public record Snapshot(List<String> undo, List<String> redo) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), List.of());

        public Snapshot {
            undo = List.copyOf(undo);
            redo = List.copyOf(redo);
        }

        public static Snapshot of(HistoryMirror mirror) {
            return new Snapshot(mirror.undoLabels(), mirror.redoLabels());
        }
    }

    /** One way to read a change: what it was, how many entries, and the flags it leaves. */
    private record Reading(Change change, int count, List<Boolean> undo, List<Boolean> redo, int ownedMoved) {}

    private Snapshot last = Snapshot.EMPTY;
    /** Parallel to {@code last.undo()}: whether each entry is the lesson's. */
    private final List<Boolean> undoOwned = new ArrayList<>();
    /** Parallel to {@code last.redo()}. */
    private final List<Boolean> redoOwned = new ArrayList<>();
    private boolean recording;
    private boolean held;
    /** Whether saved entries may still load under the history (after joining, once per connection). */
    private boolean loadsPossible = true;
    /** New entries are not the lesson's until the saved history has loaded (after a reconnect). */
    private boolean loadHeld;
    private long pushes;
    private long undos;
    private long redos;
    private long changes;
    private long topChanges;
    private Change lastChange = Change.NONE;
    private int lastCount;
    private int lastOwnedMoved;

    /**
     * Starts following a history afresh (the first state of a connection, or of a new session): every entry it shows
     * is taken as not the lesson's.
     */
    public void reset(Snapshot now) {
        last = Objects.requireNonNull(now);
        undoOwned.clear();
        undoOwned.addAll(Collections.nCopies(now.undo().size(), false));
        redoOwned.clear();
        redoOwned.addAll(Collections.nCopies(now.redo().size(), false));
    }

    /** A lesson begins: nothing on the history is its own yet. */
    public void beginLesson() {
        Collections.fill(undoOwned, false);
        Collections.fill(redoOwned, false);
    }

    /** Whether new entries are the lesson's (a lesson runs and isn't paused). Turning it off also ends a hold. */
    public void setRecording(boolean recording) {
        this.recording = recording;
        if (!recording) {
            held = false;
        }
    }

    public boolean isRecording() {
        return recording;
    }

    /**
     * New entries are not the lesson's until {@link #release}: edits begun before the lesson started or resumed are
     * still running, and their entries may land now.
     */
    public void hold() {
        held = true;
    }

    public void release() {
        held = false;
    }

    public boolean held() {
        return held;
    }

    /**
     * Whether older saved entries may still load under the history (the first moments of a connection). Only then is
     * a list growing at its bottom read as a load; once one load was seen, or the time for it is over, it is not.
     */
    public void setLoadsPossible(boolean possible) {
        loadsPossible = possible;
        if (!possible) {
            loadHeld = false;
        }
    }

    /**
     * After a reconnect: new entries are not the lesson's until saved entries have loaded, or loads are no longer
     * possible ({@link #setLoadsPossible}). A single saved entry loading under an empty history looks like one new edit.
     */
    public void holdForLoad() {
        loadHeld = true;
    }

    public boolean heldForLoad() {
        return loadHeld;
    }

    public boolean loadsPossible() {
        return loadsPossible;
    }

    /** Follows the history to {@code next}, the change read from the labels alone; returns its first reading. */
    public Change observe(Snapshot next) {
        return observe(next, HistoryMirror.Cause.UNKNOWN);
    }

    /**
     * Follows the history to {@code next}; returns what the change was (its first reading). {@code cause}, what the
     * session knows moved it ({@link HistoryMirror#lastCause}), keeps only the readings that agree with it: this
     * client's undo step is an undo of one entry, its redo step a redo of one, and anything else neither an undo nor a
     * redo. So a new edit labelled like the only redo entry is a new edit, and a redo of it is a redo, whatever the
     * labels; a move nothing explains then leaves nothing the lesson's, as always.
     */
    public Change observe(Snapshot next, HistoryMirror.Cause cause) {
        Objects.requireNonNull(next);
        Objects.requireNonNull(cause);
        List<String> u0 = last.undo();
        List<String> r0 = last.redo();
        List<String> u1 = next.undo();
        List<String> r1 = next.redo();
        if (u0.equals(u1) && r0.equals(r1)) {
            return Change.NONE;
        }
        List<Reading> readings = readings(u0, r0, u1, r1, cause);
        last = next;
        changes++;
        if (readings.isEmpty()) {
            // Unexplained: nothing is the lesson's any more, so nothing of it is undone by mistake.
            set(new ArrayList<>(), u1.size(), new ArrayList<>(), r1.size());
            topChanges++;
            lastChange = Change.UNKNOWN;
            lastCount = 0;
            lastOwnedMoved = 0;
            return Change.UNKNOWN;
        }
        Reading first = readings.get(0);
        List<Boolean> undo = new ArrayList<>(first.undo());
        List<Boolean> redo = new ArrayList<>(first.redo());
        for (Reading other : readings.subList(1, readings.size())) {
            for (int i = 0; i < undo.size(); i++) {
                undo.set(i, undo.get(i) && other.undo().get(i));
            }
            for (int i = 0; i < redo.size(); i++) {
                redo.set(i, redo.get(i) && other.redo().get(i));
            }
        }
        set(undo, u1.size(), redo, r1.size());
        switch (first.change()) {
            case PUSH -> pushes += first.count();
            case UNDO -> undos += first.count();
            case REDO -> redos += first.count();
            default -> {
            }
        }
        if (first.change() != Change.TAIL) {
            topChanges++;
        } else if (u1.size() > u0.size() || r1.size() > r0.size()) {
            // The saved entries arrived: there is one load per connection.
            loadsPossible = false;
            loadHeld = false;
        }
        lastChange = first.change();
        lastCount = first.count();
        lastOwnedMoved = first.ownedMoved();
        return first.change();
    }

    /** Whether a reading of the change fits what the session says moved the history. */
    private static boolean agrees(Reading reading, HistoryMirror.Cause cause) {
        return switch (cause) {
            case UNKNOWN -> true;
            case UNDO_STEP -> reading.change() == Change.UNDO && reading.count() == 1;
            case REDO_STEP -> reading.change() == Change.REDO && reading.count() == 1;
            case OTHER -> reading.change() != Change.UNDO && reading.change() != Change.REDO;
        };
    }

    /** Every reading of the change that agrees with {@code cause}, the most likely first. */
    private List<Reading> readings(List<String> u0, List<String> r0, List<String> u1, List<String> r1,
            HistoryMirror.Cause cause) {
        List<Reading> readings = new ArrayList<>();
        boolean undoCut = u0.size() >= SHOWN;
        boolean redoCut = r0.size() >= SHOWN;
        for (int k = 1; k <= u0.size(); k++) {
            List<String> moved = reversed(u0.subList(0, k));
            if (shows(r1, concat(moved, r0), redoCut) && shows(u1, u0.subList(k, u0.size()), undoCut)) {
                List<Boolean> movedOwned = reversed(undoOwned.subList(0, k));
                readings.add(new Reading(Change.UNDO, k, fit(undoOwned.subList(k, undoOwned.size()), u1.size()),
                        fit(concat(movedOwned, redoOwned), r1.size()), count(movedOwned)));
            }
        }
        for (int k = 1; k <= r0.size(); k++) {
            List<String> moved = reversed(r0.subList(0, k));
            if (shows(u1, concat(moved, u0), undoCut) && shows(r1, r0.subList(k, r0.size()), redoCut)) {
                List<Boolean> movedOwned = reversed(redoOwned.subList(0, k));
                readings.add(new Reading(Change.REDO, k, fit(concat(movedOwned, undoOwned), u1.size()),
                        fit(redoOwned.subList(k, redoOwned.size()), r1.size()), count(movedOwned)));
            }
        }
        boolean ours = recording && !held && !loadHeld;
        if (pushed(1, u0, u1, r1)) {
            readings.add(new Reading(Change.PUSH, 1, fit(concat(List.of(ours), undoOwned), u1.size()), List.of(),
                    ours ? 1 : 0));
        }
        if (bottomOnly(u0, u1, loadsPossible) && bottomOnly(r0, r1, loadsPossible) && sameWay(u0, u1, r0, r1)) {
            readings.add(new Reading(Change.TAIL, 0, fit(undoOwned, u1.size()), fit(redoOwned, r1.size()), 0));
        }
        readings.removeIf(reading -> !agrees(reading, cause));
        if (readings.isEmpty() && u0.isEmpty() && r0.isEmpty() && loadsPossible) {
            // Saved entries loaded under an empty history (right after joining): none of them the lesson's.
            readings.add(new Reading(Change.TAIL, 0, fit(List.of(), u1.size()), fit(List.of(), r1.size()), 0));
        } else if (readings.isEmpty()) {
            // Several new entries on top at once: an edit never makes them so, so they are not the lesson's.
            for (int k = 2; k <= u1.size(); k++) {
                if (pushed(k, u0, u1, r1)) {
                    readings.add(new Reading(Change.PUSH, k,
                            fit(concat(Collections.nCopies(k, false), undoOwned), u1.size()), List.of(), 0));
                    break;
                }
            }
        }
        readings.removeIf(reading -> !agrees(reading, cause));
        return readings;
    }

    /**
     * Whether {@code shown} is how the server would show a list whose known start is {@code expected}: the same labels
     * up to {@link #SHOWN}, and exactly as many where the list before was shown whole ({@code cut} false). Where it was
     * cut off at {@link #SHOWN}, entries beyond it exist and may show now.
     */
    private static boolean shows(List<String> shown, List<String> expected, boolean cut) {
        int known = Math.min(expected.size(), SHOWN);
        if (cut ? shown.size() < known : shown.size() != known) {
            return false;
        }
        return shown.subList(0, known).equals(expected.subList(0, known));
    }

    /**
     * Whether {@code k} new entries on top explain the new undo list: under them the old list's start (its oldest
     * entries may have been dropped for memory), at least one old label where there was one, and no redo entries.
     */
    private static boolean pushed(int k, List<String> u0, List<String> u1, List<String> r1) {
        if (!r1.isEmpty() || k > u1.size()) {
            return false;
        }
        List<String> rest = u1.subList(k, u1.size());
        if (rest.isEmpty() && !u0.isEmpty()) {
            return false;
        }
        return rest.size() <= u0.size() && rest.equals(u0.subList(0, rest.size()));
    }

    /**
     * One list changed at its bottom only: it shortened (entries dropped), or it grew under entries already there
     * while saved entries may still load.
     */
    private static boolean bottomOnly(List<String> before, List<String> after, boolean loadsPossible) {
        if (after.size() > before.size() && (before.isEmpty() || !loadsPossible)) {
            return false; // entries under nothing, or with no load to come: new ones, not a change at the bottom
        }
        int n = Math.min(before.size(), after.size());
        return before.subList(0, n).equals(after.subList(0, n));
    }

    /** Both lists grew (saved entries loaded) or both shortened (entries dropped), not one each way. */
    private static boolean sameWay(List<String> u0, List<String> u1, List<String> r0, List<String> r1) {
        int undo = Integer.compare(u1.size(), u0.size());
        int redo = Integer.compare(r1.size(), r0.size());
        return undo * redo >= 0;
    }

    private static <T> List<T> reversed(List<T> list) {
        List<T> copy = new ArrayList<>(list);
        Collections.reverse(copy);
        return copy;
    }

    private static <T> List<T> concat(List<? extends T> first, List<? extends T> second) {
        List<T> both = new ArrayList<>(first);
        both.addAll(second);
        return both;
    }

    private static int count(List<Boolean> flags) {
        int count = 0;
        for (boolean flag : flags) {
            if (flag) {
                count++;
            }
        }
        return count;
    }

    /** A copy cut or padded (with "not the lesson's") to {@code size}. */
    private static List<Boolean> fit(List<Boolean> flags, int size) {
        List<Boolean> fitted = new ArrayList<>(flags.subList(0, Math.min(size, flags.size())));
        while (fitted.size() < size) {
            fitted.add(false);
        }
        return fitted;
    }

    private void set(List<Boolean> undo, int undoSize, List<Boolean> redo, int redoSize) {
        List<Boolean> undoFlags = fit(undo, undoSize);
        List<Boolean> redoFlags = fit(redo, redoSize);
        undoOwned.clear();
        undoOwned.addAll(undoFlags);
        redoOwned.clear();
        redoOwned.addAll(redoFlags);
    }

    // ---- The lesson's entries ----

    /** Whether the newest undo entry is the lesson's. */
    public boolean topOwned() {
        return !undoOwned.isEmpty() && undoOwned.get(0);
    }

    /** The lesson's entries on the undo list, wherever they are. */
    public int owned() {
        return count(undoOwned);
    }

    /** The lesson's entries on top of the undo list, before the first that isn't. */
    public int ownedOnTop() {
        int count = 0;
        while (count < undoOwned.size() && undoOwned.get(count)) {
            count++;
        }
        return count;
    }

    // ---- Counters ----

    /** New entries seen (edits). */
    public long pushes() {
        return pushes;
    }

    /** Entries undone. */
    public long undos() {
        return undos;
    }

    /** Entries redone. */
    public long redos() {
        return redos;
    }

    /** History changes seen (every change but {@link Change#NONE}). */
    public long changes() {
        return changes;
    }

    /** History changes seen at the top of the history (every change but a change at the bottom only). */
    public long topChanges() {
        return topChanges;
    }

    public Change lastChange() {
        return lastChange;
    }

    /** Entries the last change moved or added (its first reading). */
    public int lastCount() {
        return lastCount;
    }

    /** Of those, the lesson's. */
    public int lastOwnedMoved() {
        return lastOwnedMoved;
    }
}
