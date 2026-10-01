package dev.sculptory.core.scatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.MultiPaste;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryPrograms;
import dev.sculptory.core.scatter.ScatterFixture.Spec;
import dev.sculptory.core.scatter.ScatterSettings.Filters;
import dev.sculptory.core.scatter.ScatterSettings.Fit;
import dev.sculptory.core.scatter.ScatterSettings.Transforms;
import dev.sculptory.core.scatter.ScatterSettings.Variant;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Water plants and column plants in the planner and the commit: underwater variants stand on the seabed in still water
 * only, lily pads on still water surfaces only, land variants stay on land, kelp columns are capped by the water above
 * them, existing water plants are not replaced unless fluids are allowed, and plans do not depend on the partition.
 */
class WaterPlantsTest {
    private final ScatterFixture f = new ScatterFixture();
    private final int poppy = f.states.state("minecraft:poppy");
    private final int seagrass = f.states.state("minecraft:seagrass");
    private final int tallSeagrass = f.states.state("minecraft:tall_seagrass[half=lower]");
    private final int fan = f.states.state("minecraft:tube_coral_fan[waterlogged=true]");
    private final int lily = f.states.state("minecraft:lily_pad");
    private final int kelp = f.states.state("minecraft:kelp");
    private final int kelpPlant = f.states.state("minecraft:kelp_plant");
    private final int cane = f.states.state("minecraft:sugar_cane");
    private final int flowing = f.states.state("minecraft:water[level=2]");
    private final int lava = f.states.state("minecraft:lava");
    /** Still water. */
    private final int water = f.water;
    private final UUID id = UUID.randomUUID();

    /** The lake's still-water columns: x 6-13 (z 0-15). */
    private static boolean lake(int x) {
        return x >= 6 && x <= 13;
    }

