package dev.sculptory.core.generate;

import dev.sculptory.core.brush.SurfaceScan;
import dev.sculptory.core.world.WorldReader;
import java.util.Objects;

/**
 * The ground the Path generator follows: per column, the surface the brushes and scatter use ({@link SurfaceScan}:
 * the topmost terrain-solid block without a block entity under open cells), or {@link #NONE} (a column without a
 * surface: a structure on top, or nothing in the build height), or {@link #UNLOADED} (a chunk the reader does not
 * hold). Implementations are pure reads: the kernel may ask for a column more than once.
 */
@FunctionalInterface
public interface SurfaceReader {
    /** No surface in the column (as {@link SurfaceScan#NONE}). */
    int NONE = SurfaceScan.NONE;
    /** The column's chunk is not loaded. */
    int UNLOADED = Integer.MIN_VALUE + 1;

    /** The surface y of column (x, z), {@link #NONE} or {@link #UNLOADED}. */
    int ground(int x, int z);

    /** Whether a value is a height rather than {@link #NONE} or {@link #UNLOADED}. */
    static boolean known(int ground) {
        return ground != NONE && ground != UNLOADED;
    }

    /** Live scans of {@code world} over its whole build height (from the reader's height hint down). */
    static SurfaceReader of(WorldReader world) {
        Objects.requireNonNull(world);
        return (x, z) -> {
            if (!world.isLoaded(x >> 4, z >> 4)) return UNLOADED;
            int top = world.topYExclusive() - 1;
            int bottom = world.bottomY();
            if (top < bottom) return NONE;
            return SurfaceScan.scan(world, world.states(), x, z, top, bottom, null);
        };
    }
}
