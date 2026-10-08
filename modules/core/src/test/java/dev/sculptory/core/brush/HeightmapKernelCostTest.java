package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.testing.FakeStateSpace;
import java.util.Arrays;
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
