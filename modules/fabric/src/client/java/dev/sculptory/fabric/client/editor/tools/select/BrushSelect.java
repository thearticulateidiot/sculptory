package dev.sculptory.fabric.client.editor.tools.select;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.ShapeStamp;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.Objects;

/**
 * The selection brush: one press-and-drag that paints spheres of cells into the selection (or out of it). Pure (a
 * {@link WorldReader}, no Minecraft types) and driven by the Select tool: {@link #dragTo} samples a sphere at the
 * cursor's block and at intermediate blocks when the cursor moved more than a quarter of the radius since the last
 * sample, so a fast drag leaves no gaps; {@link #result} is the selection as painted so far.
 *
 * <p>The sphere of radius {@code r} at a block is exactly the Shape brush's sphere there ({@link #sphere}: the
 * {@link ShapeStamp} box of diameter {@code 2r + 1} centred on the block, voxelized as the contract's
 * {@link ShapeKind#ELLIPSOID}), so it is the same cells as a selection sphere of that box.
 *
 * <p><b>Adding</b> with "solid only" reads the world on the caller's thread (the client thread): air is never added,
 * and cells in chunks the client doesn't have are never read and skipped ({@link #hitUnloaded()}); without it every
 * cell of the sphere within the build height is added, loaded or not. Adding stops at {@code cap} cells
 * ({@link #hitLimit()}) and never passes it. <b>Removing</b> takes every cell of the sphere out, without reading the
 * world. Cells outside the build height are never added.
 *
 * <p><b>The base.</b> The selection the drag starts from, as cells, is set with {@link #setBase} either at once
 * (empty, a cell set, or a small box or shape listed on the client thread) or later, when a large box or shape has
 * been listed off the client thread: until then the painted cells are kept apart, and merging them may put an
 * addition over the cap ({@link #overCap()}: the tool then abandons the drag). Client thread only.
 */
final class BrushSelect {
    /** What a drag does with the cells of its spheres. */
    enum Combine {
        /** A plain drag: they join the selection. */
        ADD,
        /** Alt+drag: they leave it. */
        REMOVE
    }

    private static final ShapeSpec SPHERE = ShapeSpec.solid(ShapeSpec.Kind.SPHERE, 1, Facing.UP);

    private final WorldReader world;
    private final StateSpace states;
    private final boolean solidOnly;
    private final Combine combine;
    private final long cap;
    private final int bottomY;
    private final int topY;
    private int radius;
    /** The live selection once the base is known; the painted cells alone before that. */
    private CellSet.Builder cells = CellSet.builder();
    private boolean baseKnown;
    private boolean overCap;
    private boolean changed;
    private boolean hitLimit;
    private boolean hitUnloaded;
    private boolean cancelled;
    /** Where the last sample was taken (block centre), or NaN before the first. */
    private double lastX = Double.NaN;
    private double lastY;
    private double lastZ;
    /** The block of the last sphere sampled. */
    private int lastBlockX;
    private int lastBlockY;
    private int lastBlockZ;
    private boolean sampled;
    /** Loaded state per chunk column, for one {@link #dragTo} call. */
    private final Long2ByteOpenHashMap loadedChunks = new Long2ByteOpenHashMap();

    /**
     * @param world the client's world (read only when adding with {@code solidOnly})
     * @param radius the sphere radius, {@link SelectSettings#MIN_BRUSH_RADIUS} to {@link SelectSettings#MAX_BRUSH_RADIUS}
     * @param cap the most cells the selection may hold (at least 1)
     */
    BrushSelect(WorldReader world, int radius, boolean solidOnly, Combine combine, long cap) {
        this.world = Objects.requireNonNull(world);
        this.states = world.states();
        this.solidOnly = solidOnly;
        this.combine = Objects.requireNonNull(combine);
        if (cap < 1) throw new IllegalArgumentException("The cap must be at least 1: " + cap);
        this.cap = cap;
        this.bottomY = world.bottomY();
        this.topY = world.topYExclusive();
        setRadius(radius);
    }

    /** The Shape brush's sphere of {@code radius} centred on block {@code centre}: the cells one sample paints. */
    static Region.Shape sphere(BlockPos centre, int radius) {
        Box box = ShapeStamp.box(SPHERE, radius, Facing.UP, 16L * centre.x() + 8, 16L * centre.y() + 8, 16L * centre.z() + 8);
        return new Region.Shape(box, ShapeKind.ELLIPSOID, Facing.UP);
    }

    /** The distance between samples along a drag: a quarter of the radius, at least half a block. */
    static double spacing(int radius) {
        return Math.max(0.5, radius / 4.0);
    }

    Combine combine() {
        return combine;
    }

    int radius() {
        return radius;
    }

    /** Changes the radius for the samples that follow (Ctrl+Scroll during a drag). */
    void setRadius(int radius) {
        if (radius < SelectSettings.MIN_BRUSH_RADIUS || radius > SelectSettings.MAX_BRUSH_RADIUS) {
            throw new IllegalArgumentException("The radius must be " + SelectSettings.MIN_BRUSH_RADIUS + "-"
                    + SelectSettings.MAX_BRUSH_RADIUS + ": " + radius);
        }
        this.radius = radius;
    }

