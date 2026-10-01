package dev.sculptory.core.scatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.MultiPaste;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.scatter.ScatterArea.Stamp;
import dev.sculptory.core.scatter.ScatterFixture.Spec;
import dev.sculptory.core.scatter.ScatterSettings.Fit;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Block variants: resolving their state text, the double-tall footprint, rotation, and the planner's survival hook. */
class BlockVariantsTest {
    private final ScatterFixture f = new ScatterFixture();
    private final int poppy = f.states.state("minecraft:poppy");
    private final int tallLower = f.states.state("minecraft:tall_grass[half=lower]");
    private final int tallUpper = f.states.state("minecraft:tall_grass[half=upper]");

    private String reason(String text) {
        return assertThrows(IllegalArgumentException.class, () -> BlockVariants.resolve(f.states, text)).getMessage();
    }

    @Test
    void stateTextResolvesExactlyOrSaysWhyNot() {
        assertEquals(poppy, BlockVariants.resolve(f.states, "minecraft:poppy"));
        assertEquals(f.states.state("minecraft:pink_petals[facing=west,flower_amount=3]"),
                BlockVariants.resolve(f.states, "minecraft:pink_petals[facing=west,flower_amount=3]"));
        assertTrue(reason("minecraft:poppy[").startsWith("malformed block state"), reason("minecraft:poppy["));
        assertEquals("unknown block minecraft:not_a_block", reason("minecraft:not_a_block"));
        assertTrue(reason("minecraft:poppy[age=3]").startsWith("invalid properties for minecraft:poppy"));
        assertTrue(reason("minecraft:pink_petals[facing=up]").startsWith("invalid properties for minecraft:pink_petals"));
        assertEquals("air is not a scatter variant", reason("minecraft:air"));
        assertEquals("minecraft:water is a fluid, not a scatter variant", reason("minecraft:water[level=0]"));
    }


    /**
     * Where a block goes follows from its state: aquatic blocks (made waterlogged when they can be) and waterlogged
     * states under water, blocks that go on water on the surface (dry), everything else on land (then holding no
     * water). A block that holds water without being a water plant (a bubble column) and fluids are refused.
     */
    @Test
    void aBlocksStateSaysWhereItGoes() {
        int seagrass = f.states.state("minecraft:seagrass");
        assertEquals(seagrass, BlockVariants.resolve(f.states, "minecraft:seagrass"));
        assertEquals(BlockVariants.Medium.UNDERWATER, BlockVariants.medium(f.states, seagrass));
        int wetPickle = f.states.state("minecraft:sea_pickle[pickles=1,waterlogged=true]");
        assertEquals(wetPickle, BlockVariants.resolve(f.states, "minecraft:sea_pickle"), "waterlogged by default: kept");
        assertEquals(BlockVariants.Medium.UNDERWATER, BlockVariants.medium(f.states, wetPickle));
        int dryPickle = f.states.state("minecraft:sea_pickle[pickles=3,waterlogged=false]");
        assertEquals(dryPickle, BlockVariants.resolve(f.states, "minecraft:sea_pickle[pickles=3,waterlogged=false]"));
        assertEquals(BlockVariants.Medium.LAND, BlockVariants.medium(f.states, dryPickle));
        assertEquals(dryPickle, BlockVariants.clipboard(f.states, dryPickle).get(0, 0, 0));
        assertEquals(wetPickle, BlockVariants.clipboard(f.states, wetPickle).get(0, 0, 0));
        int wetSlab = f.states.state("minecraft:oak_slab[type=top,waterlogged=true]");
        assertEquals(wetSlab, BlockVariants.resolve(f.states, "minecraft:oak_slab[type=top,waterlogged=true]"),
                "a waterlogged block the player picked goes under water");
        assertEquals(BlockVariants.Medium.UNDERWATER, BlockVariants.medium(f.states, wetSlab));
        assertEquals(BlockVariants.Medium.LAND, BlockVariants.medium(f.states, poppy));

        int wetFan = f.states.state("minecraft:tube_coral_fan[waterlogged=true]");
        assertEquals(wetFan, BlockVariants.resolve(f.states, "minecraft:tube_coral_fan[waterlogged=false]"),
                "live coral dies out of water: made waterlogged");
        int dryFan = f.states.state("minecraft:tube_coral_fan[waterlogged=false]");
        assertEquals("minecraft:tube_coral_fan dies out of water: it goes in waterlogged",
                assertThrows(IllegalArgumentException.class, () -> BlockVariants.clipboard(f.states, dryFan))
                        .getMessage());

        int lily = f.states.state("minecraft:lily_pad");
        assertEquals(lily, BlockVariants.resolve(f.states, "minecraft:lily_pad"));
        assertEquals(BlockVariants.Medium.WATER_SURFACE, BlockVariants.medium(f.states, lily));

        assertEquals("minecraft:bubble_column carries water but is not a water plant",
                reason("minecraft:bubble_column"));
        assertEquals("minecraft:lava is a fluid, not a scatter variant", reason("minecraft:lava"));
        int tallLowerWet = f.states.state("minecraft:tall_seagrass[half=lower]");
        assertEquals(tallLowerWet, BlockVariants.resolve(f.states, "minecraft:tall_seagrass[half=upper]"));
        Clipboard tallSeagrass = BlockVariants.clipboard(f.states, tallLowerWet);
        assertEquals(f.states.state("minecraft:tall_seagrass[half=upper]"), tallSeagrass.get(0, 1, 0));
    }