    /**
     * Land for x 0-5 (grass at 64 on stone) and a lake for x 6-15 (z 0-15): a sand seabed at 60 under still water at
     * 61-64, except flowing water at x 14 and lava at x 15.
     */
    private FakeWorld shore() {
        FakeWorld world = new FakeWorld(f.states);
        world.setHeightHints(true);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                world.set(x, 58, z, f.stone);
                world.set(x, 59, z, f.stone);
                if (x < 6) {
                    for (int y = 60; y < 64; y++) world.set(x, y, z, f.stone);
                    world.set(x, 64, z, f.grass);
                    continue;
                }
                world.set(x, 60, z, f.sand);
                int fluid = x == 14 ? flowing : x == 15 ? lava : water;
                for (int y = 61; y <= 64; y++) world.set(x, y, z, fluid);
            }
        }
        return world;
    }

    private List<Clipboard> sources(int... blocks) {
        List<Clipboard> sources = new ArrayList<>();
        for (int block : blocks) sources.add(BlockVariants.clipboard(f.states, block));
        return sources;
    }

    private ScatterPlanner planner(Spec spec, FakeWorld world, int... blocks) {
        return new ScatterPlanner(spec.build(), sources(blocks), world, Long.MAX_VALUE, ScatterPlanner.DEFAULT_MAX_WORK,
                ScatterPlanner.ColumnGuard.ALLOW_ALL, null, ScatterPlanner.SurvivalCheck.ALWAYS, blocks);
    }

    private ScatterPlan plan(Spec spec, FakeWorld world, int... blocks) {
        ScatterPlan plan = planner(spec, world, blocks).finish();
        ScatterFixture.assertBalanced(plan);
        return plan;
    }

    private static Spec all() {
        return Spec.box(0, 0, 15, 15);
    }

    /** Every column, its surface searched between y 60 and 65. */
    private static Spec window() {
        return new Spec(new ScatterArea.Region(Box.of(new BlockPos(0, 60, 0), new BlockPos(15, 65, 15))));
    }

    private EditProgram compile(ScatterPlan plan) {
        return OpCompiler.compile(new OpSpec.ScatterCommit(id),
                ScatterFixture.context(f.states, id, plan.toMultiPaste(), Long.MAX_VALUE));
    }

    private static int[][][] snapshot(FakeWorld world) {
        int[][][] cells = new int[16][12][16];
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 12; y++) {
                for (int z = 0; z < 16; z++) cells[x][y][z] = world.get(x, 58 + y, z);
            }
        }
        return cells;
    }

    private static void assertSame(int[][][] expected, int[][][] actual) {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 12; y++) {
                for (int z = 0; z < 16; z++) {
                    assertEquals(expected[x][y][z], actual[x][y][z], "at " + x + "," + (58 + y) + "," + z);
                }
            }
        }
    }

    private void undoExactly(FakeExecutor.Result commit, FakeWorld world, int[][][] before) {
        HistoryEntry entry = new HistoryEntry(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", "Scatter",
                commit.record(), 0L);
        FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(entry, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(0, undo.conflicts());
        assertSame(before, snapshot(world));
    }

    /**
     * An underwater variant's anchor is the cell above the seabed, in still water: never on dry ground, in flowing
     * water or in lava (those columns end as WATER).
     */
    @Test
    void underwaterVariantsStandOnTheSeabedInStillWaterOnly() {
        FakeWorld world = shore();
        for (int block : new int[] {seagrass, fan}) {
            ScatterPlan plan = plan(all(), world, block);
            assertEquals(BlockVariants.Medium.UNDERWATER, plan.medium(0));
            assertEquals(8 * 16, plan.placements().size(), plan.toString());
            for (ScatterPlan.Placement p : plan.placements()) {
                assertTrue(lake(p.anchor().x()), "not in the still lake: " + p);
                assertEquals(61, p.anchor().y(), "not on the seabed: " + p);
            }
            assertEquals(8 * 16, plan.count(Outcome.WATER), "land, flowing water and lava: " + plan);
        }
        ScatterPlan poppies = plan(all(), world, poppy);
        for (ScatterPlan.Placement p : poppies.placements()) assertTrue(p.anchor().x() < 6, "a poppy under water: " + p);
        assertEquals(6 * 16, poppies.placements().size(), poppies.toString());
    }

    /** A two-cell water plant needs still water in both cells: in water one block deep it is not placed. */
    @Test
    void aTallSeagrassNeedsTwoCellsOfWater() {
        FakeWorld world = shore();
        for (int x = 6; x <= 13; x++) {
            for (int y = 62; y <= 64; y++) world.set(x, y, 0, f.air);
        }
        ScatterPlan plan = plan(all(), world, tallSeagrass);
        assertEquals(8 * 15, plan.placements().size(), plan.toString());
        for (ScatterPlan.Placement p : plan.placements()) assertTrue(p.anchor().z() > 0, "in shallow water: " + p);
        assertEquals(8 * 16 + 8, plan.count(Outcome.WATER), "the shallow row too: " + plan);
        assertEquals(2L * 8 * 15, plan.totalCells());
    }

    /**
     * A variant that goes on water anchors on the air cell above a still water surface: never on land, over flowing
     * water or over lava.
     */
    @Test
    void aLilyPadGoesOnStillWaterOnly() {
        ScatterPlan plan = plan(all(), shore(), lily);
        assertEquals(BlockVariants.Medium.WATER_SURFACE, plan.medium(0));
        assertEquals(8 * 16, plan.placements().size(), plan.toString());
        for (ScatterPlan.Placement p : plan.placements()) {
            assertTrue(lake(p.anchor().x()), "not over the still lake: " + p);
            assertEquals(65, p.anchor().y(), "not on the surface: " + p);
        }
        assertEquals(8 * 16, plan.count(Outcome.WATER), plan.toString());

        // Under a roof (the window's top in the water), no surface is found.
        Spec low = new Spec(new ScatterArea.Region(Box.of(new BlockPos(0, 50, 0), new BlockPos(15, 63, 15))));
        assertEquals(0, plan(low, shore(), lily).placements().size());
    }

    /**
     * The filters read the anchor's support: the seabed block and its height under water, the water surface for a
     * lily pad.
     */
    @Test
    void filtersReadTheSupport() {
        FakeWorld world = shore();
        Filters sandBelow62 = Filters.NONE.withElevation(Integer.MIN_VALUE, 62)
                .withSubstrate(new CellMask.Blocks(List.of(new NamespacedId("minecraft:sand"))));
        ScatterPlan seabed = plan(all().filters(sandBelow62), world, seagrass);
        assertEquals(8 * 16, seabed.placements().size(), seabed.toString());
        ScatterPlan surface = plan(all().filters(sandBelow62), world, lily);
        assertEquals(0, surface.placements().size());
        assertEquals(8 * 16, surface.count(Outcome.ELEVATION), "the surface is at 64: " + surface);
        ScatterPlan onSand = plan(all().filters(Filters.NONE.withSubstrate(
                new CellMask.Blocks(List.of(new NamespacedId("minecraft:sand"))))), world, lily);
        assertEquals(8 * 16, onSand.count(Outcome.SUBSTRATE), "a lily pad's support is the water: " + onSand);
        ScatterPlan onWater = plan(all().filters(Filters.NONE.withSubstrate(
                new CellMask.Blocks(List.of(new NamespacedId("minecraft:water"))))), world, lily);
        assertEquals(8 * 16, onWater.placements().size(), onWater.toString());
        ScatterPlan flat = plan(all().filters(Filters.NONE.withExtra(new SurfaceMask.Slope(0, 0))), world, lily);
        assertEquals(8 * 16, flat.placements().size(), "the water surface is flat: " + flat);
    }

    /**
     * Kelp grows as a column picked in the column height range and capped by the still water above the seabed: kelp
     * on top of kelp_plant, the top at the surface at most. The commit writes the columns and one undo restores the
     * water exactly.
     */
    @Test
    void kelpColumnsAreCappedByTheWater() {
        FakeWorld world = shore();
        int[][][] before = snapshot(world);
        ScatterPlan plan = plan(all().columns(1, 8), world, kelp);
        assertEquals(8 * 16, plan.placements().size(), plan.toString());
        Set<Integer> heights = new HashSet<>();
        long cells = 0;
        for (ScatterPlan.Placement p : plan.placements()) {
            assertTrue(lake(p.anchor().x()) && p.anchor().y() == 61, "not on the seabed: " + p);
            assertTrue(p.height() >= 1 && p.height() <= 4, "above the surface: " + p);
            heights.add(p.height());
            cells += p.height();
            Clipboard column = plan.content(p);
            assertEquals(p.height(), column.size().y());
            for (int y = 0; y < p.height(); y++) {
                assertEquals(y == p.height() - 1 ? kelp : kelpPlant, column.get(0, y, 0), "cell " + y + " of " + p);
            }
        }
        assertTrue(heights.contains(4) && heights.size() >= 3, "heights " + heights);
        assertEquals(cells, plan.totalCells());

        FakeExecutor.Result commit = FakeExecutor.run(compile(plan), world);
        assertEquals(cells, commit.written());
        assertEquals(0, commit.conflicts());
        for (ScatterPlan.Placement p : plan.placements()) {
            int x = p.anchor().x(), z = p.anchor().z();
            for (int y = 61; y <= 64; y++) {
                int expected = y < 61 + p.height() - 1 ? kelpPlant : y == 61 + p.height() - 1 ? kelp : water;
                assertEquals(expected, world.get(x, y, z), "at " + x + "," + y + "," + z + " of " + p);
            }
        }
        undoExactly(commit, world, before);

        ScatterPlan tooShallow = plan(all().columns(5, 8), world, kelp);
        assertEquals(0, tooShallow.placements().size());
        assertEquals(8 * 16 + 8 * 16, tooShallow.count(Outcome.WATER), "four blocks of water are not five: " + tooShallow);
    }

    /** On land, a column plant is capped by the first cell above that is not open. */
    @Test
    void landColumnsAreCappedByOpenCells() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        world.set(3, 67, 3, f.stone);
        ScatterPlan three = plan(window().columns(3, 3), world, cane);
        assertEquals(255, three.placements().size(), three.toString());
        assertEquals(1, three.count(Outcome.COLLISION), "under the stone: " + three);
        for (ScatterPlan.Placement p : three.placements()) assertEquals(3, p.height());
        ScatterPlan capped = plan(window().columns(2, 3), world, cane);
        assertEquals(256, capped.placements().size(), capped.toString());
        for (ScatterPlan.Placement p : capped.placements()) {
            boolean under = p.anchor().x() == 3 && p.anchor().z() == 3;
            assertTrue(under ? p.height() == 2 : p.height() >= 2, p.toString());
        }
        ScatterPlan one = plan(window(), world, cane);
        for (ScatterPlan.Placement p : one.placements()) assertEquals(1, p.height(), "the default is one block");
        assertEquals(256, one.totalCells());
    }

    /**
     * An existing water plant is not replaced unless fluids are allowed: coral fans go around seagrass, and with fluids
     * allowed onto it too.
     */
    @Test
    void existingSeagrassIsNotReplacedUnlessFluidsAreAllowed() {
        FakeWorld world = shore();
        int grown = 0;
        for (int x = 6; x <= 13; x++) {
            for (int z = 0; z < 16; z++) {
                if ((x + z) % 3 != 0) continue;
                world.set(x, 61, z, seagrass);
                grown++;
            }
        }
        ScatterPlan plan = plan(all(), world, fan);
        assertEquals(8 * 16 - grown, plan.placements().size(), plan.toString());
        assertEquals(grown, plan.count(Outcome.COLLISION), plan.toString());
        for (ScatterPlan.Placement p : plan.placements()) {
            assertEquals(water, world.get(p.anchor().x(), 61, p.anchor().z()), "on the seagrass: " + p);
        }
        ScatterPlan wet = plan(all().fit(Fit.DEFAULT.withAllowInFluid(true)), world, fan);
        assertEquals(8 * 16, wet.placements().size(), "fluids allowed: " + wet);
        assertEquals(MultiPaste.Replace.WATER, plan.toMultiPaste().rule(0));
        assertEquals(MultiPaste.Replace.WATER_OR_WET_PLANT, wet.toMultiPaste().rule(0));

        // The commit leaves seagrass grown since the preview, and a cell drained since.
        ScatterPlan.Placement first = plan.placements().get(0), second = plan.placements().get(1);
        world.set(first.anchor().x(), 61, first.anchor().z(), seagrass);
        world.set(second.anchor().x(), 61, second.anchor().z(), f.air);
        FakeExecutor.Result commit = FakeExecutor.run(compile(plan), world);
        assertEquals(2, commit.conflicts());
        assertEquals(plan.placements().size() - 2, commit.written());
        assertEquals(seagrass, world.get(first.anchor().x(), 61, first.anchor().z()));
        assertEquals(f.air, world.get(second.anchor().x(), 61, second.anchor().z()), "a coral fan left dry");
    }

    /**
     * "Allow in fluids" is for held sources: a land block variant (poppies, a sugar cane column) or a lily pad still
     * never replaces a cell holding a fluid, so no dry block goes under water; the same poppy as a clipboard does.
     */
    @Test
    void landBlockVariantsNeverGoIntoWaterEvenWithFluidsAllowed() {
        FakeWorld world = shore();
        Spec wet = all().fit(Fit.DEFAULT.withAllowInFluid(true));
        ScatterPlan poppies = plan(wet, world, poppy);
        assertEquals(6 * 16, poppies.placements().size(), poppies.toString());
        for (ScatterPlan.Placement p : poppies.placements()) assertTrue(p.anchor().x() < 6, "a poppy under water: " + p);
        assertEquals(10 * 16, poppies.count(Outcome.COLLISION), poppies.toString());
        assertEquals(MultiPaste.Replace.OPEN, poppies.toMultiPaste().rule(0));

        ScatterPlan canes = plan(all().fit(Fit.DEFAULT.withAllowInFluid(true)).columns(1, 4), world, cane);
        for (ScatterPlan.Placement p : canes.placements()) assertTrue(p.anchor().x() < 6, "sugar cane in water: " + p);
        ScatterPlan lilies = plan(all().fit(Fit.DEFAULT.withAllowInFluid(true)), world, lily);
        for (ScatterPlan.Placement p : lilies.placements()) assertEquals(65, p.anchor().y(), "a lily pad in water: " + p);

        ScatterPlan held = ScatterFixture.plan(all().fit(Fit.DEFAULT.withAllowInFluid(true)), sources(poppy), world);
        assertTrue(held.placements().stream().anyMatch(p -> lake(p.anchor().x())), "a clipboard may go in: " + held);
        assertEquals(MultiPaste.Replace.OPEN_OR_FLUID, held.toMultiPaste().rule(0));

        // A mix of a land and an underwater block variant commits under per-source rules.
        ScatterPlan mixed = plan(all().fit(Fit.DEFAULT.withAllowInFluid(true)).variants(new Variant(0, 1),
                new Variant(1, 1)), world, poppy, seagrass);
        MultiPaste paste = mixed.toMultiPaste();
        assertEquals(List.of(MultiPaste.Replace.OPEN, MultiPaste.Replace.WATER_OR_WET_PLANT), paste.sourceRules());
        FakeExecutor.run(compile(mixed), world);
        for (int x = 6; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                assertTrue(world.get(x, 61, z) != poppy, "a poppy under water at " + x + "," + z);
            }
        }
    }

    /**
     * A placement of several cells under water (a kelp column, a tall seagrass) is skipped whole when any one of its
     * cells stops holding plain still water before the commit: dried above the anchor, turned to seagrass, or
     * flowing. Nothing of it is written, so no column is left without its top.
     */
    @Test
    void aMultiCellUnderwaterPlacementIsSkippedWholeWhenOneCellChanges() {
        FakeWorld world = shore();
        ScatterPlan plan = plan(all().columns(4, 4).variants(new Variant(0, 1), new Variant(1, 1)), world, kelp,
                tallSeagrass);
        List<ScatterPlan.Placement> columns = plan.placements().stream().filter(p -> p.variant() == 0).toList();
        List<ScatterPlan.Placement> tall = plan.placements().stream().filter(p -> p.variant() == 1).toList();
        assertTrue(columns.size() > 3 && tall.size() > 1, plan.toString());
        for (ScatterPlan.Placement p : columns) assertEquals(4, p.height());
        ScatterPlan.Placement dried = columns.get(0), grown = columns.get(1), flowed = columns.get(2);
        ScatterPlan.Placement lowered = tall.get(0);
        world.set(dried.anchor().x(), 64, dried.anchor().z(), f.air);
        world.set(grown.anchor().x(), 62, grown.anchor().z(), seagrass);
        world.set(flowed.anchor().x(), 63, flowed.anchor().z(), flowing);
        world.set(lowered.anchor().x(), 62, lowered.anchor().z(), flowing);
        FakeExecutor.Result commit = FakeExecutor.run(compile(plan), world);
        assertEquals(4, commit.conflicts());
        assertEquals(plan.totalCells() - 3 * 4 - 2, commit.written(),
                "every cell but those of the three skipped columns and the skipped tall seagrass");
        for (ScatterPlan.Placement p : List.of(dried, grown, flowed, lowered)) {
            int x = p.anchor().x(), z = p.anchor().z();
            assertEquals(water, world.get(x, 61, z), "the anchor of a skipped placement was written: " + p);
        }
        assertEquals(f.air, world.get(dried.anchor().x(), 64, dried.anchor().z()));
        assertEquals(seagrass, world.get(grown.anchor().x(), 62, grown.anchor().z()));
    }

    /** A cell drained while its section is written is not written: the write-time guard asks for still water too. */
    @Test
    void theWriteGuardAsksForStillWater() {
        FakeWorld world = shore();
        ScatterPlan plan = plan(all(), world, seagrass);
        ScatterPlan.Placement target = plan.placements().get(0);
        BlockPos at = target.anchor();
        long section = dev.sculptory.core.buffer.BlockBuffer.keyOfBlock(at.x(), at.y(), at.z());
        FakeExecutor.Result during = FakeExecutor.run(compile(plan), world, new FakeExecutor.Hooks() {
            @Override
            public void afterCompute(long key, FakeWorld w) {
                if (key == section) w.set(at.x(), at.y(), at.z(), flowing);
            }
        });
        assertEquals(flowing, world.get(at.x(), at.y(), at.z()), "seagrass written into flowing water");
        assertEquals(plan.placements().size() - 1, during.written());
        assertEquals(1, during.conflicts());
    }

    /**
     * A mix of land and water plants over a shoreline puts each on its side: poppies on the land, seagrass and kelp on
     * the seabed, lily pads on the water. Nothing dry goes under water; one undo is exact.
     */
    @Test
    void aShorelineMixPutsEachPlantOnItsSide() {
        FakeWorld world = shore();
        int[][][] before = snapshot(world);
        Spec spec = all().columns(1, 3).seed(5).variants(new Variant(0, 1), new Variant(1, 1), new Variant(2, 1),
                new Variant(3, 1));
        ScatterPlan plan = plan(spec, world, poppy, seagrass, lily, kelp);
        int[] seen = new int[4];
        for (ScatterPlan.Placement p : plan.placements()) {
            seen[p.variant()]++;
            int x = p.anchor().x(), y = p.anchor().y();
            switch (p.variant()) {
                case 0 -> assertTrue(x < 6 && y == 65, "a poppy off the land: " + p);
                case 1, 3 -> assertTrue(lake(x) && y == 61, "a water plant off the seabed: " + p);
                case 2 -> assertTrue(lake(x) && y == 65, "a lily pad off the water: " + p);
                default -> throw new AssertionError(p);
            }
        }
        for (int v = 0; v < 4; v++) assertTrue(seen[v] > 5, "variant " + v + ": " + seen[v] + " in " + plan);
        FakeExecutor.Result commit = FakeExecutor.run(compile(plan), world);
        assertEquals(plan.totalCells(), commit.written());
        for (int x = 6; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 61; y <= 64; y++) {
                    int state = world.get(x, y, z);
                    assertTrue(state == seagrass || state == kelp || state == kelpPlant || state == water
                            || state == flowing || state == lava, "under water at " + x + "," + y + "," + z + ": "
                            + f.states.format(state));
                }
            }
        }
        undoExactly(commit, world, before);
    }

    /** The plan does not depend on how the survey is tiled or acceptance stepped, water plants and columns included. */
    @Test
    void waterPlansDoNotDependOnThePartition() {
        FakeWorld world = shore();
        Spec spec = all().columns(1, 6).seed(77).spacing(1).transforms(Transforms.ALL)
                .density(new ScatterSettings.Density.Fraction(0.7))
                .variants(new Variant(0, 2), new Variant(1, 1), new Variant(2, 1), new Variant(3, 3));
        int[] blocks = {poppy, seagrass, lily, kelp};
        ScatterPlan whole = plan(spec, world, blocks);
        assertTrue(whole.placements().size() > 40, whole.toString());

        ScatterPlanner tiled = planner(spec, world, blocks);
        tiled.bucketBits(2);
        for (int x0 = 12; x0 >= 0; x0 -= 4) {
            for (int z0 = 0; z0 < 16; z0 += 3) tiled.survey(x0, z0, x0 + 4, z0 + 3);
        }
        ScatterPlanner.Acceptance acceptance = tiled.acceptance();
        while (!acceptance.step(7)) {
            // small steps
        }
        ScatterPlan stepped = acceptance.plan();
        assertEquals(whole.placements(), stepped.placements());
        assertEquals(whole.hash(), stepped.hash());
        assertEquals(whole.rejectedCounts(), stepped.rejectedCounts());
    }

    /** The survival check is asked with a column's height. */
    @Test
    void theSurvivalCheckSeesAColumnsHeight() {
        FakeWorld world = shore();
        List<Integer> asked = new ArrayList<>();
        ScatterPlanner.SurvivalCheck check = new ScatterPlanner.SurvivalCheck() {
            @Override
            public Outcome check(int source, Transform transform, int x, int y, int z) {
                throw new AssertionError("asked without the height");
            }

            @Override
            public Outcome check(int source, Transform transform, int x, int y, int z, int height) {
                asked.add(height);
                return height == 2 ? Outcome.SURVIVAL : null;
            }
        };
        int[] blocks = {kelp};
        ScatterPlan plan = new ScatterPlanner(all().columns(1, 4).build(), sources(blocks), world, Long.MAX_VALUE,
                ScatterPlanner.DEFAULT_MAX_WORK, ScatterPlanner.ColumnGuard.ALLOW_ALL, null, check, blocks).finish();
        ScatterFixture.assertBalanced(plan);
        assertEquals(8 * 16, asked.size());
        assertTrue(asked.contains(2) && plan.count(Outcome.SURVIVAL) > 0, plan.toString());
        for (ScatterPlan.Placement p : plan.placements()) assertTrue(p.height() != 2, p.toString());
    }
}
