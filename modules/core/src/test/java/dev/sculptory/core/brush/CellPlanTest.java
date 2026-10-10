package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.testing.FakeWorld;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The plan's fluid refill lookup stays within about 16 ints a planned cell however its cells are spread: a few deep
 * columns far apart (a symmetric stamp of one bright pixel per chunk) take a sparse index, not a block per chunk column
 * over their whole height; and the refill comes out the same either way.
 */
class CellPlanTest {
    private final BrushFixture f = new BrushFixture();

    private CellPlan plan(FakeWorld world) {
        return new CellPlan(world, null, new CellPlan.Layout() {
            @Override
            public long key(int x, int y, int z) {
                return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
            }

            @Override
            public boolean loaded(int x, int z) {
                return true;
            }
        });
    }

    /**
     * One column 128 cells deep in each of 400 chunk columns, under a water source on every other one: about 51,000
     * cells. A block per chunk column over its y range would take 256 × 128 ints each (13 M in all, 256 a cell); the
     * lookup stays within 16 ints a cell, and each column under water fills with it, the others with air.
     */
    @Test
    void aFewDeepColumnsFarApartTakeASparseLookup() {
        FakeWorld world = new FakeWorld(f.states);
        CellPlan cells = plan(world);
        Map<Long, Boolean> wet = new HashMap<>();
        for (int i = 0; i < 20; i++) {
            for (int j = 0; j < 20; j++) {
                int x = 16 * i + 3, z = 16 * j + 5;
                boolean water = (i + j) % 2 == 0;
                if (water) world.set(x, 128, z, f.water);
                wet.put(((long) x << 32) | (z & 0xFFFFFFFFL), water);
                for (int y = 127; y >= 0; y--) {
                    world.set(x, y, z, f.stone);
                    cells.plan(x, y, z, CellPlan.FILL, f.stone);
                }
            }
        }
        int count = cells.size();
        assertEquals(400 * 128, count);
        while (!cells.resolve(Long.MIN_VALUE)) {
            // a piece at a time
        }
        assertTrue(cells.lookupInts() <= 16L * count, "the lookup took " + cells.lookupInts() + " ints for " + count
                + " cells");
        Map<Long, Integer> filled = new HashMap<>();
        cells.emit((x, y, z, h) -> {
            long key = ((long) x << 32) | (z & 0xFFFFFFFFL);
            assertEquals(wet.get(key) ? f.water : f.air, h, "cell " + x + "," + y + "," + z);
            filled.merge(key, 1, Integer::sum);
        });
        assertEquals(400, filled.size());
        filled.values().forEach(n -> assertEquals(128, n));
    }

    /** A deep cut whose columns are full stays on the dense lookups: per chunk column, or over the whole bounds. */
    @Test
    void aSolidCutStaysDense() {
        FakeWorld world = new FakeWorld(f.states);
        CellPlan cells = plan(world);
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                for (int y = 100; y >= 0; y--) cells.plan(x, y, z, CellPlan.FILL, f.stone);
            }
        }
        cells.resolve(Long.MAX_VALUE);
        long count = cells.size();
        assertTrue(cells.lookupInts() >= count && cells.lookupInts() <= 2 * count, "dense: " + cells.lookupInts());
    }
}
