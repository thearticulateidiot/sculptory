package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSampler;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Use Stairs and Slabs: the step cells of a sloping volume take their family's stair, else its slab, else stay full. */
class SlopeStepsTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int bricks = states.state("minecraft:stone_bricks");
    private final int planks = states.state("minecraft:oak_planks");
    private final int stone = states.state("minecraft:stone");
    private final int air = states.air();

    /** Stone bricks have stairs and slabs, oak planks only a slab here, stone neither. */
    private final SlopeSteps.Variants variants = new SlopeSteps.Variants() {
        @Override
        public int stairs(int state, String facing, String half) {
            if (state != bricks) return -1;
            return states.state("minecraft:stone_brick_stairs[facing=" + facing + ",half=" + half + "]");
        }

        @Override
        public int slab(int state, String type) {
            if (state == bricks) return states.state("minecraft:stone_brick_slab[type=" + type + "]");
            if (state == planks) return states.state("minecraft:oak_slab[type=" + type + "]");
            return -1;
        }
    };

    private int stairs(String facing, String half) {
        return states.state("minecraft:stone_brick_stairs[facing=" + facing + ",half=" + half + "]");
    }

    /** A ramp along +x: two cells per level, from y 64 up, {@code thick} cells deep under its top. */
    private GeneratedSource ramp(int state, int thick) {
        GeneratedSource.Builder out = GeneratedSource.builder(1 << 16);
        for (int x = 0; x < 8; x++) {
            int top = 64 + x / 2;
            for (int y = top - thick + 1; y <= top; y++) out.set(x, y, 0, state);
        }
        return out.build();
    }

    @Test
    void theFirstCellOfEachStepUpIsAStairFacingUpTheClimbAndTheUndersideGetsUpsideDownOnes() {
        SlopeSteps.Result result = SlopeSteps.apply(ramp(bricks, 3), (x, y, z) -> SlopeSteps.AXIS_X, variants, air);
        GeneratedSource s = result.source();
        for (int x = 2; x < 8; x += 2) {
            int top = 64 + x / 2;
            assertEquals(stairs("east", "bottom"), s.get(x, top, 0), "the step up at " + x);
            assertEquals(bricks, s.get(x + 1, top, 0), "the rest of the level stays full");
        }
        assertEquals(bricks, s.get(0, 64, 0), "the first cell has nothing below it to step from");
        // The underside: the last cell before it steps up is an upside-down stair facing down the climb.
        for (int x = 1; x < 7; x += 2) {
            int bottom = 64 + x / 2 - 2;
            assertEquals(stairs("west", "top"), s.get(x, bottom, 0), "under the step at " + x);
        }
        assertEquals(6, result.stepped());
        assertEquals(0, result.plain());
        assertEquals(ramp(bricks, 3).cells(), s.cells(), "no cell added or removed");
    }

    @Test
    void withoutAStairTheSlabAndWithoutEitherTheFullBlockCounted() {
        SlopeSteps.Result slabs = SlopeSteps.apply(ramp(planks, 1), (x, y, z) -> SlopeSteps.AXIS_X, variants, air);
        assertEquals(states.state("minecraft:oak_slab[type=bottom]"), slabs.source().get(2, 65, 0));
        assertTrue(slabs.stepped() > 0);
        SlopeSteps.Result plain = SlopeSteps.apply(ramp(stone, 1), (x, y, z) -> SlopeSteps.AXIS_X, variants, air);
        assertEquals(0, plain.stepped());
        assertEquals(3, plain.plain(), "three steps up kept full: the host says so");
        plain.source().forEach((x, y, z, state) -> assertEquals(stone, state));
    }

    @Test
    void levelGroundCrestsAirAndUprightStretchesGetNoStairs() {
        GeneratedSource.Builder out = GeneratedSource.builder(1 << 10);
        for (int x = 0; x < 5; x++) out.set(x, 64, 0, bricks);
        out.set(2, 65, 0, bricks); // a one-cell crest: down both ways
        for (int x = 0; x < 5; x++) out.set(x, 66, 0, air); // a road's cleared space is not part of it
        GeneratedSource flat = out.build();
        SlopeSteps.Result result = SlopeSteps.apply(flat, (x, y, z) -> SlopeSteps.AXIS_X, variants, air);
        assertEquals(0, result.stepped());
        assertEquals(0, result.plain());
        assertEquals(0, SlopeSteps.apply(ramp(bricks, 2), (x, y, z) -> SlopeSteps.NONE, variants, air).stepped(),
                "no climb axis: upright");
        // The road's air over a step does not hide it: the step's top cell is under air only.
        GeneratedSource.Builder road = GeneratedSource.builder(1 << 10);
        for (int x = 0; x < 4; x++) {
            int top = 64 + x / 2;
            road.set(x, top, 0, bricks);
            road.set(x, top + 1, 0, air);
        }
        assertEquals(stairs("east", "bottom"),
                SlopeSteps.apply(road.build(), (x, y, z) -> SlopeSteps.AXIS_X, variants, air).source().get(2, 65, 0));
    }

    @Test
    void aClimbingLinesAxisComesFromItsNearestSampleAndATurnedLineStepsTheOtherWay() {
        PathSpec north = new PathSpec(List.of(new BlockPos(0, 64, 0), new BlockPos(0, 70, -12)), PathKind.STRAIGHT);
        SlopeSteps.Axis axis = SlopeSteps.along(PathSampler.sample(north, 0.5));
        assertEquals(SlopeSteps.AXIS_Z, axis.at(0, 66, -4));
        assertEquals(SlopeSteps.NONE, axis.at(100, 64, 100), "far from the line");
        PathSpec up = new PathSpec(List.of(new BlockPos(0, 64, 0), new BlockPos(0, 90, 1)), PathKind.STRAIGHT);
        assertEquals(SlopeSteps.NONE, SlopeSteps.along(PathSampler.sample(up, 0.5)).at(0, 70, 0), "nearly straight up");
        GeneratedSource line = LineKernel.generate(new LineKernel.Spec(north, 1, LineKernel.Profile.ROUND,
                new Pattern.Single(bricks)), states, 1 << 16);
        SlopeSteps.Result result = SlopeSteps.apply(line, axis, variants, air);
        assertTrue(result.stepped() >= 5, "a step every two blocks: " + result.stepped());
        result.source().forEach((x, y, z, state) -> {
            if (state != bricks) {
                assertTrue(state == stairs("north", "bottom") || state == stairs("south", "top"),
                        "facing up the climb (or down it, upside down, underneath) at " + x + "," + y + "," + z);
            }
        });
    }
}
