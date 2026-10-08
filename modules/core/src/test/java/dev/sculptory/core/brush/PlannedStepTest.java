package dev.sculptory.core.brush;

import static dev.sculptory.core.brush.BrushFixture.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.Box;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A planned step ({@link PlannedStep}): worked out without touching the world, its parts write exactly what the kernel
 * writes in one go, in the same order, at most {@value PlannedStep#PART_CELLS} cells each, inside each part's area; a
 * cell the world changed between parts is left out; a stroke of planned steps ends where the kernel's stroke does.
 */
class PlannedStepTest {
    private final BrushFixture f = new BrushFixture();

    @Test
    void itsPartsWriteExactlyWhatTheKernelWrites() {
        BrushSpec spec = HeightmapKernelTest.spec(32, HeightmapKernelCostTest.hill(65), 24, false);
        FakeWorld direct = f.flat(60);
        FakeWorld parted = f.flat(60);
        StrokeState directStroke = new StrokeState(), partedStroke = new StrokeState();
        for (int i = 0; i < 3; i++) {
            Dab dab = at(i, -8 + 8 * i, 61, 2);
            List<BrushFixture.Write> expected = BrushFixture.dab(spec, directStroke, direct, dab);
            String before = f.heights(parted, 0, 2, 40, 60);
            PlannedStep step = PlannedStep.of(BrushKernels.forTool(BrushTool.HEIGHTMAP), spec, List.of(dab), partedStroke,
                    parted);
            assertEquals(before, f.heights(parted, 0, 2, 40, 60), "working a step out writes nothing");
            assertEquals(expected.size(), step.cells());
            assertEquals((expected.size() + PlannedStep.PART_CELLS - 1) / PlannedStep.PART_CELLS, step.parts());
            if (i == 0) assertTrue(step.parts() > 1, "a big stamp takes several parts: " + step.cells());
            List<BrushFixture.Write> written = new ArrayList<>();
            while (!step.done()) {
                long cells = step.nextCells();
                assertTrue(cells <= PlannedStep.PART_CELLS);
                Box area = step.nextArea();
                int at = written.size();
                step.runNext(parted, (x, y, z, h) -> {
                    assertTrue(area.contains(x, y, z), "a part wrote outside its area");
                    written.add(new BrushFixture.Write(x, y, z, h));
                    parted.set(x, y, z, h);
                });
                assertEquals(cells, written.size() - at);
            }
            assertEquals(expected, written, "dab " + i);
            assertEquals(0, step.skipped());
        }
        assertEquals(f.heights(direct, 0, 2, 40, 60), f.heights(parted, 0, 2, 40, 60));
    }

    /**
     * A part's areas are one box per chunk column it writes in: a part crossing from a dab's area to its mirror image's
     * (mirror plane at x = 0, the stamps 40 blocks apart) covers nothing between them.
     */
    @Test
    void aPartsAreasLeaveTheGapBetweenCopiesOut() {
        FakeWorld world = f.flat(60);
        byte[] dot = new byte[25];
        dot[12] = (byte) 255;
        BrushSpec spec = HeightmapKernelTest.spec(2, dot, 8, false, SurfaceMask.ANY, null,
                new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0));
        PlannedStep step = PlannedStep.of(BrushKernels.forTool(BrushTool.HEIGHTMAP), spec,
                SymmetricStep.of(spec, at(0, 20, 61, 0), world).dabs(), new StrokeState(), world);
        assertEquals(1, step.parts());
        List<Box> areas = step.nextAreas();
        assertEquals(2, areas.size(), "one box per stamp: " + areas);
        for (Box area : areas) {
            assertTrue(area.max().x() - area.min().x() == 0, "only the stamped column: " + area);
            assertFalse(area.min().x() <= 0 && area.max().x() >= -1, "nothing on the mirror plane");
        }
        Box all = step.nextArea();
        assertEquals(-21, all.min().x());
        assertEquals(20, all.max().x());
    }

    @Test
    void positionsPackExactly() {
        for (int[] c : new int[][] {{0, 0, 0}, {-1, -64, -1}, {(1 << 25) - 1, 2047, -(1 << 25)}, {-30_000_000 / 16, 319, 12345}}) {
            long packed = PlannedStep.pack(c[0], c[1], c[2]);
            assertEquals(c[0], PlannedStep.x(packed));
            assertEquals(c[1], PlannedStep.y(packed));
            assertEquals(c[2], PlannedStep.z(packed));
        }
    }

    @Test
    void aCellChangedBetweenPartsIsLeftAlone() {
        BrushSpec spec = HeightmapKernelTest.spec(32, HeightmapKernelCostTest.hill(65), 24, false);
        FakeWorld world = f.flat(60);
        PlannedStep step = PlannedStep.of(BrushKernels.forTool(BrushTool.HEIGHTMAP), spec, List.of(at(0, 0, 61, 0)),
                new StrokeState(), world);
        assertTrue(step.parts() > 1);
        step.runNext(world, world::set);
        // Someone builds a stone pillar in the middle of the stamp before the next parts run.
        for (int y = 61; y <= 90; y++) world.set(20, y, 0, f.stone);
        world.set(20, 91, 0, f.stairs);
        List<int[]> rest = new ArrayList<>();
        step.runAll(world, (x, y, z, h) -> {
            rest.add(new int[] {x, y, z});
            world.set(x, y, z, h);
        });
        assertTrue(step.skipped() > 0, "the pillar's cells were left out");
        assertFalse(rest.stream().anyMatch(c -> c[0] == 20 && c[2] == 0 && c[1] >= 61), "nothing written over the pillar");
        assertEquals(f.stairs, world.get(20, 91, 0));
        assertThrows(IllegalStateException.class, () -> step.runNext(world, (x, y, z, h) -> {}));
        assertTrue(PlannedStep.of(BrushKernels.forTool(BrushTool.HEIGHTMAP), spec, List.of(at(0, 300, 61, 0)),
                new StrokeState(), world).done(), "a step over nothing has no parts");
    }
}
