package dev.sculptory.fabric.client.session;

/**
 * A jump through history in progress ({@link EditorSession#historyJump}): {@code done} of {@code total} undo (or redo)
 * steps have finished.
 */
public record HistoryJump(boolean undo, int done, int total) {
    public HistoryJump {
        if (total < 1 || done < 0 || done > total) throw new IllegalArgumentException(done + " of " + total);
    }
}
