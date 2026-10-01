package dev.sculptory.fabric.client.editor.tools.place;

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
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where a Place tool preview sits and what committing it sends. Pure and mutable; client thread only.
 *
 * <p>Every source is a box of {@link #dims()} cells in its own local coordinates. The <b>pivot</b> is the local cell
 * at the bottom centre of that box: the ghost hangs from it, it follows the cursor, and rotations turn around it.
 * {@link #pivotWorld()} is the world cell the pivot lands on. The transformed box then starts at
 * {@link #targetMin()} = pivotWorld − T(pivot), where T maps local cells exactly as the server's paste does
 * ({@link Transform#mapX}, extended affinely to cells outside the box).
 *
 * <p>Modes:
 * <ul>
 *   <li>{@link Mode#PASTE}: a clipboard or library asset. The server places the source's own {@link #anchor()} on the
 *       origin it is sent: origin = targetMin + T(anchor), so the paste lands where the ghost shows whatever the
 *       anchor is.</li>
 *   <li>{@link Mode#MOVE}: the selection moves (with the transform); offset = targetMin − box.min, where box is the
 *       selection's bounds. A shape or cell set moves only its own cells ({@link #region()}).</li>
 *   <li>{@link Mode#STACK}: the selection repeats {@link #count()} times, each copy offset by
 *       targetMin − box.min from the previous one. Stacked copies cannot be rotated or mirrored, only flipped upside
 *       down.</li>
 * </ul>
 *
 * <p>The flip upside down ({@link #flipUpsideDown}) turns the source over
 * within its own height: the transformed box stays where it is (its bottom on the pivot's height), and only what is in
 * it turns over.
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
    private Transform transform = Transform.IDENTITY;
    private BlockPos pivotWorld;
    private int count = 1;

    private Placement(Mode mode, SourceRef source, Region region, BlockPos dims, BlockPos anchor, BlockPos pivotWorld) {
        this.mode = mode;
        this.source = source;
        this.region = region;
        this.box = region == null ? null : region.bounds();
        this.dims = dims;
        this.anchor = anchor;
        this.pivot = bottomCentre(dims);
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

    /** The local pivot: the bottom centre of the source box. */
    public BlockPos pivot() {
        return pivot;
    }

    public Transform transform() {
        return transform;
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
     * Turns the placement clockwise (seen from above) by quarter turns around the pivot. Returns false (and changes
     * nothing) for a stack, whose copies cannot turn.
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
        transform = transform.compose(Transform.UPSIDE_DOWN);
        return true;
    }

    /**
     * Sets the whole transform (e.g. carried over from the Clipboard window). False (and nothing changes) for a stack
     * with a turn or mirror in it; a stack takes the flip upside down alone.
     */
    public boolean setTransform(Transform next) {
        Objects.requireNonNull(next);
        if (mode == Mode.STACK && !next.horizontal().isIdentity()) return false;
        transform = next;
        return true;
    }

    private boolean apply(Transform step) {
        if (mode == Mode.STACK) return false;
        transform = transform.compose(step);
        return true;
    }

    /** Sets the stack count, clamped to 1..{@value #MAX_STACK}; returns the new count. */
    public int setCount(int copies) {
        count = mode == Mode.STACK ? Math.max(1, Math.min(MAX_STACK, copies)) : 1;
        return count;
    }

    // ---- Geometry ----

    /** The transformed source size. */
    public BlockPos size() {
        return transform.size(dims.x(), dims.y(), dims.z());
    }

    /** The world cell of the transformed box's minimum corner (the flip upside down never moves it). */
    public BlockPos targetMin() {
        long[] mapped = map(transform, dims, pivot.x(), pivot.z());
        return new BlockPos(clampInt(pivotWorld.x() - mapped[0]), pivotWorld.y() - pivot.y(),
                clampInt(pivotWorld.z() - mapped[1]));
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
        long[] mapped = map(transform, dims, anchor.x(), anchor.z());
        long anchorY = min.y() + transform.mapY((long) anchor.y(), dims.y());
        return new BlockPos(clampInt(min.x() + mapped[0]), clampInt(anchorY), clampInt(min.z() + mapped[1]));
    }

    /** targetMin − box.min (MOVE and STACK). */
    public BlockPos offset() {
        if (box == null) return BlockPos.ORIGIN;
        BlockPos min = targetMin();
        return new BlockPos(min.x() - box.min().x(), min.y() - box.min().y(), min.z() - box.min().z());
    }

    /** Whether committing would change nothing (a move or stack that has not moved). */
    public boolean unmoved() {
        if (mode == Mode.PASTE) return false;
        return offset().equals(BlockPos.ORIGIN) && (mode == Mode.STACK || transform.isIdentity());
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
     * {@code options.entities()}), and writes the landing cells {@code options.into()} allows (a paste carries the
     * options whole).
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
            case PASTE -> new OpSpec.Paste(source, pasteOrigin(), transform, options);
            case MOVE -> new OpSpec.Move(region, offset(), transform, new Pattern.Single(air), entities, Symmetry.NONE,
                    options.into());
            case STACK -> {
                BlockPos step = offset();
                yield new OpSpec.Stack(region, step.x(), step.y(), step.z(), count, entities, Symmetry.NONE, options.into(),
                        transform.upsideDown());
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
