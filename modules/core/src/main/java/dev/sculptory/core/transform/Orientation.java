package dev.sculptory.core.transform;

import dev.sculptory.core.BlockPos;
import java.util.Objects;

/**
 * How a copy is turned (protocol 6): an exact {@link Transform} (mirror, quarter turns about +Y, flip upside down),
 * applied first, then an optional free rotation given as three angles in hundredths of a degree. Paste and Move carry
 * one; most are exact ({@link #isExact()}), and an exact orientation behaves exactly as its {@link Transform} always has.
 *
 * <h2>Axes and angles</h2>
 * World axes: +X east, +Y up, +Z south. Angles are in 1/100 degree and normalised into {@code 0..35999}.
 * <ul>
 *   <li>{@code yaw} turns about the vertical axis, <b>clockwise seen from above</b> (east goes to south), the direction
 *       of {@link Transform#quarterTurnsCw()} and of the game's own yaw: {@code x' = x cos - z sin},
 *       {@code z' = x sin + z cos}.</li>
 *   <li>{@code pitch} turns about the east-west (X) axis, right-handed (counter-clockwise looking from +X towards the
 *       origin; up goes to south): {@code y' = y cos - z sin}, {@code z' = y sin + z cos}.</li>
 *   <li>{@code roll} turns about the north-south (Z) axis, right-handed (counter-clockwise looking from +Z towards the
 *       origin; east goes to up): {@code x' = x cos - y sin}, {@code y' = x sin + y cos}.</li>
 * </ul>
 * The free rotation is {@code R = Yaw · Pitch · Roll} acting on column vectors: <b>roll first, then pitch, then yaw</b>,
 * each about the fixed world axes. Sines and cosines of multiples of 90° are exact (0 and ±1); every other angle uses
 * {@link StrictMath}, so client and server compute bit-identical results.
 *
 * <h2>Boxes, the pivot and the result box</h2>
 * An orientation maps a <i>source box</i> of size {@code (sx, sy, sz)} (local cells {@code 0 <= x < sx}; cell
 * {@code (x, y, z)} is the unit cube {@code [x, x+1) × [y, y+1) × [z, z+1)}) onto a <i>result box</i>, {@link #bounds}:
 * <ol>
 *   <li>The exact transform maps the source box onto the <i>intermediate box</i> of size
 *       {@code transform.size(sx, sy, sz)}, cell for cell, exactly as {@link Transform#apply} does.</li>
 *   <li>The free rotation turns the intermediate box about its <b>true centre</b> {@code C = (sx'/2, sy'/2, sz'/2)},
 *       and the centre lands on {@code L = C + δ}: {@code δ} is 0 on an axis, or half a block towards the minimum on a
 *       result axis whose cells would otherwise land on cell corners. That happens where the nearest quarter turns
 *       ({@link #quarterTurns}: each angle rounded to a multiple of 90°, halfway rounding down) bring an axis of the
 *       other parity there (an even width turned onto an odd depth), so a quarter turn on any axis stays an exact
 *       permutation of cells, with no ties and no holes, and an even-sized build turns about its middle, not half a
 *       block off it.</li>
 *   <li>The result box holds every cell whose centre lies in the half-open axis-aligned bounds {@code [L, H)} of the
 *       turned intermediate box. {@link Bounds#offset()} is its minimum corner in the intermediate box's coordinates.
 *       For an exact orientation the result box is the intermediate box ({@code offset} 0). A free orientation's
 *       result box can hold a few cells no source cell maps to (corners of a turned box): {@link #inverseCell}
 *       returns {@code null} for them.</li>
 * </ol>
 * {@link #inverseCell} maps a result cell back to the source cell it takes: the centre of the result cell is turned
 * back ({@code p = Rᵀ(q - L) + C}) and the cell containing that point is taken ("nearest source cell"; a point on a
 * cell boundary takes the higher cell). Resampling every result cell this way leaves no holes. {@link #mapCell} and
 * {@link #mapPoint} map forwards (anchors, entities). {@link CellResampler} has the other ways to fill the result box
 * (an exact-count bijection, a smoothing majority).
 *
 * <h2>Canonical form</h2>
 * The constructor normalises each angle into {@code 0..35999}. When all three are multiples of 90° and the rotation
 * keeps the vertical axis vertical (it maps Y to ±Y: any yaw quarter turns, a half turn of pitch or roll, and their
 * combinations), the rotation is one of the 16 {@link Transform}s and is folded into {@link #transform()} with all
 * angles set to 0, so such an orientation is exact. Quarter turns of pitch or roll stay free: the exact group has no
 * such turns, but the resampling above is exact for them by construction. Equality compares the canonical fields: two
 * free orientations that describe the same rotation with different angles (gimbal lock) are not equal.
 *
 * <p>An exact orientation and a free one frame their result boxes differently (the exact transform keeps the box's
 * minimum corner; a free rotation turns about the pivot), so a caller that turns continuously through 90° places the
 * copy by a point ({@link #mapCell} of an anchor, as a paste does), not by the result box's corner.
 *
 * <h2>Resampling</h2>
 * A free orientation also carries how its blocks land on the grid ({@link #resampling()}: nearest block, exact count
 * or smooth) and whether surface cells on turned slopes get stairs and slabs ({@link #smoothSlopes()}, for every
 * method). They travel with the angles (Paste, PasteMany placements and Move all carry an orientation) and are part
 * of equality. An exact orientation is a permutation of cells: the constructor sets them to {@link Resampling#NEAREST}
 * and {@code false}, its canonical form. {@link #then} and {@link #conjugated} keep them; {@link #free(Transform, int,
 * int, int)} and {@link #nearest} give the defaults. {@link #resampler} fills a result box the method's way
 * ({@link CellResampler}); {@link #bounds} is the method's result box (exact count's can be a cell wider or narrower),
 * and {@link #mapCell}, {@link #mapPoint}, {@link #inverseCell} and {@link Sampler} work in its frame, so a paste
 * placed by its anchor lands in the same place whatever the method.
 *
 * @param transform the exact part, applied first
 * @param yaw clockwise seen from above, 1/100 degree
 * @param pitch about +X, right-handed, 1/100 degree
 * @param roll about +Z, right-handed, 1/100 degree
 * @param resampling how a free rotation's blocks land on the grid ({@link Resampling#NEAREST} when exact)
 * @param smoothSlopes whether turned slopes get stairs and slabs after resampling ({@code false} when exact)
 */
