package dev.sculptory.core.transform;

import dev.sculptory.core.BlockPos;
import java.util.Objects;

/**
 * The "exact count" resampler: a free {@link Orientation} done as a bijection of cells, so every source cell lands in
 * exactly one result cell and no two land in the same one (no holes, no doubles, the block count kept).
 *
 * <p><b>How.</b> The free rotation {@code Yaw · Pitch · Roll} is done one axis at a time, roll first. Each angle is
 * split into its nearest quarter turns ({@link Orientation#quarters}) and a rest {@code φ} within ±45°. The rest is
 * three shears (Paeth's rotation by shearing): {@code a += t·b}, {@code b += s·a}, {@code a += t·b} with
 * {@code t = -tan(φ/2)} and {@code s = sin φ} ({@code (a, b)} the plane's axes: x and y for roll, y and z for pitch, x
 * and z for yaw), each shift rounded to a whole cell; then the quarter turns, exactly. A shear that moves every row by a
 * whole number of cells is a bijection of the grid, and so is a quarter turn, so their composition is one. Coordinates
 * are cell centres relative to the box's true centre, doubled so they are whole numbers (odd along an even side), and the
 * centre lands where {@link Orientation#bounds} puts it, so a cell always lands on a cell.
 *
 * <p><b>Accuracy.</b> Each rounding moves a cell at most half a block, so a cell lands within a block or two of where the
 * exact rotation puts it (the bound per axis is worked out from the angles and makes the result box). Quarter turns have
 * no rest and land exactly where {@link Orientation.Sampler} puts them; so do multiples of 90° on every axis.
 *
 * <p><b>Result box.</b> The cells whose centres lie within the bound of where the exact rotation turns the source cells'
 * centres: close to {@link Orientation#bounds}, sometimes a cell wider or narrower. Its {@link #offset()} is in the same
 * intermediate coordinates, so a copy placed by its anchor ({@link Orientation#mapCell}) lands in the same frame as
 * the other ways. Immutable and thread-safe.
 */
public final class ShearTurn implements CellResampler {
    private final Orientation orientation;
    private final Orientation.Sampler ideal;
    private final Transform transform;
    private final Transform inverse;
    private final int sx, sy, sz;
    /** The intermediate box's size, by axis. */
    private final int[] s;
    /** Twice the half-block shift where the centre lands, by axis: 0 or -1. */
    private final int[] shift2;
    /** Per axis (0 roll, 1 pitch, 2 yaw): quarter turns, the shears, whether there is a rest at all. */
    private final int[] quarters = new int[3];
    private final double[] shearT = new double[3];
    private final double[] shearS = new double[3];
    private final boolean[] rest = new boolean[3];
    /** The bound on how far a cell lands from where the exact rotation puts it, by result axis (blocks). */
    private final double[] error = new double[3];
    private final BlockPos size;
    private final BlockPos offset;
    /** This box's minimum corner minus the ideal sampler's, for the continuous maps. */
    private final int dx, dy, dz;

    /** The planes' axes: roll turns x towards y, pitch y towards z, yaw x towards z. */
    private static final int[][] PLANES = {{0, 1}, {1, 2}, {0, 2}};

    ShearTurn(Orientation orientation, int sx, int sy, int sz) {
        this(orientation, sx, sy, sz, true);
    }

    /** The result box alone (no continuous maps): {@link Orientation#bounds} for exact count. */
    static Orientation.Bounds bounds(Orientation orientation, int sx, int sy, int sz) {
        ShearTurn box = new ShearTurn(orientation, sx, sy, sz, false);
        return new Orientation.Bounds(box.size, box.offset);
    }

