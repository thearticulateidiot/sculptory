package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.RegionTooLargeException;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.state.StateSpace;
import java.util.Arrays;
import java.util.Objects;

/**
 * An edit that writes a pattern over a {@link Region}: every cell of the
 * region ({@link #solid}), only its inside ({@link #inside}: Hollow) or only its walls ({@link #walls}). Cells are
 * further filtered by a bound mask, only cells inside the build height are written, and only cells whose state changes.
 *
 * <p>The program lists only the sections holding region cells, in {@link SectionOrder} order, and works on each as
 * {@link Regions#rows rows} of 16 cells; it never tests the cells of a large bounding box one by one. Hollow's inside is
 * the region cells with at least {@code thickness} region cells on both sides along x, y and z; Walls writes the region
 * cells with a cell outside the region within {@code thickness} along x or z. For a box both are its inner box and its
 * four sides, worked out from the box directly (the section list and cell count too: the inner box's sections, the
 * columns touching the sides); any other region goes through {@link Erosion}. Thicknesses are 1 to
 * {@link OpCompiler#MAX_THICKNESS}.
 */
final class RegionProgram implements ClaimingProgram {
    /** Guard against boxes whose section list alone would exhaust memory. */
    static final long MAX_SECTIONS = 1L << 22;

    private static final long[] NO_SECTIONS = new long[0];

    private enum Mode {
        SOLID,
        INSIDE,
        WALLS
    }

    private final String label;
    private final Region region;
    private final Height height;
    private final Box bounds;
    private final Mode mode;
    /** {@code null} accepts every cell. */
    private final CellPredicate mask;
    private final Pattern pattern;
    /** The job's state space, which the pattern reads. */
    private final StateSpace states;
    /** How a symmetric copy evaluates and turns its pattern; {@code null} for the original (the plain pattern). */
    private final CopyFrame frame;
    /** The state of a {@link Pattern.Single} pattern (turned by the copy's frame), else -1. */
    private final int constant;
    /** Keep shape and remap patterns, cached per state ({@code null} for any other pattern). */
    private final ReplaceCache replace;
    private final long estimatedCells;
    private final long[] sectionOrder;
    /** A box's Hollow or Walls: the cells written are these ({@code null}: none) ... */
    private final Region boxCells;
    /** ... minus these ({@code null}: none). */
    private final Region boxHole;
    /** Hollow and Walls of any other region: its rows, unclipped (the test reads beyond the build height). */
    private final RegionRows rows;
    private final Erosion erosion;

    private RegionProgram(String label, Region region, Height height, Mode mode, int thickness, CellPredicate mask,
                          Pattern pattern, StateSpace states, long estimatedCells, long[] sectionOrder, Region boxCells,
                          Region boxHole, CopyFrame frame) {
        this.label = Objects.requireNonNull(label);
        this.region = region;
        this.height = height;
        this.bounds = height.bounds(region.bounds());
        this.mode = mode;
        this.mask = mask == CellPredicate.ALWAYS ? null : mask;
        this.pattern = Objects.requireNonNull(pattern);
        this.states = Objects.requireNonNull(states);
        this.frame = frame;
        this.constant = pattern instanceof Pattern.Single single
                ? (frame == null ? single.state() : frame.mapState(single.state())) : -1;
        this.replace = ReplaceCache.of(pattern, states, frame);
        this.estimatedCells = estimatedCells;
        this.sectionOrder = sectionOrder;
        this.boxCells = boxCells;
        this.boxHole = boxHole;
        boolean eroded = mode != Mode.SOLID && !(region instanceof Region.Cuboid);
        this.rows = eroded ? new RegionRows(region, Integer.MIN_VALUE, Integer.MAX_VALUE) : null;
        this.erosion = eroded ? new Erosion(rows, thickness) : null;
    }

    /** The build height, inclusive: programs never list sections or cells outside it. */
    record Height(int bottom, int top) {
        /** {@code box} cut to this height, or {@code null} if nothing is left. */
        Box clip(Box box) {
            if (box == null || box.max().y() < bottom || box.min().y() > top) return null;
            if (box.min().y() >= bottom && box.max().y() <= top) return box;
            return new Box(new BlockPos(box.min().x(), Math.max(box.min().y(), bottom), box.min().z()),
                    new BlockPos(box.max().x(), Math.min(box.max().y(), top), box.max().z()));
        }

