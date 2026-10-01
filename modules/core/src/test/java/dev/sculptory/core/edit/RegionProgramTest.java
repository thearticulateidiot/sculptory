package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.box;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.region.ShapeKind;
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
 * Fill, Replace, Erase, Hollow and Walls over every kind of region: the exact
 * cells written, masks, the build height, empty and sparse regions, Hollow and Walls against the old box programs, and
 * the volume checks by cell count.
 */
class RegionProgramTest {
    private final FakeStateSpace states = new FakeStateSpace();
    /** y -64 to 319, like the overworld and FakeWorld's default. */
    private final CompileContext context = CopyTestSupport.context(states, Map.of());
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int sand = states.state("minecraft:sand");
    private final int grass = states.state("minecraft:grass_block");

    private EditProgram compile(OpSpec op) {
        return OpCompiler.compile(op, context);
    }

    // =================================================================== reference model

    /** Whether the cell is Hollow's inside: every cell within t along each of the six directions is a region cell. */
    private static boolean inside(Region r, int x, int y, int z, int t) {
        if (!r.contains(x, y, z)) return false;
        for (int k = 1; k <= t; k++) {
            if (!r.contains(x + k, y, z) || !r.contains(x - k, y, z) || !r.contains(x, y + k, z)
                    || !r.contains(x, y - k, z) || !r.contains(x, y, z + k) || !r.contains(x, y, z - k)) {
                return false;
            }
        }
        return true;
    }

    /** Whether the cell is a wall: a cell outside the region lies within t along x or z. */
    private static boolean wall(Region r, int x, int y, int z, int t) {
        if (!r.contains(x, y, z)) return false;
        for (int k = 1; k <= t; k++) {
            if (!r.contains(x + k, y, z) || !r.contains(x - k, y, z) || !r.contains(x, y, z + k) || !r.contains(x, y, z - k)) {
                return true;
            }
        }
        return false;
    }

    /** The old box programs' shapes (before regions): inner box for Hollow, four sides for Walls. */
    private static boolean oldBoxShape(Box b, int x, int y, int z, int t, boolean hollow) {
        int dx = Math.min(x - b.min().x(), b.max().x() - x);
        int dy = Math.min(y - b.min().y(), b.max().y() - y);
        int dz = Math.min(z - b.min().z(), b.max().z() - z);
        return hollow ? dx >= t && dy >= t && dz >= t : dx < t || dz < t;
    }

    /**
     * Runs {@code op} on a world of dirt over {@code area} (its part inside the world, y -64 to 319); checks each cell
     * there against {@code expected} and the count written.
     */
    private FakeExecutor.Result check(OpSpec op, Box area, CellTest expected, int written) {
        FakeWorld world = new FakeWorld(states);
        int bottom = Math.max(area.min().y(), -64), top = Math.min(area.max().y(), 319);
        world.fill(new Box(new BlockPos(area.min().x(), bottom, area.min().z()), new BlockPos(area.max().x(), top, area.max().z())),
                dirt);
        EditProgram program = compile(op);
        FakeExecutor.Result result = FakeExecutor.run(program, world);
        long count = 0;
        for (int x = area.min().x(); x <= area.max().x(); x++) {
            for (int y = bottom; y <= top; y++) {
                for (int z = area.min().z(); z <= area.max().z(); z++) {
                    boolean in = expected.test(x, y, z);
                    if (in) count++;
                    assertEquals(in ? written : dirt, world.get(x, y, z), op + " at " + x + "," + y + "," + z);
                }
            }
        }
        assertEquals(count, result.written(), op.toString());
        return result;
    }

    @FunctionalInterface
    private interface CellTest {
        boolean test(int x, int y, int z);
    }

    private static CellSet cellsOf(Box b) {
        return CellSet.builder().addAll(new Region.Cuboid(b)).build();
    }

    private static Box grow(Box b, int margin) {
        return CopyTestSupport.grow(b, margin);
    }

    // =================================================================== box equivalence

