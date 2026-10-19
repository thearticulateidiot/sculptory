package dev.sculptory.core.state;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.transform.CellResampler;
import java.util.Objects;

/**
 * A {@link BlockBuffer} of source-local cells (a clipboard's) as a resampler reads it: a cell inside the box holds its
 * state, or nothing when absent; a block is any state that is not air. Without {@code air}, air cells count as absent:
 * what a paste that leaves air out weighs, the same as the client ghost (whose previews carry no air). Stateless and
 * thread-safe as long as the buffer is not changed.
 */
public final class BufferCells implements CellResampler.SourceCells {
    private final BlockBuffer cells;
    private final BlockPos size;
    private final StateSpace states;
    private final boolean air;

    public BufferCells(BlockBuffer cells, BlockPos size, StateSpace states) {
        this(cells, size, states, true);
    }

    /** @param air whether air cells are present (else read as absent) */
    public BufferCells(BlockBuffer cells, BlockPos size, StateSpace states, boolean air) {
        this.cells = Objects.requireNonNull(cells);
        this.size = Objects.requireNonNull(size);
        this.states = Objects.requireNonNull(states);
        this.air = air;
    }

    @Override
    public int state(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= size.x() || y >= size.y() || z >= size.z()) return -1;
        int state = cells.get(x, y, z);
        if (!air && state >= 0 && StateFlags.has(states.flags(state), StateFlags.AIR)) return -1;
        return state;
    }

    @Override
    public boolean solid(int state) {
        return !StateFlags.has(states.flags(state), StateFlags.AIR);
    }
}
