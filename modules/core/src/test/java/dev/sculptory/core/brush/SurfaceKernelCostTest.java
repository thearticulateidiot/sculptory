package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a radius-32 Surface-mode dab costs in the kernel itself, against the kernel as it was before Smooth was made
 * faster ({@link SurfaceKernelReference}), on {@link SurfaceScene} held in a flat array (so the world's own reads cost
 * next to nothing, unlike {@code FakeWorld}'s map): the two run in turn, so load on the machine slows both alike.
 * Printed, with a loose bound. The server's cost on a real world is
 * {@code BrushSurfaceGameTest.aRadius32DabComputesInMilliseconds}.
 */
class SurfaceKernelCostTest {
    private final FakeStateSpace states = new FakeStateSpace();

    @Test
    void aRadius32DabComputesInMilliseconds() {
        ArrayWorld world = new ArrayWorld(states);
        SurfaceScene.build(states, world::set);
        StringBuilder report = new StringBuilder();
        for (BrushTool tool : List.of(BrushTool.RAISE, BrushTool.LOWER, BrushTool.SMOOTH, BrushTool.FLATTEN)) {
            SurfacePlane plane = tool == BrushTool.FLATTEN ? new SurfacePlane(Facing.WEST, SurfaceScene.WALL_X) : null;
            BrushSpec spec = new BrushSpec(tool, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 3L)
                    .withSurface(plane);
            Dab[] dabs = {SurfaceScene.wallDab(0, 80, 0)};
            long[] fast = new long[20], old = new long[20];
            int[] cells = {0, 0};
            // One stroke each, as the server keeps one per press (a full-strength Smooth leaves no accumulator behind).
            StrokeState fastStroke = new StrokeState(), oldStroke = new StrokeState();
            for (int run = 0; run < 60; run++) {
                cells[0] = 0;
                cells[1] = 0;
                long start = System.nanoTime();
                SurfaceKernel.apply(spec, dabs[0], dabs, fastStroke, world, (x, y, z, h) -> cells[0]++);
                long middle = System.nanoTime();
                SurfaceKernelReference.apply(spec, dabs[0], dabs, oldStroke, world, (x, y, z, h) -> cells[1]++);
                long end = System.nanoTime();
                if (run >= 40) {
                    fast[run - 40] = middle - start;
                    old[run - 40] = end - middle;
                }
            }
            // Smooth writes what the old kernel wrote; the line tools work in a cylinder now, the reference in the ball.
            if (tool == BrushTool.SMOOTH) assertEquals(cells[1], cells[0], tool + " wrote another number of cells than before");
            Arrays.sort(fast);
            Arrays.sort(old);
            report.append(String.format("%n  %s: best %.2f ms, median %.2f ms (before: best %.2f ms, median %.2f ms), %d cells",
                    tool, fast[0] / 1e6, fast[fast.length / 2] / 1e6, old[0] / 1e6, old[old.length / 2] / 1e6, cells[0]));
            assertTrue(fast[0] < 2_000_000_000L, tool + " took " + fast[0] / 1e6 + " ms");
        }
        System.out.println("Surface radius-32 dab, kernel only:" + report);
    }

    /** {@link SurfaceScene}'s extent in a flat array: every chunk loaded, air outside it. */
    static final class ArrayWorld implements WorldReader {
        private static final int E = SurfaceScene.EXTENT + 40;
        private static final int W = 2 * E + 1;
        private static final int Y0 = 0, H = 192;
        private final StateSpace states;
        private final int[] cells = new int[W * W * H];

        ArrayWorld(StateSpace states) {
            this.states = states;
        }

        void set(int x, int y, int z, int state) {
            cells[((x + E) * W + (z + E)) * H + (y - Y0)] = state;
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
            if (x < -E || x > E || z < -E || z > E || y < Y0 || y >= Y0 + H) return states.air();
            return cells[((x + E) * W + (z + E)) * H + (y - Y0)];
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
}
