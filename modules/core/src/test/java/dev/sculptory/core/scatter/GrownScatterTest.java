package dev.sculptory.core.scatter;

import static dev.sculptory.core.scatter.ScatterFixture.assertBalanced;
import static dev.sculptory.core.scatter.ScatterFixture.context;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.MultiPaste;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.SourceBlocks;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryPrograms;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.scatter.ScatterFixture.Spec;
import dev.sculptory.core.scatter.ScatterSettings.Density;
import dev.sculptory.core.scatter.ScatterSettings.Variant;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Trees and features in scatter plans, with a fake grower: a trunk on
 * dirt (the grass under it becomes dirt), a 7 × 7 crown that grows around earlier crowns, a cleared short grass cell,
 * and a bee nest with a block entity on some. Plans, clusters, caps, and commits that write exactly what the plan
 * holds and undo exactly.
 */
class GrownScatterTest {
    private static final int Y = 64;

    private final ScatterFixture f = new ScatterFixture();
    private final int leaves = f.states.state("minecraft:oak_planks"); // a stand-in for leaves
    private final int nest = f.states.state("minecraft:hopper"); // a stand-in for a bee nest (a block entity)
    private final UUID planId = UUID.randomUUID();
    private final FeatureCatalog.FeatureDef oak = FeatureCatalog.find("minecraft:oak").orElseThrow();

    /** The fake tree, reading {@code world} under the planner's grown cells. */
    private ScatterPlanner.Grower grower(FakeWorld world) {
        return (source, x, y, z, seed, grown) -> {
            GrownFeature.Builder b = GrownFeature.builder();
            int[] reads = {0};
            Reader read = (cx, cy, cz) -> {
                reads[0]++;
                int mine = b.get(cx, cy, cz);
                if (mine >= 0) return mine;
                int earlier = grown.get(cx, cy, cz);
                if (earlier >= 0) return earlier;
                // As the server's capture does: a growth that reaches an unloaded chunk fails.
                if (!world.isLoaded(cx >> 4, cz >> 4)) throw new IllegalStateException("unloaded");
                return world.get(cx, cy, cz);
            };
            try {
                return fakeTree(b, read, reads, x, y, z, seed);
            } catch (IllegalStateException unloaded) {
                return ScatterPlanner.Growth.failed(Outcome.UNLOADED, reads[0]);
            }
        };
    }

