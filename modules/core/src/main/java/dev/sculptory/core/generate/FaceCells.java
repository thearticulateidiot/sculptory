package dev.sculptory.core.generate;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Objects;

/**
 * A flat face of blocks, as the Extrude tool found it: its cells (all in one plane square to {@code axis}) with their
 * states, and the side it faces ({@code sign} along the axis). What Extrude along a path ({@link PathExtrudeKernel})
 * and to a point ({@link TaperKernel}) carry. Immutable (the arrays are copied).
 *
 * <p>Cells holding a block entity (a chest, a sign) can't travel in a generated clipboard: {@link #without} leaves them
 * out and {@link #leftOut} counts them.
 */
public final class FaceCells {
    private final int axis;
    private final int sign;
    private final int[] xs, ys, zs, states;
    private final long leftOut;

    /**
     * @param axis 0, 1 or 2 (x, y, z): the face's normal axis
     * @param sign +1 or -1: the side along it the face is exposed on
     */
    public FaceCells(int axis, int sign, int[] xs, int[] ys, int[] zs, int[] states) {
        this(axis, sign, xs.clone(), ys.clone(), zs.clone(), states.clone(), 0);
    }

    private FaceCells(int axis, int sign, int[] xs, int[] ys, int[] zs, int[] states, long leftOut) {
        if (axis < 0 || axis > 2) throw new IllegalArgumentException("Axis " + axis);
        if (sign != 1 && sign != -1) throw new IllegalArgumentException("Sign " + sign);
        Objects.requireNonNull(states);
        int n = xs.length;
        if (ys.length != n || zs.length != n || states.length != n) throw new IllegalArgumentException("Uneven face");
        for (int i = 1; i < n; i++) {
            if (coordinate(axis, xs[i], ys[i], zs[i]) != coordinate(axis, xs[0], ys[0], zs[0])) {
                throw new IllegalArgumentException("A face lies in one plane");
            }
        }
        this.axis = axis;
        this.sign = sign;
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.states = states;
        this.leftOut = leftOut;
    }

    /** The face without its cells whose state holds a block entity ({@link #leftOut} counts them). */
    public FaceCells without(StateSpace space) {
        int kept = 0;
        for (int state : states) if (!blockEntity(space, state)) kept++;
        if (kept == states.length) return this;
        int[] x = new int[kept], y = new int[kept], z = new int[kept], s = new int[kept];
        int k = 0;
        for (int i = 0; i < states.length; i++) {
            if (blockEntity(space, states[i])) continue;
            x[k] = xs[i];
            y[k] = ys[i];
            z[k] = zs[i];
            s[k] = states[i];
            k++;
        }
        return new FaceCells(axis, sign, x, y, z, s, leftOut + states.length - kept);
    }

    /** Whether a state holds a block entity, which a generated clipboard can't carry. */
    public static boolean blockEntity(StateSpace space, int state) {
        return StateFlags.has(space.flags(state), StateFlags.HAS_BLOCK_ENTITY);
    }

    public int axis() {
        return axis;
    }

    public int sign() {
        return sign;
    }

    public int size() {
        return xs.length;
    }

    public boolean isEmpty() {
        return xs.length == 0;
    }

    /** Cells {@link #without} left out. */
    public long leftOut() {
        return leftOut;
    }

    public int x(int i) {
        return xs[i];
    }

    public int y(int i) {
        return ys[i];
    }

    public int z(int i) {
        return zs[i];
    }

    public int state(int i) {
        return states[i];
    }

    /** The coordinate along the normal every cell has. */
    public int plane() {
        return coordinate(axis, xs[0], ys[0], zs[0]);
    }

    /** The unit normal: the side the face is exposed on. */
    public double[] normal() {
        double[] n = new double[3];
        n[axis] = sign;
        return n;
    }

    /** Whether the face is a wall (its normal is horizontal). */
    public boolean wall() {
        return axis != 1;
    }

    /**
     * The face's centre cell: on its plane, at the middle of its bounds across (rounded up toward +x, +y, +z when the
     * middle falls between two cells). It need not be a cell of the face (a ring's is not).
     *
     * @throws IllegalStateException for an empty face
     */
    public BlockPos centre() {
        if (isEmpty()) throw new IllegalStateException("An empty face has no centre");
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int i = 0; i < xs.length; i++) {
            int[] c = {xs[i], ys[i], zs[i]};
            for (int k = 0; k < 3; k++) {
                min[k] = Math.min(min[k], c[k]);
                max[k] = Math.max(max[k], c[k]);
            }
        }
        int[] centre = new int[3];
        for (int k = 0; k < 3; k++) centre[k] = (int) Math.floorDiv((long) min[k] + max[k] + 1, 2L);
        return new BlockPos(centre[0], centre[1], centre[2]);
    }

    /** The cells by position, packed as {@link #key}, to their index. */
    Long2IntOpenHashMap index() {
        Long2IntOpenHashMap map = new Long2IntOpenHashMap(xs.length);
        map.defaultReturnValue(-1);
        for (int i = 0; i < xs.length; i++) map.put(key(xs[i], ys[i], zs[i]), i);
        return map;
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int coordinate(int axis, int x, int y, int z) {
        return axis == 0 ? x : axis == 1 ? y : z;
    }
}
