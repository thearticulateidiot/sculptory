package dev.sculptory.fabric.client.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import org.junit.jupiter.api.Test;

/** Watching block changes inside a box (a scatter preview's staleness). */
class ClientBlockChangesTest {
    @Test
    void aWatchCountsOnlyChangesInsideItsBoxUntilClosed() {
        Box box = Box.of(new BlockPos(0, 60, 0), new BlockPos(20, 80, 20));
        long stamp = ClientBlockChanges.stamp();
        try (ClientBlockChanges.Watch watch = ClientBlockChanges.watch(box)) {
            ClientBlockChanges.bump(5, 70, 5);
            ClientBlockChanges.bump(5, 90, 5); // above the box
            ClientBlockChanges.bump(-1, 70, 5); // beside it
            assertEquals(1, watch.changes());
            ClientBlockChanges.bumpChunk(1, 1); // x, z 16..31: overlaps the box's columns
            ClientBlockChanges.bumpChunk(2, 0); // x 32..47: does not
            assertEquals(2, watch.changes());
            watch.close();
            ClientBlockChanges.bump(5, 70, 5);
            assertEquals(2, watch.changes(), "a closed watch stops counting");
        }
        assertTrue(ClientBlockChanges.stamp() - stamp >= 6, "every change still moves the global stamp");
    }
}