        Box bounds(Box box) {
            Box clipped = clip(box);
            if (clipped == null) throw new IllegalArgumentException("Edit box is outside the build height: " + box);
            return clipped;
        }
    }

    /** Writes every cell of the region; a region other than a box may reach at most {@code maxSections} sections. */
    static RegionProgram solid(String label, Region region, Height height, CellPredicate mask, Pattern pattern,
                               StateSpace states, long maxSections) {
        return solid(label, region, height, mask, pattern, states, maxSections, null);
    }

    /**
     * {@link #solid(String, Region, Height, CellPredicate, Pattern, StateSpace, long)} as a symmetric copy under
     * {@code frame}.
     */
    static RegionProgram solid(String label, Region region, Height height, CellPredicate mask, Pattern pattern,
                               StateSpace states, long maxSections, CopyFrame frame) {
        Box bounds = height.bounds(region.bounds());
        if (region instanceof Region.Cuboid) {
            return new RegionProgram(label, region, height, Mode.SOLID, 1, mask, pattern, states, bounds.volume(),
                    boxSections(bounds), null, null, frame);
        }
        long[] sections = regionSections(region, height, maxSections);
        return new RegionProgram(label, region, height, Mode.SOLID, 1, mask, pattern, states,
                Regions.cellsBetween(region, height.bottom(), height.top()), sections, null, null, frame);
    }

    /**
     * Writes the region's inside: cells with {@code thickness} region cells on both sides along every axis. For a box,
     * the cells more than {@code thickness} from every face (none if it is too thin).
     */
    static RegionProgram inside(String label, Region region, Height height, int thickness, Pattern pattern,
                                StateSpace states, long maxSections) {
        return inside(label, region, height, thickness, pattern, states, maxSections, null);
    }

    /**
     * {@link #inside(String, Region, Height, int, Pattern, StateSpace, long)} as a symmetric copy under {@code frame}.
     */
    static RegionProgram inside(String label, Region region, Height height, int thickness, Pattern pattern,
                                StateSpace states, long maxSections, CopyFrame frame) {
        checkThickness(thickness);
        Box box = region.bounds();
        boolean tooThin = box.sizeX() <= 2L * thickness || box.sizeY() <= 2L * thickness || box.sizeZ() <= 2L * thickness;
        // A too thin region has no inside; a box's inside is its inner box.
        if (region instanceof Region.Cuboid || tooThin) {
            Box inner = tooThin ? null : new Box(box.min().offset(thickness, thickness, thickness),
                    box.max().offset(-thickness, -thickness, -thickness));
            Box written = height.clip(inner);
            if (written == null) {
                // No cell has thickness cells on both sides along an axis shorter than 2 * thickness + 1.
                return new RegionProgram(label, region, height, Mode.INSIDE, thickness, null, pattern, states, 0,
                        NO_SECTIONS, null, null, frame);
            }
            return new RegionProgram(label, region, height, Mode.INSIDE, thickness, null, pattern, states,
                    written.volume(), boxSections(written), new Region.Cuboid(inner), null, frame);
        }
        long[] sections = regionSections(region, height, maxSections);
        return new RegionProgram(label, region, height, Mode.INSIDE, thickness, null, pattern, states,
                Regions.cellsBetween(region, height.bottom(), height.top()), sections, null, null, frame);
    }

    /**
     * Writes the region's walls: cells with a cell outside the region within {@code thickness} along x or z. For a box,
     * its four vertical sides {@code thickness} thick (all of it if it is too thin).
     */
    static RegionProgram walls(String label, Region region, Height height, int thickness, Pattern pattern,
                               StateSpace states, long maxSections) {
        return walls(label, region, height, thickness, pattern, states, maxSections, null);
    }

