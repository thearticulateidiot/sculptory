package dev.sculptory.fabric.client.session;

import dev.sculptory.protocol.v2.S2C;
import java.util.List;
import java.util.Objects;

/** {@link HistoryMirror} fed by {@code HistoryState}. Render thread only. */
final class SessionHistoryMirror implements HistoryMirror {
    private final Listeners<Runnable> listeners = new Listeners<>();
    private S2C.HistoryState state = S2C.HistoryState.EMPTY;
    private long version;
    private Cause lastCause = Cause.UNKNOWN;

    /** A new state, not moved by a step of this client ({@link Cause#OTHER}). */
    void update(S2C.HistoryState next) {
        update(next, Cause.OTHER);
    }

    /** A new state, and what moved it (the session knows its own undo and redo steps). */
    void update(S2C.HistoryState next, Cause cause) {
        state = Objects.requireNonNull(next);
        lastCause = Objects.requireNonNull(cause);
        version++;
        Listeners.run(listeners);
    }

    @Override
    public Cause lastCause() {
        return lastCause;
    }

    @Override
    public long version() {
        return version;
    }

    /** The last state received. */
    S2C.HistoryState state() {
        return state;
    }

    void clear() {
        if (!state.equals(S2C.HistoryState.EMPTY)) update(S2C.HistoryState.EMPTY);
    }

    @Override
    public boolean canUndo() {
        return state.canUndo();
    }

    @Override
    public boolean canRedo() {
        return state.canRedo();
    }

    @Override
    public String undoLabel() {
        return state.undoLabel();
    }

    @Override
    public String redoLabel() {
        return state.redoLabel();
    }

    @Override
    public long bytes() {
        return state.bytes();
    }

    @Override
    public List<String> undoLabels() {
        return state.undoLabels();
    }

    @Override
    public List<String> redoLabels() {
        return state.redoLabels();
    }

    @Override
    public Subscription onChange(Runnable listener) {
        return listeners.add(listener);
    }
}
