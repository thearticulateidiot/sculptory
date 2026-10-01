package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.CopySupport.SectionCursor;
import dev.sculptory.core.edit.CopySupport.StateMapper;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Transform;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Stacks copies of a region: copy {@code k} (1 to {@code count}) is the region's cells (air included) shifted by
 * {@code k * (dx, dy, dz)}, with {@link OpSpec.Stack#upsideDown()} each first turned over within the height of the
 * region's whole (uncut) bounds, the pivot the client shows (cells of a build-height-cut selection flipped out of the
 * build height are lost), its states flipped ({@link Transform#applyToState}); cells outside the region are
 * not copied. All copies read the pre-write snapshot of the
 * region's sections. Where copies overlap each other, the later copy (larger {@code k}) wins; a copy overlapping the
 * region itself overwrites it. Only the region's cells inside the build height are copied, and only the copied cells
 * inside it are written. {@link OpSpec.Stack#into()} filters the cells the copies land on by their content right
 * before the write: a cell it leaves out is neither written nor claimed.
 */
final class StackProgram implements ClaimingProgram {
    /** The region's bounds cut to the build height. */
    private final Box source;
    /**
     * Where {@link #source}'s cells sit before the shift, so copy {@code k} is this box shifted by {@code k · (dx, dy,
     * dz)}: {@link #source} itself, or with the flip {@link #source} turned over within the region's whole (uncut)
     * bounds, the pivot the client shows.
     */
    private final Box image;
    /** With the flip, the region's uncut min plus max y: a source cell at {@code y} lands at {@code flipSum - y} + shift. */
    private final long flipSum;
    /** The region's cells inside the build height; {@code null} for a box (every cell of {@link #source}). */
    private final RegionRows cells;
    private final int dx, dy, dz, count;
    private final StateSpace states;
    private final PasteOptions.Into into;
    /** Whether every copy is flipped upside down within the region's uncut height. */
    private final boolean upsideDown;
    /** Turns the copied states upside down with the flip (the identity without). */
    private final StateMapper mapper;
    private final Box bounds;
    private final long estimatedCells;
    private final long[] sourceSections;
    private final long[] order;

    private StackProgram(Box source, Box image, long flipSum, RegionRows cells, OpSpec.Stack op, StateSpace states,
                         Box bounds, long estimatedCells, long[] sourceSections, long[] order) {
        this.source = source;
        this.image = image;
        this.flipSum = flipSum;
        this.cells = cells;
        this.dx = op.dx();
        this.dy = op.dy();
        this.dz = op.dz();
        this.count = op.count();
        this.states = states;
        this.into = op.into();
        this.upsideDown = op.upsideDown();
        this.mapper = new StateMapper(states, op.upsideDown() ? Transform.UPSIDE_DOWN : Transform.IDENTITY);
        this.bounds = bounds;
        this.estimatedCells = estimatedCells;
        this.sourceSections = sourceSections;
        this.order = order;
    }

    /**
     * @throws IllegalArgumentException if the region or every copy lies outside the build height, a copy leaves the
     *     coordinate range, or the copies span too many sections
     */
    static StackProgram compile(OpSpec.Stack op, StateSpace states, RegionProgram.Height height, long maxSections) {
        Region region = op.region();
        Box uncut = region.bounds();
        Box source = height.bounds(uncut);
        // The flip's pivot is the selection's whole bounds, as the client shows them: the cut source turns over within
        // the uncut height, so its cells sit in the cut box's image there before the shift.
        Box image = op.upsideDown() ? source.flippedWithin(uncut) : source;
        long flipSum = (long) uncut.min().y() + uncut.max().y();
        boolean box = region instanceof Region.Cuboid;
        if (box && SectionOrder.sectionCount(source) > RegionProgram.MAX_SECTIONS / op.count()) {
            throw new IllegalArgumentException("Stack spans too many sections");
        }
        long cap = Math.min(maxSections, RegionProgram.MAX_SECTIONS);
        long[] sourceSections = box ? CopySupport.ordered(source) : RegionProgram.regionSections(region, height, cap);
        if (sourceSections.length > RegionProgram.MAX_SECTIONS / op.count()) {
            throw new IllegalArgumentException("Stack spans too many sections");
        }
        Box bounds = null;
        for (int k = 1; k <= op.count(); k++) {
            Box clipped = height.clip(copyOf(image, op, k));
            if (clipped != null) bounds = bounds == null ? clipped : CopySupport.union(bounds, clipped);
        }
        if (bounds == null) throw new IllegalArgumentException("Every stacked copy is outside the build height");
        if (box) {
            LongOpenHashSet keys = new LongOpenHashSet();
            long cells = 0;
            for (int k = 1; k <= op.count(); k++) {
                Box clipped = height.clip(copyOf(image, op, k));
                if (clipped == null) continue;
                CopySupport.addSections(clipped, keys);
                cells = saturatingAdd(cells, clipped.volume());
            }
            return new StackProgram(source, image, flipSum, null, op, states, bounds, cells, sourceSections,
                    CopySupport.ordered(keys));
        }
        RegionRows cells = new RegionRows(region, height.bottom(), height.top());
        // One pass over the region's rows: the sections every copy of each row reaches, and the cells per layer.
        long[] layers = new long[source.sizeY()];
        LongOpenHashSet keys = new LongOpenHashSet();
        for (long key : sourceSections) {
            int[] rows = cells.rows(key);
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            for (int r = 0; r < rows.length; r++) {
                int row = rows[r];
                if (row == 0) continue;
                int y = oy + (r >>> 4), z = oz + (r & 15);
                layers[y - source.min().y()] += Integer.bitCount(row);
                long west = ox + Integer.numberOfTrailingZeros(row), east = ox + 31 - Integer.numberOfLeadingZeros(row);
                // Where the row lands in copy 0: the same height, or turned over within the source's height.
                long landing = op.upsideDown() ? flipSum - y : y;
                for (int k = 1; k <= op.count(); k++) {
                    long ty = landing + (long) k * op.dy(), tz = z + (long) k * op.dz();
                    if (ty < height.bottom() || ty > height.top()) continue;
                    keys.add(sectionOf(west + (long) k * op.dx(), ty, tz));
                    keys.add(sectionOf(east + (long) k * op.dx(), ty, tz));
                }
                if (keys.size() > cap) {
                    throw new EditTooLargeException("A stack reaching more than " + cap + " sections", keys.size(), cap);
                }
            }
        }
        // Copy k keeps the layers that land inside the build height (layer i lands at min + i + shift, or flipped at
        // max - i + shift).
        long[] below = new long[layers.length + 1];
        for (int i = 0; i < layers.length; i++) below[i + 1] = below[i] + layers[i];
        long total = 0;
        for (int k = 1; k <= op.count(); k++) {
            long shift = (long) k * op.dy();
            long from = op.upsideDown() ? image.max().y() + shift - height.top()
                    : height.bottom() - shift - source.min().y();
            long to = op.upsideDown() ? image.max().y() + shift - height.bottom()
                    : height.top() - shift - source.min().y();
            from = Math.max(0, from);
            to = Math.min(layers.length - 1L, to);
            if (from <= to) total = saturatingAdd(total, below[(int) to + 1] - below[(int) from]);
        }
        return new StackProgram(source, image, flipSum, cells, op, states, bounds, total, sourceSections,
                CopySupport.ordered(keys));
    }

    @Override
    public String label() {
        return "Stack";
    }

    @Override
    public Box bounds() {
        return bounds;
    }

    /** The cells of every copy inside the build height; overlapping copies are counted once per copy. */
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

    /** Covers every cell a copy lands on. */
    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx, long[] claimed) {
        int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
        int x0 = Math.max(bounds.min().x(), ox), x1 = Math.min(bounds.max().x(), ox + 15);
        int y0 = Math.max(bounds.min().y(), oy), y1 = Math.min(bounds.max().y(), oy + 15);
        int z0 = Math.max(bounds.min().z(), oz), z1 = Math.min(bounds.max().z(), oz + 15);
        if (x0 > x1 || y0 > y1 || z0 > z1) return;
        SectionCursor snapshot = new SectionCursor(ctx::source);
        if (cells != null) {
            computeRegion(before, out, snapshot, ox, oy, oz, x0 - ox, x1 - ox, y0 - oy, y1 - oy, z0 - oz, z1 - oz,
                    claimed);
            return;
        }
        long[] range = new long[2];
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                // Copies whose y and z ranges hold this row.
                range[0] = 1;
                range[1] = count;
                if (!narrow(range, y, dy, image.min().y(), image.max().y())
                        || !narrow(range, z, dz, image.min().z(), image.max().z())) {
                    continue;
                }
                long rowLo = range[0], rowHi = range[1];
                int base = SectionBuffer.index(0, y - oy, z - oz);
                for (int x = x0; x <= x1; x++) {
                    range[0] = rowLo;
                    range[1] = rowHi;
                    if (!narrow(range, x, dx, image.min().x(), image.max().x())) continue;
                    int i = base | (x - ox);
                    if (ClaimingProgram.claimed(claimed, i)) continue;
                    if (!CopySupport.writable(into, states, before.get(i))) continue;
                    // The latest copy holding the cell: in a box every copy in range does.
                    long k = range[1];
                    int sx = (int) (x - k * dx), sy = (int) (y - k * dy), sz = (int) (z - k * dz);
                    if (upsideDown) sy = (int) (flipSum - sy);
                    SectionBuffer section = snapshot.at(sx, sy, sz);
                    if (section == null) continue;
                    int si = SectionBuffer.index(sx & 15, sy & 15, sz & 15);
                    int state = section.get(si);
                    if (state < 0) continue;
                    ClaimingProgram.claim(claimed, i);
                    CopySupport.copyCell(before, out, i, mapper.map(state), section.tile(si));
                }
            }
        }
    }

    /**
     * A section of the copies of a region (local ranges {@code lx0..lx1} and so on, inside the bounds), 16 cells at a
     * time: the copies that can reach the section are taken latest first, and for each the source rows landing on the
     * section's rows come from at most 8 source sections ({@link RegionRows}), shifted into place; a cell takes the
     * first copy holding it, and a row every cell of which is taken skips the rest. So a section costs 16 × 16 row
     * operations per copy that reaches it, and a copy whose source sections are all empty only its lookups.
     */
    private void computeRegion(SectionBuffer before, SectionBuffer out, SectionCursor snapshot, int ox, int oy, int oz,
                               int lx0, int lx1, int ly0, int ly1, int lz0, int lz1, long[] claimed) {
        long[] range = {1, count};
        if (!narrowSpan(range, ox + lx0, ox + lx1, dx, image.min().x(), image.max().x())
                || !narrowSpan(range, oy + ly0, oy + ly1, dy, image.min().y(), image.max().y())
                || !narrowSpan(range, oz + lz0, oz + lz1, dz, image.min().z(), image.max().z())) {
            return;
        }
        int xMask = ((1 << (lx1 - lx0 + 1)) - 1) << lx0;
        // Cells taken by a later copy, or claimed by an earlier symmetric copy of the whole op; claimed back at the end.
        int[] written = new int[Regions.ROWS];
        if (claimed != null) {
            for (int r = 0; r < Regions.ROWS; r++) written[r] = ClaimingProgram.claimedRow(claimed, r);
        }
        // The cells the filter allows, per row (every cell without a filter).
        int[] allowed = new int[Regions.ROWS];
        for (int r = 0; r < Regions.ROWS; r++) allowed[r] = CopySupport.writableRow(into, states, before, r);
        int[][] near = new int[8][];
        for (long k = range[1]; k >= range[0]; k--) {
            // Where the section's (0, 0, 0) comes from in copy k, split into a source section and an offset in it. The
            // section's 16 layers come from 16 source layers starting at wy: its own layer ly from wy + ly, or flipped
            // from wy + 15 - ly.
            long bx = ox - k * dx, by = oy - k * dy, bz = oz - k * dz;
            long wy = upsideDown ? flipSum - by - 15 : by;
            int ax = (int) (bx >> 4), ay = (int) (wy >> 4), az = (int) (bz >> 4);
            int fx = (int) (bx & 15), fy = (int) (wy & 15), fz = (int) (bz & 15);
            boolean any = false;
            for (int i = 0; i < 8; i++) {
                near[i] = cells.rows(ax + (i & 1), ay + ((i >> 1) & 1), az + (i >> 2));
                any |= near[i] != RegionRows.EMPTY;
            }
            if (!any) continue;
            for (int ly = ly0; ly <= ly1; ly++) {
                int layer = upsideDown ? 15 - ly : ly;
                int sy = fy + layer;
                for (int lz = lz0; lz <= lz1; lz++) {
                    int r = (ly << 4) | lz;
                    int free = xMask & ~written[r] & allowed[r];
                    if (free == 0) continue;
                    int sz = fz + lz;
                    int n = ((sy >> 4) << 1) | ((sz >> 4) << 2);
                    int row = ((sy & 15) << 4) | (sz & 15);
                    int bits = near[n][row] >>> fx;
                    if (fx != 0) bits |= near[n | 1][row] << (16 - fx);
                    bits &= free;
                    if (bits == 0) continue;
                    written[r] |= bits;
                    int base = SectionBuffer.index(0, ly, lz);
                    for (; bits != 0; bits &= bits - 1) {
                        int lx = Integer.numberOfTrailingZeros(bits);
                        int x = (int) (bx + lx), y = (int) (wy + layer), z = (int) (bz + lz);
                        SectionBuffer section = snapshot.at(x, y, z);
                        if (section == null) continue;
                        int si = SectionBuffer.index(x & 15, y & 15, z & 15);
                        int state = section.get(si);
                        if (state < 0) continue;
                        CopySupport.copyCell(before, out, base | lx, mapper.map(state), section.tile(si));
                    }
                }
            }
        }
        if (claimed != null) {
            for (int r = 0; r < Regions.ROWS; r++) ClaimingProgram.claimRow(claimed, r, written[r]);
        }
    }

    /**
     * Narrows {@code range} (inclusive copy indices) to the copies {@code k} for which some {@code c} in
     * {@code [c0, c1]} has {@code c - k*d} in {@code [lo, hi]}; returns false when none is left.
     */
    private static boolean narrowSpan(long[] range, long c0, long c1, int d, int lo, int hi) {
        long kLo, kHi;
        if (d == 0) {
            if (c1 < lo || c0 > hi) return false;
            return range[0] <= range[1];
        } else if (d > 0) {
            kLo = Math.ceilDiv(c0 - hi, d);
            kHi = Math.floorDiv(c1 - lo, d);
        } else {
            long e = -(long) d;
            kLo = Math.ceilDiv(lo - c1, e);
            kHi = Math.floorDiv(hi - c0, e);
        }
        range[0] = Math.max(range[0], kLo);
        range[1] = Math.min(range[1], kHi);
        return range[0] <= range[1];
    }

    /** Where copy {@code k} of {@code source} lands. */
    private static Box copyOf(Box source, OpSpec.Stack op, int k) {
        return CopySupport.boxAt(source.min().x() + (long) k * op.dx(), source.min().y() + (long) k * op.dy(),
                source.min().z() + (long) k * op.dz(), new BlockPos(source.sizeX(), source.sizeY(), source.sizeZ()));
    }

    /** The key of the section holding the cell, whose coordinates are known to fit an int. */
    private static long sectionOf(long x, long y, long z) {
        return BlockBuffer.keyOfBlock((int) x, (int) y, (int) z);
    }

    /**
     * Narrows {@code range} (inclusive copy indices) to the copies {@code k} with {@code c - k*d} in
     * {@code [lo, hi]}; returns false when none is left.
     */
    private static boolean narrow(long[] range, int c, int d, int lo, int hi) {
        long kLo, kHi;
        if (d == 0) {
            if (c < lo || c > hi) return false;
            return range[0] <= range[1];
        } else if (d > 0) {
            kLo = Math.ceilDiv((long) c - hi, d);
            kHi = Math.floorDiv((long) c - lo, d);
        } else {
            long e = -(long) d;
            kLo = Math.ceilDiv((long) lo - c, e);
            kHi = Math.floorDiv((long) hi - c, e);
        }
        range[0] = Math.max(range[0], kLo);
        range[1] = Math.min(range[1], kHi);
        return range[0] <= range[1];
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return sum < 0 ? Long.MAX_VALUE : sum;
    }
}