    /**
     * {@link #walls(String, Region, Height, int, Pattern, StateSpace, long)} as a symmetric copy under {@code frame}.
     */
    static RegionProgram walls(String label, Region region, Height height, int thickness, Pattern pattern,
                               StateSpace states, long maxSections, CopyFrame frame) {
        checkThickness(thickness);
        if (region instanceof Region.Cuboid cuboid) {
            Box box = cuboid.box();
            Box bounds = height.bounds(box);
            boolean hasInterior = box.sizeX() > 2L * thickness && box.sizeZ() > 2L * thickness;
            if (!hasInterior) {
                return new RegionProgram(label, region, height, Mode.WALLS, thickness, null, pattern, states,
                        bounds.volume(), boxSections(bounds), cuboid, null, frame);
            }
            long sx = bounds.sizeX(), sz = bounds.sizeZ();
            long perLayer = sx * sz - (sx - 2L * thickness) * (sz - 2L * thickness);
            Box hole = new Box(box.min().offset(thickness, 0, thickness), box.max().offset(-thickness, 0, -thickness));
            return new RegionProgram(label, region, height, Mode.WALLS, thickness, null, pattern, states,
                    saturatingProduct(perLayer, bounds.sizeY()), wallSections(bounds, thickness), cuboid,
                    new Region.Cuboid(hole), frame);
        }
        long[] sections = regionSections(region, height, maxSections);
        return new RegionProgram(label, region, height, Mode.WALLS, thickness, null, pattern, states,
                Regions.cellsBetween(region, height.bottom(), height.top()), sections, null, null, frame);
    }

    /** Whether the program works the thickness test out section by section ({@link Erosion}); never for a box. */
    boolean erodes() {
        return erosion != null;
    }

