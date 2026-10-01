package dev.sculptory.core.brush;

import static dev.sculptory.core.brush.BrushFixture.at;
import static dev.sculptory.core.brush.BrushFixture.dab;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Exact-state and inverted surface masks, and the clip box ("only inside selection") for every tool. */
class BrushMaskClipTest {
    private final BrushFixture f = new BrushFixture();
    private final int snowy = f.states.state("minecraft:grass_block[snowy=true]");

    /** Rolling terrain, 58 to 63. */
    private static final IntBinaryOperator ROLLING = (x, z) -> 58 + Math.floorMod(x / 2 + z / 3 + ((x * 5 + z * 3) & 1), 6);
    /** Every cell a stroke near the origin could touch. */
    private static final Box AREA = box(-16, BrushFixture.FLOOR, -16, 16, 100, 16);

    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    private static BrushSpec spec(BrushTool tool, int radius, Pattern material, SurfaceMask mask, int flattenY, Box clip) {
        return new BrushSpec(tool, radius, 1f, Falloff.CONSTANT, Shape.CIRCLE, material, mask, 2, flattenY, 3L, clip);
    }

    // ---- Exact states and invert ----

    @Test
    void exactStatesMatchOnlyTheListedStatesWhileBlockTypesMatchEveryState() {
        Supplier<FakeWorld> checkered = () -> {
            FakeWorld world = f.flat(60);
            for (int x = -4; x <= 4; x++) {
                for (int z = -4; z <= 4; z++) if (Math.floorMod(x + z, 2) == 0) world.set(x, 60, z, snowy);
            }
            return world;
        };
        Pattern sand = new Pattern.Single(f.sand);
        SurfaceMask byType = new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.of(new NamespacedId("minecraft:grass_block"))));
        SurfaceMask exact = new SurfaceMask.SurfaceBlocks(new CellMask.States(new int[] {snowy}));

        FakeWorld typed = checkered.get();
        dab(spec(BrushTool.PAINT, 3, sand, byType, 0, null), new StrokeState(), typed, at(0, 0, 61, 0));
        FakeWorld exactly = checkered.get();
        dab(spec(BrushTool.PAINT, 3, sand, exact, 0, null), new StrokeState(), exactly, at(0, 0, 61, 0));
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (x * x + z * z > 9) continue;
                boolean isSnowy = Math.floorMod(x + z, 2) == 0;
                assertEquals(f.sand, typed.get(x, 60, z), "any grass state matches by type at " + x + "," + z);
                assertEquals(isSnowy ? f.sand : f.grass, exactly.get(x, 60, z), "only snowy grass matches at " + x + "," + z);
            }
        }
        // A plain grass state lists snowy=false: exact matching then leaves the snowy columns alone.
        FakeWorld plain = checkered.get();
        SurfaceMask plainExact = new SurfaceMask.SurfaceBlocks(new CellMask.States(new int[] {f.grass}));
        dab(spec(BrushTool.PAINT, 3, sand, plainExact, 0, null), new StrokeState(), plain, at(0, 0, 61, 0));
        assertEquals(snowy, plain.get(0, 60, 0));
        assertEquals(f.sand, plain.get(1, 60, 0));
    }

    @Test
    void invertChangesExactlyTheColumnsTheMaskRejects() {
        IntBinaryOperator ridges = (x, z) -> Math.floorMod(x, 2) == 0 ? 60 : 62;
        SurfaceMask mask = new SurfaceMask.And(List.of(
                new SurfaceMask.Elevation(61, 70),
                new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.of(new NamespacedId("minecraft:grass_block"))))));
        List<String> kept = raised(ridges, mask);
        List<String> inverted = raised(ridges, new SurfaceMask.Not(mask));
        List<String> all = raised(ridges, SurfaceMask.ANY);
        assertFalse(kept.isEmpty());
        assertFalse(inverted.isEmpty());
        for (String column : kept) assertFalse(inverted.contains(column), column + " in both");
        List<String> union = new ArrayList<>(kept);
        union.addAll(inverted);
        union.sort(null);
        List<String> sortedAll = new ArrayList<>(all);
        sortedAll.sort(null);
        assertEquals(sortedAll, union, "the mask and its inverse together cover the brush");
        assertTrue(raised(ridges, new SurfaceMask.Not(SurfaceMask.ANY)).isEmpty(), "inverting 'any' matches nothing");
    }

    /** The columns ("x,z") a full-strength radius-3 raise changes under {@code mask}. */
    private List<String> raised(IntBinaryOperator height, SurfaceMask mask) {
        List<String> columns = new ArrayList<>();
        for (BrushFixture.Write w : dab(spec(BrushTool.RAISE, 3, null, mask, 0, null), new StrokeState(),
                f.terrain(height), at(0, 0, 62, 0))) {
            columns.add(w.x() + "," + w.z());
        }
        return columns;
    }

    // ---- Clip box, per tool ----

    /** A curving stroke of eight dabs across the origin. */
    private static List<Dab> path() {
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < 8; i++) dabs.add(new Dab(i, (-6 + i * 2) * 16 + 5, 62 * 16, (i % 3 - 1) * 16 + 9, 255));
        return dabs;
    }

    private static List<BrushFixture.Write> stroke(BrushSpec spec, FakeWorld world) {
        StrokeState state = new StrokeState();
        List<BrushFixture.Write> writes = new ArrayList<>();
        for (Dab d : path()) writes.addAll(dab(spec, state, world, d));
        return writes;
    }

    /** The cells of {@link #AREA} where the worlds differ, as "x,y,z". */
    private static List<String> changed(FakeWorld before, FakeWorld after) {
        List<String> cells = new ArrayList<>();
        for (int x = AREA.min().x(); x <= AREA.max().x(); x++) {
            for (int z = AREA.min().z(); z <= AREA.max().z(); z++) {
                for (int y = AREA.min().y(); y <= AREA.max().y(); y++) {
                    if (before.get(x, y, z) != after.get(x, y, z)) cells.add(x + "," + y + "," + z);
                }
            }
        }
        return cells;
    }

    /** Ground from {@link #ROLLING}, with a pond (water 57-61 over a sand bed) east of x = 2. */
    private FakeWorld wetTerrain() {
        FakeWorld world = f.terrain(ROLLING);
        for (int x = 3; x <= 9; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = 56; y <= 70; y++) world.set(x, y, z, y == 56 ? f.sand : y <= 61 ? f.water : f.air);
            }
        }
        return world;
    }

    @Test
    void everyToolWritesOnlyInsideTheClipBox() {
        Pattern palette = new Pattern.Weighted(new int[] {f.stone, f.dirt, f.sand}, new int[] {3, 2, 1}, 9L);
        Box clip = box(-3, 59, -1, 4, 62, 2);
        for (BrushTool tool : BrushTool.TERRAIN) {
            Pattern material = tool == BrushTool.PAINT ? new Pattern.Single(f.sand) : tool == BrushTool.PALETTE ? palette : null;
            BrushSpec clipped = spec(tool, 5, material, SurfaceMask.ANY, 59, clip);
            FakeWorld world = wetTerrain();
            List<BrushFixture.Write> writes = stroke(clipped, world);
            assertFalse(writes.isEmpty(), tool + " wrote nothing inside the box");
            for (BrushFixture.Write w : writes) assertTrue(clip.contains(w.x(), w.y(), w.z()), tool + " wrote " + w);
            for (String cell : changed(wetTerrain(), world)) {
                String[] xyz = cell.split(",");
                assertTrue(clip.contains(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])),
                        tool + " changed " + cell + " outside the box");
            }
            assertEquals(writes, stroke(clipped, wetTerrain()), tool + " is deterministic with a clip");
            // The same stroke without the clip reaches beyond the box, so the clip really cut something.
            List<BrushFixture.Write> free = stroke(clipped.withClip(null), wetTerrain());
            assertTrue(free.stream().anyMatch(w -> !clip.contains(w.x(), w.y(), w.z())), tool + " never left the box anyway");
        }
    }

    @Test
    void columnToolsWriteInsideTheBoxExactlyWhatTheyWouldWithoutIt() {
        Pattern palette = new Pattern.Weighted(new int[] {f.stone, f.dirt, f.sand}, new int[] {3, 2, 1}, 9L);
        Box clip = box(-2, 50, -2, 3, 100, 1);
        for (BrushTool tool : new BrushTool[] {BrushTool.RAISE, BrushTool.PAINT, BrushTool.PALETTE}) {
            Pattern material = tool == BrushTool.PAINT ? new Pattern.Single(f.sand) : tool == BrushTool.PALETTE ? palette : null;
            BrushSpec free = spec(tool, 4, material, SurfaceMask.ANY, 0, null);
            // Raise and paint change each column on its own: the clip only removes the columns outside it.
            List<BrushFixture.Write> expected = stroke(free, f.terrain(ROLLING)).stream()
                    .filter(w -> clip.contains(w.x(), w.y(), w.z())).toList();
            assertFalse(expected.isEmpty());
            assertEquals(expected, stroke(free.withClip(clip), f.terrain(ROLLING)), tool.toString());
        }
    }

    @Test
    void smoothAndFlattenStillReadOutsideTheBox() {
        // A 3-block step at x = 1: smoothing the column just inside the box pulls it toward the ground outside.
        IntBinaryOperator step = (x, z) -> x >= 1 ? 63 : 60;
        Box clip = box(1, 50, -2, 1, 100, 2);
        FakeWorld world = f.terrain(step);
        StrokeState state = new StrokeState();
        List<BrushFixture.Write> writes = dab(spec(BrushTool.SMOOTH, 2, null, SurfaceMask.ANY, 0, clip), state, world,
                at(0, 1, 63, 0));
        assertFalse(writes.isEmpty());
        for (BrushFixture.Write w : writes) assertEquals(1, w.x(), "only column x = 1 is inside: " + w);
        assertTrue(f.surface(world, 1, 0) < 63, "the step's top was lowered toward its outside neighbours");
        assertEquals(60, f.surface(world, 0, 0), "outside the box: untouched");
        assertEquals(63, f.surface(world, 2, 0), "outside the box: untouched");

        FakeWorld flat = f.terrain(step);
        List<BrushFixture.Write> flattened = dab(spec(BrushTool.FLATTEN, 3, null, SurfaceMask.ANY, 60, clip),
                new StrokeState(), flat, at(0, 1, 63, 0));
        for (BrushFixture.Write w : flattened) assertEquals(1, w.x(), w.toString());
        assertEquals(60, f.surface(flat, 1, 0), "flattened to the target");
        assertEquals(63, f.surface(flat, 2, 0));
    }

    // ---- Clip box and fluids ----

    /** Ground at 60 for x <= 2; for x >= 3 a stone bed at 55 under water up to 60. */
    private FakeWorld shore() {
        FakeWorld world = f.terrain((x, z) -> x <= 2 ? 60 : 55);
        for (int x = 3; x <= BrushFixture.EXTENT; x++) {
            for (int z = -BrushFixture.EXTENT; z <= BrushFixture.EXTENT; z++) {
                world.set(x, 55, z, f.stone);
                for (int y = 56; y <= 60; y++) world.set(x, y, z, f.water);
            }
        }
        return world;
    }

    private static BrushSpec lower(Box clip) {
        return new BrushSpec(BrushTool.LOWER, 1, 1f, Falloff.CONSTANT, Shape.SQUARE, null, SurfaceMask.ANY, 0, 0, 1L, clip);
    }

    @Test
    void loweringInsideTheBoxRefillsFromWaterOutsideIt() {
        // Column x = 2 touches the sea (x = 3, outside the box): its removed cells fill from it, and x = 1 from x = 2.
        FakeWorld world = shore();
        List<BrushFixture.Write> writes = dab(lower(box(1, 50, -1, 2, 100, 1)), new StrokeState(), world, at(0, 1, 61, 0));
        assertEquals(6, writes.size());
        for (BrushFixture.Write w : writes) {
            assertEquals(f.water, w.state(), w.toString());
            assertTrue(w.x() >= 1, w.toString());
        }
        assertEquals(f.grass, world.get(0, 60, 0), "outside the box");
    }

    @Test
    void waterDoesNotFlowThroughCellsTheBoxKeeps() {
        // x = 2 is outside the box: it stays ground, so the sea cannot reach the cells lowered at x = 0 and 1.
        FakeWorld world = shore();
        List<BrushFixture.Write> writes = dab(lower(box(-1, 50, -1, 1, 100, 1)), new StrokeState(), world, at(0, 1, 61, 0));
        assertEquals(6, writes.size());
        for (BrushFixture.Write w : writes) assertEquals(f.air, w.state(), w.toString());
        assertEquals(f.grass, world.get(2, 60, 0));
    }

    @Test
    void aDeepCutBelowWaterStopsAtTheBoxFloorAndRefills() {
        // Flatten to 57 cuts y 58-60 (27 cells); a box from y 59 keeps y 58, and the rest still refills.
        FakeWorld world = shore();
        BrushSpec spec = new BrushSpec(BrushTool.FLATTEN, 1, 1f, Falloff.CONSTANT, Shape.SQUARE, null, SurfaceMask.ANY, 0, 57,
                1L, box(-5, 59, -5, 5, 100, 5));
        List<BrushFixture.Write> writes = dab(spec, new StrokeState(), world, at(0, 1, 61, 0));
        assertEquals(18, writes.size());
        for (BrushFixture.Write w : writes) {
            assertEquals(f.water, w.state(), w.toString());
            assertTrue(w.y() >= 59, w.toString());
        }
        assertEquals(f.stone, world.get(1, 58, 0), "below the box floor");
    }

    // ---- Clip box edges ----

    @Test
    void aBoxOutsideTheBrushChangesNothingAndTracksNothing() {
        for (BrushTool tool : new BrushTool[] {BrushTool.RAISE, BrushTool.SMOOTH, BrushTool.PAINT}) {
            Pattern material = tool == BrushTool.PAINT ? new Pattern.Single(f.sand) : null;
            FakeWorld world = f.terrain(ROLLING);
            StrokeState state = new StrokeState();
            Box beside = box(10, 50, 10, 14, 100, 14);
            assertTrue(dab(spec(tool, 4, material, SurfaceMask.ANY, 0, beside), state, world, at(0, 0, 62, 0)).isEmpty(),
                    tool + " beside the box");
            assertEquals(0, state.trackedColumns(), tool + " accumulated outside the box");
            Box above = box(-4, 90, -4, 4, 100, 4);
            assertTrue(dab(spec(tool, 4, material, SurfaceMask.ANY, 0, above), state, world, at(1, 0, 62, 0)).isEmpty(),
                    tool + " below the box");
        }
    }

    @Test
    void aSingleBlockBoxChangesAtMostThatBlock() {
        FakeWorld world = f.flat(60);
        Box aboveSurface = Box.of(new BlockPos(0, 61, 0));
        List<BrushFixture.Write> raised = dab(spec(BrushTool.RAISE, 3, null, SurfaceMask.ANY, 0, aboveSurface),
                new StrokeState(), world, at(0, 0, 61, 0));
        assertEquals(List.of(new BrushFixture.Write(0, 61, 0, f.grass)), raised);

        FakeWorld lowered = f.flat(60);
        Box surface = Box.of(new BlockPos(1, 60, 1));
        assertEquals(List.of(new BrushFixture.Write(1, 60, 1, f.air)),
                dab(spec(BrushTool.LOWER, 3, null, SurfaceMask.ANY, 0, surface), new StrokeState(), lowered, at(0, 0, 61, 0)));
        FakeWorld painted = f.flat(60);
        assertEquals(List.of(new BrushFixture.Write(1, 60, 1, f.sand)), dab(spec(BrushTool.PAINT, 3, new Pattern.Single(f.sand),
                SurfaceMask.ANY, 0, surface), new StrokeState(), painted, at(0, 0, 61, 0)));
        // Raising the same column writes above its surface: nothing of that is inside a box at the surface.
        assertTrue(dab(spec(BrushTool.RAISE, 3, null, SurfaceMask.ANY, 0, surface), new StrokeState(), f.flat(60),
                at(0, 0, 61, 0)).isEmpty());
    }

    @Test
    void aColumnWhosePlantsReachOutsideTheBoxDoesNotMove() {
        // Column (0, 0) carries a two-high plant (61-62); column (1, 0) a one-high plant (61).
        Supplier<FakeWorld> planted = () -> {
            FakeWorld world = f.flat(60);
            world.set(0, 61, 0, f.shortGrass);
            world.set(0, 62, 0, f.shortGrass);
            world.set(1, 61, 0, f.shortGrass);
            return world;
        };
        // The box top at 61 cuts the two-high plant: that column neither rises nor loses half a plant.
        Box upTo61 = box(-1, 50, 0, 1, 61, 0);
        FakeWorld raised = planted.get();
        List<BrushFixture.Write> writes = dab(spec(BrushTool.RAISE, 1, null, SurfaceMask.ANY, 0, upTo61), new StrokeState(),
                raised, at(0, 0, 61, 0));
        assertEquals(List.of(new BrushFixture.Write(-1, 61, 0, f.grass), new BrushFixture.Write(1, 61, 0, f.grass)), writes);
        assertEquals(f.shortGrass, raised.get(0, 61, 0));
        assertEquals(f.shortGrass, raised.get(0, 62, 0));

        // The box top at the surface (60) keeps every plant: planted columns do not sink under their plants.
        Box upTo60 = box(-1, 50, 0, 1, 60, 0);
        FakeWorld lowered = planted.get();
        writes = dab(spec(BrushTool.LOWER, 1, null, SurfaceMask.ANY, 0, upTo60), new StrokeState(), lowered, at(0, 0, 61, 0));
        assertEquals(List.of(new BrushFixture.Write(-1, 60, 0, f.air)), writes, "only the bare column sinks");
        assertEquals(f.grass, lowered.get(0, 60, 0));
        assertEquals(f.grass, lowered.get(1, 60, 0));

        // A box that holds the whole plant lets the column move and clears the plant, as without a box.
        Box whole = box(-1, 50, 0, 1, 70, 0);
        FakeWorld free = planted.get();
        List<BrushFixture.Write> unclipped = dab(spec(BrushTool.LOWER, 1, null, SurfaceMask.ANY, 0, null), new StrokeState(),
                free, at(0, 0, 61, 0)).stream().filter(w -> w.z() == 0).toList();
        assertEquals(unclipped, dab(spec(BrushTool.LOWER, 1, null, SurfaceMask.ANY, 0, whole), new StrokeState(),
                planted.get(), at(0, 0, 61, 0)));
        assertEquals(f.air, free.get(0, 62, 0));
    }

    @Test
    void theClipCombinesWithTheMaskAndItsInverse() {
        IntBinaryOperator ridges = (x, z) -> Math.floorMod(x, 2) == 0 ? 60 : 62;
        SurfaceMask high = new SurfaceMask.Elevation(61, 70);
        Box clip = box(-3, 50, 0, 3, 100, 3);
        for (SurfaceMask mask : List.of(high, new SurfaceMask.Not(high))) {
            List<BrushFixture.Write> free = dab(spec(BrushTool.RAISE, 3, null, mask, 0, null), new StrokeState(),
                    f.terrain(ridges), at(0, 0, 62, 0));
            List<BrushFixture.Write> clipped = dab(spec(BrushTool.RAISE, 3, null, mask, 0, clip), new StrokeState(),
                    f.terrain(ridges), at(0, 0, 62, 0));
            assertEquals(free.stream().filter(w -> w.z() >= 0).toList(), clipped, "the clip is not inverted: " + mask);
        }
    }
}
