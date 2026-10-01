package dev.sculptory.core.edit;

import dev.sculptory.core.region.Regions;

/**
 * The thickness test of Hollow and Walls over any region, one section at a
 * time: which of the section's region cells have a run of region cells reaching {@code thickness} cells on both sides
 * along an axis. Hollow keeps as its inside the cells that pass along x, y and z; Walls writes the cells that fail along
 * x or along z.
 *
 * <p>Each axis is worked line by line (the 256 lines of 16 cells crossing the section along it): a line's runs are
 * counted forwards and backwards in one pass each, seeded with how many region cells continue past the section's two
 * faces. That continuation is read from the six neighbouring sections (the thickness is at most
 * {@link OpCompiler#MAX_THICKNESS} = 16), and only for a line whose edge cell is in the region. So a section costs about
 * 25,000 simple steps plus at most 6 · 256 · 16 reads beyond it, whatever the region's size; nothing is looked up cell by
 * cell with 6 · thickness lookups.
 *
 * <p>The region is read unclipped: cells outside the build height still count as region cells, as a box's shell is
 * measured on the whole box. Not thread-safe.
 */
final class Erosion {
    private final RegionRows region;
    private final int thickness;
    /** Per cell of the line in hand: how many region cells run right before it, capped at the thickness. */
    private final int[] before = new int[16];

    Erosion(RegionRows region, int thickness) {
        if (thickness < 1 || thickness > OpCompiler.MAX_THICKNESS) {
            throw new IllegalArgumentException("Thickness " + thickness + " is not 1 to " + OpCompiler.MAX_THICKNESS);
        }
        this.region = region;
        this.thickness = thickness;
    }

    /** Into {@code keep}: the cells of {@code own} (section (sx, sy, sz)'s rows) whose run along x passes. */
    void alongX(int sx, int sy, int sz, int[] own, int[] keep) {
        for (int r = 0; r < Regions.ROWS; r++) {
            int line = own[r];
            if (line == 0) {
                keep[r] = 0;
                continue;
            }
            int ly = r >>> 4, lz = r & 15;
            int west = (line & 1) != 0 ? run(sx, sy, sz, 0, -1, ly, lz) : 0;
            int east = (line & 0x8000) != 0 ? run(sx, sy, sz, 0, 1, ly, lz) : 0;
            keep[r] = keep(line, west, east);
        }
    }

    /** Into {@code keep}: the cells of {@code own} whose run along y passes. */
    void alongY(int sx, int sy, int sz, int[] own, int[] keep) {
        java.util.Arrays.fill(keep, 0, Regions.ROWS, 0);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int line = 0;
                for (int ly = 0; ly < 16; ly++) line |= ((own[(ly << 4) | lz] >>> lx) & 1) << ly;
                if (line == 0) continue;
                int down = (line & 1) != 0 ? run(sx, sy, sz, 1, -1, lx, lz) : 0;
                int up = (line & 0x8000) != 0 ? run(sx, sy, sz, 1, 1, lx, lz) : 0;
                for (int kept = keep(line, down, up); kept != 0; kept &= kept - 1) {
                    keep[(Integer.numberOfTrailingZeros(kept) << 4) | lz] |= 1 << lx;
                }
            }
        }
    }

    /** Into {@code keep}: the cells of {@code own} whose run along z passes. */
    void alongZ(int sx, int sy, int sz, int[] own, int[] keep) {
        java.util.Arrays.fill(keep, 0, Regions.ROWS, 0);
        for (int ly = 0; ly < 16; ly++) {
            for (int lx = 0; lx < 16; lx++) {
                int line = 0;
                for (int lz = 0; lz < 16; lz++) line |= ((own[(ly << 4) | lz] >>> lx) & 1) << lz;
                if (line == 0) continue;
                int north = (line & 1) != 0 ? run(sx, sy, sz, 2, -1, lx, ly) : 0;
                int south = (line & 0x8000) != 0 ? run(sx, sy, sz, 2, 1, lx, ly) : 0;
                for (int kept = keep(line, north, south); kept != 0; kept &= kept - 1) {
                    keep[(ly << 4) | Integer.numberOfTrailingZeros(kept)] |= 1 << lx;
                }
            }
        }
    }

    /**
     * The cells of a 16-cell line (bit i: cell i is in the region) with at least {@code thickness} region cells on each
     * side, given how many continue before cell 0 ({@code first}) and after cell 15 ({@code last}).
     */
    int keep(int line, int first, int last) {
        int t = thickness;
        int run = first;
        for (int i = 0; i < 16; i++) {
            before[i] = run;
            run = ((line >>> i) & 1) != 0 ? Math.min(t, run + 1) : 0;
        }
        int kept = 0;
        run = last;
        for (int i = 15; i >= 0; i--) {
            boolean in = ((line >>> i) & 1) != 0;
            if (in && before[i] >= t && run >= t) kept |= 1 << i;
            run = in ? Math.min(t, run + 1) : 0;
        }
        return kept;
    }

    /**
     * How many region cells follow the section's face in direction {@code dir} along {@code axis} (0 x, 1 y, 2 z) on the
     * line at local coordinates (a, b) (x: (ly, lz); y: (lx, lz); z: (lx, ly)), capped at the thickness.
     */
    private int run(int sx, int sy, int sz, int axis, int dir, int a, int b) {
        int count = 0;
        for (int step = 1; count < thickness; step++) {
            int[] rows = region.rows(sx + (axis == 0 ? dir * step : 0), sy + (axis == 1 ? dir * step : 0),
                    sz + (axis == 2 ? dir * step : 0));
            if (axis == 0) {
                int row = rows[(a << 4) | b];
                // Region cells from the face inwards: trailing ones going east, leading ones going west.
                int n = dir > 0 ? Integer.numberOfTrailingZeros(~row) : Integer.numberOfLeadingZeros(~(row << 16));
                count += Math.min(n, 16);
                if (n < 16) break;
                continue;
            }
            for (int i = 0; i < 16; i++) {
                int c = dir > 0 ? i : 15 - i;
                int row = axis == 1 ? rows[(c << 4) | b] : rows[(b << 4) | c];
                if (((row >>> a) & 1) == 0) return Math.min(count, thickness);
                count++;
            }
        }
        return Math.min(count, thickness);
    }
}