    private ShearTurn(Orientation orientation, int sx, int sy, int sz, boolean maps) {
        this.orientation = Objects.requireNonNull(orientation);
        this.transform = orientation.transform();
        this.inverse = transform.inverse();
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        BlockPos t = transform.size(sx, sy, sz);
        this.s = new int[] {t.x(), t.y(), t.z()};
        double[] landing = Orientation.landing(s, orientation.yaw(), orientation.pitch(), orientation.roll());
        this.shift2 = new int[3];
        for (int i = 0; i < 3; i++) shift2[i] = landing[i] == s[i] / 2.0 ? 0 : -1;
        int[] angles = {orientation.roll(), orientation.pitch(), orientation.yaw()};
        double[][] errors = new double[3][3];
        for (int k = 0; k < 3; k++) {
            int angle = Math.floorMod(angles[k], Orientation.FULL_TURN);
            int whole = Orientation.roundedQuarters(angle);
            quarters[k] = whole % 4;
            int phi = angle - whole * Orientation.QUARTER_TURN;
            rest[k] = phi != 0;
            double sin = Orientation.sin(phi), cos = Orientation.cos(phi);
            shearS[k] = sin;
            shearT[k] = -sin / (1 + cos);
            if (!rest[k]) continue;
            // Rounding errors of the three shears, carried through them (a: the twice-sheared axis).
            double ea = 0.5 * (Math.abs(cos) + Math.abs(shearT[k]) + 1);
            double eb = 0.5 * (Math.abs(sin) + 1);
            if ((quarters[k] & 1) == 1) {
                double swap = ea;
                ea = eb;
                eb = swap;
            }
            errors[k][PLANES[k][0]] = ea;
            errors[k][PLANES[k][1]] = eb;
        }
        // A later rotation turns an earlier one's error: |Yaw·Pitch|·e_roll + |Yaw|·e_pitch + e_yaw.
        double[] yawM = Orientation.rotation(orientation.yaw(), 0, 0);
        double[] yawPitch = Orientation.rotation(orientation.yaw(), orientation.pitch(), 0);
        for (int i = 0; i < 3; i++) {
            double e = errors[2][i];
            for (int j = 0; j < 3; j++) {
                e += Math.abs(yawPitch[3 * i + j]) * errors[0][j] + Math.abs(yawM[3 * i + j]) * errors[1][j];
            }
            error[i] = e;
        }
        // The result box: the cells within the bound of where the exact rotation turns the source cells' centres.
        double[] r = orientation.rotationMatrix();
        int[] lo = new int[3];
        int[] count = new int[3];
        for (int i = 0; i < 3; i++) {
            double reach = 0;
            for (int j = 0; j < 3; j++) reach += Math.abs(r[3 * i + j]) * (s[j] - 1) / 2.0;
            double slack = rest[0] || rest[1] || rest[2] ? error[i] + 1e-9 : 0;
            double low = landing[i] - reach - slack, high = landing[i] + reach + slack;
            double first = Math.ceil(low - 0.5), last = Math.floor(high - 0.5);
            if (first < -Orientation.MAX_TURNED_SIDE || last - first + 1 > Orientation.MAX_TURNED_SIDE) {
                throw new IllegalArgumentException("A turned box of " + sx + "x" + sy + "x" + sz + " is too large");
            }
            lo[i] = (int) first;
            count[i] = (int) Math.max(1, last - first + 1);
        }
        this.size = new BlockPos(count[0], count[1], count[2]);
        this.offset = new BlockPos(lo[0], lo[1], lo[2]);
        // The continuous maps go through the nearest sampler, in this box's frame (the same one when the orientation's
        // method is exact count, since its bounds are these).
        this.ideal = maps ? orientation.sampler(sx, sy, sz) : null;
        this.dx = maps ? offset.x() - ideal.offset().x() : 0;
        this.dy = maps ? offset.y() - ideal.offset().y() : 0;
        this.dz = maps ? offset.z() - ideal.offset().z() : 0;
    }

    @Override
    public Orientation orientation() {
        return orientation;
    }

    @Override
    public BlockPos size() {
        return size;
    }

    @Override
    public BlockPos offset() {
        return offset;
    }

    @Override
    public BlockPos sourceSize() {
        return new BlockPos(sx, sy, sz);
    }

    /** The bound on how far a cell lands from where the exact rotation puts it, along result axis {@code axis}. */
    public double error(int axis) {
        return error[axis];
    }

    /**
     * Where source cell (x, y, z) lands, result-local, into {@code out}: every cell of the source box lands inside the
     * result box, each on its own cell. Cells outside the source box map too (anchors).
     */
    public void land(int x, int y, int z, int[] out) {
        int qx = transform.mapX(x, z, sx, sz), qy = transform.mapY(y, sy), qz = transform.mapZ(x, z, sx, sz);
        long vx = 2L * qx + 1 - s[0], vy = 2L * qy + 1 - s[1], vz = 2L * qz + 1 - s[2];
        // Roll: x towards y.
        if (rest[0]) {
            vx += shift(shearT[0], vy);
            vy += shift(shearS[0], vx);
            vx += shift(shearT[0], vy);
        }
        for (int q = 0; q < quarters[0]; q++) {
            long n = -vy;
            vy = vx;
            vx = n;
        }
        // Pitch: y towards z.
        if (rest[1]) {
            vy += shift(shearT[1], vz);
            vz += shift(shearS[1], vy);
            vy += shift(shearT[1], vz);
        }
        for (int q = 0; q < quarters[1]; q++) {
            long n = -vz;
            vz = vy;
            vy = n;
        }
        // Yaw: x towards z.
        if (rest[2]) {
            vx += shift(shearT[2], vz);
            vz += shift(shearS[2], vx);
            vx += shift(shearT[2], vz);
        }
        for (int q = 0; q < quarters[2]; q++) {
            long n = -vz;
            vz = vx;
            vx = n;
        }
        out[0] = Math.toIntExact(Math.floorDiv(s[0] + shift2[0] + vx - 1, 2) - offset.x());
        out[1] = Math.toIntExact(Math.floorDiv(s[1] + shift2[1] + vy - 1, 2) - offset.y());
        out[2] = Math.toIntExact(Math.floorDiv(s[2] + shift2[2] + vz - 1, 2) - offset.z());
    }

