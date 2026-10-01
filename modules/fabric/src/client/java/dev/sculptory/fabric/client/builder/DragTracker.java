package dev.sculptory.fabric.client.builder;

import dev.sculptory.core.BlockPos;
import dev.sculptory.protocol.v2.C2S;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The cells of one break drag on the client: a click is a drag of one cell;
 * a bulldozer drag adds the cell under the crosshair each tick while the button is held. Each cell goes once per drag,
 * in batches of at most {@value C2S.BuilderBreak#MAX_CELLS} ({@link #drain}). Drag ids count up per client run. Pure
 * Java; client thread only.
 */
public final class DragTracker {
    private int nextDragId = 1;
    private int dragId;
    private boolean open;
    private boolean sameKind;
    private final Set<BlockPos> seen = new LinkedHashSet<>();
    private final List<BlockPos> pending = new ArrayList<>();

    public boolean open() {
        return open;
    }

    /** The open drag's id (or the last one's). */
    public int dragId() {
        return dragId;
    }

    /** Whether the open drag breaks only blocks of its first block's kind. */
    public boolean sameKind() {
        return sameKind;
    }

    /** Starts a drag (ending an open one); returns its id. */
    public int begin(boolean sameKind) {
        end();
        dragId = nextDragId++;
        open = true;
        this.sameKind = sameKind;
        return dragId;
    }

    /** Adds a cell to the open drag; false when it was already part of it (or no drag is open). */
    public boolean add(BlockPos cell) {
        if (!open || !seen.add(cell)) return false;
        pending.add(cell);
        return true;
    }

    /** The cells added since the last drain, in batches a {@code BuilderBreak} can carry; empties the queue. */
    public List<List<BlockPos>> drain() {
        List<List<BlockPos>> batches = new ArrayList<>();
        for (int from = 0; from < pending.size(); from += C2S.BuilderBreak.MAX_CELLS) {
            batches.add(List.copyOf(pending.subList(from, Math.min(pending.size(), from + C2S.BuilderBreak.MAX_CELLS))));
        }
        pending.clear();
        return batches;
    }

    /** How many cells the open drag holds. */
    public int size() {
        return seen.size();
    }

    /** Ends the open drag; returns whether one was open. Its id stays readable. */
    public boolean end() {
        if (!open) return false;
        open = false;
        seen.clear();
        pending.clear();
        return true;
    }
}
