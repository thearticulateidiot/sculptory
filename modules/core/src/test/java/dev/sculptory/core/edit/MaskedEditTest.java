package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.box;
import static dev.sculptory.core.edit.CopyTestSupport.grow;
import static dev.sculptory.core.edit.CopyTestSupport.runAndUndo;
import static dev.sculptory.core.edit.CopyTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CopyTestSupport.Cell;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.mask.MaskedProgram;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The global mask on bulk edits: judged where cells land against the world before
 * the edit ({@link MaskedProgram}), and at the source for a Move ({@code CompileContext.sourceMask()}); exactly
 * undoable.
 */
class MaskedEditTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int planks = states.state("minecraft:oak_planks");
    private final int sand = states.state("minecraft:sand");

    private CompileContext context(BoundMask sourceMask) {
        CompileContext base = CopyTestSupport.context(states, Map.of());
        return new CompileContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef ref) {
                return base.source(ref);
            }

            @Override
            public int bottomY() {
                return base.bottomY();
            }

            @Override
            public int topYExclusive() {
                return base.topYExclusive();
            }

            @Override
            public BoundMask sourceMask() {
                return sourceMask;
            }
        };
    }

    private EditProgram masked(OpSpec op, EditMask mask) {
        return MaskedProgram.wrap(OpCompiler.compile(op, context(BoundMask.ALL)), mask.bind(states));
    }

    private static EditMask mask(MaskRule... rules) {
        List<MaskEntry> entries = new ArrayList<>();
        for (MaskRule rule : rules) entries.add(MaskEntry.of(rule));
        return new EditMask(entries, false);
    }

    @Test
    void anOffMaskLeavesTheProgramAsItIs() {
        EditProgram program = OpCompiler.compile(new OpSpec.Fill(box(0, 0, 0, 3, 3, 3), new Pattern.Single(stone),
                CellMask.ANY), context(BoundMask.ALL));
        assertSame(program, MaskedProgram.wrap(program, EditMask.NONE.bind(states)));
    }

    @Test
    void aNeighbourMaskReadsTheWorldAsItWasBeforeTheFillAcrossSections() {
        FakeWorld world = new FakeWorld(states);
        // A stone floor at y 15, the top layer of section 0; the fill spans sections 1 and 2 above it and two chunks.
        world.fill(box(-4, 15, -4, 20, 15, 20), stone);
        Box region = box(-2, 16, -2, 18, 40, 18);
        EditMask onStone = mask(new MaskRule.OnTopOf(BlockSet.parse("minecraft:stone")));
        EditProgram program = masked(new OpSpec.Fill(region, new Pattern.Single(stone), CellMask.ANY), onStone);
        runAndUndo(program, world, grow(region, 2), "fill on top of stone");
        FakeWorld again = new FakeWorld(states);
        again.fill(box(-4, 15, -4, 20, 15, 20), stone);
        FakeExecutor.run(program, again);
        for (int x = -2; x <= 18; x++) {
            for (int z = -2; z <= 18; z++) {
                assertEquals(stone, again.get(x, 16, z), "the layer on the old floor");
                for (int y = 17; y <= 40; y++) {
                    assertEquals(air, again.get(x, y, z), "a cell on newly written stone is not on the old floor: "
                            + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void aMaskedFillMatchesTheCellByCellReferenceAndUndoesExactly() {
        Random random = new Random(7);
        int[] palette = {air, air, stone, dirt, planks, sand};
        Box region = box(-3, 10, -5, 19, 35, 12);
        Box world = grow(region, 2);
        List<EditMask> masks = List.of(
                mask(new MaskRule.NextTo(BlockSet.parse("minecraft:dirt;minecraft:oak_planks")), new MaskRule.NotAir()),
                new EditMask(List.of(new MaskEntry(new MaskRule.Exposed(), true),
                        MaskEntry.of(new MaskRule.Under(BlockSet.parse("#minecraft:sand")))), true),
                mask(new MaskRule.Height(12, 30), new MaskRule.Chance(40, 3L), new MaskRule.Is(BlockSet.parse(
                        "minecraft:air;minecraft:stone"))),
                mask(new MaskRule.Slope(1, 16), new MaskRule.Solid()));
        for (EditMask mask : masks) {
            FakeWorld live = new FakeWorld(states);
            for (int x = world.min().x(); x <= world.max().x(); x++) {
                for (int y = world.min().y(); y <= world.max().y(); y++) {
                    for (int z = world.min().z(); z <= world.max().z(); z++) {
                        live.set(x, y, z, palette[random.nextInt(palette.length)]);
                    }
                }
            }
            FakeWorld reference = copy(live, world);
            BoundMask bound = mask.bind(states);
            Map<BlockPos, Cell> expected = snapshot(live, world);
            for (int x = region.min().x(); x <= region.max().x(); x++) {
                for (int y = region.min().y(); y <= region.max().y(); y++) {
                    for (int z = region.min().z(); z <= region.max().z(); z++) {
                        if (bound.test(x, y, z, reference.get(x, y, z), reference)) {
                            expected.put(new BlockPos(x, y, z), new Cell(planks, null));
                        }
                    }
                }
            }
            EditProgram program = masked(new OpSpec.Fill(region, new Pattern.Single(planks), CellMask.ANY), mask);
            if (bound.reach() > 0) {
                assertEquals(program.sectionOrder().length, program.sourceSections().length,
                        "every written section is snapshotted");
            }
            FakeWorld run = copy(live, world);
            FakeExecutor.run(program, run);
            CopyTestSupport.assertWorld(expected, run, "masked fill " + mask);
            runAndUndo(program, live, world, "masked fill " + mask);
        }
    }

    @Test
    void aMaskChangeBetweenOpsAppliesToTheLaterOpOnly() {
        FakeWorld world = new FakeWorld(states);
        world.fill(box(0, 0, 0, 7, 0, 7), dirt);
        world.fill(box(0, 0, 0, 3, 0, 7), stone);
        Box region = box(0, 0, 0, 7, 0, 7);
        FakeExecutor.run(masked(new OpSpec.Fill(region, new Pattern.Single(planks), CellMask.ANY),
                mask(new MaskRule.Is(BlockSet.parse("minecraft:stone")))), world);
        FakeExecutor.run(masked(new OpSpec.Fill(region, new Pattern.Single(sand), CellMask.ANY),
                mask(new MaskRule.Is(BlockSet.parse("minecraft:dirt")))), world);
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 7; z++) assertEquals(x <= 3 ? planks : sand, world.get(x, 0, z), x + "," + z);
        }
    }

    @Test
    void aMaskedStackIsJudgedWhereItsCopiesLand() {
        FakeWorld world = new FakeWorld(states);
        world.fill(box(0, 0, 0, 3, 0, 3), planks);
        world.fill(box(4, 0, 0, 11, 0, 1), stone);
        EditProgram program = masked(new OpSpec.Stack(box(0, 0, 0, 3, 0, 3), 4, 0, 0, 2),
                mask(new MaskRule.Is(BlockSet.parse("minecraft:stone"))));
        runAndUndo(program, world, box(-1, -1, -1, 13, 1, 5), "masked stack");
        for (int x = 4; x <= 11; x++) {
            for (int z = 0; z <= 3; z++) {
                assertEquals(z <= 1 ? planks : air, world.get(x, 0, z), "only landing cells that were stone: " + x + "," + z);
            }
        }
    }

    // ------------------------------------------------------------------ Move: masked at the source

    @Test
    void aMoveLiftsOnlyTheMatchingBlocksAndLandsThemUnmasked() {
        FakeWorld world = new FakeWorld(states);
        Box source = box(0, 0, 0, 3, 3, 3);
        Random random = new Random(11);
        int stones = 0;
        for (int x = 0; x <= 3; x++) {
            for (int y = 0; y <= 3; y++) {
                for (int z = 0; z <= 3; z++) {
                    boolean isStone = random.nextBoolean();
                    world.set(x, y, z, isStone ? stone : dirt);
                    if (isStone) stones++;
                }
            }
        }
        // The destination holds sand, which the source mask (stone only) does not name: its writes are not masked.
        world.fill(box(10, 0, 0, 13, 3, 3), sand);
        FakeWorld original = copy(world, box(-1, -1, -1, 15, 5, 5));
        BoundMask stoneOnly = mask(new MaskRule.Is(BlockSet.parse("minecraft:stone"))).bind(states);
        OpSpec.Move move = new OpSpec.Move(source, new BlockPos(10, 0, 0), Transform.IDENTITY, new Pattern.Single(air));
        EditProgram program = OpCompiler.compile(move, context(stoneOnly));
        runAndUndo(program, world, box(-1, -1, -1, 15, 5, 5), "source-masked move");
        int landed = 0;
        for (int x = 0; x <= 3; x++) {
            for (int y = 0; y <= 3; y++) {
                for (int z = 0; z <= 3; z++) {
                    boolean wasStone = original.get(x, y, z) == stone;
                    assertEquals(wasStone ? air : dirt, world.get(x, y, z), "the source keeps what was not lifted");
                    assertEquals(wasStone ? stone : sand, world.get(x + 10, y, z), "lifted blocks land, the rest stays");
                    if (world.get(x + 10, y, z) == stone) landed++;
                }
            }
        }
        assertEquals(stones, landed, "every lifted block lands once");
    }

    @Test
    void anOverlappingSourceMaskedMoveDuplicatesNothing() {
        for (int dx = 1; dx <= 3; dx++) {
            FakeWorld world = new FakeWorld(states);
            Box source = box(0, 0, 0, 5, 1, 1);
            for (int x = 0; x <= 5; x++) {
                for (int y = 0; y <= 1; y++) {
                    for (int z = 0; z <= 1; z++) world.set(x, y, z, (x + y + z) % 2 == 0 ? stone : planks);
                }
            }
            Box area = box(-1, -1, -1, 10, 3, 3);
            int stonesBefore = count(world, area, stone), planksBefore = count(world, area, planks);
            BoundMask stoneOnly = mask(new MaskRule.Is(BlockSet.parse("minecraft:stone"))).bind(states);
            EditProgram program = OpCompiler.compile(new OpSpec.Move(source, new BlockPos(dx, 0, 0), Transform.IDENTITY,
                    new Pattern.Single(air)), context(stoneOnly));
            runAndUndo(program, world, area, "overlapping move by " + dx);
            assertTrue(count(world, area, stone) <= stonesBefore, "no stone is duplicated (by " + dx + ")");
            assertTrue(count(world, area, planks) <= planksBefore, "no planks are duplicated (by " + dx + ")");
            for (int x = 0; x <= 5 + dx; x++) {
                for (int y = 0; y <= 1; y++) {
                    for (int z = 0; z <= 1; z++) {
                        boolean landsStone = x >= dx && x - dx <= 5 && (x - dx + y + z) % 2 == 0;
                        if (landsStone) assertEquals(stone, world.get(x, y, z), "a lifted stone lands at " + x);
                    }
                }
            }
        }
    }

    @Test
    void aSourceMaskReadingNeighboursDecidesEachCellOnceAcrossSections() {
        // A dirt column on stone, y 1 to 40 (three sections), moved up 16: each cell is lifted when it sits on dirt or
        // stone as the world was before the move, however the sections are ordered.
        FakeWorld world = new FakeWorld(states);
        world.fill(box(0, 0, 0, 1, 0, 1), stone);
        world.fill(box(0, 1, 0, 1, 40, 1), dirt);
        BoundMask onGround = mask(new MaskRule.OnTopOf(BlockSet.parse("minecraft:stone;minecraft:dirt"))).bind(states);
        OpSpec.Move move = new OpSpec.Move(box(0, 1, 0, 1, 40, 1), new BlockPos(0, 16, 0), Transform.IDENTITY,
                new Pattern.Single(air));
        EditProgram program = OpCompiler.compile(move, context(onGround));
        assertTrue(java.util.Arrays.stream(program.sourceSections()).boxed().toList().containsAll(
                java.util.Arrays.stream(program.sectionOrder()).boxed().toList()), "every written section is snapshotted");
        Box area = box(-1, -1, -1, 2, 60, 2);
        int dirtBefore = count(world, area, dirt);
        runAndUndo(program, world, area, "neighbour-masked move");
        assertEquals(dirtBefore, count(world, area, dirt), "no block lost or duplicated");
        for (int y = 1; y <= 56; y++) {
            assertEquals(y <= 16 ? air : dirt, world.get(0, y, 0), "y " + y);
        }
    }

    private int count(FakeWorld world, Box box, int state) {
        int n = 0;
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    if (world.get(x, y, z) == state) n++;
                }
            }
        }
        return n;
    }

    private FakeWorld copy(FakeWorld world, Box box) {
        FakeWorld copy = new FakeWorld(states);
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) copy.set(x, y, z, world.get(x, y, z));
            }
        }
        return copy;
    }
}
