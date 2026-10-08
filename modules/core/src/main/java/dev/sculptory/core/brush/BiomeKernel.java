package dev.sculptory.core.brush;

import dev.sculptory.core.Box;
import dev.sculptory.core.history.BiomeHistory;
import dev.sculptory.core.mask.MaskedKernel;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.Objects;

/**
 * The Biome brush ({@link BrushTool#BIOME}, {@link BiomeSpec}, protocol 6): it writes biome quarts (4×4×4 cells aligned
 * to multiples of 4) through {@link CellSink#biome}, never blocks.
 *
 * <p><b>Which quarts.</b> A quart is painted when its geometric centre ({@code 4q + 2.0} on each axis, a point between
 * cells) lies inside the dab's footprint, and the quart holding the dab's centre always is, so a small brush or a thin
 * area never paints nothing:
 * <ul>
 *   <li><b>Columns</b> ({@link BiomeSpec#columns()}, the default): the footprint is the brush's circle or square across
 *       (x, z) of the given radius, and a chosen quart column is painted over the whole build height. The brush mask and
 *       the global mask judge the column at its surface block: the surface {@link SurfaceScan} finds in its
 *       representative column (the block column {@code 4q + 2}) within {@code radius + 8} above and below the dab; a
 *       column without a surface there is painted only when no mask is set.</li>
 *   <li><b>3D</b>: the footprint is the ball (circle) or cube (square) of the radius around the dab's point, and each
 *       chosen quart is painted on its own, judged at its centre cell ({@code 4q + 2}, slope 0).</li>
 * </ul>
 * The clip box ("only inside selection") keeps the quarts whose centre cell lies in it. Quarts in unloaded chunks and
 * outside the build height are left alone. Strength, falloff, pressure and stroke state play no part: a quart takes the
 * biome or keeps its own. Every dab of a step chooses its quarts the same way; a quart chosen by several is written once.
 * Integer arithmetic throughout, and nothing depends on hash order.
 */
final class BiomeKernel implements MaskedKernel.Grouped {
    /** How far above and below the dab a column's surface is looked for, beyond the radius (as the terrain brushes). */
    static final int SCAN_MARGIN = 8;

    @Override
    public void applyStep(BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w, CellSink out) {
        applyStep(s, dabs, st, w, out, null);
    }

    @Override
    public void applyStep(BrushSpec s, List<Dab> dabs, StrokeState st, WorldReader w, CellSink out,
                          MaskedKernel.Judge mask) {
        Objects.requireNonNull(s);
        Objects.requireNonNull(w);
        Objects.requireNonNull(out);
        if (s.tool() != BrushTool.BIOME) throw new IllegalArgumentException("Biome kernel given a " + s.tool() + " brush");
        BiomeSpec biome = s.biome();
        StateSpace states = w.states();
        ColumnFilter filter = s.mask() instanceof SurfaceMask.Any ? null : ColumnFilter.bind(s.mask(), states).on(w);
        boolean slope = filter != null && ColumnFilter.usesSlope(s.mask());
        int bottom = w.bottomY(), top = w.topYExclusive();
        int minQy = bottom >> 2, maxQy = (top >> 2) - 1;
        LongLinkedOpenHashSet chosen = new LongLinkedOpenHashSet();
        LongOpenHashSet columnsSeen = new LongOpenHashSet();
        for (Dab dab : dabs) {
            if (biome.columns()) {
                columns(s, dab, chosen, columnsSeen);
            } else {
                cells(s, dab, minQy, maxQy, chosen);
            }
        }
        int[] found = new int[2];
        Box clip = s.clip();
        for (long key : chosen) {
            int qx = BiomeHistory.keyX(key), qy = BiomeHistory.keyY(key), qz = BiomeHistory.keyZ(key);
            int x = (qx << 2) + 2, z = (qz << 2) + 2;
            if (!w.isLoaded(x >> 4, z >> 4)) continue;
            if (clip != null && (x < clip.min().x() || x > clip.max().x() || z < clip.min().z() || z > clip.max().z())) {
                continue;
            }
            if (biome.columns()) {
                // qy holds the dab's quart y: its height decides the surface scan's window.
                if ((filter != null || mask != null) && !columnPasses(s, w, states, x, qy, z, filter, slope, mask, found)) {
                    continue;
                }
                for (int y = minQy; y <= maxQy; y++) {
                    int cy = (y << 2) + 2;
                    if (clip != null && (cy < clip.min().y() || cy > clip.max().y())) continue;
                    out.biome(qx, y, qz, biome.biomeId());
                }
            } else {
                int y = (qy << 2) + 2;
                if (clip != null && (y < clip.min().y() || y > clip.max().y())) continue;
                if (filter != null && !filter.test(x, y, z, w.get(x, y, z), 0)) continue;
                if (mask != null && !mask.accepts(x, y, z)) continue;
                out.biome(qx, qy, qz, biome.biomeId());
            }
        }
    }