    @Override
    public boolean source(int x, int y, int z, SourceCells cells, int[] out) {
        long vx = 2L * (offset.x() + (long) x) + 1 - s[0] - shift2[0];
        long vy = 2L * (offset.y() + (long) y) + 1 - s[1] - shift2[1];
        long vz = 2L * (offset.z() + (long) z) + 1 - s[2] - shift2[2];
        // The steps of land, undone in reverse order.
        for (int q = 0; q < quarters[2]; q++) {
            long n = vz;
            vz = -vx;
            vx = n;
        }
        if (rest[2]) {
            vx -= shift(shearT[2], vz);
            vz -= shift(shearS[2], vx);
            vx -= shift(shearT[2], vz);
        }
        for (int q = 0; q < quarters[1]; q++) {
            long n = vz;
            vz = -vy;
            vy = n;
        }
        if (rest[1]) {
            vy -= shift(shearT[1], vz);
            vz -= shift(shearS[1], vy);
            vy -= shift(shearT[1], vz);
        }
        for (int q = 0; q < quarters[0]; q++) {
            long n = vy;
            vy = -vx;
            vx = n;
        }
        if (rest[0]) {
            vx -= shift(shearT[0], vy);
            vy -= shift(shearS[0], vx);
            vx -= shift(shearT[0], vy);
        }
        // Back to intermediate cells: a doubled centre 2q + 1 - s.
        long ix = (vx + s[0] - 1) / 2, iy = (vy + s[1] - 1) / 2, iz = (vz + s[2] - 1) / 2;
        if (ix < 0 || ix >= s[0] || iy < 0 || iy >= s[1] || iz < 0 || iz >= s[2]) return false;
        out[0] = inverse.mapX((int) ix, (int) iz, s[0], s[2]);
        out[1] = inverse.mapY((int) iy, s[1]);
        out[2] = inverse.mapZ((int) ix, (int) iz, s[0], s[2]);
        return true;
    }

    /** A shear's shift of a doubled coordinate: {@code factor} times the other axis' coordinate, rounded to a cell. */
    private static long shift(double factor, long other) {
        return 2 * (long) Math.floor(factor * other * 0.5 + 0.5);
    }

    @Override
    public int maxTakers() {
        return 1;
    }

    @Override
    public int takers(int x, int y, int z, SourceCells cells, int[] out) {
        if (x < 0 || y < 0 || z < 0 || x >= sx || y >= sy || z >= sz) return 0;
        land(x, y, z, out);
        return out[0] >= 0 && out[1] >= 0 && out[2] >= 0 && out[0] < size.x() && out[1] < size.y()
                && out[2] < size.z() ? 1 : 0;
    }

    @Override
    public int[] turnedBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        double[] p = new double[3];
        for (int corner = 0; corner < 8; corner++) {
            forward((corner & 1) == 0 ? x0 : x1 + 1.0, (corner & 2) == 0 ? y0 : y1 + 1.0,
                    (corner & 4) == 0 ? z0 : z1 + 1.0, p);
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.min(lo[i], p[i]);
                hi[i] = Math.max(hi[i], p[i]);
            }
        }
        int[] max = {size.x() - 1, size.y() - 1, size.z() - 1};
        int[] out = new int[6];
        for (int i = 0; i < 3; i++) {
            double reach = Math.ceil(error[i]) + 1;
            out[i] = (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.floor(lo[i]) - reach));
            out[3 + i] = (int) Math.min(max[i], Math.max(Integer.MIN_VALUE, Math.ceil(hi[i]) + reach - 1));
            if (out[i] > out[3 + i]) return null;
        }
        return out;
    }

    @Override
    public void forward(double x, double y, double z, double[] out) {
        ideal.forward(x, y, z, out);
        out[0] -= dx;
        out[1] -= dy;
        out[2] -= dz;
    }

    @Override
    public void back(double x, double y, double z, double[] out) {
        ideal.back(x + dx, y + dy, z + dz, out);
    }
}
