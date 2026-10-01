package dev.sculptory.fabric.client.editor.tools.scatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The draw cap and level-of-detail decisions for scatter placements. */
class ScatterLodTest {
    /** Placements along +x at x = 0, 10, 20, ... (y 0, z 0), each costing {@code draws} ghost draws. */
    private static double[] line(int count) {
        double[] centres = new double[3 * count];
        for (int i = 0; i < count; i++) centres[3 * i] = 10.0 * i;
        return centres;
    }

    private static int[] draws(int count, int each) {
        int[] draws = new int[count];
        Arrays.fill(draws, each);
        return draws;
    }

    @Test
    void theNearestPlacementsAreGhostsThenOutlinesThenDotsThenNothing() {
        ScatterLod.Caps caps = new ScatterLod.Caps(3, 100, 1_000, 2, 1_000, 4, 1_000);
        ScatterLod.Plan plan = ScatterLod.plan(line(12), draws(12, 1), 0, 0, 0, caps);
        StringBuilder tiers = new StringBuilder();
        for (int i = 0; i < 12; i++) tiers.append(plan.tier(i).name().charAt(0));
        assertEquals("GGGBBDDDDNNN", tiers.toString());
        assertEquals(3, plan.ghosts());
        assertEquals(2, plan.boxes());
        assertEquals(4, plan.dots());
        assertEquals(3, plan.hidden());
        assertTrue(plan.ghostsCapped());
    }

    @Test
    void theOrderFollowsTheCameraNotTheList() {
        ScatterLod.Caps caps = new ScatterLod.Caps(1, 100, 1_000, 0, 0, 0, 0);
        ScatterLod.Plan plan = ScatterLod.plan(line(5), draws(5, 1), 41, 0, 0, caps);
        assertEquals(ScatterLod.Tier.GHOST, plan.tier(4), "x = 40 is nearest to the camera at 41");
        assertEquals(1, plan.ghosts());
    }

    @Test
    void ghostsAreCappedByDrawsAndACheaperFartherOneMayStillFit() {
        double[] centres = line(3);
        int[] draws = {6, 8, 3};
        ScatterLod.Plan plan = ScatterLod.plan(centres, draws, 0, 0, 0, new ScatterLod.Caps(10, 10, 1_000, 10, 1_000, 0, 0));
        assertEquals(ScatterLod.Tier.GHOST, plan.tier(0));
        assertEquals(ScatterLod.Tier.BOX, plan.tier(1), "6 + 8 draws is over the budget");
        assertEquals(ScatterLod.Tier.GHOST, plan.tier(2), "6 + 3 fits");
    }

    @Test
    void distancesLimitEachTierAndPlacementsWithoutAPreviewAreNeverGhosts() {
        ScatterLod.Caps caps = new ScatterLod.Caps(100, 100, 15, 100, 25, 100, 35);
        int[] draws = {1, -1, 1, 1, 1};
        ScatterLod.Plan plan = ScatterLod.plan(line(5), draws, 0, 0, 0, caps);
        assertEquals(ScatterLod.Tier.GHOST, plan.tier(0));
        assertEquals(ScatterLod.Tier.BOX, plan.tier(1), "no preview: an outline even when near");
        assertEquals(ScatterLod.Tier.BOX, plan.tier(2), "20 blocks: past the ghost distance");
        assertEquals(ScatterLod.Tier.DOT, plan.tier(3), "30 blocks: past the outline distance");
        assertEquals(ScatterLod.Tier.NONE, plan.tier(4), "40 blocks: too far for anything");
        assertFalse(plan.ghostsCapped(), "only distance held ghosts back");
    }

    @Test
    void thousandsOfPlacementsStayWithinTheDefaultCaps() {
        int count = 131_072;
        double[] centres = new double[3 * count];
        for (int i = 0; i < count; i++) {
            centres[3 * i] = (i % 362) * 3;
            centres[3 * i + 1] = 64;
            centres[3 * i + 2] = (i / 362) * 3;
        }
        ScatterLod.Plan plan = ScatterLod.plan(centres, draws(count, 1), 500, 80, 500, ScatterLod.DEFAULT);
        assertTrue(plan.ghosts() <= ScatterLod.DEFAULT.ghosts());
        assertTrue(plan.boxes() <= ScatterLod.DEFAULT.boxes());
        assertTrue(plan.dots() <= ScatterLod.DEFAULT.dots());
        assertEquals(count, plan.ghosts() + plan.boxes() + plan.dots() + plan.hidden());
        assertEquals(ScatterLod.DEFAULT.ghosts(), plan.ghosts(), "plenty of placements within ghost distance");
    }

    @Test
    void theGhostCapFollowsTheFrameCost() {
        int max = ScatterLod.DEFAULT.ghosts();
        long budget = ScatterLod.GHOST_BUDGET_NANOS;
        assertEquals(1_500, ScatterLod.tune(2_000, budget + 1, true, max), "over budget: a quarter less");
        assertEquals(2_000, ScatterLod.tune(2_000, budget / 4, true, max), "never over the maximum");
        assertEquals(1_125, ScatterLod.tune(1_000, budget / 4, true, max), "cheap and capped: an eighth more");
        assertEquals(1_000, ScatterLod.tune(1_000, budget / 4, false, max), "nothing held back: stays");
        assertEquals(1_000, ScatterLod.tune(1_000, budget * 3 / 4, true, max), "in between: stays");
        assertEquals(ScatterLod.MIN_GHOSTS, ScatterLod.tune(70, budget * 10, true, max), "never under the minimum");
        int cap = max;
        for (int frame = 0; frame < 50; frame++) cap = ScatterLod.tune(cap, budget * 2, true, max);
        assertEquals(ScatterLod.MIN_GHOSTS, cap, "a slow machine settles at the minimum");
    }
}
