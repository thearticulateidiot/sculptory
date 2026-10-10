package dev.sculptory.core.brush;

import static dev.sculptory.core.brush.BrushFixture.at;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.mask.MaskedKernel;
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
            int parts = step.parts();
            assertTrue(parts <= (expected.size() + PlannedStep.PART_CELLS - 1) / PlannedStep.PART_CELLS, "parts " + parts);
            if (i == 0) assertTrue(step.parts() > 1, "a big stamp takes several parts: " + step.cells());
            List<BrushFixture.Write> written = new ArrayList<>();
            while (!step.done()) {
                long cells = step.nextCells();
                // A part runs on to the end of its last column: a few hundred cells at most past the part size.
                assertTrue(cells <= PlannedStep.PART_CELLS + 260, "a part of " + cells);
                parts--;
                Box area = step.nextArea();
                int at = written.size();
                step.runNext(parted, (x, y, z, h) -> {
                    assertTrue(area.contains(x, y, z), "a part wrote outside its area");
                    written.add(new BrushFixture.Write(x, y, z, h));
                    parted.set(x, y, z, h);
                });
                assertEquals(cells, written.size() - at);
            }
            assertEquals(0, parts, "parts() counted the parts run");
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

    /**
     * Rough ground 281 columns across, stone from the fixture's floor under grass between 58 and 65, short grass on every
     * third column, and a pond (ground at 52, water up to 60) east of the middle: room for radius-128 stamps, their
     * copies, and a cut that refills with water.
     */
    private FakeWorld wideGround() {
        FakeWorld world = new FakeWorld(f.states);
        for (int x = -140; x <= 140; x++) {
            for (int z = -140; z <= 140; z++) {
                boolean pond = x >= 30 && x <= 45 && z >= -10 && z <= 10;
                int top = pond ? 52 : 58 + Math.floorMod(x / 9 + z / 13, 8);
                for (int y = BrushFixture.FLOOR; y <= top; y++) world.set(x, y, z, y < top ? f.stone : f.grass);
                if (pond) {
                    for (int y = top + 1; y <= 60; y++) world.set(x, y, z, f.water);
                } else if (Math.floorMod(x + z, 3) == 0) {
                    world.set(x, top + 1, z, f.shortGrass);
                }
            }
        }
        return world;
    }

    /** {@code step}'s parts run on {@code world}, written through; the cells in the order written. */
    private static List<BrushFixture.Write> runAll(PlannedStep step, FakeWorld world) {
        List<BrushFixture.Write> written = new ArrayList<>();
        step.runAll(world, (x, y, z, h) -> {
            written.add(new BrushFixture.Write(x, y, z, h));
            world.set(x, y, z, h);
        });
        return written;
    }

    /** Works {@code planner} out with a deadline already past each call (the least work a call does); the calls made. */
    private static int workOut(PlannedStep.Planner planner) {
        int calls = 1;
        while (!planner.advance(Long.MIN_VALUE)) calls++;
        return calls;
    }

    /**
     * At radius 128, the planner worked out a few dozen columns at a time keeps exactly the cells the kernel writes
     * whole, in the same order: a stroke of three dabs mirrored across x = 0 (the copies overlapping), a 40-deep cut
     * through the pond's bank refilled with water, a 90-tall stamp, all inside a clip box, each step written before the
     * next is worked out.
     */
    @Test
    void thePlannerKeepsWhatTheKernelWritesWholeHoweverItIsCut() {
        Box clip = new Box(new BlockPos(-120, 40, -125), new BlockPos(130, 200, 110));
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0);
        for (BrushSpec spec : List.of(
                HeightmapKernelTest.spec(128, HeightmapKernelCostTest.hill(257), 40, true, SurfaceMask.ANY, clip, mirror),
                HeightmapKernelTest.spec(100, HeightmapKernelCostTest.hill(201), 90, false, SurfaceMask.ANY, clip,
                        Symmetry.NONE))) {
            FakeWorld whole = wideGround(), pieces = wideGround();
            StrokeState wholeStroke = new StrokeState(), piecesStroke = new StrokeState();
            for (int i = 0; i < 3; i++) {
                Dab dab = at(i, 8 + 6 * i, 66, -4 + 3 * i);
                List<Dab> dabs = SymmetricStep.of(spec, dab, whole).dabs();
                PlannedStep expected = PlannedStep.of(BrushKernels.forTool(BrushTool.HEIGHTMAP), spec, dabs, wholeStroke,
                        whole);
                PlannedStep.Planner planner = PlannedStep.planner(BrushKernels.forTool(BrushTool.HEIGHTMAP), spec, dabs,
                        piecesStroke, pieces, null);
                int calls = workOut(planner);
                assertTrue(calls > 100, "worked out in " + calls + " pieces");
                assertEquals(expected.cells(), planner.step().cells(), "dab " + i);
                assertEquals(runAll(expected, whole), runAll(planner.step(), pieces), "dab " + i);
            }
            if (spec.heightmap().lower()) {
                assertEquals(f.water, whole.get(29, 58, 0), "the cut beside the pond filled with water");
            }
        }
    }

    /**
     * With the global mask on, the planner judges the cells it keeps as {@code MaskedKernel} does (against the world
     * before the step): the same cells as the masked kernel's step worked out whole.
     */
    @Test
    void thePlannerJudgesTheGlobalMaskAsTheMaskedKernelDoes() {
        BoundMask mask = new EditMask(List.of(MaskEntry.of(new MaskRule.Is(BlockSet.parse("minecraft:stone")))), true)
                .bind(f.states);
        BrushSpec spec = HeightmapKernelTest.spec(64, HeightmapKernelCostTest.hill(129), 24, true);
        FakeWorld whole = wideGround(), pieces = wideGround();
        Dab dab = at(0, 30, 66, 0);
        PlannedStep expected = PlannedStep.of(MaskedKernel.wrap(
                BrushKernels.forTool(BrushTool.HEIGHTMAP), mask), spec, List.of(dab), new StrokeState(), whole);
        PlannedStep.Planner planner = PlannedStep.planner(BrushKernels.forTool(BrushTool.HEIGHTMAP), spec, List.of(dab),
                new StrokeState(), pieces, MaskedKernel.judge(mask, pieces));
        workOut(planner);
        PlannedStep unmasked = PlannedStep.of(BrushKernels.forTool(BrushTool.HEIGHTMAP), spec, List.of(dab),
                new StrokeState(), wideGround());
        assertTrue(expected.cells() > 0 && expected.cells() < unmasked.cells(), "the mask keeps some cells: "
                + expected.cells() + " of " + unmasked.cells());
        assertEquals(runAll(expected, whole), runAll(planner.step(), pieces));
    }

    /**
     * A step that plans more cells than its kernel allows stops there ({@link StepTooLargeException}), whether worked out
     * whole or in pieces; nothing of it is written. The Heightmap brush's cap holds its largest stamp.
     */
    @Test
    void aStepPastTheCapIsRefusedWhileWorkedOut() {
        BrushSpec spec = HeightmapKernelTest.spec(32, HeightmapKernelCostTest.hill(65), 24, false);
        FakeWorld world = f.flat(60);
        String before = f.heights(world, 0, 0, 34, 60);
        long whole = PlannedStep.of(BrushKernels.forTool(BrushTool.HEIGHTMAP), spec, List.of(at(0, 0, 61, 0)),
                new StrokeState(), world).cells();
        assertTrue(whole > 1_000, "the step plans " + whole + " cells");
        for (long deadline : new long[] {Long.MAX_VALUE, Long.MIN_VALUE}) {
            // Whole (one call, no deadline) and in pieces (a few dozen columns a call).
            HeightmapKernel.Pass pass = HeightmapKernel.start(spec, List.of(at(0, 0, 61, 0)), new StrokeState(), world);
            pass.limit(1_000);
            StepTooLargeException e = assertThrows(StepTooLargeException.class, () -> {
                while (!pass.advance(deadline)) {
                    // the next piece
                }
            }, deadline == Long.MAX_VALUE ? "whole" : "in pieces");
            assertEquals(1_000, e.limit());
            PlannedStep.Planner planner = PlannedStep.planner(BrushKernels.forTool(BrushTool.HEIGHTMAP), spec,
                    List.of(at(0, 0, 61, 0)), new StrokeState(), world, null, whole / 2);
            assertThrows(StepTooLargeException.class, () -> {
                while (!planner.advance(deadline)) {
                    // the next piece
                }
            }, "over the planner's limit");
            PlannedStep.Planner fits = PlannedStep.planner(BrushKernels.forTool(BrushTool.HEIGHTMAP), spec,
                    List.of(at(0, 0, 61, 0)), new StrokeState(), world, null, 4 * whole);
            while (!fits.advance(deadline)) {
                // the next piece
            }
            assertEquals(whole, fits.step().cells(), "within the limit");
        }
        assertEquals(before, f.heights(world, 0, 0, 34, 60), "nothing written");
        assertEquals(257L * 257 * 131, HeightmapSpec.MAX_STEP_CELLS);
    }

    /**
     * A column whose top block someone replaced between parts is left whole: nothing of it is raised (no block floats
     * over the change), its cells all count as skipped, and the columns around it rise as planned.
     */
    @Test
    void aColumnChangedBetweenPartsIsLeftWhole() {
        BrushSpec spec = HeightmapKernelTest.spec(32, HeightmapKernelCostTest.hill(65), 24, false);
        FakeWorld world = f.flat(60);
        PlannedStep step = PlannedStep.of(BrushKernels.forTool(BrushTool.HEIGHTMAP), spec, List.of(at(0, 0, 61, 0)),
                new StrokeState(), world);
        step.runNext(world, world::set);
        world.set(20, 60, 0, f.sand);
        List<int[]> rest = new ArrayList<>();
        step.runAll(world, (x, y, z, h) -> {
            rest.add(new int[] {x, y, z});
            world.set(x, y, z, h);
        });
        assertFalse(rest.stream().anyMatch(c -> c[0] == 20 && c[2] == 0), "nothing of the changed column written");
        assertEquals(f.sand, world.get(20, 60, 0));
        assertEquals(f.air, world.get(20, 61, 0), "nothing floats over the change");
        assertTrue(step.skipped() > 5, "the whole column skipped: " + step.skipped());
        assertTrue(f.surface(world, 21, 0) > 70 && f.surface(world, 19, 0) > 70, "its neighbours rose");
    }

    /**
     * The parts let go of the pages they have written, and a step worked out in pieces keeps only the pages its kept
     * cells take (a cut through the air under thin ground plans many cells that change nothing).
     */
    @Test
    void writtenAndDroppedPagesAreLetGo() {
        BrushSpec tall = HeightmapKernelTest.spec(32, HeightmapKernelCostTest.hill(65), 128, false);
        FakeWorld world = f.flat(60);
        PlannedStep step = PlannedStep.of(BrushKernels.forTool(BrushTool.HEIGHTMAP), tall, List.of(at(0, 0, 61, 0)),
                new StrokeState(), world);
        int pages = step.heldPages();
        assertTrue(pages >= 4, "pages " + pages);
        int lowest = pages;
        while (!step.done()) {
            step.runNext(world, world::set);
            int held = step.heldPages();
            assertTrue(held <= (int) ((step.remainingCells() + CellPlan.PAGE - 1) / CellPlan.PAGE) + 1,
                    "held " + held + " with " + step.remainingCells() + " cells left");
            lowest = Math.min(lowest, held);
        }
        assertEquals(0, step.heldPages());

        // A 128-deep cut of ground 11 blocks thick: the cells below it are air already and are dropped.
        BrushSpec deep = HeightmapKernelTest.spec(32, HeightmapKernelCostTest.hill(65), 128, true);
        FakeWorld thin = f.flat(60);
        PlannedStep.Planner planner = PlannedStep.planner(BrushKernels.forTool(BrushTool.HEIGHTMAP), deep,
                List.of(at(0, 0, 61, 0)), new StrokeState(), thin, null);
        while (!planner.advance(Long.MIN_VALUE)) {
            // the next piece
        }
        PlannedStep cut = planner.step();
        assertEquals((int) ((cut.cells() + CellPlan.PAGE - 1) / CellPlan.PAGE), cut.heldPages(),
                "only the kept cells' pages: " + cut.cells() + " cells");
    }
}
