package dev.sculptory.fabric.client.editor.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

/** The world-free parts of {@link WorldRaycaster}: range and the plane fallback. */
class WorldRaycasterTest {
    @Test
    void rangeFollowsRenderDistanceUpTo512() {
        assertEquals(16, WorldRaycaster.maxDistance(0));
        assertEquals(208, WorldRaycaster.maxDistance(12));
        assertEquals(512, WorldRaycaster.maxDistance(31));
        assertEquals(512, WorldRaycaster.maxDistance(32));
        assertEquals(16, WorldRaycaster.maxDistance(-4));
    }

    @Test
    void planeFallbackFromAboveHitsTheTopOfTheBlockBelow() {
        Ray ray = new Ray(0.5, 80, 0.5, 1, -1, 0);
        WorldRaycaster.Hit hit = WorldRaycaster.planeHit(ray, 64, 100).orElseThrow();
        assertEquals(WorldRaycaster.Hit.Kind.PLANE, hit.kind());
        assertTrue(hit.missed());
        assertEquals(new BlockPos(16, 63, 0), hit.blockPos());
        assertEquals(Direction.UP, hit.face());
        assertEquals(16.5, hit.pos().x, 1e-9);
        assertEquals(64, hit.pos().y, 1e-9);
        assertEquals(16 * Math.sqrt(2), hit.distance(), 1e-9);
    }

    @Test
    void planeFallbackFromBelowHitsTheBottomOfTheBlockAbove() {
        WorldRaycaster.Hit hit = WorldRaycaster.planeHit(new Ray(-3.5, 10, 2.5, 0, 1, 0), 64, 100).orElseThrow();
        assertEquals(new BlockPos(-4, 64, 2), hit.blockPos());
        assertEquals(Direction.DOWN, hit.face());
    }

    @Test
    void planeFallbackNeedsAReachablePlane() {
        assertTrue(WorldRaycaster.planeHit(new Ray(0, 80, 0, 1, 0, 0), 64, 100).isEmpty(), "parallel");
        assertTrue(WorldRaycaster.planeHit(new Ray(0, 80, 0, 0, 1, 0), 64, 100).isEmpty(), "behind");
        assertTrue(WorldRaycaster.planeHit(new Ray(0, 80, 0, 0, -1, 0), 64, 10).isEmpty(), "beyond range");
    }

    @Test
    void fallbackKeepsRealHits() {
        Ray ray = new Ray(0.5, 80, 0.5, 0, -1, 0);
        WorldRaycaster.Hit block = new WorldRaycaster.Hit(
                WorldRaycaster.Hit.Kind.BLOCK, new BlockPos(0, 70, 0), Direction.UP, new Vec3d(0.5, 71, 0.5), 9);
        assertSame(block, WorldRaycaster.withPlaneFallback(block, ray, 64, 100));

        WorldRaycaster.Hit miss = new WorldRaycaster.Hit(
                WorldRaycaster.Hit.Kind.MISS, new BlockPos(0, -20, 0), Direction.UP, new Vec3d(0.5, -20, 0.5), 100);
        assertEquals(WorldRaycaster.Hit.Kind.PLANE, WorldRaycaster.withPlaneFallback(miss, ray, 64, 100).kind());
        assertSame(miss, WorldRaycaster.withPlaneFallback(miss, ray, 90, 100), "plane behind the ray");
    }
}
