package dev.sculptory.core.brush;

import dev.sculptory.core.region.Facing;
import dev.sculptory.core.world.WorldReader;
import java.util.Objects;

/**
 * Which way a surface faces around a block, for the Surface mode ({@link SculptMode#SURFACE}): the direction Raise
 * pushes and Lower pulls, the facing of Flatten's plane, the cursor's ring and the Slope mask.
 *
 * <p><b>The estimate</b> ({@link #moment}) is the weighted first moment of the cells within {@code radius} of the
 * block's centre: every open cell (air, plants, fluids) adds its offset from the centre, every other cell (ground and
 * structures) subtracts it, each weighted {@code radius² + 1 - d²} (d the offset's length), so near cells count most.
 * The sum points from the solid blocks toward the open side, and it is exact integer arithmetic, so the client and the
 * server get the same vector. Cells outside the build height or in unloaded chunks count nothing. The offsets are
 * those of block centres, so a flat floor, wall or ceiling gives exactly (0, 1, 0), a horizontal or (0, -1, 0) whatever
 * point on the block was aimed at, and a whole ball of the averaging (not just the face under the cursor) keeps one
 * step or bump from turning it.
 *
 * <p>{@link #facing} snaps it to the nearest of the six directions (ties: up or down first, then east or west, then
 * north or south); a zero vector (no surface near, or a perfectly balanced one) counts as up, the Terrain mode's
 * direction.
 */
public final class SurfaceNormal {
    /** The smallest and largest radius of the estimate: the brush's radius, kept within these. */
    public static final int MIN_RADIUS = 3;
    public static final int MAX_RADIUS = 8;
    /** The radius of the estimate behind the Surface mode's Slope mask ({@link #slope}). */
    public static final int SLOPE_RADIUS = 2;
    /** Surfaces steeper than this many blocks up per block across (walls, overhangs) count as this for the Slope mask. */
    public static final int MAX_SLOPE = 16;

    /** Cell kinds for {@link #moment}. */
    static final int SKIP = 0;
    static final int OPEN = 1;
    static final int SOLID = 2;

    /** What {@link #moment} sees at a cell: {@link #OPEN}, {@link #SOLID} or {@link #SKIP}. */
    @FunctionalInterface
    interface Cells {
        int kind(int x, int y, int z);
    }

    private SurfaceNormal() {}

    /** The estimate's radius for a brush of {@code brushRadius}: the radius, at least 3 and at most 8. */
    public static int radiusFor(int brushRadius) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, brushRadius));
    }

    /**
     * The estimate around block (bx, by, bz) in {@code world}, for the cursor and Flatten's plane: {x, y, z} pointing
     * toward the open side, not normalized; zero when nothing around is solid or everything is.
     */
    public static long[] estimate(WorldReader world, int bx, int by, int bz, int radius) {
        Objects.requireNonNull(world);
        int bottom = world.bottomY(), top = world.topYExclusive();
        var states = world.states();
        long[] chunk = {Long.MIN_VALUE};
        boolean[] loaded = {false};
        return moment((x, y, z) -> {
            if (y < bottom || y >= top) return SKIP;
            long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
            if (key != chunk[0]) {
                chunk[0] = key;
                loaded[0] = world.isLoaded(x >> 4, z >> 4);
            }
            if (!loaded[0]) return SKIP;
            return SurfaceScan.open(states.flags(world.get(x, y, z))) ? OPEN : SOLID;
        }, bx, by, bz, radius);
    }

    /** The weighted moment of the cells within {@code radius} of block (bx, by, bz)'s centre (see the class comment). */
    static long[] moment(Cells cells, int bx, int by, int bz, int radius) {
        long nx = 0, ny = 0, nz = 0;
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int h2 = dx * dx + dz * dz;
                if (h2 > r2) continue;
                for (int dy = -radius; dy <= radius; dy++) {
                    int d2 = h2 + dy * dy;
                    if (d2 > r2 || d2 == 0) continue;
                    int kind = cells.kind(bx + dx, by + dy, bz + dz);
                    if (kind == SKIP) continue;
                    long w = kind == OPEN ? r2 + 1 - d2 : -(r2 + 1 - d2);
                    nx += w * dx;
                    ny += w * dy;
                    nz += w * dz;
                }
            }
        }
        return new long[] {nx, ny, nz};
    }

    /** The direction nearest {@code n}: its largest component's (ties: y, then x, then z); up for a zero vector. */
    public static Facing facing(long[] n) {
        long ax = Math.abs(n[0]), ay = Math.abs(n[1]), az = Math.abs(n[2]);
        if (ax == 0 && ay == 0 && az == 0) return Facing.UP;
        if (ay >= ax && ay >= az) return n[1] > 0 ? Facing.UP : Facing.DOWN;
        if (ax >= az) return n[0] > 0 ? Facing.EAST : Facing.WEST;
        return n[2] > 0 ? Facing.SOUTH : Facing.NORTH;
    }

    /**
     * How steep a surface facing {@code n} is, in blocks up per block across, rounded: 0 for floors and ceilings, 1 at
     * 45°, {@value #MAX_SLOPE} for walls and anything steeper. 0 for a zero vector.
     */
    public static int slope(long[] n) {
        double across = Math.sqrt((double) n[0] * n[0] + (double) n[2] * n[2]);
        double up = Math.abs((double) n[1]);
        if (across == 0) return 0;
        if (up == 0 || across >= up * MAX_SLOPE) return MAX_SLOPE;
        return (int) Math.floor(across / up + 0.5);
    }
}
