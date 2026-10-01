package dev.sculptory.fabric.client.editor.tools.select;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import java.util.Arrays;
import java.util.Objects;

/**
 * A lasso selection: a freehand loop drawn on the horizontal plane through the top face of the pressed block
 * ({@code y = pressed y + 1}), then the cells of the pressed block's layer whose centres lie inside the loop, extruded
 * upward. Pure; the loop is collected on the client thread and {@link #rasterise} may run on any thread.
 *
 * <p><b>Points.</b> {@link #addPoint} follows the cursor's ray where it crosses the plane: a point is clamped to
 * {@link #MAX_REACH} blocks from the press (so a grazing ray can't draw a loop across the world) and dropped when it is
 * closer than {@link #MIN_STEP} to the previous one. The loop closes from the last point back to the first.
 *
 * <p><b>Rasterisation.</b> A cell is inside by the even-odd rule (the same test on every cell centre, so a
 * self-intersecting loop selects predictably: an overlap twice around is outside again), computed row by row: the
 * loop's edges crossing the row's centre line {@code z + 0.5} are sorted and cells are filled between each pair. A
 * loop of fewer than {@link #MIN_POINTS} points, or with no cell centre inside, selects nothing. Layers run from the
 * pressed block's up for {@code height} blocks, clamped to the build height; cells are added layer by layer, row by
 * row, and stop exactly at the cap ({@link Result#hitLimit()}).
 */
final class LassoSelect {
    /** The farthest a point may lie from the press, in blocks. */
    static final double MAX_REACH = 512;
    /** Points closer than this to the previous one are dropped. */
    static final double MIN_STEP = 0.25;
    /** The fewest points that enclose anything. */
    static final int MIN_POINTS = 3;

    /** The cells of a finished lasso (empty when the loop enclosed nothing) and whether the cap cut them off. */
    record Result(CellSet cells, boolean hitLimit) {
        Result {
            Objects.requireNonNull(cells);
        }
    }

    private final int layerY;
    private final double originX;
    private final double originZ;
    private double[] xs = new double[64];
    private double[] zs = new double[64];
    private int size;

    /**
     * Starts a loop at the press.
     *
     * @param layerY the pressed block's y: the first layer selected
     * @param x the press point on the plane
     * @param z the press point on the plane
     */
    LassoSelect(int layerY, double x, double z) {
        this.layerY = layerY;
        this.originX = x;
        this.originZ = z;
        xs[0] = x;
        zs[0] = z;
        size = 1;
    }

    /** The first layer selected: the pressed block's. */
    int layerY() {
        return layerY;
    }

    /** The plane the loop is drawn on: the top face of the pressed block's layer. */
    double planeY() {
        return layerY + 1.0;
    }