    private ScatterPlanner.Growth fakeTree(GrownFeature.Builder b, Reader read, int[] reads, int x, int y, int z,
                                           long seed) {
        int ground = read.get(x, y - 1, z);
        if (ground != f.grass && ground != f.dirt) return ScatterPlanner.Growth.failed(Outcome.FEATURE_FAILED, 1);
        b.set(x, y - 1, z, f.dirt, null, ground);
        int height = 3 + (int) Long.remainderUnsigned(seed, 3);
        for (int k = 0; k < height; k++) b.set(x, y + k, z, f.log, null, read.get(x, y + k, z));
        for (int dy = height - 1; dy <= height; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int state = read.get(x + dx, y + dy, z + dz);
                    // Like a boulder, the crown would take a chest too (the planner refuses that).
                    if (state == f.air || state == leaves || state == f.chest) {
                        b.set(x + dx, y + dy, z + dz, leaves, null, state);
                    }
                }
            }
        }
        if (read.get(x + 1, y, z) == f.shortGrass) b.set(x + 1, y, z, f.air, null, f.shortGrass);
        if ((seed & 1) == 0 && read.get(x, y + 1, z - 1) == f.air) b.set(x, y + 1, z - 1, nest, bees(seed), f.air);
        return ScatterPlanner.Growth.grown(b.build(), reads[0] + b.size());
    }

    @FunctionalInterface
    private interface Reader {
        int get(int x, int y, int z);
    }

    private static NbtBytes bees(long seed) {
        return BlockEntityNbt.toNbtBytes("minecraft:beehive",
                NbtCompound.builder().putString("bees", "bees of " + seed).build());
    }

    private FakeWorld world() {
        FakeWorld world = f.flat(-20, -20, 60, 60, Y);
        for (int x = -20; x <= 60; x += 3) {
            for (int z = -20; z <= 60; z += 2) world.set(x, Y + 1, z, f.shortGrass);
        }
        return world;
    }

    private ScatterPlanner planner(Spec spec, List<Clipboard> sources, List<FeatureCatalog.FeatureDef> defs,
                                   FakeWorld world, long maxCells, long maxGrown, ScatterPlanner.ColumnGuard guard) {
        return new ScatterPlanner(spec.build(), sources, world, maxCells, ScatterPlanner.DEFAULT_MAX_WORK, guard, null,
                ScatterPlanner.SurvivalCheck.ALWAYS, null, new ScatterPlanner.Features(defs, grower(world), maxGrown));
    }

    /** Oak trees over a 40 × 40 box, spacing 3 (so crowns overlap). */
    private ScatterPlan trees(FakeWorld world, long seed) {
        Spec spec = Spec.box(0, 0, 39, 39).spacing(3).density(new Density.Fraction(0.2)).seed(seed);
        ScatterPlan plan = planner(spec, List.of(placeholder()), List.of(oak), world, Long.MAX_VALUE, 1 << 20,
                ScatterPlanner.ColumnGuard.ALLOW_ALL).finish();
        assertBalanced(plan);
        return plan;
    }

    private Clipboard placeholder() {
        return Clipboard.builder(f.states, new BlockPos(1, 1, 1)).build();
    }

    private EditProgram compile(ScatterPlan plan) {
        return OpCompiler.compile(new OpSpec.ScatterCommit(planId), context(f.states, planId, plan.toMultiPaste(),
                Long.MAX_VALUE));
    }

    @Test
    void grownPlacementsFormClustersThatHoldEveryCellOnce() {
        ScatterPlan plan = trees(world(), 7L);
        assertTrue(plan.placements().size() > 20, plan.toString());
        assertFalse(plan.clusters().isEmpty());
        assertTrue(plan.clusters().size() < plan.placements().size(), "crowns 7 wide at spacing 3 overlap");
        long cells = 0;
        Map<Long, Integer> owner = new HashMap<>();
        for (int c = 0; c < plan.clusters().size(); c++) {
            GrownFeature cluster = plan.clusters().get(c);
            cells += cluster.size();
            for (int i = 0; i < cluster.size(); i++) {
                assertNull(owner.put(cluster.cell(i), c), "a cell in two clusters");
            }
        }
        assertEquals(cells, plan.grownCells());
        assertEquals(cells, plan.totalCells());
        for (int p = 0; p < plan.placements().size(); p++) {
            ScatterPlan.Placement placement = plan.placements().get(p);
            assertEquals(Transform.IDENTITY, placement.transform(), "grown placements are never turned");
            int c = plan.clusterOf(p);
            assertTrue(c >= 0);
            BlockPos a = placement.anchor();
            assertEquals(c, owner.get(GrownFeature.pack(a.x(), a.y(), a.z())), "a trunk outside its cluster");
            assertTrue(plan.bounds().orElseThrow().contains(a.x(), a.y() - 1, a.z()));
        }
        // Each cell keeps what the world held, even where a later crown grew over an earlier one.
        FakeWorld world = world();
        for (GrownFeature cluster : plan.clusters()) {
            for (int i = 0; i < cluster.size(); i++) {
                assertEquals(world.get(cluster.x(i), cluster.y(i), cluster.z(i)), cluster.before(i));
            }
        }
    }

    @Test
    void theSameSeedGrowsTheSamePlanAndReRollingGrowsAnother() {
        ScatterPlan a = trees(world(), 7L);
        ScatterPlan b = trees(world(), 7L);
        assertEquals(a.hash(), b.hash());
        assertEquals(a, b);
        ScatterPlan c = trees(world(), 8L);
        assertNotEquals(a.hash(), c.hash());
    }

    @Test
    void theCommitWritesExactlyTheGrownCellsAndUndoesExactly() {
        FakeWorld world = world();
        ScatterPlan plan = trees(world, 11L);
        Box region = grow(plan.bounds().orElseThrow(), 2);
        BlockBuffer original = snapshot(world, region);
        int tiles = 0;
        for (GrownFeature cluster : plan.clusters()) tiles += cluster.tileCount();
        assertTrue(tiles > 0, "some trees got a bee nest");

        FakeExecutor.Result result = FakeExecutor.run(compile(plan), world);
        assertEquals(0, result.conflicts());
        assertEquals(plan.grownCells(), result.written());
        // Preview equals commit: every grown cell holds its state and block entity, nothing else changed.
        BlockBuffer expected = snapshot(world(), region);
        for (GrownFeature cluster : plan.clusters()) {
            for (int i = 0; i < cluster.size(); i++) {
                expected.set(cluster.x(i), cluster.y(i), cluster.z(i), cluster.after(i));
                expected.setTile(cluster.x(i), cluster.y(i), cluster.z(i), cluster.tile(i));
            }
        }
        assertSame(expected, snapshot(world, region), region);
        boolean cleared = false;
        for (GrownFeature cluster : plan.clusters()) {
            for (int i = 0; i < cluster.size(); i++) cleared |= cluster.after(i) == f.air;
        }
        assertTrue(cleared, "a short grass cell was cleared (an air cell written)");

        HistoryEntry entry = new HistoryEntry(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", "Scatter",
                result.record(), 0L);
        FakeExecutor.Result undo = FakeExecutor.run(HistoryPrograms.undo(entry, ConflictPolicy.SKIP_CONFLICTS), world);
        assertEquals(0, undo.conflicts());
        assertSame(original, snapshot(world, region), region);
    }

    @Test
    void aClusterBuiltOnSinceThePreviewIsSkippedWhole() {
        FakeWorld world = world();
        ScatterPlan plan = trees(world, 11L);
        assertTrue(plan.clusters().size() >= 2);
        GrownFeature changed = plan.clusters().get(0);
        // A block placed where the first cluster grows a leaf, and one where the second turns grass into dirt.
        int leaf = -1;
        for (int i = 0; i < changed.size() && leaf < 0; i++) if (changed.after(i) == leaves) leaf = i;
        world.set(changed.x(leaf), changed.y(leaf), changed.z(leaf), f.stone);
        GrownFeature other = plan.clusters().get(1);
        int soil = -1;
        for (int i = 0; i < other.size() && soil < 0; i++) if (other.after(i) == f.dirt) soil = i;
        world.set(other.x(soil), other.y(soil), other.z(soil), f.sand);

        Map<Long, Integer> built = new HashMap<>();
        for (GrownFeature cluster : List.of(changed, other)) {
            for (int i = 0; i < cluster.size(); i++) {
                built.put(cluster.cell(i), world.get(cluster.x(i), cluster.y(i), cluster.z(i)));
            }
        }
        FakeExecutor.Result result = FakeExecutor.run(compile(plan), world);
        assertEquals(2, result.conflicts(), "two clusters skipped");
        for (GrownFeature cluster : List.of(changed, other)) {
            for (int i = 0; i < cluster.size(); i++) {
                assertEquals(built.get(cluster.cell(i)), world.get(cluster.x(i), cluster.y(i), cluster.z(i)),
                        "a skipped cluster wrote a cell");
            }
        }
        long written = 0;
        for (GrownFeature cluster : plan.clusters()) {
            if (cluster != changed && cluster != other) written += cluster.size();
        }
        assertEquals(written, result.written());
    }

    @Test
    void failedGrowthsAreCountedNotErrors() {
        FakeWorld world = world();
        // Sand on the east half: the fake tree only grows on grass or dirt.
        for (int x = 20; x <= 39; x++) {
            for (int z = 0; z <= 39; z++) world.set(x, Y, z, f.sand);
        }
        ScatterPlan plan = trees(world, 3L);
        assertTrue(plan.count(Outcome.FEATURE_FAILED) > 10, plan.toString());
        for (ScatterPlan.Placement p : plan.placements()) assertTrue(p.anchor().x() < 20, "a tree on sand");
    }

    @Test
    void protectedAndUnloadedColumnsSkipAGrowthWhole() {
        FakeWorld world = world();
        world.setLoaded(2, 0, false); // x 32-47
        Spec spec = Spec.box(0, 0, 31, 15).spacing(3).density(new Density.Fraction(0.3)).seed(5L);
        ScatterPlan plan = planner(spec, List.of(placeholder()), List.of(oak), world, Long.MAX_VALUE, 1 << 20,
                (x, z) -> z < 12).finish();
        assertBalanced(plan);
        assertTrue(plan.count(Outcome.PROTECTED) > 0, plan.toString());
        assertTrue(plan.count(Outcome.UNLOADED) > 0, plan.toString());
        for (GrownFeature cluster : plan.clusters()) {
            for (int i = 0; i < cluster.size(); i++) {
                assertTrue(cluster.z(i) < 12, "a cell in a protected column");
                assertTrue(cluster.x(i) < 32, "a cell in an unloaded chunk");
            }
        }
    }

    /**
     * The global mask (a cell guard) skips a placement or a tree whole, as a protected column does: the plan holds
     * none in part and counts them MASKED. A plan made without the mask and committed under it leaves the same kind of
     * placement out whole ({@code denied}, {@code toMultiPaste(skip)}), and a plan made under the mask loses nothing
     * at the commit: the preview is the commit.
     */
    @Test
    void theMaskSkipsAPlacementOrATreeWhole() {
        Spec spec = Spec.box(0, 0, 29, 29).spacing(2).density(new Density.Fraction(0.3)).seed(9L)
                .variants(new Variant(0, 1), new Variant(1, 1));
        List<FeatureCatalog.FeatureDef> defs = Arrays.asList(oak, null);
        List<Clipboard> sources = List.of(placeholder(), f.slab(3, 3, f.stone));
        ScatterPlanner.CellGuard guard = (x, y, z) -> z != 7 && z != 19;
        FakeWorld world = world();
        ScatterPlan masked = planner(spec, sources, defs, world, Long.MAX_VALUE, 1 << 20,
                ScatterPlanner.ColumnGuard.ALLOW_ALL).cellGuard(guard).finish();
        assertBalanced(masked);
        assertTrue(masked.count(Outcome.MASKED) > 0, masked.toString());
        for (GrownFeature cluster : masked.clusters()) {
            for (int i = 0; i < cluster.size(); i++) {
                assertTrue(guard.allows(cluster.x(i), cluster.y(i), cluster.z(i)), "a tree in part");
            }
        }
        int slabs = 0;
        for (int p = 0; p < masked.placements().size(); p++) {
            if (masked.clusterOf(p) >= 0) continue;
            slabs++;
            BlockPos a = masked.placements().get(p).anchor();
            for (int dz = -1; dz <= 1; dz++) assertTrue(guard.allows(a.x(), a.y(), a.z() + dz), "a slab in part");
        }
        assertTrue(slabs > 0 && masked.clusters().size() > 0, masked.toString());
        assertTrue(masked.denied(guard, f.states).isEmpty(), "the commit would leave out what the preview showed");
        FakeExecutor.Result kept = FakeExecutor.run(compile(masked), world);
        assertEquals(0, kept.conflicts());
        assertEquals(masked.totalCells(), kept.written());

        // Planned without the mask, committed under it.
        FakeWorld other = world();
        ScatterPlan open = planner(spec, sources, defs, other, Long.MAX_VALUE, 1 << 20,
                ScatterPlanner.ColumnGuard.ALLOW_ALL).finish();
        BitSet denied = open.denied(guard, f.states);
        assertTrue(denied.cardinality() > 0 && denied.cardinality() < open.placements().size(), denied.toString());
        long expected = 0;
        Set<Integer> deniedClusters = new HashSet<>();
        for (int p = denied.nextSetBit(0); p >= 0; p = denied.nextSetBit(p + 1)) {
            if (open.clusterOf(p) >= 0) deniedClusters.add(open.clusterOf(p));
        }
        for (int p = 0; p < open.placements().size(); p++) {
            // A cluster is denied for all of its trees together.
            if (open.clusterOf(p) >= 0) {
                assertEquals(deniedClusters.contains(open.clusterOf(p)), denied.get(p), "placement " + p);
            } else if (!denied.get(p)) {
                expected += 9;
            }
        }
        for (int c = 0; c < open.clusters().size(); c++) {
            if (!deniedClusters.contains(c)) expected += open.clusters().get(c).size();
        }
        BlockBuffer before = snapshot(other, grow(open.bounds().orElseThrow(), 2));
        FakeExecutor.Result result = FakeExecutor.run(OpCompiler.compile(new OpSpec.ScatterCommit(planId),
                context(f.states, planId, open.toMultiPaste(denied), Long.MAX_VALUE)), other);
        assertEquals(0, result.conflicts());
        assertEquals(expected, result.written());
        Box region = grow(open.bounds().orElseThrow(), 2);
        for (int x = region.min().x(); x <= region.max().x(); x++) {
            for (int y = region.min().y(); y <= region.max().y(); y++) {
                for (int z = region.min().z(); z <= region.max().z(); z++) {
                    if (!guard.allows(x, y, z)) {
                        assertEquals(before.get(x, y, z), other.get(x, y, z), "a masked cell changed at " + x + ", " + y
                                + ", " + z);
                    }
                }
            }
        }
    }

    @Test
    void theGrownCellCapRefusesThePlanAndTheCellBudgetSkipsTrees() {
        Spec spec = Spec.box(0, 0, 39, 39).spacing(3).density(new Density.Fraction(0.2)).seed(7L);
        ScatterPlanner capped = planner(spec, List.of(placeholder()), List.of(oak), world(), Long.MAX_VALUE, 200,
                ScatterPlanner.ColumnGuard.ALLOW_ALL);
        ScatterPlanner.GrownCellsExceeded refused = assertThrows(ScatterPlanner.GrownCellsExceeded.class, capped::finish);
        assertEquals(200, refused.cap());

        ScatterPlan budget = planner(spec, List.of(placeholder()), List.of(oak), world(), 300, 1 << 20,
                ScatterPlanner.ColumnGuard.ALLOW_ALL).finish();
        assertTrue(budget.count(Outcome.BUDGET) > 0);
        assertTrue(budget.totalCells() <= 300);
        assertFalse(budget.placements().isEmpty());
    }

    @Test
    void clustersStayWithinTheirCapAndGrowthsNeverTakeABlockEntity() {
        Spec spec = Spec.box(0, 0, 39, 39).spacing(3).density(new Density.Fraction(0.2)).seed(7L);
        FakeWorld world = world();
        ScatterPlan capped = new ScatterPlanner(spec.build(), List.of(placeholder()), world, Long.MAX_VALUE,
                ScatterPlanner.DEFAULT_MAX_WORK, ScatterPlanner.ColumnGuard.ALLOW_ALL, null,
                ScatterPlanner.SurvivalCheck.ALWAYS, null,
                new ScatterPlanner.Features(List.of(oak), grower(world), 1 << 20, 300)).finish();
        assertBalanced(capped);
        ScatterPlan free = trees(world(), 7L);
        assertTrue(capped.count(Outcome.COLLISION) > free.count(Outcome.COLLISION), "the cap refused joining trees");
        for (GrownFeature cluster : capped.clusters()) assertTrue(cluster.size() <= 300, cluster.toString());

        // Chests at y 67, in the crowns of the shortest trees: no growth writes over one.
        FakeWorld chests = world();
        for (int x = 0; x <= 39; x += 2) {
            for (int z = 1; z <= 39; z += 2) chests.set(x, Y + 3, z, f.chest);
        }
        ScatterPlan plan = trees(chests, 7L);
        for (GrownFeature cluster : plan.clusters()) {
            for (int i = 0; i < cluster.size(); i++) assertNotEquals(f.chest, cluster.before(i));
        }
        assertTrue(plan.count(Outcome.COLLISION) > 0);
    }

    @Test
    void heldPlacementsAndGrowthsNeverShareACell() {
        FakeWorld world = world();
        Spec spec = Spec.box(0, 0, 29, 29).spacing(2).density(new Density.Fraction(0.3)).seed(9L)
                .variants(new Variant(0, 1), new Variant(1, 1));
        List<FeatureCatalog.FeatureDef> defs = Arrays.asList(oak, null);
        ScatterPlan plan = planner(spec, List.of(placeholder(), f.slab(3, 3, f.stone)), defs, world, Long.MAX_VALUE,
                1 << 20, ScatterPlanner.ColumnGuard.ALLOW_ALL).finish();
        assertBalanced(plan);
        assertTrue(plan.count(Outcome.COLLISION) > 0, plan.toString());
        Map<Long, Integer> grown = new HashMap<>();
        for (GrownFeature cluster : plan.clusters()) {
            for (int i = 0; i < cluster.size(); i++) grown.put(cluster.cell(i), i);
        }
        int slabs = 0;
        for (int p = 0; p < plan.placements().size(); p++) {
            if (plan.clusterOf(p) >= 0) continue;
            slabs++;
            BlockPos a = plan.placements().get(p).anchor();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    assertFalse(grown.containsKey(GrownFeature.pack(a.x() + dx, a.y(), a.z() + dz)), "shared cell");
                }
            }
        }
        assertTrue(slabs > 0 && slabs < plan.placements().size(), plan.toString());
        // Both commit in one program.
        FakeExecutor.Result result = FakeExecutor.run(compile(plan), world);
        assertEquals(0, result.conflicts());
        assertEquals(plan.totalCells(), result.written());
    }

    @Test
    void anExpectedSourceMustBeUntransformedAndCarryItsExpectedCells() {
        BlockBuffer cells = new BlockBuffer();
        cells.set(0, 0, 0, f.log);
        SourceBlocks source = new SourceBlocks(cells, new BlockPos(1, 1, 1), BlockPos.ORIGIN);
        MultiPaste.Placement turned = new MultiPaste.Placement(BlockPos.ORIGIN, 0, Transform.rotation(1));
        MultiPaste.Placement plain = new MultiPaste.Placement(BlockPos.ORIGIN, 0, Transform.IDENTITY);
        assertThrows(IllegalArgumentException.class, () -> new MultiPaste(List.of(source), List.of(turned),
                MultiPaste.Replace.EXPECTED, List.of(), Map.of(0, source)));
        assertThrows(IllegalArgumentException.class, () -> new MultiPaste(List.of(source), List.of(plain),
                MultiPaste.Replace.EXPECTED, List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new MultiPaste(List.of(source), List.of(plain),
                MultiPaste.Replace.OPEN, List.of(), Map.of(0, source)));
        new MultiPaste(List.of(source), List.of(plain), MultiPaste.Replace.EXPECTED, List.of(), Map.of(0, source));
    }

    @Test
    void grownFeaturesPackCellsAndDropWritesThatChangeNothing() {
        for (int[] c : new int[][] {{0, 0, 0}, {-1, -64, -1}, {33_554_431, 2047, -33_554_432}, {-33_554_432, -2048, 7}}) {
            long packed = GrownFeature.pack(c[0], c[1], c[2]);
            assertEquals(c[0], GrownFeature.unpackX(packed));
            assertEquals(c[1], GrownFeature.unpackY(packed));
            assertEquals(c[2], GrownFeature.unpackZ(packed));
        }
        assertThrows(IllegalArgumentException.class, () -> GrownFeature.pack(1 << 25, 0, 0));
        GrownFeature.Builder b = GrownFeature.builder();
        b.set(1, 2, 3, f.log, null, f.air).set(1, 2, 3, f.stone, null, f.dirt); // keeps the first before
        b.set(4, 5, 6, f.dirt, null, f.dirt); // changes nothing
        b.set(7, 8, 9, f.log, null, f.grass).set(7, 8, 9, f.grass, null, f.log); // back as it was
        GrownFeature grown = b.build();
        assertEquals(1, grown.size());
        assertEquals(f.stone, grown.after(0));
        assertEquals(f.air, grown.before(0));
        assertEquals(new Box(new BlockPos(1, 2, 3), new BlockPos(1, 2, 3)), grown.bounds());
        assertNull(GrownFeature.builder().set(0, 0, 0, f.dirt, null, f.dirt).build());
    }

    // ---------------------------------------------------------------- helpers

    private static BlockBuffer snapshot(FakeWorld world, Box box) {
        BlockBuffer cells = new BlockBuffer();
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    cells.set(x, y, z, world.get(x, y, z));
                    BlockEntityData tile = world.tile(x, y, z);
                    if (tile != null) cells.setTile(x, y, z, tile);
                }
            }
        }
        return cells;
    }

    private static void assertSame(BlockBuffer expected, BlockBuffer actual, Box box) {
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    String at = x + ", " + y + ", " + z;
                    assertEquals(expected.get(x, y, z), actual.get(x, y, z), at);
                    BlockEntityData want = expected.tile(x, y, z), got = actual.tile(x, y, z);
                    assertTrue(want == null ? got == null : want.sameContent(got), "tile at " + at);
                }
            }
        }
    }

    private static Box grow(Box box, int margin) {
        return new Box(box.min().offset(-margin, -margin, -margin), box.max().offset(margin, margin, margin));
    }
}
