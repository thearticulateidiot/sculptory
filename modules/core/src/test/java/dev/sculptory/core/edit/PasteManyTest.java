package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.box;
import static dev.sculptory.core.edit.CopyTestSupport.chest;
import static dev.sculptory.core.edit.CopyTestSupport.runAndUndo;
import static dev.sculptory.core.edit.CopyTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CopyTestSupport.Cell;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Generate's Stamp on the engine: a multi-paste writes exactly what its placements' pastes would, one after another (the
 * later winning), in one program, undoes exactly, and is budgeted on the source's cells times its placements.
 */
class PasteManyTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final Map<SourceRef, SourceBlocks> sources = new HashMap<>();
    private final CompileContext context = CopyTestSupport.context(states, sources);
    private final SourceRef ref = new SourceRef.Clipboard(UUID.randomUUID());
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");

    /** A small lamp post: stone, a stair facing east on top, a chest with an apple beside it, air at a corner. */
    private Clipboard post() {
        Clipboard clipboard = Clipboard.builder(states, new BlockPos(3, 3, 2)).anchor(new BlockPos(1, 0, 0))
                .set(1, 0, 0, stone).set(1, 1, 0, stone)
                .set(1, 2, 0, states.state("minecraft:oak_stairs[facing=east]"))
                .set(2, 0, 1, states.state("minecraft:chest[facing=north]"))
                .setTile(2, 0, 1, chest("minecraft:apple", 3))
                .set(0, 0, 0, air)
                .set(0, 2, 1, dirt)
                .build();
        sources.put(ref, clipboard.toSource());
        return clipboard;
    }

    /** Ground of dirt under a layer of stone where the stamps land, so air, Into and overlaps show. */
    private FakeWorld ground() {
        FakeWorld world = new FakeWorld(states);
        for (int x = -6; x <= 14; x++) {
            for (int z = -6; z <= 14; z++) {
                world.set(x, 63, z, dirt);
                if ((x + z) % 3 == 0) world.set(x, 64, z, stone);
            }
        }
        return world;
    }

    private static List<OpSpec.PasteMany.Placement> placements(Object... originsAndTransforms) {
        List<OpSpec.PasteMany.Placement> out = new ArrayList<>();
        for (int i = 0; i < originsAndTransforms.length; i += 2) {
            out.add(new OpSpec.PasteMany.Placement((BlockPos) originsAndTransforms[i],
                    Orientation.of((Transform) originsAndTransforms[i + 1])));
        }
        return out;
    }

    @Test
    void eachPlacementWritesWhatItsOwnPasteWouldTheLaterWinning() {
        post();
        Box area = box(-6, 60, -6, 14, 70, 14);
        List<OpSpec.PasteMany.Placement> stamps = placements(
                new BlockPos(0, 64, 0), Transform.IDENTITY,
                new BlockPos(1, 64, 0), new Transform(1, Mirror.NONE),         // overlaps the first
                new BlockPos(8, 66, 3), new Transform(2, Mirror.X, true),      // flipped upside down
                new BlockPos(4, 64, 9), new Transform(3, Mirror.Z));
        // Writing every landing cell, the later placement wins exactly as the pastes run one after another would.
        // With Into, each placement judges the world as it was (the stamps here do not overlap, so pastes in turn agree).
        List<PasteOptions> options = List.of(PasteOptions.DEFAULT, new PasteOptions(true, false, false),
                PasteOptions.DEFAULT.withInto(PasteOptions.Into.AIR), PasteOptions.DEFAULT.withInto(PasteOptions.Into.EXISTING));
        for (PasteOptions o : options) {
            List<OpSpec.PasteMany.Placement> used = o.into() == PasteOptions.Into.EVERYTHING ? stamps
                    : List.of(stamps.get(0), stamps.get(2), stamps.get(3));
            FakeWorld many = ground();
            FakeExecutor.run(OpCompiler.compile(new OpSpec.PasteMany(ref, used, o), context), many);
            FakeWorld oneByOne = ground();
            for (OpSpec.PasteMany.Placement stamp : used) {
                FakeExecutor.run(OpCompiler.compile(new OpSpec.Paste(ref, stamp.origin(), stamp.orientation(), o), context),
                        oneByOne);
            }
            Map<BlockPos, Cell> expected = snapshot(oneByOne, area);
            CopyTestSupport.assertWorld(expected, many, o.toString());
            assertEquals(expected, snapshot(many, area), o.toString());
        }
    }

    @Test
    void theChestKeepsItsContentsAndEveryStampUndoesExactly() {
        post();
        FakeWorld world = ground();
        List<OpSpec.PasteMany.Placement> stamps = placements(new BlockPos(0, 64, 0), Transform.IDENTITY,
                new BlockPos(6, 64, 6), new Transform(1, Mirror.NONE), new BlockPos(3, 64, 0), Transform.UPSIDE_DOWN);
        EditProgram program = OpCompiler.compile(new OpSpec.PasteMany(ref, stamps, PasteOptions.DEFAULT), context);
        assertInstanceOf(PasteManyProgram.class, program);
        assertEquals(3, ((PasteManyProgram) program).placements().size());
        runAndUndo(program, world, box(-6, 58, -6, 14, 70, 14), "three stamps");
        FakeExecutor.run(OpCompiler.compile(new OpSpec.PasteMany(ref, stamps, PasteOptions.DEFAULT), context), world);
        // The first stamp's chest is at anchor + (1, 0, 1).
        assertNotNull(world.tile(1, 64, 1), "the chest's contents travel");
        assertEquals(chest("minecraft:apple", 3), world.tile(1, 64, 1));
    }

    @Test
    void theBudgetCountsTheSourcesCellsOncePerPlacementAndPerCopy() {
        Clipboard clipboard = post();
        long cells = clipboard.cellCount();
        List<OpSpec.PasteMany.Placement> stamps = placements(new BlockPos(0, 64, 0), Transform.IDENTITY,
                new BlockPos(9, 64, 0), Transform.IDENTITY, new BlockPos(0, 64, 9), Transform.IDENTITY);
        CompileContext tight = budget(cells * 3 - 1);
        assertThrows(EditTooLargeException.class,
                () -> OpCompiler.compile(new OpSpec.PasteMany(ref, stamps, PasteOptions.DEFAULT), tight));
        OpCompiler.compile(new OpSpec.PasteMany(ref, stamps, PasteOptions.DEFAULT), budget(cells * 3));
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 41, 0);
        assertThrows(EditTooLargeException.class,
                () -> OpCompiler.compile(new OpSpec.PasteMany(ref, stamps, PasteOptions.DEFAULT, mirror), budget(cells * 3)));
        OpCompiler.compile(new OpSpec.PasteMany(ref, stamps, PasteOptions.DEFAULT, mirror), budget(cells * 6));
    }

    @Test
    void refusalsAnUnknownSourceAndNothingInsideTheBuildHeight() {
        post();
        OpSpec.PasteMany unknown = new OpSpec.PasteMany(new SourceRef.Clipboard(UUID.randomUUID()),
                placements(BlockPos.ORIGIN, Transform.IDENTITY), PasteOptions.DEFAULT);
        assertThrows(IllegalArgumentException.class, () -> OpCompiler.compile(unknown, context));
        OpSpec.PasteMany sky = new OpSpec.PasteMany(ref, placements(new BlockPos(0, 400, 0), Transform.IDENTITY,
                new BlockPos(0, -200, 0), Transform.IDENTITY), PasteOptions.DEFAULT);
        assertThrows(IllegalArgumentException.class, () -> OpCompiler.compile(sky, context));
        // One placement in the sky is left out; the other is written.
        FakeWorld world = ground();
        FakeExecutor.run(OpCompiler.compile(new OpSpec.PasteMany(ref, placements(new BlockPos(0, 400, 0), Transform.IDENTITY,
                new BlockPos(0, 64, 0), Transform.IDENTITY), PasteOptions.DEFAULT), context), world);
        assertEquals(stone, world.get(0, 65, 0));
    }

    @Test
    void aSymmetricStampWritesEveryPlacementsMirrorImageToo() {
        post();
        FakeWorld world = ground();
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 41, 0);  // about the middle of block 20: x lands on 40 - x
        List<OpSpec.PasteMany.Placement> stamps = placements(new BlockPos(0, 64, 0), Transform.IDENTITY,
                new BlockPos(5, 64, 5), Transform.IDENTITY);
        runAndUndo(OpCompiler.compile(new OpSpec.PasteMany(ref, stamps, PasteOptions.DEFAULT, mirror), context), world,
                box(-6, 60, -6, 48, 70, 14), "a mirrored stamp");
        FakeExecutor.run(OpCompiler.compile(new OpSpec.PasteMany(ref, stamps, PasteOptions.DEFAULT, mirror), context), world);
        for (int[] stamp : new int[][] {{0, 0}, {5, 5}}) {
            assertEquals(stone, world.get(stamp[0], 65, stamp[1]));
            assertEquals(stone, world.get(40 - stamp[0], 65, stamp[1]), "the image of " + stamp[0]);
            // The stair faces east; its mirror image faces west.
            assertEquals("west", states.describe(world.get(40 - stamp[0], 66, stamp[1])).get("facing"));
        }
    }

    @Test
    void placementsSharingATurnShareOneScanAndCompileAsTheirOwnPastes() {
        post();
        List<OpSpec.PasteMany.Placement> stamps = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            // Four turns, at heights from the ground to the top of the world (the last ones cut by it).
            stamps.add(new OpSpec.PasteMany.Placement(new BlockPos(i * 5, 64 + i * 7, i % 3),
                    Orientation.of(new Transform(i % 4, i % 2 == 0 ? Mirror.NONE : Mirror.X, i % 5 == 0))));
        }
        PasteManyProgram many = (PasteManyProgram) OpCompiler.compile(new OpSpec.PasteMany(ref, stamps,
                PasteOptions.DEFAULT), context);
        int k = 0;
        for (OpSpec.PasteMany.Placement stamp : stamps) {
            EditProgram alone;
            try {
                alone = OpCompiler.compile(new OpSpec.Paste(ref, stamp.origin(), stamp.orientation(), PasteOptions.DEFAULT),
                        context);
            } catch (IllegalArgumentException aboveTheWorld) {
                continue; // a lone paste there is refused; the stamp leaves it out
            }
            PasteProgram shared = many.placements().get(k++);
            assertEquals(alone.bounds(), shared.bounds(), stamp.toString());
            assertEquals(alone.estimatedCells(), shared.estimatedCells(), stamp.toString());
            assertEquals(java.util.Arrays.toString(alone.sectionOrder()), java.util.Arrays.toString(shared.sectionOrder()),
                    stamp.toString());
        }
        assertEquals(k, many.placements().size());
        assertTrue(k < stamps.size() && k > 30, "some are above the world: " + k);
    }

    private CompileContext budget(long cells) {
        return new CompileContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef source) {
                return Optional.ofNullable(sources.get(source));
            }

            @Override
            public long maxCells() {
                return cells;
            }

            @Override
            public int bottomY() {
                return -64;
            }

            @Override
            public int topYExclusive() {
                return 320;
            }
        };
    }
}
