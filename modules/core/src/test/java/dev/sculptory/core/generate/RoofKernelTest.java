package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.testing.FakeStateSpace;
import org.junit.jupiter.api.Test;

/** The Roof generator against hand-checked cells on small footprints. */
class RoofKernelTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final RoofKernel.Materials oak = new RoofKernel.Materials(BlockDescriptor.parse("minecraft:oak_stairs"),
            BlockDescriptor.parse("minecraft:oak_slab"), BlockDescriptor.parse("minecraft:oak_planks"));
    private final RoofStates roof;
    private final int full = states.state("minecraft:oak_planks");
    private final int slab = states.state("minecraft:oak_slab[type=bottom]");
    private final int air = states.air();
    /** x 0-4, z 0-2, eaves at y 10 (the box's height is ignored). */
    private static final Box FOOTPRINT = new Box(new BlockPos(0, 10, 0), new BlockPos(4, 15, 2));

    RoofKernelTest() throws UnknownMaterialException {
        roof = RoofStates.resolve(states, oak);
    }

    private int stair(Facing facing, StairShapes.Shape shape) {
        return states.state("minecraft:oak_stairs[facing=" + facing.name().toLowerCase() + ",half=bottom,shape="
                + shape.property() + "]");
    }

    private static RoofKernel.Spec spec(Box footprint, RoofKernel.Style style, RoofKernel.Ridge ridge,
                                        RoofKernel.Pitch pitch, int overhang, int thickness, boolean walls,
                                        RoofKernel.Inside inside) {
        return new RoofKernel.Spec(footprint, style, ridge, RoofKernel.Side.NORTH, pitch, overhang, thickness, walls,
                inside, 0);
    }

    private GeneratedSource generate(RoofKernel.Spec spec) {
        return RoofKernel.generate(spec, roof, -64, 320, 100_000);
    }

    // ---------------------------------------------------------------- gable

    @Test
    void aGableRoofIsStairsUpToAFullBlockRidge() {
        GeneratedSource source = generate(spec(FOOTPRINT, RoofKernel.Style.GABLE, RoofKernel.Ridge.AUTO,
                RoofKernel.Pitch.NORMAL, 0, 1, false, RoofKernel.Inside.LEAVE));
        assertEquals(15, source.cells());
        for (int x = 0; x <= 4; x++) {
            assertEquals(stair(Facing.SOUTH, StairShapes.Shape.STRAIGHT), source.get(x, 10, 0), "north eave " + x);
            assertEquals(full, source.get(x, 11, 1), "ridge " + x);
            assertEquals(stair(Facing.NORTH, StairShapes.Shape.STRAIGHT), source.get(x, 10, 2), "south eave " + x);
        }
        assertEquals(new Box(new BlockPos(0, 10, 0), new BlockPos(4, 11, 2)), source.bounds());
    }

    @Test
    void anEvenDepthEndsInTwoRowsOfStairsFacingEachOther() {
        Box even = new Box(new BlockPos(0, 10, 0), new BlockPos(4, 10, 3));
        GeneratedSource source = generate(spec(even, RoofKernel.Style.GABLE, RoofKernel.Ridge.AUTO, RoofKernel.Pitch.NORMAL,
                0, 1, false, RoofKernel.Inside.LEAVE));
        assertEquals(20, source.cells());
        for (int x = 0; x <= 4; x++) {
            assertEquals(stair(Facing.SOUTH, StairShapes.Shape.STRAIGHT), source.get(x, 10, 0));
            assertEquals(stair(Facing.SOUTH, StairShapes.Shape.STRAIGHT), source.get(x, 11, 1));
            assertEquals(stair(Facing.NORTH, StairShapes.Shape.STRAIGHT), source.get(x, 11, 2));
            assertEquals(stair(Facing.NORTH, StairShapes.Shape.STRAIGHT), source.get(x, 10, 3));
        }
    }

    @Test
    void theRidgeCanBeForcedAcrossTheShorterSide() {
        GeneratedSource source = generate(spec(FOOTPRINT, RoofKernel.Style.GABLE, RoofKernel.Ridge.NORTH_SOUTH,
                RoofKernel.Pitch.NORMAL, 0, 1, false, RoofKernel.Inside.LEAVE));
        assertEquals(15, source.cells());
        for (int z = 0; z <= 2; z++) {
            assertEquals(stair(Facing.EAST, StairShapes.Shape.STRAIGHT), source.get(0, 10, z));
            assertEquals(stair(Facing.EAST, StairShapes.Shape.STRAIGHT), source.get(1, 11, z));
            assertEquals(full, source.get(2, 12, z));
            assertEquals(stair(Facing.WEST, StairShapes.Shape.STRAIGHT), source.get(3, 11, z));
            assertEquals(stair(Facing.WEST, StairShapes.Shape.STRAIGHT), source.get(4, 10, z));
        }
        assertEquals(RoofKernel.Ridge.EAST_WEST, spec(FOOTPRINT, RoofKernel.Style.GABLE, RoofKernel.Ridge.AUTO,
                RoofKernel.Pitch.NORMAL, 0, 1, false, RoofKernel.Inside.LEAVE).gableRidge(), "Auto: the longer side");
        Box square = new Box(new BlockPos(0, 10, 0), new BlockPos(2, 10, 2));
        assertEquals(RoofKernel.Ridge.EAST_WEST, spec(square, RoofKernel.Style.GABLE, RoofKernel.Ridge.AUTO,
                RoofKernel.Pitch.NORMAL, 0, 1, false, RoofKernel.Inside.LEAVE).gableRidge(), "a tie runs east-west");
    }

    @Test
    void overhangGrowsTheEavesAndGableWallsFillTheEndsUnderTheRoof() {
        GeneratedSource source = generate(spec(FOOTPRINT, RoofKernel.Style.GABLE, RoofKernel.Ridge.AUTO,
                RoofKernel.Pitch.NORMAL, 1, 1, true, RoofKernel.Inside.LEAVE));
        // The eaves box is x -1..5, z -1..3: five rows, the ridge at y 12.
        assertEquals(7 * 5 + 2 * (1 + 2 + 1), source.cells());
        for (int x = -1; x <= 5; x++) {
            assertEquals(stair(Facing.SOUTH, StairShapes.Shape.STRAIGHT), source.get(x, 10, -1));
            assertEquals(stair(Facing.SOUTH, StairShapes.Shape.STRAIGHT), source.get(x, 11, 0));
            assertEquals(full, source.get(x, 12, 1));
            assertEquals(stair(Facing.NORTH, StairShapes.Shape.STRAIGHT), source.get(x, 11, 2));
            assertEquals(stair(Facing.NORTH, StairShapes.Shape.STRAIGHT), source.get(x, 10, 3));
        }
        for (int x : new int[] {0, 4}) {
            assertEquals(full, source.get(x, 10, 0), "wall under the second row");
            assertEquals(full, source.get(x, 10, 1), "wall under the ridge");
            assertEquals(full, source.get(x, 11, 1), "wall under the ridge");
            assertEquals(full, source.get(x, 10, 2));
        }
        assertEquals(-1, source.get(2, 10, 1), "no wall inside");
        assertEquals(-1, source.get(-1, 10, 0), "no wall in the overhang");
    }

    @Test
    void thicknessAddsFullBlocksUnderEveryRow() {
        GeneratedSource source = generate(spec(FOOTPRINT, RoofKernel.Style.GABLE, RoofKernel.Ridge.AUTO,
                RoofKernel.Pitch.NORMAL, 0, 2, false, RoofKernel.Inside.LEAVE));
        assertEquals(30, source.cells());
        for (int x = 0; x <= 4; x++) {
            assertEquals(full, source.get(x, 9, 0));
            assertEquals(full, source.get(x, 10, 1));
            assertEquals(full, source.get(x, 9, 2));
            assertEquals(stair(Facing.SOUTH, StairShapes.Shape.STRAIGHT), source.get(x, 10, 0));
        }
    }

    @Test
    void hollowWritesAirUnderTheRoofInsideTheFootprint() {
        GeneratedSource source = generate(spec(FOOTPRINT, RoofKernel.Style.GABLE, RoofKernel.Ridge.AUTO,
                RoofKernel.Pitch.NORMAL, 0, 1, true, RoofKernel.Inside.HOLLOW));
        // 15 roof cells, the walls at (0, 10, 1) and (4, 10, 1), air at (1..3, 10, 1).
        assertEquals(15 + 2 + 3, source.cells());
        assertEquals(full, source.get(0, 10, 1));
        assertEquals(full, source.get(4, 10, 1));
        for (int x = 1; x <= 3; x++) assertEquals(air, source.get(x, 10, 1), "hollow " + x);
        assertEquals(-1, source.get(2, 9, 1), "nothing below the eaves");
        GeneratedSource open = generate(spec(FOOTPRINT, RoofKernel.Style.GABLE, RoofKernel.Ridge.AUTO,
                RoofKernel.Pitch.NORMAL, 0, 1, false, RoofKernel.Inside.HOLLOW));
        assertEquals(air, open.get(0, 10, 1), "without gable walls the ends are hollowed too");
    }

    // ---------------------------------------------------------------- pitches

    @Test
    void steepIsTwoFullBlocksPerRow() {
        GeneratedSource source = generate(spec(FOOTPRINT, RoofKernel.Style.GABLE, RoofKernel.Ridge.AUTO,
                RoofKernel.Pitch.STEEP, 0, 1, false, RoofKernel.Inside.LEAVE));
        assertEquals(30, source.cells());
        for (int x = 0; x <= 4; x++) {
            assertEquals(full, source.get(x, 10, 0));
            assertEquals(full, source.get(x, 11, 0));
            assertEquals(full, source.get(x, 12, 1));
            assertEquals(full, source.get(x, 13, 1));
            assertEquals(full, source.get(x, 10, 2));
            assertEquals(full, source.get(x, 11, 2));
            assertEquals(-1, source.get(x, 12, 0));
        }
    }

    @Test
    void gentleAlternatesSlabsAndFullBlocksRisingOnePerTwo() {
        Box deep = new Box(new BlockPos(0, 10, 0), new BlockPos(4, 10, 4));
        GeneratedSource source = generate(spec(deep, RoofKernel.Style.GABLE, RoofKernel.Ridge.AUTO, RoofKernel.Pitch.GENTLE,
                0, 1, false, RoofKernel.Inside.LEAVE));
        assertEquals(25, source.cells());
        for (int x = 0; x <= 4; x++) {
            assertEquals(slab, source.get(x, 10, 0));
            assertEquals(full, source.get(x, 10, 1));
            assertEquals(slab, source.get(x, 11, 2), "the ridge row of an even index is a slab");
            assertEquals(full, source.get(x, 10, 3));
            assertEquals(slab, source.get(x, 10, 4));
        }
    }

    // ---------------------------------------------------------------- hip and shed

    @Test
    void aHipRoofTurnsItsCornersAndRidgesAlongTheLongerSide() {
        GeneratedSource source = generate(spec(FOOTPRINT, RoofKernel.Style.HIP, RoofKernel.Ridge.AUTO,
                RoofKernel.Pitch.NORMAL, 0, 1, false, RoofKernel.Inside.LEAVE));
        assertEquals(15, source.cells());
        assertEquals(stair(Facing.SOUTH, StairShapes.Shape.OUTER_LEFT), source.get(0, 10, 0), "north-west corner");
        assertEquals(stair(Facing.SOUTH, StairShapes.Shape.OUTER_RIGHT), source.get(4, 10, 0), "north-east corner");
        assertEquals(stair(Facing.NORTH, StairShapes.Shape.OUTER_RIGHT), source.get(0, 10, 2), "south-west corner");
        assertEquals(stair(Facing.NORTH, StairShapes.Shape.OUTER_LEFT), source.get(4, 10, 2), "south-east corner");
        for (int x = 1; x <= 3; x++) {
            assertEquals(stair(Facing.SOUTH, StairShapes.Shape.STRAIGHT), source.get(x, 10, 0));
            assertEquals(stair(Facing.NORTH, StairShapes.Shape.STRAIGHT), source.get(x, 10, 2));
            assertEquals(full, source.get(x, 11, 1), "ridge " + x);
        }
        assertEquals(stair(Facing.EAST, StairShapes.Shape.STRAIGHT), source.get(0, 10, 1), "west hip end");
        assertEquals(stair(Facing.WEST, StairShapes.Shape.STRAIGHT), source.get(4, 10, 1), "east hip end");

        Box square = new Box(new BlockPos(0, 10, 0), new BlockPos(2, 10, 2));
        GeneratedSource pyramid = generate(spec(square, RoofKernel.Style.HIP, RoofKernel.Ridge.AUTO, RoofKernel.Pitch.NORMAL,
                0, 1, false, RoofKernel.Inside.LEAVE));
        assertEquals(9, pyramid.cells());
        assertEquals(full, pyramid.get(1, 11, 1), "a square hip roof peaks in one block");
        Box evenSquare = new Box(new BlockPos(0, 10, 0), new BlockPos(3, 10, 3));
        GeneratedSource peak = generate(spec(evenSquare, RoofKernel.Style.HIP, RoofKernel.Ridge.AUTO, RoofKernel.Pitch.NORMAL,
                0, 1, false, RoofKernel.Inside.LEAVE));
        assertEquals(16, peak.cells());
        // The top four stairs sit on the hip lines: they face across the shorter axis (a tie), two by two.
        assertEquals(stair(Facing.SOUTH, StairShapes.Shape.STRAIGHT), peak.get(1, 11, 1));
        assertEquals(stair(Facing.SOUTH, StairShapes.Shape.STRAIGHT), peak.get(2, 11, 1));
        assertEquals(stair(Facing.NORTH, StairShapes.Shape.STRAIGHT), peak.get(1, 11, 2));
        assertEquals(stair(Facing.NORTH, StairShapes.Shape.STRAIGHT), peak.get(2, 11, 2));
        assertEquals(stair(Facing.SOUTH, StairShapes.Shape.OUTER_LEFT), peak.get(0, 10, 0), "the corners turn");
        assertEquals(stair(Facing.NORTH, StairShapes.Shape.OUTER_LEFT), peak.get(3, 10, 3));
    }

    @Test
    void aShedRoofRisesAwayFromItsLowSide() {
        RoofKernel.Spec north = new RoofKernel.Spec(FOOTPRINT, RoofKernel.Style.SHED, RoofKernel.Ridge.AUTO,
                RoofKernel.Side.NORTH, RoofKernel.Pitch.NORMAL, 0, 1, true, RoofKernel.Inside.LEAVE, 0);
        GeneratedSource source = generate(north);
        assertEquals(15 + 2 * (1 + 2), source.cells());
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 2; z++) {
                assertEquals(stair(Facing.SOUTH, StairShapes.Shape.STRAIGHT), source.get(x, 10 + z, z), x + "," + z);
            }
        }
        for (int x : new int[] {0, 4}) {
            assertEquals(full, source.get(x, 10, 1));
            assertEquals(full, source.get(x, 10, 2));
            assertEquals(full, source.get(x, 11, 2));
        }
        assertEquals(-1, source.get(2, 10, 2), "no wall in the middle");
        RoofKernel.Spec east = new RoofKernel.Spec(FOOTPRINT, RoofKernel.Style.SHED, RoofKernel.Ridge.AUTO,
                RoofKernel.Side.EAST, RoofKernel.Pitch.NORMAL, 0, 1, false, RoofKernel.Inside.LEAVE, 0);
        GeneratedSource fromEast = generate(east);
        for (int z = 0; z <= 2; z++) {
            for (int x = 0; x <= 4; x++) {
                assertEquals(stair(Facing.WEST, StairShapes.Shape.STRAIGHT), fromEast.get(x, 10 + (4 - x), z), x + "," + z);
            }
        }
    }

    // ---------------------------------------------------------------- limits

    @Test
    void theCapAndTheBuildHeightBoundTheRoof() {
        RoofKernel.Spec spec = spec(FOOTPRINT, RoofKernel.Style.GABLE, RoofKernel.Ridge.AUTO, RoofKernel.Pitch.NORMAL,
                0, 1, false, RoofKernel.Inside.LEAVE);
        assertThrows(GeneratedTooLargeException.class, () -> RoofKernel.generate(spec, roof, -64, 320, 14));
        assertEquals(15, RoofKernel.generate(spec, roof, -64, 320, 15).cells());
        // A vast footprint is refused before any column is looked at, and eaves above the world write nothing.
        RoofKernel.Spec vast = spec(new Box(new BlockPos(0, 10, 0), new BlockPos(9_999, 10, 9_999)), RoofKernel.Style.HIP,
                RoofKernel.Ridge.AUTO, RoofKernel.Pitch.NORMAL, 0, 1, false, RoofKernel.Inside.LEAVE);
        long started = System.nanoTime();
        assertThrows(GeneratedTooLargeException.class, () -> RoofKernel.generate(vast, roof, -64, 320, 2_097_152));
        assertTrue(System.nanoTime() - started < 1_000_000_000L, "refused at once");
        assertTrue(RoofKernel.generate(spec, roof, -64, 10, 100).isEmpty(), "eaves above the build height");
        GeneratedSource cut = RoofKernel.generate(spec, roof, -64, 11, 100);
        assertEquals(10, cut.cells(), "the ridge at y 11 is outside the build height");
        assertThrows(IllegalArgumentException.class, () -> spec(FOOTPRINT, RoofKernel.Style.GABLE, RoofKernel.Ridge.AUTO,
                RoofKernel.Pitch.NORMAL, 5, 1, false, RoofKernel.Inside.LEAVE));
        assertThrows(IllegalArgumentException.class, () -> spec(FOOTPRINT, RoofKernel.Style.GABLE, RoofKernel.Ridge.AUTO,
                RoofKernel.Pitch.NORMAL, 0, 4, false, RoofKernel.Inside.LEAVE));
    }

    @Test
    void unknownMaterialsAreNamed() {
        UnknownMaterialException e = assertThrows(UnknownMaterialException.class, () -> RoofStates.resolve(states,
                new RoofKernel.Materials(BlockDescriptor.parse("minecraft:no_stairs"), oak.slab(), oak.full())));
        assertEquals("minecraft:no_stairs", e.state().block().value());
        assertThrows(UnknownMaterialException.class, () -> RoofStates.resolve(states,
                new RoofKernel.Materials(oak.stairs(), BlockDescriptor.parse("minecraft:stone"), oak.full())),
                "a slab needs a type property");
        assertTrue(assertThrows(UnknownMaterialException.class, () -> RoofStates.resolve(states,
                new RoofKernel.Materials(oak.stairs(), oak.slab(), BlockDescriptor.parse("minecraft:nothing"))))
                .getMessage().contains("minecraft:nothing"));
        assertEquals(stair(Facing.EAST, StairShapes.Shape.INNER_RIGHT), roof.stair(Facing.EAST, StairShapes.Shape.INNER_RIGHT));
    }
}
