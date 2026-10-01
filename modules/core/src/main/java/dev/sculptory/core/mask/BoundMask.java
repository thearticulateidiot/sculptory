package dev.sculptory.core.mask;

import dev.sculptory.core.world.WorldReader;

/**
 * An {@link EditMask} bound to one state space ({@link EditMask#bind}). Thread-safe once bound; a job tests cells
 * from its compute threads.
 */
public interface BoundMask {
    /** Accepts every cell, reads nothing. */
    BoundMask ALL = new BoundMask() {
        @Override
        public boolean test(int x, int y, int z, int before, WorldReader beforeView) {
            return true;
        }

        @Override
        public int reach() {
            return 0;
        }

        @Override
        public String toString() {
            return "BoundMask.ALL";
        }
    };

    /** The farthest, in blocks, a rule may read from the cell it tests (a slope scan's neighbouring columns). */
    int REACH_LIMIT = 16;

    /**
     * Whether cell (x, y, z), whose state before the edit is {@code before}, may be written. A rule that looks beyond
     * the cell reads {@code beforeView}, the world as it was before the edit (within {@link #reach()} of the cell).
     */
    boolean test(int x, int y, int z, int before, WorldReader beforeView);

    /** How far from the cell {@link #test} reads, 0 to {@value #REACH_LIMIT}: 0 when it reads the cell alone. */
    int reach();

    /** Whether this accepts every cell without reading anything ({@link #ALL}). */
    default boolean acceptsAll() {
        return this == ALL;
    }
}
