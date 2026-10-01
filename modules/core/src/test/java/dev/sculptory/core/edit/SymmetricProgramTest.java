package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.box;
import static dev.sculptory.core.edit.CopyTestSupport.chest;
import static dev.sculptory.core.edit.CopyTestSupport.grow;
import static dev.sculptory.core.edit.CopyTestSupport.runAndUndo;
import static dev.sculptory.core.edit.CopyTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CopyTestSupport.Cell;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Symmetric ops as one program: Fill, Replace, Erase,
 * Hollow, Walls, Paste, Move and Stack under every mode against a brute-force model (each copy's covered cells with
 * their turned states, the first copy covering a cell deciding it), then exact undo and redo.
 */
class SymmetricProgramTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final Map<SourceRef, SourceBlocks> sources = new HashMap<>();
    private final CompileContext context = CopyTestSupport.context(states, sources);
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int logX = states.state("minecraft:oak_log[axis=x]");

    /** Centres near the regions (copies overlap the original) and away from them. */
    private static List<Symmetry> symmetries() {
        List<Symmetry> all = new ArrayList<>();
        for (Symmetry.Mode mode : Symmetry.Mode.values()) {
            if (mode == Symmetry.Mode.OFF) continue;
            for (int[] centre : new int[][] {{9, 9}, {8, 8}, {31, -20}, {6, 7}}) {
                if (mode == Symmetry.Mode.ROTATE_4 && ((centre[0] ^ centre[1]) & 1) != 0) continue;
                all.add(new Symmetry(mode, centre[0], centre[1]));
            }
        }
        return all;
    }

    private List<Region> regions() {
        Random random = new Random(5);
        CellSet.Builder builder = CellSet.builder();
        for (int i = 0; i < 200; i++) builder.add(random.nextInt(12), 60 + random.nextInt(7), random.nextInt(11));
        for (int x = 0; x < 12; x++) builder.add(x, 63, 0);
        return List.of(new Region.Cuboid(box(0, 60, 0, 11, 66, 10)),
                new Region.Shape(box(0, 60, 0, 11, 66, 10), ShapeKind.ELLIPSOID, Facing.UP),
                new Region.Shape(box(0, 60, 0, 11, 66, 10), ShapeKind.CONE, Facing.EAST),
                new Region.Shape(box(0, 60, 0, 11, 66, 10), ShapeKind.PYRAMID, Facing.SOUTH),
                new Region.Cells(builder.build()));
    }

    private int stairs(String facing) {
        return states.state("minecraft:oak_stairs[facing=" + facing + "]");
    }

    /** Random states over {@code box} (a third air), chests carrying distinct tiles. */
    private FakeWorld randomWorld(Box box, long seed) {
        Random random = new Random(seed);
        FakeWorld world = new FakeWorld(states);
        int chests = 0;
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    int state = random.nextInt(3) == 0 ? air : random.nextInt(states.size());
                    world.set(x, y, z, state);
                    if (StateFlags.has(states.flags(state), StateFlags.HAS_BLOCK_ENTITY)) {
                        world.setTile(x, y, z, chest("minecraft:emerald", ++chests));
                    }
                }
            }
        }
        return world;
    }

    private static List<BlockPos> cells(Region region) {
        List<BlockPos> cells = new ArrayList<>();
        Box b = region.bounds();
        for (int x = b.min().x(); x <= b.max().x(); x++) {
            for (int y = b.min().y(); y <= b.max().y(); y++) {
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    if (region.contains(x, y, z)) cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return cells;
    }

    private static BlockPos image(Symmetry symmetry, Symmetry.Image image, BlockPos cell) {
        return new BlockPos((int) symmetry.cellX(image, cell.x(), cell.z()), cell.y(),
                (int) symmetry.cellZ(image, cell.x(), cell.z()));
    }

    /** Hollow's inside: every cell within t along the six directions is a region cell. */
    private static boolean inside(Region r, BlockPos c, int t) {
        for (int k = 1; k <= t; k++) {
            if (!r.contains(c.x() + k, c.y(), c.z()) || !r.contains(c.x() - k, c.y(), c.z()) || !r.contains(c.x(), c.y() + k, c.z())
                    || !r.contains(c.x(), c.y() - k, c.z()) || !r.contains(c.x(), c.y(), c.z() + k) || !r.contains(c.x(), c.y(), c.z() - k)) {
                return false;
            }
        }
        return true;
    }

    /** A wall: a cell outside the region lies within t along x or z. */
    private static boolean wall(Region r, BlockPos c, int t) {
        for (int k = 1; k <= t; k++) {
            if (!r.contains(c.x() + k, c.y(), c.z()) || !r.contains(c.x() - k, c.y(), c.z()) || !r.contains(c.x(), c.y(), c.z() + k)
                    || !r.contains(c.x(), c.y(), c.z() - k)) {
                return true;
            }
        }
        return false;
    }

    /** The union of the copies' writes: the first copy covering a cell decides it. */
    private static Map<BlockPos, Cell> union(Map<BlockPos, Cell> before, List<Map<BlockPos, Cell>> copies) {
        Map<BlockPos, Cell> expected = new HashMap<>(before);
        Set<BlockPos> covered = new HashSet<>();
        for (Map<BlockPos, Cell> copy : copies) {
            for (Map.Entry<BlockPos, Cell> entry : copy.entrySet()) {
                if (covered.add(entry.getKey())) expected.put(entry.getKey(), entry.getValue());
            }
        }
        return expected;
    }

    /** Runs the program, checks the world against {@code expected} over {@code area}, then undoes and redoes it. */
    private void check(EditProgram program, FakeWorld world, Box area, Map<BlockPos, Cell> before,
                       Map<BlockPos, Cell> expected, String what) {
        long changed = 0;
        LongOpenHashSet sections = new LongOpenHashSet();
        for (Map.Entry<BlockPos, Cell> entry : expected.entrySet()) {
            if (entry.getValue().equals(before.get(entry.getKey()))) continue;
            changed++;
            BlockPos p = entry.getKey();
            sections.add(BlockBuffer.keyOfBlock(p.x(), p.y(), p.z()));
        }
        assertTrue(program.estimatedCells() >= changed, what + ": estimate " + program.estimatedCells() + " < " + changed);
        LongOpenHashSet order = new LongOpenHashSet(program.sectionOrder());
        assertTrue(order.containsAll(sections), what + ": the section order holds every changed section");
        assertEquals(order.size(), program.sectionOrder().length, what + ": no section twice");
        FakeExecutor.Result result = runAndUndo(program, world, area, what);
        CopyTestSupport.assertWorld(expected, world, what);
        assertEquals(changed, result.written(), what + ": cells written");
    }

    // =================================================================== region ops

    /**
     * Fill (with stairs, so turned states show), Replace, Erase, Hollow and Walls of every region kind under every
     * mode, with centres that make the copies overlap the original: each cell as the first covering copy writes it,
     * its state turned with the copy's image, then exact undo and redo.
     */
    @Test
    void regionOpsUnderEveryModeMatchTheModel() {
        long seed = 1;
        Pattern east = new Pattern.Single(stairs("east"));
        for (Region region : regions()) {
            for (Symmetry symmetry : symmetries()) {
                List<OpSpec> ops = List.of(
                        new OpSpec.Fill(region, east, CellMask.ANY, symmetry),
                        new OpSpec.Replace(region, new CellMask.Blocks(List.of(states.blockId(dirt))), east, symmetry),
                        new OpSpec.Erase(region, CellMask.ANY, symmetry),
                        new OpSpec.Hollow(region, 2, east, symmetry),
                        new OpSpec.Walls(region, 1, east, symmetry));
                for (OpSpec op : ops) {
                    String what = op.getClass().getSimpleName() + " of " + region + " under " + symmetry;
                    Box area = grow(box(-40, 58, -40, 50, 68, 50), 1);
                    FakeWorld world = randomWorld(grow(region.bounds(), 24), seed++);
                    Map<BlockPos, Cell> before = snapshot(world, area);
                    List<Map<BlockPos, Cell>> copies = new ArrayList<>();
                    List<OpSymmetry.Copy> expectedCopies = OpSymmetry.copies(op);
                    assertEquals(OpSymmetry.copyCount(op), expectedCopies.size());
                    for (OpSymmetry.Copy copy : expectedCopies) {
                        Region imaged = OpRegions.region(copy.op());
                        Transform turn = copy.image().transform();
                        Map<BlockPos, Cell> writes = new LinkedHashMap<>();
                        for (BlockPos cell : cells(imaged)) {
                            Cell current = before.get(cell);
                            switch (op) {
                                case OpSpec.Fill fill -> writes.put(cell, new Cell(turn.applyToState(states, stairs("east")), null));
                                case OpSpec.Replace replace -> {
                                    if (current.state() == dirt) writes.put(cell, new Cell(turn.applyToState(states, stairs("east")), null));
                                }
                                case OpSpec.Erase erase -> writes.put(cell, new Cell(air, null));
                                case OpSpec.Hollow hollow -> {
                                    if (inside(imaged, cell, 2)) writes.put(cell, new Cell(turn.applyToState(states, stairs("east")), null));
                                }
                                case OpSpec.Walls walls -> {
                                    if (wall(imaged, cell, 1)) writes.put(cell, new Cell(turn.applyToState(states, stairs("east")), null));
                                }
                                default -> throw new AssertionError(op);
                            }
                        }
                        // A cell already holding the state keeps its tile (no write); the model keeps the world's.
                        writes.replaceAll((cell, value) -> value.state() == before.get(cell).state() ? before.get(cell) : value);
                        copies.add(writes);
                    }
                    EditProgram program = OpCompiler.compile(op, context);
                    if (expectedCopies.size() > 1) assertInstanceOf(SymmetricProgram.class, program, what);
                    check(program, world, area, before, union(before, copies), what);
                }
            }
        }
    }

    /** Where the copies overlap at the plane, the original wins: east stairs stay east, the mirror gets west. */
    @Test
    void theOriginalWinsWhereCopiesOverlap() {
        Region.Cuboid region = new Region.Cuboid(box(4, 60, 0, 12, 62, 3)); // x 4..12; the plane at x = 10 (x2 = 20)
        Symmetry symmetry = new Symmetry(Symmetry.Mode.MIRROR_X, 20, 0);
        FakeWorld world = new FakeWorld(states);
        world.fill(box(0, 60, 0, 20, 62, 3), dirt);
        Box area = box(0, 60, 0, 20, 62, 3);
        Map<BlockPos, Cell> before = snapshot(world, area);
        EditProgram program = OpCompiler.compile(new OpSpec.Fill(region, new Pattern.Single(stairs("east")), CellMask.ANY,
                symmetry), context);
        runAndUndo(program, world, area, "overlap");
        for (int x = 0; x <= 20; x++) {
            int expected = x >= 4 && x <= 12 ? stairs("east") : x >= 7 && x <= 15 ? stairs("west") : dirt;
            assertEquals(states.format(expected), states.format(world.get(x, 61, 2)), "x = " + x);
        }
        assertEquals(dirt, before.get(new BlockPos(9, 61, 2)).state());
        // A stack copy that lands on the original's landing cells is decided by the original too.
        Region.Cuboid strip = new Region.Cuboid(box(0, 70, 0, 1, 70, 0));
        FakeWorld stacked = new FakeWorld(states);
        stacked.set(0, 70, 0, stone);
        stacked.set(1, 70, 0, logX);
        stacked.set(6, 70, 0, dirt);
        stacked.set(7, 70, 0, dirt);
        // Mirror about x = 4 (x2 = 8): the strip's image is 6..7; both stacks step toward each other.
        EditProgram stack = OpCompiler.compile(new OpSpec.Stack(strip, 2, 0, 0, 2, EntityFilter.NONE,
                new Symmetry(Symmetry.Mode.MIRROR_X, 8, 0)), context);
        FakeExecutor.run(stack, stacked);
        assertEquals(stone, stacked.get(2, 70, 0));
        assertEquals(logX, stacked.get(3, 70, 0));
        assertEquals(stone, stacked.get(4, 70, 0), "the original's second copy wins over the mirror's second copy");
        assertEquals(logX, stacked.get(5, 70, 0));
        assertEquals(dirt, stacked.get(6, 70, 0));
        assertEquals(dirt, stacked.get(7, 70, 0));
    }

    /** A weighted pattern is evaluated at the pre-image and turned, so a copy is the original's pattern mirrored. */
    @Test
    void weightedPatternsAndModdedFacingsAreMirrored() {
        int widgetEast = states.state("testmod:widget[facing=east]");
        Pattern mix = new Pattern.Weighted(new int[] {widgetEast, logX, stone}, new int[] {1, 1, 1}, 99L);
        Region.Cuboid region = new Region.Cuboid(box(0, 60, 0, 5, 61, 5));
        Symmetry symmetry = new Symmetry(Symmetry.Mode.ROTATE_4, 20, 20);
        FakeWorld world = new FakeWorld(states);
        Box area = box(-2, 60, -2, 22, 61, 22);
        FakeExecutor.run(OpCompiler.compile(new OpSpec.Fill(region, mix, CellMask.ANY, symmetry), context), world);
        for (BlockPos cell : cells(region)) {
            int original = mix.apply(states, cell.x(), cell.y(), cell.z(), air);
            assertEquals(original, world.get(cell.x(), cell.y(), cell.z()));
            for (Symmetry.Image image : symmetry.images()) {
                BlockPos at = image(symmetry, image, cell);
                assertEquals(states.format(image.transform().applyToState(states, original)),
                        states.format(world.get(at.x(), at.y(), at.z())), image + " of " + cell);
            }
        }
        assertEquals("testmod:widget[facing=south]", states.format(Symmetry.Image.QUARTER_CW.transform().applyToState(states,
                widgetEast)), "a modded facing turns through the state space's fallback");
        assertEquals("minecraft:oak_log[axis=z]", states.format(Symmetry.Image.QUARTER_CW.transform().applyToState(states, logX)));
        assertTrue(area.contains(image(symmetry, Symmetry.Image.HALF_TURN, new BlockPos(0, 60, 0))));
    }

    /**
     * A state pattern is evaluated on the copy's own cell and not turned: a mirrored flood waterlogs the mirror's stairs without changing their facing, and a mirrored drain
     * dries them the same way.
     */
    @Test
    void fluidPatternsAreNotTurnedInCopies() {
        int water = states.state("minecraft:water[level=0]");
        int east = states.state("minecraft:oak_stairs[facing=east]");
        int west = states.state("minecraft:oak_stairs[facing=west]");
        int wetEast = states.state("minecraft:oak_stairs[facing=east,waterlogged=true]");
        int wetWest = states.state("minecraft:oak_stairs[facing=west,waterlogged=true]");
        FakeWorld world = new FakeWorld(states);
        // Mirror about x = 4 (x2 = 8): cell 1 is imaged to 6, cell 2 to 5.
        world.set(1, 60, 0, east);
        world.set(5, 60, 0, west);
        Region.Cuboid region = new Region.Cuboid(box(1, 60, 0, 2, 60, 0));
        Symmetry symmetry = new Symmetry(Symmetry.Mode.MIRROR_X, 8, 0);
        FakeExecutor.run(OpCompiler.compile(new OpSpec.Fill(region, new Pattern.Waterlog(water), CellMask.ANY, symmetry),
                context), world);
        assertEquals(wetEast, world.get(1, 60, 0));
        assertEquals(water, world.get(2, 60, 0));
        assertEquals(water, world.get(6, 60, 0), "the mirror's air is flooded");
        assertEquals(wetWest, world.get(5, 60, 0), "the mirror's stair keeps its own facing");
        FakeExecutor.run(OpCompiler.compile(new OpSpec.Fill(region, new Pattern.Dry(), CellMask.ANY, symmetry), context), world);
        assertEquals(east, world.get(1, 60, 0));
        assertEquals(air, world.get(2, 60, 0));
        assertEquals(air, world.get(6, 60, 0));
        assertEquals(west, world.get(5, 60, 0));
    }

    // =================================================================== paste

    /** A paste under every mode: each copy is the exact image of the original, states turned, tiles kept. */
    @Test
    void pastesUnderEveryModeMatchTheModel() {
        BlockPos size = new BlockPos(4, 2, 3);
        Clipboard.Builder builder = Clipboard.builder(states, size).anchor(new BlockPos(1, 0, 1));
        int chests = 0;
        Random random = new Random(9);
        List<Integer> palette = List.of(stairs("east"), stairs("north"), stone, states.state("minecraft:chest[facing=west]"),
                states.state("testmod:widget[facing=up]"), air);
        for (int x = 0; x < size.x(); x++) {
            for (int y = 0; y < size.y(); y++) {
                for (int z = 0; z < size.z(); z++) {
                    if (random.nextInt(6) == 0) continue;
                    int state = palette.get(random.nextInt(palette.size()));
                    builder.set(x, y, z, state);
                    if (StateFlags.has(states.flags(state), StateFlags.HAS_BLOCK_ENTITY)) {
                        builder.setTile(x, y, z, chest("minecraft:apple", ++chests));
                    }
                }
            }
        }
        Clipboard clipboard = builder.build();
        SourceRef ref = new SourceRef.Clipboard(UUID.randomUUID());
        sources.put(ref, clipboard.toSource());
        long seed = 40;
        for (Symmetry symmetry : symmetries()) {
            for (Transform t : List.of(Transform.IDENTITY, Transform.rotation(1), new Transform(2, Mirror.X), new Transform(0, Mirror.Z))) {
                for (boolean includeAir : new boolean[] {false, true}) {
                    BlockPos origin = new BlockPos(6, 64, 5);
                    OpSpec.Paste paste = new OpSpec.Paste(ref, origin, t, new PasteOptions(includeAir, false), symmetry);
                    String what = paste + " " + (includeAir ? "with air" : "");
                    Box area = box(-40, 62, -40, 50, 68, 50);
                    FakeWorld world = randomWorld(box(-2, 63, -2, 14, 66, 14), seed++);
                    Map<BlockPos, Cell> before = snapshot(world, area);
                    List<Map<BlockPos, Cell>> copies = new ArrayList<>();
                    for (OpSymmetry.Copy copy : OpSymmetry.copies(paste)) {
                        Transform ct = t.compose(copy.image().transform());
                        Map<BlockPos, Cell> writes = new LinkedHashMap<>();
                        int ax = t.mapX(1, 1, size.x(), size.z()), az = t.mapZ(1, 1, size.x(), size.z());
                        clipboard.forEachCell((x, y, z, state, tile) -> {
                            if (state < 0 || (!includeAir && state == air)) return;
                            BlockPos at = new BlockPos(origin.x() - ax + t.mapX(x, z, size.x(), size.z()), origin.y() + y,
                                    origin.z() - az + t.mapZ(x, z, size.x(), size.z()));
                            writes.put(image(symmetry, copy.image(), at), new Cell(ct.applyToState(states, state), tile));
                        });
                        copies.add(writes);
                    }
                    Map<BlockPos, Cell> expected = union(before, copies);
                    // A cell already holding the state and tile is not written (the model agrees by construction).
                    EditProgram program = OpCompiler.compile(paste, context);
                    runAndUndo(program, world, area, what);
                    CopyTestSupport.assertWorld(expected, world, what);
                }
            }
        }
    }

    // =================================================================== move and stack

    /** Reference model of one move: vacate every region cell, then write each cell at its moved place (read before). */
    private Map<BlockPos, Cell> expectedMove(Map<BlockPos, Cell> before, OpSpec.Move move) {
        Region region = move.region();
        Map<BlockPos, Cell> writes = new LinkedHashMap<>();
        Box b = region.bounds();
        List<BlockPos> cells = cells(region);
        for (BlockPos cell : cells) writes.put(cell, new Cell(air, null));
        BlockPos destMin = b.min().add(move.offset());
        Transform t = move.t();
        for (BlockPos cell : cells) {
            int lx = cell.x() - b.min().x(), lz = cell.z() - b.min().z();
            BlockPos to = new BlockPos(destMin.x() + t.mapX(lx, lz, b.sizeX(), b.sizeZ()), cell.y() + move.offset().y(),
                    destMin.z() + t.mapZ(lx, lz, b.sizeX(), b.sizeZ()));
            Cell from = before.get(cell);
            writes.put(to, new Cell(t.applyToState(states, from.state()), from.tile()));
        }
        return writes;
    }

    /** Reference model of one stack: copies 1..count of the region's cells, read before, later ones winning. */
    private static Map<BlockPos, Cell> expectedStack(Map<BlockPos, Cell> before, OpSpec.Stack stack) {
        Map<BlockPos, Cell> writes = new LinkedHashMap<>();
        for (int k = 1; k <= stack.count(); k++) {
            for (BlockPos cell : cells(stack.region())) {
                writes.put(cell.offset(k * stack.dx(), k * stack.dy(), k * stack.dz()), before.get(cell));
            }
        }
        return writes;
    }

    /**
     * Moves and stacks of every region kind under every mode: each copy moves or stacks the image region's own
     * content (the copy of a move turned by the conjugated transform lands exactly on the image of the original's
     * destination), the first copy covering a cell deciding it, then exact undo and redo.
     */
    @Test
    void movesAndStacksUnderEveryModeMatchTheModel() {
        long seed = 100;
        for (Region region : regions()) {
            for (Symmetry symmetry : symmetries()) {
                for (Transform t : List.of(Transform.IDENTITY, Transform.rotation(1), new Transform(0, Mirror.X))) {
                    for (BlockPos offset : List.of(new BlockPos(6, 1, -3), new BlockPos(-14, 0, 2))) {
                        OpSpec.Move move = new OpSpec.Move(region, offset, t, new Pattern.Single(air), EntityFilter.NONE, symmetry);
                        String what = move.toString();
                        Box area = box(-60, 58, -60, 70, 68, 70);
                        FakeWorld world = randomWorld(grow(region.bounds(), 30), seed++);
                        Map<BlockPos, Cell> before = snapshot(world, area);
                        List<Map<BlockPos, Cell>> copies = new ArrayList<>();
                        List<OpSymmetry.Copy> expectedCopies = OpSymmetry.copies(move);
                        for (OpSymmetry.Copy copy : expectedCopies) copies.add(expectedMove(before, (OpSpec.Move) copy.op()));
                        // The copies of a move land on the images of the original's landing cells.
                        Map<BlockPos, Cell> original = copies.get(0);
                        for (int i = 1; i < copies.size(); i++) {
                            Set<BlockPos> expectedCovered = new HashSet<>();
                            for (BlockPos cell : original.keySet()) expectedCovered.add(image(symmetry, expectedCopies.get(i).image(), cell));
                            assertEquals(expectedCovered, copies.get(i).keySet(), what + ": copy " + i + " covers the image");
                        }
                        EditProgram program = OpCompiler.compile(move, context);
                        check(program, world, area, before, union(before, copies), what);
                        // Every copy's source sections are snapshotted.
                        LongOpenHashSet sourceSections = new LongOpenHashSet(program.sourceSections());
                        for (OpSymmetry.Copy copy : expectedCopies) {
                            assertTrue(sourceSections.containsAll(new LongOpenHashSet(OpRegions.region(copy.op()).sectionKeys())), what);
                        }
                    }
                }
                for (int[] step : new int[][] {{13, 0, 0}, {0, 8, 0}, {-5, 1, 12}}) {
                    OpSpec.Stack stack = new OpSpec.Stack(region, step[0], step[1], step[2], 2, EntityFilter.NONE, symmetry);
                    String what = stack.toString();
                    Box area = box(-60, 58, -60, 70, 90, 70);
                    FakeWorld world = randomWorld(grow(region.bounds(), 30), seed++);
                    Map<BlockPos, Cell> before = snapshot(world, area);
                    List<Map<BlockPos, Cell>> copies = new ArrayList<>();
                    for (OpSymmetry.Copy copy : OpSymmetry.copies(stack)) copies.add(expectedStack(before, (OpSpec.Stack) copy.op()));
                    check(OpCompiler.compile(stack, context), world, area, before, union(before, copies), what);
                }
            }
        }
    }

    /** The composed transform of a paste copy turns a state as its parts do: the op's transform, then the image. */
    @Test
    void composedTransformsTurnStatesAsTheirParts() {
        List<Integer> samples = List.of(stairs("east"), stairs("north"), states.state("minecraft:oak_stairs[facing=west,half=top,shape=inner_left]"),
                logX, states.state("testmod:widget[facing=south]"), states.state("minecraft:chest[facing=north]"));
        for (Mirror mirror : Mirror.values()) {
            for (int turns = 0; turns < 4; turns++) {
                Transform t = new Transform(turns, mirror);
                for (Symmetry.Image image : Symmetry.Image.values()) {
                    for (int state : samples) {
                        assertEquals(states.format(image.transform().applyToState(states, t.applyToState(states, state))),
                                states.format(t.compose(image.transform()).applyToState(states, state)), t + " then " + image);
                    }
                }
            }
        }
    }

    /** Without a mode the compiler gives the plain program, and a straddling box's single copy is not wrapped. */
    @Test
    void withoutCopiesThePlainProgramIsUsed() {
        Region.Cuboid region = new Region.Cuboid(box(0, 60, 0, 4, 61, 4));
        EditProgram plain = OpCompiler.compile(new OpSpec.Fill(region, new Pattern.Single(stone), CellMask.ANY), context);
        assertInstanceOf(RegionProgram.class, plain);
        EditProgram one = OpCompiler.compile(new OpSpec.Fill(region, new Pattern.Single(stone), CellMask.ANY,
                new Symmetry(Symmetry.Mode.MIRROR_X, 5, 0)), context);
        assertInstanceOf(RegionProgram.class, one, "its mirror is itself");
        EditProgram two = OpCompiler.compile(new OpSpec.Fill(region, new Pattern.Single(stone), CellMask.ANY,
                new Symmetry(Symmetry.Mode.MIRROR_X, 40, 0)), context);
        assertInstanceOf(SymmetricProgram.class, two);
        assertEquals("Fill", two.label());
        assertEquals(100, two.estimatedCells());
        assertEquals(box(0, 60, 0, 39, 61, 4), two.bounds());
        assertEquals(2, ((SymmetricProgram) two).copies().size());
    }
}
