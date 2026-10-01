package dev.sculptory.core.mask;

import dev.sculptory.core.brush.BrushKernel;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.CellSink;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Applies the global mask to a brush: the wrapped kernel buffers each step's cells,
 * tests them against the reader as it was before the step, and forwards the accepted ones, so every read still
 * comes before the first write. The server and the client's prediction wrap the same way, so both write the same
 * cells.
 *
 * <p>A cell is tested with its state in the reader before the step, as a bulk edit tests it, and forwarded in the
 * order the kernel wrote it. A neighbour in another chunk that is not loaded matches no rule ({@link BoundRules}).
 */
public final class MaskedKernel implements BrushKernel {
    private final BrushKernel kernel;
    private final BoundMask mask;

    private MaskedKernel(BrushKernel kernel, BoundMask mask) {
        this.kernel = kernel;
        this.mask = mask;
    }

    /** {@code kernel} writing only the cells {@code mask} accepts; {@code kernel} itself when the mask is off. */
    public static BrushKernel wrap(BrushKernel kernel, BoundMask mask) {
        Objects.requireNonNull(kernel);
        Objects.requireNonNull(mask);
        if (mask.acceptsAll()) return kernel;
        return new MaskedKernel(kernel, mask);
    }

    @Override
    public void applyStep(BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w, CellSink out) {
        Buffer buffer = new Buffer();
        Judge judge = new Judge(mask, w);
        if (kernel instanceof Grouped grouped) {
            grouped.applyStep(s, dabs, st, w, buffer, judge);
        } else {
            kernel.applyStep(s, dabs, st, w, buffer);
        }
        if (buffer.size() == 0) return;
        // Every cell is tested before any is forwarded: the sink may write the world the tests read.
        boolean[] keep = new boolean[buffer.size()];
        for (int k = 0; k < keep.length; k++) {
            int x = buffer.cells.getInt(3 * k), y = buffer.cells.getInt(3 * k + 1), z = buffer.cells.getInt(3 * k + 2);
            keep[k] = judge.accepts(x, y, z);
        }
        for (int k = 0; k < keep.length; k++) {
            if (!keep[k]) continue;
            int x = buffer.cells.getInt(3 * k), y = buffer.cells.getInt(3 * k + 1), z = buffer.cells.getInt(3 * k + 2);
            BlockEntityData tile = buffer.tiles == null ? null : buffer.tiles.get(k);
            if (tile != null) {
                out.set(x, y, z, buffer.handles.getInt(k), tile);
            } else {
                out.set(x, y, z, buffer.handles.getInt(k));
            }
        }
    }

    /** The kernel wrapped (for tests). */
    BrushKernel kernel() {
        return kernel;
    }

    /**
     * A kernel some of whose writes belong together: Weather's Melt removes a block and writes it where it lands. Under
     * the global mask it judges each group's cells itself, with the same {@link Judge} this wrapper then applies to
     * every cell, and plans a group only when all its cells pass, so the mask keeps a whole group or none of it (a
     * move is never a removal without its landing, or a landing without its removal). The client's prediction and
     * the server wrap the same way, so both make the same choice.
     */
    public interface Grouped extends BrushKernel {
        void applyStep(BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w, CellSink out, Judge mask);
    }

    /**
     * The global mask's test of a cell against the reader as it was before the step: the cell's state there, its
     * neighbours there, and its measured slope for a slope rule.
     */
    public static final class Judge {
        private final BoundMask mask;
        private final WorldReader before;
        private final BoundRules rules;
        private final BoundRules.Scratch scratch;

        Judge(BoundMask mask, WorldReader before) {
            this.mask = mask;
            this.before = before;
            this.rules = mask instanceof BoundRules r ? r : null;
            this.scratch = rules != null && rules.usesSlope() ? new BoundRules.Scratch() : null;
        }

        /** Whether the mask lets cell (x, y, z) change; call it only before the step writes. */
        public boolean accepts(int x, int y, int z) {
            int state = before.get(x, y, z);
            return rules != null ? rules.test(x, y, z, state, before, BoundRules.MEASURE_SLOPE, scratch)
                    : mask.test(x, y, z, state, before);
        }
    }

    /** A step's cells in the order the kernel wrote them, with the tiles it kept. */
    private static final class Buffer implements CellSink {
        final IntArrayList cells = new IntArrayList();
        final IntArrayList handles = new IntArrayList();
        List<BlockEntityData> tiles;

        int size() {
            return handles.size();
        }

        @Override
        public void set(int x, int y, int z, int handle) {
            cells.add(x);
            cells.add(y);
            cells.add(z);
            handles.add(handle);
            if (tiles != null) tiles.add(null);
        }

        @Override
        public void set(int x, int y, int z, int handle, BlockEntityData tile) {
            if (tile != null && tiles == null) {
                tiles = new ArrayList<>(handles.size() + 1);
                for (int k = 0; k < handles.size(); k++) tiles.add(null);
            }
            cells.add(x);
            cells.add(y);
            cells.add(z);
            handles.add(handle);
            if (tiles != null) tiles.add(tile);
        }
    }
}
