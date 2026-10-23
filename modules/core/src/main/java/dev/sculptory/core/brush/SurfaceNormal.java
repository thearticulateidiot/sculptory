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
 *
 * <p><b>At a dab's point</b> ({@link #facingAt}) the face the point lies on comes first. A point aimed at a block face
 * lies on it, or within a sixteenth of it on either side (rounding to 1/16 block, and a mirrored copy of a point a
 * sixteenth inside a block is a sixteenth inside the mirrored one): when the cells on the two sides of that face are
 * one solid and one open, the point is on a surface facing from the solid cell to the open one. The estimate is then
 * centred on that open cell, the one in front of the face, not on the block holding the point: centred in a wall one
 * block thick, with open cells on both sides, the estimate cancels to zero. Next, a face beats the wider
 * estimate when the estimate within {@value #FACE_RADIUS} blocks of the open cell agrees with it: the side of a short
 * wall on a floor, or of a cliff smaller than the brush, is then the surface worked, while the side of a one-block bump
 * or step (where that small estimate already points up) is left to the brush-sized estimate, which keeps a stroke over
 * rough ground working the ground. Without a face at the point, the estimate is centred on the point's block as before.
 */
public final class SurfaceNormal {
    /** The smallest and largest radius of the estimate: the brush's radius, kept within these. */
    public static final int MIN_RADIUS = 3;
    public static final int MAX_RADIUS = 8;
    /** The radius of the estimate behind the Surface mode's Slope mask ({@link #slope}). */
    public static final int SLOPE_RADIUS = 2;
    /** The radius of the small estimate that lets the face under a dab's point decide ({@link #facingAt}). */
    public static final int FACE_RADIUS = 2;
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

    /** The face a point lies on ({@link #faceAt}): its direction (from the solid cell to the open one), the open cell. */
    record Face(Facing facing, int x, int y, int z) {}

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
        return moment(cells(world), bx, by, bz, radius);
    }

    /**
     * The way the surface faces at a dab's point (x16, y16, z16, in 1/16 block) for a brush of {@code brushRadius}, as
     * the kernel works it out for Raise and Lower and the client for the cursor's ring and Flatten's plane (see the
     * class comment); {@code null} when nothing tells: no face at the point and a zero estimate.
     */
    public static Facing facingAt(WorldReader world, int x16, int y16, int z16, int brushRadius) {
        return facingAt(cells(world), x16, y16, z16, brushRadius);
    }

    /** {@link #facingAt(WorldReader, int, int, int, int)} over {@code cells}. */
    static Facing facingAt(Cells cells, int x16, int y16, int z16, int brushRadius) {
        Face face = faceAt(cells, x16, y16, z16);
        int cx = x16 >> 4, cy = y16 >> 4, cz = z16 >> 4;
        if (face != null) {
            cx = face.x();
            cy = face.y();
            cz = face.z();
            long[] near = moment(cells, cx, cy, cz, FACE_RADIUS);
            if (!zero(near) && facing(near) == face.facing()) return face.facing();
        }
        long[] n = moment(cells, cx, cy, cz, radiusFor(brushRadius));
        if (!zero(n)) return facing(n);
        return face == null ? null : face.facing();
    }

    /**
     * The block face point (x16, y16, z16) lies on, or within a sixteenth of, with a solid cell on one side and an open
     * cell on the other; {@code null} when there is none. A point on two or three such faces (an edge or corner) takes
     * the nearest: exactly on it before a sixteenth away, then y, then x, then z, the order {@link #facing} breaks ties
     * in. Integer arithmetic on the point's 1/16 coordinates only, so the client and the server find the same face.
     */
    static Face faceAt(Cells cells, int x16, int y16, int z16) {
        Face best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int axis : new int[] {1, 0, 2}) {
            int p = axis == 0 ? x16 : axis == 1 ? y16 : z16;
            int offset = p & 15;
            // On the block's low face (offset 0) or a sixteenth past it (1), or a sixteenth short of its high face
            // (15). A hit rounded down to 1/16 lands on 0 or 15; a mirrored copy turns 15 into 1.
            int distance;
            int low;
            if (offset == 0 || offset == 1) {
                distance = offset;
                low = (p >> 4) - 1;
            } else if (offset == 15) {
                distance = 1;
                low = p >> 4;
            } else {
                continue;
            }
            if (distance >= bestDistance) continue;
            int lowKind = kindAlong(cells, axis, low, x16 >> 4, y16 >> 4, z16 >> 4);
            int highKind = kindAlong(cells, axis, low + 1, x16 >> 4, y16 >> 4, z16 >> 4);
            Face face;
            if (lowKind == SOLID && highKind == OPEN) {
                face = face(positive(axis), axis, low + 1, x16 >> 4, y16 >> 4, z16 >> 4);
            } else if (lowKind == OPEN && highKind == SOLID) {
                face = face(positive(axis).opposite(), axis, low, x16 >> 4, y16 >> 4, z16 >> 4);
            } else {
                continue;
            }
            best = face;
            bestDistance = distance;
        }
        return best;
    }

    /** The kind of the cell at coordinate {@code c} on {@code axis}, the block (bx, by, bz)'s on the other two. */
    private static int kindAlong(Cells cells, int axis, int c, int bx, int by, int bz) {
        return switch (axis) {
            case 0 -> cells.kind(c, by, bz);
            case 1 -> cells.kind(bx, c, bz);
            default -> cells.kind(bx, by, c);
        };
    }

    private static Face face(Facing facing, int axis, int c, int bx, int by, int bz) {
        return switch (axis) {
            case 0 -> new Face(facing, c, by, bz);
            case 1 -> new Face(facing, bx, c, bz);
            default -> new Face(facing, bx, by, c);
        };
    }

    private static Facing positive(int axis) {
        return axis == 0 ? Facing.EAST : axis == 1 ? Facing.UP : Facing.SOUTH;
    }

    private static boolean zero(long[] n) {
        return n[0] == 0 && n[1] == 0 && n[2] == 0;
    }

    /** What the estimate sees in {@code world}: cells outside the build height or in unloaded chunks are skipped. */
    private static Cells cells(WorldReader world) {
        Objects.requireNonNull(world);
        int bottom = world.bottomY(), top = world.topYExclusive();
        var states = world.states();
        long[] chunk = {Long.MIN_VALUE};
        boolean[] loaded = {false};
        return (x, y, z) -> {
            if (y < bottom || y >= top) return SKIP;
            long key = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
            if (key != chunk[0]) {
                chunk[0] = key;
                loaded[0] = world.isLoaded(x >> 4, z >> 4);
            }
            if (!loaded[0]) return SKIP;
            return SurfaceScan.open(states.flags(world.get(x, y, z))) ? OPEN : SOLID;
        };
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