    private static void checkThickness(int thickness) {
        if (thickness < 1 || thickness > OpCompiler.MAX_THICKNESS) {
            throw new IllegalArgumentException("Thickness " + thickness + " is not 1 to " + OpCompiler.MAX_THICKNESS);
        }
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public Box bounds() {
        return bounds;
    }

    @Override
    public long estimatedCells() {
        return estimatedCells;
    }

    @Override
    public long[] sourceSections() {
        return NO_SECTIONS;
    }

    @Override
    public long[] sectionOrder() {
        return sectionOrder.clone();
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
        compute(key, before, out, ctx, null);
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx, long[] claimed) {
        if (sectionOrder.length == 0) return;
        int sx = BlockBuffer.keyX(key), sy = BlockBuffer.keyY(key), sz = BlockBuffer.keyZ(key);
        int ox = sx << 4, oy = sy << 4, oz = sz << 4;
        int[] cells = new int[Regions.ROWS];
        if (mode == Mode.SOLID) {
            if (Regions.rows(region, key, height.bottom(), height.top(), cells) == 0) return;
        } else if (erosion == null) {
            // A box: its inner box, or its sides (the box without the part more than thickness from x and z).
            if (boxCells == null || Regions.rows(boxCells, key, height.bottom(), height.top(), cells) == 0) return;
            if (boxHole != null) {
                int[] hole = new int[Regions.ROWS];
                Regions.rows(boxHole, key, height.bottom(), height.top(), hole);
                for (int r = 0; r < Regions.ROWS; r++) cells[r] &= ~hole[r];
            }
        } else {
            int[] own = rows.rows(key);
            if (own == RegionRows.EMPTY) return;
            int[] alongX = new int[Regions.ROWS];
            int[] alongZ = new int[Regions.ROWS];
            erosion.alongX(sx, sy, sz, own, alongX);
            erosion.alongZ(sx, sy, sz, own, alongZ);
            if (mode == Mode.INSIDE) {
                int[] alongY = new int[Regions.ROWS];
                erosion.alongY(sx, sy, sz, own, alongY);
                for (int r = 0; r < Regions.ROWS; r++) cells[r] = alongX[r] & alongY[r] & alongZ[r];
            } else {
                for (int r = 0; r < Regions.ROWS; r++) cells[r] = own[r] & ~(alongX[r] & alongZ[r]);
            }
            // Only the build height is written.
            for (int ly = 0; ly < 16; ly++) {
                if (oy + ly < height.bottom() || oy + ly > height.top()) Arrays.fill(cells, ly << 4, (ly << 4) + 16, 0);
            }
        }
        for (int r = 0; r < Regions.ROWS; r++) {
            int row = cells[r];
            if (row == 0) continue;
            int ly = r >>> 4, lz = r & 15;
            int base = (ly << 8) | (lz << 4);
            // Runs of consecutive cells, so a full row costs as little as a box row.
            while (row != 0) {
                int from = Integer.numberOfTrailingZeros(row);
                int to = from + Integer.numberOfTrailingZeros(~(row >>> from)) - 1;
                writeRow(before, out, base, ox, oy + ly, oz + lz, from, to, claimed);
                row &= ~(((1 << (to - from + 1)) - 1) << from);
            }
        }
    }

    /**
     * Writes cells {@code lx0..lx1} of a row: those the mask accepts (and, sharing the section with earlier symmetric
     * copies, those not claimed yet, claiming them), where the pattern changes the state.
     */
    private void writeRow(SectionBuffer before, SectionBuffer out, int base, int ox, int y, int z, int lx0, int lx1,
                          long[] claimed) {
        for (int lx = lx0; lx <= lx1; lx++) {
            int i = base | lx;
            if (ClaimingProgram.claimed(claimed, i)) continue;
            int current = before.get(i);
            if (mask != null && !mask.test(ox + lx, y, z, current)) continue;
            ClaimingProgram.claim(claimed, i);
            int next = constant >= 0 ? constant
                    : replace != null ? replace.apply(ox + lx, y, z, current)
                    : frame == null ? pattern.apply(states, ox + lx, y, z, current)
                    : frame.apply(pattern, ox + lx, y, z, current);
            if (next == current) continue;
            out.set(i, next);
            // A state pattern changing a property of the same block keeps the block entity (a waterlogged sign's text).
            // Keep shape carries it where the new block can hold it (a sign made another wood keeps its text).
            if (Pattern.keepsTile(pattern, states, current, next)
                    || (replace != null && replace.carriesTiles() && states.keepsBlockEntity(current, next))) {
                out.setTile(i, before.tile(i));
            }
        }
    }

    /**
     * The sections of the region inside the build height, in {@link SectionOrder} order, refused once more than
     * {@code maxSections} (and {@link #MAX_SECTIONS}) show, before they are all listed.
     *
     * @throws EditTooLargeException past the cap
     */
    static long[] regionSections(Region region, Height height, long maxSections) {
        long cap = Math.min(maxSections, MAX_SECTIONS);
        try {
            return Regions.sectionKeysBetween(region, height.bottom(), height.top(), cap);
        } catch (RegionTooLargeException e) {
            throw new EditTooLargeException(e.getMessage(), e.cells(), e.limit());
        }
    }

    /** Every section of {@code box}, in {@link SectionOrder} order. */
    private static long[] boxSections(Box box) {
        return wallSections(box, 0);
    }

    /**
     * The sections holding a cell of {@code box}'s four sides {@code t} thick (every section of it for {@code t == 0}),
     * in {@link SectionOrder} order.
     */
    private static long[] wallSections(Box box, int t) {
        if (SectionOrder.sectionCount(box) > MAX_SECTIONS) {
            throw new IllegalArgumentException("Edit spans too many sections: " + box);
        }
        BlockPos min = box.min(), max = box.max();
        long[] keys = new long[(int) SectionOrder.sectionCount(box)];
        int n = 0;
        for (int sx = min.x() >> 4; sx <= max.x() >> 4; sx++) {
            for (int sz = min.z() >> 4; sz <= max.z() >> 4; sz++) {
                if (t > 0 && !columnTouchesWalls(box, t, sx, sz)) continue;
                for (int sy = min.y() >> 4; sy <= max.y() >> 4; sy++) keys[n++] = BlockBuffer.key(sx, sy, sz);
            }
        }
        return n == keys.length ? keys : Arrays.copyOf(keys, n);
    }

    /** Whether section column (sx, sz) holds a cell of the walls of {@code box}. */
    private static boolean columnTouchesWalls(Box box, int t, int sx, int sz) {
        long cx0 = (long) sx << 4, cx1 = cx0 + 15, cz0 = (long) sz << 4, cz1 = cz0 + 15;
        BlockPos min = box.min(), max = box.max();
        boolean westSide = cx0 <= min.x() + t - 1L;
        boolean eastSide = cx1 >= max.x() - t + 1L;
        boolean northSide = cz0 <= min.z() + t - 1L;
        boolean southSide = cz1 >= max.z() - t + 1L;
        return westSide || eastSide || northSide || southSide;
    }

    private static long saturatingProduct(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