    /** Whether the selection the drag started from is known (see the class comment). */
    boolean baseKnown() {
        return baseKnown;
    }

    /**
     * The cells the drag started from. Cells painted before this are merged in: added ones join them (the total may
     * then be {@link #overCap()}), removed ones leave them.
     */
    void setBase(CellSet base) {
        Objects.requireNonNull(base);
        if (baseKnown) throw new IllegalStateException("The base is already known");
        CellSet painted = cells.build();
        CellSet merged = combine == Combine.ADD ? base.union(painted) : base.subtract(painted);
        cells = CellSet.builder();
        if (!merged.isEmpty()) cells.addAll(new Region.Cells(merged));
        baseKnown = true;
        overCap = combine == Combine.ADD && cells.size() > cap;
        changed = true;
    }

    /** Whether merging the painted cells into a late base put the selection over the cap: the drag is abandoned. */
    boolean overCap() {
        return overCap;
    }

    /**
     * The cursor moved to the centre of block {@code (x, y, z)}: samples a sphere there, and at every
     * {@link #spacing} along the way from the last sample (each block once).
     */
    void dragTo(int x, int y, int z) {
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        loadedChunks.clear();
        if (Double.isNaN(lastX)) {
            sample(x, y, z);
        } else {
            double dx = cx - lastX, dy = cy - lastY, dz = cz - lastZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int steps = (int) Math.max(1, Math.min(1 << 20, Math.ceil(distance / spacing(radius))));
            for (int i = 1; i <= steps; i++) {
                double t = (double) i / steps;
                sample((int) Math.floor(lastX + dx * t), (int) Math.floor(lastY + dy * t), (int) Math.floor(lastZ + dz * t));
            }
        }
        lastX = cx;
        lastY = cy;
        lastZ = cz;
    }

    /** Paints one sphere centred on block {@code (x, y, z)} (nothing when it is the block last sampled). */
    void sample(int x, int y, int z) {
        if (sampled && x == lastBlockX && y == lastBlockY && z == lastBlockZ) return;
        sampled = true;
        lastBlockX = x;
        lastBlockY = y;
        lastBlockZ = z;
        Region.Shape sphere = sphere(new BlockPos(x, y, z), radius);
        Box box = sphere.box();
        int y0 = Math.max(bottomY, box.min().y());
        int y1 = Math.min(topY - 1, box.max().y());
        for (int sz = box.min().z(); sz <= box.max().z(); sz++) {
            for (int sy = y0; sy <= y1; sy++) {
                long span = sphere.rowSpan(sy, sz);
                if (span == Region.Shape.EMPTY_ROW) continue;
                int x0 = Region.Shape.rowMin(span), x1 = Region.Shape.rowMax(span);
                if (combine == Combine.REMOVE) {
                    for (int sx = x0; sx <= x1; sx++) remove(sx, sy, sz);
                } else {
                    for (int sx = x0; sx <= x1; sx++) {
                        if (!add(sx, sy, sz)) return;
                    }
                }
            }
        }
    }

    /** Adds a cell if it qualifies; returns false once the cap stops the drag. */
    private boolean add(int x, int y, int z) {
        if (cells.contains(x, y, z)) return true;
        if (solidOnly) {
            if (!loaded(x, z)) {
                hitUnloaded = true;
                return true;
            }
            if (StateFlags.has(states.flags(world.get(x, y, z)), StateFlags.AIR)) return true;
        }
        if (cells.size() >= cap) {
            hitLimit = true;
            return false;
        }
        cells.add(x, y, z);
        changed = true;
        return true;
    }

    private void remove(int x, int y, int z) {
        if (baseKnown) {
            if (cells.contains(x, y, z)) {
                cells.remove(x, y, z);
                changed = true;
            }
        } else if (!cells.contains(x, y, z)) {
            cells.add(x, y, z); // remembered until the base is known
            changed = true;
        }
    }

    /** Whether {@link #result} has changed since it was last taken. */
    boolean changed() {
        return changed;
    }

    /**
     * The selection as painted so far (empty when nothing is left), once the base is known.
     *
     * @throws IllegalStateException before {@link #setBase}
     */
    CellSet result() {
        if (!baseKnown) throw new IllegalStateException("The base is not known yet");
        changed = false;
        return cells.build();
    }

    /** Cells in the selection so far (the painted cells alone before the base is known). */
    long count() {
        return cells.size();
    }

    /** Whether the cap stopped an addition (the selection holds exactly the cap). */
    boolean hitLimit() {
        return hitLimit;
    }

    /** Whether an addition skipped cells in chunks the client doesn't have. */
    boolean hitUnloaded() {
        return hitUnloaded;
    }

    /** Esc: a base that lands later is ignored. */
    void cancel() {
        cancelled = true;
    }

    boolean cancelled() {
        return cancelled;
    }

    private boolean loaded(int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        byte known = loadedChunks.get(key);
        if (known == 0) {
            known = world.isLoaded(cx, cz) ? (byte) 1 : (byte) 2;
            loadedChunks.put(key, known);
        }
        return known == 1;
    }
}
