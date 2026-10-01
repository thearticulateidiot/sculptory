package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.box;
import static dev.sculptory.core.edit.CopyTestSupport.chest;
import static dev.sculptory.core.edit.CopyTestSupport.grow;
import static dev.sculptory.core.edit.CopyTestSupport.runAndUndo;
import static dev.sculptory.core.edit.CopyTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CopyTestSupport.Cell;
import dev.sculptory.core.edit.PasteOptions.Into;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Transform;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The paste-into filter on Paste, Move and Stack: each {@link Into} against a
 * world of air, blocks, water and plants, the includeAir interplay, a move's landing filtered while its source
 * vacates, symmetric copies, and exact undo and redo throughout.
 */
class PasteIntoTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final Map<SourceRef, SourceBlocks> sources = new HashMap<>();
    private final CompileContext context = CopyTestSupport.context(states, sources);
    private final SourceRef ref = new SourceRef.Clipboard(UUID.randomUUID());
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int sand = states.state("minecraft:sand");
    private final int water = states.state("minecraft:water");
    private final int grass = states.state("minecraft:short_grass");
    private final int poppy = states.state("minecraft:poppy");
    private final int chestState = states.state("minecraft:chest");

    /** The world states a landing cell may hold: air, blocks, a fluid, plants and a container. */
    private int[] landingStates() {
        return new int[] {air, stone, dirt, water, grass, poppy, chestState};
    }

    /** A world whose cells in {@code box} cycle through {@link #landingStates} (chests with distinct contents). */
    private FakeWorld mixedWorld(Box box, long seed) {
        Random random = new Random(seed);
        int[] palette = landingStates();
        FakeWorld world = new FakeWorld(states);
        int chests = 0;
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    int state = palette[random.nextInt(palette.length)];
                    world.set(x, y, z, state);
                    if (state == chestState) world.setTile(x, y, z, chest("minecraft:emerald", ++chests));
                }
            }
        }
        return world;
    }

    /** The contract's rule: EXISTING writes on a non-air state, AIR on an air state; fluids and plants are not air. */
    private boolean writable(Into into, int before) {
        boolean isAir = StateFlags.has(states.flags(before), StateFlags.AIR);
        return switch (into) {
            case EVERYTHING -> true;
            case EXISTING -> !isAir;
            case AIR -> isAir;
        };
    }

    private static Cell cell(int state) {
        return new Cell(state, null);
    }

    // ------------------------------------------------------------------ paste

    /** A 4 × 2 × 3 source: stone, dirt, a chest, an air hole and a sand cell per layer. */
    private Clipboard source() {
        return Clipboard.builder(states, new BlockPos(4, 2, 3))
                .set(0, 0, 0, stone).set(1, 0, 0, dirt).set(2, 0, 0, air).set(3, 0, 0, sand)
                .set(0, 0, 1, chestState).setTile(0, 0, 1, chest("minecraft:diamond", 3)).set(1, 0, 1, stone)
                .set(2, 0, 1, dirt).set(3, 0, 1, air)
                .set(0, 0, 2, air).set(1, 0, 2, stone).set(2, 0, 2, stone).set(3, 0, 2, dirt)
                .set(0, 1, 0, dirt).set(1, 1, 0, air).set(2, 1, 0, stone).set(3, 1, 0, stone)
                .set(0, 1, 1, stone).set(1, 1, 1, dirt).set(2, 1, 1, air).set(3, 1, 1, sand)
                .set(0, 1, 2, stone).set(1, 1, 2, air).set(2, 1, 2, dirt).set(3, 1, 2, stone)
                .build();
    }

    private EditProgram paste(Clipboard clipboard, BlockPos origin, PasteOptions options, Symmetry symmetry) {
        sources.put(ref, clipboard.toSource());
        return OpCompiler.compile(new OpSpec.Paste(ref, origin, Transform.IDENTITY, options, symmetry), context);
    }

    /** What a paste of {@code clipboard} at {@code origin} (anchor at the origin cell) leaves, cell by cell. */
    private Map<BlockPos, Cell> expectedPaste(Map<BlockPos, Cell> before, Clipboard clipboard, BlockPos origin,
                                              PasteOptions options) {
        Map<BlockPos, Cell> expected = new HashMap<>(before);
        BlockPos size = clipboard.size();
        for (int x = 0; x < size.x(); x++) {
            for (int y = 0; y < size.y(); y++) {
                for (int z = 0; z < size.z(); z++) {
                    int state = clipboard.get(x, y, z);
                    if (state < 0) continue;
                    if (!options.includeAir() && StateFlags.has(states.flags(state), StateFlags.AIR)) continue;
                    BlockPos at = origin.offset(x, y, z);
                    if (!writable(options.into(), before.get(at).state())) continue;
                    expected.put(at, new Cell(state, clipboard.tile(x, y, z)));
                }
            }
        }
        return expected;
    }

    /**
     * Every Into with and without includeAir over a mixed world: EVERYTHING writes every present (non-air) source
     * cell, EXISTING only onto non-air cells (water, grass and poppies included), AIR only onto air; with includeAir
     * and EXISTING source air erases existing blocks, with AIR it changes nothing; the written count is the changed
     * cells, and undo and redo are exact.
     */
    @Test
    void pasteWritesOnlyTheLandingCellsIntoAllows() {
        Clipboard clipboard = source();
        BlockPos origin = new BlockPos(3, 1, 2);
        Box target = box(3, 1, 2, 6, 2, 4);
        for (Into into : Into.values()) {
            for (boolean includeAir : new boolean[] {false, true}) {
                PasteOptions options = new PasteOptions(includeAir, false, true, into);
                FakeWorld world = mixedWorld(grow(target, 2), 7L * into.ordinal() + (includeAir ? 1 : 0));
                Map<BlockPos, Cell> before = snapshot(world, grow(target, 2));
                Map<BlockPos, Cell> expected = expectedPaste(before, clipboard, origin, options);
                String what = into + (includeAir ? " with air" : "");
                FakeExecutor.Result result = runAndUndo(paste(clipboard, origin, options, Symmetry.NONE), world,
                        grow(target, 2), what);
                CopyTestSupport.assertWorld(expected, world, what);
                long changed = expected.entrySet().stream().filter(e -> !e.getValue().equals(before.get(e.getKey()))).count();
                assertEquals(changed, result.written(), what + ": written cells");
                assertTrue(changed > 0, what + ": something changes in a mixed world");
            }
        }
    }

    /** EXISTING onto pure air writes nothing (and records nothing); AIR onto solid stone likewise. */
    @Test
    void aFilterThatMatchesNothingWritesNothing() {
        Clipboard clipboard = source();
        BlockPos origin = new BlockPos(0, 0, 0);
        FakeWorld empty = new FakeWorld(states);
        FakeExecutor.Result nothing = FakeExecutor.run(paste(clipboard, origin, PasteOptions.DEFAULT.withInto(Into.EXISTING),
                Symmetry.NONE), empty);
        assertEquals(0, nothing.written(), "EXISTING over air");
        assertTrue(nothing.record().before().isEmpty(), "nothing recorded");
        FakeWorld solid = new FakeWorld(states);
        solid.fill(box(-1, -1, -1, 5, 3, 4), stone);
        FakeExecutor.Result none = FakeExecutor.run(paste(clipboard, origin, PasteOptions.DEFAULT.withInto(Into.AIR),
                Symmetry.NONE), solid);
        assertEquals(0, none.written(), "AIR over stone");
        assertEquals(stone, solid.get(0, 0, 0));
    }

    /**
     * Fluids and replaceable plants are existing blocks: EXISTING writes onto water and short grass, AIR does not,
     * and only true air (the AIR flag) takes an AIR paste.
     */
    @Test
    void fluidsAndPlantsCountAsExistingBlocks() {
        Clipboard cube = Clipboard.builder(states, new BlockPos(1, 1, 4))
                .set(0, 0, 0, stone).set(0, 0, 1, stone).set(0, 0, 2, stone).set(0, 0, 3, stone).build();
        for (Into into : new Into[] {Into.EXISTING, Into.AIR}) {
            FakeWorld world = new FakeWorld(states);
            world.set(0, 0, 0, water);
            world.set(0, 0, 1, grass);
            world.set(0, 0, 2, poppy);
            world.set(0, 0, 3, air);
            FakeExecutor.run(paste(cube, BlockPos.ORIGIN, PasteOptions.DEFAULT.withInto(into), Symmetry.NONE), world);
            boolean existing = into == Into.EXISTING;
            assertEquals(existing ? stone : water, world.get(0, 0, 0), into + " onto water");
            assertEquals(existing ? stone : grass, world.get(0, 0, 1), into + " onto short grass");
            assertEquals(existing ? stone : poppy, world.get(0, 0, 2), into + " onto a poppy");
            assertEquals(existing ? air : stone, world.get(0, 0, 3), into + " onto air");
        }
    }

    /**
     * A mirrored paste filters each copy's own landing cells: the original lands over a mixed strip and the copy over
     * the mirrored strip's different content, and each cell follows its own world content; one program, exact undo.
     */
    @Test
    void symmetricCopiesFilterTheirOwnLandingCells() {
        Clipboard clipboard = source();
        // The plane x = 10 (x2 = 20): the paste at x 2..5 mirrors to x 14..17 (19 - x).
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 20, 0);
        BlockPos origin = new BlockPos(2, 0, 0);
        Box area = box(0, -1, -1, 19, 3, 4);
        for (Into into : Into.values()) {
            FakeWorld world = mixedWorld(area, 99 + into.ordinal());
            Map<BlockPos, Cell> before = snapshot(world, area);
            PasteOptions options = new PasteOptions(false, false, true, into);
            Map<BlockPos, Cell> expected = expectedPaste(before, clipboard, origin, options);
            // The copy: cell (x, y, z) of the source lands on (19 - (2 + x), y, z); stone, dirt, sand and chests are
            // their own mirror images.
            BlockPos size = clipboard.size();
            for (int x = 0; x < size.x(); x++) {
                for (int y = 0; y < size.y(); y++) {
                    for (int z = 0; z < size.z(); z++) {
                        int state = clipboard.get(x, y, z);
                        if (state < 0 || StateFlags.has(states.flags(state), StateFlags.AIR)) continue;
                        BlockPos at = new BlockPos(19 - (2 + x), y, z);
                        if (!writable(into, before.get(at).state())) continue;
                        expected.put(at, new Cell(state, clipboard.tile(x, y, z)));
                    }
                }
            }
            EditProgram program = paste(clipboard, origin, options, mirror);
            assertTrue(program instanceof SymmetricProgram, "one symmetric program");
            runAndUndo(program, world, area, into + " mirrored");
            CopyTestSupport.assertWorld(expected, world, into + " mirrored");
        }
    }

    // ------------------------------------------------------------------ move

    /** A move's result: the region vacated, then each block landed where the filter allows (identity transform). */
    private Map<BlockPos, Cell> expectedMove(Map<BlockPos, Cell> before, Region region, BlockPos offset, Into into) {
        Map<BlockPos, Cell> expected = new HashMap<>(before);
        Box b = region.bounds();
        for (int x = b.min().x(); x <= b.max().x(); x++) {
            for (int y = b.min().y(); y <= b.max().y(); y++) {
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    if (region.contains(x, y, z)) expected.put(new BlockPos(x, y, z), cell(air));
                }
            }
        }
        for (int x = b.min().x(); x <= b.max().x(); x++) {
            for (int y = b.min().y(); y <= b.max().y(); y++) {
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    if (!region.contains(x, y, z)) continue;
                    BlockPos at = new BlockPos(x, y, z).add(offset);
                    if (!writable(into, before.get(at).state())) continue;
                    expected.put(at, before.get(new BlockPos(x, y, z)));
                }
            }
        }
        return expected;
    }

    /**
     * A box and a cell set moved over a mixed landing, with and without overlapping their source, under every
     * Into: the whole source is vacated (including cells a filtered-out block would have landed on), the blocks whose
     * landing cell the filter leaves out are gone, the others land, and undo brings everything back exactly.
     */
    @Test
    void aMoveFiltersItsLandingCellsWhileItsSourceVacates() {
        Box box = box(0, 0, 0, 3, 1, 3);
        CellSet.Builder set = CellSet.builder();
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 3; z++) {
                if ((x + z) % 3 != 1) set.add(x, 0, z).add(x, 1, z);
            }
        }
        List<Region> regions = List.of(new Region.Cuboid(box), new Region.Cells(set.build()));
        List<BlockPos> offsets = List.of(new BlockPos(2, 0, 1), new BlockPos(8, 1, -2));
        Box area = box(-2, -2, -5, 14, 5, 6);
        for (Region region : regions) {
            for (BlockPos offset : offsets) {
                for (Into into : Into.values()) {
                    String what = region.getClass().getSimpleName() + " by " + offset + " " + into;
                    FakeWorld world = mixedWorld(area, what.hashCode());
                    Map<BlockPos, Cell> before = snapshot(world, area);
                    Map<BlockPos, Cell> expected = expectedMove(before, region, offset, into);
                    OpSpec.Move move = new OpSpec.Move(region, offset, Transform.IDENTITY, new Pattern.Single(air),
                            EntityFilter.NONE, Symmetry.NONE, into);
                    FakeExecutor.Result result = runAndUndo(OpCompiler.compile(move, context), world, area, what);
                    CopyTestSupport.assertWorld(expected, world, what);
                    long changed = expected.entrySet().stream().filter(e -> !e.getValue().equals(before.get(e.getKey()))).count();
                    assertEquals(changed, result.written(), what + ": written cells");
                }
            }
        }
    }

    /**
     * The documented loss: a stone row moved with EXISTING onto a landing that is half air loses the blocks that would
     * have landed on air (their source cells become air all the same), keeps the rest, and undo restores the row.
     */
    @Test
    void blocksLandingOnFilteredCellsAreLostUntilUndo() {
        FakeWorld world = new FakeWorld(states);
        for (int x = 0; x < 4; x++) world.set(x, 0, 0, stone);
        world.set(10, 0, 0, dirt);
        world.set(11, 0, 0, dirt);
        Box area = box(-1, -1, -1, 14, 1, 1);
        OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(box(0, 0, 0, 3, 0, 0)), new BlockPos(10, 0, 0),
                Transform.IDENTITY, new Pattern.Single(air), EntityFilter.NONE, Symmetry.NONE, Into.EXISTING);
        FakeExecutor.Result result = runAndUndo(OpCompiler.compile(move, context), world, area, "lossy move");
        assertEquals(4 + 2, result.written(), "four vacated, two landed");
        for (int x = 0; x < 4; x++) assertEquals(air, world.get(x, 0, 0), "vacated " + x);
        assertEquals(stone, world.get(10, 0, 0));
        assertEquals(stone, world.get(11, 0, 0));
        assertEquals(air, world.get(12, 0, 0), "lost: its landing cell was air");
        assertEquals(air, world.get(13, 0, 0), "lost: its landing cell was air");
    }

    /**
     * A mirrored move under AIR: each copy's landing is filtered by its own content and the copy's source vacates;
     * the whole edit is one program with an exact undo.
     */
    @Test
    void symmetricMovesFilterEachCopysLanding() {
        Box box = box(0, 0, 0, 2, 0, 2);
        BlockPos offset = new BlockPos(0, 0, 5);
        // The plane x = 10 (x2 = 20): the copy's region is x 17..19, moved by the same offset (mirrored: dx = 0).
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 20, 0);
        Box area = box(-1, -1, -1, 20, 1, 8);
        FakeWorld world = mixedWorld(area, 5);
        Map<BlockPos, Cell> before = snapshot(world, area);
        Map<BlockPos, Cell> expected = expectedMove(before, new Region.Cuboid(box), offset, Into.AIR);
        expected = expectedMoveOnto(expected, before, new Region.Cuboid(box(17, 0, 0, 19, 0, 2)), offset, Into.AIR);
        OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(box), offset, Transform.IDENTITY, new Pattern.Single(air),
                EntityFilter.NONE, mirror, Into.AIR);
        EditProgram program = OpCompiler.compile(move, context);
        assertTrue(program instanceof SymmetricProgram);
        runAndUndo(program, world, area, "mirrored move");
        CopyTestSupport.assertWorld(expected, world, "mirrored move");
    }

    /** {@link #expectedMove} of a second, disjoint copy applied on top of {@code expected} (reads from {@code before}). */
    private Map<BlockPos, Cell> expectedMoveOnto(Map<BlockPos, Cell> expected, Map<BlockPos, Cell> before, Region region,
                                                 BlockPos offset, Into into) {
        Map<BlockPos, Cell> copy = expectedMove(before, region, offset, into);
        Map<BlockPos, Cell> merged = new HashMap<>(expected);
        for (Map.Entry<BlockPos, Cell> entry : copy.entrySet()) {
            if (!entry.getValue().equals(before.get(entry.getKey()))) merged.put(entry.getKey(), entry.getValue());
        }
        return merged;
    }

    // ------------------------------------------------------------------ stack

    /** A stack's result: copy k lands the region's cells shifted k steps where the filter allows; later copies win. */
    private Map<BlockPos, Cell> expectedStack(Map<BlockPos, Cell> before, Region region, BlockPos step, int count, Into into) {
        Map<BlockPos, Cell> expected = new HashMap<>(before);
        Box b = region.bounds();
        for (int k = 1; k <= count; k++) {
            for (int x = b.min().x(); x <= b.max().x(); x++) {
                for (int y = b.min().y(); y <= b.max().y(); y++) {
                    for (int z = b.min().z(); z <= b.max().z(); z++) {
                        if (!region.contains(x, y, z)) continue;
                        BlockPos at = new BlockPos(x + k * step.x(), y + k * step.y(), z + k * step.z());
                        if (!writable(into, before.get(at).state())) continue;
                        expected.put(at, before.get(new BlockPos(x, y, z)));
                    }
                }
            }
        }
        return expected;
    }

    /**
     * A box and a cell set stacked twice over a mixed world (the copies overlapping each other and the source) under
     * every Into: every landing cell follows its pre-write content, the count is exact, and undo and redo are exact.
     */
    @Test
    void aStackWritesOnlyTheLandingCellsIntoAllows() {
        Box box = box(0, 0, 0, 3, 1, 2);
        CellSet.Builder set = CellSet.builder();
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 2; z++) {
                if ((x * z) % 2 == 0) set.add(x, 0, z).add(x, 1, z);
            }
        }
        List<Region> regions = List.of(new Region.Cuboid(box), new Region.Cells(set.build()));
        List<BlockPos> steps = List.of(new BlockPos(2, 0, 1), new BlockPos(0, 2, 0), new BlockPos(-5, 1, 3));
        Box area = box(-12, -2, -2, 12, 6, 10);
        for (Region region : regions) {
            for (BlockPos step : steps) {
                for (Into into : Into.values()) {
                    String what = region.getClass().getSimpleName() + " step " + step + " " + into;
                    FakeWorld world = mixedWorld(area, what.hashCode());
                    Map<BlockPos, Cell> before = snapshot(world, area);
                    Map<BlockPos, Cell> expected = expectedStack(before, region, step, 2, into);
                    OpSpec.Stack stack = new OpSpec.Stack(region, step.x(), step.y(), step.z(), 2, EntityFilter.NONE,
                            Symmetry.NONE, into);
                    FakeExecutor.Result result = runAndUndo(OpCompiler.compile(stack, context), world, area, what);
                    CopyTestSupport.assertWorld(expected, world, what);
                    long changed = expected.entrySet().stream().filter(e -> !e.getValue().equals(before.get(e.getKey()))).count();
                    assertEquals(changed, result.written(), what + ": written cells");
                }
            }
        }
    }

    /** A mirrored stack filters each copy's landing by its own content, as one program with an exact undo. */
    @Test
    void symmetricStacksFilterEachCopysLanding() {
        Box box = box(0, 0, 0, 2, 0, 2);
        BlockPos step = new BlockPos(0, 0, 4);
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 20, 0);
        Box area = box(-1, -1, -1, 20, 1, 10);
        for (Into into : Into.values()) {
            FakeWorld world = mixedWorld(area, 11 + into.ordinal());
            Map<BlockPos, Cell> before = snapshot(world, area);
            Map<BlockPos, Cell> expected = expectedStack(before, new Region.Cuboid(box), step, 2, into);
            Map<BlockPos, Cell> copy = expectedStack(before, new Region.Cuboid(box(17, 0, 0, 19, 0, 2)), step, 2, into);
            for (Map.Entry<BlockPos, Cell> entry : copy.entrySet()) {
                if (!entry.getValue().equals(before.get(entry.getKey()))) expected.put(entry.getKey(), entry.getValue());
            }
            OpSpec.Stack stack = new OpSpec.Stack(new Region.Cuboid(box), 0, 0, 4, 2, EntityFilter.NONE, mirror, into);
            EditProgram program = OpCompiler.compile(stack, context);
            assertTrue(program instanceof SymmetricProgram);
            runAndUndo(program, world, area, into + " mirrored stack");
            CopyTestSupport.assertWorld(expected, world, into + " mirrored stack");
        }
    }

    // ------------------------------------------------------------------ types

    /** The earlier constructors mean EVERYTHING; the option travels through the symmetry and region helpers. */
    @Test
    void earlierConstructorsMeanEverythingAndTheOptionIsKept() {
        assertEquals(Into.EVERYTHING, PasteOptions.DEFAULT.into());
        assertEquals(Into.EVERYTHING, new PasteOptions(true, true).into());
        assertEquals(Into.EVERYTHING, new PasteOptions(true, true, false).into());
        assertEquals(new PasteOptions(true, false, true, Into.AIR), new PasteOptions(true, false).withInto(Into.AIR));
        Region region = new Region.Cuboid(box(0, 0, 0, 1, 1, 1));
        OpSpec.Move move = new OpSpec.Move(region, new BlockPos(1, 0, 0), Transform.IDENTITY, new Pattern.Single(air),
                EntityFilter.NONE);
        assertEquals(Into.EVERYTHING, move.into());
        assertEquals(Into.EVERYTHING, new OpSpec.Stack(region, 1, 0, 0, 1, EntityFilter.NONE).into());
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 20, 0);
        OpSpec.Move filtered = new OpSpec.Move(region, new BlockPos(1, 0, 0), Transform.IDENTITY, new Pattern.Single(air),
                EntityFilter.NONE, Symmetry.NONE, Into.EXISTING);
        OpSpec.Move symmetric = (OpSpec.Move) OpSymmetry.withSymmetry(filtered, mirror);
        assertEquals(Into.EXISTING, symmetric.into());
        for (OpSymmetry.Copy copy : OpSymmetry.copies(symmetric)) assertEquals(Into.EXISTING, ((OpSpec.Move) copy.op()).into());
        assertEquals(Into.EXISTING, ((OpSpec.Move) OpRegions.withRegion(filtered, region)).into());
        OpSpec.Stack stack = new OpSpec.Stack(region, 1, 0, 0, 1, EntityFilter.NONE, mirror, Into.AIR);
        for (OpSymmetry.Copy copy : OpSymmetry.copies(stack)) assertEquals(Into.AIR, ((OpSpec.Stack) copy.op()).into());
        assertEquals(Into.AIR, ((OpSpec.Stack) OpRegions.withRegion(stack, region)).into());
        assertFalse(new PasteOptions(false, false, true, Into.AIR).equals(PasteOptions.DEFAULT));
    }
}