    /**
     * A column plant goes in by its top (kelp for a picked kelp_plant) and builds columns of kelp_plant under kelp, or
     * of the plant itself (sugar cane); anything else is not a column.
     */
    @Test
    void aColumnPlantBuildsColumns() {
        int kelp = f.states.state("minecraft:kelp");
        int kelpPlant = f.states.state("minecraft:kelp_plant");
        assertEquals(kelp, BlockVariants.resolve(f.states, "minecraft:kelp_plant"), "the top of its column");
        assertTrue(BlockVariants.isColumn(f.states, kelp));
        assertFalse(BlockVariants.isColumn(f.states, poppy));
        Clipboard three = BlockVariants.column(f.states, kelp, 3);
        assertEquals(new BlockPos(1, 3, 1), three.size());
        assertEquals(BlockPos.ORIGIN, three.anchor());
        assertEquals(kelpPlant, three.get(0, 0, 0));
        assertEquals(kelpPlant, three.get(0, 1, 0));
        assertEquals(kelp, three.get(0, 2, 0));
        int grown = f.states.state("minecraft:kelp[age=25]");
        assertEquals(grown, BlockVariants.column(f.states, grown, 2).get(0, 1, 0), "the top keeps its age");
        assertEquals(new BlockPos(1, 1, 1), BlockVariants.column(f.states, kelp, 1).size());
        int cane = f.states.state("minecraft:sugar_cane");
        Clipboard canes = BlockVariants.column(f.states, cane, ScatterSettings.MAX_COLUMN_HEIGHT);
        for (int y = 0; y < ScatterSettings.MAX_COLUMN_HEIGHT; y++) assertEquals(cane, canes.get(0, y, 0));
        assertThrows(IllegalArgumentException.class, () -> BlockVariants.column(f.states, poppy, 2));
        assertThrows(IllegalArgumentException.class, () -> BlockVariants.column(f.states, kelp, 0));
        assertThrows(IllegalArgumentException.class,
                () -> BlockVariants.column(f.states, kelp, ScatterSettings.MAX_COLUMN_HEIGHT + 1));
    }

