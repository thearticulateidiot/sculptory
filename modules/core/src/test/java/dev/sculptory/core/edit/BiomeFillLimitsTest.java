package dev.sculptory.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A biome fill (protocol 6) is refused as too large before its quarts are listed: a huge box (also mirrored) up front,
 * a long thin shape as soon as it passes the quart budget or the job's chunk columns, quickly in every case.
 */
class BiomeFillLimitsTest {
    private static final StateSpace STATES = new FakeStateSpace();
    private static final long BUDGET = 1_000_000;

    /** An overworld, the default volume budget, and 4,096 chunk columns a job. */
    private static CompileContext context(long maxCells) {
        return new CompileContext() {
            @Override
            public StateSpace states() {
                return STATES;
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
            public long maxCells() {
                return maxCells;
            }

            @Override
            public long maxSections() {
                return 24L * 4096;
            }
        };
    }

    @Test
    void aHugeBoxIsRefusedBeforeItsQuartsAreListedMirroredToo() {
        Region huge = new Region.Cuboid(new Box(new BlockPos(-30_000_000, 60, -30_000_000),
                new BlockPos(30_000_000, 60, 30_000_000)));
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0);
        for (OpSpec.BiomeFill fill : new OpSpec.BiomeFill[] {new OpSpec.BiomeFill(huge, "minecraft:desert", true),
                new OpSpec.BiomeFill(huge, "minecraft:desert", false, mirror)}) {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(EditTooLargeException.class,
                    () -> OpCompiler.compile(fill, context(BUDGET))));
        }
        // Two mirrored boxes each under the budget, together over it.
        Region half = new Region.Cuboid(new Box(new BlockPos(0, 60, 0), new BlockPos(399, 60, 399)));
        OpSpec.BiomeFill pair = new OpSpec.BiomeFill(half, "minecraft:desert", true, new Symmetry(Symmetry.Mode.MIRROR_X,
                -2000, 0));
        assertEquals(100L * 100 * 96, ((BiomeFillProgram) OpCompiler.compile(new OpSpec.BiomeFill(half,
                "minecraft:desert", true), context(BUDGET))).estimatedCells());
        assertThrows(EditTooLargeException.class, () -> OpCompiler.compile(pair, context(BUDGET)));
    }

    @Test
    void aLongThinShapeStopsAsSoonAsItPassesTheBudgetOrTheColumns() {
        // A cylinder 180 across and 60 million long: few rows, so it passes the shape row cap.
        Region cylinder = new Region.Shape(new Box(new BlockPos(-30_000_000, 0, -90), new BlockPos(30_000_000, 179, 89)),
                ShapeKind.CYLINDER, Facing.EAST);
        for (boolean columns : new boolean[] {true, false}) {
            OpSpec.BiomeFill fill = new OpSpec.BiomeFill(cylinder, "minecraft:desert", columns);
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(EditTooLargeException.class,
                    () -> OpCompiler.compile(fill, context(BUDGET))), "columns " + columns);
            // limit.bypass lifts the budget, not the job's chunk columns.
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertThrows(EditTooLargeException.class,
                    () -> OpCompiler.compile(fill, context(Long.MAX_VALUE))), "bypass, columns " + columns);
        }
    }
}
