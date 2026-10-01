package dev.sculptory.fabric.client.session;

/**
 * Undo anyway (or Redo anyway) on offer ({@link EditorSession#historyOffer}): the last {@code steps} undo (or redo)
 * steps in a row kept {@code skipped} blocks that had changed since, and the server can overwrite them too.
 *
 * @param id changes whenever the run behind the offer changes, so a toast can show each offer once
 * @param redo the steps were redos (Redo anyway)
 * @param steps how many steps the overwrite re-applies
 * @param skipped the blocks those steps kept
 * @param running the overwrite was sent and has not finished
 */
public record HistoryOffer(long id, boolean redo, int steps, long skipped, boolean running) {
    public HistoryOffer {
        if (steps < 1 || skipped < 1) throw new IllegalArgumentException("Nothing to offer");
    }
}
