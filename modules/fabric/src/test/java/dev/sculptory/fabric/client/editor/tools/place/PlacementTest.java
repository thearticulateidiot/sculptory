package dev.sculptory.fabric.client.editor.tools.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.CompileContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceBlocks;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.render.ghost.GhostMapping;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostSection;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Place tool's geometry against the core programs the server runs: wherever the ghost shows a block (moved by
 * the model matrix, or baked with turned states) is exactly where the paste or move writes that block.
 */
class PlacementTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final SourceRef SOURCE = new SourceRef.Clipboard(new UUID(7, 7));

    /** Every transform: 4 turns × no mirror, X and Z, each also flipped upside down. */
    private static List<Transform> transforms() {
        List<Transform> all = new ArrayList<>();
        for (boolean flip : new boolean[] {false, true}) {
            for (Mirror mirror : Mirror.values()) {
                for (int turns = 0; turns < 4; turns++) all.add(new Transform(turns, mirror, flip));
            }
        }
        return all;
    }

    /** An asymmetric 5×3×4 source of stairs, logs and stone, so every transform lands differently. */
    private static BlockBuffer source() {
        BlockBuffer cells = new BlockBuffer();
        cells.set(0, 0, 0, STATES.state("minecraft:oak_stairs[facing=north]"));
        cells.set(4, 0, 0, STATES.state("minecraft:oak_stairs[facing=east,shape=inner_left]"));
        cells.set(1, 1, 3, STATES.state("minecraft:oak_log[axis=x]"));
        cells.set(2, 2, 1, STATES.state("minecraft:stone"));
        cells.set(3, 0, 2, STATES.state("minecraft:chest[facing=west]"));
        return cells;
    }

    private static CompileContext context(Map<SourceRef, SourceBlocks> sources) {
        return new CompileContext() {
            @Override
            public StateSpace states() {
                return STATES;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef ref) {
                return Optional.ofNullable(sources.get(ref));
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

    /** The world cells the ghost shows, and their states, for a placement drawn as the tool draws it. */
    private static Map<BlockPos, Integer> ghostCells(GhostVolume volume, Placement placement, boolean baked) {
        Map<BlockPos, Integer> cells = new HashMap<>();
        BlockPos min = placement.targetMin();
        if (baked) {
            GhostVolume turned = GhostBaker.bake(volume.frame(), volume.sections(), placement.transform(), STATES);
            GhostMapping mapping = GhostPlacement.of(turned, min.x(), min.y(), min.z()).mapping();
            forEachCell(turned, (x, y, z, h) -> cells.put(new BlockPos(mapping.worldX(x, z), mapping.worldY(y),
                    mapping.worldZ(x, z)), h));
        } else {
            GhostMapping mapping = GhostPlacement.of(volume, min.x(), min.y(), min.z())
                    .withTransform(placement.transform()).mapping();
            forEachCell(volume, (x, y, z, h) -> cells.put(new BlockPos(mapping.worldX(x, z), mapping.worldY(y),
                    mapping.worldZ(x, z)), placement.transform().applyToState(STATES, h)));
        }
        return cells;
    }

    private interface CellVisitor {
        void visit(int x, int y, int z, int handle);
    }

    private static void forEachCell(GhostVolume volume, CellVisitor visitor) {
        for (GhostSection section : volume.sections()) {
            for (int i = 0; i < SectionBuffer.SIZE; i++) {
                int h = section.handle(i);
                if (h < 0) continue;
                visitor.visit((section.sectionX() << 4) + SectionBuffer.localX(i), (section.sectionY() << 4) + SectionBuffer.localY(i),
                        (section.sectionZ() << 4) + SectionBuffer.localZ(i), h);
            }
        }
    }

    /** The non-air cells the program wrote. */
    private static Map<BlockPos, Integer> written(FakeWorld world, Box box) {
        Map<BlockPos, Integer> cells = new HashMap<>();
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    int h = world.get(x, y, z);
                    if (h != STATES.air()) cells.put(new BlockPos(x, y, z), h);
                }
            }
        }
        return cells;
    }

    @Test
    void theGhostShowsExactlyWhatThePasteWritesForEveryTransformAndAnchor() {
        BlockBuffer cells = source();
        BlockPos dims = new BlockPos(5, 3, 4);
        GhostVolume volume = GhostVolume.of(cells, GhostBaker.air(STATES));
        volume.setFrame(new Box(BlockPos.ORIGIN, new BlockPos(4, 2, 3)));
        // Our own copies are anchored at the bottom centre; imported files can have any anchor, even outside.
        for (BlockPos anchor : List.of(Placement.bottomCentre(dims), new BlockPos(-3, 1, 9), new BlockPos(4, 2, 0))) {
            for (Transform transform : transforms()) {
                Placement placement = Placement.paste(SOURCE, dims, anchor, new BlockPos(100, 70, -40));
                assertTrue(placement.setTransform(transform));
                OpSpec op = placement.op(new PasteOptions(false, false), STATES.air());
                OpSpec.Paste paste = assertInstanceOf(OpSpec.Paste.class, op);
                assertEquals(transform, paste.t());
                EditProgram program = OpCompiler.compile(op, context(Map.of(SOURCE, new SourceBlocks(cells, dims, anchor))));
                FakeWorld world = new FakeWorld(STATES, -64, 320);
                FakeExecutor.run(program, world);
                Box around = placement.targetBox();
                Box search = new Box(around.min().offset(-8, -8, -8), around.max().offset(8, 8, 8));
                Map<BlockPos, Integer> pasted = written(world, search);
                String what = transform + " anchor " + anchor;
                assertEquals(pasted, ghostCells(volume, placement, false), "model-matrix ghost, " + what);
                assertEquals(pasted, ghostCells(volume, placement, true), "baked ghost, " + what);
                assertTrue(around.contains(program.bounds()), what);
            }
        }
    }

    @Test
    void rotationTurnsAroundThePivot() {
        BlockPos dims = new BlockPos(6, 2, 3);
        Placement placement = Placement.paste(SOURCE, dims, BlockPos.ORIGIN, new BlockPos(10, 64, 10));
        BlockPos pivot = placement.pivot();
        assertEquals(new BlockPos(3, 0, 1), pivot);
        for (Transform transform : transforms()) {
            placement.setTransform(transform);
            BlockPos min = placement.targetMin();
            GhostMapping frame = new GhostMapping(new Box(BlockPos.ORIGIN, new BlockPos(5, 1, 2)), transform, min.x(), min.y(),
                    min.z());
            // The pivot stays put; flipped, the box stays where it is and the pivot's cell turns over within it.
            assertEquals(new BlockPos(10, 64, 10),
                    new BlockPos(frame.worldX(pivot.x(), pivot.z()), frame.worldY(transform.mapY(0, 2)),
                            frame.worldZ(pivot.x(), pivot.z())),
                    transform.toString());
            assertEquals(64, min.y(), transform + ": the flip never moves the box");
            assertEquals(transform.size(6, 2, 3), placement.size());
        }
    }

    @Test
    void rotateAndFlipCompose() {
        Placement placement = Placement.paste(SOURCE, new BlockPos(3, 1, 3), BlockPos.ORIGIN, BlockPos.ORIGIN);
        assertTrue(placement.rotate(1));
        assertEquals(new Transform(1, Mirror.NONE), placement.transform());
        assertTrue(placement.rotate(1));
        assertTrue(placement.rotate(-1));
        assertEquals(new Transform(1, Mirror.NONE), placement.transform());
        assertTrue(placement.mirror(Mirror.X));
        assertEquals(new Transform(1, Mirror.NONE).compose(new Transform(0, Mirror.X)), placement.transform());
        assertTrue(placement.mirror(Mirror.X));
        assertEquals(new Transform(1, Mirror.NONE), placement.transform(), "mirroring twice undoes it");
        assertTrue(placement.rotate(3));
        assertTrue(placement.transform().isIdentity());
    }

    @Test
    void theMoveGhostMatchesTheMoveProgram() {
        Box box = new Box(new BlockPos(20, 64, 20), new BlockPos(24, 66, 23));
        FakeWorld world = new FakeWorld(STATES, -64, 320);
        BlockBuffer local = source();
        for (long key : local.sortedKeys()) {
            SectionBuffer section = local.section(key);
            section.forEachPresent(i -> world.set(20 + SectionBuffer.localX(i), 64 + SectionBuffer.localY(i),
                    20 + SectionBuffer.localZ(i), section.get(i)));
        }
        GhostVolume captured = GhostBaker.capture(world, box);
        assertEquals(5, captured.blockCount());
        for (Transform transform : transforms()) {
            Placement placement = Placement.move(box);
            assertTrue(placement.unmoved());
            placement.setTransform(transform);
            placement.nudge(9, 1, -7);
            FakeWorld copy = new FakeWorld(STATES, -64, 320);
            for (long key : local.sortedKeys()) {
                SectionBuffer section = local.section(key);
                section.forEachPresent(i -> copy.set(20 + SectionBuffer.localX(i), 64 + SectionBuffer.localY(i),
                        20 + SectionBuffer.localZ(i), section.get(i)));
            }
            OpSpec.Move move = assertInstanceOf(OpSpec.Move.class, placement.op(PasteOptions.DEFAULT, STATES.air()));
            assertEquals(new Pattern.Single(STATES.air()), move.leave());
            FakeExecutor.run(OpCompiler.compile(move, context(Map.of())), copy);
            Map<BlockPos, Integer> moved = written(copy, placement.targetBox());
            assertEquals(moved, ghostCells(captured, placement, true), transform.toString());
            assertEquals(moved, ghostCells(captured, placement, false), transform.toString());
        }
    }

    /** The options' Into reaches a move's and a stack's op (a paste carries the options whole). */
    @Test
    void theOpCarriesThePasteIntoFilter() {
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(3, 65, 1));
        PasteOptions air = PasteOptions.DEFAULT.withInto(PasteOptions.Into.AIR);
        Placement move = Placement.move(box);
        move.nudge(2, 0, 0);
        assertEquals(PasteOptions.Into.AIR, assertInstanceOf(OpSpec.Move.class, move.op(air, STATES.air())).into());
        assertEquals(PasteOptions.Into.EVERYTHING,
                assertInstanceOf(OpSpec.Move.class, move.op(PasteOptions.DEFAULT, STATES.air())).into());
        Placement stack = Placement.stack(box, new int[] {1, 0, 0});
        assertEquals(new OpSpec.Stack(new Region.Cuboid(box), 4, 0, 0, 1, EntityFilter.NONE, Symmetry.NONE,
                PasteOptions.Into.EXISTING), stack.op(PasteOptions.DEFAULT.withInto(PasteOptions.Into.EXISTING), 0));
        Placement paste = Placement.paste(new SourceRef.Clipboard(new UUID(1, 2)), new BlockPos(2, 1, 2), BlockPos.ORIGIN,
                new BlockPos(5, 64, 5));
        assertEquals(air, assertInstanceOf(OpSpec.Paste.class, paste.op(air, 0)).o());
    }

    @Test
    void stackCopiesRepeatTheOffsetAndCannotTurn() {
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(3, 65, 1));
        Placement placement = Placement.stack(box, new int[] {0, 0, 1});
        assertEquals(new BlockPos(0, 0, 2), placement.offset(), "one box length along the facing direction");
        assertFalse(placement.rotate(1));
        assertFalse(placement.mirror(Mirror.X));
        assertFalse(placement.setTransform(new Transform(1, Mirror.NONE, true)), "a stack never turns");
        assertTrue(placement.transform().isIdentity());
        assertEquals(1, placement.count());
        assertEquals(256, placement.setCount(1000));
        assertEquals(1, placement.setCount(-4));
        placement.setCount(3);
        placement.nudge(1, 0, 0);
        OpSpec.Stack stack = assertInstanceOf(OpSpec.Stack.class, placement.op(PasteOptions.DEFAULT, 0));
        assertEquals(new OpSpec.Stack(box, 1, 0, 2, 3), stack);
        assertEquals(List.of(box.offset(1, 0, 2), box.offset(2, 0, 4), box.offset(3, 0, 6)), placement.copies(16));
        assertEquals(2, placement.copies(2).size());
        assertEquals(8 * 3, placement.blocks(8));
    }

    /**
     * V flips a stack's copies upside down: the op carries the flip, and each copy's ghost (baked, or by its model
     * matrix) is exactly what the stack program writes there.
     */
    @Test
    void aFlippedStackGhostMatchesTheStackProgram() {
        Box box = new Box(new BlockPos(20, 64, 20), new BlockPos(24, 66, 23));
        BlockBuffer local = source();
        FakeWorld world = new FakeWorld(STATES, -64, 320);
        for (long key : local.sortedKeys()) {
            SectionBuffer section = local.section(key);
            section.forEachPresent(i -> world.set(20 + SectionBuffer.localX(i), 64 + SectionBuffer.localY(i),
                    20 + SectionBuffer.localZ(i), section.get(i)));
        }
        GhostVolume captured = GhostBaker.capture(world, box);
        Placement placement = Placement.stack(box, new int[] {1, 0, 0});
        assertTrue(placement.flipUpsideDown());
        assertEquals(Transform.UPSIDE_DOWN, placement.transform());
        placement.setCount(2);
        placement.nudge(3, 4, 0);
        OpSpec.Stack stack = assertInstanceOf(OpSpec.Stack.class, placement.op(PasteOptions.DEFAULT, 0));
        assertTrue(stack.upsideDown());
        FakeExecutor.run(OpCompiler.compile(stack, context(Map.of())), world);
        for (Box copy : placement.copies(16)) {
            Map<BlockPos, Integer> written = written(world, copy);
            for (boolean baked : new boolean[] {false, true}) {
                GhostVolume shown = baked ? GhostBaker.bake(captured.frame(), captured.sections(), placement.transform(),
                        STATES) : captured;
                GhostMapping mapping = GhostPlacement.of(shown, copy.min().x(), copy.min().y(), copy.min().z())
                        .withTransform(baked ? Transform.IDENTITY : placement.transform()).mapping();
                Map<BlockPos, Integer> ghost = new HashMap<>();
                forEachCell(shown, (x, y, z, h) -> ghost.put(new BlockPos(mapping.worldX(x, z), mapping.worldY(y),
                        mapping.worldZ(x, z)), baked ? h : STATES.flip(h)));
                assertEquals(written, ghost, (baked ? "baked" : "model-matrix") + " copy at " + copy);
            }
        }
        assertTrue(placement.flipUpsideDown());
        assertTrue(placement.transform().isIdentity(), "flipping twice turns it back");
    }

    @Test
    void anUnmovedMoveOrStackHasNoOp() {
        Box box = new Box(new BlockPos(0, 0, 0), new BlockPos(1, 1, 1));
        Placement move = Placement.move(box);
        assertThrows(IllegalStateException.class, () -> move.op(PasteOptions.DEFAULT, 0));
        move.rotate(1);
        assertFalse(move.unmoved(), "turning in place is a move");
        Placement stack = Placement.stack(box, new int[] {1, 0, 0});
        stack.moveTo(stack.pivotWorld().offset(-2, 0, 0));
        assertTrue(stack.unmoved());
        assertThrows(IllegalStateException.class, () -> stack.op(PasteOptions.DEFAULT, 0));
    }
}
