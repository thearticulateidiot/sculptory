package dev.sculptory.fabric.client.editor.tools.select;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.Set;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class MagicSelectTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final FakeWorld world = new FakeWorld(states);
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int logY = states.state("minecraft:oak_log[axis=y]");
    private final int logX = states.state("minecraft:oak_log[axis=x]");

    private static MagicSelect run(MagicSelect fill) {
        int guard = 0;
        while (!fill.step(1000)) {
            if (++guard > 1_000_000) throw new AssertionError("The fill never finished");
        }
        return fill;
    }

    private MagicSelect fill(int x, int y, int z, MagicSelect.Match match, MagicSelect.Connect connect, long limit) {
        return run(new MagicSelect(world, new BlockPos(x, y, z), match, connect, limit));
    }

    private static Set<BlockPos> cells(MagicSelect fill) {
        Set<BlockPos> cells = CellSets.cells(fill.cells());
        assertEquals(fill.count(), fill.cells().size());
        return cells;
    }

    @Test
    void selectsTheConnectedBlocksOfTheClickedKind() {
        world.fill(new Box(new BlockPos(0, 0, 0), new BlockPos(4, 2, 4)), stone);
        world.set(10, 0, 0, stone); // same block, not connected
        world.fill(new Box(new BlockPos(0, 3, 0), new BlockPos(4, 3, 4)), dirt); // connected, another block

        MagicSelect fill = fill(2, 1, 2, MagicSelect.Match.SAME_BLOCK, MagicSelect.Connect.FACES, 100_000);

        assertEquals(75, fill.count());
        assertEquals(75, cells(fill).size());
        assertTrue(fill.contains(0, 0, 0));
        assertTrue(fill.contains(4, 2, 4));
        assertFalse(fill.contains(10, 0, 0));
        assertFalse(fill.contains(0, 3, 0));
        assertFalse(fill.hitLimit());
        assertFalse(fill.hitUnloaded());
    }

    @Test
    void sameBlockTakesEveryStateAndExactStateOnlyTheClickedOne() {
        world.set(0, 0, 0, logY);
        world.set(1, 0, 0, logX);
        world.set(2, 0, 0, logY);

        assertEquals(3, fill(0, 0, 0, MagicSelect.Match.SAME_BLOCK, MagicSelect.Connect.FACES, 100).count());
        MagicSelect exact = fill(0, 0, 0, MagicSelect.Match.EXACT_STATE, MagicSelect.Connect.FACES, 100);
        assertEquals(1, exact.count(), "the x log between them breaks the chain");
        assertTrue(exact.contains(0, 0, 0));
    }

    @Test
    void anyBlockCrossesKindsButNotAir() {
        world.set(0, 0, 0, stone);
        world.set(1, 0, 0, dirt);
        world.set(2, 0, 0, logX);
        world.set(4, 0, 0, stone); // one air gap

        MagicSelect fill = fill(0, 0, 0, MagicSelect.Match.ANY_BLOCK, MagicSelect.Connect.FACES, 100);

        assertEquals(3, fill.count());
        assertFalse(fill.contains(4, 0, 0));
    }

    @Test
    void facesDoNotConnectAcrossEdgesAndCornersButAllDoes() {
        world.set(0, 0, 0, stone);
        world.set(1, 1, 0, stone); // shares an edge
        world.set(2, 2, 1, stone); // shares a corner with the one above

        assertEquals(1, fill(0, 0, 0, MagicSelect.Match.SAME_BLOCK, MagicSelect.Connect.FACES, 100).count());
        MagicSelect all = fill(0, 0, 0, MagicSelect.Match.SAME_BLOCK, MagicSelect.Connect.DIAGONALS, 100);
        assertEquals(3, all.count());
        assertTrue(all.contains(2, 2, 1));
    }

    @Test
    void theLimitStopsTheFillAndSaysSo() {
        world.fill(new Box(new BlockPos(0, 0, 0), new BlockPos(9, 0, 9)), stone);

        MagicSelect limited = fill(0, 0, 0, MagicSelect.Match.SAME_BLOCK, MagicSelect.Connect.FACES, 40);
        assertEquals(40, limited.count());
        assertTrue(limited.hitLimit());

        MagicSelect exactFit = fill(0, 0, 0, MagicSelect.Match.SAME_BLOCK, MagicSelect.Connect.FACES, 100);
        assertEquals(100, exactFit.count());
        assertFalse(exactFit.hitLimit(), "exactly the limit, with nothing more connected, is not cut off");
    }

    @Test
    void theFillStopsAtUnloadedChunksWithoutReadingThem() {
        // A row of stone from chunk 0 into chunk 1, which the client doesn't have: reading it would throw.
        for (int x = 0; x < 32; x++) world.set(x, 0, 0, stone);
        world.setLoaded(1, 0, false);

        MagicSelect fill = fill(0, 0, 0, MagicSelect.Match.SAME_BLOCK, MagicSelect.Connect.FACES, 100);

        assertEquals(16, fill.count());
        assertTrue(fill.hitUnloaded());
        assertFalse(fill.contains(16, 0, 0));
    }

    @Test
    void aChunkUnloadedBetweenSlicesCountsAsUnloaded() {
        // A long row of stone through chunks 0 to 3; the fill has seen chunk 2 loaded before it unloads.
        for (int x = 0; x < 64; x++) world.set(x, 0, 0, stone);
        MagicSelect fill = new MagicSelect(world, BlockPos.ORIGIN, MagicSelect.Match.SAME_BLOCK,
                MagicSelect.Connect.FACES, 1000);
        fill.step(40 * 6); // about 40 cells in: into chunk 2
        assertTrue(fill.contains(33, 0, 0));
        assertFalse(fill.done());
        world.setLoaded(2, 0, false); // reading it now would throw
        world.setLoaded(3, 0, false);
        while (!fill.step(100)) {
            // the next slices must ask again
        }
        assertTrue(fill.hitUnloaded(), "reported, not read as air");
        assertFalse(fill.contains(63, 0, 0));
    }

    @Test
    void theFillStopsAtTheBuildHeight() {
        FakeWorld low = new FakeWorld(states, 0, 4);
        for (int y = 0; y < 4; y++) low.set(0, y, 0, stone);

        MagicSelect fill = run(new MagicSelect(low, new BlockPos(0, 1, 0), MagicSelect.Match.SAME_BLOCK,
                MagicSelect.Connect.FACES, 100));

        assertEquals(4, fill.count());
        assertFalse(fill.hitUnloaded());
    }

    @Test
    void airOrAnUnloadedOrOutOfWorldSeedSelectsNothing() {
        world.setLoaded(5, 5, false);
        assertEquals(0, fill(0, 0, 0, MagicSelect.Match.SAME_BLOCK, MagicSelect.Connect.FACES, 100).count());
        assertEquals(0, fill(0, 0, 0, MagicSelect.Match.ANY_BLOCK, MagicSelect.Connect.DIAGONALS, 100).count());
        assertEquals(0, fill(80, 0, 80, MagicSelect.Match.ANY_BLOCK, MagicSelect.Connect.DIAGONALS, 100).count());
        assertEquals(0, fill(0, 400, 0, MagicSelect.Match.ANY_BLOCK, MagicSelect.Connect.DIAGONALS, 100).count());
        MagicSelect air = new MagicSelect(world, BlockPos.ORIGIN, MagicSelect.Match.SAME_BLOCK,
                MagicSelect.Connect.FACES, 100);
        assertTrue(air.done(), "nothing to do: done at once");
    }

    @Test
    void stepsAreBoundedSoALargeFillSpansManySteps() {
        world.fill(new Box(new BlockPos(0, 0, 0), new BlockPos(63, 3, 63)), stone);
        MagicSelect fill = new MagicSelect(world, BlockPos.ORIGIN, MagicSelect.Match.SAME_BLOCK,
                MagicSelect.Connect.FACES, 1_000_000);

        int steps = 0;
        long lastExamined = 0;
        while (!fill.step(500)) {
            steps++;
            // Each step examines at most the budget plus the rest of one cell's neighbours.
            assertTrue(fill.examined() - lastExamined <= 500 + 6);
            lastExamined = fill.examined();
        }
        assertTrue(steps > 50, "a 16k-block fill takes many 500-cell steps: " + steps);
        assertEquals(64 * 4 * 64, fill.count());
    }

    @Test
    void negativeCoordinatesAndSectionBordersRoundTrip() {
        Box box = new Box(new BlockPos(-20, -64, -20), new BlockPos(-10, -60, 3));
        world.fill(box, dirt);

        MagicSelect fill = fill(-15, -62, 0, MagicSelect.Match.SAME_BLOCK, MagicSelect.Connect.DIAGONALS, 100_000);

        assertEquals(box.volume(), fill.count());
        Set<BlockPos> cells = cells(fill);
        assertEquals(box.volume(), cells.size());
        for (BlockPos cell : cells) assertTrue(box.contains(cell), cell.toString());
    }

    @Test
    void theLimitMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new MagicSelect(world, BlockPos.ORIGIN,
                MagicSelect.Match.SAME_BLOCK, MagicSelect.Connect.FACES, 0));
    }

    @Test
    void aJobRunsItsFillWithinTheFrameBudget() {
        world.fill(new Box(new BlockPos(0, 0, 0), new BlockPos(63, 3, 63)), stone);
        MagicJob job = new MagicJob(new MagicSelect(world, BlockPos.ORIGIN, MagicSelect.Match.SAME_BLOCK,
                MagicSelect.Connect.FACES, 1_000_000), MagicJob.Combine.ADD);
        long[] now = {0};
        // Each clock read is 0.5 ms later: a 2 ms budget runs four batches, then yields.
        LongSupplier clock = () -> now[0] += 500_000;
        assertFalse(job.run(MagicJob.FRAME_BUDGET_NANOS, clock));
        long examined = job.fill().examined();
        assertTrue(examined >= 4 * MagicJob.BATCH && examined <= 4 * (MagicJob.BATCH + 6), "examined " + examined);

        int frames = 1;
        while (!job.run(MagicJob.FRAME_BUDGET_NANOS, clock)) frames++;
        assertEquals(64 * 4 * 64, job.fill().count());
        assertTrue(frames > 10, "a 16k-block fill spans frames: " + frames);
        assertEquals(MagicJob.Combine.ADD, job.combine());
    }

    @Test
    void aJobAlwaysRunsAtLeastOneBatch() {
        world.set(0, 0, 0, stone);
        MagicJob job = new MagicJob(new MagicSelect(world, BlockPos.ORIGIN, MagicSelect.Match.SAME_BLOCK,
                MagicSelect.Connect.FACES, 10), MagicJob.Combine.REPLACE);
        assertTrue(job.run(0, () -> 0), "a zero budget still takes one batch, which finishes a small fill");
        assertEquals(1, job.fill().count());
    }
}