    /**
     * Adds the cursor's point on the plane, clamped to {@link #MAX_REACH} from the press; returns whether it was kept
     * (a point within {@link #MIN_STEP} of the previous one is not).
     */
    boolean addPoint(double x, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) return false;
        double dx = x - originX, dz = z - originZ;
        double reach = Math.sqrt(dx * dx + dz * dz);
        if (reach > MAX_REACH) {
            x = originX + dx * (MAX_REACH / reach);
            z = originZ + dz * (MAX_REACH / reach);
        }
        double px = x - xs[size - 1], pz = z - zs[size - 1];
        if (px * px + pz * pz < MIN_STEP * MIN_STEP) return false;
        if (size == xs.length) {
            xs = Arrays.copyOf(xs, size * 2);
            zs = Arrays.copyOf(zs, size * 2);
        }
        xs[size] = x;
        zs[size] = z;
        size++;
        return true;
    }

    /** Points kept so far (the press first). */
    int size() {
        return size;
    }

    double pointX(int i) {
        return xs[Objects.checkIndex(i, size)];
    }

    double pointZ(int i) {
        return zs[Objects.checkIndex(i, size)];
    }

    /** Whether the loop can enclose anything: at least {@link #MIN_POINTS} points. */
    boolean closed() {
        return size >= MIN_POINTS;
    }

    /** The layers {@code height} blocks up from the pressed block's reach within the build height: {min, max}. */
    int[] layers(int height, int bottomY, int topYExclusive) {
        if (height < 1) throw new IllegalArgumentException("The height must be at least 1: " + height);
        int min = Math.max(bottomY, layerY);
        int max = (int) Math.min(topYExclusive - 1L, layerY + (long) height - 1);
        return new int[] {min, max};
    }

    /** An upper bound on the cells a rasterisation lists: the loop's bounding rectangle times the layers. */
    long atMost(int height, int bottomY, int topYExclusive) {
        if (!closed()) return 0;
        int[] layers = layers(height, bottomY, topYExclusive);
        if (layers[1] < layers[0]) return 0;
        double minX = xs[0], maxX = xs[0], minZ = zs[0], maxZ = zs[0];
        for (int i = 1; i < size; i++) {
            minX = Math.min(minX, xs[i]);
            maxX = Math.max(maxX, xs[i]);
            minZ = Math.min(minZ, zs[i]);
            maxZ = Math.max(maxZ, zs[i]);
        }
        long columns = (long) (Math.floor(maxX) - Math.floor(minX) + 1) * (long) (Math.floor(maxZ) - Math.floor(minZ) + 1);
        return columns * (layers[1] - layers[0] + 1);
    }

    /**
     * The cells inside the loop, {@code height} layers up from the pressed block within the build height, at most
     * {@code cap} of them (see the class comment). Any thread; the loop must not change meanwhile.
     */
    Result rasterise(int height, int bottomY, int topYExclusive, long cap) {
        if (cap < 1) throw new IllegalArgumentException("The cap must be at least 1: " + cap);
        CellSet.Builder cells = CellSet.builder();
        int[] layers = layers(height, bottomY, topYExclusive);
        if (!closed() || layers[1] < layers[0]) return new Result(cells.build(), false);
        double minZ = zs[0], maxZ = zs[0];
        for (int i = 1; i < size; i++) {
            minZ = Math.min(minZ, zs[i]);
            maxZ = Math.max(maxZ, zs[i]);
        }
        int firstRow = (int) Math.floor(minZ), lastRow = (int) Math.floor(maxZ);
        double[] crossings = new double[size];
        for (int y = layers[0]; y <= layers[1]; y++) {
            for (int z = firstRow; z <= lastRow; z++) {
                int n = crossings(z + 0.5, crossings);
                for (int k = 0; k + 1 < n; k += 2) {
                    int x0 = (int) Math.ceil(crossings[k] - 0.5);
                    int x1 = (int) Math.ceil(crossings[k + 1] - 0.5) - 1;
                    if (x1 < x0) continue;
                    long room = cap - cells.size();
                    if (room <= 0) return new Result(cells.build(), true);
                    if (x1 - x0 + 1 <= room) {
                        cells.addAll(new Region.Cuboid(new Box(new BlockPos(x0, y, z), new BlockPos(x1, y, z))));
                    } else {
                        for (int x = x0; x < x0 + room; x++) cells.add(x, y, z);
                        return new Result(cells.build(), true);
                    }
                }
            }
        }
        return new Result(cells.build(), false);
    }

    /**
     * Whether the point is inside the loop by the even-odd rule: the crossing test every cell's centre goes through
     * (the rasteriser sorts the same crossings per row).
     */
    boolean contains(double px, double pz) {
        boolean inside = false;
        for (int i = 0, j = size - 1; i < size; j = i++) {
            if ((zs[i] > pz) != (zs[j] > pz) && px < crossing(i, j, pz)) inside = !inside;
        }
        return inside;
    }

    /** The x where the loop's edges cross the line {@code z = pz}, sorted; returns how many (always even). */
    private int crossings(double pz, double[] into) {
        int n = 0;
        for (int i = 0, j = size - 1; i < size; j = i++) {
            if ((zs[i] > pz) != (zs[j] > pz)) into[n++] = crossing(i, j, pz);
        }
        Arrays.sort(into, 0, n);
        return n;
    }

    private double crossing(int i, int j, double pz) {
        return (xs[j] - xs[i]) * (pz - zs[i]) / (zs[j] - zs[i]) + xs[i];
    }
}
