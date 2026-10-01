package dev.sculptory.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.testing.FakeStateSpace;
import org.junit.jupiter.api.Test;

class PatternTest {
    private static final int ROCK = 10, DIRT = 20, PROPERTY = 30;

    private final FakeStateSpace states = new FakeStateSpace();
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int water = states.state("minecraft:water[level=0]");
    private final int flowing = states.state("minecraft:water[level=3]");
    private final int lava = states.state("minecraft:lava[level=0]");
    private final int dryStairs = states.state("minecraft:oak_stairs[facing=east,half=top]");
    private final int wetStairs = states.state("minecraft:oak_stairs[facing=east,half=top,waterlogged=true]");
    private final int drySlab = states.state("minecraft:oak_slab[type=top]");
    private final int wetSlab = states.state("minecraft:oak_slab[type=top,waterlogged=true]");
    private final int wetPickle = states.state("minecraft:sea_pickle[pickles=2]");
    private final int dryPickle = states.state("minecraft:sea_pickle[pickles=2,waterlogged=false]");
    private final int seagrass = states.state("minecraft:seagrass");
    private final int kelp = states.state("minecraft:kelp[age=3]");
    private final int bubbles = states.state("minecraft:bubble_column");
    private final int grass = states.state("minecraft:short_grass");

    private static Pattern.Weighted palette(long seed) {
        return new Pattern.Weighted(new int[] {ROCK, DIRT, PROPERTY}, new int[] {3, 2, 1}, seed);
    }

    private int apply(Pattern pattern, int before) {
        return pattern.apply(states, 3, 64, -7, before);
    }

    @Test
    void singleIgnoresPositionAndState() {
        Pattern single = new Pattern.Single(4);
        assertEquals(4, single.apply(states, 0, 0, 0, 9));
        assertEquals(4, single.apply(states, -5, 300, 12, 0));
        assertEquals(4, single.apply(null, -5, 300, 12, 0), "a position pattern reads no state space");
        assertThrows(IllegalArgumentException.class, () -> new Pattern.Single(-1));
    }

    /** Literal vectors from the old SurfacePalettes test: the port must pick exactly what it picked. */
    @Test
    void weightedMatchesLegacyVectors() {
        record Vector(long seed, int x, int y, int z, int state) {}
        Vector[] vectors = {
            new Vector(0, 8, 200, 8, ROCK),
            new Vector(0, -8, -64, -8, PROPERTY),
            new Vector(0, Integer.MIN_VALUE, 0, Integer.MAX_VALUE, ROCK),
            new Vector(-42, 8, 200, 8, ROCK),
            new Vector(-42, -8, -64, -8, PROPERTY),
            new Vector(-42, Integer.MIN_VALUE, 0, Integer.MAX_VALUE, ROCK),
            new Vector(Long.MIN_VALUE, 8, 200, 8, ROCK),
            new Vector(Long.MIN_VALUE, -8, -64, -8, DIRT),
            new Vector(Long.MIN_VALUE, Integer.MIN_VALUE, 0, Integer.MAX_VALUE, ROCK),
            new Vector(Long.MAX_VALUE, 8, 200, 8, ROCK),
            new Vector(Long.MAX_VALUE, -8, -64, -8, DIRT),
            new Vector(Long.MAX_VALUE, Integer.MIN_VALUE, 0, Integer.MAX_VALUE, ROCK),
        };
        for (Vector v : vectors) {
            assertEquals(v.state(), palette(v.seed()).apply(states, v.x(), v.y(), v.z(), 0), v.toString());
        }
        Pattern reordered = new Pattern.Weighted(new int[] {DIRT, ROCK, PROPERTY}, new int[] {2, 3, 1}, 0);
        assertNotEquals(palette(0).apply(states, 8, 200, 8, 0), reordered.apply(states, 8, 200, 8, 0),
                "entry order participates");
    }

