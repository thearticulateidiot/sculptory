package dev.sculptory.fabric.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.fabric.engine.ChunkPermit;
import dev.sculptory.fabric.engine.PermissionService;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.perm.ChunkPermits;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.junit.jupiter.api.Test;

/**
 * Entities are found by their UUID and may have wandered out of the chunks a job was admitted for: their protection is
 * asked for their own column, not answered "nothing is written there" as a chunk outside the job's bounds is.
 */
class PermitSourceTest {
    /** A claim over columns x 40..47 (in chunk 2, 0); the corner rule as the server applies it. */
    private static final class Claim implements PermissionService {
        final List<Box> asked = new ArrayList<>();

        @Override
        public boolean has(ServerPlayerEntity p, Perm node) {
            return true;
        }

        @Override
        public ChunkPermit chunk(ServerPlayerEntity p, ServerWorld w, int cx, int cz, Box bounds) {
            asked.add(bounds);
            return ChunkPermits.forChunk(cx, cz, bounds, (x, z) -> x < 40 || x > 47);
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
        assertFalse(permits.mayChangeColumn(41, 5), "an entity that wandered into the claim stays protected");
        assertEquals(box(41, 64, 5, 41, 64, 5), claim.asked.get(claim.asked.size() - 1), "asked for its column only");
        assertTrue(permits.mayChangeColumn(50, 5));
        assertTrue(permits.mayChangeColumn(3, 3));
        assertEquals(ChunkPermit.ALLOW, permits.chunk(0, 0));
    }

    @Test
    void otherSourcesAnswerFromTheirChunkPermit() {
        PermitSource denyEast = (cx, cz) -> cx >= 1 ? ChunkPermit.DENY : ChunkPermit.ALLOW;
        assertTrue(denyEast.mayChangeColumn(15, 0));
        assertFalse(denyEast.mayChangeColumn(16, 0));
        assertTrue(PermitSource.ALLOW_ALL.mayChangeColumn(-1000, 1000));
    }
}
