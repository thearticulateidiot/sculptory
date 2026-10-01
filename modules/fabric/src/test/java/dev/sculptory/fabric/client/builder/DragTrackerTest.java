package dev.sculptory.fabric.client.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.protocol.v2.C2S;
import java.util.List;
import org.junit.jupiter.api.Test;

class DragTrackerTest {
    private static BlockPos cell(int x) {
        return new BlockPos(x, 64, 0);
    }

    @Test
    void aDragTakesEachCellOnceAndDrainsInWireSizedBatches() {
        DragTracker drags = new DragTracker();
        assertFalse(drags.open());
        assertFalse(drags.add(cell(1)), "no drag open");
        assertTrue(drags.drain().isEmpty());

        int id = drags.begin(false);
        assertEquals(1, id);
        assertTrue(drags.open());
        assertFalse(drags.sameKind());
        assertTrue(drags.add(cell(1)));
        assertFalse(drags.add(cell(1)), "the crosshair stayed on the same block");
        assertTrue(drags.add(cell(2)));
        assertEquals(2, drags.size());
        assertEquals(List.of(List.of(cell(1), cell(2))), drags.drain());
        assertTrue(drags.drain().isEmpty(), "drained");
        assertFalse(drags.add(cell(2)), "still part of the drag after the drain");

        for (int x = 10; x < 10 + C2S.BuilderBreak.MAX_CELLS * 2 + 3; x++) drags.add(cell(x));
        List<List<BlockPos>> batches = drags.drain();
        assertEquals(3, batches.size());
        assertEquals(C2S.BuilderBreak.MAX_CELLS, batches.get(0).size());
        assertEquals(C2S.BuilderBreak.MAX_CELLS, batches.get(1).size());
        assertEquals(3, batches.get(2).size());
        assertEquals(cell(10), batches.get(0).get(0));
        assertEquals(cell(10 + C2S.BuilderBreak.MAX_CELLS * 2 + 2), batches.get(2).get(2));
        for (List<BlockPos> batch : batches) {
            new C2S.BuilderBreak(1, id, batch, 0, dev.sculptory.core.brush.Symmetry.NONE, false, false);
        }

        assertTrue(drags.end());
        assertFalse(drags.open());
        assertEquals(id, drags.dragId(), "the id stays readable for the drag's end message");
        assertFalse(drags.end());
    }

    @Test
    void aNewDragEndsTheOpenOneAndCountsUp() {
        DragTracker drags = new DragTracker();
        drags.begin(false);
        drags.add(cell(1));
        int second = drags.begin(true);
        assertEquals(2, second);
        assertTrue(drags.sameKind());
        assertEquals(0, drags.size(), "the first drag's cells are gone");
        assertTrue(drags.add(cell(1)), "a cell of the earlier drag is new to this one");
        assertTrue(drags.drain().get(0).contains(cell(1)));
    }
}