    /** The state text is capped at 256 UTF-8 bytes, so 64 block variants fit one client frame. */
    @Test
    void theStateTextIsCappedAt256Bytes() {
        String at = "minecraft:" + "a".repeat(ScatterSource.MAX_STATE_BYTES - "minecraft:".length());
        assertEquals(256, ScatterSource.MAX_STATE_BYTES);
        assertEquals(at, new ScatterSource.Block(at).state());
        assertThrows(IllegalArgumentException.class, () -> new ScatterSource.Block(at + "a"));
        assertThrows(IllegalArgumentException.class, () -> new ScatterSource.Block("minecraft:" + "é".repeat(124)),
                "counted in UTF-8 bytes, not characters");
    }
    /** A double-tall block, whichever half was picked, places its lower half on the anchor and the upper above. */
    @Test
    void aDoubleTallBlockIsAOneByTwoByOneFootprint() {
        assertEquals(tallLower, BlockVariants.resolve(f.states, "minecraft:tall_grass[half=upper]"));
        assertTrue(BlockVariants.doubleTall(f.states, tallUpper));
        assertFalse(BlockVariants.doubleTall(f.states, poppy));
        assertFalse(BlockVariants.doubleTall(f.states, f.states.state("minecraft:oak_stairs[half=top]")),
                "stairs' half is top/bottom, not a double-tall half");
        int slab = f.states.state("testmod:vertical_slab[half=upper]");
        assertFalse(BlockVariants.doubleTall(f.states, slab), "a lower/upper property alone is not a two-block plant");
        assertEquals(slab, BlockVariants.resolve(f.states, "testmod:vertical_slab[half=upper]"), "kept as picked");
        assertEquals(new BlockPos(1, 1, 1), BlockVariants.clipboard(f.states, slab).size());
        Clipboard tall = BlockVariants.clipboard(f.states, tallUpper);
        assertEquals(new BlockPos(1, 2, 1), tall.size());
        assertEquals(BlockPos.ORIGIN, tall.anchor());
        assertEquals(tallLower, tall.get(0, 0, 0));
        assertEquals(tallUpper, tall.get(0, 1, 0));
        Clipboard single = BlockVariants.clipboard(f.states, poppy);
        assertEquals(new BlockPos(1, 1, 1), single.size());
        assertEquals(poppy, single.get(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> BlockVariants.clipboard(f.states, f.states.air()));
    }

    /** A directional block turns and mirrors with its placement, through the state space (vanilla on a server). */
    @Test
    void aDirectionalBlockTurnsWithItsPlacement() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        int north = f.states.state("minecraft:pink_petals[facing=north,flower_amount=2]");
        Clipboard petals = BlockVariants.clipboard(f.states, north);
        MultiPaste paste = new MultiPaste(List.of(petals.toSource()), List.of(
                new MultiPaste.Placement(new BlockPos(2, 65, 2), 0, Transform.rotation(1)),
                new MultiPaste.Placement(new BlockPos(6, 65, 2), 0, new Transform(0, Mirror.Z)),
                new MultiPaste.Placement(new BlockPos(10, 65, 2), 0, Transform.IDENTITY)), MultiPaste.Replace.OPEN);
        UUID id = UUID.randomUUID();
        EditProgram program = OpCompiler.compile(new OpSpec.ScatterCommit(id),
                ScatterFixture.context(f.states, id, paste, Long.MAX_VALUE));
        FakeExecutor.run(program, world);
        assertEquals(f.states.state("minecraft:pink_petals[facing=east,flower_amount=2]"), world.get(2, 65, 2));
        assertEquals(f.states.state("minecraft:pink_petals[facing=south,flower_amount=2]"), world.get(6, 65, 2));
        assertEquals(north, world.get(10, 65, 2));
    }

