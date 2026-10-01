package dev.sculptory.fabric.client.editor.demo;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.check.Fixtures;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.Heightmap;

/**
 * The demo's stage: a {@value #SIZE} x {@value #SIZE} grass field cut into the tour world's forest at ground level
 * around the world spawn, with what each step works on built on it (a post for the box, a cliff, a house, a plank wall,
 * a basin, a mound and a tree, stairs and an armor stand, a wall to bulldoze). Coordinates given to the script are
 * relative to the corner {@code (x0, y0, z0)}, where {@code y0} is the first air layer and {@code -1} the floor's top.
 * Built on the integrated server's thread once, before the demo starts; the world is a fresh copy each run.
 */
public final class DemoStage {
    public static final int SIZE = 113;
    /** Air above the floor. */
    public static final int HEIGHT = 40;
    /** Dirt under the grass, so a stage above lower ground shows earth at its edge. */
    private static final int DEPTH = 6;
    private static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;

    // ---- What is where (relative; the floor's top is y -1) ----

    /** Step 3: a post whose top and the floor span a 7x6x7 box when dragged between. */
    public static final int BOX_X = 16;
    public static final int BOX_Z = 16;
    public static final int POST_X = 22;
    public static final int POST_Z = 22;
    public static final int POST_TOP = 4;
    /** Step 4: the cliff (stone at x >= CLIFF_X, its west face aimed at from the west). */
    public static final int CLIFF_X = 84;
    public static final int CLIFF_TOP = 17;
    public static final int CLIFF_Z0 = 36;
    public static final int CLIFF_Z1 = 80;
    /** The bump in front of the face for Smooth, and the one for Flatten. */
    public static final int BUMP_Z = 44;
    public static final int FLAT_BUMP_Z = 58;
    /** Where the Terrain-mode mound is raised on the grass. */
    public static final int MOUND_X = 70;
    public static final int MOUND_Z = 62;
    /** Step 5 and 6: flat grass. */
    public static final int PAINT_X = 12;
    public static final int PAINT_Z = 72;
    public static final int SHAPE_X = 38;
    public static final int SHAPE_Z = 72;
    /** Step 7: the symmetry centre and the stroke beside it. */
    public static final int SYM_X = 19;
    public static final int SYM_Z = 47;
    /** Step 8: the house, its chimney at the far corner so one drag selects it all; pasted east of it. */
    public static final int HOUSE_X0 = 36;
    public static final int HOUSE_X1 = 42;
    public static final int HOUSE_Z0 = 44;
    public static final int HOUSE_Z1 = 50;
    public static final int CHIMNEY_TOP = 7;
    public static final int PASTE_X = 50;
    public static final int PASTE_Z = 47;
    /** Step 9: the road's nodes and the roofless plank box. */
    public static final int ROAD_X0 = 60;
    public static final int ROAD_X1 = 96;
    public static final int ROAD_Z = 98;
    public static final int ROOF_X0 = 62;
    public static final int ROOF_X1 = 68;
    public static final int ROOF_Z0 = 68;
    public static final int ROOF_Z1 = 74;
    public static final int ROOF_TOP = 3;
    /** Step 10: the plank wall, its south face at z = WALL_Z. */
    public static final int WALL_X0 = 62;
    public static final int WALL_X1 = 68;
    public static final int WALL_Z = 48;
    public static final int WALL_TOP = 4;
    /** Step 11: the basin (its inside one block in from the outer box) and the water ball's spot. */
    public static final int BASIN_X0 = 38;
    public static final int BASIN_X1 = 45;
    public static final int BASIN_Z0 = 16;
    public static final int BASIN_Z1 = 23;
    public static final int BASIN_DEPTH = 3;
    public static final int BALL_X = 52;
    public static final int BALL_Z = 20;
    /** Step 12: the mound Scatter paints and the tree it copies. */
    public static final int MOUND2_X = 24;
    public static final int MOUND2_Z = 98;
    public static final int MOUND2_RADIUS = 12;
    public static final int MOUND2_HEIGHT = 5;
    public static final int TREE_X = 48;
    public static final int TREE_Z = 98;
    /** Step 13 and 18: the stairs (facing south) and the armor stand. */
    public static final int STAIRS_X = 65;
    public static final int STAIRS_Z = 20;
    public static final int STAND_X = 70;
    public static final int STAND_Z = 20;
    /** Step 15: three quick fills happen here. */
    public static final int FILLS_X = 8;
    public static final int FILLS_Z = 36;
    /** Step 18: the cobblestone wall to bulldoze. */
    public static final int BULLDOZE_X0 = 88;
    public static final int BULLDOZE_X1 = 96;
    public static final int BULLDOZE_Z = 16;
    public static final int BULLDOZE_TOP = 3;

