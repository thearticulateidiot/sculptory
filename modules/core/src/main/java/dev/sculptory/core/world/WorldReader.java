package dev.sculptory.core.world;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateSpace;

/** Read access to one world (dimension), in handles of {@link #states()}. */
public interface WorldReader {
    StateSpace states();

    int bottomY();

    int topYExclusive();

    boolean isLoaded(int cx, int cz);

    /** The state at (x, y, z); air outside the build height. Callers check {@link #isLoaded} first. */
    int get(int x, int y, int z);

    /**
     * The states of {@code count} cells of column (x, z) from y {@code y0} up, into {@code into} from {@code offset}:
     * exactly what {@link #get} gives for each, in one call a reader can make faster than cell by cell (the brush
     * kernel's Smooth reads its ball this way). Callers check {@link #isLoaded} first.
     */
    default void getColumn(int x, int z, int y0, int count, int[] into, int offset) {
        for (int i = 0; i < count; i++) into[offset + i] = get(x, y0 + i, z);
    }

    /** The block entity at (x, y, z), or {@code null}. */
    BlockEntityData tile(int x, int y, int z);

    /**
     * Resets {@code into} and fills every one of its 4096 cells from section (sx, sy, sz), with air
     * where the world has nothing, plus the section's tiles.
     */
    void copySection(int sx, int sy, int sz, SectionBuffer into);

    /**
     * A y at and above which column (x, z) holds only air (states with {@code StateFlags.AIR} and no block
     * entity), for example the exclusive top of its non-air blocks. Brush kernels start their surface scans no
     * higher than this, which skips the empty air above the terrain without changing the result. A larger value
     * is always safe; {@link #topYExclusive()} (the default) says nothing. A value that is too low is detected
     * (and the whole scan window used) when the cell there is not air, but not when it falls into an air gap
     * below other blocks, so it must never be below the column's highest non-air block. Only called for loaded
     * chunks.
     */
    default int heightHint(int x, int z) {
        return topYExclusive();
    }
}
