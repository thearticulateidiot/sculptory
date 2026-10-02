package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.EditRejected;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What a scatter preview holds (A3), and the per-player hold budget. */
class ScatterHoldTest {
    private static final double NO_BORDER = 30_000_000;

    private static LongOpenHashSet columns(long... packed) {
        return new LongOpenHashSet(packed);
    }

    @Test
    void twoDotsFarApartHoldOnlyTheirOwnChunks() throws EditRejected {
        ScatterArea dots = new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(8, 8, 3),
                ScatterArea.Stamp.paint(904, 8, 3)));
        LongOpenHashSet held = ServerScatter.heldColumns(dots, 1);
        assertEquals(columns(ColumnPlan.pack(0, 0), ColumnPlan.pack(56, 0)), held);

        // A wider reach spreads into the neighbouring chunks of each dot only.
        held = ServerScatter.heldColumns(dots, 9);
        assertEquals(9 + 9, held.size(), held.toString());
        assertTrue(held.contains(ColumnPlan.pack(-1, -1)) && held.contains(ColumnPlan.pack(57, 1)));
        assertTrue(!held.contains(ColumnPlan.pack(20, 0)), "a chunk between the dots is held");
    }

    /** Stamps along a stroke share chunk rectangles (each added once): the held set is still the union of all. */
    @Test
    void aLongStrokeHoldsTheUnionOfItsStamps() throws EditRejected {
        List<ScatterArea.Stamp> stroke = new java.util.ArrayList<>();
        for (int i = 0; i < 400; i++) stroke.add(ScatterArea.Stamp.paint(i * 4, (i * 7) % 50, 8));
        LongOpenHashSet expected = new LongOpenHashSet();
        for (ScatterArea.Stamp stamp : stroke) {
            expected.addAll(ServerScatter.heldColumns(new ScatterArea.Stamps(List.of(stamp)), 3));
        }
        assertEquals(expected, ServerScatter.heldColumns(new ScatterArea.Stamps(stroke), 3));
    }

    @Test
    void eraseStampsHoldNothingAndABoxHoldsItsRectangle() throws EditRejected {
        ScatterArea erased = new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(8, 8, 2),
                ScatterArea.Stamp.erase(500, 500, 30)));
        assertEquals(columns(ColumnPlan.pack(0, 0)), ServerScatter.heldColumns(erased, 1));

        ScatterArea box = new ScatterArea.Region(Box.of(new BlockPos(0, 60, 0), new BlockPos(31, 70, 47)));
        LongOpenHashSet held = ServerScatter.heldColumns(box, 1);
        assertEquals((2 + 2) * (3 + 2), held.size(), "the box's 2 × 3 chunks and a ring for the margin");
    }

    @Test
    void tooManyColumnsIsRefusedBeforeBuildingTheSet() {
        ScatterArea huge = new ScatterArea.Region(Box.of(new BlockPos(0, 60, 0), new BlockPos(1023, 70, 1023)));
        EditRejected e = assertThrows(EditRejected.class, () -> ServerScatter.heldColumns(huge, 1));
        assertEquals(RejectReason.TOO_LARGE, e.reason());
        // A huge reach from one small dot is refused as fast.
        ScatterArea dot = new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(0, 0, 1)));
        assertEquals(RejectReason.TOO_LARGE,
                assertThrows(EditRejected.class, () -> ServerScatter.heldColumns(dot, 1 << 16)).reason());
    }

    /** Chunks the player may not modify at all, and chunks wholly outside the border, are not held; mixed ones are. */
    @Test
    void unmodifiableChunksAreNotHeld() {
        LongOpenHashSet held = new LongOpenHashSet();
        for (int cx = 0; cx < 4; cx++) {
            for (int cz = 0; cz < 2; cz++) held.add(ColumnPlan.pack(cx, cz));
        }
        long[] half = new long[4];
        half[0] = -1L;
        half[1] = -1L;
        Set<Long> asked = new java.util.HashSet<>();
        ServerScatter.dropUnmodifiable(held, -NO_BORDER, 3 * 16 + 8, -NO_BORDER, NO_BORDER, (cx, cz) -> {
            asked.add(ColumnPlan.pack(cx, cz));
            if (cx == 1 && cz == 0) return ChunkPermit.DENY;
            if (cx == 2 && cz == 0) return new ChunkPermit.Columns(half);
            if (cx == 2 && cz == 1) return null;
            return ChunkPermit.ALLOW;
        });
        assertEquals(columns(ColumnPlan.pack(0, 0), ColumnPlan.pack(0, 1), ColumnPlan.pack(1, 1), ColumnPlan.pack(2, 0),
                ColumnPlan.pack(3, 0), ColumnPlan.pack(3, 1)), held);

        // Wholly outside the border: dropped without asking for a permit.
        LongOpenHashSet outside = columns(ColumnPlan.pack(5, 0), ColumnPlan.pack(0, 0));
        asked.clear();
        ServerScatter.dropUnmodifiable(outside, -NO_BORDER, 80, -NO_BORDER, NO_BORDER, (cx, cz) -> {
            asked.add(ColumnPlan.pack(cx, cz));
            return ChunkPermit.ALLOW;
        });
        assertEquals(columns(ColumnPlan.pack(0, 0)), outside);
        assertEquals(Set.of(ColumnPlan.pack(0, 0)), asked);
    }

    @Test
    void theHoldBudgetDrainsRefillsAndOverdrawsAtMostOneBudget() {
        SculptoryConfig.ScatterConfig config = new SculptoryConfig.ScatterConfig();
        config.holdBudgetSeconds = 30;
        config.holdRefillShare = 0.5;
        HoldBudgets budgets = new HoldBudgets(config);
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        long s = 1_000_000_000L, t = 1_000L * s;
        assertEquals(30 * s, budgets.left(player, t), "a new player has a full budget");

        budgets.charge(player, 20 * s, t);
        assertEquals(10 * s, budgets.left(player, t));
        assertEquals(20 * s, budgets.left(player, t + 20 * s), "refills at half a second per second");
        assertEquals(30 * s, budgets.left(player, t + 1000 * s), "never above the budget");
        assertEquals(30 * s, budgets.left(other, t), "budgets are per player");

        // Re-previewing all the time: every hold is charged, the budget runs out and refuses.
        budgets.charge(player, 25 * s, t + 1 * s);
        long left = budgets.left(player, t + 1 * s);
        assertEquals(10 * s + s / 2 - 25 * s, left);
        assertTrue(left <= 0);
        long refill = budgets.refillNanos(left);
        assertTrue(budgets.left(player, t + 1 * s + refill) > 0, "not refilled after " + refill);
        assertTrue(budgets.left(player, t + 1 * s + refill - 2) <= 0, "refilled early");

        // One very long hold overdraws by at most one budget.
        budgets.charge(other, 1000 * s, t);
        assertEquals(-30 * s, budgets.left(other, t));
        assertEquals(60 * s + 2, budgets.refillNanos(-30 * s), "a full budget of debt takes 60 s to repay");

        // Full budgets are forgotten, except for a player whose preview is in flight.
        budgets.sweep(t + 10_000 * s, id -> id.equals(other));
        assertEquals(1, budgets.size());
        budgets.sweep(t + 10_000 * s, id -> false);
        assertEquals(0, budgets.size());
    }
}
