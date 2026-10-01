package dev.sculptory.fabric.client.editor.tools.scatter;

/**
 * Debounces scatter previews: a change marks the preview due {@value #DEBOUNCE_MILLIS} ms later, and every further
 * change restarts that wait, so dragging a slider sends one preview when it settles. A refresh (R) is due at once.
 * Previews are also kept {@value #MIN_INTERVAL_MILLIS} ms apart, since the server refuses a player's second preview
 * within one tick ({@code RATE_LIMITED}). Nothing is retried on its own: only a change or a refresh makes a preview
 * due. Pure; times are {@code System.nanoTime()} values.
 */
public final class PreviewScheduler {
    public static final long DEBOUNCE_MILLIS = 250;
    public static final long DEBOUNCE_NANOS = DEBOUNCE_MILLIS * 1_000_000L;
    public static final long MIN_INTERVAL_MILLIS = 100;
    public static final long MIN_INTERVAL_NANOS = MIN_INTERVAL_MILLIS * 1_000_000L;

    private boolean dirty;
    private long changedAt;
    private boolean sentBefore;
    private long sentAt;

    /** Something the preview depends on changed at {@code now}. */
    public void changed(long now) {
        dirty = true;
        changedAt = now;
    }

    /** The player asked for a fresh preview at {@code now}: due at once (within the minimum interval). */
    public void refresh(long now) {
        dirty = true;
        changedAt = now - DEBOUNCE_NANOS;
    }

    /** Whether a preview should be sent now. */
    public boolean due(long now) {
        return dirty && now - changedAt >= DEBOUNCE_NANOS && (!sentBefore || now - sentAt >= MIN_INTERVAL_NANOS);
    }

    /** Whether a change is waiting (due or not). */
    public boolean waiting() {
        return dirty;
    }

    /** A preview was sent at {@code now}. */
    public void sent(long now) {
        dirty = false;
        sentBefore = true;
        sentAt = now;
    }

    /** The waiting change was dropped (nothing to preview, or the preview was cleared). */
    public void cancel() {
        dirty = false;
    }
}
