package dev.sculptory.fabric.client.editor.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.sculptory.fabric.client.editor.tool.RayOverlay;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The cursor ray through a {@link RayOverlay} ({@link OverlaidBlockView}), over a small world of real block states: the
 * cells the overlay shows are hit, or passed, as the states it gives; every other cell is the world's.
 */
class OverlaidBlockViewTest {
    private static final double EPS = 1e-6;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    /** Block states by position, air elsewhere; no block entities. */
    private static final class TestWorld implements BlockView {
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        TestWorld fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        blocks.put(new BlockPos(x, y, z), state);
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

    private static int id(BlockState state) {
        return Block.getRawIdFromState(state);
    }

    /** The overlay showing {@code state} in the box, the world elsewhere. */
    private static RayOverlay showing(BlockState state, int x0, int y0, int z0, int x1, int y1, int z1) {
        return (x, y, z) -> x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1
                ? id(state) : RayOverlay.WORLD;
    }

    private static WorldRaycaster.Hit cast(BlockView world, Ray ray, WorldRaycaster.Fluids fluids) {
        return new WorldRaycaster().raycast(world, null, ray, WorldRaycaster.Mode.TERRAIN, fluids, 100, 0);
    }

    private static void assertHit(WorldRaycaster.Hit hit, int y, double hitY) {
        assertEquals(WorldRaycaster.Hit.Kind.BLOCK, hit.kind(), "kind of " + hit);
        assertEquals(new BlockPos(0, y, 0), hit.blockPos(), "block of " + hit);
        assertEquals(Direction.UP, hit.face());
        assertEquals(hitY, hit.pos().y, EPS);
    }

    @Test
    void theRaySeesThroughAPlacedShapeAndStopsAtCarvedGroundAsItWas() {
        Ray down = new Ray(0.5, 80, 0.5, 0, -1, 0);
        BlockState stone = Blocks.STONE.getDefaultState();
        // The press placed a pillar on the floor: seen as the air it replaced, the floor is hit.
        TestWorld placed = new TestWorld().fill(-4, 60, -4, 4, 60, 4, stone).fill(0, 61, 0, 0, 70, 0, stone);
        assertHit(cast(placed, down, WorldRaycaster.Fluids.NONE), 70, 71);
        BlockView before = new OverlaidBlockView(placed, showing(Blocks.AIR.getDefaultState(), -1, 61, -1, 1, 70, 1));
        assertHit(cast(before, down, WorldRaycaster.Fluids.NONE), 60, 61);
        assertSame(stone, before.getBlockState(new BlockPos(0, 60, 0)), "outside the overlay: the world");

        // The press carved a hole: seen as the stone it removed, the ray stops at the old ground.
        TestWorld carved = new TestWorld().fill(-4, 50, -4, 4, 60, 4, stone).fill(0, 55, 0, 0, 60, 0,
                Blocks.AIR.getDefaultState());
        assertHit(cast(carved, down, WorldRaycaster.Fluids.NONE), 54, 55);
        assertHit(cast(new OverlaidBlockView(carved, showing(stone, 0, 55, 0, 0, 60, 0)), down,
                WorldRaycaster.Fluids.NONE), 60, 61);
    }

    @Test
    void overlaidCellsHaveTheirStatesFluidAndNoBlockEntity() {
        Ray down = new Ray(0.5, 80, 0.5, 0, -1, 0);
        BlockState stone = Blocks.STONE.getDefaultState();
        BlockState water = Blocks.WATER.getDefaultState();
        // A ball of water the press placed: aiming at fluids, it is seen as the air it replaced.
        TestWorld world = new TestWorld().fill(-4, 60, -4, 4, 60, 4, stone).fill(0, 61, 0, 0, 61, 0, water);
        assertHit(cast(world, down, WorldRaycaster.Fluids.ANY), 61, 61 + 8 / 9.0);
        BlockState air = Blocks.AIR.getDefaultState();
        OverlaidBlockView before = new OverlaidBlockView(world, showing(air, 0, 61, 0, 0, 61, 0));
        assertHit(cast(before, down, WorldRaycaster.Fluids.ANY), 60, 61);
        assertEquals(air.getFluidState(), before.getFluidState(new BlockPos(0, 61, 0)));
        // And water shown where the world has air is a surface.
        OverlaidBlockView pond = new OverlaidBlockView(new TestWorld().fill(-4, 60, -4, 4, 60, 4, stone),
                showing(water, 0, 61, 0, 0, 61, 0));
        assertHit(cast(pond, down, WorldRaycaster.Fluids.ANY), 61, 61 + 8 / 9.0);
        assertHit(cast(pond, down, WorldRaycaster.Fluids.NONE), 60, 61);
        assertNull(pond.getBlockEntity(new BlockPos(0, 61, 0)));
        assertEquals(384, pond.getHeight());
        assertEquals(-64, pond.getBottomY());
    }
}
