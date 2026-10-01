package dev.sculptory.fabric.client.editor.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.SlabType;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.RaycastContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * "Aim at water and lava" in {@link WorldRaycaster}, over a small in-memory world of real block states: with
 * {@link WorldRaycaster.Fluids#NONE} rays pass through fluids exactly as before, with {@link WorldRaycaster.Fluids#ANY}
 * they stop where they enter water or lava, at that block's fluid height.
 */
class WorldRaycasterFluidsTest {
    private static final double SOURCE_HEIGHT = 8 / 9.0;
    private static final double EPS = 1e-6;
    private static final WorldRaycaster.Mode TERRAIN = WorldRaycaster.Mode.TERRAIN;
    private static final WorldRaycaster.Mode BLOCK = WorldRaycaster.Mode.BLOCK;
    private static final WorldRaycaster.Fluids OFF = WorldRaycaster.Fluids.NONE;
    private static final WorldRaycaster.Fluids ON = WorldRaycaster.Fluids.ANY;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    /** Block states by position, air elsewhere; no block entities. */
    private static final class TestWorld implements BlockView {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        TestWorld set(int x, int y, int z, BlockState state) {
            blocks.put(new BlockPos(x, y, z), state);
            return this;
        }

        TestWorld fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        set(x, y, z, state);
                    }
                }
            }
            return this;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.getDefaultState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getBottomY() {
            return -64;
        }
    }

    /** The same blocks with every fluid removed, to compare the fluid path's block hits with vanilla's. */
    private record NoFluids(TestWorld blocks) implements BlockView {
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blocks.getBlockState(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.getDefaultState();
        }

        @Override
        public int getHeight() {
            return blocks.getHeight();
        }

        @Override
        public int getBottomY() {
            return blocks.getBottomY();
        }
    }

    private static BlockState stone() {
        return Blocks.STONE.getDefaultState();
    }

    private static BlockState water() {
        return Blocks.WATER.getDefaultState();
    }

    /** Flowing water of block level 1-7: fluid level {@code 8 - blockLevel}, {@code (8 - blockLevel) / 9} high. */
    private static BlockState flowingWater(int blockLevel) {
        return Blocks.WATER.getDefaultState().with(FluidBlock.LEVEL, blockLevel);
    }

    /** A stone floor at y 60 and a pool of still water on it, y 61-63, over x and z -8..8. */
    private static TestWorld pool() {
        return new TestWorld().fill(-8, 60, -8, 8, 60, 8, stone()).fill(-8, 61, -8, 8, 63, 8, water());
    }

    private static WorldRaycaster.Hit cast(BlockView world, Ray ray, WorldRaycaster.Mode mode, WorldRaycaster.Fluids fluids) {
        return new WorldRaycaster().raycast(world, null, ray, mode, fluids, 100, 0);
    }

    private static void assertHit(WorldRaycaster.Hit hit, int x, int y, int z, Direction face, double hitY) {
        assertEquals(WorldRaycaster.Hit.Kind.BLOCK, hit.kind(), "kind of " + hit);
        assertEquals(new BlockPos(x, y, z), hit.blockPos(), "block of " + hit);
        assertEquals(face, hit.face(), "face of " + hit);
        assertEquals(hitY, hit.pos().y, EPS, "hit height of " + hit);
    }

    @Test
    void theToggleChoosesTheFluidHandling() {
        assertSame(OFF, WorldRaycaster.Fluids.of(false));
        assertSame(ON, WorldRaycaster.Fluids.of(true));
    }

    @Test
    void offTheRayPassesThroughWaterToTheGround() {
        Ray down = new Ray(0.5, 80, 0.5, 0.2, -1, 0.1);
        for (WorldRaycaster.Mode mode : WorldRaycaster.Mode.values()) {
            assertHit(cast(pool(), down, mode, OFF), 4, 60, 2, Direction.UP, 61);
        }
    }

    @Test
    void onTheRayStopsAtTheWaterSurface() {
        Ray down = new Ray(0.5, 80, 0.5, 0.2, -1, 0.1);
        for (WorldRaycaster.Mode mode : WorldRaycaster.Mode.values()) {
            WorldRaycaster.Hit hit = cast(pool(), down, mode, ON);
            assertHit(hit, 3, 63, 2, Direction.UP, 63 + SOURCE_HEIGHT);
            assertEquals(new Vec3d(0.5, 80, 0.5).distanceTo(hit.pos()), hit.distance(), EPS);
        }
    }

    @Test
    void aSourceUnderMoreFluidFillsItsWholeBlock() {
        // Along x at 0.95 above the blocks' base: over a lone source (8/9 high), into a source with water above it.
        TestWorld world = new TestWorld()
                .set(3, 64, 0, water())
                .set(6, 64, 0, water()).set(6, 65, 0, water())
                .set(10, 64, 0, stone());
        WorldRaycaster.Hit hit = cast(world, new Ray(0.5, 64.95, 0.5, 1, 0, 0), BLOCK, ON);
        assertHit(hit, 6, 64, 0, Direction.WEST, 64.95);
        assertEquals(6, hit.pos().x, EPS);
        assertHit(cast(world, new Ray(0.5, 64.95, 0.5, 1, 0, 0), BLOCK, OFF), 10, 64, 0, Direction.WEST, 64.95);
    }

    @Test
    void flowingWaterStopsTheRayAtItsOwnHeight() {
        // Block level 4 is fluid level 4: 4/9 of a block, with air above it.
        TestWorld world = new TestWorld().fill(0, 60, 0, 6, 60, 0, stone()).set(3, 61, 0, flowingWater(4));
        assertHit(cast(world, new Ray(3.5, 70, 0.5, 0.01, -1, 0), TERRAIN, ON), 3, 61, 0, Direction.UP, 61 + 4 / 9.0);

        Ray low = new Ray(0.5, 61.3, 0.5, 1, 0, 0);
        WorldRaycaster.Hit side = cast(world, low, TERRAIN, ON);
        assertHit(side, 3, 61, 0, Direction.WEST, 61.3);
        assertEquals(3, side.pos().x, EPS);
        assertEquals(WorldRaycaster.Hit.Kind.MISS, cast(world, new Ray(0.5, 61.6, 0.5, 1, 0, 0), TERRAIN, ON).kind(),
                "above the flowing water's surface the ray passes over it");
        assertEquals(WorldRaycaster.Hit.Kind.MISS, cast(world, low, TERRAIN, OFF).kind(), "off, flowing water never stops it");
    }

    @Test
    void lavaStopsTheRayToo() {
        TestWorld world = new TestWorld().fill(-2, 60, -2, 2, 60, 2, stone())
                .fill(-2, 61, -2, 2, 61, 2, Blocks.LAVA.getDefaultState());
        Ray down = new Ray(0.5, 75, 0.5, 0.05, -1, 0.05);
        assertHit(cast(world, down, TERRAIN, ON), 1, 61, 1, Direction.UP, 61 + SOURCE_HEIGHT);
        assertHit(cast(world, down, TERRAIN, OFF), 1, 60, 1, Direction.UP, 61);

        // Block level 2 is fluid level 6: 6/9 of a block.
        TestWorld flowing = new TestWorld().set(0, 61, 0, Blocks.LAVA.getDefaultState().with(FluidBlock.LEVEL, 2));
        assertHit(cast(flowing, new Ray(0.5, 70, 0.5, 0.001, -1, 0), BLOCK, ON), 0, 61, 0, Direction.UP, 61 + 6 / 9.0);
    }

    /** Vanilla's own raycast with {@code FluidHandling.ANY}, for contrast. */
    private static BlockHitResult vanillaAny(BlockView world, Ray ray) {
        Vec3d start = new Vec3d(ray.originX(), ray.originY(), ray.originZ());
        Vec3d end = new Vec3d(ray.pointX(100), ray.pointY(100), ray.pointZ(100));
        return world.raycast(new RaycastContext(start, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.ANY, ShapeContext.absent()));
    }

    @Test
    void underWaterTheRayPassesThroughTheWaterAroundTheCamera() {
        TestWorld world = pool().set(2, 70, 0, stone()).fill(8, 61, -8, 8, 63, 8, stone());
        Ray down = new Ray(0.5, 62.5, 0.5, 0.1, -1, 0);
        assertHit(cast(world, down, TERRAIN, ON), 0, 60, 0, Direction.UP, 61);
        assertTrue(vanillaAny(world, down).isInsideBlock(), "vanilla stops in the water around the camera");
        // Up through the surface: leaving the water is no hit, the block above is.
        assertHit(cast(world, new Ray(0.5, 62.5, 0.5, 0.25, 1, 0), BLOCK, ON), 2, 70, 0, Direction.DOWN, 70);
        // Sideways to the pool's wall: the faces between water blocks are no surfaces.
        assertHit(cast(world, new Ray(0.5, 62.5, 0.5, 1, 0, 0), TERRAIN, ON), 8, 62, 0, Direction.WEST, 62.5);
    }

    @Test
    void underWaterTheRayStopsAtTheNextFluidItEnters() {
        // Out of the pool's open side, through the air, into a second body of water.
        TestWorld world = pool().fill(12, 61, -2, 14, 63, 2, water());
        WorldRaycaster.Hit hit = cast(world, new Ray(0.5, 62.5, 0.5, 1, 0, 0), TERRAIN, ON);
        assertHit(hit, 12, 62, 0, Direction.WEST, 62.5);
        assertEquals(12, hit.pos().x, EPS);
    }

    @Test
    void aCameraJustAboveTheSurfaceStillHitsIt() {
        // Vanilla's fluid shape raycast first tests a point 0.1% of the ray's length ahead of its start (0.1 blocks
        // at this range). Here that point is under the surface, so vanilla would report a hit inside the water.
        Ray down = new Ray(0.5, 63.93, 0.5, 0.01, -1, 0);
        assertHit(cast(pool(), down, TERRAIN, ON), 0, 63, 0, Direction.UP, 63 + SOURCE_HEIGHT);
        BlockHitResult vanilla = vanillaAny(pool(), down);
        assertTrue(vanilla.isInsideBlock() && vanilla.getPos().y < 63.85, "vanilla: " + vanilla.getPos());
    }

    @Test
    void waterloggedBlocksShowTheirWaterWhereItIsNearer() {
        BlockState slab = Blocks.OAK_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.BOTTOM)
                .with(Properties.WATERLOGGED, true);
        TestWorld world = new TestWorld().set(0, 61, 0, slab);
        Ray down = new Ray(0.5, 70, 0.5, 0.001, -1, 0);
        assertHit(cast(world, down, BLOCK, ON), 0, 61, 0, Direction.UP, 61 + SOURCE_HEIGHT);
        assertHit(cast(world, down, BLOCK, OFF), 0, 61, 0, Direction.UP, 61.5);

        // Where the block reaches above its water (a stair's upper step, on its north half), the block is hit.
        BlockState stair = Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH)
                .with(Properties.WATERLOGGED, true);
        TestWorld stairs = new TestWorld().set(0, 61, 0, stair);
        assertHit(cast(stairs, new Ray(0.5, 70, 0.2, 0.001, -1, 0), BLOCK, ON), 0, 61, 0, Direction.UP, 62);
        assertHit(cast(stairs, new Ray(0.5, 70, 0.8, 0.001, -1, 0), BLOCK, ON), 0, 61, 0, Direction.UP, 61 + SOURCE_HEIGHT);
    }

    @Test
    void plantsUnderWaterAreBehindTheSurface() {
        TestWorld world = pool().set(0, 61, 0, Blocks.SEAGRASS.getDefaultState());
        Ray down = new Ray(0.5, 70, 0.5, 0.001, -1, 0);
        assertHit(cast(world, down, BLOCK, ON), 0, 63, 0, Direction.UP, 63 + SOURCE_HEIGHT);
        assertHit(cast(world, down, BLOCK, OFF), 0, 61, 0, Direction.UP, 61.75);
    }

    @Test
    void offThePickIsVanillasRaycastAndOnBlocksAreHitLikeVanilla() {
        // A mixed scene: terrain, a pool, waterlogged stairs and slabs, plants, a fence.
        TestWorld world = pool()
                .fill(-8, 64, 4, 8, 66, 8, stone())
                .set(1, 62, 1, Blocks.OAK_STAIRS.getDefaultState().with(Properties.WATERLOGGED, true))
                .set(-2, 63, 2, Blocks.OAK_SLAB.getDefaultState().with(Properties.WATERLOGGED, true))
                .set(3, 61, -3, Blocks.SEAGRASS.getDefaultState())
                .set(2, 67, 5, Blocks.POPPY.getDefaultState())
                .set(-4, 64, -4, Blocks.OAK_FENCE.getDefaultState());
        Random random = new Random(42);
        for (int i = 0; i < 2_000; i++) {
            Vec3d start = new Vec3d(random.nextDouble() * 24 - 12, 58 + random.nextDouble() * 16, random.nextDouble() * 24 - 12);
            Vec3d end = start.add(random.nextGaussian() * 20, random.nextGaussian() * 20, random.nextGaussian() * 20);
            for (WorldRaycaster.Mode mode : WorldRaycaster.Mode.values()) {
                RaycastContext.ShapeType shape = mode == TERRAIN ? RaycastContext.ShapeType.COLLIDER : RaycastContext.ShapeType.OUTLINE;
                RaycastContext none = new RaycastContext(start, end, shape, RaycastContext.FluidHandling.NONE, ShapeContext.absent());
                BlockHitResult vanilla = world.raycast(none);
                String what = mode + " ray " + start + " -> " + end;

                // Off: the raycaster's pick is vanilla's.
                Vec3d delta = end.subtract(start);
                WorldRaycaster.Hit off = new WorldRaycaster().raycast(world, null,
                        new Ray(start.x, start.y, start.z, delta.x, delta.y, delta.z), mode, OFF, delta.length(), 0);
                assertEquals(vanilla.getBlockPos(), off.blockPos(), what);
                assertEquals(vanilla.getSide(), off.face(), what);

                // The fluid path hits the same blocks, the same way, when there is no fluid to stop at.
                BlockHitResult ours = WorldRaycaster.raycastEnteringFluids(new NoFluids(world), none);
                assertEquals(vanilla.getType(), ours.getType(), what);
                assertEquals(vanilla.getBlockPos(), ours.getBlockPos(), what);
                assertEquals(vanilla.getSide(), ours.getSide(), what);
                assertEquals(vanilla.getPos(), ours.getPos(), what);
            }
        }
    }

    @Test
    void onTheRayStopsNoLaterThanOffAndOnlyOnSurfaces() {
        TestWorld world = pool().fill(-8, 64, 4, 8, 66, 8, stone())
                .set(0, 64, 0, flowingWater(3)).set(-3, 64, -3, flowingWater(6));
        Random random = new Random(7);
        int surfaces = 0;
        for (int i = 0; i < 2_000; i++) {
            Ray ray = new Ray(random.nextDouble() * 16 - 8, 70 + random.nextDouble() * 6, random.nextDouble() * 16 - 8,
                    random.nextGaussian(), -0.2 - random.nextDouble(), random.nextGaussian());
            WorldRaycaster.Hit on = cast(world, ray, TERRAIN, ON);
            WorldRaycaster.Hit off = cast(world, ray, TERRAIN, OFF);
            assertTrue(on.distance() <= off.distance() + EPS, "on " + on + " vs off " + off);
            if (on.missed() || !world.getBlockState(on.blockPos()).isOf(Blocks.WATER)) {
                continue;
            }
            surfaces++;
            double local = on.pos().y - on.blockPos().getY();
            double height = world.getFluidState(on.blockPos()).getHeight(world, on.blockPos());
            if (on.face() == Direction.UP) {
                assertEquals(height, local, EPS, "a hit from above lies on the water's surface: " + on);
                assertFalse(WorldRaycaster.inFluid(world, on.pos().add(0, 1e-3, 0)), "no water above a surface: " + on);
            } else {
                assertTrue(local <= height + EPS, "a side hit lies below the surface: " + on);
            }
        }
        assertTrue(surfaces > 100, "most rays reach the pool: " + surfaces);
    }
}
