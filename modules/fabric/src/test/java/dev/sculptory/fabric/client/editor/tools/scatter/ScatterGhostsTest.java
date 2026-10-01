package dev.sculptory.fabric.client.editor.tools.scatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.render.ghost.GhostMapping;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.tools.place.GhostBaker;
import dev.sculptory.fabric.client.session.ClipboardCache;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Decoded placements feeding the ghost list: shared volumes, paste geometry, bakes. */
class ScatterGhostsTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final List<Runnable> backgroundTasks = new ArrayList<>();
    private final List<GhostVolume> released = new ArrayList<>();
    private final ScatterGhosts ghosts = new ScatterGhosts(backgroundTasks::add, released::add);

    /** A 3×2×5 tree-ish source anchored at (1, 0, 2), with a stair so bakes turn states. */
    private ClipboardCache.Preview preview(String key) {
        BlockPos dims = new BlockPos(3, 2, 5);
        BlockBuffer cells = new BlockBuffer();
        cells.set(0, 0, 0, states.state("minecraft:oak_stairs[facing=north]"));
        cells.set(1, 1, 2, states.state("minecraft:stone"));
        GhostVolume volume = GhostVolume.of(cells, GhostBaker.air(states));
        volume.setFrame(new Box(BlockPos.ORIGIN, dims.offset(-1, -1, -1)));
        return new ClipboardCache.Preview(key, dims, new BlockPos(1, 0, 2), 2, volume, 1024, 0);
    }

    private static ScatterPlan.Placement at(int x, int y, int z, int variant, Transform transform) {
        return new ScatterPlan.Placement(new BlockPos(x, y, z), variant, transform);
    }

    private void runBackground() {
        List<Runnable> tasks = List.copyOf(backgroundTasks);
        backgroundTasks.clear();
        tasks.forEach(Runnable::run);
    }

    @Test
    void theVariantsAnchorLandsOnThePlacementsAnchor() {
        ghosts.set(List.of(at(10, 64, 20, 0, Transform.IDENTITY), at(10, 64, 20, 0, Transform.rotation(1))),
                List.of(preview("a")));
        assertEquals(new Box(new BlockPos(9, 64, 18), new BlockPos(11, 65, 22)), ghosts.footprint(0));
        // A quarter turn: the transformed anchor (mapX = 5-1-2 = 2, mapZ = 1) lands on (10, 64, 20).
        assertEquals(new Box(new BlockPos(8, 64, 19), new BlockPos(12, 65, 21)), ghosts.footprint(1));
        GhostMapping mapping = ghosts.ghost(1).mapping();
        assertEquals(new BlockPos(10, 64, 20), new BlockPos(mapping.worldX(1, 2), mapping.worldY(0), mapping.worldZ(1, 2)),
                "the ghost puts the source's anchor cell on the placement's anchor");
        assertEquals(2, ghosts.centres().length / 3);
        assertEquals(10.5, ghosts.centres()[0]);
    }

    @Test
    void placementsOfAVariantShareItsVolumeAndDrawWithTheirOwnModelMatrix() {
        ClipboardCache.Preview a = preview("a");
        ClipboardCache.Preview b = preview("b");
        ghosts.set(List.of(at(0, 64, 0, 0, Transform.IDENTITY), at(30, 64, 0, 1, Transform.IDENTITY),
                at(60, 64, 0, 0, new Transform(1, Mirror.X))), List.of(a, b));
        GhostPlacement first = ghosts.ghost(0);
        GhostPlacement third = ghosts.ghost(2);
        assertSame(a.volume(), first.volume());
        assertSame(b.volume(), ghosts.ghost(1).volume());
        assertSame(a.volume(), third.volume(), "until baked, a turned placement draws the shared volume turned");
        assertEquals(new Transform(1, Mirror.X), third.transform());
        assertFalse(ghosts.bakedFor(2));
        assertTrue(ghosts.bakedFor(0), "untransformed placements show their final states");
        assertEquals(1, ghosts.draws()[0]);
    }

    @Test
    void eachVolumeAndTransformIsBakedOnceAndSharedByItsPlacements() {
        ClipboardCache.Preview a = preview("a");
        Transform half = Transform.rotation(2);
        ghosts.set(List.of(at(0, 64, 0, 0, half), at(40, 64, 0, 0, half), at(80, 64, 0, 0, Transform.rotation(1))),
                List.of(a));
        ghosts.updateBakes(List.of(0, 1, 2), states);
        assertEquals(2, ghosts.bakesInFlight(), "one bake per (volume, transform) pair");
        runBackground();
        ghosts.updateBakes(List.of(0, 1, 2), states);
        GhostVolume baked = ghosts.ghost(0).volume();
        assertNotSame(a.volume(), baked);
        assertSame(baked, ghosts.ghost(1).volume(), "shared by both half-turned placements");
        assertEquals(Transform.IDENTITY, ghosts.ghost(0).transform(), "a baked volume draws untransformed");
        assertEquals(ghosts.footprint(0).min(), new BlockPos(ghosts.ghost(0).originX(), ghosts.ghost(0).originY(),
                ghosts.ghost(0).originZ()), "at the same place");
        assertTrue(ghosts.bakedFor(2));
        int stair = states.state("minecraft:oak_stairs[facing=north]");
        assertEquals(states.rotate(stair, 2), baked.handle(2, 0, 4), "the stair turned with the placement");
    }

    @Test
    void atMostTwoBakesRunAtOnce() {
        ghosts.set(List.of(at(0, 64, 0, 0, Transform.rotation(1)), at(0, 64, 9, 0, Transform.rotation(2)),
                at(0, 64, 18, 0, Transform.rotation(3))), List.of(preview("a")));
        ghosts.updateBakes(List.of(0, 1, 2), states);
        assertEquals(ScatterGhosts.MAX_BAKES_IN_FLIGHT, ghosts.bakesInFlight());
        runBackground();
        ghosts.updateBakes(List.of(0, 1, 2), states);
        assertEquals(1, ghosts.bakesInFlight(), "the third starts once the first two are in");
    }

    @Test
    void bakesAreKeptForTheSameVariantsAndReleasedWhenTheirSourceLeaves() {
        ClipboardCache.Preview a = preview("a");
        ghosts.set(List.of(at(0, 64, 0, 0, Transform.rotation(1))), List.of(a));
        ghosts.updateBakes(List.of(0), states);
        runBackground();
        ghosts.updateBakes(List.of(0), states);
        GhostVolume baked = ghosts.ghost(0).volume();
        // A re-rolled plan of the same variant reuses the bake (and so its meshes).
        ghosts.set(List.of(at(5, 64, 5, 0, Transform.rotation(1))), List.of(a));
        assertSame(baked, ghosts.ghost(0).volume());
        assertTrue(released.isEmpty());
        ghosts.set(List.of(at(5, 64, 5, 0, Transform.rotation(1))), List.of(preview("other")));
        assertEquals(List.of(baked), released);
        ghosts.clear();
        assertEquals(0, ghosts.size());
    }

    /** The seven transforms other than the identity. */
    private static List<Transform> turns() {
        List<Transform> turns = new ArrayList<>();
        for (int k = 1; k < 4; k++) turns.add(Transform.rotation(k));
        for (int k = 0; k < 4; k++) turns.add(new Transform(k, Mirror.X));
        return turns;
    }

    /**
     * B1: at most {@code MAX_BAKES} baked volumes are kept. While every kept one is drawn no new bake starts; once some
     * are no longer drawn, the least recently drawn make room and are released.
     */
    @Test
    void theBakesKeptAreCappedAndTheLeastRecentlyDrawnMakeRoom() {
        List<ClipboardCache.Preview> previews = List.of(preview("a"), preview("b"), preview("c"), preview("d"), preview("e"));
        List<ScatterPlan.Placement> placements = new ArrayList<>();
        for (int v = 0; v < previews.size(); v++) {
            for (Transform t : turns()) placements.add(at(placements.size() * 8, 64, 0, v, t));
        }
        assertTrue(placements.size() > ScatterGhosts.MAX_BAKES);
        ghosts.set(placements, previews);
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < placements.size(); i++) all.add(i);
        for (int round = 0; round < 100; round++) {
            ghosts.updateBakes(all, states);
            if (ghosts.bakesInFlight() == 0) break;
            runBackground();
        }
        assertEquals(ScatterGhosts.MAX_BAKES, ghosts.bakeCount());
        assertTrue(released.isEmpty(), "every kept bake is drawn: none is released to bake another");

        List<Integer> unbaked = new ArrayList<>();
        for (int i = 0; i < placements.size(); i++) {
            if (!ghosts.bakedFor(i)) unbaked.add(i);
        }
        assertEquals(placements.size() - ScatterGhosts.MAX_BAKES, unbaked.size());
        for (int round = 0; round < 100; round++) {
            ghosts.updateBakes(unbaked, states);
            if (ghosts.bakesInFlight() == 0 && unbaked.stream().allMatch(ghosts::bakedFor)) break;
            runBackground();
        }
        assertTrue(unbaked.stream().allMatch(ghosts::bakedFor), "the drawn placements got their bakes");
        assertEquals(ScatterGhosts.MAX_BAKES, ghosts.bakeCount());
        assertEquals(unbaked.size(), released.size(), "one released per new bake");
        assertFalse(ghosts.bakedFor(0), "the least recently drawn bake made room");
        assertTrue(ghosts.bakedFor(unbaked.get(0) - 1), "the most recently drawn bakes are kept");
    }

    /**
     * B5: a bake that fails is not rethrown: its placements are drawn as outlines (no ghost), and it is not retried until
     * the plan changes.
     */
    @Test
    void aFailedBakeIsDrawnAsAnOutlineAndNotRetried() {
        dev.sculptory.fabric.client.editor.tools.place.FailingStates failing =
                new dev.sculptory.fabric.client.editor.tools.place.FailingStates(states);
        failing.failing = true;
        ClipboardCache.Preview a = preview("a");
        ghosts.set(List.of(at(0, 64, 0, 0, Transform.rotation(1)), at(9, 64, 0, 0, Transform.IDENTITY)), List.of(a));
        ghosts.updateBakes(List.of(0, 1), failing);
        assertEquals(1, ghosts.bakesInFlight());
        runBackground(); // the bake throws on the background thread
        ghosts.updateBakes(List.of(0, 1), failing);
        assertEquals(0, ghosts.bakesInFlight());
        assertTrue(ghosts.bakeFailed(0));
        assertNull(ghosts.ghost(0), "no ghost: an outline instead");
        assertFalse(ghosts.bakeFailed(1));
        assertSame(a.volume(), ghosts.ghost(1).volume(), "untransformed placements are unaffected");
        ghosts.updateBakes(List.of(0, 1), failing);
        assertEquals(0, ghosts.bakesInFlight(), "not retried");

        failing.failing = false;
        ghosts.set(List.of(at(0, 64, 0, 0, Transform.rotation(1))), List.of(a));
        assertFalse(ghosts.bakeFailed(0), "a new plan tries again");
        ghosts.updateBakes(List.of(0), failing);
        runBackground();
        ghosts.updateBakes(List.of(0), failing);
        assertTrue(ghosts.bakedFor(0));
    }

    @Test
    void aVariantWithoutAPreviewIsADotAtItsAnchor() {
        List<ClipboardCache.Preview> previews = new ArrayList<>(Arrays.asList(preview("a"), null));
        ghosts.set(List.of(at(3, 70, 4, 1, Transform.IDENTITY)), previews);
        assertNull(ghosts.footprint(0));
        assertNull(ghosts.ghost(0));
        assertEquals(-1, ghosts.draws()[0]);
        assertEquals(new BlockPos(3, 70, 4), ghosts.anchor(0));
        assertEquals(3.5, ghosts.centres()[0]);
    }
}
