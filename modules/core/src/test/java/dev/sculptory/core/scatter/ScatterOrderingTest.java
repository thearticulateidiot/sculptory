package dev.sculptory.core.scatter;

import static dev.sculptory.core.scatter.ScatterFixture.assertBalanced;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.scatter.ScatterFixture.Spec;
import dev.sculptory.core.scatter.ScatterSettings.Density;
import dev.sculptory.core.scatter.ScatterSettings.Filters;
import dev.sculptory.core.scatter.ScatterSettings.Transforms;
import dev.sculptory.core.scatter.ScatterSettings.Variant;
import dev.sculptory.core.testing.FakeWorld;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Lazy bucketed ordering: the plan is the one a full sort gives, starting acceptance sorts nothing, and a step sorts
 * at most the bucket it enters.
 */
class ScatterOrderingTest {
    private final ScatterFixture f = new ScatterFixture();

    private List<Clipboard> sources() {
        return List.of(f.tree(), f.slab(3, 1, f.stone), f.single(f.dirt));
    }

    private ScatterPlan plan(Spec spec, FakeWorld world, int bucketBits, long maxWork, FootprintCache cache) {
        ScatterPlanner planner = new ScatterPlanner(spec.build(), sources(), world, Long.MAX_VALUE, maxWork,
                ScatterPlanner.ColumnGuard.ALLOW_ALL, cache);
        planner.bucketBits(bucketBits);
        return planner.finish();
    }

    @Test
    void bucketedOrderGivesTheFullSortsPlan() {
        FakeWorld world = f.terrain(-50, -40, 80, 70, ScatterFixture::hills, f.grass);
        FootprintCache cache = new FootprintCache(8, 1 << 20);
        for (Spec spec : List.of(busy(), busy().spacing(0), busy().density(new Density.Count(300)))) {
            for (long maxWork : new long[] {ScatterPlanner.DEFAULT_MAX_WORK, 20_000}) {
                ScatterPlan fullSort = plan(spec, world, 0, maxWork, null);
                assertBalanced(fullSort);
                for (int bits : new int[] {1, 4, ScatterPlanner.BUCKET_BITS, 16}) {
                    ScatterPlan bucketed = plan(spec, world, bits, maxWork, cache);
                    assertEquals(fullSort.hash(), bucketed.hash(), bits + " bucket bits, work " + maxWork);
                    assertEquals(fullSort.placements(), bucketed.placements());
                    assertEquals(fullSort.rejectedCounts(), bucketed.rejectedCounts());
                }
            }
        }
        assertEquals(3, cache.size(), "one cached footprint per source");
        ScatterPlan limited = plan(busy(), world, ScatterPlanner.BUCKET_BITS, 20_000, cache);
        assertTrue(limited.count(Outcome.WORK_LIMIT) > 0, "the small budget is reached: " + limited);
    }

    /** A fresh spec each time: {@code Spec}'s setters change the spec itself. */
    private static Spec busy() {
        return Spec.box(-40, -30, 70, 60).density(new Density.Fraction(0.8)).spacing(2)
                .filters(Filters.NONE.withSlope(0, 3))
                .variants(new Variant(0, 5), new Variant(1, 3, 0b0101), new Variant(2, 2))
                .transforms(Transforms.ALL).seed(0xFACE);
    }

    @Test
    void startingAcceptanceSortsNothingAndEachStepAtMostOneBucket() {
        FakeWorld world = f.flat(0, 0, 255, 255, 64);
        ScatterSettings settings = Spec.box(0, 0, 255, 255).density(new Density.Fraction(1)).spacing(3)
                .variants(new Variant(0, 1)).build();
        ScatterPlanner planner = new ScatterPlanner(settings, List.of(f.single(f.dirt)), world, Long.MAX_VALUE);
        for (int cx = 0; cx < 16; cx++) {
            for (int cz = 0; cz < 16; cz++) planner.survey(cx << 4, cz << 4, (cx << 4) + 15, (cz << 4) + 15);
        }
        assertEquals(65_536, planner.candidateCount());
        ScatterPlanner.Acceptance acceptance = planner.acceptance();
        assertEquals(0, planner.sortedCandidates, "starting acceptance sorts nothing");
        long largestStep = 0, steps = 0;
        boolean done = false;
        while (!done) {
            long before = planner.sortedCandidates;
            done = acceptance.step(1);
            largestStep = Math.max(largestStep, planner.sortedCandidates - before);
            steps++;
        }
        assertEquals(65_536 + 1, steps, "one candidate per one-unit step, then the plan");
        assertEquals(65_536, planner.sortedCandidates, "every bucket sorted once");
        // 65,536 uniform ranks in 4,096 buckets: 16 on average; 64 would be far in the tail.
        assertTrue(largestStep > 0 && largestStep <= 64, "a step sorted " + largestStep);
        ScatterPlan whole = ScatterPlanner.plan(settings, List.of(f.single(f.dirt)), world, Long.MAX_VALUE);
        assertEquals(whole.hash(), acceptance.plan().hash());
    }
}