    private final int x0;
    private final int y0;
    private final int z0;

    private DemoStage(int x0, int y0, int z0) {
        this.x0 = x0;
        this.y0 = y0;
        this.z0 = z0;
    }

    public int x(int dx) {
        return x0 + dx;
    }

    public int y(int dy) {
        return y0 + dy;
    }

    public int z(int dz) {
        return z0 + dz;
    }

    public Box box(int ax, int ay, int az, int bx, int by, int bz) {
        return Box.of(new BlockPos(x(ax), y(ay), z(az)), new BlockPos(x(bx), y(by), z(bz)));
    }

    /** The height of the Scatter mound's top block above the floor at a relative cell, or -1 on flat ground. */
    public static int moundTop(int dx, int dz) {
        double d = Math.hypot(dx - MOUND2_X, dz - MOUND2_Z);
        if (d >= MOUND2_RADIUS) {
            return -1;
        }
        int h = (int) Math.round(MOUND2_HEIGHT * (1 - (d / MOUND2_RADIUS) * (d / MOUND2_RADIUS)));
        return h - 1;
    }

    @Override
    public String toString() {
        return "stage at " + x0 + " " + y0 + " " + z0;
    }

    // ---- Building (server thread) ----

    /**
     * Builds the stage centred on the world spawn, at the ground's height there, and gives the player the planks
     * builder mode places.
     */
    public static DemoStage build(MinecraftServer server, UUID playerId) {
        ServerWorld world = server.getOverworld();
        net.minecraft.util.math.BlockPos spawn = world.getSpawnPos();
        int x0 = ((spawn.getX() - SIZE / 2) >> 4) << 4;
        int z0 = ((spawn.getZ() - SIZE / 2) >> 4) << 4;
        for (int cx = x0 >> 4; cx <= (x0 + SIZE - 1) >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= (z0 + SIZE - 1) >> 4; cz++) {
                world.setChunkForced(cx, cz, true);
                world.getChunk(cx, cz);
            }
        }
        int floor = groundAt(world, x0 + SIZE / 2, z0 + SIZE / 2);
        DemoStage stage = new DemoStage(x0, floor + 1, z0);
        stage.buildField(world);
        stage.buildFixtures(world);
        stage.placeArmorStand(world);
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerId);
        if (player != null) {
            player.getInventory().setStack(0, new ItemStack(Items.OAK_PLANKS, 64));
            player.getInventory().markDirty();
        }
        return stage;
    }

    /** The top solid ground block's y at a column: under the trees and their crowns. */
    private static int groundAt(ServerWorld world, int x, int z) {
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z) - 1;
        net.minecraft.util.math.BlockPos.Mutable at = new net.minecraft.util.math.BlockPos.Mutable();
        for (int down = 0; down < 48; down++, y--) {
            BlockState state = world.getBlockState(at.set(x, y, z));
            if (!state.isAir() && !state.isIn(BlockTags.LEAVES) && !state.isIn(BlockTags.LOGS)
                    && state.getFluidState().isEmpty() && state.isOpaqueFullCube(world, at)) {
                return y;
            }
        }
        return world.getSeaLevel();
    }

    private void buildField(ServerWorld world) {
        BlockState air = Blocks.AIR.getDefaultState();
        BlockState grass = Blocks.GRASS_BLOCK.getDefaultState();
        BlockState dirt = Blocks.DIRT.getDefaultState();
        net.minecraft.util.math.BlockPos.Mutable at = new net.minecraft.util.math.BlockPos.Mutable();
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) {
                for (int dy = HEIGHT - 1; dy >= 0; dy--) {
                    at.set(x(dx), y(dy), z(dz));
                    if (!world.getBlockState(at).isAir()) {
                        world.setBlockState(at, air, FLAGS);
                    }
                }
                world.setBlockState(at.set(x(dx), y(-1), z(dz)), grass, FLAGS);
                for (int dy = -2; dy >= -DEPTH; dy--) {
                    at.set(x(dx), y(dy), z(dz));
                    if (!world.getBlockState(at).isOpaqueFullCube(world, at)) {
                        world.setBlockState(at, dirt, FLAGS);
                    }
                }
            }
        }
        for (Entity entity : world.getOtherEntities(null, new net.minecraft.util.math.Box(x(0), y(-DEPTH), z(0),
                x(SIZE), y(HEIGHT), z(SIZE)))) {
            if (!entity.isPlayer()) {
                entity.discard();
            }
        }
    }

    private void buildFixtures(ServerWorld world) {
        Writer w = new Writer(world);
        // Step 3: the post.
        w.fill(POST_X, 0, POST_Z, POST_X, POST_TOP, POST_Z, "minecraft:stone_bricks");
        // Step 4: the cliff, grass on top, a bump to smooth and one to flatten in front of its face.
        w.fill(CLIFF_X, 0, CLIFF_Z0, SIZE - 1, CLIFF_TOP - 1, CLIFF_Z1, "minecraft:stone");
        w.fill(CLIFF_X, CLIFF_TOP, CLIFF_Z0, SIZE - 1, CLIFF_TOP, CLIFF_Z1, "minecraft:grass_block");
        w.fill(CLIFF_X - 1, 6, BUMP_Z - 1, CLIFF_X - 1, 8, BUMP_Z + 1, "minecraft:stone");
        w.fill(CLIFF_X - 1, 7, FLAT_BUMP_Z - 1, CLIFF_X - 1, 9, FLAT_BUMP_Z + 1, "minecraft:stone");
        w.set(CLIFF_X - 2, 8, FLAT_BUMP_Z, "minecraft:stone");
        // Step 8: the house.
        w.fill(HOUSE_X0, 0, HOUSE_Z0, HOUSE_X1, 3, HOUSE_Z1, "minecraft:oak_planks");
        w.fill(HOUSE_X0 + 1, 0, HOUSE_Z0 + 1, HOUSE_X1 - 1, 3, HOUSE_Z1 - 1, "minecraft:air");
        w.fill(HOUSE_X0, 4, HOUSE_Z0, HOUSE_X1, 4, HOUSE_Z1, "minecraft:oak_planks");
        w.fill(HOUSE_X0 + 1, 5, HOUSE_Z0 + 1, HOUSE_X1 - 1, 5, HOUSE_Z1 - 1, "minecraft:oak_planks");
        w.fill(HOUSE_X0 + 2, 6, HOUSE_Z0 + 2, HOUSE_X1 - 2, 6, HOUSE_Z1 - 2, "minecraft:oak_planks");
        w.set(HOUSE_X0 + 3, 7, HOUSE_Z0 + 3, "minecraft:oak_planks");
        w.fill(HOUSE_X1, 5, HOUSE_Z0, HOUSE_X1, CHIMNEY_TOP, HOUSE_Z0, "minecraft:cobblestone");
        int doorX = HOUSE_X0 + 3;
        w.set(doorX, 0, HOUSE_Z1, "minecraft:oak_door[facing=south,half=lower]");
        w.set(doorX, 1, HOUSE_Z1, "minecraft:oak_door[facing=south,half=upper]");
        w.set(HOUSE_X0 + 1, 1, HOUSE_Z1, "minecraft:glass");
        w.set(HOUSE_X1 - 1, 1, HOUSE_Z1, "minecraft:glass");
        w.set(HOUSE_X0, 1, HOUSE_Z0 + 3, "minecraft:glass");
        w.set(HOUSE_X1, 1, HOUSE_Z0 + 3, "minecraft:glass");
        // Step 9: the roofless box.
        w.fill(ROOF_X0, 0, ROOF_Z0, ROOF_X1, ROOF_TOP, ROOF_Z1, "minecraft:oak_planks");
        w.fill(ROOF_X0 + 1, 0, ROOF_Z0 + 1, ROOF_X1 - 1, ROOF_TOP, ROOF_Z1 - 1, "minecraft:air");
        // Step 10: the wall.
        w.fill(WALL_X0, 0, WALL_Z, WALL_X1, WALL_TOP, WALL_Z, "minecraft:oak_planks");
        // Step 11: the basin.
        w.fill(BASIN_X0, 0, BASIN_Z0, BASIN_X1, BASIN_DEPTH - 1, BASIN_Z1, "minecraft:stone_bricks");
        w.fill(BASIN_X0 + 1, 0, BASIN_Z0 + 1, BASIN_X1 - 1, BASIN_DEPTH - 1, BASIN_Z1 - 1, "minecraft:air");
        // Step 12: the mound and the tree.
        for (int dx = MOUND2_X - MOUND2_RADIUS; dx <= MOUND2_X + MOUND2_RADIUS; dx++) {
            for (int dz = MOUND2_Z - MOUND2_RADIUS; dz <= MOUND2_Z + MOUND2_RADIUS; dz++) {
                int top = moundTop(dx, dz);
                if (top >= 0) {
                    w.fill(dx, 0, dz, dx, top, dz, "minecraft:dirt");
                    w.set(dx, top, dz, "minecraft:grass_block");
                }
            }
        }
        w.fill(TREE_X, 0, TREE_Z, TREE_X, 3, TREE_Z, "minecraft:oak_log");
        for (int dy = 2; dy <= 5; dy++) {
            int r = dy <= 3 ? 2 : dy == 4 ? 1 : 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    boolean corner = r == 2 && Math.abs(dx) == 2 && Math.abs(dz) == 2;
                    boolean trunk = dx == 0 && dz == 0 && dy <= 3;
                    if (!corner && !trunk) {
                        w.set(TREE_X + dx, dy, TREE_Z + dz, "minecraft:oak_leaves[persistent=true]");
                    }
                }
            }
        }
        // Step 13: the stairs.
        w.fill(STAIRS_X - 1, 0, STAIRS_Z, STAIRS_X + 1, 0, STAIRS_Z, "minecraft:oak_stairs[facing=south]");
        // Step 18: the wall to bulldoze.
        w.fill(BULLDOZE_X0, 0, BULLDOZE_Z, BULLDOZE_X1, BULLDOZE_TOP, BULLDOZE_Z, "minecraft:cobblestone");
    }

    private void placeArmorStand(ServerWorld world) {
        ArmorStandEntity stand = EntityType.ARMOR_STAND.create(world);
        if (stand == null) {
            return;
        }
        stand.refreshPositionAndAngles(x(STAND_X) + 0.5, y(0), z(STAND_Z) + 0.5, 180f, 0f);
        world.spawnEntity(stand);
    }

    /** Relative writes into the server world. */
    private final class Writer {
        private final ServerWorld world;
        private final net.minecraft.util.math.BlockPos.Mutable at = new net.minecraft.util.math.BlockPos.Mutable();

        Writer(ServerWorld world) {
            this.world = world;
        }

        void set(int dx, int dy, int dz, String state) {
            world.setBlockState(at.set(x(dx), y(dy), z(dz)), Fixtures.Build.state(state), FLAGS);
        }

        void fill(int ax, int ay, int az, int bx, int by, int bz, String state) {
            BlockState parsed = Fixtures.Build.state(state);
            for (int dy = Math.min(ay, by); dy <= Math.max(ay, by); dy++) {
                for (int dz = Math.min(az, bz); dz <= Math.max(az, bz); dz++) {
                    for (int dx = Math.min(ax, bx); dx <= Math.max(ax, bx); dx++) {
                        world.setBlockState(at.set(x(dx), y(dy), z(dz)), parsed, FLAGS);
                    }
                }
            }
        }
    }
}
