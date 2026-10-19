package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.CopySupport.InverseMap;
import dev.sculptory.core.edit.CopySupport.SectionCursor;
import dev.sculptory.core.edit.CopySupport.StateMapper;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.state.OrientedStates;
import dev.sculptory.core.state.ResampledCopy;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.state.TwoCellBlocks;
import dev.sculptory.core.state.TwoCellPairs;
import dev.sculptory.core.transform.CellResampler;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moves a region's cells: the region's bounds (cut to the build height) are the box an exact transform turns, landing
 * with their minimum corner on {@code bounds.min() + offset}; a flip upside down turns the cells over within the
 * region's whole (uncut) height, the pivot the client shows, so a cut selection's cells land where their image there
 * would (cells flipped out of the build height are lost, as by a move out of it); every region
 * cell is written there transformed (air included), then the vacated cells (region cells no moved cell lands on) are set
 * from the {@code leave} pattern. Cells outside the region are neither moved nor touched. Every read comes from the
 * pre-write snapshot of the region's sections, so an overlapping source and destination are handled exactly. Only the
 * region's cells inside the build height move, and only the moved cells inside it are written.
 *
 * <p>A <b>free orientation</b> turns the region's whole bounds (the pivot the client shows, as the flip's) about their
 * true centre, and only its cells inside the build height move; the result box
 * ({@link dev.sculptory.core.transform.Orientation#bounds}) lands with its minimum corner on
 * {@code bounds.min() + turned.offset() + offset}, and a destination leaving the build height is refused. The landing
 * cells are the result cells whose source cell (by the orientation's method,
 * {@link dev.sculptory.core.transform.Orientation#resampler}) is such a region cell, a cell set worked out cell by cell
 * ({@link #landing}; the server works it out once, at admission, and passes it in); states take the nearest cube turn
 * ({@link dev.sculptory.core.state.OrientedStates}), two-cell blocks stay whole within the landing cells and smooth
 * slopes shape turned slopes ({@link dev.sculptory.core.state.ResampledCopy}; a landing cell a kept-whole block took
 * the block from is left as air). A symmetric copy is the exact image of the original
 * ({@link #compileImage}).
 *
 * <p>{@link OpSpec.Move#into()} filters the landing cells by their content right before the write: a landing cell it leaves out is neither written nor claimed, and the
 * region cell whose block would have landed there is vacated all the same, so that block is lost (undo brings it
 * back). A region cell a filtered-out block lands on counts as one no moved cell lands on: it is vacated too.
 *
 * <p>The global mask is judged at the source ({@code CompileContext.sourceMask()}): only
 * the region cells it accepts, as they were before the move, are lifted. A cell it rejects stays where it is: it is
 * neither vacated nor written anywhere, and the landing cell its block would have reached is left alone. The landing
 * writes of the lifted cells are not masked. A mask that reads neighbours reads the snapshots only: of every section
 * the move writes and every section around its region (listed in {@link #sourceSections()}), so each source cell is
 * decided the same way whenever it is asked.
 */
final class MoveProgram implements ClaimingProgram {
    /** The region's bounds cut to the build height (an exact transform turns them; a free one the whole bounds). */
    private final Box source;
    /** Where {@link #source} lands: shifted, or with the flip its image within the region's uncut height, shifted. */
    private final Box destination;
    /** {@link #destination} clipped to the build height. */
    private final Box written;
    private final Box bounds;
    private final RegionProgram.Height height;
    /** The region; its cells inside the build height move. */
    private final Region region;
    /** Where they land: {@link Regions#moved}, read within {@link #movedMinY}..{@link #movedMaxY}. */
    private final Region moved;
    private final int movedMinY;
    private final int movedMaxY;
    private final Pattern leave;
    /** The job's state space, which the leave pattern reads. */
    private final StateSpace states;
    /** The leave state of a {@link Pattern.Single} (turned by the copy's frame), else -1. */
    private final int leaveConstant;
    /** How a symmetric copy evaluates and turns its leave pattern; {@code null} for the original. */
    private final CopyFrame frame;
    private final StateMapper mapper;
    private final InverseMap inverse;
    /** Whether the move flips its cells upside down (within the region's uncut height; see {@link #destination}). */
    private final boolean upsideDown;
    private final PasteOptions.Into into;
    private final long estimatedCells;
    /** The global mask at the source ({@link BoundMask#ALL}: every region cell is lifted). */
    private final BoundMask lift;
    /** The chunk columns snapshotted for a source mask that reads neighbours; {@code null} otherwise. */
    private final LongOpenHashSet snapshotColumns;
    private final long[] sourceSections;
    private final long[] order;
    /** A free orientation's cell and state maps; null for an exact one. */
    private final FreeMap free;
    /** A free move's decisions for two-cell blocks, by pair ({@link #pairLands}). */
    private final ConcurrentHashMap<BlockPos, Boolean> pairDecisions = new ConcurrentHashMap<>();

    private MoveProgram(Region region, Region moved, Box source, Box destination, Box written, RegionProgram.Height height,
                        Pattern leave, StateSpace states, Transform t, PasteOptions.Into into, long[] sourceSections,
                        long[] movedSections, CopyFrame frame, BoundMask lift, FreeMap free) {
        this.free = free;
        this.region = region;
        this.lift = lift;
        this.moved = moved;
        this.source = source;
        this.destination = destination;
        this.written = written;
        this.height = height;
        this.bounds = CopySupport.union(source, written);
        this.movedMinY = written.min().y();
        this.movedMaxY = written.max().y();
        this.leave = leave;
        this.states = states;
        this.frame = frame;
        this.leaveConstant = leave instanceof Pattern.Single single
                ? (frame == null ? single.state() : frame.mapState(single.state())) : -1;
        this.mapper = new StateMapper(states, t);
        this.inverse = InverseMap.of(t, source.sizeX(), source.sizeZ());
        this.upsideDown = t.upsideDown();
        this.into = into;
        LongOpenHashSet keys = new LongOpenHashSet(sourceSections);
        for (long key : movedSections) keys.add(key);
        this.order = CopySupport.ordered(keys);
        // A mask reading neighbours reads them as they were before the move, from snapshots only: every section the move
        // writes and every section around its region is snapshotted, so a source cell is judged the same whenever it is
        // asked (as a landing cell's source, and as a cell to vacate).
        if (lift.reach() > 0) {
            LongOpenHashSet all = new LongOpenHashSet(keys);
            int lowest = height.bottom() >> 4, highest = height.top() >> 4;
            for (long key : sourceSections) {
                int sx = BlockBuffer.keyX(key), sy = BlockBuffer.keyY(key), sz = BlockBuffer.keyZ(key);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (sy + dy >= lowest && sy + dy <= highest) all.add(BlockBuffer.key(sx + dx, sy + dy, sz + dz));
                        }
                    }
                }
            }
            this.sourceSections = CopySupport.ordered(all);
            LongOpenHashSet columns = new LongOpenHashSet();
            for (long key : all) columns.add(EditProgram.column(BlockBuffer.keyX(key), BlockBuffer.keyZ(key)));
            this.snapshotColumns = columns;
        } else if (free != null && (into != PasteOptions.Into.EVERYTHING || !lift.acceptsAll())) {
            // A free move judges a two-cell block's landing cells together, the other half's from its snapshot.
            this.sourceSections = CopySupport.ordered(keys);
            this.snapshotColumns = null;
        } else {
            this.sourceSections = sourceSections;
            this.snapshotColumns = null;
        }
        if (region instanceof Region.Cuboid && free == null) {
            Box overlap = CopySupport.intersection(source, written);
            this.estimatedCells = written.volume() + source.volume() - (overlap == null ? 0 : overlap.volume());
        } else {
            this.estimatedCells = cellsOf(sourceSections, movedSections);
        }
    }

    /**
     * @throws IllegalArgumentException if the region or the destination lies outside the build height, the destination
     *     leaves the coordinate range, or the move spans too many sections
     */
    static MoveProgram compile(OpSpec.Move op, Pattern leave, StateSpace states, RegionProgram.Height height,
                               long maxSections) {
        return compile(op, leave, states, height, maxSections, null, BoundMask.ALL);
    }

    /** {@link #compile(OpSpec.Move, Pattern, StateSpace, RegionProgram.Height, long)} as a symmetric copy under {@code frame}. */
    static MoveProgram compile(OpSpec.Move op, Pattern leave, StateSpace states, RegionProgram.Height height,
                               long maxSections, CopyFrame frame) {
        return compile(op, leave, states, height, maxSections, frame, BoundMask.ALL);
    }

    /**
     * {@link #compile(OpSpec.Move, Pattern, StateSpace, RegionProgram.Height, long, CopyFrame)} lifting only the region
     * cells {@code lift} accepts (the global mask at the source).
     */
    static MoveProgram compile(OpSpec.Move op, Pattern leave, StateSpace states, RegionProgram.Height height,
                               long maxSections, CopyFrame frame, BoundMask lift) {
        if (!op.orientation().isExact()) {
            return compileFree(op, leave, states, height, maxSections, frame, lift, false, null,
                    OpCompiler.maxFreeMoveCells(Long.MAX_VALUE, op.orientation()));
        }
        Region region = op.region();
        Box uncut = region.bounds();
        Box source = height.bounds(uncut);
        Transform t = op.orientation().transform();
        BlockPos size = t.size(source.sizeX(), source.sizeY(), source.sizeZ());
        // The flip's pivot is the selection's whole bounds, as the client shows them: the cut source turns over within
        // the uncut height, so it lands where its image there would, shifted by the offset.
        Box image = t.upsideDown() ? source.flippedWithin(uncut) : source;
        Box destination = CopySupport.boxAt((long) image.min().x() + op.offset().x(),
                (long) image.min().y() + op.offset().y(), (long) image.min().z() + op.offset().z(), size);
        Box written = height.clip(destination);
        if (written == null) throw new IllegalArgumentException("Move destination is outside the build height: " + destination);
        // A flipped move whose image leaves the build height is refused, as the edit service refuses a plain move whose
        // destination does (the cells that would leave it would be lost); an unflipped program still clips, for the
        // paths that admit it themselves.
        if (t.upsideDown() && !written.equals(destination)) {
            throw new IllegalArgumentException("The flipped move would leave the build height: " + destination);
        }
        long[] sourceSections;
        long[] movedSections;
        Region moved;
        if (region instanceof Region.Cuboid) {
            long sections = SectionOrder.sectionCount(source) + SectionOrder.sectionCount(written);
            if (sections > RegionProgram.MAX_SECTIONS) throw new IllegalArgumentException("Move spans too many sections");
            moved = new Region.Cuboid(destination);
            sourceSections = CopySupport.ordered(source);
            movedSections = CopySupport.ordered(written);
        } else {
            long cap = Math.min(maxSections, RegionProgram.MAX_SECTIONS) / 2;
            sourceSections = RegionProgram.regionSections(region, height, cap);
            moved = Regions.moved(region, source, destination.min(), t);
            movedSections = RegionProgram.regionSections(moved,
                    new RegionProgram.Height(written.min().y(), written.max().y()), cap);
        }
        return new MoveProgram(region, moved, source, destination, written, height, leave, states, t, op.into(),
                sourceSections, movedSections, frame, Objects.requireNonNull(lift), null);
    }

    /**
     * A symmetric copy of a move with a free orientation: the exact image under {@code frame} of what {@code original}
     * (the move without symmetry) does. Its region is the image of the original's, its cells land on the images of
     * where the original's land, each taking the block at the image of the original's source cell, turned by
     * {@code image · orientation · image⁻¹}; the leave pattern is the copy's, as for an exact copy.
     *
     * @throws IllegalArgumentException as {@link #compile(OpSpec.Move, Pattern, StateSpace, RegionProgram.Height, long)},
     *     or if the image leaves the coordinate range
     */
    static MoveProgram compileImage(OpSpec.Move original, Pattern leave, StateSpace states, RegionProgram.Height height,
                                    long maxSections, CopyFrame frame, BoundMask lift, Region moved, long maxCells) {
        if (original.orientation().isExact()) throw new IllegalArgumentException("An exact move's copy is a move");
        return compileFree(original, leave, states, height, maxSections, Objects.requireNonNull(frame), lift, true, moved,
                maxCells);
    }

    /**
     * The landing cells of a move with a free orientation: the result cells whose source cell is a region cell inside the
     * build height, the region's whole bounds turning about their true centre (see {@link #compileFree}).
     *
     * @throws IllegalArgumentException if the destination leaves the coordinate range or the build height
     * @throws EditTooLargeException past {@code maxSections} sections or {@code maxCells} region cells
     */
    static Region landing(OpSpec.Move op, RegionProgram.Height height, long maxSections, long maxCells) {
        Region region = op.region();
        Box pivot = region.bounds();
        CellResampler sampler = op.orientation().resampler(pivot.sizeX(), pivot.sizeY(), pivot.sizeZ());
        BlockPos at = sampler.offset();
        Box destination = CopySupport.boxAt((long) pivot.min().x() + at.x() + op.offset().x(),
                (long) pivot.min().y() + at.y() + op.offset().y(), (long) pivot.min().z() + at.z() + op.offset().z(),
                sampler.size());
        Box written = height.clip(destination);
        if (written == null || !written.equals(destination)) {
            throw new IllegalArgumentException("The turned move would leave the build height: " + destination);
        }
        try {
            return Regions.moved(region, pivot, destination.min(), sampler, height.bottom(), height.top(),
                    Math.min(maxSections, RegionProgram.MAX_SECTIONS) / 2, maxCells);
        } catch (dev.sculptory.core.region.RegionTooLargeException e) {
            throw new EditTooLargeException(e.getMessage(), e.cells(), e.limit());
        }
    }

    /**
     * A move with a free orientation, its landing cells ({@code moved}) worked out already (the server does that once,
     * at admission), or {@code null} to work them out here, refusing a region of more than {@code maxCells} cells
     * ({@link Regions#moved(Region, Box, BlockPos, dev.sculptory.core.transform.CellResampler, int, int, long, long)}).
     *
     * @throws IllegalArgumentException as {@link #compile(OpSpec.Move, Pattern, StateSpace, RegionProgram.Height, long)}
     * @throws EditTooLargeException if the region has more than {@code maxCells} cells (or sections than allowed)
     */
    static MoveProgram compileFree(OpSpec.Move op, Pattern leave, StateSpace states, RegionProgram.Height height,
                                   long maxSections, BoundMask lift, Region moved, long maxCells) {
        if (op.orientation().isExact()) throw new IllegalArgumentException("Not a free move");
        return compileFree(op, leave, states, height, maxSections, null, lift, false, moved, maxCells);
    }

    /**
     * A free orientation: the region's whole bounds are the box it turns about its true centre (only the cells inside the
     * build height move), the result box landing with its minimum corner on {@code bounds.min() + turned.offset() +
     * offset}. The moved region is {@code precomputed} or {@link #landing}, a cell set. A destination leaving the build
     * height is refused, as a flipped one is.
     */
    private static MoveProgram compileFree(OpSpec.Move op, Pattern leave, StateSpace states, RegionProgram.Height height,
                                           long maxSections, CopyFrame frame, BoundMask lift, boolean image,
                                           Region precomputed, long maxCells) {
        Region region = op.region();
        // The whole bounds turn (the pivot the client shows, as the flip's); only the cells inside the build height move.
        Box pivot = region.bounds();
        Box source = height.bounds(pivot);
        CellResampler sampler = op.orientation().resampler(pivot.sizeX(), pivot.sizeY(), pivot.sizeZ());
        BlockPos at = sampler.offset();
        Box destination = CopySupport.boxAt((long) pivot.min().x() + at.x() + op.offset().x(),
                (long) pivot.min().y() + at.y() + op.offset().y(), (long) pivot.min().z() + at.z() + op.offset().z(),
                sampler.size());
        Box written = height.clip(destination);
        if (written == null || !written.equals(destination)) {
            throw new IllegalArgumentException("The turned move would leave the build height: " + destination);
        }
        long cap = Math.min(maxSections, RegionProgram.MAX_SECTIONS) / 2;
        Region moved = precomputed != null ? precomputed : landing(op, height, maxSections, maxCells);
        FreeMap free = new FreeMap(sampler, new OrientedStates(states, op.orientation()), states, pivot.min(),
                destination.min(), image ? frame : null, region, moved, height);
        if (image) {
            region = Regions.image(region, frame.symmetry(), frame.image());
            moved = Regions.image(moved, frame.symmetry(), frame.image());
            source = frame.imageBox(source);
            destination = frame.imageBox(destination);
            written = destination;
        }
        long[] sourceSections = RegionProgram.regionSections(region, height, cap);
        long[] movedSections = RegionProgram.regionSections(moved,
                new RegionProgram.Height(written.min().y(), written.max().y()), cap);
        return new MoveProgram(region, moved, source, destination, written, height, leave, states, Transform.IDENTITY,
                op.into(), sourceSections, movedSections, frame, Objects.requireNonNull(lift), free);
    }

    /**
     * Where a free move's landing cells take their blocks from, and how it turns them: the orientation's method over the
     * original's boxes ({@link ResampledCopy}: two-cell blocks kept whole within the landing cells, smooth slopes), and
     * for a symmetric copy through the image (its landing cell's pre-image, the image of the original's source cell, the
     * state turned back, resampled and turned again). The copy reads block states, which a move has only once its
     * snapshots are taken, so it is built at the first {@link #prepare}.
     */
    private static final class FreeMap {
        private final CellResampler sampler;
        private final OrientedStates oriented;
        private final StateSpace states;
        private final BlockPos sourceMin;
        private final BlockPos destinationMin;
        private final CopyFrame image;
        /** The original's region and landing cells, and the heights its cells move between. */
        private final Region region;
        private final Region landing;
        private final int bottom;
        private final int top;
        private volatile ResampledCopy copy;
        private volatile Cells cells;

        FreeMap(CellResampler sampler, OrientedStates oriented, StateSpace states, BlockPos sourceMin,
                BlockPos destinationMin, CopyFrame image, Region region, Region landing, RegionProgram.Height height) {
            this.sampler = sampler;
            this.oriented = oriented;
            this.states = states;
            this.sourceMin = sourceMin;
            this.destinationMin = destinationMin;
            this.image = image;
            this.region = region;
            this.landing = landing;
            this.bottom = height.bottom();
            this.top = height.top();
        }

        /** Builds the copy from the job's snapshots, once. */
        void prepare(ComputeContext ctx) {
            if (copy != null) return;
            synchronized (this) {
                if (copy != null) return;
                Cells read = new Cells(ctx);
                ResampledCopy.Tables tables = ResampledCopy.Tables.shared(states);
                copy = ResampledCopy.of(sampler, read, oriented, oriented.orientation().smoothSlopes(),
                        halves(read, tables.blocks()), tables, (x, y, z) -> landing.contains(destinationMin.x() + x,
                                destinationMin.y() + y, destinationMin.z() + z));
                cells = read;
            }
        }

        /** The region's cells (local to the pivot box) that hold a two-cell block's half, walking the region's cells. */
        private long[] halves(Cells read, TwoCellBlocks blocks) {
            LongArrayList out = new LongArrayList();
            int[] rows = new int[Regions.ROWS];
            for (long key : Regions.sectionKeysBetween(region, bottom, top, Long.MAX_VALUE)) {
                if (Regions.rows(region, key, bottom, top, rows) == 0) continue;
                int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
                for (int r = 0; r < Regions.ROWS; r++) {
                    int ly = oy + (r >>> 4) - sourceMin.y(), lz = oz + (r & 15) - sourceMin.z();
                    for (int bits = rows[r]; bits != 0; bits &= bits - 1) {
                        int lx = ox + Integer.numberOfTrailingZeros(bits) - sourceMin.x();
                        TwoCellPairs.addHalf(out, blocks, read.state(lx, ly, lz), lx, ly, lz);
                    }
                }
            }
            return out.toLongArray();
        }

        /** The world source cell of landing cell (x, y, z) into {@code out}; false when it takes none. */
        boolean source(int x, int y, int z, int[] out) {
            int px = image == null ? x : image.preX(x, z), pz = image == null ? z : image.preZ(x, z);
            if (!copy.cells().source(px - destinationMin.x(), y - destinationMin.y(), pz - destinationMin.z(), cells,
                    out)) {
                return false;
            }
            int sx = sourceMin.x() + out[0], sz = sourceMin.z() + out[2];
            out[0] = image == null ? sx : image.imageX(sx, sz);
            out[1] += sourceMin.y();
            out[2] = image == null ? sz : image.imageZ(sx, sz);
            return true;
        }

        /** Whether any two-cell block was placed (after {@link #prepare}). */
        boolean hasPairs() {
            return copy.hasPairs();
        }

        /**
         * The world landing cell of the other half, into {@code out}, when landing cell (x, y, z) holds a placed pair's
         * half; false otherwise.
         */
        boolean partner(int x, int y, int z, int[] out) {
            int px = image == null ? x : image.preX(x, z), pz = image == null ? z : image.preZ(x, z);
            int lx = px - destinationMin.x(), ly = y - destinationMin.y(), lz = pz - destinationMin.z();
            if (lx < 0 || ly < 0 || lz < 0 || !copy.partner(lx, ly, lz, out)) return false;
            int wx = destinationMin.x() + out[0], wz = destinationMin.z() + out[2];
            out[0] = image == null ? wx : image.imageX(wx, wz);
            out[1] += destinationMin.y();
            out[2] = image == null ? wz : image.imageZ(wx, wz);
            return true;
        }

        /** The state landing cell (x, y, z) writes for {@code state}, the snapshot state of its source cell. */
        int state(int x, int y, int z, int state) {
            int px = image == null ? x : image.preX(x, z), pz = image == null ? z : image.preZ(x, z);
            int lx = px - destinationMin.x(), ly = y - destinationMin.y(), lz = pz - destinationMin.z();
            if (image == null) return copy.state(lx, ly, lz, state);
            return image.mapState(copy.state(lx, ly, lz, image.unmapState(state)));
        }

        /**
         * The original's source cells as the copy reads them, local to its pivot box: a region cell between the heights
         * holds its snapshot state (for a symmetric copy, read at its image and turned back), any other cell nothing.
         */
        private final class Cells implements CellResampler.SourceCells {
            private final ComputeContext ctx;

            Cells(ComputeContext ctx) {
                this.ctx = ctx;
            }

            @Override
            public int state(int x, int y, int z) {
                int wx = sourceMin.x() + x, wy = sourceMin.y() + y, wz = sourceMin.z() + z;
                if (wy < bottom || wy > top || !region.contains(wx, wy, wz)) return -1;
                int rx = image == null ? wx : image.imageX(wx, wz), rz = image == null ? wz : image.imageZ(wx, wz);
                SectionBuffer section = ctx.source(BlockBuffer.keyOfBlock(rx, wy, rz));
                if (section == null) return -1;
                int s = section.get(SectionBuffer.index(rx & 15, wy & 15, rz & 15));
                if (s < 0) return -1;
                return image == null ? s : image.unmapState(s);
            }

            @Override
            public boolean solid(int state) {
                return !StateFlags.has(states.flags(state), StateFlags.AIR);
            }
        }
    }


    @Override
    public String label() {
        return "Move";
    }

    @Override
    public Box bounds() {
        return bounds;
    }

    /** Destination cells plus vacated cells. */
    @Override
    public long estimatedCells() {
        return estimatedCells;
    }

    @Override
    public long[] sourceSections() {
        return sourceSections.clone();
    }

    @Override
    public long[] sectionOrder() {
        return order.clone();
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
        compute(key, before, out, ctx, null);
    }

    /** Covers the cells moved cells land on (those {@code into} allows) and the vacated cells. */
    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx, long[] claimed) {
        int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
        int[] landing = new int[Regions.ROWS];
        int[] leaving = new int[Regions.ROWS];
        int landed = Regions.rows(moved, key, movedMinY, movedMaxY, landing);
        int left = Regions.rows(region, key, height.bottom(), height.top(), leaving);
        if (landed == 0 && left == 0) return;
        if (free != null) free.prepare(ctx);
        SectionCursor snapshot = new SectionCursor(ctx::source);
        int[] cell = new int[3];
        // The world before the move, for the source mask: the cell alone (a cell-only mask), or the snapshots only.
        WorldReader beforeMove = lift.acceptsAll() ? null : new Frozen(ctx, snapshotColumns);
        for (int r = 0; r < Regions.ROWS; r++) {
            int ly = r >>> 4, lz = r & 15;
            int base = SectionBuffer.index(0, ly, lz);
            int y = oy + ly, z = oz + lz;
            int free = ~ClaimingProgram.claimedRow(claimed, r);
            // The landing cells the filter allows; a landing cell it leaves out is treated as not landed on.
            int lands = landing[r] == 0 ? 0 : landing[r] & CopySupport.writableRow(into, states, before, r);
            // Only lifted blocks land (a landing cell whose block stays is left alone).
            if (beforeMove != null) lands &= liftedSources(beforeMove, lands, ox, y, z);
            if (this.free != null && this.free.hasPairs() && landing[r] != 0) {
                lands = pairLands(ctx, beforeMove, before, base, landing[r], lands, ox, y, z, cell);
            }
            // The vacated cells: region cells no moved cell lands on (only lifted ones).
            int vacated = leaving[r] & ~lands;
            if (beforeMove != null) vacated &= liftedCells(beforeMove, leaving[r] & ~lands, ox, y, z);
            int covered = (lands | vacated) & free;
            ClaimingProgram.claimRow(claimed, r, covered);
            for (int bits = lands & free; bits != 0; bits &= bits - 1) {
                int lx = Integer.numberOfTrailingZeros(bits);
                copyCell(before, out, snapshot, base | lx, ox + lx, y, z, cell);
            }
            for (int bits = vacated & free; bits != 0; bits &= bits - 1) {
                int lx = Integer.numberOfTrailingZeros(bits);
                int i = base | lx;
                int current = before.get(i);
                int next = leaveConstant >= 0 ? leaveConstant
                        : frame == null ? leave.apply(states, ox + lx, y, z, current)
                        : frame.apply(leave, ox + lx, y, z, current);
                // A state pattern leaving the same block with a property changed keeps its block entity.
                BlockEntityData tile = Pattern.keepsTile(leave, states, current, next) ? before.tile(i) : null;
                CopySupport.copyCell(before, out, i, next, tile);
            }
        }
    }

    /**
     * A free move's landing cells of one row once two-cell blocks are judged whole ({@code landing} the row's landing
     * cells, {@code lands} those the filter and the source mask let land): a placed pair's half lands only when both
     * halves may (their landing cells allowed by "Paste into" as they were before the move, read from the snapshots,
     * and their source cells lifted by the mask), the same for both halves; and a landing cell whose block a kept-whole pair took lands nothing, so
     * it is left as it is, or vacated with the leave pattern when it is a region cell.
     */
    private int pairLands(ComputeContext ctx, WorldReader beforeMove, SectionBuffer before, int base, int landing,
                          int lands, int ox, int y, int z, int[] cell) {
        // With every landing cell writable and every block lifted, a pair lands whole as it is: nothing to judge.
        boolean judge = into != PasteOptions.Into.EVERYTHING || beforeMove != null;
        int[] partner = new int[3];
        for (int bits = landing; bits != 0; bits &= bits - 1) {
            int lx = Integer.numberOfTrailingZeros(bits);
            int x = ox + lx;
            if (judge && free.partner(x, y, z, partner)) {
                int own = snapshotState(ctx, new int[] {x, y, z});
                int state = own >= 0 ? own : before.get(base | lx);
                boolean both = pairDecisions.computeIfAbsent(pairKey(x, y, z, partner),
                        key -> mayLand(state, x, y, z, beforeMove) && mayLand(snapshotState(ctx, partner), partner[0],
                                partner[1], partner[2], beforeMove));
                lands = both ? lands | 1 << lx : lands & ~(1 << lx);
            }
            if ((lands & 1 << lx) != 0 && !free.source(x, y, z, cell)) lands &= ~(1 << lx);
        }
        return lands;
    }

    /**
     * Whether a pair's half may land on world cell (x, y, z), holding {@code state} before the move, from its source
     * cell. A state the snapshots cannot tell (-1, never for a section the move lists) is taken as writable: a pair is
     * moved whole rather than lost.
     */
    private boolean mayLand(int state, int x, int y, int z, WorldReader beforeMove) {
        if (state >= 0 && !CopySupport.writable(into, states, state)) return false;
        if (beforeMove == null) return true;
        int[] source = new int[3];
        if (!free.source(x, y, z, source)) return false;
        return lift.test(source[0], source[1], source[2], beforeMove.get(source[0], source[1], source[2]), beforeMove);
    }

    /**
     * World cell {@code at} before the move, from the snapshot of its section (a judged free move snapshots every
     * section it lands on), or -1 when there is none.
     */
    private static int snapshotState(ComputeContext ctx, int[] at) {
        SectionBuffer section = ctx.source(BlockBuffer.keyOfBlock(at[0], at[1], at[2]));
        return section == null ? -1 : section.get(SectionBuffer.index(at[0] & 15, at[1] & 15, at[2] & 15));
    }

    /** One key for both halves of a pair: the smaller of their landing cells. */
    private static BlockPos pairKey(int x, int y, int z, int[] partner) {
        BlockPos a = new BlockPos(x, y, z), b = new BlockPos(partner[0], partner[1], partner[2]);
        return a.compareTo(b) <= 0 ? a : b;
    }

    /**
     * Of the landing cells {@code bits} of the row at (y, z) (bit lx: x = ox + lx), those whose source cell the source
     * mask lifts.
     */
    private int liftedSources(WorldReader beforeMove, int bits, int ox, int y, int z) {
        int lifted = 0;
        if (free != null) {
            int[] cell = new int[3];
            for (int b = bits; b != 0; b &= b - 1) {
                int lx = Integer.numberOfTrailingZeros(b);
                if (!free.source(ox + lx, y, z, cell)) continue;
                if (lift.test(cell[0], cell[1], cell[2], beforeMove.get(cell[0], cell[1], cell[2]), beforeMove)) {
                    lifted |= 1 << lx;
                }
            }
            return lifted;
        }
        for (int b = bits; b != 0; b &= b - 1) {
            int lx = Integer.numberOfTrailingZeros(b);
            int tx = ox + lx - destination.min().x(), tz = z - destination.min().z();
            int sx = source.min().x() + inverse.x(tx, tz), sz = source.min().z() + inverse.z(tx, tz);
            int ty = y - destination.min().y();
            int sy = upsideDown ? source.max().y() - ty : source.min().y() + ty;
            if (lift.test(sx, sy, sz, beforeMove.get(sx, sy, sz), beforeMove)) lifted |= 1 << lx;
        }
        return lifted;
    }

    /** Of the region cells {@code bits} of the row at (y, z), those the source mask lifts. */
    private int liftedCells(WorldReader beforeMove, int bits, int ox, int y, int z) {
        int lifted = 0;
        for (int b = bits; b != 0; b &= b - 1) {
            int lx = Integer.numberOfTrailingZeros(b);
            int x = ox + lx;
            if (lift.test(x, y, z, beforeMove.get(x, y, z), beforeMove)) lifted |= 1 << lx;
        }
        return lifted;
    }

    /**
     * The world before the move as the source mask reads it, the same at every moment of the job: the snapshots, air
     * where there is none; a chunk column counts as loaded when it was snapshotted (all when the mask reads the cell
     * alone, which reads nothing else).
     */
    private static final class Frozen implements WorldReader {
        private final ComputeContext ctx;
        private final LongOpenHashSet columns;

        Frozen(ComputeContext ctx, LongOpenHashSet columns) {
            this.ctx = ctx;
            this.columns = columns;
        }

        @Override
        public StateSpace states() {
            return ctx.states();
        }

        @Override
        public int bottomY() {
            return Integer.MIN_VALUE;
        }

        @Override
        public int topYExclusive() {
            return Integer.MAX_VALUE;
        }

        @Override
        public boolean isLoaded(int cx, int cz) {
            return columns == null || columns.contains(EditProgram.column(cx, cz));
        }

        @Override
        public int get(int x, int y, int z) {
            SectionBuffer section = ctx.source(BlockBuffer.keyOfBlock(x, y, z));
            int state = section == null ? -1 : section.get(SectionBuffer.index(x & 15, y & 15, z & 15));
            return state < 0 ? ctx.states().air() : state;
        }

        @Override
        public BlockEntityData tile(int x, int y, int z) {
            SectionBuffer section = ctx.source(BlockBuffer.keyOfBlock(x, y, z));
            return section == null ? null : section.tile(SectionBuffer.index(x & 15, y & 15, z & 15));
        }

        @Override
        public void copySection(int sx, int sy, int sz, SectionBuffer into) {
            into.clearAll();
            SectionBuffer section = ctx.source(BlockBuffer.key(sx, sy, sz));
            for (int i = 0; i < SectionBuffer.SIZE; i++) {
                int state = section == null ? -1 : section.get(i);
                into.set(i, state < 0 ? ctx.states().air() : state);
            }
            if (section != null) section.forEachTile(into::setTile);
        }
    }

    /** Writes the moved cell landing on (x, y, z) from its source cell in the snapshot. */
    private void copyCell(SectionBuffer before, SectionBuffer out, SectionCursor snapshot, int i, int x, int y, int z,
                          int[] cell) {
        if (free != null) {
            // A landing cell whose block went to a kept-whole pair is not landed on (pairLands).
            if (!free.source(x, y, z, cell)) return;
            SectionBuffer section = snapshot.at(cell[0], cell[1], cell[2]);
            if (section == null) return;
            int si = SectionBuffer.index(cell[0] & 15, cell[1] & 15, cell[2] & 15);
            int state = section.get(si);
            if (state < 0) return;
            CopySupport.copyCell(before, out, i, free.state(x, y, z, state), section.tile(si));
            return;
        }
        int tx = x - destination.min().x(), tz = z - destination.min().z();
        int sx = source.min().x() + inverse.x(tx, tz), sz = source.min().z() + inverse.z(tx, tz);
        // The destination's y range is the source's shifted, turned upside down with the flip.
        int ty = y - destination.min().y();
        int sy = upsideDown ? source.max().y() - ty : source.min().y() + ty;
        SectionBuffer section = snapshot.at(sx, sy, sz);
        // A source section the executor could not snapshot (e.g. beyond the world border) is left alone.
        if (section == null) return;
        int si = SectionBuffer.index(sx & 15, sy & 15, sz & 15);
        int state = section.get(si);
        if (state < 0) return;
        CopySupport.copyCell(before, out, i, mapper.map(state), section.tile(si));
    }

    /** Moved cells plus vacated cells, counted section by section (the overlap only where both have sections). */
    private long cellsOf(long[] sourceSections, long[] movedSections) {
        long cells = Regions.cellsBetween(moved, movedMinY, movedMaxY);
        LongOpenHashSet landing = new LongOpenHashSet(movedSections);
        int[] landed = new int[Regions.ROWS];
        int[] leaving = new int[Regions.ROWS];
        for (long key : sourceSections) {
            if (Regions.rows(region, key, height.bottom(), height.top(), leaving) == 0) continue;
            if (landing.contains(key)) {
                Regions.rows(moved, key, movedMinY, movedMaxY, landed);
            } else {
                java.util.Arrays.fill(landed, 0);
            }
            for (int r = 0; r < Regions.ROWS; r++) cells += Integer.bitCount(leaving[r] & ~landed[r]);
        }
        return cells;
    }
}