public record Orientation(Transform transform, int yaw, int pitch, int roll, Resampling resampling,
                          boolean smoothSlopes) {
    /** A full turn in the units of the angles (1/100 degree). */
    public static final int FULL_TURN = 36_000;
    /** A quarter turn in the units of the angles (1/100 degree). */
    public static final int QUARTER_TURN = 9_000;
    public static final Orientation IDENTITY = new Orientation(Transform.IDENTITY, 0, 0, 0);
    /**
     * The longest side a free orientation's result box may have ({@link #bounds} refuses more): 2^30 cells, so sizes and
     * offsets stay well inside the int range (the world is 60,000,000 blocks across).
     */
    public static final int MAX_TURNED_SIDE = 1 << 30;

    public Orientation {
        Objects.requireNonNull(transform);
        Objects.requireNonNull(resampling);
        yaw = Math.floorMod(yaw, FULL_TURN);
        pitch = Math.floorMod(pitch, FULL_TURN);
        roll = Math.floorMod(roll, FULL_TURN);
        if ((yaw | pitch | roll) != 0 && yaw % QUARTER_TURN == 0 && pitch % QUARTER_TURN == 0
                && roll % QUARTER_TURN == 0) {
            Transform folded = foldable(rotation(yaw, pitch, roll));
            if (folded != null) {
                transform = transform.compose(folded);
                yaw = 0;
                pitch = 0;
                roll = 0;
            }
        }
        if ((yaw | pitch | roll) == 0) {
            // An exact orientation has nothing to resample.
            resampling = Resampling.NEAREST;
            smoothSlopes = false;
        }
    }

    /** {@code transform}, then the free rotation, resampled to the nearest block without stairs on slopes. */
    public Orientation(Transform transform, int yaw, int pitch, int roll) {
        this(transform, yaw, pitch, roll, Resampling.NEAREST, false);
    }

    /** The exact orientation of {@code transform}. */
    public static Orientation of(Transform transform) {
        return transform.isIdentity() ? IDENTITY : new Orientation(transform, 0, 0, 0);
    }

    /**
     * {@code transform}, then the free rotation (angles in 1/100 degree), in canonical form, resampled to the nearest
     * block ({@link Resampling#NEAREST}, no stairs on slopes).
     */
    public static Orientation free(Transform transform, int yaw, int pitch, int roll) {
        return new Orientation(transform, yaw, pitch, roll);
    }

    /** {@code transform}, then the free rotation, resampled by {@code resampling}, in canonical form. */
    public static Orientation free(Transform transform, int yaw, int pitch, int roll, Resampling resampling,
                                   boolean smoothSlopes) {
        return new Orientation(transform, yaw, pitch, roll, resampling, smoothSlopes);
    }

    /**
     * This orientation resampled by {@code method}, with or without stairs on slopes. An exact orientation is returned
     * as it is (it has nothing to resample).
     */
    public Orientation withResampling(Resampling method, boolean slopes) {
        Objects.requireNonNull(method);
        if (isExact() || (method == resampling && slopes == smoothSlopes)) return this;
        return new Orientation(transform, yaw, pitch, roll, method, slopes);
    }

    /** Whether the resampling is the default one: nearest block, no stairs on slopes (always for an exact one). */
    public boolean resamplesByDefault() {
        return resampling == Resampling.NEAREST && !smoothSlopes;
    }

    /** Whether there is no free rotation: the orientation is its {@link #transform()} alone. */
    public boolean isExact() {
        return yaw == 0 && pitch == 0 && roll == 0;
    }

    public boolean isIdentity() {
        return isExact() && transform.isIdentity();
    }

    /**
     * The exact transform of an exact orientation.
     *
     * @throws IllegalStateException if the orientation has a free rotation
     */
    public Transform exact() {
        if (!isExact()) throw new IllegalStateException("A free orientation has no exact transform: " + this);
        return transform;
    }

    /**
     * The whole orientation's linear map in world axes, as 9 doubles, row-major ({@code m[3 * row + column]}): the free
     * rotation times the exact transform's signed permutation (a mirror or the flip makes the determinant -1). A fresh
     * array each call.
     */
    public double[] matrix() {
        double[] r = rotation(yaw, pitch, roll);
        int[] t = linear(transform);
        double[] m = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                double sum = 0;
                for (int k = 0; k < 3; k++) sum += r[3 * row + k] * t[3 * k + col];
                m[3 * row + col] = sum;
            }
        }
        return m;
    }

    /**
     * The free rotation alone ({@code Yaw · Pitch · Roll}, the identity for an exact orientation), 9 doubles,
     * row-major: {@link #matrix()} is this times the exact transform's signed permutation. A fresh array each call.
     */
    public double[] rotationMatrix() {
        return rotation(yaw, pitch, roll);
    }

    /**
     * The rotation {@code Yaw · Pitch · Roll} of three angles in 1/100 degree, row-major, as a free orientation turns
     * (without folding: {@code rotationMatrix(9000, 0, 0)} is a quarter turn, though {@code free(t, 9000, 0, 0)} folds it
     * into its transform). A fresh array each call.
     */
    public static double[] rotationMatrix(int yaw, int pitch, int roll) {
        return rotation(yaw, pitch, roll);
    }

    /**
     * This orientation followed by a horizontal {@code image} (a symmetry image: mirrors, quarter turns about the
     * vertical, and the flip), as one orientation: the copy a symmetric paste places. For an exact orientation it is
     * {@code transform.compose(image)}. For a free one the image's quarter turns add to the yaw (the exact part keeps
     * its box, so the copy turns about the same centre) and its mirror and flip move into the exact part, with the
     * angles they reverse negated (a mirror across x reverses yaw and roll, across z yaw and pitch, the flip pitch and
     * roll). The matrix is exactly {@code image · matrix()}. For a free orientation this names the copy but does not
     * place it as the exact mirror image of the original when the box has an even size along a mirrored axis (the
     * copy turns about its own centre): programs write a free paste's copies with
     * {@code PasteProgram.compileImage} instead.
     */
    public Orientation then(Transform image) {
        Objects.requireNonNull(image);
        if (isExact()) return of(transform.compose(image));
        int y = yaw, p = pitch, r = roll;
        if (image.mirror() == Mirror.X) {
            y = -y;
            r = -r;
        } else if (image.mirror() == Mirror.Z) {
            y = -y;
            p = -p;
        }
        if (image.upsideDown()) {
            p = -p;
            r = -r;
        }
        Transform reflected = transform.compose(new Transform(0, image.mirror(), image.upsideDown()));
        return free(reflected, y + image.quarterTurnsCw() * QUARTER_TURN, p, r)
                .withResampling(resampling, smoothSlopes);
    }

    /**
     * {@code image⁻¹}, then this orientation, then {@code image}: how the image of a moved selection turns so that its
     * cells land on the images of where this orientation's land (a symmetric move's copy). For an exact orientation it
     * is {@code image.inverse().compose(transform).compose(image)}. For a free one the rotation is conjugated: mirrors
     * and the half turn negate angles exactly; a quarter turn swaps the pitch and roll axes, which the yaw, pitch, roll
     * order can hold exactly only when one of them is 0, and otherwise takes the nearest angles in 1/100 degree
     * ({@link #nearest}). Programs write a free copy as the exact image of the original's cells, so these angles only
     * name the copy.
     */
    public Orientation conjugated(Transform image) {
        Objects.requireNonNull(image);
        Transform t = image.inverse().compose(transform).compose(image);
        if (isExact()) return of(t);
        int[] i = linear(image);
        double[] r = rotation(yaw, pitch, roll);
        // I · R · I⁻¹, with I a signed permutation (its inverse is its transpose).
        double[] conjugate = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                double sum = 0;
                for (int a = 0; a < 3; a++) {
                    for (int b = 0; b < 3; b++) sum += i[3 * row + a] * r[3 * a + b] * i[3 * col + b];
                }
                conjugate[3 * row + col] = sum;
            }
        }
        return nearest(t, conjugate).withResampling(resampling, smoothSlopes);
    }

    /**
     * The orientation with exact part {@code t} whose free rotation is nearest {@code rotation} (a proper rotation,
     * row-major), its angles rounded to 1/100 degree: {@code rotation} decomposed as {@code Yaw · Pitch · Roll} (pitch
     * within ±90°; at ±90° the roll is taken as 0).
     */
    public static Orientation nearest(Transform t, double[] rotation) {
        Objects.requireNonNull(t);
        if (rotation.length != 9) throw new IllegalArgumentException("A rotation has 9 entries");
        double sinPitch = Math.max(-1, Math.min(1, -rotation[5]));
        double pitch = Math.asin(sinPitch);
        double yaw;
        double roll;
        if (Math.abs(sinPitch) > 1 - 1e-12) {
            roll = 0;
            yaw = Math.atan2(rotation[6], rotation[0]);
        } else {
            roll = Math.atan2(rotation[3], rotation[4]);
            yaw = Math.atan2(-rotation[2], rotation[8]);
        }
        return free(t, centidegrees(yaw), centidegrees(pitch), centidegrees(roll));
    }

    private static int centidegrees(double radians) {
        return (int) Math.round(Math.toDegrees(radians) * 100);
    }

    /**
     * The result box of a source box of size {@code (sx, sy, sz)}: its size, and its minimum corner in the intermediate
     * box's coordinates ({@code offset} 0 for an exact orientation, whose result box is {@link Transform#size}). It is
     * the method's ({@link #resampling()}): the cells whose centres lie in the turned box for nearest and smooth, the
     * cells the exact-count bijection can reach for exact count ({@link ShearTurn}); {@link #mapCell}, {@link #mapPoint},
     * {@link #inverseCell} and {@link #sampler} are all local to it.
     *
     * @throws IllegalArgumentException if a size is not positive, or a free orientation's result box would be more than
     *     {@link #MAX_TURNED_SIDE} cells along an axis (callers are bounded by the world far below that)
     */
    public Bounds bounds(int sx, int sy, int sz) {
        if (resampling == Resampling.EXACT && !isExact()) return ShearTurn.bounds(this, sx, sy, sz);
        return turnedBounds(sx, sy, sz);
    }

    /** The cells whose centres lie in the turned box: the nearest and smooth result box. */
    Bounds turnedBounds(int sx, int sy, int sz) {
        BlockPos size = transform.size(sx, sy, sz);
        if (isExact()) return new Bounds(size, BlockPos.ORIGIN);
        double[] r = rotation(yaw, pitch, roll);
        int[] s = {size.x(), size.y(), size.z()};
        double[] centre = centre(s);
        double[] landing = landing(s, yaw, pitch, roll);
        int[] lo = new int[3];
        int[] count = new int[3];
        for (int i = 0; i < 3; i++) {
            double low = landing[i];
            double high = landing[i];
            for (int j = 0; j < 3; j++) {
                double a = r[3 * i + j] * (0 - centre[j]);
                double b = r[3 * i + j] * (s[j] - centre[j]);
                low += Math.min(a, b);
                high += Math.max(a, b);
            }
            // The cells whose centres lie in [low, high).
            double first = Math.ceil(low - 0.5);
            double last = Math.ceil(high - 0.5) - 1;
            if (first < -MAX_TURNED_SIDE || last - first + 1 > MAX_TURNED_SIDE) {
                throw new IllegalArgumentException("A turned box of " + sx + "x" + sy + "x" + sz + " is too large");
            }
            lo[i] = (int) first;
            count[i] = (int) Math.max(1, last - first + 1);
        }
        return new Bounds(new BlockPos(count[0], count[1], count[2]), new BlockPos(lo[0], lo[1], lo[2]));
    }

    /**
     * The source cell result cell {@code (x, y, z)} takes, both local to their boxes, or {@code null} when the result
     * cell's centre turns back to a point outside the source box (a corner cell of a free orientation's result box, or
     * a cell outside the result box). For an exact orientation this is {@code transform.inverse()} applied within the
     * result box. Allocates the returned position.
     *
     * @param sx the source box's size (as are {@code sy} and {@code sz}), not the result box's
     * @throws IllegalArgumentException if a size is not positive
     */
    public BlockPos inverseCell(int x, int y, int z, int sx, int sy, int sz) {
        int[] out = new int[3];
        return sampler(sx, sy, sz).source(x, y, z, out) ? new BlockPos(out[0], out[1], out[2]) : null;
    }

    /**
     * How this orientation's method fills the result box of a source box of size {@code (sx, sy, sz)}
     * ({@link #resampling()}: {@link CellResampler#nearest}, {@link CellResampler#exact} or {@link CellResampler#smooth}),
     * in the frame of {@link #bounds}. An exact orientation's is its {@link #sampler} (the inverse transform).
     *
     * @throws IllegalArgumentException if a size is not positive (or the result box is too large: {@link #bounds})
     */
    public CellResampler resampler(int sx, int sy, int sz) {
        if (isExact()) return sampler(sx, sy, sz);
        return switch (resampling) {
            case NEAREST -> sampler(sx, sy, sz);
            case EXACT -> CellResampler.exact(this, sx, sy, sz);
            case SMOOTH -> CellResampler.smooth(this, sx, sy, sz);
        };
    }

    /**
     * The cached inverse map of a source box of size {@code (sx, sy, sz)}: {@link #inverseCell} with the matrix, pivot
     * and result box worked out once, for programs and ghosts that map every cell of a result box.
     *
     * @throws IllegalArgumentException if a size is not positive (or the result box is too large: {@link #bounds})
     */
    public Sampler sampler(int sx, int sy, int sz) {
        return new Sampler(this, sx, sy, sz);
    }

    /**
     * {@link Orientation#inverseCell} for one source box size, precomputed: {@link #source} maps a result cell back to
     * its source cell without allocating, with exactly the arithmetic of {@code inverseCell} (so every caller, the
     * server's programs and the client's ghost, takes the same cells). It is the {@link CellResampler#nearest nearest}
     * resampler. Immutable and thread-safe.
     */
    public static final class Sampler implements CellResampler {
        private final Orientation orientation;
        private final Transform inverse;
        private final int sx, sy, sz;
        /** The intermediate box (after the exact transform). */
        private final int tx, ty, tz;
        private final BlockPos size;
        private final BlockPos offset;
        private final boolean exact;
        private final int ox, oy, oz;
        /** The free rotation, row-major; read transposed (its inverse). */
        private final double r0, r1, r2, r3, r4, r5, r6, r7, r8;
        /** The centre the rotation turns about, and where it lands (see {@link Orientation}). */
        private final double cx, cy, cz, lx, ly, lz;
        /** The exact transform's signed permutation, row-major (the forward map). */
        private final int t0, t1, t2, t3, t4, t5, t6, t7, t8;

        private Sampler(Orientation orientation, int sx, int sy, int sz) {
            this.orientation = orientation;
            Transform t = orientation.transform;
            BlockPos intermediate = t.size(sx, sy, sz);
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.tx = intermediate.x();
            this.ty = intermediate.y();
            this.tz = intermediate.z();
            this.inverse = t.inverse();
            this.exact = orientation.isExact();
            Bounds bounds = orientation.bounds(sx, sy, sz);
            this.size = bounds.size();
            this.offset = bounds.offset();
            this.ox = offset.x();
            this.oy = offset.y();
            this.oz = offset.z();
            double[] r = rotation(orientation.yaw, orientation.pitch, orientation.roll);
            r0 = r[0];
            r1 = r[1];
            r2 = r[2];
            r3 = r[3];
            r4 = r[4];
            r5 = r[5];
            r6 = r[6];
            r7 = r[7];
            r8 = r[8];
            int[] s = {tx, ty, tz};
            double[] c = centre(s);
            double[] l = landing(s, orientation.yaw, orientation.pitch, orientation.roll);
            cx = c[0];
            cy = c[1];
            cz = c[2];
            lx = l[0];
            ly = l[1];
            lz = l[2];
            int[] m = linear(t);
            t0 = m[0];
            t1 = m[1];
            t2 = m[2];
            t3 = m[3];
            t4 = m[4];
            t5 = m[5];
            t6 = m[6];
            t7 = m[7];
            t8 = m[8];
        }

        @Override
        public Orientation orientation() {
            return orientation;
        }

        /** The result box's size ({@link Bounds#size}). */
        @Override
        public BlockPos size() {
            return size;
        }

        /** The result box's minimum corner in the intermediate box's coordinates ({@link Bounds#offset}). */
        @Override
        public BlockPos offset() {
            return offset;
        }

        /** The source box's size. */
        @Override
        public BlockPos sourceSize() {
            return new BlockPos(sx, sy, sz);
        }

        /**
         * {@link Orientation#mapPoint} with this sampler's cached numbers (the same arithmetic, no allocation but
         * {@code out}): where source point (x, y, z) lands, result-local, into {@code out[0..2]}.
         */
        @Override
        public void forward(double x, double y, double z, double[] out) {
            double dx = x - sx / 2.0, dy = y - sy / 2.0, dz = z - sz / 2.0;
            double qx = t0 * dx + t1 * dy + t2 * dz + tx / 2.0;
            double qy = t3 * dx + t4 * dy + t5 * dz + ty / 2.0;
            double qz = t6 * dx + t7 * dy + t8 * dz + tz / 2.0;
            if (exact) {
                out[0] = qx;
                out[1] = qy;
                out[2] = qz;
                return;
            }
            double ex = qx - cx, ey = qy - cy, ez = qz - cz;
            out[0] = r0 * ex + r1 * ey + r2 * ez + lx - ox;
            out[1] = r3 * ex + r4 * ey + r5 * ez + ly - oy;
            out[2] = r6 * ex + r7 * ey + r8 * ez + lz - oz;
        }

        /**
         * The inverse of {@link #forward}: the source point (source-local, continuous) that result-local point
         * (x, y, z) turns back to, into {@code out[0..2]}. No allocation.
         */
        @Override
        public void back(double x, double y, double z, double[] out) {
            double qx, qy, qz;
            if (exact) {
                qx = x;
                qy = y;
                qz = z;
            } else {
                double dx = ox + x - lx, dy = oy + y - ly, dz = oz + z - lz;
                qx = r0 * dx + r3 * dy + r6 * dz + cx;
                qy = r1 * dx + r4 * dy + r7 * dz + cy;
                qz = r2 * dx + r5 * dy + r8 * dz + cz;
            }
            // The exact part's inverse: its signed permutation transposed, about the boxes' centres.
            double ex = qx - tx / 2.0, ey = qy - ty / 2.0, ez = qz - tz / 2.0;
            out[0] = t0 * ex + t3 * ey + t6 * ez + sx / 2.0;
            out[1] = t1 * ex + t4 * ey + t7 * ez + sy / 2.0;
            out[2] = t2 * ex + t5 * ey + t8 * ez + sz / 2.0;
        }

        /** At most 27 result cells take one source cell ({@link #takers(int, int, int, int[], double[], int[])}). */
        @Override
        public int maxTakers() {
            return 27;
        }

        /** {@link #takers(int, int, int, int[], double[], int[])}; the blocks play no part. */
        @Override
        public int takers(int x, int y, int z, SourceCells cells, int[] out) {
            return takers(x, y, z, out, new double[3], new int[3]);
        }

        /**
         * The result cells that take source cell (x, y, z) ({@link #source} gives it back for them), into
         * {@code out} as triples ({@code out} holds at least 81 ints); returns how many. Only the 27 cells around where
         * the cell's centre lands can (a result cell takes the source cell its centre turns back into, so its centre lies
         * within the turned unit cube, under a cell from the landing point on each axis), so the work per source cell is
         * fixed whatever the angle. {@code point} and {@code cell} are scratch arrays of 3.
         */
        public int takers(int x, int y, int z, int[] out, double[] point, int[] cell) {
            forward(x + 0.5, y + 0.5, z + 0.5, point);
            int bx = (int) Math.floor(point[0]), by = (int) Math.floor(point[1]), bz = (int) Math.floor(point[2]);
            int n = 0;
            for (int ry = by - 1; ry <= by + 1; ry++) {
                if (ry < 0 || ry >= size.y()) continue;
                for (int rz = bz - 1; rz <= bz + 1; rz++) {
                    if (rz < 0 || rz >= size.z()) continue;
                    for (int rx = bx - 1; rx <= bx + 1; rx++) {
                        if (rx < 0 || rx >= size.x()) continue;
                        if (!source(rx, ry, rz, cell) || cell[0] != x || cell[1] != y || cell[2] != z) continue;
                        out[3 * n] = rx;
                        out[3 * n + 1] = ry;
                        out[3 * n + 2] = rz;
                        n++;
                    }
                }
            }
            return n;
        }

        /**
         * The result cells that can take a source cell of the source-local box {@code [x0, x1] × [y0, y1] × [z0, z1]}
         * (inclusive): the turned box of those cells one cell wider on every side (a result cell takes the source cell
         * its centre turns back into, which lies within a cell of where that cell turns to), cut to the result box, as
         * {@code {x0, y0, z0, x1, y1, z1}} inclusive; {@code null} when nothing is left. Lets a caller visit only the
         * result cells near the source cells it has, so the work follows the cells, not the turned box.
         */
        @Override
        public int[] turnedBox(int x0, int y0, int z0, int x1, int y1, int z1) {
            return turnedBox(x0, y0, z0, x1, y1, z1, 1);
        }

        /** {@link #turnedBox(int, int, int, int, int, int)} grown by {@code margin} cells instead of one. */
        int[] turnedBox(int x0, int y0, int z0, int x1, int y1, int z1, int margin) {
            double[] lo = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] hi = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            for (int corner = 0; corner < 8; corner++) {
                double[] p = new double[3];
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
                out[i] = (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.floor(lo[i]) - margin));
                out[3 + i] = (int) Math.min(max[i], Math.max(Integer.MIN_VALUE, Math.ceil(hi[i]) + margin - 1));
                if (out[i] > out[3 + i]) return null;
            }
            return out;
        }

        /** {@link #source(int, int, int, int[])}; the blocks play no part. */
        @Override
        public boolean source(int x, int y, int z, SourceCells cells, int[] out) {
            return source(x, y, z, out);
        }

        /**
         * The source cell result cell {@code (x, y, z)} takes, into {@code out} ({@code out[0..2]}), as
         * {@link Orientation#inverseCell}; false (and {@code out} unspecified) when it takes none.
         */
        public boolean source(int x, int y, int z, int[] out) {
            return sourceAt(x, y, z, 0, 0, 0, out);
        }

        /**
         * The source cell holding the point {@code (ex, ey, ez)} away from result cell (x, y, z)'s centre, turned back
         * (result-box axes, in blocks): {@link #source(int, int, int, int[])} for a point other than the centre, with the
         * same arithmetic (at offset 0 it is {@code source}). False when the point turns back outside the source box.
         */
        boolean sourceAt(int x, int y, int z, double ex, double ey, double ez, int[] out) {
            int ix, iy, iz;
            if (exact) {
                ix = (int) Math.floor(x + 0.5 + ex);
                iy = (int) Math.floor(y + 0.5 + ey);
                iz = (int) Math.floor(z + 0.5 + ez);
            } else {
                double dx = ox + x + 0.5 - lx + ex;
                double dy = oy + y + 0.5 - ly + ey;
                double dz = oz + z + 0.5 - lz + ez;
                // The inverse of a rotation is its transpose.
                ix = (int) Math.floor(r0 * dx + r3 * dy + r6 * dz + cx);
                iy = (int) Math.floor(r1 * dx + r4 * dy + r7 * dz + cy);
                iz = (int) Math.floor(r2 * dx + r5 * dy + r8 * dz + cz);
            }
            if (ix < 0 || ix >= tx || iy < 0 || iy >= ty || iz < 0 || iz >= tz) return false;
            out[0] = inverse.mapX(ix, iz, tx, tz);
            out[1] = inverse.mapY(iy, ty);
            out[2] = inverse.mapZ(ix, iz, tx, tz);
            return true;
        }
    }

    /**
     * Where source cell {@code (x, y, z)} lands, local to the result box: the result cell holding the turned centre of
     * the source cell. The cell may lie anywhere (an anchor outside the source box maps too), and for a free orientation
     * a cell at the edge of the source box may land just outside the result box. For an exact orientation this is
     * {@link Transform#apply} extended to every cell.
     *
     * @throws IllegalArgumentException if a size is not positive
     * @throws ArithmeticException if the result leaves the int range
     */
    public BlockPos mapCell(int x, int y, int z, int sx, int sy, int sz) {
        if (isExact()) {
            BlockPos size = transform.size(sx, sy, sz);
            // The exact map is affine: sampled at three cells, extended in long arithmetic.
            int ox = transform.mapX(0, 0, sx, sz), oz = transform.mapZ(0, 0, sx, sz);
            long mx = ox + (long) (transform.mapX(1, 0, sx, sz) - ox) * x + (long) (transform.mapX(0, 1, sx, sz) - ox) * z;
            long mz = oz + (long) (transform.mapZ(1, 0, sx, sz) - oz) * x + (long) (transform.mapZ(0, 1, sx, sz) - oz) * z;
            long my = transform.mapY((long) y, size.y());
            return new BlockPos(Math.toIntExact(mx), Math.toIntExact(my), Math.toIntExact(mz));
        }
        double[] p = mapPoint(x + 0.5, y + 0.5, z + 0.5, sx, sy, sz);
        return new BlockPos(floorExact(p[0]), floorExact(p[1]), floorExact(p[2]));
    }

    /**
     * Where the point {@code (x, y, z)} in the source box's continuous coordinates (the box spans {@code [0, sx]} and so
     * on) lands, in the result box's continuous coordinates: the exact transform maps the source box onto the
     * intermediate box about their centres, then the free rotation turns it about the pivot. For entities and for
     * drawing; a fresh array {@code {x, y, z}}.
     *
     * @throws IllegalArgumentException if a size is not positive
     */
    public double[] mapPoint(double x, double y, double z, int sx, int sy, int sz) {
        BlockPos size = transform.size(sx, sy, sz);
        int[] t = linear(transform);
        double cx = x - sx / 2.0, cy = y - sy / 2.0, cz = z - sz / 2.0;
        double qx = t[0] * cx + t[1] * cy + t[2] * cz + size.x() / 2.0;
        double qy = t[3] * cx + t[4] * cy + t[5] * cz + size.y() / 2.0;
        double qz = t[6] * cx + t[7] * cy + t[8] * cz + size.z() / 2.0;
        if (isExact()) return new double[] {qx, qy, qz};
        double[] r = rotation(yaw, pitch, roll);
        int[] s = {size.x(), size.y(), size.z()};
        double[] centre = centre(s);
        double[] landing = landing(s, yaw, pitch, roll);
        BlockPos offset = bounds(sx, sy, sz).offset();
        double dx = qx - centre[0], dy = qy - centre[1], dz = qz - centre[2];
        return new double[] {
                r[0] * dx + r[1] * dy + r[2] * dz + landing[0] - offset.x(),
                r[3] * dx + r[4] * dy + r[5] * dz + landing[1] - offset.y(),
                r[6] * dx + r[7] * dy + r[8] * dz + landing[2] - offset.z()};
    }

    /**
     * A result box: its size, and its minimum corner in the intermediate box's coordinates (the box after the exact
     * transform; see {@link Orientation}).
     */
    public record Bounds(BlockPos size, BlockPos offset) {
        public Bounds {
            Objects.requireNonNull(size);
            Objects.requireNonNull(offset);
        }
    }

    // ---------------------------------------------------------------- the math

    /** The free rotation {@code Yaw · Pitch · Roll}, row-major. */
    static double[] rotation(int yaw, int pitch, int roll) {
        double cy = cos(yaw), sy = sin(yaw);
        double cp = cos(pitch), sp = sin(pitch);
        double cr = cos(roll), sr = sin(roll);
        double[] yawM = {cy, 0, -sy, 0, 1, 0, sy, 0, cy};
        double[] pitchM = {1, 0, 0, 0, cp, -sp, 0, sp, cp};
        double[] rollM = {cr, -sr, 0, sr, cr, 0, 0, 0, 1};
        return times(yawM, times(pitchM, rollM));
    }

    /** The exact transform's linear part in world axes (a signed permutation), row-major. */
    static int[] linear(Transform t) {
        int ox = t.mapX(0, 0, 3, 3), oz = t.mapZ(0, 0, 3, 3);
        return new int[] {
                t.mapX(1, 0, 3, 3) - ox, 0, t.mapX(0, 1, 3, 3) - ox,
                0, t.upsideDown() ? -1 : 1, 0,
                t.mapZ(1, 0, 3, 3) - oz, 0, t.mapZ(0, 1, 3, 3) - oz};
    }

    /** The transform whose linear part is {@code r} (an exact signed permutation keeping Y vertical), else null. */
    private static Transform foldable(double[] r) {
        if (Math.abs(r[4]) != 1) return null;
        for (Transform t : Transform.allWithFlips()) {
            int[] l = linear(t);
            boolean same = true;
            for (int i = 0; i < 9 && same; i++) same = l[i] == r[i];
            if (same) return t;
        }
        return null;
    }

    /** The centre a free rotation turns a box of size {@code s} about: its true centre. */
    static double[] centre(int[] s) {
        return new double[] {s[0] / 2.0, s[1] / 2.0, s[2] / 2.0};
    }

    /**
     * Where the {@link #centre} of a box of size {@code s} lands under the free rotation (yaw, pitch, roll): the centre,
     * minus half a block on each result axis that the nearest quarter turns ({@link #quarterTurns}) bring an axis of the
     * other parity onto (whose cell centres would otherwise land on cell corners).
     */
    static double[] landing(int[] s, int yaw, int pitch, int roll) {
        int[] q = quarterTurns(yaw, pitch, roll);
        double[] out = centre(s);
        for (int i = 0; i < 3; i++) {
            int from = q[3 * i] != 0 ? 0 : q[3 * i + 1] != 0 ? 1 : 2;
            if ((s[i] & 1) != (s[from] & 1)) out[i] -= 0.5;
        }
        return out;
    }

    /**
     * The nearest quarter turns of a free rotation: {@code Yaw · Pitch · Roll} with each angle rounded to a multiple of
     * 90° (halfway rounds down: 45° to 0°, 135° to 90°), as a signed permutation, row-major. A free rotation that is
     * a quarter turn on every axis is its own.
     */
    static int[] quarterTurns(int yaw, int pitch, int roll) {
        double[] r = rotation(quarters(yaw) * QUARTER_TURN, quarters(pitch) * QUARTER_TURN,
                quarters(roll) * QUARTER_TURN);
        int[] q = new int[9];
        for (int i = 0; i < 9; i++) q[i] = (int) r[i];
        return q;
    }

    /** The multiple of 90° nearest {@code angle} (normalised), in quarter turns 0..3; halfway rounds down. */
    static int quarters(int angle) {
        return roundedQuarters(angle) % 4;
    }

    /**
     * {@link #quarters} before it wraps: 0..4 for an angle normalised into 0..35999, so {@code angle - 9000 * it} is the
     * rest, within ±45°.
     */
    static int roundedQuarters(int angle) {
        return (Math.floorMod(angle, FULL_TURN) + QUARTER_TURN / 2 - 1) / QUARTER_TURN;
    }

    private static double[] times(double[] a, double[] b) {
        double[] m = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                m[3 * row + col] = a[3 * row] * b[col] + a[3 * row + 1] * b[3 + col] + a[3 * row + 2] * b[6 + col];
            }
        }
        return m;
    }

    /**
     * The sine of an angle in 1/100 degree (normalised), exact at multiples of 90°, and symmetric to the last bit:
     * {@code sin(-a) = -sin(a)}, {@code sin(a + 90°) = cos(a)} and {@code cos(90° - a) = sin(a)} hold exactly, because
     * every angle is reduced to its first octant before {@link StrictMath} is asked (and no zero is negative). So a rotation turned by a quarter
     * turn, or mirrored, has exactly the permuted and negated entries, and symmetric copies resample alike.
     */
    static double sin(int angle) {
        int a = Math.floorMod(angle, FULL_TURN);
        int r = a % QUARTER_TURN;
        return switch (a / QUARTER_TURN) {
            case 0 -> firstQuadrantSin(r);
            case 1 -> firstQuadrantSin(QUARTER_TURN - r);
            case 2 -> 0.0 - firstQuadrantSin(r);
            default -> 0.0 - firstQuadrantSin(QUARTER_TURN - r);
        };
    }

    /** The cosine: {@code sin(a + 90°)}, exactly. */
    static double cos(int angle) {
        return sin(Math.floorMod(angle, FULL_TURN) + QUARTER_TURN);
    }

    /** The sine of {@code r} in {@code 0..9000}: {@code sin(r)} up to 45°, {@code cos(90° - r)} above. */
    private static double firstQuadrantSin(int r) {
        if (r == 0) return 0;
        if (r == QUARTER_TURN) return 1;
        if (r == QUARTER_TURN / 2) return HALF_SQRT2;
        return r < QUARTER_TURN / 2 ? StrictMath.sin(r * RADIANS) : StrictMath.cos((QUARTER_TURN - r) * RADIANS);
    }

    private static final double RADIANS = Math.PI / 18_000.0;
    /** {@code Math.sqrt(0.5)}, as a literal so it is set before any static initialiser could need it. */
    private static final double HALF_SQRT2 = 0.7071067811865476;

    private static int floorExact(double v) {
        double f = Math.floor(v);
        if (f < Integer.MIN_VALUE || f > Integer.MAX_VALUE) throw new ArithmeticException("Cell outside the int range");
        return (int) f;
    }
}
