package dev.sculptory.server.engine.impl;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * One player's history as the client should see it (maps onto {@code S2C.HistoryState}).
 *
 * @param canUndo there is an entry to undo (the stack's view; see {@code busy})
 * @param canRedo there is an entry to redo
 * @param undoLabels undo labels, next undo first: at most {@link #MAX_LABELS}, each at most {@link #MAX_LABEL_BYTES}
 *     UTF-8 bytes
 * @param redoLabels redo labels, next redo first, capped the same way
 * @param bytes estimated memory held by the player's entries
 * @param busy an undo or redo of this player is running; another is refused until it finishes
 */
public record HistorySnapshot(boolean canUndo, boolean canRedo, List<String> undoLabels, List<String> redoLabels,
                              long bytes, boolean busy) {
    /** {@code S2C.HistoryState.MAX_LABELS}. */
    public static final int MAX_LABELS = 64;
    /** The protocol's cap for a history label ({@code Codec.MAX_NAME_BYTES}). */
    public static final int MAX_LABEL_BYTES = 256;

    public static final HistorySnapshot EMPTY = new HistorySnapshot(false, false, List.of(), List.of(), 0L, false);

    public HistorySnapshot {
        undoLabels = List.copyOf(undoLabels);
        redoLabels = List.copyOf(redoLabels);
        if (undoLabels.size() > MAX_LABELS || redoLabels.size() > MAX_LABELS) {
            throw new IllegalArgumentException("At most " + MAX_LABELS + " labels each way");
        }
    }

    /** The next undo's label, or {@code ""}. */
    public String undoLabel() {
        return undoLabels.isEmpty() ? "" : undoLabels.get(0);
    }

    /** The next redo's label, or {@code ""}. */
    public String redoLabel() {
        return redoLabels.isEmpty() ? "" : redoLabels.get(0);
    }

    /**
     * {@code label} cut to at most {@code maxBytes} UTF-8 bytes, never inside a character (a surrogate pair is
     * kept or dropped whole). Unpaired surrogates, which UTF-8 cannot encode, count as the one-byte '?' they
     * encode to.
     */
    public static String truncateUtf8(String label, int maxBytes) {
        Objects.requireNonNull(label);
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes");
        if (label.length() * 3L <= maxBytes) return label;
        int bytes = 0;
        int i = 0;
        while (i < label.length()) {
            int cp = label.codePointAt(i);
            int chars = Character.charCount(cp);
            int size = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? (Character.isSurrogate((char) cp) ? 1 : 3) : 4;
            if (bytes + size > maxBytes) break;
            bytes += size;
            i += chars;
        }
        String cut = label.substring(0, i);
        // The loop's count matches the encoder's; this guards the contract anyway.
        while (cut.getBytes(StandardCharsets.UTF_8).length > maxBytes) cut = cut.substring(0, cut.length() - 1);
        return cut;
    }
}