    /**
     * The planner asks the survival check last, only with {@code Fit.survive}, about the source and transform it
     * picked; a candidate that fails ends as SURVIVAL.
     */
    @Test
    void thePlannerAsksTheSurvivalCheckOnlyWhenAsked() {
        FakeWorld world = f.flat(0, 0, 31, 31, 64);
        List<int[]> asked = new ArrayList<>();
        ScatterPlanner.SurvivalCheck evenX = (source, transform, x, y, z) -> {
            asked.add(new int[] {source, x, y, z});
            return (x & 1) == 0 ? null : Outcome.SURVIVAL;
        };
        Spec spec = Spec.box(0, 0, 31, 31).density(new ScatterSettings.Density.Fraction(0.5)).seed(9);
        List<Clipboard> sources = List.of(BlockVariants.clipboard(f.states, poppy));
        ScatterPlan surviving = new ScatterPlanner(spec.build(), sources, world, Long.MAX_VALUE,
                ScatterPlanner.DEFAULT_MAX_WORK, ScatterPlanner.ColumnGuard.ALLOW_ALL, null, evenX).finish();
        ScatterFixture.assertBalanced(surviving);
        assertTrue(surviving.placements().size() > 20, surviving.toString());
        assertTrue(surviving.count(Outcome.SURVIVAL) > 20, surviving.toString());
        for (ScatterPlan.Placement p : surviving.placements()) assertEquals(0, p.anchor().x() & 1, "odd x: " + p);
        assertEquals(surviving.placements().size() + surviving.count(Outcome.SURVIVAL), asked.size());
        for (int[] call : asked) {
            assertEquals(0, call[0], "the source index");
            assertEquals(65, call[2], "the anchor, on the cell above the surface");
        }

        asked.clear();
        ScatterPlan anywhere = new ScatterPlanner(spec.fit(Fit.DEFAULT.withSurvive(false)).build(), sources, world,
                Long.MAX_VALUE, ScatterPlanner.DEFAULT_MAX_WORK, ScatterPlanner.ColumnGuard.ALLOW_ALL, null, evenX).finish();
        assertTrue(asked.isEmpty(), "asked with survive off");
        assertEquals(0, anywhere.count(Outcome.SURVIVAL));
        assertEquals(surviving.placements().size() + surviving.count(Outcome.SURVIVAL), anywhere.placements().size());
    }

    /**
     * A survival check that cannot tell without an unloaded chunk answers UNLOADED, counted as such; any other refusal
     * counts as SURVIVAL.
     */
    @Test
    void aSurvivalCheckMayAnswerUnloaded() {
        FakeWorld world = f.flat(0, 0, 31, 31, 64);
        ScatterPlanner.SurvivalCheck edges = (source, transform, x, y, z) ->
                x < 4 ? Outcome.UNLOADED : x >= 28 ? Outcome.COLLISION : null;
        Spec spec = Spec.box(0, 0, 31, 31);
        List<Clipboard> sources = List.of(BlockVariants.clipboard(f.states, poppy));
        ScatterPlan plan = new ScatterPlanner(spec.build(), sources, world, Long.MAX_VALUE,
                ScatterPlanner.DEFAULT_MAX_WORK, ScatterPlanner.ColumnGuard.ALLOW_ALL, null, edges).finish();
        ScatterFixture.assertBalanced(plan);
        assertEquals(4 * 32, plan.count(Outcome.UNLOADED));
        assertEquals(4 * 32, plan.count(Outcome.SURVIVAL), "another answer counts as SURVIVAL");
        assertEquals(0, plan.count(Outcome.COLLISION));
        assertEquals(24 * 32, plan.placements().size());
    }

    /**
     * Both cells of a double-tall footprint must be open: a block where the upper half would go (above the scan
     * window, so it is not taken for the surface) blocks that column.
     */
    @Test
    void aDoubleTallVariantNeedsTwoOpenCells() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        world.set(4, 66, 4, f.stone);
        List<Clipboard> sources = List.of(BlockVariants.clipboard(f.states, tallLower));
        Spec spec = new Spec(new ScatterArea.Region(dev.sculptory.core.Box.of(new BlockPos(4, 60, 4),
                new BlockPos(8, 65, 4))));
        ScatterPlan plan = ScatterFixture.plan(spec, sources, world);
        assertEquals(4, plan.placements().size(), plan.toString());
        for (ScatterPlan.Placement p : plan.placements()) assertTrue(p.anchor().x() != 4, "under the stone: " + p);
        assertEquals(1, plan.count(Outcome.COLLISION));
        assertEquals(8, plan.totalCells(), "two cells each");
    }
}
