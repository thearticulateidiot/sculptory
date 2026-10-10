package dev.sculptory.fabric.client.editor.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class SurfaceSamplerTest {
    /** Fake terrain: solid from the world bottom up to a per-column height, plus extra floating blocks. */
    private static final class FakeTerrain implements TerrainProbe {
        private final IntBinaryOperator groundHeight;
        private final Map<Long, Set<Integer>> floating = new HashMap<>();
        private final int bottomY;
        private final int topY;
        int probes;
        /** The height hint every column gives ({@link #topY} when unset). */
        int hint = Integer.MAX_VALUE;

        FakeTerrain(int bottomY, int topY, IntBinaryOperator groundHeight) {
            this.bottomY = bottomY;
            this.topY = topY;
            this.groundHeight = groundHeight;
        }

        FakeTerrain block(int x, int y, int z) {
            floating.computeIfAbsent(key(x, z), k -> new HashSet<>()).add(y);
            return this;
        }

        private static long key(int x, int z) {
            return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
        }

        @Override
        public boolean isTerrainSolid(int x, int y, int z) {
            probes++;
            if (y < bottomY || y >= topY) {
                throw new AssertionError("probe outside the world: y=" + y);
            }
            return y <= groundHeight.applyAsInt(x, z) || floating.getOrDefault(key(x, z), Set.of()).contains(y);
        }

        @Override
        public int bottomY() {
            return bottomY;
        }

        @Override
        public int topY() {
            return topY;
        }

        @Override
        public int heightHint(int x, int z) {
            return Math.min(topY, hint);
        }
    }

    private static FakeTerrain flat(int height) {
        return new FakeTerrain(-64, 320, (x, z) -> height);
    }

    @Test
    void flatGroundFillsTheDiscOnly() {
        SurfaceSampler.Samples samples = SurfaceSampler.scan(flat(63), 10, 63, -20, 3);
        assertEquals(3, samples.radius());
        assertEquals(7, samples.side());
        int inDisc = 0;
        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
                if (SurfaceSampler.inDisc(dx, dz, 3)) {
                    inDisc++;
                    assertEquals(63, samples.heightAt(dx, dz));
                } else {
                    assertEquals(SurfaceSampler.NONE, samples.heightAt(dx, dz));
                }
            }
        }
        assertEquals(inDisc, samples.surfaceCount());
        assertEquals(SurfaceSampler.NONE, samples.heightAt(3, 3), "corner is outside the disc");
        assertEquals(SurfaceSampler.NONE, samples.heightAt(4, 0), "outside the stored square");
        assertEquals(63, samples.heightAtWorld(13, -20));
    }

    @Test
    void slopeIsFollowedPerColumn() {
        FakeTerrain slope = new FakeTerrain(-64, 320, (x, z) -> 60 + x);
        SurfaceSampler.Samples samples = SurfaceSampler.scan(slope, 0, 60, 0, 4);
        for (int dx = -4; dx <= 4; dx++) {
            assertEquals(60 + dx, samples.heightAt(dx, 0));
        }
    }

    @Test
    void scanStartsAtHitPlusRadiusPlusEight() {
        int hitY = 63;
        int radius = 2;
        int top = hitY + radius + SurfaceSampler.SCAN_ABOVE;
        FakeTerrain terrain = flat(63).block(0, top + 1, 0).block(1, top, 0).block(0, 66, 1);

        SurfaceSampler.Samples samples = SurfaceSampler.scan(terrain, 0, hitY, 0, radius);
        assertEquals(63, samples.heightAt(0, 0), "a block above the scan window is ignored");
        assertEquals(top, samples.heightAt(1, 0), "the scan window's top block counts");
        assertEquals(66, samples.heightAt(0, 1), "an overhang is the first solid from above");
    }

    @Test
    void columnsWithoutSurfaceInRangeAreNone() {
        int hitY = 63;
        int radius = 2;
        int bottom = hitY - radius - SurfaceSampler.SCAN_BELOW;
        FakeTerrain terrain = new FakeTerrain(-64, 320, (x, z) -> x == 0 ? bottom : bottom - 1);
        SurfaceSampler.Samples samples = SurfaceSampler.scan(terrain, 0, hitY, 0, radius);
        assertEquals(bottom, samples.heightAt(0, 0), "the scan window's bottom block counts");
        assertEquals(SurfaceSampler.NONE, samples.heightAt(1, 0), "below the scan window");
        assertEquals(5, samples.surfaceCount(), "only the x = 0 column (5 cells in the disc) has a surface");
    }

    @Test
    void scanStaysInsideTheWorldHeightRange() {
        FakeTerrain nearBottom = new FakeTerrain(0, 16, (x, z) -> -100);
        SurfaceSampler.Samples samples = SurfaceSampler.scan(nearBottom, 0, 2, 0, 5);
        assertEquals(0, samples.surfaceCount());

        FakeTerrain nearTop = new FakeTerrain(0, 16, (x, z) -> 15);
        assertEquals(15, SurfaceSampler.scan(nearTop, 0, 14, 0, 5).heightAt(0, 0));
    }

    @Test
    void radiusIsClamped() {
        assertEquals(SurfaceSampler.MAX_RADIUS, SurfaceSampler.scan(flat(0), 0, 0, 0, 50).radius());
        SurfaceSampler.Samples single = SurfaceSampler.scan(flat(5), 0, 5, 0, -3);
        assertEquals(0, single.radius());
        assertEquals(1, single.surfaceCount());
        assertEquals(5, single.heightAt(0, 0));
    }

    @Test
    void discMembershipIsRounded() {
        assertTrue(SurfaceSampler.inDisc(0, 0, 0));
        assertFalse(SurfaceSampler.inDisc(1, 0, 0));
        assertTrue(SurfaceSampler.inDisc(1, 1, 1));
        assertTrue(SurfaceSampler.inDisc(2, 1, 2));
        assertFalse(SurfaceSampler.inDisc(2, 2, 2));
    }

    @Test
    void cacheIsKeyedByHitRadiusStampAndProbe() {
        FakeTerrain terrain = flat(40);
        SurfaceSampler sampler = new SurfaceSampler();
        SurfaceSampler.Samples first = sampler.sample(terrain, 0, 40, 0, 4, 7);
        int probesAfterFirst = terrain.probes;
        assertTrue(probesAfterFirst > 0);

        assertSame(first, sampler.sample(terrain, 0, 40, 0, 4, 7));
        assertEquals(probesAfterFirst, terrain.probes, "a cache hit does not touch the world");

        assertNotSame(first, sampler.sample(terrain, 0, 40, 0, 4, 8), "block change stamp");
        SurfaceSampler.Samples moved = sampler.sample(terrain, 1, 40, 0, 4, 8);
        assertEquals(1, moved.centerX(), "hit block");
        assertEquals(5, sampler.sample(terrain, 1, 40, 0, 5, 8).radius(), "radius");
        SurfaceSampler.Samples other = sampler.sample(flat(40), 1, 40, 0, 5, 8);
        assertEquals(40, other.heightAt(0, 0), "another probe rescans");

        SurfaceSampler.Samples cached = sampler.sample(terrain, 2, 40, 0, 5, 8);
        sampler.invalidate();
        assertNotSame(cached, sampler.sample(terrain, 2, 40, 0, 5, 8));
    }

    /**
     * The Heightmap brush's square: every column up to radius 32 (corners included, unlike the disc), then one column per
     * cell of a few columns, at most 65 × 65 cells, each column of a cell reading its middle column's surface; a probe's
     * height hint lets each scan start at the surface, so a radius-128 square takes a few probes a cell.
     */
    @Test
    void theHeightmapSquareIsSampledInAtMost65By65Cells() {
        assertEquals(1, SurfaceSampler.squareStep(32));
        assertEquals(2, SurfaceSampler.squareStep(64));
        assertEquals(4, SurfaceSampler.squareStep(128));
        FakeTerrain terrain = new FakeTerrain(-64, 320, (x, z) -> 60 + Math.floorMod(x + 2 * z, 9));
        terrain.hint = 70;
        SurfaceSampler.Samples small = SurfaceSampler.scanSquare(terrain, 0, 61, 0, 32);
        assertEquals(1, small.step());
        assertEquals(60 + Math.floorMod(32 + 64, 9), small.heightAt(32, 32), "the corner is sampled");
        assertEquals(SurfaceSampler.NONE, small.heightAt(33, 0));
        terrain.probes = 0;
        SurfaceSampler.Samples wide = SurfaceSampler.scanSquare(terrain, 0, 61, 0, 128);
        assertEquals(4, wide.step());
        assertTrue(terrain.probes <= 65 * 65 * 12, "probes " + terrain.probes);
        for (int dz = -128; dz <= 128; dz += 9) {
            for (int dx = -128; dx <= 128; dx++) {
                int mx = Math.min(256, (dx + 128) / 4 * 4 + 2) - 128, mz = Math.min(256, (dz + 128) / 4 * 4 + 2) - 128;
                assertEquals(60 + Math.floorMod(mx + 2 * mz, 9), wide.heightAt(dx, dz), dx + "," + dz);
            }
        }
        assertEquals(wide.heightAt(126, 126), wide.heightAt(125, 125), "one cell");
        assertEquals(SurfaceSampler.NONE, wide.heightAt(129, 0));
    }
}
