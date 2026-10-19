package dev.sculptory.core.region;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.transform.CellResampler;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The landing cells of a free move ({@link Regions#moved(Region, Box, BlockPos, CellResampler, int, int, long, long)})
 * for every resampling way are exactly the result cells that take one of the region's cells, by brute force.
 */
class MovedResamplingTest {
    @Test
    void theLandingCellsAreTheResultCellsThatTakeARegionCell() {
        Random random = new Random(21);
        CellSet.Builder cells = CellSet.builder();
        Box pivot = new Box(new BlockPos(-5, 60, 3), new BlockPos(14, 71, 20));
        for (int i = 0; i < 900; i++) {
            cells.add(pivot.min().x() + random.nextInt(pivot.sizeX()), pivot.min().y() + random.nextInt(pivot.sizeY()),
                    pivot.min().z() + random.nextInt(pivot.sizeZ()));
        }
        List<Region> regions = List.of(new Region.Cuboid(pivot), new Region.Cells(cells.build()));
        BlockPos destination = new BlockPos(100, 64, -40);
        for (Orientation o : List.of(Orientation.free(Transform.IDENTITY, 3_000, 0, 0),
                Orientation.free(Transform.IDENTITY, 4_500, 2_000, 0), Orientation.free(Transform.IDENTITY, 0, 0, 2_500))) {
            for (Region region : regions) {
                Box bounds = region.bounds();
                int sx = bounds.sizeX(), sy = bounds.sizeY(), sz = bounds.sizeZ();
                for (CellResampler r : List.of(CellResampler.nearest(o, sx, sy, sz), CellResampler.exact(o, sx, sy, sz),
                        CellResampler.smooth(o, sx, sy, sz))) {
                    int lo = 62, hi = 69;
                    CellResampler.SourceCells moving = new CellResampler.SourceCells() {
                        @Override
                        public int state(int x, int y, int z) {
                            int wy = bounds.min().y() + y;
                            return wy >= lo && wy <= hi
                                    && region.contains(bounds.min().x() + x, wy, bounds.min().z() + z) ? 0 : -1;
                        }

                        @Override
                        public boolean solid(int state) {
                            return true;
                        }
                    };
                    Set<BlockPos> expected = new HashSet<>();
                    BlockPos s = r.size();
                    int[] cell = new int[3];
                    for (int x = 0; x < s.x(); x++) {
                        for (int y = 0; y < s.y(); y++) {
                            for (int z = 0; z < s.z(); z++) {
                                // Nearest and exact count look only at positions: the taken cell must be one that moves.
                                if (r.source(x, y, z, moving, cell) && moving.state(cell[0], cell[1], cell[2]) >= 0) {
                                    expected.add(destination.offset(x, y, z));
                                }
                            }
                        }
                    }
                    Region moved = Regions.moved(region, bounds, destination, r, lo, hi, Long.MAX_VALUE, Long.MAX_VALUE);
                    Set<BlockPos> got = new HashSet<>();
                    Box b = moved.bounds();
                    for (int x = b.min().x(); x <= b.max().x(); x++) {
                        for (int y = b.min().y(); y <= b.max().y(); y++) {
                            for (int z = b.min().z(); z <= b.max().z(); z++) {
                                if (moved.contains(x, y, z)) got.add(new BlockPos(x, y, z));
                            }
                        }
                    }
                    Set<BlockPos> missing = new HashSet<>(expected);
                    missing.removeAll(got);
                    Set<BlockPos> extra = new HashSet<>(got);
                    extra.removeAll(expected);
                    assertEquals(Set.of(), missing, o + " " + r.getClass().getSimpleName() + " " + region.getClass()
                            + ": missing (extra " + extra.size() + " of " + got.size() + ")");
                    assertEquals(Set.of(), extra, o + " " + r.getClass().getSimpleName() + " " + region.getClass());
                }
            }
        }
    }
}
