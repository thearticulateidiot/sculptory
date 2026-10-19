package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a radius-32 Heightmap dab costs in the kernel itself, on rolling ground held in a flat array
 * ({@link SurfaceKernelCostTest.ArrayWorld}): a fresh stamp of a round hill 16, 32 and 128 blocks tall (writes
 * discarded, so every run is a first stamp), and a drag of twelve dabs eight blocks apart laying a 32-block ridge (writes
 * applied, on a fresh world each run): best and median. Printed, with a loose bound. The server's cost on a real world
 * is {@code HeightmapGameTest.aRadius32DabComputesInMilliseconds}.
 */
class HeightmapKernelCostTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int grass = states.state("minecraft:grass_block");

    @Test
    void aRadius32DabComputesInMilliseconds() {
        byte[] hill = hill(65);
        StringBuilder report = new StringBuilder();
        for (int signed : new int[] {16, 32, 128, -128}) {
            int heightBlocks = Math.abs(signed);
            BrushSpec spec = HeightmapKernelTest.spec(32, hill, heightBlocks, signed < 0);
            SurfaceKernelCostTest.ArrayWorld world = rolling();
            long[] took = new long[20];
            int[] cells = {0};
            for (int run = 0; run < 60; run++) {
                cells[0] = 0;
                StrokeState stroke = new StrokeState();
                long start = System.nanoTime();
                BrushKernels.forTool(BrushTool.HEIGHTMAP).apply(spec, BrushFixture.at(0, 0, 65, 0), stroke, world,
                        (x, y, z, h) -> cells[0]++);
                if (run >= 40) took[run - 40] = System.nanoTime() - start;
            }
            Arrays.sort(took);
            report.append(String.format("%n  stamp %d blocks %s: best %.2f ms, median %.2f ms, %d cells", heightBlocks,
                    signed < 0 ? "deep" : "tall",
                    took[0] / 1e6, took[took.length / 2] / 1e6, cells[0]));
            assertTrue(took[0] < 2_000_000_000L, "a stamp took " + took[0] / 1e6 + " ms");
        }
        BrushSpec ridge = HeightmapKernelTest.spec(32, hill, 32, false);
        long[] perDab = new long[10];
        int[] cells = {0};
        for (int run = 0; run < 30; run++) {
            SurfaceKernelCostTest.ArrayWorld world = rolling();
            StrokeState stroke = new StrokeState();
            cells[0] = 0;
            long start = System.nanoTime();
            for (int i = 0; i < 12; i++) {
                BrushKernels.forTool(BrushTool.HEIGHTMAP).apply(ridge, BrushFixture.at(i, -40 + 8 * i, 70, 0), stroke, world,
                        (x, y, z, h) -> {
                            world.set(x, y, z, h);
                            cells[0]++;
                        });
            }
            if (run >= 20) perDab[run - 20] = (System.nanoTime() - start) / 12;
        }
        Arrays.sort(perDab);
        report.append(String.format("%n  drag of 12 dabs, 32 blocks tall: best %.2f ms, median %.2f ms a dab, %d cells a stroke",
                perDab[0] / 1e6, perDab[perDab.length / 2] / 1e6, cells[0]));
        System.out.println("Heightmap radius-32 dab, kernel only:" + report);
        assertTrue(perDab[0] < 2_000_000_000L, "a drag dab took " + perDab[0] / 1e6 + " ms");
    }


    /**
     * A radius-128 dab (a 257-wide stamp), worked out a piece at a time as the server does ({@link PlannedStep#planner},
     * each piece given 2 ms, garbage collection pauses left out): a round hill 64 and 128 blocks tall, a 128-deep cut of it, and a 128-deep cut white all
     * over (the largest step there is), on rolling ground; then a drag of six dabs 32 blocks apart (the stroke's spacing,
     * a quarter of the radius) laying a 64-tall ridge, writes applied. Printed: the whole working out, the longest piece,
     * the pieces, the cells; the cells equal the kernel's run whole. Loose bounds: no piece stalls, whatever the machine.
     */
    @Test
    void aRadius128DabWorksOutInPieces() {
        byte[] hill = hill(257);
        byte[] white = new byte[257 * 257];
        Arrays.fill(white, (byte) 255);
        StringBuilder report = new StringBuilder();
        Object[][] cases = {{"hill 64 tall", hill, 64, false}, {"hill 128 tall", hill, 128, false},
                {"hill 128 deep", hill, 128, true}, {"white 128 deep", white, 128, true}};
        for (Object[] c : cases) {
            BrushSpec spec = HeightmapKernelTest.spec(128, (byte[]) c[1], (int) c[2], (boolean) c[3]);
            RollingWorld world = new RollingWorld(states, stone, grass);
            Dab dab = BrushFixture.at(0, 0, 68, 0);
            long best = Long.MAX_VALUE, longestPiece = 0;
            int pieces = 0;
            long cells = 0;
            for (int run = 0; run < 3; run++) {
                PlannedStep.Planner planner = PlannedStep.planner(BrushKernels.forTool(BrushTool.HEIGHTMAP), spec,
                        List.of(dab), new StrokeState(), world, null);
                long start = System.nanoTime();
                int n = 0;
                long longest = 0;
                boolean done = false;
                while (!done) {
                    long gc = gcMillis();
                    long piece = System.nanoTime();
                    done = planner.advance(piece + 2_000_000L);
                    longest = Math.max(longest, System.nanoTime() - piece - (gcMillis() - gc) * 1_000_000L);
                    n++;
                }
                long took = System.nanoTime() - start;
                if (took < best) {
                    best = took;
                    longestPiece = longest;
                    pieces = n;
                }
                cells = planner.step().cells();
            }
            long[] whole = {0};
            BrushKernels.forTool(BrushTool.HEIGHTMAP).apply(spec, dab, new StrokeState(), world, (x, y, z, h) -> whole[0]++);
            assertEquals(whole[0], cells, c[0] + ": the pieces plan what the kernel writes whole");
            report.append(String.format("%n  %s: worked out in %.1f ms, %d pieces, longest %.1f ms, %d cells", c[0],
                    best / 1e6, pieces, longestPiece / 1e6, cells));
            assertTrue(longestPiece < 250_000_000L, c[0] + ": a piece took " + longestPiece / 1e6 + " ms");
        }
        BrushSpec ridge = HeightmapKernelTest.spec(128, hill, 64, false);
        RollingWorld world = new RollingWorld(states, stone, grass);
        StrokeState stroke = new StrokeState();
        long longestPiece = 0, totalCells = 0, worst = 0;
        for (int i = 0; i < 6; i++) {
            PlannedStep.Planner planner = PlannedStep.planner(BrushKernels.forTool(BrushTool.HEIGHTMAP), ridge,
                    List.of(BrushFixture.at(i, -80 + 32 * i, 68, 0)), stroke, world, null);
            long start = System.nanoTime();
            boolean done = false;
            while (!done) {
                long gc = gcMillis();
                long piece = System.nanoTime();
                done = planner.advance(piece + 2_000_000L);
                longestPiece = Math.max(longestPiece, System.nanoTime() - piece - (gcMillis() - gc) * 1_000_000L);
            }
            worst = Math.max(worst, System.nanoTime() - start);
            PlannedStep step = planner.step();
            totalCells += step.cells();
            step.runAll(world, world::set);
        }
        report.append(String.format("%n  drag of 6 dabs 32 apart, 64 tall: slowest dab worked out in %.1f ms, longest piece"
                + " %.1f ms, %d cells a stroke", worst / 1e6, longestPiece / 1e6, totalCells));
        System.out.println("Heightmap radius-128 dab, kernel only:" + report);
        assertTrue(longestPiece < 250_000_000L, "a drag piece took " + longestPiece / 1e6 + " ms");
    }

    /**
     * Rolling ground worked out per cell (stone to the bottom under a grass top between 60 and 67), with the cells
     * written kept aside: room for radius-128 stamps without a dense array. Its height hint is the column's top, as a
     * server's is.
     */
    static final class RollingWorld implements WorldReader {
        private final StateSpace states;
        private final int stone, grass;
        private final Long2IntOpenHashMap written = new Long2IntOpenHashMap();
        private final Long2IntOpenHashMap tops = new Long2IntOpenHashMap();

        RollingWorld(StateSpace states, int stone, int grass) {
            this.states = states;
            this.stone = stone;
            this.grass = grass;
            written.defaultReturnValue(-1);
            tops.defaultReturnValue(Integer.MIN_VALUE);
        }

        private static int top(int x, int z) {
            return 60 + Math.floorMod(Math.floorDiv(x, 5) + Math.floorDiv(z, 7), 8);
        }

        private static long key(int x, int y, int z) {
            return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        }

        private static long column(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }

        void set(int x, int y, int z, int state) {
            written.put(key(x, y, z), state);
            if (!StateFlags.has(states.flags(state), StateFlags.AIR)) {
                long c = column(x, z);
                tops.put(c, Math.max(tops.get(c), y));
            }
        }

        @Override
        public StateSpace states() {
            return states;
        }

        @Override
        public int bottomY() {
            return -64;
        }

        @Override
        public int topYExclusive() {
            return 320;
        }

        @Override
        public boolean isLoaded(int cx, int cz) {
            return true;
        }

        @Override
        public int get(int x, int y, int z) {
            int set = written.get(key(x, y, z));
            if (set >= 0) return set;
            int top = top(x, z);
            if (y > top) return states.air();
            return y < top ? stone : grass;
        }

        @Override
        public int heightHint(int x, int z) {
            return Math.max(top(x, z), tops.get(column(x, z))) + 1;
        }

        @Override
        public BlockEntityData tile(int x, int y, int z) {
            return null;
        }

        @Override
        public void copySection(int sx, int sy, int sz, SectionBuffer into) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Milliseconds the JVM's stop-the-world collections have paused every thread for so far. G1's "G1 Concurrent GC"
     * bean counts: in JDK 21 its time is the Remark and Cleanup pauses (JDK-8297247), which stop every thread. ZGC's and
     * Shenandoah's "... Cycles" beans don't: their time is spent by collector threads while ours runs, so subtracting it
     * would hide real work. (The same rule as {@code ShapeBrushLaneGameTest.gcMillis} in the GameTests.)
     */
    static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            String name = gc.getName();
            if (name.contains("Cycles")) continue;
            total += Math.max(0, gc.getCollectionTime());
        }
        return total;
    }

    /** A round hill: 255 at the centre, falling to 0 at the edge. */
    static byte[] hill(int side) {
        byte[] pixels = new byte[side * side];
        double c = (side - 1) / 2.0;
        for (int v = 0; v < side; v++) {
            for (int u = 0; u < side; u++) {
                double d = Math.sqrt((u - c) * (u - c) + (v - c) * (v - c)) / c;
                pixels[v * side + u] = (byte) Math.max(0, Math.round(255 * (1 - d * d)));
            }
        }
        return pixels;
    }

    /** Stone under a grass top rolling between 60 and 67, over the array's whole extent. */
    private SurfaceKernelCostTest.ArrayWorld rolling() {
        SurfaceKernelCostTest.ArrayWorld world = new SurfaceKernelCostTest.ArrayWorld(states);
        for (int x = -84; x <= 84; x++) {
            for (int z = -84; z <= 84; z++) {
                int top = 60 + Math.floorMod(x / 5 + z / 7, 8);
                for (int y = 40; y <= top; y++) world.set(x, y, z, y < top ? stone : grass);
            }
        }
        return world;
    }
}