    @Test
    void weightedIsDeterministicAndIgnoresBefore() {
        Pattern a = palette(1234), b = palette(1234);
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                int picked = a.apply(states, x, 70, z, 0);
                assertEquals(picked, a.apply(states, x, 70, z, 999));
                assertEquals(picked, b.apply(null, x, 70, z, 0));
            }
        }
    }

    @Test
    void weightedFollowsWeights() {
        Pattern p = palette(99);
        int[] counts = new int[3];
        for (int x = 0; x < 200; x++) {
            for (int z = 0; z < 150; z++) {
                int state = p.apply(states, x, 64, z, 0);
                counts[state / 10 - 1]++;
            }
        }
        double total = 200 * 150;
        assertTrue(Math.abs(counts[0] / total - 0.5) < 0.02, "rock ~1/2");
        assertTrue(Math.abs(counts[1] / total - 1 / 3.0) < 0.02, "dirt ~1/3");
        assertTrue(Math.abs(counts[2] / total - 1 / 6.0) < 0.02, "property ~1/6");
    }

    @Test
    void weightedValidatesAndCopies() {
        assertThrows(IllegalArgumentException.class, () -> new Pattern.Weighted(new int[] {1}, new int[] {1, 2}, 0));
        assertThrows(IllegalArgumentException.class, () -> new Pattern.Weighted(new int[0], new int[0], 0));
        assertThrows(IllegalArgumentException.class, () -> new Pattern.Weighted(new int[] {1}, new int[] {0}, 0));
        assertThrows(IllegalArgumentException.class, () -> new Pattern.Weighted(new int[] {1}, new int[] {1001}, 0));
        assertThrows(IllegalArgumentException.class, () -> new Pattern.Weighted(new int[] {1, 1}, new int[] {1, 1}, 0));
        assertThrows(IllegalArgumentException.class, () -> new Pattern.Weighted(new int[] {-1}, new int[] {1}, 0));
        int[] states = {1, 2};
        Pattern.Weighted w = new Pattern.Weighted(states, new int[] {1, 1}, 5);
        states[0] = 7;
        w.states()[1] = 7;
        assertEquals(1, w.state(0));
        assertEquals(2, w.state(1));
        assertEquals(w, new Pattern.Weighted(new int[] {1, 2}, new int[] {1, 1}, 5));
        assertEquals(w.hashCode(), new Pattern.Weighted(new int[] {1, 2}, new int[] {1, 1}, 5).hashCode());
        assertNotEquals(w, new Pattern.Weighted(new int[] {1, 2}, new int[] {1, 1}, 6));
    }

    // ---------------------------------------------------------------- fluid patterns

    @Test
    void fluidSourcesAreStillWaterAndLavaOnly() {
        assertTrue(Pattern.isFluidSource(states, water));
        assertTrue(Pattern.isFluidSource(states, lava));
        assertFalse(Pattern.isFluidSource(states, flowing), "flowing water is not a source");
        assertFalse(Pattern.isFluidSource(states, wetStairs), "a waterlogged block holds water but is not a fluid");
        assertFalse(Pattern.isFluidSource(states, seagrass));
        assertFalse(Pattern.isFluidSource(states, stone));
        assertFalse(Pattern.isFluidSource(states, air));
        assertFalse(Pattern.isFluidSource(states, -1));
        assertFalse(Pattern.isFluidSource(states, states.size()));
        assertThrows(IllegalArgumentException.class, () -> new Pattern.Waterlog(-1));
    }

    /** Water into air, waterlogging what can be waterlogged, everything else exactly as it was (the no-op convention). */
    @Test
    void waterlogFillsAirAndWaterlogsTheRest() {
        Pattern waterlog = new Pattern.Waterlog(water);
        assertEquals(water, apply(waterlog, air));
        assertEquals(wetStairs, apply(waterlog, dryStairs));
        assertEquals(wetStairs, apply(waterlog, wetStairs), "already wet: unchanged");
        assertEquals(wetSlab, apply(waterlog, drySlab));
        assertEquals(wetPickle, apply(waterlog, dryPickle));
        assertEquals(stone, apply(waterlog, stone), "a solid block is left alone");
        assertEquals(grass, apply(waterlog, grass), "a replaceable plant is not air and is left alone");
        assertEquals(water, apply(waterlog, water));
        assertEquals(flowing, apply(waterlog, flowing), "an existing fluid is left alone, whatever its level");
        assertEquals(lava, apply(waterlog, lava));
        assertEquals(seagrass, apply(waterlog, seagrass), "seagrass has no waterlogged property");
        assertEquals(new Pattern.Waterlog(water), waterlog);
        assertNotEquals(new Pattern.Waterlog(lava), waterlog);
    }

    /** Lava cannot waterlog: it fills air and leaves every other block alone. */
    @Test
    void lavaWaterlogsNothing() {
        Pattern lavalog = new Pattern.Waterlog(lava);
        assertEquals(lava, apply(lavalog, air));
        assertEquals(dryStairs, apply(lavalog, dryStairs));
        assertEquals(drySlab, apply(lavalog, drySlab));
        assertEquals(wetStairs, apply(lavalog, wetStairs), "nor does it dry anything");
        assertEquals(stone, apply(lavalog, stone));
        assertEquals(water, apply(lavalog, water));
    }

    /** Fluids and waterlogging go, water plants with them; everything else exactly as it was. */
    @Test
    void dryRemovesFluidsWaterloggingAndWaterPlants() {
        Pattern dry = new Pattern.Dry();
        assertEquals(air, apply(dry, water));
        assertEquals(air, apply(dry, flowing));
        assertEquals(air, apply(dry, lava));
        assertEquals(dryStairs, apply(dry, wetStairs));
        assertEquals(drySlab, apply(dry, wetSlab));
        assertEquals(dryPickle, apply(dry, wetPickle));
        assertEquals(air, apply(dry, seagrass), "seagrass cannot exist dry");
        assertEquals(air, apply(dry, kelp));
        assertEquals(air, apply(dry, bubbles), "a bubble column holds water");
        assertEquals(dryStairs, apply(dry, dryStairs), "already dry: unchanged");
        assertEquals(stone, apply(dry, stone));
        assertEquals(grass, apply(dry, grass));
        assertEquals(air, apply(dry, air));
        assertEquals(new Pattern.Dry(), dry);
    }

    /** The fluid patterns depend on the state alone: the position never changes their answer. */
    @Test
    void fluidPatternsIgnoreThePosition() {
        Pattern waterlog = new Pattern.Waterlog(water);
        Pattern dry = new Pattern.Dry();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                assertEquals(wetStairs, waterlog.apply(states, x, 200 * x, z, dryStairs));
                assertEquals(dryStairs, dry.apply(states, x, 200 * x, z, wetStairs));
            }
        }
    }
}