    /**
     * Hollow and Walls of a box give exactly the old box programs' result, as a cuboid (box geometry) and as the same
     * cells in a cell set (erosion), on boxes of many sizes (too thin ones included) across section boundaries and
     * thicknesses up to 16.
     */
    @Test
    void hollowAndWallsOfABoxAreTheOldBoxPrograms() {
        Random random = new Random(42);
        int[] sides = {1, 2, 3, 4, 5, 6, 7, 9, 11, 16, 17, 20, 33};
        List<Box> boxes = new ArrayList<>(List.of(box(0, 0, 0, 2, 2, 2), box(-1, 0, -1, 0, 30, 33), box(10, 1, 10, 42, 6, 11),
                box(-7, 3, 5, 20, 18, 40), box(5, 5, 5, 5, 5, 5)));
        for (int i = 0; i < 40; i++) {
            int x0 = random.nextInt(40) - 20, y0 = random.nextInt(40) - 10, z0 = random.nextInt(40) - 20;
            boxes.add(box(x0, y0, z0, x0 + sides[random.nextInt(sides.length)] - 1, y0 + sides[random.nextInt(sides.length)] - 1,
                    z0 + sides[random.nextInt(sides.length)] - 1));
        }
        for (Box b : boxes) {
            for (int t : new int[] {1, 2, 3, 5, 8, 15, 16}) {
                for (boolean hollow : new boolean[] {true, false}) {
                    for (Region region : List.of(new Region.Cuboid(b), new Region.Cells(cellsOf(b)))) {
                        OpSpec op = hollow ? new OpSpec.Hollow(region, t, new Pattern.Single(air))
                                : new OpSpec.Walls(region, t, new Pattern.Single(stone));
                        check(op, grow(b, 1), (x, y, z) -> b.contains(x, y, z) && oldBoxShape(b, x, y, z, t, hollow),
                                hollow ? air : stone);
                    }
                }
            }
        }
    }

    // =================================================================== shapes and cell sets

    private static List<Region.Shape> shapes() {
        List<Region.Shape> shapes = new ArrayList<>();
        for (ShapeKind kind : ShapeKind.values()) {
            for (Facing facing : Facing.values()) {
                if (kind == ShapeKind.ELLIPSOID && facing != Facing.UP) continue;
                shapes.add(new Region.Shape(box(-9, 2, 11, 12, 20, 30), kind, facing));
                shapes.add(new Region.Shape(box(3, -5, -4, 9, 1, 29), kind, facing));
            }
        }
        return shapes;
    }

    /** A clumpy random set: blobs and single cells, some cells right on section faces. */
    private static CellSet clumps(long seed) {
        Random random = new Random(seed);
        CellSet.Builder builder = CellSet.builder();
        for (int blob = 0; blob < 6; blob++) {
            int cx = random.nextInt(40) - 20, cy = random.nextInt(30), cz = random.nextInt(40) - 20, r = 2 + random.nextInt(6);
            for (int x = -r; x <= r; x++) {
                for (int y = -r; y <= r; y++) {
                    for (int z = -r; z <= r; z++) {
                        if (x * x + y * y + z * z <= r * r + random.nextInt(4)) builder.add(cx + x, cy + y, cz + z);
                    }
                }
            }
        }
        for (int i = 0; i < 50; i++) builder.add(random.nextInt(50) - 25, random.nextInt(40), random.nextInt(50) - 25);
        return builder.build();
    }

    private List<Region> regions() {
        List<Region> regions = new ArrayList<>(shapes());
        regions.add(new Region.Cells(clumps(1)));
        regions.add(new Region.Cells(clumps(2)));
        return regions;
    }

    @Test
    void fillEraseAndReplaceWriteExactlyTheRegionCells() {
        for (Region region : regions()) {
            Box area = grow(region.bounds(), 2);
            check(new OpSpec.Fill(region, new Pattern.Single(stone), CellMask.ANY), area, region::contains, stone);
            check(new OpSpec.Erase(region, CellMask.ANY), area, region::contains, air);
            check(new OpSpec.Replace(region, new CellMask.States(new int[] {dirt}), new Pattern.Single(sand)), area,
                    region::contains, sand);
            EditProgram program = compile(new OpSpec.Fill(region, new Pattern.Single(stone), CellMask.ANY));
            assertEquals(region.cellCount(), program.estimatedCells(), region + " estimate");
            assertArrayEquals(region.sectionKeys(), program.sectionOrder(), region + ": only its sections, in order");
            assertEquals(region.bounds(), program.bounds());
        }
    }

