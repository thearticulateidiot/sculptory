package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.testing.FakeWorld;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Where a Heightmap dab can write ({@link HeightmapReach}): every cell a stroke's dab writes, its copies included, lies
 * in a range found for it against the world before it, and also against the world before the dab ahead of it (as
 * when two dabs go out in one batch, or a stamp is still being written).
 */
class HeightmapReachTest {
    private final BrushFixture f = new BrushFixture();

    /** Bumpy ground with a pond and plants. */
    private FakeWorld bumpy() {
        FakeWorld world = f.terrain((x, z) -> 58 + Math.floorMod(x * 7 + z * 3, 5) + (x > 10 ? 5 : 0));
        for (int x = -BrushFixture.EXTENT; x <= BrushFixture.EXTENT; x++) {
            for (int z = -BrushFixture.EXTENT; z <= BrushFixture.EXTENT; z++) {
                int top = f.surface(world, x, z);
                if (x < -12) {
                    for (int y = top + 1; y <= 63; y++) world.set(x, y, z, f.water);
                } else if (Math.floorMod(x + z, 3) == 0) {
                    world.set(x, top + 1, z, f.shortGrass);
                }
            }
        }
        world.setHeightHints(true);
        return world;
    }

    private static byte[] picture(int side, long seed) {
        byte[] heights = new byte[side * side];
        new Random(seed).nextBytes(heights);
        return heights;
    }

    @Test
    void everyWriteOfAStrokeLiesInItsDabsRanges() {
        Box clip = new Box(new BlockPos(-30, 55, -9), new BlockPos(30, 70, 12));
        for (boolean lower : new boolean[] {false, true}) {
            for (Symmetry symmetry : List.of(Symmetry.NONE, new Symmetry(Symmetry.Mode.MIRROR_X, 1, 1),
                    new Symmetry(Symmetry.Mode.ROTATE_4, -3, 5))) {
                for (Box box : new Box[] {null, clip}) {
                    String what = (lower ? "lower " : "raise ") + symmetry.mode() + (box == null ? "" : " clipped");
                    BrushSpec spec = HeightmapKernelTest.spec(6, picture(13, 7), 9, lower, SurfaceMask.ANY, box, symmetry);
                    FakeWorld world = bumpy();
                    BrushKernel kernel = BrushKernels.forTool(BrushTool.HEIGHTMAP);
                    StrokeState stroke = new StrokeState();
                    List<Dab> path = new ArrayList<>();
                    for (int i = 0; i < 14; i++) {
                        int x = -20 + i * 3, z = (i % 4) * 2 - 3;
                        path.add(new Dab(i, x * 16 + 8, (f.surface(world, x, z) + 1 + i % 3) * 16, z * 16 + 8,
                                Dab.FULL_PRESSURE));
                    }
                    Long2ObjectOpenHashMap<int[]> stale = ranges(spec, path.get(0), world);
                    int writes = 0;
                    for (int i = 0; i < path.size(); i++) {
                        Long2ObjectOpenHashMap<int[]> now = ranges(spec, path.get(i), world);
                        Long2ObjectOpenHashMap<int[]> ahead = stale;
                        if (i + 1 < path.size()) stale = ranges(spec, path.get(i + 1), world);
                        List<int[]> written = new ArrayList<>();
                        kernel.applyStep(spec, SymmetricStep.of(spec, path.get(i), world).dabs(), stroke, world,
                                (x, y, z, h) -> written.add(new int[] {x, y, z, h}));
                        for (int[] cell : written) {
                            world.set(cell[0], cell[1], cell[2], cell[3]);
                            assertTrue(inside(now, cell), what + ": dab " + i + " wrote outside its range: " + cell[0]
                                    + "," + cell[1] + "," + cell[2]);
                            assertTrue(inside(ahead, cell), what + ": dab " + i + " outside its range found a dab early");
                        }
                        writes += written.size();
                    }
                    assertTrue(writes > 200, what + ": the stroke wrote " + writes);
                }
            }
        }
    }

    @Test
    void rangesHugTheSurfaceInsteadOfTheScanWindow() {
        FakeWorld world = f.flat(60);
        BrushSpec raise = HeightmapKernelTest.spec(4, picture(9, 1), 10, false);
        Long2ObjectOpenHashMap<int[]> up = ranges(raise, BrushFixture.at(0, 0, 61, 0), world);
        assertEquals(11 * 11, up.size(), "the footprint and a column round it: radius + 1");
        for (int[] range : up.values()) assertEquals(List.of(60, 72), List.of(range[0], range[1]), "raised: 10 + 2 plants");
        BrushSpec lower = HeightmapKernelTest.spec(4, picture(9, 1), 10, true);
        for (int[] range : ranges(lower, BrushFixture.at(0, 0, 61, 0), world).values()) {
            assertEquals(List.of(50, 62), List.of(range[0], range[1]), "lowered: 10 down, plants up");
        }
        // No surface in the window (a dab far above the ground): nothing.
        assertTrue(ranges(raise, BrushFixture.at(0, 0, 120, 0), world).isEmpty());
        // Unloaded chunks: nothing there.
        world.setLoaded(-1, 0, false);
        Long2ObjectOpenHashMap<int[]> loaded = ranges(raise, BrushFixture.at(0, 0, 61, 0), world);
        assertEquals(11 * 11 - 5 * 6, loaded.size(), "x -5..-1, z 0..5 lie in chunk (-1, 0)");
        assertFalse(loaded.containsKey(key(-1, 0)));
    }

    @Test
    void anotherToolsSpecIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> HeightmapReach.forEach(
                BrushFixture.spec(BrushTool.RAISE, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE), BrushFixture.at(0, 0, 61, 0),
                f.flat(60), (x, z, minY, maxY) -> {}));
    }

    /** The ranges per column, merged over the step's dabs. */
    private static Long2ObjectOpenHashMap<int[]> ranges(BrushSpec spec, Dab dab, FakeWorld world) {
        Long2ObjectOpenHashMap<int[]> ranges = new Long2ObjectOpenHashMap<>();
        HeightmapReach.forEach(spec, dab, world, (x, z, minY, maxY) -> {
            int[] range = ranges.get(key(x, z));
            if (range == null) {
                ranges.put(key(x, z), new int[] {minY, maxY});
            } else {
                range[0] = Math.min(range[0], minY);
                range[1] = Math.max(range[1], maxY);
            }
        });
        return ranges;
    }

    private static boolean inside(Long2ObjectOpenHashMap<int[]> ranges, int[] cell) {
        int[] range = ranges.get(key(cell[0], cell[2]));
        return range != null && cell[1] >= range[0] && cell[1] <= range[1];
    }

    private static long key(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }
}
