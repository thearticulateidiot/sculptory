package dev.sculptory.fabric.client.editor.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class BoxHandlesTest {
    private static final Aabb BOX = new Aabb(0, 0, 0, 4, 4, 4);

    private static double[] sizes(double half) {
        double[] sizes = new double[6];
        Arrays.fill(sizes, half);
        return sizes;
    }

    @Test
    void entryFaceFromEverySide() {
        assertEntry(new Ray(-5, 2, 1, 1, 0, 0), BoxFace.WEST, 5, BoxFace.EAST, 9);
        assertEntry(new Ray(10, 2, 1, -1, 0, 0), BoxFace.EAST, 6, BoxFace.WEST, 10);
        assertEntry(new Ray(2, 10, 2, 0, -1, 0), BoxFace.UP, 6, BoxFace.DOWN, 10);
        assertEntry(new Ray(2, -3, 2, 0, 1, 0), BoxFace.DOWN, 3, BoxFace.UP, 7);
        assertEntry(new Ray(1, 1, -6, 0, 0, 1), BoxFace.NORTH, 6, BoxFace.SOUTH, 10);
        assertEntry(new Ray(1, 1, 9, 0, 0, -1), BoxFace.SOUTH, 5, BoxFace.NORTH, 9);
    }

    private static void assertEntry(Ray ray, BoxFace entry, double tEnter, BoxFace exit, double tExit) {
        BoxHandles.SlabHit hit = BoxHandles.intersect(ray, BOX).orElseThrow();
        assertEquals(entry, hit.entryFace());
        assertEquals(tEnter, hit.tEnter(), 1e-9);
        assertEquals(exit, hit.exitFace());
        assertEquals(tExit, hit.tExit(), 1e-9);
        assertFalse(hit.originInside());
    }

    @Test
    void diagonalRayEntersThroughTheLastSlabCrossed() {
        // From above and to the west: crosses x = 0 at t·(1/√2) = 3, y = 4 at t·(1/√2) = 2, so enters west.
        Ray ray = new Ray(-3, 6, 2, 1, -1, 0);
        BoxHandles.SlabHit hit = BoxHandles.intersect(ray, BOX).orElseThrow();
        assertEquals(BoxFace.WEST, hit.entryFace());
        assertEquals(3 * Math.sqrt(2), hit.tEnter(), 1e-9);
    }

    @Test
    void missesParallelRaysOutsideAndBoxesBehind() {
        assertTrue(BoxHandles.intersect(new Ray(-5, 10, 1, 1, 0, 0), BOX).isEmpty(), "passes above");
        assertTrue(BoxHandles.intersect(new Ray(-5, 2, 1, -1, 0, 0), BOX).isEmpty(), "points away");
        assertTrue(BoxHandles.intersect(new Ray(-5, 2, 1, 1, 1, 0), BOX).isEmpty(), "slides over the edge");
        assertTrue(BoxHandles.pick(new Ray(-5, 10, 1, 1, 0, 0), BOX, sizes(0.25)).isEmpty());
    }

    @Test
    void rayFromInsideReportsTheExitFace() {
        Ray ray = new Ray(1, 1, 1, 1, 0, 0);
        BoxHandles.SlabHit hit = BoxHandles.intersect(ray, BOX).orElseThrow();
        assertTrue(hit.originInside());
        assertEquals(BoxFace.EAST, hit.exitFace());

        BoxHandles.Pick pick = BoxHandles.pick(ray, BOX, sizes(0)).orElseThrow();
        assertEquals(BoxHandles.Part.FACE, pick.part());
        assertEquals(BoxFace.EAST, pick.face());
        assertEquals(3, pick.t(), 1e-9);
        assertTrue(pick.inside());
    }

    @Test
    void handleWinsOverTheFaceItSitsOn() {
        BoxHandles.Pick pick = BoxHandles.pick(new Ray(2, 10, 2, 0, -1, 0), BOX, sizes(0.25)).orElseThrow();
        assertEquals(BoxHandles.Part.HANDLE, pick.part());
        assertEquals(BoxFace.UP, pick.face());
        assertEquals(10 - 4.25, pick.t(), 1e-9);
    }

    @Test
    void faceIsPickedAwayFromTheHandle() {
        BoxHandles.Pick pick = BoxHandles.pick(new Ray(1, 10, 1, 0, -1, 0), BOX, sizes(0.25)).orElseThrow();
        assertEquals(BoxHandles.Part.FACE, pick.part());
        assertEquals(BoxFace.UP, pick.face());
        assertEquals(6, pick.t(), 1e-9);
        assertFalse(pick.inside());
    }

    @Test
    void nearestHandleWinsWhenTheRayCrossesTwo() {
        BoxHandles.Pick pick = BoxHandles.pick(new Ray(-10, 2, 2, 1, 0, 0), BOX, sizes(0.25)).orElseThrow();
        assertEquals(BoxHandles.Part.HANDLE, pick.part());
        assertEquals(BoxFace.WEST, pick.face());
        assertEquals(9.75, pick.t(), 1e-9);
    }

    @Test
    void handleSizeGrowsWithDistanceButStaysWithinTheBox() {
        Aabb big = new Aabb(0, 0, 0, 40, 40, 40);
        double near = BoxHandles.handleHalfSize(big, BoxFace.UP, 20, 43, 20);
        double far = BoxHandles.handleHalfSize(big, BoxFace.UP, 20, 140, 20);
        assertEquals(BoxHandles.MIN_HANDLE_HALF_SIZE, near, 1e-9);
        assertEquals(100 * BoxHandles.HANDLE_SCALE_PER_DISTANCE, far, 1e-9);

        Aabb block = Aabb.ofBlocks(0, 0, 0, 0, 0, 0);
        assertEquals(0.25, BoxHandles.handleHalfSize(block, BoxFace.UP, 0.5, 500, 0.5), 1e-9);
    }

    @Test
    void defaultPickSizesHandlesFromTheRayOrigin() {
        Aabb big = new Aabb(0, 0, 0, 40, 40, 40);
        // 100 blocks above the top face centre, aiming 1.5 blocks off-centre: inside the 1.8 half-size handle.
        BoxHandles.Pick pick = BoxHandles.pick(new Ray(21.5, 140, 20, 0, -1, 0), big).orElseThrow();
        assertEquals(BoxHandles.Part.HANDLE, pick.part());
        assertEquals(BoxFace.UP, pick.face());
    }

    @Test
    void blockCornersInAnyOrderCoverWholeBlocks() {
        Aabb box = Aabb.ofBlocks(5, 70, -3, 2, 64, -1);
        assertEquals(new Aabb(2, 64, -3, 6, 71, 0), box);
        assertEquals(3, box.minSize(), 1e-9);
        assertEquals(71, box.faceCenter(BoxFace.UP, 1), 1e-9);
        assertEquals(4, box.faceCenter(BoxFace.UP, 0), 1e-9);
    }
}
