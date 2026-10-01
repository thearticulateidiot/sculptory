package dev.sculptory.core.scatter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CompileContext;
import dev.sculptory.core.edit.MultiPaste;
import dev.sculptory.core.edit.SourceBlocks;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.ScatterSettings.Density;
import dev.sculptory.core.scatter.ScatterSettings.Filters;
import dev.sculptory.core.scatter.ScatterSettings.Fit;
import dev.sculptory.core.scatter.ScatterSettings.Transforms;
import dev.sculptory.core.scatter.ScatterSettings.Variant;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntBinaryOperator;

/** Worlds, assets and settings shared by the scatter tests. */
final class ScatterFixture {
    final FakeStateSpace states = new FakeStateSpace();
    final int air = states.air();
    final int stone = states.state("minecraft:stone");
    final int dirt = states.state("minecraft:dirt");
    final int grass = states.state("minecraft:grass_block");
    final int sand = states.state("minecraft:sand");
    final int shortGrass = states.state("minecraft:short_grass");
    final int water = states.state("minecraft:water");
    final int log = states.state("minecraft:oak_log");
    final int chest = states.state("minecraft:chest");

    /** A world whose columns in the rectangle have stone below a {@code top} block at {@code height(x, z)}. */
    FakeWorld terrain(int x0, int z0, int x1, int z1, IntBinaryOperator height, int top) {
        FakeWorld world = new FakeWorld(states);
        world.setHeightHints(true);
        paint(world, x0, z0, x1, z1, height, top);
        return world;
    }

