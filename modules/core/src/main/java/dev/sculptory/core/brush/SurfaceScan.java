package dev.sculptory.core.brush;

import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;

/**
 * The terrain surface definition shared by the terrain brushes and scatter (M3), so both agree on what the
 * ground is.
 *
 * <ul>
 *   <li><b>Ground</b>: {@link StateFlags#TERRAIN_SOLID} cells without a block entity.</li>
 *   <li><b>Open</b>: air, replaceable, vegetation and fluid cells that are neither terrain-solid nor carry a
 *       block entity. Surface scans pass through them; brushes and scatter may overwrite them.</li>
 *   <li><b>Structure</b>: everything else (non-full blocks such as stairs and fences, and every block with a
 *       block entity). Nothing sculpts or scatters over them.</li>
 *   <li><b>Plant</b>: open vegetation that is not a fluid block.</li>
 * </ul>
 *
 * <p>{@link #scan} finds a column's surface: scanning down from {@code yTop} (or from the reader's
 * {@link WorldReader#heightHint} when that is lower; the cells skipped are air, so the result is the same, and a
 * hint whose cell is not air is ignored) to {@code yBottom} through open cells to the first ground cell. A column
 * has no surface if the scan starts inside a non-open cell (the surface is above the window), meets a structure
 * cell first, or finds nothing.
 */
public final class SurfaceScan {
    /** What {@link #scan} returns for a column without a surface. */
    public static final int NONE = Integer.MIN_VALUE;

    private static final int OPEN = StateFlags.AIR | StateFlags.REPLACEABLE | StateFlags.VEGETATION
            | StateFlags.FLUID_BLOCK;

    private SurfaceScan() {}

    /**
     * The surface y of column (x, z) within {@code [yBottom, yTop]}, or {@link #NONE}. The caller checks that the
     * column's chunk is loaded and that {@code yBottom <= yTop} lie inside the build height.
     *
     * @param found if a surface is found and this is not {@code null}: {@code found[0]} receives the surface
     *     state and {@code found[1]} the number of plant cells standing on it (0, 1 or 2)
     */
    public static int scan(WorldReader world, StateSpace states, int x, int z, int yTop, int yBottom, int[] found) {
        // Cells at and above the reader's height hint are air: open, and not plants. Starting there (kept inside
        // the window) finds the same surface and plant count as starting at yTop, with fewer reads.
        int start = Math.max(yBottom, Math.min(yTop, world.heightHint(x, z)));
        int topFlags = states.flags(world.get(x, start, z));
        if (start < yTop && !StateFlags.has(topFlags, StateFlags.AIR)) {
            // The hint is wrong or stale (the cell there is not air): scan the whole window instead.
            start = yTop;
            topFlags = states.flags(world.get(x, start, z));
        }
        if (!open(topFlags)) return NONE;
        int above1 = topFlags, above2 = 0;
        for (int y = start - 1; y >= yBottom; y--) {
            int state = world.get(x, y, z);
            int flags = states.flags(state);
            if (ground(flags)) {
                if (found != null) {
                    found[0] = state;
                    found[1] = plant(above1) ? (plant(above2) ? 2 : 1) : 0;
                }
                return y;
            }
            if (!open(flags)) return NONE;
            above2 = above1;
            above1 = flags;
        }
        return NONE;
    }

    /** Terrain-solid cells without a block entity: what brushes find, move and paint, and scatter stands on. */
    public static boolean ground(int flags) {
        return StateFlags.has(flags, StateFlags.TERRAIN_SOLID) && !StateFlags.has(flags, StateFlags.HAS_BLOCK_ENTITY);
    }

    /** Cells sculpting treats as air: air, replaceable, vegetation and fluid cells without a block entity. */
    public static boolean open(int flags) {
        return (flags & OPEN) != 0
                && !StateFlags.has(flags, StateFlags.TERRAIN_SOLID)
                && !StateFlags.has(flags, StateFlags.HAS_BLOCK_ENTITY);
    }

    /**
     * A cell brushes never overwrite: neither ground nor open. This covers non-full blocks (stairs, fences)
     * and every block with a block entity (chests, barrels, furnaces), full cube or not.
     */
    public static boolean structure(int flags) {
        return !ground(flags) && !open(flags);
    }

    /** Open vegetation that is not a fluid block (short grass, flowers, leaves). */
    public static boolean plant(int flags) {
        return open(flags) && StateFlags.has(flags, StateFlags.VEGETATION) && !StateFlags.has(flags, StateFlags.FLUID_BLOCK);
    }
}
