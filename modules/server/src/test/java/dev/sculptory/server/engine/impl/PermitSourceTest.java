package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.PermissionService;
import dev.sculptory.server.perm.ChunkPermits;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Entities are found by their UUID and may have wandered out of the chunks a job was admitted for: their protection is
 * asked for their own cell, not answered "nothing is written there" as a chunk outside the job's bounds is.
 */
class PermitSourceTest {
    /** A claim over columns x 40..47 (in chunk 2, 0); the corner rule as the server applies it. */
    private static final class Claim implements PermissionService<Object, Object> {
        final List<Box> asked = new ArrayList<>();

        @Override
        public boolean has(Object p, Perm node) {
            return true;
        }

        @Override
        public ChunkPermit chunk(Object p, Object w, int cx, int cz, Box bounds) {
            asked.add(bounds);
            return ChunkPermits.forChunk(cx, cz, bounds, (x, z) -> x < 40 || x > 47);
        }
    }

    /** A region over x 40..47 and y 10..19 only (protection limited in height), judged cell by cell. */
    private static final class Region implements PermissionService<Object, Object> {
        final List<Box> asked = new ArrayList<>();

        @Override
        public boolean has(Object p, Perm node) {
            return true;
        }

        @Override
        public ChunkPermit chunk(Object p, Object w, int cx, int cz, Box bounds) {
            asked.add(bounds);
            return ChunkPermits.rasterize(cx, cz, bounds, (x, y, z) -> x < 40 || x > 47 || y < 10 || y > 19);
        }
    }

    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    @Test
    void aColumnOutsideTheJobsBoundsIsCheckedForItself() {
        Claim claim = new Claim();
        Box bounds = box(0, 64, 0, 15, 70, 15);
        PermitSource permits = PermitSource.forPlayer(claim, null, null, bounds);
        assertEquals(ChunkPermit.ALLOW, permits.chunk(2, 0), "blocks: nothing of the job is written in that chunk");
        assertFalse(permits.mayChange(41, 66, 5), "an entity that wandered into the claim stays protected");
        assertEquals(box(41, 66, 5, 41, 66, 5), claim.asked.get(claim.asked.size() - 1), "asked for its cell only");
        assertTrue(permits.mayChange(50, 66, 5));
        assertTrue(permits.mayChange(3, 66, 3));
        assertEquals(ChunkPermit.ALLOW, permits.chunk(0, 0));
        // Work visiting one column at several heights asks once, for the column over the heights it gives.
        assertFalse(permits.column(41, 5, 64, 70).allows(41, 64, 5));
        assertEquals(box(41, 64, 5, 41, 70, 5), claim.asked.get(claim.asked.size() - 1), "the column, the heights given");
        assertTrue(permits.column(50, 5, 64, 70).allows(50, 70, 5));
    }

    @Test
    void anEntityIsAskedAboutAtItsOwnHeight() {
        Region region = new Region();
        Box bounds = box(0, 0, 0, 15, 30, 15);
        PermitSource permits = PermitSource.forPlayer(region, null, null, bounds);
        // Inside the region's columns: denied only within its heights.
        assertTrue(permits.mayChange(41, 9, 5), "below the region");
        assertFalse(permits.mayChange(41, 10, 5), "the region's lowest layer");
        assertFalse(permits.mayChange(41, 19, 5), "the region's highest layer");
        assertTrue(permits.mayChange(41, 20, 5), "above the region");
        assertEquals(box(41, 20, 5, 41, 20, 5), region.asked.get(region.asked.size() - 1), "asked at its own height");
        assertTrue(permits.mayChange(48, 15, 5), "beside the region");
        // A column over heights 0..30 holds both: answered per cell.
        ChunkPermit column = permits.column(41, 5, 0, 30);
        assertInstanceOf(ChunkPermit.Cells.class, column);
        for (int y = 0; y <= 30; y++) assertEquals(y < 10 || y > 19, column.allows(41, y, 5), "y " + y);
    }

    @Test
    void otherSourcesAnswerFromTheirChunkPermit() {
        PermitSource denyEast = (cx, cz) -> cx >= 1 ? ChunkPermit.DENY : ChunkPermit.ALLOW;
        assertTrue(denyEast.mayChange(15, 64, 0));
        assertFalse(denyEast.mayChange(16, 64, 0));
        assertFalse(denyEast.column(16, 0, 64, 64).allows(16, 64, 0));
        assertTrue(PermitSource.ALLOW_ALL.mayChange(-1000, -64, 1000));
        // A chunk permit limited in height answers the default mayChange at the cell's own height.
        long[] bits = new long[2 * 4];
        java.util.Arrays.fill(bits, 0, 4, -1L); // layer 10 allowed, layer 11 denied
        ChunkPermit layers = new ChunkPermit.Cells(10, 11, bits);
        PermitSource lowerLayer = (cx, cz) -> layers;
        assertTrue(lowerLayer.mayChange(3, 10, 3));
        assertFalse(lowerLayer.mayChange(3, 11, 3));
        assertFalse(lowerLayer.mayChange(3, 12, 3), "beyond the permit's heights");
        PermitSource none = (cx, cz) -> null;
        assertFalse(none.mayChange(0, 0, 0), "no permit: denied");
    }
}