    void paint(FakeWorld world, int x0, int z0, int x1, int z1, IntBinaryOperator height, int top) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int h = height.applyAsInt(x, z);
                world.set(x, h - 2, z, stone);
                world.set(x, h - 1, z, stone);
                world.set(x, h, z, top);
            }
        }
    }

    FakeWorld flat(int x0, int z0, int x1, int z1, int y) {
        return terrain(x0, z0, x1, z1, (x, z) -> y, grass);
    }

    /** Gentle hills, 58-70: every step between neighbours is small but not always zero. */
    static int hills(int x, int z) {
        return 64 + (int) Math.round(4 * StrictMath.sin(x / 9.0) + 3 * StrictMath.cos(z / 7.0) + StrictMath.sin((x + z) / 3.0));
    }

    /** One block with its anchor on it. */
    Clipboard single(int state) {
        return Clipboard.builder(states, new BlockPos(1, 1, 1)).set(0, 0, 0, state).build();
    }

    /** A {@code w × 1 × d} slab of {@code state}, anchored at its centre (w and d odd). */
    Clipboard slab(int w, int d, int state) {
        Clipboard.Builder b = Clipboard.builder(states, new BlockPos(w, 1, d)).anchor(new BlockPos(w / 2, 0, d / 2));
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) b.set(x, 0, z, state);
        }
        return b.build();
    }

    /**
     * A 5 × 6 × 3 "tree": a log trunk at (2, 0..3, 1) and an asymmetric canopy of east-facing widgets and logs
     * (so every transform gives a different result), anchored at the trunk's foot.
     */
    Clipboard tree() {
        Clipboard.Builder b = Clipboard.builder(states, new BlockPos(5, 6, 3)).anchor(new BlockPos(2, 0, 1));
        for (int y = 0; y < 4; y++) b.set(2, y, 1, log);
        int widget = states.state("testmod:widget[facing=east]");
        int stairs = states.state("minecraft:oak_stairs[facing=north,shape=inner_left]");
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 3; z++) {
                b.set(x, 4, z, (x + z) % 2 == 0 ? widget : states.state("minecraft:oak_log[axis=x]"));
            }
        }
        b.set(3, 5, 0, stairs);
        b.set(0, 5, 2, widget);
        return b.build();
    }

    /** Settings with sensible defaults, changed with the {@code with} methods. */
    static final class Spec {
        ScatterArea area;
        Density density = new Density.Fraction(1);
        int spacing;
        Filters filters = Filters.NONE;
        Fit fit = Fit.DEFAULT;
        List<Variant> variants = List.of(new Variant(0, 1));
        Transforms transforms = Transforms.NONE;
        long seed = 42;
        ScatterSettings.ColumnHeight columns = ScatterSettings.ColumnHeight.ONE;

        Spec(ScatterArea area) {
            this.area = area;
        }

        static Spec box(int x0, int z0, int x1, int z1) {
            return new Spec(new ScatterArea.Region(Box.of(new BlockPos(x0, -64, z0), new BlockPos(x1, 319, z1))));
        }

        static Spec stamps(ScatterArea.Stamp... stamps) {
            return new Spec(new ScatterArea.Stamps(List.of(stamps)));
        }

        Spec density(Density value) {
            density = value;
            return this;
        }

        Spec spacing(int value) {
            spacing = value;
            return this;
        }

        Spec filters(Filters value) {
            filters = value;
            return this;
        }

        Spec fit(Fit value) {
            fit = value;
            return this;
        }

        Spec variants(Variant... value) {
            variants = List.of(value);
            return this;
        }

        Spec transforms(Transforms value) {
            transforms = value;
            return this;
        }

        Spec seed(long value) {
            seed = value;
            return this;
        }

        Spec columns(int min, int max) {
            columns = new ScatterSettings.ColumnHeight(min, max);
            return this;
        }

        ScatterSettings build() {
            return new ScatterSettings(area, density, spacing, filters, fit, variants, transforms, seed, columns);
        }
    }

    static ScatterPlan plan(Spec spec, List<Clipboard> sources, FakeWorld world) {
        return plan(spec, sources, world, Long.MAX_VALUE);
    }

    static ScatterPlan plan(Spec spec, List<Clipboard> sources, FakeWorld world, long maxCells) {
        ScatterPlan plan = ScatterPlanner.plan(spec.build(), sources, world, maxCells);
        assertBalanced(plan);
        return plan;
    }

    /** Every area column ends as exactly one outcome or placement. */
    static void assertBalanced(ScatterPlan plan) {
        long total = plan.placements().size();
        for (Outcome outcome : Outcome.values()) total += plan.count(outcome);
        assertEquals(plan.columns(), total, "outcomes + placements = columns: " + plan);
        long candidateOutcomes = plan.placements().size();
        for (Outcome outcome : List.of(Outcome.SPACING, Outcome.COLLISION, Outcome.SUPPORT, Outcome.COUNT_LIMIT,
                Outcome.BUDGET, Outcome.WORK_LIMIT, Outcome.PROTECTED, Outcome.SURVIVAL, Outcome.FEATURE_FAILED,
                Outcome.MASKED)) {
            candidateOutcomes += plan.count(outcome);
        }
        // UNLOADED and WATER are the only outcomes shared with columns.
        long sharedCandidates = plan.candidates() - candidateOutcomes;
        assertEquals(true, sharedCandidates >= 0
                && sharedCandidates <= plan.count(Outcome.UNLOADED) + plan.count(Outcome.WATER), plan.toString());
    }

    /** An overworld context whose only scatter plan is {@code plan} under {@code id}. */
    static CompileContext context(StateSpace states, UUID id, MultiPaste plan, long maxCells) {
        return new CompileContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef ref) {
                return Optional.empty();
            }

            @Override
            public Optional<MultiPaste> scatterPlan(UUID planId) {
                return planId.equals(id) ? Optional.of(plan) : Optional.empty();
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
        };
    }

    /** Placement anchors as (x, z) pairs. */
    static List<long[]> anchors(ScatterPlan plan) {
        List<long[]> out = new ArrayList<>();
        for (ScatterPlan.Placement p : plan.placements()) out.add(new long[] {p.anchor().x(), p.anchor().z()});
        return out;
    }

    static Map<Outcome, Long> counts(ScatterPlan plan) {
        return plan.rejectedCounts();
    }
}
