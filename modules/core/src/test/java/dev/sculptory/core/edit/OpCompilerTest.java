package dev.sculptory.core.edit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Transform;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpCompilerTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final CompileContext context = new CompileContext() {
        @Override
        public StateSpace states() {
            return states;
        }

        @Override
        public Optional<SourceBlocks> source(SourceRef ref) {
            return Optional.empty();
        }
    };
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int grass = states.state("minecraft:grass_block");
    private final int snowyGrass = states.state("minecraft:grass_block[snowy=true]");
    private final int sand = states.state("minecraft:sand");

    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return Box.of(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    private EditProgram compile(OpSpec op) {
        return OpCompiler.compile(op, context);
    }

    @Test
    void fillMetadata() {
        Box b = box(-7, 3, 5, 20, 18, 40);
        EditProgram program = compile(new OpSpec.Fill(b, new Pattern.Single(stone), CellMask.ANY));
        assertEquals("Fill", program.label());
        assertEquals(b, program.bounds());
        assertEquals(b.volume(), program.estimatedCells());
        assertEquals(0, program.sourceSections().length);
        LongArrayList expected = new LongArrayList();
        b.forEachSectionKey(expected::add);
        assertArrayEquals(expected.toLongArray(), program.sectionOrder());
    }

    @Test
    void sectionOrderIsChunkLocal() {
        EditProgram program = compile(new OpSpec.Fill(box(-40, -70, -40, 40, 70, 40), new Pattern.Single(stone), CellMask.ANY));
        long[] order = program.sectionOrder();
        for (int i = 1; i < order.length; i++) {
            long a = order[i - 1], b = order[i];
            int ax = BlockBuffer.keyX(a), az = BlockBuffer.keyZ(a), ay = BlockBuffer.keyY(a);
            int bx = BlockBuffer.keyX(b), bz = BlockBuffer.keyZ(b), by = BlockBuffer.keyY(b);
            boolean ascending = ax < bx || (ax == bx && (az < bz || (az == bz && ay < by)));
            assertTrue(ascending, "section " + i + " out of order");
        }
        assertEquals(6 * 10 * 6, order.length);
    }

    @Test
    void fillWritesOnlyChangedCells() {
        FakeWorld world = new FakeWorld(states);
        Box b = box(0, 0, 0, 9, 9, 9);
        world.fill(box(0, 0, 0, 9, 4, 9), stone);
        FakeExecutor.Result result = FakeExecutor.run(compile(new OpSpec.Fill(b, new Pattern.Single(stone), CellMask.ANY)), world);
        assertEquals(500, result.written());
        assertEquals(500, result.record().before().cellCount());
        assertAll(world, b, stone);
        assertEquals(0, FakeExecutor.run(compile(new OpSpec.Fill(b, new Pattern.Single(stone), CellMask.ANY)), world).written());
    }

    @Test
    void outHoldsOnlyChangedCells() {
        FakeWorld world = new FakeWorld(states);
        world.fill(box(0, 0, 0, 15, 15, 7), stone);
        EditProgram program = compile(new OpSpec.Fill(box(0, 0, 0, 15, 15, 15), new Pattern.Single(stone), CellMask.ANY));
        SectionBuffer before = new SectionBuffer();
        world.copySection(0, 0, 0, before);
        SectionBuffer out = new SectionBuffer();
        program.compute(BlockBuffer.key(0, 0, 0), before, out, null);
        assertEquals(2048, out.presentCount());
        out.forEachPresent(i -> assertTrue(SectionBuffer.localZ(i) >= 8));
    }

    @Test
    void fillHonoursMask() {
        FakeWorld world = new FakeWorld(states);
        Box b = box(0, 0, 0, 3, 0, 3);
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 3; z++) world.set(x, 0, z, (x + z) % 2 == 0 ? dirt : grass);
        }
        FakeExecutor.run(compile(new OpSpec.Fill(b, new Pattern.Single(sand), new CellMask.States(new int[] {dirt}))), world);
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 3; z++) assertEquals((x + z) % 2 == 0 ? sand : grass, world.get(x, 0, z));
        }
    }

    @Test
    void replaceHonoursMask() {
        FakeWorld world = new FakeWorld(states);
        Box b = box(0, 0, 0, 5, 1, 5);
        world.fill(b, stone);
        world.set(1, 0, 1, grass);
        world.set(2, 1, 3, snowyGrass);
        world.set(4, 0, 4, dirt);
        CellMask anyGrass = new CellMask.Blocks(List.of(new NamespacedId("minecraft:grass_block")));
        FakeExecutor.Result result = FakeExecutor.run(compile(new OpSpec.Replace(b, anyGrass, new Pattern.Single(sand))), world);
        assertEquals(2, result.written());
        assertEquals(sand, world.get(1, 0, 1));
        assertEquals(sand, world.get(2, 1, 3));
        assertEquals(dirt, world.get(4, 0, 4));
        assertEquals(stone, world.get(0, 0, 0));
        assertEquals("Replace", compile(new OpSpec.Replace(b, anyGrass, new Pattern.Single(sand))).label());
    }

    @Test
    void eraseWritesAir() {
        FakeWorld world = new FakeWorld(states);
        Box b = box(-3, 10, -3, 3, 12, 3);
        world.fill(box(-3, 10, -3, 3, 11, 3), dirt);
        world.set(0, 11, 0, stone);
        FakeExecutor.Result all = FakeExecutor.run(compile(new OpSpec.Erase(b, new CellMask.Not(new CellMask.States(new int[] {stone})))), world);
        assertEquals(97, all.written());
        assertEquals(stone, world.get(0, 11, 0));
        assertEquals(air, world.get(-3, 10, -3));
        FakeExecutor.Result rest = FakeExecutor.run(compile(new OpSpec.Erase(b, CellMask.ANY)), world);
        assertEquals(1, rest.written());
        assertAll(world, b, air);
    }

    @Test
    void weightedPatternFollowsPositions() {
        FakeWorld world = new FakeWorld(states);
        Box b = box(0, 0, 0, 20, 3, 20);
        Pattern pattern = new Pattern.Weighted(new int[] {stone, dirt, sand}, new int[] {1, 2, 3}, 99L);
        FakeExecutor.run(compile(new OpSpec.Fill(b, pattern, CellMask.ANY)), world);
        for (int x = 0; x <= 20; x++) {
            for (int y = 0; y <= 3; y++) {
                for (int z = 0; z <= 20; z++) assertEquals(pattern.apply(states, x, y, z, air), world.get(x, y, z));
            }
        }
    }

    @Test
    void hollowCounts() {
        Box cube = box(0, 0, 0, 9, 9, 9);
        assertEquals(512, compile(new OpSpec.Hollow(cube, 1, new Pattern.Single(air))).estimatedCells());
        assertEquals(216, compile(new OpSpec.Hollow(cube, 2, new Pattern.Single(air))).estimatedCells());
        assertEquals(64, compile(new OpSpec.Hollow(cube, 3, new Pattern.Single(air))).estimatedCells());
        assertEquals(0, compile(new OpSpec.Hollow(cube, 5, new Pattern.Single(air))).estimatedCells());
        assertEquals(0, compile(new OpSpec.Hollow(cube, 16, new Pattern.Single(air))).estimatedCells());
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Hollow(cube, 17, new Pattern.Single(air))),
                "thicker than 16");
        assertEquals(0, compile(new OpSpec.Hollow(box(0, 0, 0, 1, 9, 9), 1, new Pattern.Single(air))).estimatedCells());
        assertEquals(1, compile(new OpSpec.Hollow(box(0, 0, 0, 2, 2, 2), 1, new Pattern.Single(air))).estimatedCells());
        assertEquals(8 * 3 * 8, compile(new OpSpec.Hollow(box(0, 0, 0, 9, 4, 9), 1, new Pattern.Single(air))).estimatedCells());
    }

    @Test
    void wallsCounts() {
        assertEquals(144, compile(new OpSpec.Walls(box(0, 0, 0, 9, 3, 9), 1, new Pattern.Single(stone))).estimatedCells());
        assertEquals(336, compile(new OpSpec.Walls(box(0, 0, 0, 9, 3, 9), 3, new Pattern.Single(stone))).estimatedCells());
        assertEquals(400, compile(new OpSpec.Walls(box(0, 0, 0, 9, 3, 9), 5, new Pattern.Single(stone))).estimatedCells());
        assertEquals(400, compile(new OpSpec.Walls(box(0, 0, 0, 9, 3, 9), 16, new Pattern.Single(stone))).estimatedCells());
        assertThrows(IllegalArgumentException.class,
                () -> compile(new OpSpec.Walls(box(0, 0, 0, 9, 3, 9), 99, new Pattern.Single(stone))), "thicker than 16");
        assertEquals(54, compile(new OpSpec.Walls(box(0, 0, 0, 1, 2, 8), 1, new Pattern.Single(stone))).estimatedCells());
        assertEquals(1, compile(new OpSpec.Walls(box(5, 5, 5, 5, 5, 5), 1, new Pattern.Single(stone))).estimatedCells());
    }

    /** Every cell written matches the shape predicate, across section boundaries, thicknesses and thin boxes. */
    @Test
    void hollowAndWallsShapesMatchBruteForce() {
        List<Box> boxes = List.of(box(-7, 3, 5, 20, 18, 40), box(0, 0, 0, 2, 2, 2), box(-1, 0, -1, 0, 30, 33),
                box(10, 1, 10, 42, 6, 11));
        for (Box b : boxes) {
            for (int t : new int[] {1, 2, 3, 5, 16}) {
                checkShape(b, t, true);
                checkShape(b, t, false);
            }
        }
    }

    private void checkShape(Box b, int t, boolean hollow) {
        FakeWorld world = new FakeWorld(states);
        world.fill(b, dirt);
        OpSpec op = hollow ? new OpSpec.Hollow(b, t, new Pattern.Single(air)) : new OpSpec.Walls(b, t, new Pattern.Single(stone));
        EditProgram program = compile(op);
        FakeExecutor.Result result = FakeExecutor.run(program, world);
        long expected = 0;
        for (int x = b.min().x(); x <= b.max().x(); x++) {
            for (int y = b.min().y(); y <= b.max().y(); y++) {
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    int dx = Math.min(x - b.min().x(), b.max().x() - x);
                    int dy = Math.min(y - b.min().y(), b.max().y() - y);
                    int dz = Math.min(z - b.min().z(), b.max().z() - z);
                    boolean inShape = hollow ? dx >= t && dy >= t && dz >= t : dx < t || dz < t;
                    if (inShape) expected++;
                    int want = inShape ? (hollow ? air : stone) : dirt;
                    assertEquals(want, world.get(x, y, z), op + " at " + x + "," + y + "," + z);
                }
            }
        }
        assertEquals(expected, program.estimatedCells(), op.toString());
        assertEquals(expected, result.written(), op.toString());
        assertEquals(b, program.bounds());
    }

    @Test
    void wallsSkipInteriorSections() {
        EditProgram walls = compile(new OpSpec.Walls(box(0, 0, 0, 63, 15, 63), 1, new Pattern.Single(stone)));
        assertEquals(12, walls.sectionOrder().length);
        EditProgram thick = compile(new OpSpec.Walls(box(0, 0, 0, 63, 15, 63), 16, new Pattern.Single(stone)));
        assertEquals(12, thick.sectionOrder().length);
        EditProgram narrow = compile(new OpSpec.Walls(box(0, 0, 0, 62, 15, 63), 16, new Pattern.Single(stone)));
        assertEquals(14, narrow.sectionOrder().length, "an east side 16 thick reaches back into section 2");
    }

    @Test
    void emptyHollowIsANoOp() {
        EditProgram program = compile(new OpSpec.Hollow(box(0, 0, 0, 3, 3, 3), 2, new Pattern.Single(air)));
        assertEquals(0, program.sectionOrder().length);
        assertEquals(0, program.estimatedCells());
        SectionBuffer before = SectionBuffer.uniform(stone);
        SectionBuffer out = new SectionBuffer();
        program.compute(BlockBuffer.key(0, 0, 0), before, out, null);
        assertTrue(out.isEmpty());
    }

    @Test
    void computeIgnoresSectionsOutsideTheRegion() {
        EditProgram program = compile(new OpSpec.Fill(box(0, 0, 0, 3, 3, 3), new Pattern.Single(stone), CellMask.ANY));
        SectionBuffer out = new SectionBuffer();
        program.compute(BlockBuffer.key(5, 0, 0), SectionBuffer.uniform(air), out, null);
        assertTrue(out.isEmpty());
    }

    /** A context for a world from y -64 to 319, like the overworld and {@link FakeWorld}'s default. */
    private final CompileContext overworld = new CompileContext() {
        @Override
        public StateSpace states() {
            return states;
        }

        @Override
        public Optional<SourceBlocks> source(SourceRef ref) {
            return Optional.empty();
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

    @Test
    void fillSpanningBelowTheWorldIsClipped() {
        Box b = box(0, -80, 0, 9, -50, 9);
        EditProgram program = OpCompiler.compile(new OpSpec.Fill(b, new Pattern.Single(stone), CellMask.ANY), overworld);
        assertEquals(box(0, -64, 0, 9, -50, 9), program.bounds());
        assertEquals(10 * 15 * 10, program.estimatedCells());
        assertArrayEquals(new long[] {BlockBuffer.key(0, -4, 0)}, program.sectionOrder());
        FakeWorld world = new FakeWorld(states);
        assertEquals(1500, FakeExecutor.run(program, world).written());
        assertEquals(stone, world.get(0, -64, 0));
        // Unclipped, the same box lists the section below the world too.
        assertEquals(2, compile(new OpSpec.Fill(b, new Pattern.Single(stone), CellMask.ANY)).sectionOrder().length);
    }

    @Test
    void shapesAreMeasuredOnTheWholeBoxThenClipped() {
        // Hollow inside (1..8, -69..-56, 1..8) keeps the shell of the whole box, then loses y < -64.
        EditProgram hollow = OpCompiler.compile(new OpSpec.Hollow(box(0, -70, 0, 9, -55, 9), 1, new Pattern.Single(air)), overworld);
        assertEquals(8 * 9 * 8, hollow.estimatedCells());
        assertEquals(box(0, -64, 0, 9, -55, 9), hollow.bounds());
        EditProgram walls = OpCompiler.compile(new OpSpec.Walls(box(0, 300, 0, 9, 330, 9), 1, new Pattern.Single(stone)), overworld);
        assertEquals(36 * 20, walls.estimatedCells());
        assertEquals(box(0, 300, 0, 9, 319, 9), walls.bounds());
        assertEquals(2, walls.sectionOrder().length);
    }

    @Test
    void refusesBoxesOutsideTheBuildHeight() {
        assertThrows(IllegalArgumentException.class, () -> OpCompiler.compile(
                new OpSpec.Fill(box(0, 320, 0, 5, 400, 5), new Pattern.Single(stone), CellMask.ANY), overworld));
        assertThrows(IllegalArgumentException.class, () -> OpCompiler.compile(
                new OpSpec.Erase(box(0, -100, 0, 5, -65, 5), CellMask.ANY), overworld));
    }

    @Test
    void scatterCommitNeedsAPlanTheContextKnows() {
        // This context knows no scatter plans (the default).
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.ScatterCommit(UUID.randomUUID())));
    }

    @Test
    void clipboardOpsCompile() {
        Box b = box(0, 0, 0, 1, 1, 1);
        // This context knows no paste sources.
        assertThrows(IllegalArgumentException.class, () -> compile(
                new OpSpec.Paste(new SourceRef.Clipboard(UUID.randomUUID()), BlockPos.ORIGIN, Transform.IDENTITY, PasteOptions.DEFAULT)));
        assertEquals("Move", compile(new OpSpec.Move(b, BlockPos.ORIGIN, Transform.IDENTITY, new Pattern.Single(air))).label());
        assertEquals("Stack", compile(new OpSpec.Stack(b, 1, 0, 0, 2)).label());
    }

    @Test
    void refusesStatesOutsideTheSpace() {
        Box b = box(0, 0, 0, 1, 1, 1);
        int bad = states.size();
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Fill(b, new Pattern.Single(bad), CellMask.ANY)));
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Walls(b, 1,
                new Pattern.Weighted(new int[] {stone, bad}, new int[] {1, 1}, 0L))));
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Replace(b,
                new CellMask.States(new int[] {bad}), new Pattern.Single(stone))));
    }

    @Test
    void refusesBoxesSpanningTooManySections() {
        Box huge = box(-1_000_000, 0, -1_000_000, 1_000_000, 0, 1_000_000);
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Fill(huge, new Pattern.Single(stone), CellMask.ANY)));
    }

    /**
     * A Fill with Waterlog writes only the cells it changes (air, and dry waterloggable blocks) and records only them, so
     * the undo puts back exactly those; a Dry fill the same the other way; a Waterlog of a non-fluid is refused.
     */
    @Test
    void fluidPatternFillsWriteOnlyChangedCellsAndUndoExactly() {
        int water = states.state("minecraft:water[level=0]");
        int dryStairs = states.state("minecraft:oak_stairs[facing=north]");
        int wetStairs = states.state("minecraft:oak_stairs[facing=north,waterlogged=true]");
        int kelp = states.state("minecraft:kelp[age=2]");
        Box b = box(0, 0, 0, 3, 0, 0);
        java.util.function.Supplier<FakeWorld> basin = () -> {
            FakeWorld world = new FakeWorld(states);
            world.set(0, 0, 0, stone);
            world.set(1, 0, 0, dryStairs);
            world.set(2, 0, 0, wetStairs);
            // (3, 0, 0) is air.
            return world;
        };
        FakeWorld world = basin.get();
        FakeExecutor.Result flood = FakeExecutor.run(compile(new OpSpec.Fill(b, new Pattern.Waterlog(water), CellMask.ANY)), world);
        assertEquals(2, flood.written(), "the air and the dry stairs");
        assertEquals(2, flood.record().before().cellCount());
        assertEquals(stone, world.get(0, 0, 0));
        assertEquals(wetStairs, world.get(1, 0, 0));
        assertEquals(wetStairs, world.get(2, 0, 0));
        assertEquals(water, world.get(3, 0, 0));
        CopyTestSupport.runAndUndo(compile(new OpSpec.Fill(b, new Pattern.Waterlog(water), CellMask.ANY)), basin.get(), b, "flood");

        world.set(0, 0, 0, kelp);
        FakeExecutor.Result drain = FakeExecutor.run(compile(new OpSpec.Fill(b, new Pattern.Dry(), CellMask.ANY)), world);
        assertEquals(4, drain.written(), "the kelp, both stairs and the water");
        assertEquals(air, world.get(0, 0, 0));
        assertEquals(dryStairs, world.get(1, 0, 0));
        assertEquals(dryStairs, world.get(2, 0, 0));
        assertEquals(air, world.get(3, 0, 0));
        assertEquals(0, FakeExecutor.run(compile(new OpSpec.Fill(b, new Pattern.Dry(), CellMask.ANY)), world).written());
        FakeWorld flooded = basin.get();
        flooded.set(3, 0, 0, water);
        CopyTestSupport.runAndUndo(compile(new OpSpec.Fill(b, new Pattern.Dry(), CellMask.ANY)), flooded, b, "drain");

        assertThrows(IllegalArgumentException.class,
                () -> compile(new OpSpec.Fill(b, new Pattern.Waterlog(stone), CellMask.ANY)), "not a fluid");
        assertThrows(IllegalArgumentException.class,
                () -> compile(new OpSpec.Fill(b, new Pattern.Waterlog(states.state("minecraft:water[level=4]")), CellMask.ANY)),
                "flowing water is not a source");
        assertThrows(IllegalArgumentException.class,
                () -> compile(new OpSpec.Fill(b, new Pattern.Waterlog(states.size()), CellMask.ANY)), "outside the space");
    }

    /**
     * A state pattern that only changes a property keeps the cell's block entity: a chest flooded or drained keeps its
     * items (the tile travels into the program's output), and the undo puts back exactly what was there.
     */
    @Test
    void fluidPatternsKeepBlockEntitiesOfTheSameBlock() {
        int water = states.state("minecraft:water[level=0]");
        int dryChest = states.state("minecraft:chest[facing=north]");
        int wetChest = states.state("minecraft:chest[facing=north,waterlogged=true]");
        Box b = box(0, 0, 0, 1, 0, 0);
        FakeWorld world = new FakeWorld(states);
        world.set(0, 0, 0, dryChest);
        world.setTile(0, 0, 0, CopyTestSupport.chest("minecraft:diamond", 5));
        FakeExecutor.Result flood = FakeExecutor.run(compile(new OpSpec.Fill(b, new Pattern.Waterlog(water), CellMask.ANY)), world);
        assertEquals(2, flood.written());
        assertEquals(wetChest, world.get(0, 0, 0));
        assertTrue(CopyTestSupport.chest("minecraft:diamond", 5).sameContent(world.tile(0, 0, 0)), "the chest keeps its diamonds");
        FakeExecutor.run(compile(new OpSpec.Fill(b, new Pattern.Dry(), CellMask.ANY)), world);
        assertEquals(dryChest, world.get(0, 0, 0));
        assertEquals(air, world.get(1, 0, 0));
        assertTrue(CopyTestSupport.chest("minecraft:diamond", 5).sameContent(world.tile(0, 0, 0)), "and after the drain");

        FakeWorld again = new FakeWorld(states);
        again.set(0, 0, 0, dryChest);
        again.setTile(0, 0, 0, CopyTestSupport.chest("minecraft:diamond", 5));
        CopyTestSupport.runAndUndo(compile(new OpSpec.Fill(b, new Pattern.Waterlog(water), CellMask.ANY)), again, b, "flood a chest");
        assertTrue(CopyTestSupport.chest("minecraft:diamond", 5).sameContent(again.tile(0, 0, 0)), "exact after the undo");

        // A Single still writes the state's default block entity, as before.
        FakeWorld plain = new FakeWorld(states);
        plain.set(0, 0, 0, dryChest);
        plain.setTile(0, 0, 0, CopyTestSupport.chest("minecraft:diamond", 5));
        FakeExecutor.run(compile(new OpSpec.Fill(box(0, 0, 0, 0, 0, 0), new Pattern.Single(wetChest), CellMask.ANY)), plain);
        assertEquals(wetChest, plain.get(0, 0, 0));
        assertEquals(null, plain.tile(0, 0, 0), "a block choice replaces the block entity");
    }

    /**
     * Tinker's "Apply to all like it in the selection": a Fill with SetProperty changes that one property on the cells of
     * the template's block only (other properties kept), writes nothing else, keeps a chest's items, and undoes exactly;
     * a template without the property is refused.
     */
    @Test
    void propertyPatternSetsOnePropertyOfOneBlockAndUndoesExactly() {
        int straightNorth = states.state("minecraft:oak_stairs[facing=north,shape=straight]");
        int straightEastTop = states.state("minecraft:oak_stairs[facing=east,half=top,shape=straight]");
        int outerNorth = states.state("minecraft:oak_stairs[facing=north,shape=outer_left]");
        int outerEastTop = states.state("minecraft:oak_stairs[facing=east,half=top,shape=outer_left]");
        int template = states.state("minecraft:oak_stairs[facing=south,shape=outer_left,waterlogged=true]");
        int otherStairs = states.state("minecraft:stone_brick_stairs[facing=north]");
        Box b = box(0, 0, 0, 4, 0, 0);
        java.util.function.Supplier<FakeWorld> row = () -> {
            FakeWorld world = new FakeWorld(states);
            world.set(0, 0, 0, straightNorth);
            world.set(1, 0, 0, straightEastTop);
            world.set(2, 0, 0, outerNorth);
            world.set(3, 0, 0, otherStairs);
            world.set(4, 0, 0, stone);
            return world;
        };
        Pattern set = new Pattern.SetProperty(template, "shape");
        FakeWorld world = row.get();
        FakeExecutor.Result result = FakeExecutor.run(compile(new OpSpec.Fill(b, set, CellMask.ANY)), world);
        assertEquals(2, result.written(), "the two straight oak stairs");
        assertEquals(outerNorth, world.get(0, 0, 0), "facing and water kept");
        assertEquals(outerEastTop, world.get(1, 0, 0), "facing, half and water kept");
        assertEquals(outerNorth, world.get(2, 0, 0));
        assertEquals(otherStairs, world.get(3, 0, 0), "another block is left alone");
        assertEquals(stone, world.get(4, 0, 0));
        CopyTestSupport.runAndUndo(compile(new OpSpec.Fill(b, set, CellMask.ANY)), row.get(), b, "set shape");

        int northChest = states.state("minecraft:chest[facing=north]");
        int eastChest = states.state("minecraft:chest[facing=east]");
        FakeWorld chests = new FakeWorld(states);
        chests.set(0, 0, 0, northChest);
        chests.setTile(0, 0, 0, CopyTestSupport.chest("minecraft:diamond", 5));
        FakeExecutor.run(compile(new OpSpec.Fill(box(0, 0, 0, 0, 0, 0), new Pattern.SetProperty(eastChest, "facing"),
                CellMask.ANY)), chests);
        assertEquals(eastChest, chests.get(0, 0, 0));
        assertTrue(CopyTestSupport.chest("minecraft:diamond", 5).sameContent(chests.tile(0, 0, 0)), "the chest keeps its items");

        assertThrows(IllegalArgumentException.class,
                () -> compile(new OpSpec.Fill(b, new Pattern.SetProperty(stone, "shape"), CellMask.ANY)), "no such property");
        assertThrows(IllegalArgumentException.class,
                () -> compile(new OpSpec.Fill(b, new Pattern.SetProperty(states.size(), "shape"), CellMask.ANY)),
                "outside the space");
    }

    private static void assertAll(FakeWorld world, Box b, int state) {
        for (int x = b.min().x(); x <= b.max().x(); x++) {
            for (int y = b.min().y(); y <= b.max().y(); y++) {
                for (int z = b.min().z(); z <= b.max().z(); z++) assertEquals(state, world.get(x, y, z));
            }
        }
    }
}