    @Test
    void masksCombineWithTheRegion() {
        Region.Shape ball = new Region.Shape(box(0, 0, 0, 20, 20, 20), ShapeKind.ELLIPSOID, Facing.UP);
        FakeWorld world = new FakeWorld(states);
        Box area = box(-1, -1, -1, 21, 21, 21);
        for (int x = -1; x <= 21; x++) {
            for (int y = -1; y <= 21; y++) {
                for (int z = -1; z <= 21; z++) world.set(x, y, z, (x + y + z) % 3 == 0 ? grass : dirt);
            }
        }
        FakeExecutor.run(compile(new OpSpec.Fill(ball, new Pattern.Single(stone), new CellMask.States(new int[] {grass}))), world);
        for (int x = area.min().x(); x <= area.max().x(); x++) {
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = area.min().z(); z <= area.max().z(); z++) {
                    int was = (x + y + z) % 3 == 0 ? grass : dirt;
                    assertEquals(ball.contains(x, y, z) && was == grass ? stone : was, world.get(x, y, z));
                }
            }
        }
    }

    @Test
    void hollowAndWallsOfShapesAndCellSetsFollowTheirDefinition() {
        for (Region region : regions()) {
            Box area = grow(region.bounds(), 1);
            for (int t : new int[] {1, 2, 3}) {
                check(new OpSpec.Hollow(region, t, new Pattern.Single(air)), area, (x, y, z) -> inside(region, x, y, z, t), air);
                check(new OpSpec.Walls(region, t, new Pattern.Single(stone)), area, (x, y, z) -> wall(region, x, y, z, t), stone);
            }
        }
    }

    /** A thickness beyond a section: the runs continue through neighbouring sections. */
    @Test
    void thickHollowAndWallsReachAcrossSections() {
        Region.Shape ball = new Region.Shape(box(-30, -20, -25, 27, 35, 31), ShapeKind.ELLIPSOID, Facing.UP);
        Region.Shape cylinder = new Region.Shape(box(-30, 0, -25, 27, 12, 31), ShapeKind.CYLINDER, Facing.UP);
        for (Region region : List.of(ball, cylinder)) {
            Box area = grow(region.bounds(), 1);
            for (int t : new int[] {15, 16}) {
                check(new OpSpec.Hollow(region, t, new Pattern.Single(air)), area, (x, y, z) -> inside(region, x, y, z, t), air);
                check(new OpSpec.Walls(region, t, new Pattern.Single(stone)), area, (x, y, z) -> wall(region, x, y, z, t), stone);
            }
        }
    }

    // =================================================================== build height, empty and sparse regions

    @Test
    void theBuildHeightClipsWritesButNotTheShellMeasure() {
        Region.Shape ball = new Region.Shape(box(0, -80, 0, 30, -50, 30), ShapeKind.ELLIPSOID, Facing.UP);
        Box area = grow(ball.bounds(), 1);
        check(new OpSpec.Fill(ball, new Pattern.Single(stone), CellMask.ANY), area, (x, y, z) -> y >= -64 && ball.contains(x, y, z),
                stone);
        check(new OpSpec.Hollow(ball, 2, new Pattern.Single(air)), area, (x, y, z) -> y >= -64 && inside(ball, x, y, z, 2), air);
        check(new OpSpec.Walls(ball, 2, new Pattern.Single(stone)), area, (x, y, z) -> y >= -64 && wall(ball, x, y, z, 2), stone);
        EditProgram fill = compile(new OpSpec.Fill(ball, new Pattern.Single(stone), CellMask.ANY));
        long above = 0;
        for (int x = 0; x <= 30; x++) {
            for (int y = -64; y <= -50; y++) {
                for (int z = 0; z <= 30; z++) if (ball.contains(x, y, z)) above++;
            }
        }
        assertEquals(above, fill.estimatedCells(), "only the cells inside the build height count");
        assertEquals(box(0, -64, 0, 30, -50, 30), fill.bounds());
        for (long key : fill.sectionOrder()) assertTrue(BlockBuffer.keyY(key) >= -4, "a section below the world");
        CellSet below = CellSet.builder().add(0, -70, 0).add(1, -65, 0).build();
        assertThrows(IllegalArgumentException.class,
                () -> compile(new OpSpec.Fill(new Region.Cells(below), new Pattern.Single(stone), CellMask.ANY)));
    }

    @Test
    void emptyShapesChangeNothing() {
        Region.Shape empty = new Region.Shape(box(0, 0, 0, 1, 0, 1), ShapeKind.CONE, Facing.UP);
        assertEquals(0, empty.cellCount());
        for (OpSpec op : List.of(new OpSpec.Fill(empty, new Pattern.Single(stone), CellMask.ANY),
                new OpSpec.Hollow(empty, 1, new Pattern.Single(air)), new OpSpec.Walls(empty, 1, new Pattern.Single(stone)),
                new OpSpec.Move(empty, new BlockPos(5, 0, 0), Transform.IDENTITY, new Pattern.Single(air), EntityFilter.NONE),
                new OpSpec.Stack(empty, 3, 0, 0, 2, EntityFilter.NONE))) {
            EditProgram program = compile(op);
            assertEquals(0, program.sectionOrder().length, op.toString());
            assertEquals(0, program.estimatedCells(), op.toString());
            FakeWorld world = new FakeWorld(states);
            world.fill(box(-5, -5, -5, 10, 5, 10), dirt);
            assertEquals(0, FakeExecutor.run(program, world).written(), op.toString());
        }
    }

    /** Two cells a million blocks apart compile from their two sections, never their bounding box. */
    @Test
    void aHugeSparseRegionCompilesFromItsCells() {
        CellSet set = CellSet.builder().add(-1_000_000, 10, -1_000_000).add(1_000_000, 200, 1_000_000).build();
        Region.Cells far = new Region.Cells(set);
        long start = System.nanoTime();
        EditProgram fill = compile(new OpSpec.Fill(far, new Pattern.Single(stone), CellMask.ANY));
        EditProgram hollow = compile(new OpSpec.Hollow(far, 1, new Pattern.Single(air)));
        EditProgram walls = compile(new OpSpec.Walls(far, 3, new Pattern.Single(stone)));
        EditProgram move = compile(new OpSpec.Move(far, new BlockPos(0, 5, 0), Transform.rotation(1), new Pattern.Single(air),
                EntityFilter.NONE));
        EditProgram stack = compile(new OpSpec.Stack(far, 0, 1, 0, 50, EntityFilter.NONE));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 1000, "compiling took " + millis + " ms");
        assertEquals(2, fill.sectionOrder().length);
        assertEquals(2, fill.estimatedCells());
        assertEquals(2, walls.sectionOrder().length);
        assertEquals(4, move.sectionOrder().length);
        assertEquals(4, move.estimatedCells());
        assertEquals(2, move.sourceSections().length);
        assertEquals(100, stack.estimatedCells());
        assertTrue(stack.sectionOrder().length <= 2 * 5, "stack sections " + stack.sectionOrder().length);
        FakeWorld world = new FakeWorld(states);
        assertEquals(2, FakeExecutor.run(fill, world).written());
        assertEquals(stone, world.get(1_000_000, 200, 1_000_000));
        assertEquals(0, FakeExecutor.run(hollow, world).written());
        assertEquals(0, FakeExecutor.run(walls, world).written(), "already stone");
    }

    // =================================================================== volumes and refusals

    @Test
    void volumesCountCells() {
        Region.Shape ball = new Region.Shape(box(0, 0, 0, 9, 9, 9), ShapeKind.ELLIPSOID, Facing.UP);
        long cells = ball.cellCount();
        assertTrue(cells < 1000);
        Pattern p = new Pattern.Single(stone);
        assertEquals(cells, OpCompiler.targetVolume(new OpSpec.Fill(ball, p, CellMask.ANY), null));
        assertEquals(cells, OpCompiler.targetVolume(new OpSpec.Hollow(ball, 1, p), null));
        assertEquals(2 * cells, OpCompiler.targetVolume(new OpSpec.Move(ball, new BlockPos(1, 0, 0), Transform.IDENTITY, p,
                EntityFilter.NONE), null));
        assertEquals(cells, OpCompiler.sourceVolume(new OpSpec.Move(ball, new BlockPos(1, 0, 0), Transform.IDENTITY, p,
                EntityFilter.NONE)));
        assertEquals(7 * cells, OpCompiler.targetVolume(new OpSpec.Stack(ball, 10, 0, 0, 7,
                EntityFilter.NONE), null));
        Region.Uploaded uploaded = new Region.Uploaded(Sha256.digest(new byte[] {3}), box(0, 0, 0, 99, 99, 99), 1234);
        assertEquals(1234, OpCompiler.targetVolume(new OpSpec.Erase(uploaded, CellMask.ANY), null));
        assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Erase(uploaded, CellMask.ANY)),
                "an uploaded region is resolved to its cells first");
    }

    /** A shape with more rows than the cap is refused at once, before its cells are counted. */
    @Test
    void hugeShapesAreRefusedBeforeCounting() {
        Region.Shape vast = new Region.Shape(box(0, -1_000_000, 0, 1, 1_000_000, 1_000_000), ShapeKind.CONE, Facing.UP);
        long start = System.nanoTime();
        EditTooLargeException refused = assertThrows(EditTooLargeException.class,
                () -> compile(new OpSpec.Fill(vast, new Pattern.Single(stone), CellMask.ANY)));
        assertTrue(System.nanoTime() - start < 200_000_000L, "the refusal did the work");
        assertEquals(OpCompiler.MAX_BIG_SHAPE_ROWS, refused.budget(), "a box of more than 2^29 cells");
        assertInstanceOf(IllegalArgumentException.class, refused);
        // Within the cap, a thin slab of a shape compiles.
        Region.Shape slab = new Region.Shape(box(0, 0, 0, 2000, 0, 2000), ShapeKind.CYLINDER, Facing.UP);
        assertTrue(compile(new OpSpec.Fill(slab, new Pattern.Single(stone), CellMask.ANY)).estimatedCells() > 3_000_000);
    }

    /**
     * The most rows a full count goes over in {@code long} arithmetic: along the longest side of a box of at most 2^29
     * cells, the two shorter sides make at most 2^(2 × 29 / 3) rows, an 812-cube's 659,344 (printed; generous bound).
     */
    @Test
    void aFullCountInLongArithmeticIsBounded() {
        Region.Shape cube = new Region.Shape(box(0, 0, 0, 811, 811, 811), ShapeKind.ELLIPSOID, Facing.UP);
        assertTrue(cube.box().volume() <= 1L << 29);
        OpCompiler.checkShape(cube);
        long start = System.nanoTime();
        long cells = Regions.cellsBetween(cube, Integer.MIN_VALUE, Integer.MAX_VALUE);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("full long count of an 812-cube ellipsoid (%,d rows, %,d cells): %d ms%n", 812L * 812, cells,
                millis);
        assertTrue(millis < 5_000, "counting took " + millis + " ms");
    }

    // =================================================================== resource bounds (review of 2026-09-28)

    /**
     * Thicknesses above 16 are refused for every region; a box's Hollow and Walls never go through the section-by-section
     * thickness test, so a long thin box costs what its cells cost. (Before: {@code Walls(1 × 8 × 262,144 box,
     * thickness 2^31 - 1)} passed every cap and spent about 22 ms a section.)
     */
    @Test
    void thicknessIsCappedAndBoxesNeverErode() {
        Box longBox = box(0, 0, 0, 0, 7, 262_143);
        Region.Shape ball = new Region.Shape(box(0, 0, 0, 40, 40, 40), ShapeKind.ELLIPSOID, Facing.UP);
        for (Region region : List.of(new Region.Cuboid(longBox), ball)) {
            for (int t : new int[] {17, 100, Integer.MAX_VALUE}) {
                assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Walls(region, t, new Pattern.Single(stone))));
                assertThrows(IllegalArgumentException.class, () -> compile(new OpSpec.Hollow(region, t, new Pattern.Single(air))));
            }
        }
        RegionProgram walls = (RegionProgram) compile(new OpSpec.Walls(longBox, 16, new Pattern.Single(stone)));
        RegionProgram hollow = (RegionProgram) compile(new OpSpec.Hollow(box(0, 0, 0, 99, 99, 99), 16, new Pattern.Single(air)));
        assertTrue(!walls.erodes() && !hollow.erodes(), "a box's Hollow and Walls come from the box");
        assertTrue(((RegionProgram) compile(new OpSpec.Walls(ball, 16, new Pattern.Single(stone)))).erodes());
        SectionBuffer before = SectionBuffer.uniform(dirt);
        long start = System.nanoTime();
        long written = 0;
        for (long key : walls.sectionOrder()) {
            SectionBuffer out = new SectionBuffer();
            walls.compute(key, before, out, null);
            written += out.presentCount();
        }
        long nanos = System.nanoTime() - start;
        System.out.printf("Walls t=16 of a 1 x 8 x 262,144 box: %,d sections in %d ms (%.1f us a section)%n",
                walls.sectionOrder().length, nanos / 1_000_000, nanos / 1000.0 / walls.sectionOrder().length);
        assertEquals(8L * 262_144, written, "a one-wide box is all wall");
    }

    /**
     * A shape reaching more sections than the context allows is refused as soon as its listing passes the cap, not after
     * listing them all; the same for a move and a stack of it. (Before: a Hollow of a 16,384 × 384 × 10,922 cylinder
     * listed 16.8 million sections, 3 s and 400 MB, before any check.)
     */
    @Test
    void sectionListsStopAtTheCap() {
        Region.Shape cylinder = new Region.Shape(box(0, -64, 0, 2047, 63, 2047), ShapeKind.CYLINDER, Facing.UP);
        CompileContext capped = new CompileContext() {
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

            @Override
            public long maxSections() {
                return 10_000;
            }
        };
        for (OpSpec op : List.of(new OpSpec.Hollow(cylinder, 1, new Pattern.Single(air)),
                new OpSpec.Fill(cylinder, new Pattern.Single(stone), CellMask.ANY),
                new OpSpec.Move(cylinder, new BlockPos(5, 0, 0), Transform.IDENTITY, new Pattern.Single(air), EntityFilter.NONE),
                new OpSpec.Stack(cylinder, 0, 128, 0, 1, EntityFilter.NONE))) {
            long start = System.nanoTime();
            EditTooLargeException refused = assertThrows(EditTooLargeException.class, () -> OpCompiler.compile(op, capped));
            long millis = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("%s of a %,d-section cylinder refused at 10,000 sections in %d ms%n",
                    op.getClass().getSimpleName(), 16 * 8 * 128, millis);
            assertTrue(millis < 1_000, op.getClass().getSimpleName() + " refusal took " + millis + " ms");
            assertTrue(refused.budget() <= 10_000, refused.getMessage());
        }
    }

    /**
     * Counting a shape against a limit stops once past it, along the box's longest side, so a request over the limit
     * costs little whatever its box, in {@code long} or {@code BigInteger} arithmetic; a box too large to count at all is
     * refused before counting. (Before: {@code Fill} of a 1,000,000 × 384 × 10,922 ellipsoid spent 1.3 s counting.)
     */
    @Test
    void countsAgainstALimitStopEarly() {
        record Case(String name, Region.Shape shape) {}
        List<Case> cases = List.of(
                new Case("ellipsoid 1,000,000 x 150 x 200 (BigInteger)",
                        new Region.Shape(box(0, 0, 0, 999_999, 149, 199), ShapeKind.ELLIPSOID, Facing.UP)),
                new Case("cone east 600,000 x 180 x 180 (BigInteger)",
                        new Region.Shape(box(0, 0, 0, 599_999, 179, 179), ShapeKind.CONE, Facing.EAST)),
                new Case("pyramid up 20,000 x 150 x 200 (BigInteger)",
                        new Region.Shape(box(0, 0, 0, 19_999, 149, 199), ShapeKind.PYRAMID, Facing.UP)),
                new Case("disc 1 x 2,048 x 2,048 (long)",
                        new Region.Shape(box(0, 0, 0, 0, 2047, 2047), ShapeKind.CYLINDER, Facing.EAST)),
                new Case("cone north 2 x 2,500 x 2,500 (long)",
                        new Region.Shape(box(0, 0, 0, 1, 2499, 2499), ShapeKind.CONE, Facing.NORTH)),
                new Case("ellipsoid 812 x 812 x 812 (long)",
                        new Region.Shape(box(0, 0, 0, 811, 811, 811), ShapeKind.ELLIPSOID, Facing.UP)));
        long limit = 2_097_152;
        for (Case c : cases) {
            OpCompiler.checkShape(c.shape());
            long start = System.nanoTime();
            long counted = Regions.cellsBetween(c.shape(), Integer.MIN_VALUE,
                    Integer.MAX_VALUE, limit);
            long micros = (System.nanoTime() - start) / 1000;
            System.out.printf("refusing %s: counted past %,d in %,d us%n", c.name(), limit, micros);
            assertTrue(counted > limit, c.name() + " counted " + counted);
            assertTrue(micros < 50_000, c.name() + " took " + micros + " us");
        }
        // A degenerate shape in a huge box (BigInteger arithmetic) has few rows along its long side: counted exactly.
        Region.Shape needle = new Region.Shape(box(0, 0, 0, 1, 0, 600_000_000), ShapeKind.CONE, Facing.UP);
        OpCompiler.checkShape(needle);
        long start = System.nanoTime();
        long cells = Regions.cellsBetween(needle, Integer.MIN_VALUE, Integer.MAX_VALUE, limit);
        System.out.printf("a 2 x 1 x 600,000,001 cone: %d cells counted in %d us%n", cells,
                (System.nanoTime() - start) / 1000);
        assertEquals(2, cells, "the middle row's two cells");
        // Too many rows for BigInteger arithmetic, or at all: refused at once.
        for (Region.Shape huge : List.of(
                new Region.Shape(box(0, 0, 0, 999_999, 383, 10_921), ShapeKind.ELLIPSOID, Facing.UP),
                new Region.Shape(box(0, 0, 0, 4000, 4000, 4000), ShapeKind.CYLINDER, Facing.UP))) {
            long t0 = System.nanoTime();
            assertThrows(EditTooLargeException.class, () -> compile(new OpSpec.Fill(huge, new Pattern.Single(stone), CellMask.ANY)));
            assertTrue(System.nanoTime() - t0 < 50_000_000L, "refusing " + huge + " did the work");
        }
    }

    /**
     * A full count at the row caps: the worst an accepted shape costs to count, without {@code limit.bypass} (2^15
     * {@code BigInteger} rows) and with it (2^20: a 1,024-cube sphere, and the same cross-section along a
     * million-long side, where the numbers are larger). Printed.
     */
    @Test
    void fullCountsAtTheRowCaps() {
        int bigSide = (int) Math.sqrt(OpCompiler.MAX_BIG_SHAPE_ROWS);
        Region.Shape big = new Region.Shape(box(0, 0, 0, 1_000_000, bigSide - 1, bigSide - 1), ShapeKind.ELLIPSOID, Facing.UP);
        OpCompiler.checkShape(big);
        long start = System.nanoTime();
        long cells = Regions.cellsBetween(big, Integer.MIN_VALUE, Integer.MAX_VALUE);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("full BigInteger count of %,d rows (%,d cells): %d ms%n", OpCompiler.MAX_BIG_SHAPE_ROWS, cells, millis);
        assertTrue(millis < 2_000, "took " + millis + " ms");
        for (Region.Shape bypassed : List.of(new Region.Shape(box(0, 0, 0, 1023, 1023, 1023), ShapeKind.ELLIPSOID, Facing.UP),
                new Region.Shape(box(0, 0, 0, 999_999, 1023, 1023), ShapeKind.ELLIPSOID, Facing.UP))) {
            OpCompiler.checkShape(bypassed, OpCompiler.MAX_BIG_SHAPE_ROWS_BYPASS);
            long t0 = System.nanoTime();
            long counted = Regions.cellsBetween(bypassed, Integer.MIN_VALUE, Integer.MAX_VALUE);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("full BigInteger count of %,d rows (%s, %,d cells), limit.bypass: %d ms (%.2f us a row)%n",
                    OpCompiler.MAX_BIG_SHAPE_ROWS_BYPASS, bypassed.box(), counted, ms,
                    ms * 1000.0 / OpCompiler.MAX_BIG_SHAPE_ROWS_BYPASS);
            assertTrue(ms < 10_000, "took " + ms + " ms");
        }
    }

    /**
     * Shapes in boxes of more than 2^29 cells: at most {@code MAX_BIG_SHAPE_ROWS} rows without {@code limit.bypass},
     * {@code MAX_BIG_SHAPE_ROWS_BYPASS} with it (a context passes it), refused at once past either; smaller boxes are
     * never refused by rows (they span at most 659,344).
     */
    @Test
    void limitBypassRaisesTheRowCapOfShapesInHugeBoxes() {
        Region.Shape disc = new Region.Shape(box(0, 0, 0, 1999, 199, 1999), ShapeKind.CYLINDER, Facing.UP);
        Region.Shape sphere = new Region.Shape(box(0, 0, 0, 1023, 1023, 1023), ShapeKind.ELLIPSOID, Facing.UP);
        for (Region.Shape shape : List.of(disc, sphere)) {
            EditTooLargeException refused = assertThrows(EditTooLargeException.class, () -> OpCompiler.checkShape(shape));
            assertEquals(OpCompiler.MAX_BIG_SHAPE_ROWS, refused.budget());
            OpCompiler.checkShape(shape, OpCompiler.MAX_BIG_SHAPE_ROWS_BYPASS);
        }
        Region.Shape over = new Region.Shape(box(0, 0, 0, 1024, 1024, 1024), ShapeKind.ELLIPSOID, Facing.UP);
        long start = System.nanoTime();
        EditTooLargeException refused = assertThrows(EditTooLargeException.class,
                () -> OpCompiler.checkShape(over, OpCompiler.MAX_BIG_SHAPE_ROWS_BYPASS));
        assertTrue(System.nanoTime() - start < 50_000_000L, "refused at once");
        assertEquals(OpCompiler.MAX_BIG_SHAPE_ROWS_BYPASS, refused.budget());
        OpCompiler.checkShape(new Region.Shape(box(0, 0, 0, 811, 811, 811), ShapeKind.ELLIPSOID, Facing.UP));

        // The compiler takes the cap from its context: 200 × 200 × 20,000 is 40,000 rows.
        Region.Shape rod = new Region.Shape(box(0, 0, 0, 199, 199, 19_999), ShapeKind.CYLINDER, Facing.SOUTH);
        OpSpec.Fill fill = new OpSpec.Fill(rod, new Pattern.Single(stone), CellMask.ANY);
        assertThrows(EditTooLargeException.class, () -> compile(fill));
        CompileContext bypass = new CompileContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef ref) {
                return Optional.empty();
            }

            @Override
            public long maxBigShapeRows() {
                return OpCompiler.MAX_BIG_SHAPE_ROWS_BYPASS;
            }
        };
        assertEquals(Regions.cellsBetween(rod, Integer.MIN_VALUE, Integer.MAX_VALUE),
                OpCompiler.compile(fill, bypass).estimatedCells());
    }

    // =================================================================== cost

    /**
     * Hollow and Walls with thickness 16 on a sphere of about 2.1 million cells: every section computed, timed. The
     * bound is generous (a loaded machine); the measured time is printed.
     */
    @Test
    void thickHollowOnMillionsOfCellsIsAffordable() {
        Region.Shape ball = new Region.Shape(box(0, -60, 0, 159, 99, 159), ShapeKind.ELLIPSOID, Facing.UP);
        assertTrue(ball.cellCount() > 2_000_000, "cells " + ball.cellCount());
        for (boolean hollow : new boolean[] {true, false}) {
            OpSpec op = hollow ? new OpSpec.Hollow(ball, 16, new Pattern.Single(air)) : new OpSpec.Walls(ball, 16, new Pattern.Single(stone));
            long start = System.nanoTime();
            EditProgram program = compile(op);
            SectionBuffer before = SectionBuffer.uniform(dirt);
            long written = 0;
            for (long key : program.sectionOrder()) {
                SectionBuffer out = new SectionBuffer();
                program.compute(key, before, out, null);
                written += out.presentCount();
            }
            long millis = (System.nanoTime() - start) / 1_000_000;
            System.out.printf("%s t=16 on a %,d-cell sphere: %,d sections, %,d cells written, %d ms%n", hollow ? "Hollow" : "Walls",
                    ball.cellCount(), program.sectionOrder().length, written, millis);
            assertTrue(written > 0);
            assertTrue(millis < 20_000, (hollow ? "Hollow" : "Walls") + " took " + millis + " ms");
        }
    }
}
