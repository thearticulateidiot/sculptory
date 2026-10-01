package dev.sculptory.core.transform;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.state.StateSpace;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A horizontal mirror followed by clockwise quarter turns around +Y (seen from above: north, east, south, west), and,
 * when {@code upsideDown}, a flip upside down: a mirror in the horizontal plane that maps height {@code y} of a box
 * {@code sy} tall to {@code sy - 1 - y}. The flip commutes with the turns and the horizontal mirrors, so it may be
 * thought of as applied first or last. {@code quarterTurnsCw} is normalized into {@code 0..3}.
 *
 * <p>Positions are local cells of a source box of size (sx, sy, sz), i.e. {@code 0 <= x < sx}. They map onto the
 * transformed box, whose size is {@link #size} (the flip never changes a size; odd turns swap x and z). Without the
 * flip y is never changed. Math ported from the old {@code ClipboardTransform}; the flip is the
 * upside-down flip.
 */
public record Transform(int quarterTurnsCw, Mirror mirror, boolean upsideDown) {
    public static final Transform IDENTITY = new Transform(0, Mirror.NONE);
    /** The flip upside down alone. */
    public static final Transform UPSIDE_DOWN = new Transform(0, Mirror.NONE, true);

    public Transform {
        Objects.requireNonNull(mirror);
        quarterTurnsCw = Math.floorMod(quarterTurnsCw, 4);
    }

    /** A horizontal transform: the mirror, then the turns, no flip. */
    public Transform(int quarterTurnsCw, Mirror mirror) {
        this(quarterTurnsCw, mirror, false);
    }

    public static Transform rotation(int quarterTurnsCw) {
        return new Transform(quarterTurnsCw, Mirror.NONE);
    }

    /** All 8 distinct horizontal transforms: every mirror with every rotation (no flip). */
    public static List<Transform> all() {
        List<Transform> all = new ArrayList<>(8);
        for (Mirror mirror : List.of(Mirror.NONE, Mirror.X)) {
            for (int turns = 0; turns < 4; turns++) all.add(new Transform(turns, mirror));
        }
        return List.copyOf(all);
    }

    /** All 16 distinct transforms: {@link #all()} and each of them flipped upside down. */
    public static List<Transform> allWithFlips() {
        List<Transform> all = new ArrayList<>(16);
        for (boolean flip : new boolean[] {false, true}) {
            for (Transform t : all()) all.add(t.withUpsideDown(flip));
        }
        return List.copyOf(all);
    }

    public boolean isIdentity() {
        return quarterTurnsCw == 0 && mirror == Mirror.NONE && !upsideDown;
    }

    /** Whether heights are kept: no flip upside down (every transform before the flip was horizontal). */
    public boolean isHorizontal() {
        return !upsideDown;
    }

    /** The mirror and turns alone, without the flip. */
    public Transform horizontal() {
        return upsideDown ? new Transform(quarterTurnsCw, mirror) : this;
    }

    /** The same mirror and turns, flipped upside down or not. */
    public Transform withUpsideDown(boolean flip) {
        return flip == upsideDown ? this : new Transform(quarterTurnsCw, mirror, flip);
    }

    /** Transformed x size: odd turns swap x and z. */
    public int sizeX(int sx, int sz) {
        return (quarterTurnsCw & 1) == 0 ? sx : sz;
    }

    /** Transformed z size: odd turns swap x and z. */
    public int sizeZ(int sx, int sz) {
        return (quarterTurnsCw & 1) == 0 ? sz : sx;
    }

    /** Transformed box size, as a position (x, y, z) of sizes. */
    public BlockPos size(int sx, int sy, int sz) {
        checkSize(sx, sy, sz);
        return new BlockPos(sizeX(sx, sz), sy, sizeZ(sx, sz));
    }

    /** Transformed local x of source cell (x, z). Unchecked, allocation-free. */
    public int mapX(int x, int z, int sx, int sz) {
        int mx = mirror == Mirror.X ? sx - 1 - x : x;
        int mz = mirror == Mirror.Z ? sz - 1 - z : z;
        return switch (quarterTurnsCw) {
            case 0 -> mx;
            case 1 -> sz - 1 - mz;
            case 2 -> sx - 1 - mx;
            default -> mz;
        };
    }

    /** Transformed local y of source height {@code y} in a box {@code sy} tall. Unchecked, allocation-free. */
    public int mapY(int y, int sy) {
        return upsideDown ? sy - 1 - y : y;
    }

    /** {@link #mapY} in long arithmetic, for heights far outside the box (an anchor). */
    public long mapY(long y, int sy) {
        return upsideDown ? sy - 1L - y : y;
    }

    /** Transformed local z of source cell (x, z). Unchecked, allocation-free. */
    public int mapZ(int x, int z, int sx, int sz) {
        int mx = mirror == Mirror.X ? sx - 1 - x : x;
        int mz = mirror == Mirror.Z ? sz - 1 - z : z;
        return switch (quarterTurnsCw) {
            case 0 -> mz;
            case 1 -> mx;
            case 2 -> sz - 1 - mz;
            default -> sx - 1 - mx;
        };
    }

    /** Maps a local cell of a source box of size (sx, sy, sz) into the transformed box. */
    public BlockPos apply(int x, int y, int z, int sx, int sy, int sz) {
        checkSize(sx, sy, sz);
        if (x < 0 || x >= sx || y < 0 || y >= sy || z < 0 || z >= sz) {
            throw new IllegalArgumentException("Cell outside source box: " + x + "," + y + "," + z);
        }
        return new BlockPos(mapX(x, z, sx, sz), mapY(y, sy), mapZ(x, z, sx, sz));
    }

    public BlockPos apply(BlockPos local, BlockPos sourceSize) {
        return apply(local.x(), local.y(), local.z(), sourceSize.x(), sourceSize.y(), sourceSize.z());
    }

    /**
     * The transform that maps the transformed box back onto the source box. Mirrored transforms are their own
     * inverse; a pure rotation inverts to the opposite rotation; the flip is its own inverse.
     */
    public Transform inverse() {
        return mirror == Mirror.NONE ? new Transform(-quarterTurnsCw, Mirror.NONE, upsideDown) : this;
    }

    /**
     * The transform that applies this one, then {@code next} (with {@code next} using this transform's
     * output size as its source size). The flips cancel in pairs, since the flip commutes with everything else.
     */
    public Transform compose(Transform next) {
        Objects.requireNonNull(next);
        boolean flip = upsideDown ^ next.upsideDown;
        if (next.mirror == Mirror.NONE) {
            return new Transform(quarterTurnsCw + next.quarterTurnsCw, mirror, flip);
        }
        // A mirror conjugates a rotation to its inverse: M_q R^a = R^-a M_q.
        int turns = next.quarterTurnsCw - quarterTurnsCw;
        if (mirror == Mirror.NONE) return new Transform(turns, next.mirror, flip);
        if (mirror == next.mirror) return new Transform(turns, Mirror.NONE, flip);
        // Mirroring x and z together is a half turn.
        return new Transform(turns + 2, Mirror.NONE, flip);
    }

    /** The block state after this transform: mirror first, then rotate, then {@link StateSpace#flip} when flipped. */
    public int applyToState(StateSpace states, int handle) {
        int h = mirror == Mirror.NONE ? handle : states.mirror(handle, mirror);
        h = quarterTurnsCw == 0 ? h : states.rotate(h, quarterTurnsCw);
        return upsideDown ? states.flip(h) : h;
    }

    private static void checkSize(int sx, int sy, int sz) {
        if (sx <= 0 || sy <= 0 || sz <= 0) throw new IllegalArgumentException("Transform source size");
    }
}
