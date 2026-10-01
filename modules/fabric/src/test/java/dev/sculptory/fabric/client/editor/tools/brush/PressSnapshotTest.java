package dev.sculptory.fabric.client.editor.tools.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.fabric.client.editor.tool.RayOverlay;
import org.junit.jupiter.api.Test;

/** What a press's cursor ray sees: the sections a dab touches as they were before it wrote, the world elsewhere. */
class PressSnapshotTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final FakeWorld world = new FakeWorld(states, -64, 320);

    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    @Test
    void coveredSectionsShowTheWorldAsItWasWhenCovered() {
        world.fill(box(-8, 60, -8, 24, 63, 8), stone);
        PressSnapshot snapshot = new PressSnapshot();
        assertTrue(snapshot.isEmpty());
        assertEquals(RayOverlay.WORLD, snapshot.stateAt(0, 63, 0), "nothing covered yet");

        // A dab's box across four chunk columns and two section layers (y 60-66: sections 3 and 4).
        snapshot.cover(world, box(-2, 60, -2, 2, 66, 2));
        assertEquals(8, snapshot.size());
        world.fill(box(-2, 64, -2, 2, 66, 2), dirt); // the shape, predicted or written
        world.fill(box(-2, 61, -2, 2, 63, 2), states.air()); // or a carve
        assertEquals(states.air(), snapshot.stateAt(0, 65, 0), "the shape is not seen");
        assertEquals(stone, snapshot.stateAt(0, 62, 0), "the carved ground is");
        assertEquals(states.air(), snapshot.stateAt(15, 79, 15), "the rest of a covered section too, as it was");
        assertEquals(RayOverlay.WORLD, snapshot.stateAt(16, 62, 0), "outside the covered sections: the world");
        assertEquals(RayOverlay.WORLD, snapshot.stateAt(0, 80, 0));

        // A later dab overlapping the first copies only what is new: what the first wrote stays unseen.
        snapshot.cover(world, box(0, 60, 0, 20, 66, 2));
        assertEquals(10, snapshot.size());
        assertEquals(states.air(), snapshot.stateAt(0, 65, 0));
        assertEquals(stone, snapshot.stateAt(18, 62, 0), "a new section, as it is now");

        snapshot.clear();
        assertTrue(snapshot.isEmpty());
        assertEquals(RayOverlay.WORLD, snapshot.stateAt(0, 65, 0), "the press ended: the world as it is");
    }

    @Test
    void unloadedChunksAndCellsBeyondTheBuildHeightAreNotCopied() {
        world.setLoaded(1, 0, false);
        PressSnapshot snapshot = new PressSnapshot();
        snapshot.cover(world, box(10, 310, 0, 20, 330, 4));
        assertEquals(1, snapshot.size(), "section (0, 19, 0) only: chunk (1, 0) is unloaded, y 320 above the world");
        assertEquals(RayOverlay.WORLD, snapshot.stateAt(18, 315, 0));
        snapshot.cover(world, box(0, 400, 0, 4, 410, 4));
        assertEquals(1, snapshot.size(), "a box wholly above the world copies nothing");
    }

    @Test
    void aPressKeepsAtMostMaxSections() {
        PressSnapshot snapshot = new PressSnapshot();
        // 64 × 64 chunk columns of one section each: 4096, then no more.
        snapshot.cover(world, box(0, 0, 0, 64 * 16 - 1, 15, 64 * 16 - 1));
        assertEquals(PressSnapshot.MAX_SECTIONS, snapshot.size());
        snapshot.cover(world, box(-16, 0, -16, -1, 15, -1));
        assertEquals(PressSnapshot.MAX_SECTIONS, snapshot.size());
        assertEquals(RayOverlay.WORLD, snapshot.stateAt(-8, 8, -8), "past the cap the ray sees the world");
    }
}
