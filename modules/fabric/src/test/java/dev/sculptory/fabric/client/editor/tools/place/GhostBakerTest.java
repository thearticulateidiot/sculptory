package dev.sculptory.fabric.client.editor.tools.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.render.ghost.GhostMapping;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import org.junit.jupiter.api.Test;

class GhostBakerTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final FakeWorld world = new FakeWorld(states);
    private final int stone = states.state("minecraft:stone");

    @Test
    void aFilteredCaptureTakesOnlyTheFiltersCells() {
        Box box = new Box(new BlockPos(0, 0, 0), new BlockPos(4, 4, 4));
        world.fill(box, stone);
        // The ball of cells within 2 of the centre: 33 of the box's 125 cells.
        GhostBaker.CellFilter ball = (x, y, z) -> (x - 2) * (x - 2) + (y - 2) * (y - 2) + (z - 2) * (z - 2) <= 4;
        GhostBaker.Capture capture = new GhostBaker.Capture(world, box, ball, null);
        capture.step(Integer.MAX_VALUE);
        GhostVolume volume = capture.build();

        assertEquals(33, volume.blockCount());
        assertEquals(stone, volume.handle(2, 2, 2));
        assertEquals(-1, volume.handle(0, 0, 0), "a corner outside the ball is left out");
        assertEquals(new Box(BlockPos.ORIGIN, new BlockPos(4, 4, 4)), volume.frame(), "the frame is still the box");
    }

    /**
     * A filter judges each cell where the placement's mapping lands it (a quarter turn here): Only existing blocks
     * keeps the cells over stone and water, Only air the one over air; in a chunk the client has not loaded every
     * cell is kept and the volume comes back as it is.
     */
    @Test
    void aFilterKeepsTheCellsTheSettingWouldWriteWhereTheyLand() {
        BlockBuffer cells = new BlockBuffer();
        for (int x = 0; x < 3; x++) cells.set(x, 0, 0, stone);
        GhostVolume volume = GhostVolume.of(cells, GhostBaker.air(states));
        Box frame = new Box(BlockPos.ORIGIN, new BlockPos(2, 0, 0));
        volume.setFrame(frame);
        GhostMapping mapping = new GhostMapping(frame, Transform.rotation(1), 40, 70, 40);
        int[] landed = new int[3];
        for (int x = 0; x < 3; x++) landed[x] = mapping.worldZ(x, 0);
        assertEquals(40, mapping.worldX(0, 0), "a quarter turn lays the row along z");
        world.set(40, 70, landed[0], stone);
        world.set(40, 70, landed[2], states.state("minecraft:water"));

        GhostBaker.Filter existing = new GhostBaker.Filter(world, volume, mapping, PasteOptions.Into.EXISTING);
        assertTrue(existing.step(1));
        GhostVolume kept = existing.build();
        assertEquals(2, kept.blockCount());
        assertEquals(stone, kept.handle(0, 0, 0), "over stone");
        assertEquals(-1, kept.handle(1, 0, 0), "over air");
        assertEquals(stone, kept.handle(2, 0, 0), "over water: an existing block");
        assertEquals(frame, kept.frame());

        GhostBaker.Filter air = new GhostBaker.Filter(world, volume, mapping, PasteOptions.Into.AIR);
        air.step(Integer.MAX_VALUE);
        assertEquals(1, air.build().blockCount());
        assertEquals(stone, air.build().handle(1, 0, 0));

        world.setLoaded(2, 2, false);
        GhostBaker.Filter unloaded = new GhostBaker.Filter(world, volume, mapping, PasteOptions.Into.AIR);
        unloaded.step(Integer.MAX_VALUE);
        assertSame(volume, unloaded.build(), "nothing dropped in an unloaded chunk");
    }

    @Test
    void aCaptureReadsOnlyTheSectionsItIsGiven() {
        Box box = new Box(new BlockPos(0, 0, 0), new BlockPos(31, 0, 0));
        world.fill(box, stone);
        world.setLoaded(1, 0, false); // reading the second section would throw
        GhostBaker.Capture capture = new GhostBaker.Capture(world, box, (x, y, z) -> x < 16,
                new long[] {BlockBuffer.key(0, 0, 0)});
        capture.step(Integer.MAX_VALUE);

        assertEquals(1, capture.sections());
        assertEquals(16, capture.build().blockCount());
    }
}
