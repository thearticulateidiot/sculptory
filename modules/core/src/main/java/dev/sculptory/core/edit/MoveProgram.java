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
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Objects;

/**
 * Moves a region's cells: the region's bounds (cut to the build height) are the box the transform turns, landing with
 * their minimum corner on {@code bounds.min() + offset}; a flip upside down turns the cells over within the region's
 * whole (uncut) height, the pivot the client shows, so a cut selection's cells land where their image there would (cells
 * flipped out of the build height are lost, as by a move out of it); every region
 * cell is written there transformed (air included), then the vacated cells (region cells no moved cell lands on) are set
 * from the {@code leave} pattern. Cells outside the region are neither moved nor touched. Every read comes from the
 * pre-write snapshot of the region's sections, so an overlapping source and destination are handled exactly. Only the
 * region's cells inside the build height move, and only the moved cells inside it are written.
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
    /** The region's bounds cut to the build height: the box the transform turns. */
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

    private MoveProgram(Region region, Region moved, Box source, Box destination, Box written, RegionProgram.Height height,
                        Pattern leave, StateSpace states, Transform t, PasteOptions.Into into, long[] sourceSections,
                        long[] movedSections, CopyFrame frame, BoundMask lift) {
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
        } else {
            this.sourceSections = sourceSections;
            this.snapshotColumns = null;
        }
        if (region instanceof Region.Cuboid) {
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
        Region region = op.region();
        Box uncut = region.bounds();
        Box source = height.bounds(uncut);
        Transform t = op.t();
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
                sourceSections, movedSections, frame, Objects.requireNonNull(lift));
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
        SectionCursor snapshot = new SectionCursor(ctx::source);
        // The world before the move, for the source mask: the cell alone (a cell-only mask), or the snapshots only.
        WorldReader beforeMove = lift.acceptsAll() ? null : new Frozen(ctx, snapshotColumns);
        for (int r = 0; r < Regions.ROWS; r++) {
            int ly = r >>> 4, lz = r & 15;
            int base = SectionBuffer.index(0, ly, lz);
            int y = oy + ly, z = oz + lz;
            int free = ~ClaimingProgram.claimedRow(claimed, r);
            // The landing cells the filter allows; a landing cell it leaves out is treated as not landed on.
            int lands = landing[r] == 0 ? 0 : landing[r] & CopySupport.writableRow(into, states, before, r);
            // The vacated cells: region cells no moved cell lands on.
            int vacated = leaving[r] & ~lands;
            if (beforeMove != null) {
                // Only lifted blocks land; only lifted cells are vacated (a landing cell whose block stays is left alone).
                lands &= liftedSources(beforeMove, lands, ox, y, z);
                vacated = leaving[r] & ~lands & liftedCells(beforeMove, leaving[r] & ~lands, ox, y, z);
            }
            int covered = (lands | vacated) & free;
            ClaimingProgram.claimRow(claimed, r, covered);
            for (int bits = lands & free; bits != 0; bits &= bits - 1) {
                int lx = Integer.numberOfTrailingZeros(bits);
                copyCell(before, out, snapshot, base | lx, ox + lx, y, z);
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
     * Of the landing cells {@code bits} of the row at (y, z) (bit lx: x = ox + lx), those whose source cell the source
     * mask lifts.
     */
    private int liftedSources(WorldReader beforeMove, int bits, int ox, int y, int z) {
        int lifted = 0;
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
    private void copyCell(SectionBuffer before, SectionBuffer out, SectionCursor snapshot, int i, int x, int y, int z) {
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
