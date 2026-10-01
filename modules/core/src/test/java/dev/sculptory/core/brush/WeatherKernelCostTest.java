package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.testing.FakeStateSpace;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * What a radius-32 Weather dab costs in the kernel itself, each mode in both sculpt modes, on {@link SurfaceScene} held
 * in a flat array ({@link SurfaceKernelCostTest.ArrayWorld}), next to Surface Smooth on the same dab for scale: best and
 * median of twenty, one stroke each. Printed, with a loose bound. The server's cost on a real world, with the garbage
 * collector's pauses left out, is {@code WeatherGameTest.aRadius32DabComputesInMilliseconds}.
 */
class WeatherKernelCostTest {
    private final FakeStateSpace states = new FakeStateSpace();

    @Test
    void aRadius32DabComputesInMilliseconds() {
        SurfaceKernelCostTest.ArrayWorld world = new SurfaceKernelCostTest.ArrayWorld(states);
        SurfaceScene.build(states, world::set);
        Dab dab = SurfaceScene.wallDab(0, 80, 0);
        StringBuilder report = new StringBuilder();
        BrushSpec smooth = new BrushSpec(BrushTool.SMOOTH, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                0, 3L).withSurface(null);
        time("SURFACE SMOOTH (for scale)", smooth, dab, world, report);
        for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
            BrushSpec terrain = new BrushSpec(BrushTool.WEATHER, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
                    SurfaceMask.ANY, 0, 0, 3L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null, new WeatherSpec(mode));
            time("SURFACE " + mode, terrain.withSurface(null), dab, world, report);
            time("TERRAIN " + mode, terrain, dab, world, report);
        }
        System.out.println("Weather radius-32 dab, kernel only:" + report);
    }

    /** Twenty dabs of one stroke after forty to warm up, writes discarded; the best and the median. */
    private static void time(String what, BrushSpec spec, Dab dab, SurfaceKernelCostTest.ArrayWorld world,
                             StringBuilder report) {
        long[] took = new long[20];
        int[] cells = {0};
        StrokeState stroke = new StrokeState();
        for (int run = 0; run < 60; run++) {
            cells[0] = 0;
            long start = System.nanoTime();
            BrushKernels.forTool(spec.tool()).apply(spec, dab, stroke, world, (x, y, z, h) -> cells[0]++);
            if (run >= 40) took[run - 40] = System.nanoTime() - start;
        }
        Arrays.sort(took);
        report.append(String.format("%n  %s: best %.2f ms, median %.2f ms, %d cells", what, took[0] / 1e6,
                took[took.length / 2] / 1e6, cells[0]));
        assertTrue(took[0] < 2_000_000_000L, what + " took " + took[0] / 1e6 + " ms");
    }
}
