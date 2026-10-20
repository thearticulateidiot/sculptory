package dev.sculptory.fabric.client.editor.tools.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.HeightmapSpec;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.SymmetricStep;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.fabric.client.editor.tool.RayOverlay;
import java.util.Arrays;
import java.util.List;
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

    /**
     * A press keeps at most {@link PressSnapshot#MAX_BYTES} of copies: about 10 KB a section holding every fake state,
     * so some 800 of them, while sections of one state (air above the ground, rock below) cost about a hundred bytes each.
     */
    @Test
    void aPressKeepsAtMostMaxBytes() {
        PressSnapshot snapshot = new PressSnapshot();
        // 64 × 64 chunk columns of one air section each: all kept, in well under a megabyte.
        snapshot.cover(world, box(0, 0, 0, 64 * 16 - 1, 15, 64 * 16 - 1));
        assertEquals(4096, snapshot.size());
        assertTrue(snapshot.bytes() < 1 << 20, "uniform sections: " + snapshot.bytes());
        snapshot.clear();
        assertEquals(0, snapshot.bytes());

        // A row of 2,500 sections holding every state: past the budget the rest are seen as they are.
        int sections = 2_500;
        for (int sx = 0; sx < sections; sx++) {
            for (int h = 0; h < states.size(); h++) world.set(sx * 16 + (h & 15), h >> 4, 0, h);
        }
        snapshot.cover(world, box(0, 0, 0, sections * 16 - 1, 15, 0));
        int kept = snapshot.size();
        assertTrue(kept > 500 && kept < sections, "sections kept: " + kept);
        assertTrue(snapshot.bytes() >= PressSnapshot.MAX_BYTES && snapshot.bytes() < PressSnapshot.MAX_BYTES + 16_384);
        snapshot.cover(world, box(-16, 0, -16, -1, 15, -1));
        assertEquals(kept, snapshot.size(), "nothing more once full");
        assertEquals(RayOverlay.WORLD, snapshot.stateAt(-8, 8, -8), "past the cap the ray sees the world");
        assertEquals(RayOverlay.WORLD, snapshot.stateAt((sections - 1) * 16, 0, 0));
        assertEquals(0, snapshot.stateAt(0, 0, 0), "the first sections are kept");
    }

    /**
     * A Heightmap dab copies the sections near the surface of its stamp's columns and its copies' (not the kernel's
     * whole scan window), so every cell its stamp writes is seen as it was; with too small a budget, the sections nearest
     * the dab are the ones kept.
     */
    @Test
    void aHeightmapDabCoversWhatItsStampsWriteNearestFirst() {
        world.fill(box(-150, 56, -150, 150, 60, 150), stone);
        world.setHeightHints(true);
        byte[] hill = new byte[257 * 257];
        Arrays.fill(hill, (byte) 255);
        for (Symmetry symmetry : List.of(Symmetry.NONE, new Symmetry(Symmetry.Mode.ROTATE_4, 1, 1))) {
            BrushSpec spec = new BrushSpec(BrushTool.HEIGHTMAP, 128, 1f, Falloff.CONSTANT, Shape.SQUARE, null,
                    SurfaceMask.ANY, 0, 0, 1L, null, symmetry, null, SculptMode.TERRAIN, null, null,
                    new HeightmapSpec(257, hill, 16, false), null);
            Dab dab = new Dab(0, 40 * 16 + 8, 61 * 16, 8, Dab.FULL_PRESSURE);
            PressSnapshot snapshot = new PressSnapshot();
            snapshot.coverHeightmap(world, spec, dab);
            // y 60-78 is sections 3 and 4: two per section column of each 259-wide square.
            assertTrue(snapshot.size() <= 2 * 18 * 18 * symmetry.mode().copies(), symmetry + ": " + snapshot.size());
            assertTrue(snapshot.bytes() < PressSnapshot.MAX_BYTES, symmetry + ": " + snapshot.bytes());
            FakeWorld server = new FakeWorld(states, -64, 320);
            server.fill(box(-150, 56, -150, 150, 60, 150), stone);
            int[] written = {0};
            BrushKernels.forTool(BrushTool.HEIGHTMAP).applyStep(spec, SymmetricStep.of(spec, dab, server).dabs(),
                    new StrokeState(), server, (x, y, z, h) -> {
                        written[0]++;
                        assertEquals(world.get(x, y, z), snapshot.stateAt(x, y, z),
                                () -> symmetry + ": seen as it was at " + x + "," + y + "," + z);
                    });
            assertTrue(written[0] > 200 * 200 * 16, symmetry + ": cells written " + written[0]);
        }

        BrushSpec small = new BrushSpec(BrushTool.HEIGHTMAP, 40, 1f, Falloff.CONSTANT, Shape.SQUARE, null, SurfaceMask.ANY,
                0, 0, 1L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null, null,
                new HeightmapSpec(81, new byte[81 * 81], 16, false), null);
        PressSnapshot tight = new PressSnapshot(10_000);
        tight.coverHeightmap(world, small, new Dab(0, 8, 61 * 16, 8, Dab.FULL_PRESSURE));
        assertTrue(tight.size() > 2 && tight.size() < 50, "a few sections: " + tight.size());
        assertTrue(tight.stateAt(0, 61, 0) != RayOverlay.WORLD, "the dab's own section column is kept");
        assertEquals(RayOverlay.WORLD, tight.stateAt(-38, 61, 38), "a far corner is not");
    }
}
