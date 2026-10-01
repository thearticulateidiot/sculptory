package dev.sculptory.core.scatter;

import static dev.sculptory.core.scatter.ScatterFixture.assertBalanced;
import static dev.sculptory.core.scatter.ScatterFixture.plan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.scatter.ScatterArea.Stamp;
import dev.sculptory.core.scatter.ScatterFixture.Spec;
import dev.sculptory.core.scatter.ScatterSettings.Density;
import dev.sculptory.core.scatter.ScatterSettings.Filters;
import dev.sculptory.core.scatter.ScatterSettings.Fit;
import dev.sculptory.core.scatter.ScatterSettings.Transforms;
import dev.sculptory.core.scatter.ScatterSettings.Variant;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScatterPlannerTest {
    private final ScatterFixture f = new ScatterFixture();

    // ------------------------------------------------------------ determinism and partitioning

    /** Hilly ground with a slope filter (neighbour scans), spacing, several variants and every transform. */
    private Spec busySpec() {
        return Spec.box(-40, -30, 70, 60)
                .density(new Density.Fraction(0.6))
                .spacing(3)
                .filters(Filters.NONE.withSlope(0, 2).withElevation(55, 69))
                .variants(new Variant(0, 5), new Variant(1, 3, 0b0101), new Variant(2, 2))
                .transforms(Transforms.ALL)
                .seed(0xC0FFEE);
    }

    private List<Clipboard> busySources() {
        return List.of(f.tree(), f.slab(3, 1, f.stone), f.single(f.dirt));
    }

    private FakeWorld hills() {
        return f.terrain(-50, -40, 80, 70, ScatterFixture::hills, f.grass);
    }

    @Test
    void sameInputsGiveTheSamePlan() {
        FakeWorld world = hills();
        ScatterPlan a = plan(busySpec(), busySources(), world);
        ScatterPlan b = plan(busySpec(), busySources(), hills());
        assertTrue(a.placements().size() > 50, a.toString());
        assertEquals(a.hash(), b.hash());
        assertEquals(a.placements(), b.placements());
        assertEquals(a.rejectedCounts(), b.rejectedCounts());
        assertEquals(a, b);
        assertNotEquals(a.hash(), plan(busySpec().seed(0xC0FFEF), busySources(), world).hash());
    }

    @Test
    void anyPartitionOfTheSurveyGivesTheSamePlan() {
        FakeWorld world = hills();
        ScatterPlan whole = plan(busySpec(), busySources(), world);

        // Chunk tiles, surveyed in reverse order.
        ScatterPlanner chunks = new ScatterPlanner(busySpec().build(), busySources(), world, Long.MAX_VALUE);
        Box area = chunks.areaBounds();
        for (int cx = area.max().x() >> 4; cx >= area.min().x() >> 4; cx--) {
            for (int cz = area.max().z() >> 4; cz >= area.min().z() >> 4; cz--) {
                chunks.survey(cx << 4, cz << 4, (cx << 4) + 15, (cz << 4) + 15);
            }
        }
        assertSamePlan(whole, chunks.finish());

        // Irregular, overlapping tiles (overlaps are ignored), then the rest by finish().
        ScatterPlanner odd = new ScatterPlanner(busySpec().build(), busySources(), world, Long.MAX_VALUE);
        for (int x = -45; x < 30; x += 7) {
            for (int z = -35; z < 64; z += 5) odd.survey(x, z, x + 9, z + 6);
        }
        assertSamePlan(whole, odd.finish());

        // Single columns.
        ScatterPlanner columns = new ScatterPlanner(busySpec().build(), busySources(), world, Long.MAX_VALUE);
        for (int z = 60; z >= -30; z--) {
            for (int x = -40; x <= 70; x++) columns.survey(x, z, x, z);
        }
        assertSamePlan(whole, columns.finish());
    }

    private static void assertSamePlan(ScatterPlan expected, ScatterPlan actual) {
        assertBalanced(actual);
        assertEquals(expected.placements(), actual.placements());
        assertEquals(expected.rejectedCounts(), actual.rejectedCounts());
        assertEquals(expected.hash(), actual.hash());
    }

    @Test
    void heightHintsOnlySaveReads() {
        FakeWorld hinted = hills();
        FakeWorld plain = hills();
        plain.setHeightHints(false);
        assertEquals(plan(busySpec(), busySources(), hinted).hash(), plan(busySpec(), busySources(), plain).hash());
    }

    // ------------------------------------------------------------ work budget and stepping

    /**
     * A worst case: a solid 40 × 30 × 40 asset (48,000 cells) on every column of a 128 × 128 area under a
     * stone ceiling 20 blocks up. Every candidate passes the occupancy and support checks and collides late, so
     * without a work budget acceptance would walk ~80k cells for each of 16,384 candidates.
     */
    @Test
    void aPathologicalRequestIsRefusedQuicklyWithWorkLimit() {
        FakeWorld world = f.flat(-24, -24, 151, 151, 64);
        world.fill(Box.of(new BlockPos(-24, 85, -24), new BlockPos(151, 85, 151)), f.stone);
        Clipboard.Builder solid = Clipboard.builder(f.states, new BlockPos(40, 30, 40)).anchor(new BlockPos(20, 0, 20));
        for (int x = 0; x < 40; x++) {
            for (int y = 0; y < 30; y++) {
                for (int z = 0; z < 40; z++) solid.set(x, y, z, f.stone);
            }
        }
        List<Clipboard> sources = List.of(solid.build());
        // The window stops below the ceiling, so the surface is the ground at 64.
        Spec spec = new Spec(new ScatterArea.Region(Box.of(new BlockPos(0, 60, 0), new BlockPos(127, 80, 127))));

        long start = System.nanoTime();
        ScatterPlanner planner = new ScatterPlanner(spec.build(), sources, world, Long.MAX_VALUE);
        ScatterPlanner.Acceptance acceptance = planner.acceptance();
        assertEquals(16_384, acceptance.remaining());
        while (!acceptance.step(1 << 20)) {
            assertTrue(acceptance.work() < ScatterPlanner.DEFAULT_MAX_WORK + 200_000, "stops at the budget");
        }
        ScatterPlan plan = acceptance.plan();
        long elapsed = System.nanoTime() - start;
        assertBalanced(plan);
        assertEquals(0, plan.placements().size());
        long considered = plan.count(Outcome.COLLISION);
        assertTrue(considered > 100 && considered < 1000, plan.toString());
        assertEquals(16_384 - considered, plan.count(Outcome.WORK_LIMIT));
        assertTrue(acceptance.work() >= ScatterPlanner.DEFAULT_MAX_WORK);
        assertTrue(elapsed < 10_000_000_000L, "took " + elapsed / 1_000_000 + " ms");
        System.out.printf("Pathological scatter refused: %d candidates tried, %d work units, %.1f ms%n", considered,
                acceptance.work(), elapsed / 1e6);

        // A smaller budget stops sooner; the same budget stops at the same candidate.
        ScatterPlan small = ScatterPlanner.plan(spec.build(), sources, world, Long.MAX_VALUE, 1_000_000);
        assertTrue(small.count(Outcome.COLLISION) < 20, small.toString());
        assertEquals(small.hash(), ScatterPlanner.plan(spec.build(), sources, world, Long.MAX_VALUE, 1_000_000).hash());
        assertEquals(16_384, ScatterPlanner.plan(spec.build(), sources, world, Long.MAX_VALUE, 0).count(Outcome.WORK_LIMIT));
    }

    @Test
    void steppingInAnyChunkSizeGivesTheSamePlan() {
        FakeWorld world = hills();
        ScatterPlanner reference = new ScatterPlanner(busySpec().build(), busySources(), world, Long.MAX_VALUE);
        ScatterPlanner.Acceptance whole = reference.acceptance();
        assertTrue(whole.step(Long.MAX_VALUE), "one unbounded step finishes");
        ScatterPlan expected = whole.plan();
        assertEquals(plan(busySpec(), busySources(), world).hash(), expected.hash());
        long fullWork = whole.work();

        for (long size : new long[] {1, 7, 100, 4_096, fullWork / 3, fullWork}) {
            ScatterPlanner planner = new ScatterPlanner(busySpec().build(), busySources(), world, Long.MAX_VALUE);
            ScatterPlanner.Acceptance acceptance = planner.acceptance();
            int steps = 0;
            while (!acceptance.step(size)) steps++;
            assertEquals(expected.hash(), acceptance.plan().hash(), "step size " + size);
            assertEquals(fullWork, acceptance.work(), "step size " + size);
            assertTrue(size > 100 || steps > 100, "many steps for size " + size);
            assertThrows(IllegalStateException.class, planner::acceptance);
        }

        // With a work budget that cuts acceptance short, too.
        long budget = fullWork / 2;
        ScatterPlan cut = ScatterPlanner.plan(busySpec().build(), busySources(), world, Long.MAX_VALUE, budget);
        assertTrue(cut.count(Outcome.WORK_LIMIT) > 0 && cut.placements().size() < expected.placements().size());
        assertEquals(expected.placements().subList(0, cut.placements().size()), cut.placements(), "a prefix");
        for (long size : new long[] {1, 13, 5_000}) {
            ScatterPlanner.Acceptance acceptance =
                    new ScatterPlanner(busySpec().build(), busySources(), world, Long.MAX_VALUE, budget).acceptance();
            while (!acceptance.step(size)) {
                // Keep stepping.
            }
            assertEquals(cut.hash(), acceptance.plan().hash(), "cut, step size " + size);
        }
        ScatterPlanner.Acceptance unfinished = new ScatterPlanner(busySpec().build(), busySources(), world, Long.MAX_VALUE)
                .acceptance();
        assertThrows(IllegalStateException.class, unfinished::plan);
        assertThrows(IllegalArgumentException.class, () -> unfinished.step(0));
    }

    /** The anchor's own chunk is checked before its column is read, even when the footprint lies elsewhere. */
    @Test
    void anAnchorFarFromItsCellsChecksTheAnchorChunk() {
        FakeWorld world = f.flat(0, 0, 63, 15, 64);
        // One block 20 east of its anchor.
        Clipboard offset = Clipboard.builder(f.states, new BlockPos(1, 1, 1)).anchor(new BlockPos(-20, 0, 0))
                .set(0, 0, 0, f.dirt).build();
        Spec spec = Spec.stamps(Stamp.paint(8, 8, 0));
        ScatterPlan loaded = plan(spec, List.of(offset), world);
        assertEquals(List.of(new BlockPos(8, 65, 8)), loaded.placements().stream().map(ScatterPlan.Placement::anchor).toList());

        // The anchor's chunk unloads between the survey and acceptance (the footprint's chunk stays loaded).
        ScatterPlanner planner = new ScatterPlanner(spec.build(), List.of(offset), world, Long.MAX_VALUE);
        planner.survey(0, 0, 63, 15);
        assertEquals(1, planner.candidateCount());
        world.setLoaded(0, 0, false);
        ScatterPlan unloaded = planner.finish();
        assertBalanced(unloaded);
        assertEquals(0, unloaded.placements().size());
        assertEquals(1, unloaded.count(Outcome.UNLOADED));
    }

    @Test
    void aPlannerIsSingleUse() {
        ScatterPlanner planner = new ScatterPlanner(busySpec().build(), busySources(), hills(), Long.MAX_VALUE);
        planner.finish();
        assertThrows(IllegalStateException.class, planner::finish);
        assertThrows(IllegalStateException.class, () -> planner.survey(0, 0, 1, 1));
    }

    // ------------------------------------------------------------ spacing

    @Test
    void noTwoAnchorsAreCloserThanTheSpacing() {
        FakeWorld world = f.terrain(0, 0, 95, 95, ScatterFixture::hills, f.grass);
        for (int spacing : new int[] {1, 2, 5, 8, 13}) {
            ScatterPlan plan = plan(Spec.box(0, 0, 95, 95).spacing(spacing).seed(spacing), List.of(f.single(f.dirt)), world);
            List<ScatterPlan.Placement> placements = plan.placements();
            assertTrue(placements.size() > 20, plan.toString());
            long closest = Long.MAX_VALUE;
            for (int i = 0; i < placements.size(); i++) {
                for (int j = i + 1; j < placements.size(); j++) {
                    long dx = placements.get(i).anchor().x() - placements.get(j).anchor().x();
                    long dz = placements.get(i).anchor().z() - placements.get(j).anchor().z();
                    closest = Math.min(closest, dx * dx + dz * dz);
                }
            }
            assertTrue(closest >= (long) spacing * spacing, "spacing " + spacing + ": closest² " + closest);
            // The test is tight: some pair is within one block of the minimum.
            assertTrue(closest < (long) (spacing + 1) * (spacing + 1), "spacing " + spacing + ": closest² " + closest);
            if (spacing > 1) assertTrue(plan.count(Outcome.SPACING) > 0);
        }
    }

    @Test
    void zeroSpacingAllowsNeighbours() {
        FakeWorld world = f.flat(0, 0, 9, 9, 64);
        ScatterPlan plan = plan(Spec.box(0, 0, 9, 9), List.of(f.single(f.dirt)), world);
        assertEquals(100, plan.placements().size());
        assertEquals(100, plan.totalCells());
    }

    // ------------------------------------------------------------ filters

    @Test
    void elevationFilterUsesTheSurfaceY() {
        FakeWorld world = f.terrain(0, 0, 31, 15, (x, z) -> x < 16 ? 64 : 70, f.grass);
        ScatterPlan plan = plan(Spec.box(0, 0, 31, 15).filters(Filters.NONE.withElevation(60, 64)),
                List.of(f.single(f.dirt)), world);
        assertEquals(256, plan.placements().size());
        assertEquals(256, plan.count(Outcome.ELEVATION));
        for (ScatterPlan.Placement p : plan.placements()) {
            assertTrue(p.anchor().x() < 16);
            assertEquals(65, p.anchor().y(), "the anchor is the cell above the surface");
        }
    }

    @Test
    void slopeFilterUsesTheSteepestCardinalStep() {
        FakeWorld world = f.terrain(0, 0, 31, 15, (x, z) -> x < 16 ? 64 : 67, f.grass);
        ScatterPlan plan = plan(Spec.box(0, 0, 31, 15).filters(Filters.NONE.withSlope(0, 2)),
                List.of(f.single(f.dirt)), world);
        assertEquals(32, plan.count(Outcome.SLOPE), "the two columns on each side of the 3-block step");
        for (ScatterPlan.Placement p : plan.placements()) assertTrue(p.anchor().x() != 15 && p.anchor().x() != 16);
        // Only steep columns.
        ScatterPlan steep = plan(Spec.box(0, 0, 31, 15).filters(Filters.NONE.withSlope(3, 3)),
                List.of(f.single(f.dirt)), world);
        assertEquals(32, steep.placements().size());
    }

    @Test
    void substrateMaskChoosesTheHostBlocks() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        f.paint(world, 8, 0, 15, 15, (x, z) -> 64, f.sand);
        Filters grassOnly = Filters.NONE.withSubstrate(new CellMask.Blocks(List.of(new NamespacedId("minecraft:grass_block"))));
        ScatterPlan plan = plan(Spec.box(0, 0, 15, 15).filters(grassOnly), List.of(f.single(f.dirt)), world);
        assertEquals(128, plan.placements().size());
        assertEquals(128, plan.count(Outcome.SUBSTRATE));
        for (ScatterPlan.Placement p : plan.placements()) assertTrue(p.anchor().x() < 8);
    }

    @Test
    void extraSurfaceMaskIsReportedAsFilter() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        f.paint(world, 8, 0, 15, 15, (x, z) -> 64, f.sand);
        SurfaceMask noSand = new SurfaceMask.Not(new SurfaceMask.SurfaceBlocks(new CellMask.Tag(new NamespacedId("minecraft:sand"))));
        ScatterPlan plan = plan(Spec.box(0, 0, 15, 15).filters(Filters.of(noSand)), List.of(f.single(f.dirt)), world);
        assertEquals(128, plan.placements().size());
        assertEquals(128, plan.count(Outcome.FILTER));
    }

    @Test
    void structuresAndMissingGroundHaveNoSurface() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        world.set(3, 65, 3, f.states.state("minecraft:oak_stairs"));
        world.set(4, 65, 4, f.chest);
        for (int y = -64; y <= 64; y++) world.set(5, y, 5, f.air);
        ScatterPlan plan = plan(Spec.box(0, 0, 15, 15), List.of(f.single(f.dirt)), world);
        assertEquals(3, plan.count(Outcome.NO_SURFACE));
        assertEquals(253, plan.placements().size());
    }

    @Test
    void aBoxAreaIsAlsoTheScanWindow() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        world.fill(Box.of(new BlockPos(0, 0, 0), new BlockPos(15, 61, 15)), f.stone);
        Spec below = new Spec(new ScatterArea.Region(Box.of(new BlockPos(0, 0, 0), new BlockPos(15, 60, 15))));
        ScatterPlan none = plan(below, List.of(f.single(f.dirt)), world);
        assertEquals(256, none.count(Outcome.NO_SURFACE), "the window's top cell is underground");
        Spec around = new Spec(new ScatterArea.Region(Box.of(new BlockPos(0, 60, 0), new BlockPos(15, 70, 15))));
        assertEquals(256, plan(around, List.of(f.single(f.dirt)), world).placements().size());
        Spec outside = new Spec(new ScatterArea.Region(Box.of(new BlockPos(0, 400, 0), new BlockPos(15, 410, 15))));
        assertThrows(IllegalArgumentException.class, () -> plan(outside, List.of(f.single(f.dirt)), world));
    }

    // ------------------------------------------------------------ density and counts

    @Test
    void densityFractionPrefiltersByRank() {
        FakeWorld world = f.flat(0, 0, 63, 63, 64);
        ScatterPlan none = plan(Spec.box(0, 0, 63, 63).density(new Density.Fraction(0)), List.of(f.single(f.dirt)), world);
        assertEquals(4096, none.count(Outcome.DENSITY));
        assertEquals(0, none.candidates());

        ScatterPlan quarter = plan(Spec.box(0, 0, 63, 63).density(new Density.Fraction(0.25)), List.of(f.single(f.dirt)), world);
        // Binomial(4096, 0.25): mean 1024, sd 27.7; 5 sd either way.
        assertTrue(quarter.candidates() > 885 && quarter.candidates() < 1163, quarter.toString());
        assertEquals(4096 - quarter.candidates(), quarter.count(Outcome.DENSITY));

        // A higher density keeps a superset of the columns (the same ranks, a higher threshold).
        ScatterPlan half = plan(Spec.box(0, 0, 63, 63).density(new Density.Fraction(0.5)), List.of(f.single(f.dirt)), world);
        Set<BlockPos> halfAnchors = new HashSet<>();
        for (ScatterPlan.Placement p : half.placements()) halfAnchors.add(p.anchor());
        for (ScatterPlan.Placement p : quarter.placements()) assertTrue(halfAnchors.contains(p.anchor()));
    }

    @Test
    void candidatesFollowTheDocumentedRankAndThreshold() {
        FakeWorld world = f.flat(-20, -20, 43, 43, 64);
        long seed = 0x5EED;
        ScatterPlan plan = plan(Spec.box(-20, -20, 43, 43).density(new Density.Fraction(0.3)).seed(seed),
                List.of(f.single(f.dirt)), world);
        long threshold = (long) (0.3 * 0x1p53);
        Set<BlockPos> expected = new HashSet<>();
        for (int x = -20; x <= 43; x++) {
            for (int z = -20; z <= 43; z++) {
                long rank = dev.sculptory.core.SplitMix64.mix(seed ^ (x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL));
                assertEquals(rank, ScatterPlanner.rank(seed, x, z));
                if ((rank >>> 11) < threshold) expected.add(new BlockPos(x, 65, z));
            }
        }
        Set<BlockPos> actual = new HashSet<>();
        for (ScatterPlan.Placement p : plan.placements()) actual.add(p.anchor());
        assertEquals(expected, actual);
        // Accepted in (unsigned rank, x, z) order.
        for (int i = 1; i < plan.placements().size(); i++) {
            BlockPos a = plan.placements().get(i - 1).anchor(), b = plan.placements().get(i).anchor();
            long ra = ScatterPlanner.rank(seed, a.x(), a.z()), rb = ScatterPlanner.rank(seed, b.x(), b.z());
            assertTrue(Long.compareUnsigned(ra, rb) < 0, i + ": " + a + " then " + b);
        }
    }

    /**
     * Regression: with {@code x·K1 ^ z·K2}, odd (x, z) and (-x, -z) shared a rank, so a scatter around the origin
     * was point-symmetric on a quarter of its columns (same density decision, variant and transform).
     */
    @Test
    void pointSymmetricColumnsDoNotShareRanks() {
        for (int x = -63; x <= 63; x += 2) {
            for (int z = -63; z <= 63; z += 2) {
                if (x == 0 && z == 0) continue;
                assertNotEquals(ScatterPlanner.rank(7, x, z), ScatterPlanner.rank(7, -x, -z), x + "," + z);
            }
        }
        // The shortest collision of the linear part (see ScatterPlanner.rank) is far outside the world.
        assertEquals(0L, 1346530022L * 0x9E3779B97F4A7C15L + 1795967550L * 0xC2B2AE3D27D4EB4FL);
        FakeWorld world = f.flat(-16, -16, 15, 15, 64);
        ScatterPlan plan = plan(Spec.box(-16, -16, 15, 15).density(new Density.Fraction(0.5)), List.of(f.single(f.dirt)), world);
        long mirrored = 0;
        Set<BlockPos> anchors = new HashSet<>();
        for (ScatterPlan.Placement p : plan.placements()) anchors.add(p.anchor());
        for (BlockPos a : anchors) {
            if ((a.x() & 1) != 0 && (a.z() & 1) != 0 && anchors.contains(new BlockPos(-a.x(), a.y(), -a.z()))) mirrored++;
        }
        // Independent decisions: about half of the ~128 odd-odd placements have a mirrored partner (the xor form
        // gave every one of them a partner).
        assertTrue(mirrored < 100, "mirrored " + mirrored);
    }

    @Test
    void variantsSharingASourceCommitThatSource() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        ScatterPlan plan = plan(Spec.box(0, 0, 15, 15).transforms(Transforms.ALL)
                .variants(new Variant(1, 1), new Variant(0, 1), new Variant(1, 1, 0b0001)),
                List.of(f.single(f.dirt), f.single(f.stone)), world);
        List<dev.sculptory.core.edit.MultiPaste.Placement> pastes = plan.toMultiPaste().placements();
        assertEquals(plan.placements().size(), pastes.size());
        Set<Integer> variants = new HashSet<>();
        for (int i = 0; i < pastes.size(); i++) {
            ScatterPlan.Placement p = plan.placements().get(i);
            variants.add(p.variant());
            assertEquals(p.variant() == 1 ? 0 : 1, pastes.get(i).source());
            assertEquals(p.anchor(), pastes.get(i).origin());
            assertEquals(p.transform(), pastes.get(i).transform());
            if (p.variant() == 2) assertEquals(0, p.transform().quarterTurnsCw());
        }
        assertEquals(Set.of(0, 1, 2), variants);
    }

    @Test
    void targetCountStopsAcceptance() {
        FakeWorld world = f.flat(0, 0, 63, 63, 64);
        ScatterPlan plan = plan(Spec.box(0, 0, 63, 63).density(new Density.Count(50)).spacing(2),
                List.of(f.single(f.dirt)), world);
        assertEquals(50, plan.placements().size());
        assertEquals(4096, plan.candidates());
        assertTrue(plan.count(Outcome.COUNT_LIMIT) > 3000);
        assertEquals(0, plan.count(Outcome.DENSITY));
    }

    @Test
    void budgetCapsTheTotalCells() {
        FakeWorld world = f.flat(0, 0, 31, 31, 64);
        Clipboard pillar = Clipboard.builder(f.states, new BlockPos(1, 3, 1)).set(0, 0, 0, f.log).set(0, 1, 0, f.log)
                .set(0, 2, 0, f.log).build();
        ScatterPlan plan = plan(Spec.box(0, 0, 31, 31).spacing(3), List.of(pillar), world, 3 * 7 + 2);
        assertEquals(7, plan.placements().size());
        assertEquals(21, plan.totalCells());
        assertTrue(plan.count(Outcome.BUDGET) > 0);

        // A small variant still fits once a big one no longer does.
        ScatterPlan mixed = plan(Spec.box(0, 0, 31, 31).spacing(3).variants(new Variant(0, 1), new Variant(1, 1)),
                List.of(pillar, f.single(f.dirt)), world, 20);
        assertEquals(20, mixed.totalCells());
        long pillars = mixed.placements().stream().filter(p -> p.variant() == 0).count();
        assertEquals(20, 3 * pillars + (mixed.placements().size() - pillars));

        ScatterPlan nothing = plan(Spec.box(0, 0, 31, 31), List.of(pillar), world, 2);
        assertEquals(0, nothing.placements().size());
        assertEquals(1024, nothing.count(Outcome.BUDGET));
        assertTrue(nothing.bounds().isEmpty());
    }

    // ------------------------------------------------------------ variants and transforms

    @Test
    void variantsAreDrawnInProportionToTheirWeights() {
        FakeWorld world = f.flat(0, 0, 127, 127, 64);
        List<Clipboard> sources = List.of(f.single(f.dirt), f.single(f.stone), f.single(f.log));
        long[] picks = new long[3];
        long total = 0;
        for (long seed = 1; seed <= 5; seed++) {
            ScatterPlan plan = plan(Spec.box(0, 0, 127, 127).seed(seed)
                    .variants(new Variant(0, 100), new Variant(1, 300), new Variant(2, 600)), sources, world);
            for (ScatterPlan.Placement p : plan.placements()) picks[p.variant()]++;
            total += plan.placements().size();
        }
        assertEquals(5 * 16384, total);
        double[] expected = {0.1, 0.3, 0.6};
        for (int v = 0; v < 3; v++) {
            double share = (double) picks[v] / total;
            assertEquals(expected[v], share, 0.01, "variant " + v + " share");
        }
    }

    @Test
    void transformsComeOnlyFromTheAllowedSet() {
        FakeWorld world = f.flat(0, 0, 63, 63, 64);
        Map<Transform, Integer> seen = transformsUsed(world, new Transforms(0b1010, false), new Variant(0, 1));
        assertEquals(Set.of(new Transform(1, Mirror.NONE), new Transform(3, Mirror.NONE)), seen.keySet());

        seen = transformsUsed(world, new Transforms(0b0011, true), new Variant(0, 1));
        assertEquals(Set.of(new Transform(0, Mirror.NONE), new Transform(1, Mirror.NONE),
                new Transform(0, Mirror.X), new Transform(1, Mirror.X)), seen.keySet());
        for (int count : seen.values()) assertTrue(count > 800, "roughly uniform: " + seen);

        // A variant's own turns restrict the scatter's; with nothing in common the variant's win.
        seen = transformsUsed(world, Transforms.ALL, new Variant(0, 1, 0b0001));
        assertEquals(Set.of(new Transform(0, Mirror.NONE), new Transform(0, Mirror.X)), seen.keySet());
        seen = transformsUsed(world, new Transforms(0b0010, false), new Variant(0, 1, 0b0100));
        assertEquals(Set.of(new Transform(2, Mirror.NONE)), seen.keySet());
        assertEquals(Set.of(Transform.IDENTITY), transformsUsed(world, Transforms.NONE, new Variant(0, 1)).keySet());
    }

    private Map<Transform, Integer> transformsUsed(FakeWorld world, Transforms transforms, Variant variant) {
        ScatterPlan plan = plan(Spec.box(0, 0, 63, 63).transforms(transforms).variants(variant), List.of(f.single(f.dirt)), world);
        Map<Transform, Integer> seen = new HashMap<>();
        for (ScatterPlan.Placement p : plan.placements()) seen.merge(p.transform(), 1, Integer::sum);
        return seen;
    }

    // ------------------------------------------------------------ collision and support

    @Test
    void vegetationAndReplaceableCellsDoNotBlockButSolidsAndStructuresDo() {
        FakeWorld world = f.flat(0, 0, 31, 31, 64);
        for (int x = 0; x <= 31; x++) {
            for (int z = 0; z <= 31; z++) world.set(x, 65, z, f.shortGrass);
        }
        // Over short grass: the tree fits and overwrites it.
        ScatterPlan open = plan(Spec.stamps(Stamp.paint(4, 4, 0)), List.of(f.tree()), world);
        assertEquals(1, open.placements().size());
        assertEquals(new BlockPos(4, 65, 4), open.placements().get(0).anchor());

        // A stone block where the canopy goes and a chest. They sit off the stamped columns: over a column, a
        // floating solid block would itself be the surface.
        world.set(11, 69, 10, f.stone);
        world.set(21, 69, 20, f.chest);
        ScatterPlan blocked = plan(Spec.stamps(Stamp.paint(10, 10, 0), Stamp.paint(20, 20, 0), Stamp.paint(26, 26, 0)),
                List.of(f.tree()), world);
        assertEquals(2, blocked.count(Outcome.COLLISION));
        assertEquals(1, blocked.placements().size());
        assertEquals(new BlockPos(26, 65, 26), blocked.placements().get(0).anchor());
    }

    /** Lake beds and rivers are off limits by default; aquatic assets opt in with {@code allowInFluid}. */
    @Test
    void fluidsBlockPlacementsUnlessAllowed() {
        FakeWorld world = f.flat(0, 0, 31, 31, 64);
        // A lake two blocks deep over x 0-7 (the surface scan passes through it to the lake bed).
        for (int x = 0; x <= 7; x++) {
            for (int z = 0; z <= 31; z++) {
                world.set(x, 65, z, f.water);
                world.set(x, 66, z, f.water);
            }
        }
        // A floating stream in a dry column's trunk space.
        world.set(20, 67, 20, f.water);
        Spec spec = Spec.stamps(Stamp.paint(3, 3, 0), Stamp.paint(20, 20, 0), Stamp.paint(26, 26, 0));

        ScatterPlan trees = plan(spec, List.of(f.tree()), world);
        assertEquals(2, trees.count(Outcome.COLLISION), "lake bed and river");
        assertEquals(List.of(new BlockPos(26, 65, 26)), trees.placements().stream().map(ScatterPlan.Placement::anchor).toList());

        ScatterPlan wet = plan(spec.fit(Fit.DEFAULT.withAllowInFluid(true)), List.of(f.tree()), world);
        assertEquals(3, wet.placements().size());
        assertEquals(0, wet.count(Outcome.COLLISION));

        // Kelp on the lake bed: rejected by default, placed with allowInFluid.
        Spec lake = Spec.box(0, 0, 7, 31);
        Clipboard kelp = f.single(f.shortGrass);
        assertEquals(256, plan(lake, List.of(kelp), world).count(Outcome.COLLISION));
        ScatterPlan planted = plan(lake.fit(Fit.DEFAULT.withAllowInFluid(true)), List.of(kelp), world);
        assertEquals(256, planted.placements().size());
        for (ScatterPlan.Placement p : planted.placements()) assertEquals(65, p.anchor().y(), "on the lake bed");
    }

    @Test
    void fluidAndWaterloggedCellsAreReplaceableOnlyWhenAllowed() {
        int plant = StateFlags.VEGETATION | StateFlags.REPLACEABLE;
        int waterloggedPlant = plant | StateFlags.WATERLOGGABLE | StateFlags.WATERLOGGED;
        int water = StateFlags.FLUID_BLOCK | StateFlags.REPLACEABLE;
        int waterloggedStairs = StateFlags.WATERLOGGABLE | StateFlags.WATERLOGGED;
        for (boolean allow : new boolean[] {false, true}) {
            assertTrue(ScatterPlanner.replaceable(StateFlags.AIR | StateFlags.REPLACEABLE, allow));
            assertTrue(ScatterPlanner.replaceable(plant, allow));
            assertTrue(ScatterPlanner.replaceable(plant | StateFlags.WATERLOGGABLE, allow), "waterloggable, dry");
            assertEquals(allow, ScatterPlanner.replaceable(waterloggedPlant, allow));
            assertEquals(allow, ScatterPlanner.replaceable(water, allow));
            assertFalse(ScatterPlanner.replaceable(waterloggedStairs, allow), "a structure either way");
            assertFalse(ScatterPlanner.replaceable(StateFlags.TERRAIN_SOLID, allow));
            assertFalse(ScatterPlanner.replaceable(water | StateFlags.HAS_BLOCK_ENTITY, allow));
        }
    }

    @Test
    void placementsNeverOverlapEachOther() {
        FakeWorld world = f.flat(0, 0, 31, 31, 64);
        ScatterPlan plan = plan(Spec.box(0, 0, 31, 31).transforms(Transforms.ALL), List.of(f.slab(5, 3, f.stone)), world);
        assertTrue(plan.count(Outcome.COLLISION) > 0);
        Set<BlockPos> cells = new HashSet<>();
        for (ScatterPlan.Placement p : plan.placements()) {
            boolean turned = (p.transform().quarterTurnsCw() & 1) == 1;
            int hx = turned ? 1 : 2, hz = turned ? 2 : 1;
            for (int dx = -hx; dx <= hx; dx++) {
                for (int dz = -hz; dz <= hz; dz++) {
                    assertTrue(cells.add(p.anchor().offset(dx, 0, dz)), "overlap at " + p.anchor().offset(dx, 0, dz));
                }
            }
        }
        assertEquals(plan.totalCells(), cells.size());
    }

    @Test
    void theFootprintMustStayInsideTheBuildHeight() {
        FakeWorld world = f.flat(0, 0, 15, 15, 316);
        ScatterPlan plan = plan(Spec.box(0, 0, 15, 15), List.of(f.tree()), world);
        assertEquals(256, plan.count(Outcome.COLLISION), "the canopy would reach y 322");
    }

    /** A 5-wide base along x with a trunk, anchored at the base's middle. */
    private Clipboard wideTree() {
        Clipboard.Builder b = Clipboard.builder(f.states, new BlockPos(5, 4, 1)).anchor(new BlockPos(2, 0, 0));
        for (int x = 0; x < 5; x++) b.set(x, 0, 0, f.log);
        for (int y = 1; y < 4; y++) b.set(2, y, 0, f.log);
        return b.build();
    }

    @Test
    void aWideBaseNeedsItsShareOfSupportOnASlope() {
        FakeWorld world = f.flat(0, 0, 40, 15, 64);
        // Anchor (10, 5): base x 8-12; the ground drops a block under x 11 and 12, so 3 of 5 (60%) base cells rest
        // on ground and 2 hang over air.
        world.set(11, 64, 5, f.air);
        world.set(12, 64, 5, f.air);
        // Anchor (30, 5): base x 28-32; only x 29 and 30 (40%) rest on ground.
        for (int x : new int[] {28, 31, 32}) world.set(x, 64, 5, f.air);
        Spec spec = Spec.stamps(Stamp.paint(10, 5, 0), Stamp.paint(30, 5, 0));

        ScatterPlan byDefault = plan(spec, List.of(wideTree()), world);
        assertEquals(List.of(new BlockPos(10, 65, 5)), byDefault.placements().stream().map(ScatterPlan.Placement::anchor).toList());
        assertEquals(1, byDefault.count(Outcome.SUPPORT), "40% < 50%");

        assertEquals(2, plan(spec.fit(Fit.DEFAULT.withMinSupportFraction(0.4)), List.of(wideTree()), world).placements().size());
        assertEquals(2, plan(spec.fit(Fit.DEFAULT.withMinSupportFraction(1)), List.of(wideTree()), world).count(Outcome.SUPPORT));
        assertEquals(2, plan(spec.fit(Fit.DEFAULT.withMinSupportFraction(0)), List.of(wideTree()), world).placements().size());
    }

    @Test
    void theAnchorColumnMustBeSupported() {
        // Anchored one below its only cell: it would float.
        FakeWorld world = f.flat(0, 0, 31, 31, 64);
        Clipboard floating = Clipboard.builder(f.states, new BlockPos(1, 1, 1)).anchor(new BlockPos(0, -1, 0))
                .set(0, 0, 0, f.dirt).build();
        ScatterPlan plan = plan(Spec.box(0, 0, 31, 31).fit(Fit.DEFAULT.withMinSupportFraction(0)), List.of(floating), world);
        assertEquals(1024, plan.count(Outcome.SUPPORT));

        // A 3 × 3 slab anchored one below its middle, over a one-column pit: the eight outer base cells rest on the
        // rim (8/9 supported), but the anchor column has no ground under the base.
        FakeWorld rim = f.flat(0, 0, 31, 31, 65);
        rim.set(10, 65, 10, f.air);
        Clipboard.Builder raised = Clipboard.builder(f.states, new BlockPos(3, 1, 3)).anchor(new BlockPos(1, -1, 1));
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) raised.set(x, 0, z, f.stone);
        }
        ScatterPlan pit = plan(Spec.stamps(Stamp.paint(10, 10, 0)).fit(Fit.DEFAULT.withMinSupportFraction(0)),
                List.of(raised.build()), rim);
        assertEquals(new BlockPos(10, 65, 10), anchorOf(rim, 10, 10));
        assertEquals(1, pit.count(Outcome.SUPPORT));
    }

    private BlockPos anchorOf(FakeWorld world, int x, int z) {
        return new BlockPos(x, dev.sculptory.core.brush.SurfaceScan.scan(world, f.states, x, z, 319, -64, null) + 1, z);
    }

    @Test
    void baseCellsMayHangOverAirButNotBurrowIntoGround() {
        FakeWorld world = f.flat(0, 0, 31, 31, 64);
        Clipboard slab = f.slab(3, 3, f.stone);
        // Next to a hole: 8/9 supported, so the slab is placed and one cell hangs.
        world.set(11, 64, 10, f.air);
        // Water is not ground: 4/9 supported under the second slab.
        for (int[] c : new int[][] {{19, 19}, {20, 19}, {21, 19}, {19, 20}, {21, 20}}) world.set(c[0], 64, c[1], f.water);
        ScatterPlan plan = plan(Spec.stamps(Stamp.paint(10, 10, 0), Stamp.paint(20, 20, 0)), List.of(slab), world);
        assertEquals(List.of(new BlockPos(10, 65, 10)), plan.placements().stream().map(ScatterPlan.Placement::anchor).toList());
        assertEquals(1, plan.count(Outcome.SUPPORT));

        // A raised ground block where a base cell goes collides; short grass there does not.
        FakeWorld bump = f.flat(0, 0, 31, 31, 64);
        bump.set(11, 65, 10, f.grass);
        bump.set(21, 65, 20, f.shortGrass);
        ScatterPlan bumps = plan(Spec.stamps(Stamp.paint(10, 10, 0), Stamp.paint(20, 20, 0)), List.of(slab), bump);
        assertEquals(1, bumps.count(Outcome.COLLISION));
        assertEquals(List.of(new BlockPos(20, 65, 20)), bumps.placements().stream().map(ScatterPlan.Placement::anchor).toList());
    }

    // ------------------------------------------------------------ areas and loading

    @Test
    void eraseStampsSubtractInOrder() {
        FakeWorld world = f.flat(-30, -30, 30, 30, 64);
        Spec spec = Spec.stamps(Stamp.paint(0, 0, 20), Stamp.erase(0, 0, 8), Stamp.paint(0, 0, 3), Stamp.erase(25, 25, 10));
        ScatterPlan plan = plan(spec, List.of(f.single(f.dirt)), world);
        long expected = 0;
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                int d = x * x + z * z;
                boolean in = d <= 400 && (d > 64 || d <= 9) && (x - 25) * (x - 25) + (z - 25) * (z - 25) > 100;
                if (in) expected++;
            }
        }
        assertEquals(expected, plan.columns());
        assertEquals(expected, plan.placements().size());
        for (ScatterPlan.Placement p : plan.placements()) {
            int d = p.anchor().x() * p.anchor().x() + p.anchor().z() * p.anchor().z();
            assertTrue(d <= 400 && (d > 64 || d <= 9), "anchor " + p.anchor());
        }
    }

    @Test
    void unloadedChunksAreSkippedAndReported() {
        FakeWorld world = f.flat(0, 0, 47, 15, 64);
        world.setLoaded(1, 0, false);
        ScatterPlan plan = plan(Spec.box(0, 0, 47, 15), List.of(f.single(f.dirt)), world);
        assertEquals(256, plan.count(Outcome.UNLOADED));
        for (ScatterPlan.Placement p : plan.placements()) assertFalse(p.anchor().x() >> 4 == 1);

        // A footprint reaching into the unloaded chunk.
        ScatterPlan reach = plan(Spec.stamps(Stamp.paint(15, 5, 0)), List.of(f.slab(3, 3, f.stone)), world);
        assertEquals(1, reach.count(Outcome.UNLOADED));
        assertEquals(0, reach.placements().size());
    }

    @Test
    void refusesBadSources() {
        FakeWorld world = f.flat(0, 0, 15, 15, 64);
        ScatterSettings settings = Spec.box(0, 0, 15, 15).variants(new Variant(1, 1)).build();
        assertThrows(IllegalArgumentException.class, () -> ScatterPlanner.plan(settings, List.of(f.single(f.dirt)), world, 100));
        Clipboard empty = Clipboard.builder(f.states, new BlockPos(2, 2, 2)).set(0, 0, 0, f.air).build();
        assertThrows(IllegalArgumentException.class,
                () -> ScatterPlanner.plan(Spec.box(0, 0, 15, 15).build(), List.of(empty), world, 100));
        ScatterSettings badMask = Spec.box(0, 0, 15, 15)
                .filters(Filters.NONE.withSubstrate(new CellMask.States(new int[] {f.states.size()}))).build();
        assertThrows(IllegalArgumentException.class, () -> ScatterPlanner.plan(badMask, List.of(f.single(f.dirt)), world, 100));
        assertThrows(IllegalArgumentException.class,
                () -> ScatterPlanner.plan(Spec.box(0, 0, 15, 15).build(), List.of(f.single(f.dirt)), world, -1));
    }

    @Test
    void boundsCoverEveryFootprint() {
        FakeWorld world = hills();
        ScatterPlan plan = plan(busySpec(), busySources(), world);
        Box bounds = plan.bounds().orElseThrow();
        List<Clipboard> sources = busySources();
        List<BlockPos> cells = new ArrayList<>();
        for (ScatterPlan.Placement p : plan.placements()) {
            Clipboard source = sources.get(plan.settings().variants().get(p.variant()).source());
            BlockPos size = source.size();
            BlockPos a = p.transform().apply(source.anchor(), size);
            source.forEachCell((x, y, z, state, tile) -> {
                if (state < 0 || state == f.air) return;
                BlockPos t = p.transform().apply(x, y, z, size.x(), size.y(), size.z());
                cells.add(p.anchor().offset(t.x() - a.x(), t.y() - a.y(), t.z() - a.z()));
            });
        }
        assertEquals(plan.totalCells(), cells.size());
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (BlockPos cell : cells) {
            assertTrue(bounds.contains(cell), cell + " outside " + bounds);
            min[0] = Math.min(min[0], cell.x());
            min[1] = Math.min(min[1], cell.y());
            min[2] = Math.min(min[2], cell.z());
            max[0] = Math.max(max[0], cell.x());
            max[1] = Math.max(max[1], cell.y());
            max[2] = Math.max(max[2], cell.z());
        }
        assertEquals(new Box(new BlockPos(min[0], min[1], min[2]), new BlockPos(max[0], max[1], max[2])), bounds, "tight");
    }
}
