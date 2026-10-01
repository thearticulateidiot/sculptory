package dev.sculptory.fabric.client.editor.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.RegionTooLargeException;
import dev.sculptory.core.region.ShapeKind;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class RegionWorkTest {
    private final List<Runnable> background = new ArrayList<>();
    private final List<Runnable> client = new ArrayList<>();
    private final RegionWork work = new RegionWork(background::add, client::add);

    /** A shape past the inline row count (200 × 200 rows). */
    private static Region.Shape big(int size) {
        return new Region.Shape(new Box(BlockPos.ORIGIN, new BlockPos(size - 1, size - 1, size - 1)), ShapeKind.ELLIPSOID,
                Facing.UP);
    }

    private void runBackground() {
        while (!background.isEmpty()) background.remove(0).run();
    }

    private void runClient() {
        while (!client.isEmpty()) client.remove(0).run();
    }

    @Test
    void boxesCellSetsAndSmallShapesAreCountedAtOnce() {
        Box box = new Box(BlockPos.ORIGIN, new BlockPos(9, 9, 9));
        assertEquals(OptionalLong.of(1000), work.countNow(new Region.Cuboid(box)));
        assertEquals(OptionalLong.of(2), work.countNow(new Region.Cells(CellSet.builder().add(0, 0, 0).add(5, 5, 5).build())));
        Region.Shape small = new Region.Shape(box, ShapeKind.CONE, Facing.UP);
        assertEquals(OptionalLong.of(small.cellCount()), work.countNow(small));
        assertTrue(background.isEmpty(), "nothing went to the background");
    }

    @Test
    void aLargeShapeIsCountedOnceInTheBackground() {
        Region.Shape shape = big(200);
        for (int frame = 0; frame < 5; frame++) {
            assertEquals(OptionalLong.empty(), work.countNow(shape), "the client thread never counts it");
        }
        assertEquals(1, background.size(), "one count, however often it is asked for");
        runBackground();
        assertEquals(OptionalLong.empty(), work.countNow(shape), "known only once the result is back");
        runClient();
        assertEquals(OptionalLong.of(big(200).cellCount()), work.countNow(shape));
        assertEquals(OptionalLong.of(big(200).cellCount()), work.countNow(big(200)), "remembered by value");
        assertTrue(background.isEmpty());
    }

    @Test
    void shapesAskedForMeanwhileCoalesceToTheLatest() {
        work.countNow(big(200));
        for (int size = 201; size < 220; size++) work.countNow(big(size)); // a drag: a new shape every frame
        assertEquals(1, background.size());
        runBackground();
        runClient(); // the first count lands; the latest is counted next, not the ones in between
        assertEquals(1, background.size());
        runBackground();
        runClient();
        assertTrue(background.isEmpty());
        assertTrue(work.countNow(big(219)).isPresent());
        assertEquals(OptionalLong.empty(), work.countNow(big(210)), "never counted");
    }

    @Test
    void countsSomeoneWaitsForComeFirstAndLandOnTheClientThread() {
        work.countNow(big(210));
        CompletableFuture<Long> count = work.count(big(200)).toCompletableFuture();
        assertFalse(count.isDone());
        runBackground(); // big(210), already running
        runClient();
        assertFalse(count.isDone());
        runBackground(); // big(200), waited for, before the shown shape
        assertFalse(count.isDone(), "the result is delivered on the client thread");
        runClient();
        assertEquals(big(200).cellCount(), count.join());
        assertTrue(work.count(big(200)).toCompletableFuture().isDone(), "known now");
    }

    @Test
    void aShapeTooLargeToCountIsNeverCounted() {
        // Counted along the longest side, as the server counts: in a box of more than 2^29 cells at most 2^20 rows (the
        // most the server takes from anyone), so 30,000³ and 4,000³ are not counted, a 2,000 × 200 × 2,000 disc is.
        Region.Shape huge = new Region.Shape(new Box(BlockPos.ORIGIN, new BlockPos(29_999, 29_999, 29_999)),
                ShapeKind.ELLIPSOID, Facing.UP);
        assertFalse(RegionWork.countable(huge));
        assertEquals(OptionalLong.empty(), work.countNow(huge));
        assertTrue(background.isEmpty());
        CompletionException failed = org.junit.jupiter.api.Assertions.assertThrows(CompletionException.class,
                () -> work.count(huge).toCompletableFuture().join());
        assertInstanceOf(RegionTooLargeException.class, failed.getCause());
        assertFalse(RegionWork.countable(big(4000)));
        assertTrue(RegionWork.countable(new Region.Shape(new Box(BlockPos.ORIGIN, new BlockPos(1999, 199, 1999)),
                ShapeKind.CYLINDER, Facing.UP)));
        assertTrue(RegionWork.countable(big(812)), "a box of at most 2^29 cells always counts");
        assertEquals(huge.bounds().volume(), RegionWork.atMost(huge));
    }

    /**
     * A thin shape is cheap along its longest side: a 2 × 1,000 × 1,000 disc is 2,000 rows (a million along x), so it is
     * counted at once, exactly as the shape's own count.
     */
    @Test
    void aThinShapeIsCountedAtOnceAlongItsLongestSide() {
        Region.Shape disc = new Region.Shape(new Box(BlockPos.ORIGIN, new BlockPos(1, 999, 999)), ShapeKind.CYLINDER,
                Facing.EAST);
        assertEquals(OptionalLong.of(new Region.Shape(disc.box(), disc.kind(), disc.facing()).cellCount()),
                work.countNow(disc));
        assertTrue(background.isEmpty(), "nothing went to the background");
    }

    @Test
    void workRunsInTheBackgroundAndAnswersOnTheClientThread() {
        CompletableFuture<String> done = work.supply(() -> "cells").toCompletableFuture();
        CompletableFuture<String> failed = work.<String>supply(() -> {
            throw new IllegalStateException("no");
        }).toCompletableFuture();
        runBackground();
        assertFalse(done.isDone());
        runClient();
        assertEquals("cells", done.join());
        assertTrue(failed.isCompletedExceptionally());
    }

    @Test
    void directWorkIsDoneAtOnce() {
        RegionWork direct = RegionWork.direct();
        assertEquals(OptionalLong.of(big(200).cellCount()), direct.countNow(big(200)));
        assertEquals(big(210).cellCount(), direct.count(big(210)).toCompletableFuture().join());
    }
}
