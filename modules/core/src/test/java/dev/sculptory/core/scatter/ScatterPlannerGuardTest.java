package dev.sculptory.core.scatter;

import static dev.sculptory.core.scatter.ScatterFixture.assertBalanced;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.scatter.ScatterArea.Stamp;
import dev.sculptory.core.scatter.ScatterFixture.Spec;
import dev.sculptory.core.scatter.ScatterSettings.Variant;
import dev.sculptory.core.testing.FakeWorld;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The protection guard (M3-B server protection) and chunks unloading between survey and acceptance. */
class ScatterPlannerGuardTest {
    private final ScatterFixture f = new ScatterFixture();

    @Test
    void theGuardRejectsFootprintsReachingDeniedColumns() {
        FakeWorld world = f.flat(0, 0, 63, 31, 64);
        Spec spec = Spec.box(0, 0, 63, 31).spacing(4).variants(new Variant(0, 1)).seed(7);
        List<Clipboard> slab = List.of(f.slab(5, 5, f.stone));
        ScatterPlan open = ScatterPlanner.plan(spec.build(), slab, world, Long.MAX_VALUE);
        ScatterPlan allowAll = new ScatterPlanner(spec.build(), slab, world, Long.MAX_VALUE,
                ScatterPlanner.DEFAULT_MAX_WORK, ScatterPlanner.ProtectionGuard.ALLOW_ALL).finish();
        assertEquals(open.hash(), allowAll.hash());
        assertEquals(0, open.count(Outcome.PROTECTED));

        // Columns x >= 32 are protected: a slab centred at x = 30 reaches x = 32 and is refused.
        int[] calls = {0};
        ScatterPlanner.ProtectionGuard eastProtected = (x, y, z) -> {
            calls[0]++;
            return x < 32;
        };
        ScatterPlan guarded = new ScatterPlanner(spec.build(), slab, world, Long.MAX_VALUE,
                ScatterPlanner.DEFAULT_MAX_WORK, eastProtected).finish();
        assertBalanced(guarded);
        assertTrue(guarded.count(Outcome.PROTECTED) > 0, guarded.toString());
        assertTrue(calls[0] > 0);
        assertTrue(guarded.placements().size() > 10, guarded.toString());
        for (ScatterPlan.Placement p : guarded.placements()) {
            assertTrue(p.anchor().x() + 2 < 32, "footprint reaches a protected column: " + p.anchor());
        }
        assertTrue(guarded.bounds().orElseThrow().max().x() < 32);
    }

    /** Protection limited in height: only a footprint cell at a denied height refuses a placement. */
    @Test
    void theGuardIsAskedAtEachFootprintCellsHeight() {
        FakeWorld world = f.flat(0, 0, 63, 31, 64);
        Spec spec = Spec.box(0, 0, 63, 31).spacing(4).variants(new Variant(0, 1)).seed(7);
        List<Clipboard> slab = List.of(f.slab(5, 5, f.stone));
        ScatterPlan open = ScatterPlanner.plan(spec.build(), slab, world, Long.MAX_VALUE);
        int layer = open.placements().get(0).anchor().y();
        for (ScatterPlan.Placement p : open.placements()) assertEquals(layer, p.anchor().y(), "one layer of slabs");

        // East of x 32, protected only above the slabs' layer: nothing they write is denied.
        ScatterPlan above = new ScatterPlanner(spec.build(), slab, world, Long.MAX_VALUE,
                ScatterPlanner.DEFAULT_MAX_WORK, (x, y, z) -> x < 32 || y <= layer).finish();
        assertEquals(open.hash(), above.hash(), open + " vs " + above);
        assertEquals(0, above.count(Outcome.PROTECTED));

        // Protected at the slabs' layer: refused there as a column guard would.
        ScatterPlan at = new ScatterPlanner(spec.build(), slab, world, Long.MAX_VALUE,
                ScatterPlanner.DEFAULT_MAX_WORK, (x, y, z) -> x < 32 || y != layer).finish();
        assertBalanced(at);
        assertTrue(at.count(Outcome.PROTECTED) > 0, at.toString());
        for (ScatterPlan.Placement p : at.placements()) {
            assertTrue(p.anchor().x() + 2 < 32, "footprint reaches a protected cell: " + p.anchor());
        }
    }

    /**
     * A source anchored beside its only block: the anchor column is read for support although the footprint does
     * not cover it. If its chunk unloads between the survey and acceptance, the candidate is UNLOADED (nothing is
     * read from the unloaded chunk).
     */
    @Test
    void anAnchorChunkUnloadedAfterTheSurveyIsUnloaded() {
        FakeWorld world = f.flat(0, 0, 47, 15, 64);
        Clipboard offset = Clipboard.builder(f.states, new BlockPos(1, 1, 1)).anchor(new BlockPos(-2, 0, 0))
                .set(0, 0, 0, f.dirt).build();
        // Stamp at x = 15 (chunk 0): the block lands at x = 17 (chunk 1).
        ScatterPlanner planner = new ScatterPlanner(Spec.stamps(Stamp.paint(15, 5, 0)).build(), List.of(offset), world,
                Long.MAX_VALUE);
        planner.survey(0, 0, 47, 15);
        world.setLoaded(0, 0, false);
        ScatterPlan plan = planner.finish();
        assertEquals(0, plan.placements().size());
        assertEquals(1, plan.count(Outcome.UNLOADED));
    }
}
