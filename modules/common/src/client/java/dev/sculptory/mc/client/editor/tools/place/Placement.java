package dev.sculptory.mc.client.editor.tools.place;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Resampling;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where a Place tool preview sits and what committing it sends. Pure and mutable; client thread only.
 *
 * <p>Every source is a box of {@link #dims()} cells in its own local coordinates. The <b>pivot</b> is the local cell
 * at the bottom centre of that box: the ghost hangs from it, it follows the cursor, and quarter turns turn around it.
 * {@link #pivotWorld()} is the world cell the pivot lands on. The placement's {@link #orientation()} maps the box onto
 * its result box ({@link Orientation#bounds}), which starts at {@link #targetMin()}: the pivot cell, mapped by
 * {@link Orientation#mapCell} (as the server's paste maps its anchor), lands on the pivot's column, and the result
 * box's bottom on the pivot's height (a Rotate…, {@link #rotate}, keeps its pivot cell on the pivot itself, height
 * included). For an exact orientation (a {@link Transform}) that is pivotWorld − T(pivot), T
 * extended affinely to cells outside the box, exactly as before free turns.
 *
 * <p>Modes:
 * <ul>
 *   <li>{@link Mode#PASTE}: a clipboard or library asset. The server places the source's own {@link #anchor()} on the
 *       origin it is sent: origin = targetMin + mapCell(anchor), so the paste lands where the ghost shows whatever the
 *       anchor is.</li>
 *   <li>{@link Mode#MOVE}: the selection moves (with the orientation); offset = targetMin − box.min − the result box's
 *       offset ({@link Orientation.Bounds#offset}, 0 for an exact orientation), where box is the selection's bounds.
 *       A shape or cell set moves only its own cells ({@link #region()}).</li>
 *   <li>{@link Mode#STACK}: the selection repeats {@link #count()} times, each copy offset by
 *       targetMin − box.min from the previous one. Stacked copies cannot be rotated or mirrored, only flipped upside
 *       down.</li>
 * </ul>
 *
 * <p><b>Turning.</b> Quarter turns ({@link #rotate}), mirrors ({@link #mirror}) and the flip upside down
 * ({@link #flipUpsideDown}) act about the world's axes and stay exact, whatever free angles the placement has. The free
 * angles are shown and typed as yaw, pitch and roll ({@link #angles}, {@link #setAngles}): the orientation is then
 * {@code free(reflection, yaw, pitch, roll)}, its reflection a mirror across x and the flip, or neither
 * ({@link #reflection}), so a turn of the exact transform shows in the yaw. The gizmo's rings turn about the world's
 * axes ({@link #turnedAbout}).
 */
public final class Placement {
    /** Stack copies: at least 1, at most the server's 256. */
    public static final int MAX_STACK = 256;

    public enum Mode {
        PASTE,
        MOVE,
        STACK
    }

    private final Mode mode;
    private final SourceRef source;
    private final Region region;
    private final Box box;
    private final BlockPos dims;
    private final BlockPos anchor;
    private final BlockPos pivot;
    /** Whether the result box keeps the pivot cell where it is in height too (Rotate…), not the box's bottom. */
    private final boolean pivotTurns;
    private Orientation orientation = Orientation.IDENTITY;
    /** How a free turn lands on the grid (kept while the turn is exact, which has none). */
    private Resampling method = Resampling.NEAREST;
    private boolean smoothSlopes;
    private BlockPos pivotWorld;
    private int count = 1;

    private Placement(Mode mode, SourceRef source, Region region, BlockPos dims, BlockPos anchor, BlockPos pivotWorld) {
        this(mode, source, region, dims, anchor, pivotWorld, bottomCentre(dims), false);
    }

    private Placement(Mode mode, SourceRef source, Region region, BlockPos dims, BlockPos anchor, BlockPos pivotWorld,
                      BlockPos pivot, boolean pivotTurns) {
        this.mode = mode;
        this.source = source;
        this.region = region;
        this.box = region == null ? null : region.bounds();
        this.dims = dims;
        this.anchor = anchor;
        this.pivot = Objects.requireNonNull(pivot);
        this.pivotTurns = pivotTurns;
        this.pivotWorld = Objects.requireNonNull(pivotWorld);
    }

    /**
     * Pasting {@code source}, a box of {@code dims} whose anchor is {@code anchor}, with the pivot on
     * {@code pivotWorld}.
     */
    public static Placement paste(SourceRef source, BlockPos dims, BlockPos anchor, BlockPos pivotWorld) {
        Objects.requireNonNull(source);
        checkDims(dims);
        Objects.requireNonNull(anchor);
        return new Placement(Mode.PASTE, source, null, dims, anchor, pivotWorld);
    }

    /** Moving {@code box}, starting where it is (offset 0). */
    public static Placement move(Box box) {
        return move(new Region.Cuboid(box));
    }

    /** Moving {@code region} (its own cells, in its bounds), starting where it is (offset 0). */
    public static Placement move(Region region) {
        Objects.requireNonNull(region);
        Box box = region.bounds();
        BlockPos dims = sizeOf(box);
        return new Placement(Mode.MOVE, null, region, dims, BlockPos.ORIGIN, box.min().add(bottomCentre(dims)));
    }

    /**
     * Turning {@code region} where it stands (Rotate…): a move whose pivot is world cell {@code pivotWorld} (the
     * symmetry centre's column at the selection's middle height, or the selection's middle), which every turn keeps in
     * place, height included, so a pitch or roll tilts the blocks about it.
     */
    public static Placement rotate(Region region, BlockPos pivotWorld) {
        Objects.requireNonNull(region);
        Box box = region.bounds();
        BlockPos local = pivotWorld.offset(-box.min().x(), -box.min().y(), -box.min().z());
        return new Placement(Mode.MOVE, null, region, sizeOf(box), BlockPos.ORIGIN, pivotWorld, local, true);
    }

    /** Whether turns keep the pivot cell in place in height too (Rotate…), not the result box's bottom. */
    public boolean pivotTurns() {
        return pivotTurns;
    }

    /** Stacking {@code box}, starting with one copy right next to it, one box length along {@code direction}. */
    public static Placement stack(Box box, int[] direction) {
        return stack(new Region.Cuboid(box), direction);
    }

    /** Stacking {@code region}, starting with one copy right next to it, one bounds length along {@code direction}. */
    public static Placement stack(Region region, int[] direction) {
        Objects.requireNonNull(region);
        Box box = region.bounds();
        BlockPos dims = sizeOf(box);
        int dx = direction[0] * dims.x();
        int dy = direction[1] * dims.y();
        int dz = direction[2] * dims.z();
        if (dx == 0 && dy == 0 && dz == 0) dx = dims.x();
        return new Placement(Mode.STACK, null, region, dims, BlockPos.ORIGIN,
                box.min().offset(dx, dy, dz).add(bottomCentre(dims)));
    }

    // ---- State ----

    public Mode mode() {
        return mode;
    }

    /** The paste source (PASTE only), else null. */
    public SourceRef source() {
        return source;
    }

    /** The selection's bounds (MOVE and STACK only), else null. */
    public Box box() {
        return box;
    }

    /** The selection: a box, a shape or a cell set (MOVE and STACK only), else null. */
    public Region region() {
        return region;
    }

    /** The source box size, untransformed. */
    public BlockPos dims() {
        return dims;
    }

    /** The source's own anchor (PASTE only; zero otherwise). */
    public BlockPos anchor() {
        return anchor;
    }

    /** The local pivot: the bottom centre of the source box (for Rotate…, the cell it turns about). */
    public BlockPos pivot() {
        return pivot;
    }

    /** How the placement turns the source. */
    public Orientation orientation() {
        return orientation;
    }

    /** The exact part of {@link #orientation()}: all of it unless the placement has free angles. */
    public Transform transform() {
        return orientation.transform();
    }

    /** Where the pivot lands in the world. */
    public BlockPos pivotWorld() {
        return pivotWorld;
    }

    /** Stack copies (1 outside STACK). */
    public int count() {
        return count;
    }

    // ---- Changes ----

    public void moveTo(BlockPos world) {
        pivotWorld = Objects.requireNonNull(world);
    }

    public void nudge(int dx, int dy, int dz) {
        pivotWorld = pivotWorld.offset(dx, dy, dz);
    }

    /**
     * Turns the placement clockwise (seen from above) by quarter turns about the world's vertical: exact, a free
     * placement's yaw changes by 90° a turn. Returns false (and changes nothing) for a stack, whose copies cannot turn.
     */
    public boolean rotate(int quarterTurnsCw) {
        return apply(Transform.rotation(quarterTurnsCw));
    }

    /** Mirrors the placement across a world axis (X: east-west, Z: north-south). False for a stack. */
    public boolean mirror(Mirror mirror) {
        return apply(new Transform(0, mirror));
    }

    /** Turns the placement upside down (or back): a stack's copies too. */
    public boolean flipUpsideDown() {
        orientation = resampled(orientation.then(Transform.UPSIDE_DOWN));
        return true;
    }

    /**
     * Sets the whole transform (e.g. carried over from the Clipboard window), dropping any free angles. False (and
     * nothing changes) for a stack with a turn or mirror in it; a stack takes the flip upside down alone.
     */
    public boolean setTransform(Transform next) {
        Objects.requireNonNull(next);
        return setOrientation(Orientation.of(next));
    }

    /** Sets the whole orientation. False (and nothing changes) for a stack, unless it is the flip alone or nothing. */
    public boolean setOrientation(Orientation next) {
        Objects.requireNonNull(next);
        if (mode == Mode.STACK && (!next.isExact() || !next.transform().horizontal().isIdentity())) return false;
        orientation = resampled(next);
        return true;
    }

    /**
     * Sets how a free turn lands on the grid (the paste and move ghost's Method and Smooth slopes settings): kept for
     * every later turn; an exact turn has nothing to resample.
     */
    public void setResampling(Resampling method, boolean smoothSlopes) {
        this.method = Objects.requireNonNull(method);
        this.smoothSlopes = smoothSlopes;
        orientation = resampled(orientation);
    }

    /** The method free turns use. */
    public Resampling resampling() {
        return method;
    }

    /** Whether free turns get stairs and slabs on their slopes. */
    public boolean smoothSlopes() {
        return smoothSlopes;
    }

    private Orientation resampled(Orientation o) {
        return o.withResampling(method, smoothSlopes);
    }

    private boolean apply(Transform step) {
        if (mode == Mode.STACK) return false;
        orientation = resampled(orientation.then(step));
        return true;
    }

    /**
     * The mirror and flip the orientation keeps outside its angles: a mirror across x (a mirror across z is that and a
     * half turn) and the flip upside down, or neither. {@link #angles} are the turn that follows it.
     */
    public Transform reflection() {
        return reflection(orientation);
    }

    /**
     * The placement's turn as yaw, pitch and roll in 1/100 degree (each normalised into 0..35999), applied after
     * {@link #reflection}: the free angles with the exact transform's quarter turns (and the half turn a mirror across
     * z stands for) folded into them, so a placement turned with R shows a yaw of 90°.
     */
    public int[] angles() {
        return angles(orientation);
    }

    /**
     * Sets the turn to yaw, pitch and roll in 1/100 degree after {@link #reflection} (typed in Tool Settings); turns that
     * keep up vertical fold into the exact transform. False (and nothing changes) for a stack.
     */
    public boolean setAngles(int yaw, int pitch, int roll) {
        if (mode == Mode.STACK) return false;
        orientation = resampled(Orientation.free(reflection(), yaw, pitch, roll));
        return true;
    }

    /** {@link #reflection()} of an orientation. */
    public static Transform reflection(Orientation orientation) {
        Transform t = orientation.transform();
        return new Transform(0, t.mirror() == Mirror.NONE ? Mirror.NONE : Mirror.X, t.upsideDown());
    }

    /**
     * {@link #angles()} of an orientation: exact whenever the orientation's transform has no odd quarter turn left over
     * (every orientation the keys and rings make), else the nearest angles.
     */
    public static int[] angles(Orientation orientation) {
        Transform t = orientation.transform();
        int turns = t.quarterTurnsCw() + (t.mirror() == Mirror.Z ? 2 : 0);
        if (orientation.isExact()) {
            return new int[] {Math.floorMod(turns * Orientation.QUARTER_TURN, Orientation.FULL_TURN), 0, 0};
        }
        if (turns % 4 == 0) return new int[] {orientation.yaw(), orientation.pitch(), orientation.roll()};
        double[] turned = times(orientation.rotationMatrix(), yaw(turns * Orientation.QUARTER_TURN));
        Orientation nearest = Orientation.nearest(reflection(orientation), turned);
        return new int[] {nearest.yaw(), nearest.pitch(), nearest.roll()};
    }

    /**
     * {@code start}, then turned by {@code angle} (1/100 degree) about a world axis through the placement (0 east-west,
     * 1 up, 2 north-south; positive as the orientation's pitch, yaw and roll): what a gizmo ring drag shows. A turn
     * about the vertical adds to the yaw exactly; one about a horizontal axis is decomposed again into the nearest
     * angles (exact for multiples of 90°).
     */
    public static Orientation turnedAbout(Orientation start, int axis, int angle) {
        int[] a = angles(start);
        Transform reflection = reflection(start);
        if (axis == 1) {
            return Orientation.free(reflection, a[0] + angle, a[1], a[2], start.resampling(), start.smoothSlopes());
        }
        double[] step = axis == 0 ? Orientation.rotationMatrix(0, angle, 0) : Orientation.rotationMatrix(0, 0, angle);
        double[] r = Orientation.rotationMatrix(a[0], a[1], a[2]);
        return Orientation.nearest(reflection, times(step, r)).withResampling(start.resampling(), start.smoothSlopes());
    }

    private static double[] yaw(int angle) {
        return Orientation.rotationMatrix(angle, 0, 0);
    }

    /** Sets the stack count, clamped to 1..{@value #MAX_STACK}; returns the new count. */
    public int setCount(int copies) {
        count = mode == Mode.STACK ? Math.max(1, Math.min(MAX_STACK, copies)) : 1;
        return count;
    }

    // ---- Geometry ----

    /** The turned source size: the result box's ({@link Orientation#bounds}). */
    public BlockPos size() {
        return orientation.bounds(dims.x(), dims.y(), dims.z()).size();
    }

    /** The world cell of the result box's minimum corner (the flip upside down never moves it). */
    public BlockPos targetMin() {
        long[] mapped = map(orientation, dims, pivot);
        int y = pivotTurns ? clampInt(pivotWorld.y() - mapped[1]) : pivotWorld.y() - pivot.y();
        return new BlockPos(clampInt(pivotWorld.x() - mapped[0]), y, clampInt(pivotWorld.z() - mapped[2]));
    }

    /** The box the (first) copy covers. */
    public Box targetBox() {
        BlockPos min = targetMin();
        BlockPos size = size();
        return new Box(min, min.offset(size.x() - 1, size.y() - 1, size.z() - 1));
    }

    /** Where the source's anchor lands (the origin a paste sends). */
    public BlockPos pasteOrigin() {
        BlockPos min = targetMin();
        long[] mapped = map(orientation, dims, anchor);
        return new BlockPos(clampInt(min.x() + mapped[0]), clampInt(min.y() + mapped[1]), clampInt(min.z() + mapped[2]));
    }

    /**
     * targetMin − box.min (MOVE and STACK), less the result box's offset for a free orientation: the offset a move
     * sends.
     */
    public BlockPos offset() {
        if (box == null) return BlockPos.ORIGIN;
        BlockPos min = targetMin();
        BlockPos at = orientation.isExact() ? BlockPos.ORIGIN
                : orientation.bounds(dims.x(), dims.y(), dims.z()).offset();
        return new BlockPos(min.x() - box.min().x() - at.x(), min.y() - box.min().y() - at.y(),
                min.z() - box.min().z() - at.z());
    }

    /** Whether committing would change nothing (a move or stack that has not moved). */
    public boolean unmoved() {
        if (mode == Mode.PASTE) return false;
        return offset().equals(BlockPos.ORIGIN) && (mode == Mode.STACK || orientation.isIdentity());
    }

    /** The boxes of the stack copies, first to last, at most {@code max} of them (the target box otherwise). */
    public List<Box> copies(int max) {
        List<Box> copies = new ArrayList<>();
        if (mode != Mode.STACK) {
            copies.add(targetBox());
            return copies;
        }
        BlockPos step = offset();
        for (int k = 1; k <= count && copies.size() < max; k++) {
            copies.add(box.offset(step.x() * k, step.y() * k, step.z() * k));
        }
        return copies;
    }

    /** The blocks the commit covers, as the HUD counts them. */
    public long blocks(long sourceCells) {
        return mode == Mode.STACK ? sourceCells * count : sourceCells;
    }

    /** {@link #op(PasteOptions, int, EntityFilter)} of a move or stack that takes no entities along. */
    public OpSpec op(PasteOptions options, int air) {
        return op(options, air, EntityFilter.NONE);
    }

    /**
     * The op committing this placement sends. A move or stack carries the selection's region, so only its cells move
     * or repeat, takes {@code entities} along (a paste places the clipboard's entities when
     * {@code options.entities()}), writes the landing cells {@code options.into()} allows and carries the biomes along
     * with {@code options.biomes()} (a paste carries the options whole).
     *
     * @param air the air handle a move leaves behind
     * @param entities the entities a move or stack takes along
     * @throws IllegalStateException for a move or stack that has not moved ({@link #unmoved()})
     */
    public OpSpec op(PasteOptions options, int air, EntityFilter entities) {
        Objects.requireNonNull(options);
        Objects.requireNonNull(entities);
        if (unmoved()) throw new IllegalStateException("The placement has not moved");
        return switch (mode) {
            case PASTE -> new OpSpec.Paste(source, pasteOrigin(), orientation, options);
            case MOVE -> new OpSpec.Move(region, offset(), orientation, new Pattern.Single(air), entities, Symmetry.NONE,
                    options.into(), options.biomes());
            case STACK -> {
                BlockPos step = offset();
                yield new OpSpec.Stack(region, step.x(), step.y(), step.z(), count, entities, Symmetry.NONE, options.into(),
                        transform().upsideDown(), options.biomes());
            }
        };
    }

    // ---- Helpers ----

    /** The local bottom-centre cell of a box of {@code dims}. */
    public static BlockPos bottomCentre(BlockPos dims) {
        return new BlockPos(dims.x() / 2, 0, dims.z() / 2);
    }

    /**
     * The transformed local (x, z) of local cell (x, z) of a {@code dims} box, extended affinely beyond the box, in
     * long arithmetic (as the server's paste computes its anchor).
     */
    static long[] map(Transform t, BlockPos dims, int x, int z) {
        int sx = dims.x();
        int sz = dims.z();
        int originX = t.mapX(0, 0, sx, sz);
        int originZ = t.mapZ(0, 0, sx, sz);
        long mappedX = originX + (long) (t.mapX(1, 0, sx, sz) - originX) * x + (long) (t.mapX(0, 1, sx, sz) - originX) * z;
        long mappedZ = originZ + (long) (t.mapZ(1, 0, sx, sz) - originZ) * x + (long) (t.mapZ(0, 1, sx, sz) - originZ) * z;
        return new long[] {mappedX, mappedZ};
    }

    /**
     * Where local cell {@code cell} lands in the result box, {x, y, z}: {@link #map(Transform, BlockPos, int, int)} and
     * the flip for an exact orientation, {@link Orientation#mapCell} (clamped far outside the int range) for a free one.
     */
    static long[] map(Orientation orientation, BlockPos dims, BlockPos cell) {
        Transform t = orientation.transform();
        if (orientation.isExact()) {
            long[] xz = map(t, dims, cell.x(), cell.z());
            return new long[] {xz[0], t.mapY((long) cell.y(), dims.y()), xz[1]};
        }
        try {
            BlockPos to = orientation.mapCell(cell.x(), cell.y(), cell.z(), dims.x(), dims.y(), dims.z());
            return new long[] {to.x(), to.y(), to.z()};
        } catch (ArithmeticException beyond) {
            double[] p = orientation.mapPoint(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5, dims.x(), dims.y(), dims.z());
            return new long[] {(long) Math.floor(p[0]), (long) Math.floor(p[1]), (long) Math.floor(p[2])};
        }
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

    private static BlockPos sizeOf(Box box) {
        return new BlockPos(box.sizeX(), box.sizeY(), box.sizeZ());
    }

    private static void checkDims(BlockPos dims) {
        Objects.requireNonNull(dims);
        if (dims.x() < 1 || dims.y() < 1 || dims.z() < 1) throw new IllegalArgumentException("Empty source: " + dims);
    }

    private static int clampInt(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }
}