    /**
     * Chooses the quart columns of one dab (keyed with the dab's quart y, which only places the surface scan): those
     * whose centre lies in the footprint, and the one under the dab's centre.
     */
    private static void columns(BrushSpec s, Dab dab, LongLinkedOpenHashSet chosen, LongOpenHashSet seen) {
        long r16 = s.radius() * 16L;
        int dabQy = dab.blockY() >> 2;
        int qx0 = Math.floorDiv(dab.x16() - (int) r16 - 32, 64), qx1 = Math.floorDiv(dab.x16() + (int) r16 - 32, 64) + 1;
        int qz0 = Math.floorDiv(dab.z16() - (int) r16 - 32, 64), qz1 = Math.floorDiv(dab.z16() + (int) r16 - 32, 64) + 1;
        // A column is chosen once, by the first dab of the step that chooses it (its height places the scan).
        if (seen.add(BiomeHistory.key(dab.blockX() >> 2, 0, dab.blockZ() >> 2))) {
            add(chosen, dab.blockX() >> 2, dabQy, dab.blockZ() >> 2);
        }
        for (int qx = qx0; qx <= qx1; qx++) {
            for (int qz = qz0; qz <= qz1; qz++) {
                long dx = 64L * qx + 32 - dab.x16(), dz = 64L * qz + 32 - dab.z16();
                if (inside(s.shape(), dx, 0, dz, r16) && seen.add(BiomeHistory.key(qx, 0, qz))) add(chosen, qx, dabQy, qz);
            }
        }
    }

    /** Chooses the quarts of one dab in 3D: those whose centre lies in the ball or cube, and the one under its centre. */
    private static void cells(BrushSpec s, Dab dab, int minQy, int maxQy, LongLinkedOpenHashSet chosen) {
        long r16 = s.radius() * 16L;
        int qx0 = Math.floorDiv(dab.x16() - (int) r16 - 32, 64), qx1 = Math.floorDiv(dab.x16() + (int) r16 - 32, 64) + 1;
        int qy0 = Math.max(minQy, Math.floorDiv(dab.y16() - (int) r16 - 32, 64));
        int qy1 = Math.min(maxQy, Math.floorDiv(dab.y16() + (int) r16 - 32, 64) + 1);
        int qz0 = Math.floorDiv(dab.z16() - (int) r16 - 32, 64), qz1 = Math.floorDiv(dab.z16() + (int) r16 - 32, 64) + 1;
        int centreQy = dab.blockY() >> 2;
        if (centreQy >= minQy && centreQy <= maxQy) add(chosen, dab.blockX() >> 2, centreQy, dab.blockZ() >> 2);
        for (int qy = qy0; qy <= qy1; qy++) {
            for (int qx = qx0; qx <= qx1; qx++) {
                for (int qz = qz0; qz <= qz1; qz++) {
                    long dx = 64L * qx + 32 - dab.x16(), dy = 64L * qy + 32 - dab.y16(), dz = 64L * qz + 32 - dab.z16();
                    if (inside(s.shape(), dx, dy, dz, r16)) add(chosen, qx, qy, qz);
                }
            }
        }
    }

    private static void add(LongLinkedOpenHashSet chosen, int qx, int qy, int qz) {
        chosen.add(BiomeHistory.key(qx, qy, qz));
    }

    /** Whether the offset (1/16 block) lies in the circle or ball ({@link Shape#CIRCLE}) or square or cube. */
    static boolean inside(Shape shape, long dx, long dy, long dz, long r16) {
        if (shape == Shape.CIRCLE) return dx * dx + dy * dy + dz * dz <= r16 * r16;
        return Math.abs(dx) <= r16 && Math.abs(dy) <= r16 && Math.abs(dz) <= r16;
    }

    /** Whether the masks accept column (x, z) at its surface, scanned around quart y {@code qy}. */
    private static boolean columnPasses(BrushSpec s, WorldReader w, StateSpace states, int x, int qy, int z,
                                        ColumnFilter filter, boolean slope, MaskedKernel.Judge mask, int[] found) {
        int dabY = (qy << 2) + 2;
        long reach = (long) s.radius() + SCAN_MARGIN;
        int bottom = w.bottomY(), top = w.topYExclusive() - 1;
        int yTop = (int) Math.max(bottom, Math.min(top, dabY + reach));
        int yBottom = (int) Math.max(bottom, Math.min(top, dabY - reach));
        int y = SurfaceScan.scan(w, states, x, z, yTop, yBottom, found);
        if (y == SurfaceScan.NONE) return false;
        int state = found[0];
        if (filter != null) {
            int step = slope ? slope(w, states, x, y, z, yTop, yBottom) : 0;
            if (!filter.test(x, y, z, state, step)) return false;
        }
        return mask == null || mask.accepts(x, y, z);
    }

    /** The column's largest height step to a cardinal neighbour that has a surface (in a loaded chunk). */
    private static int slope(WorldReader w, StateSpace states, int x, int y, int z, int yTop, int yBottom) {
        int steepest = 0;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            int nx = x + side[0], nz = z + side[1];
            if (!w.isLoaded(nx >> 4, nz >> 4)) continue;
            int ny = SurfaceScan.scan(w, states, nx, nz, yTop, yBottom, null);
            if (ny != SurfaceScan.NONE) steepest = Math.max(steepest, Math.abs(ny - y));
        }
        return steepest;
    }
}
