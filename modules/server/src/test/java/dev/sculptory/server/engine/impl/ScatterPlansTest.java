package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterPlanner;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.schem.AssetInfo;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.server.library.LibraryPath;
import dev.sculptory.server.library.LibraryPathException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The scatter plan store (one live plan per player, TTL, world pinning) and the scatter admission helpers. */
class ScatterPlansTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private static ScatterPlan plan() {
        FakeStateSpace states = new FakeStateSpace();
        FakeWorld world = new FakeWorld(states);
        world.fill(Box.of(new BlockPos(0, 60, 0), new BlockPos(15, 60, 15)), states.state("minecraft:stone"));
        Clipboard dirt = Clipboard.builder(states, new BlockPos(1, 1, 1)).set(0, 0, 0, states.state("minecraft:dirt"))
                .build();
        ScatterSettings settings = new ScatterSettings(
                new ScatterArea.Region(Box.of(new BlockPos(0, 55, 0), new BlockPos(15, 70, 15))),
                new ScatterSettings.Density.Fraction(1), 3, ScatterSettings.Filters.NONE, ScatterSettings.Fit.DEFAULT,
                List.of(new ScatterSettings.Variant(0, 1)), ScatterSettings.Transforms.NONE, 1L);
        return ScatterPlanner.plan(settings, List.of(dirt), world, 1000);
    }

    private static LibraryPath file(String path) {
        try {
            return LibraryPath.file(path);
        } catch (LibraryPathException e) {
            throw new AssertionError(e);
        }
    }

    private static LibraryPath folder(String path) {
        try {
            return LibraryPath.folder(path);
        } catch (LibraryPathException e) {
            throw new AssertionError(e);
        }
    }

    /** Per-asset access: plans follow library renames and moves, end with a deletion, and end for a revoked player. */
    @Test
    void plansFollowLibraryMovesAndEndWithDeletionsAndRevocations() {
        ScatterPlans plans = new ScatterPlans();
        UUID alice = new UUID(1, 1), bob = new UUID(2, 2), carol = new UUID(3, 3);
        ScatterPlan plan = plan();
        long t0 = 1_000_000_000L;
        plans.put(alice, OVERWORLD, plan, t0, List.of(file("trees/oak.schem")), false);
        plans.put(bob, OVERWORLD, plan, t0, List.of(file("trees/big/elm.schem")), false);
        plans.put(carol, OVERWORLD, plan, t0, List.of(file("Trees/Oak.schem")), false); // another spelling

        plans.moved(file("trees/oak.schem"), file("rocks/oak.schem"));
        assertEquals(List.of(file("rocks/oak.schem")), plans.get(alice).orElseThrow().libraryPaths(), "a rename re-keys the source");
        assertTrue(plans.get(carol).isEmpty(), "a plan on another spelling of the moved name is dropped");
        plans.moved(folder("trees"), folder("forest"));
        assertEquals(List.of(file("forest/big/elm.schem")), plans.get(bob).orElseThrow().libraryPaths());

        assertFalse(plans.dropIfUses(alice, file("trees/oak.schem")), "the old name no longer matches");
        assertTrue(plans.dropIfUses(alice, file("rocks/oak.schem")), "a revocation of the new name ends the plan");
        assertTrue(plans.get(alice).isEmpty());

        plans.put(alice, OVERWORLD, plan, t0, List.of(file("rocks/oak.schem")), false);
        plans.removed(file("ROCKS/OAK.schem"));
        assertTrue(plans.get(alice).isEmpty(), "a deletion ends the plans using the file, under any spelling");
        plans.removed(folder("forest"));
        assertTrue(plans.get(bob).isEmpty(), "a folder deletion ends the plans using anything inside it");

        plans.put(alice, OVERWORLD, plan, t0, List.of(file("a/x.schem")), false);
        plans.put(bob, OVERWORLD, plan, t0);
        assertEquals(1, plans.dropUsing(path -> path.name().equals("x.schem")));
        assertTrue(plans.get(alice).isEmpty() && plans.get(bob).isPresent(), "a plan without library sources is untouched");
    }

    @Test
    void onePlanPerPlayerPinnedToItsWorldForItsLifetime() {
        ScatterPlans plans = new ScatterPlans();
        UUID alice = new UUID(1, 1), bob = new UUID(2, 2);
        ScatterPlan plan = plan();
        long t0 = 1_000_000_000L;
        ScatterPlans.Held first = plans.put(alice, OVERWORLD, plan, t0);
        ScatterPlans.Held second = plans.put(alice, OVERWORLD, plan, t0);
        assertNotEquals(first.id(), second.id());
        assertEquals(1, plans.size(), "a new plan replaces the old one");
        assertTrue(plans.find(alice, first.id(), OVERWORLD, t0).isEmpty(), "the replaced id stops resolving");
        assertEquals(second, plans.find(alice, second.id(), OVERWORLD, t0 + ScatterPlans.TTL_NANOS - 1).orElseThrow());
        assertTrue(plans.find(bob, second.id(), OVERWORLD, t0).isEmpty(), "only the owner finds it");

        assertTrue(plans.find(alice, second.id(), OVERWORLD, t0 + ScatterPlans.TTL_NANOS).isEmpty(), "expired");
        assertEquals(0, plans.size(), "an expired plan is dropped when looked up");

        ScatterPlans.Held third = plans.put(alice, OVERWORLD, plan, t0);
        assertTrue(plans.find(alice, third.id(), NETHER, t0).isEmpty(), "pinned to its world");
        assertEquals(0, plans.size(), "a plan looked up from another world is dropped");

        ScatterPlans.Held fourth = plans.put(alice, OVERWORLD, plan, t0);
        plans.consume(alice, UUID.randomUUID());
        assertEquals(1, plans.size(), "consuming another id keeps the plan");
        plans.consume(alice, fourth.id());
        assertEquals(0, plans.size());
    }

    @Test
    void sweepsDropExpiredPlansAndPlayersWhoLeftOrChangedWorld() {
        ScatterPlans plans = new ScatterPlans();
        ScatterPlan plan = plan();
        UUID stays = new UUID(1, 1), left = new UUID(2, 2), moved = new UUID(3, 3), old = new UUID(4, 4);
        long now = 5_000_000_000_000L;
        plans.put(stays, OVERWORLD, plan, now);
        plans.put(left, OVERWORLD, plan, now);
        plans.put(moved, OVERWORLD, plan, now);
        plans.put(old, OVERWORLD, plan, now - ScatterPlans.TTL_NANOS);
        Map<UUID, String> worlds = new HashMap<>(Map.of(stays, OVERWORLD, moved, NETHER, old, OVERWORLD));
        assertEquals(3, plans.sweep(now, worlds::get));
        assertEquals(1, plans.size());
        assertTrue(plans.get(stays).isPresent());
        plans.remove(stays);
        assertEquals(0, plans.size());
    }

    @Test
    void assetRotationsBecomeTurnMasks() {
        BlockPos anchor = BlockPos.ORIGIN;
        assertEquals(ScatterSettings.Variant.ALL_TURNS, ServerScatter.turnMask(null));
        assertEquals(1, ServerScatter.turnMask(new AssetInfo(List.of(), anchor, List.of(), 1)), "none listed: unrotated");
        assertEquals(0b0101, ServerScatter.turnMask(new AssetInfo(List.of(), anchor, List.of(2, 0), 1)));
        assertEquals(0b1111, ServerScatter.turnMask(new AssetInfo(List.of(), anchor, AssetInfo.ALL_ROTATIONS, 1)));
    }

    @Test
    void reachCoversEveryTurnAndAFarAnchor() {
        FakeStateSpace states = new FakeStateSpace();
        int dirt = states.state("minecraft:dirt");
        Clipboard centred = Clipboard.builder(states, new BlockPos(5, 2, 3)).anchor(new BlockPos(2, 0, 1))
                .set(0, 0, 0, dirt).build();
        assertEquals(2, ServerScatter.reach(centred));
        Clipboard corner = Clipboard.builder(states, new BlockPos(5, 2, 9)).set(0, 0, 0, dirt).build();
        assertEquals(8, ServerScatter.reach(corner), "a turn swaps x and z");
        Clipboard far = Clipboard.builder(states, new BlockPos(1, 1, 1)).anchor(new BlockPos(-40, 0, 0))
                .set(0, 0, 0, dirt).build();
        assertEquals(40, ServerScatter.reach(far));
    }

    @Test
    void theWorkEstimateIsExpectedCandidatesTimesTheLargestSource() {
        assertEquals(65_536L * 300, ServerScatter.estimatedWork(262_144, new ScatterSettings.Density.Fraction(0.25), 300));
        assertEquals(3L * 300, ServerScatter.estimatedWork(10, new ScatterSettings.Density.Fraction(0.25), 300),
                "expected candidates round up");
        assertEquals((1L << 20) * 300, ServerScatter.estimatedWork(1 << 20, new ScatterSettings.Density.Count(500), 300),
                "a target count may go through every column");
        assertEquals(100L, ServerScatter.estimatedWork(100, new ScatterSettings.Density.Count(500), 1));
        assertEquals(0L, ServerScatter.estimatedWork(1 << 20, new ScatterSettings.Density.Fraction(0), 5000));
        assertTrue(ServerScatter.estimatedWork(1 << 20, new ScatterSettings.Density.Fraction(1), 1 << 21) > 50_000_000L);
    }
}
