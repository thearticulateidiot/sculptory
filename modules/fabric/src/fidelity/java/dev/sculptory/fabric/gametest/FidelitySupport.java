package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.world.BlockWriter;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.ItemEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ChunkLevelType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;
import vectorwing.farmersdelight.common.block.entity.CuttingBoardBlockEntity;
import vectorwing.farmersdelight.common.block.entity.SkilletBlockEntity;
import vectorwing.farmersdelight.common.block.entity.StoveBlockEntity;

/**
 * The modded build the fidelity GameTests edit: a Farmer's Delight kitchen and garden in
 * a Chipped room, {@value #SIZE} x {@value #HEIGHT} x {@value #SIZE}. Every block entity holds something, and nothing
 * in it changes by itself: no heat under the pot and skillet, the stove unlit with air above it, and the fidelity run
 * turns random ticks off ({@link FidelityTestBootstrap}), so crops do not grow.
 */
final class FidelitySupport {
    static final int SIZE = 8;
    static final int HEIGHT = 4;
    /** A sign-style {@code rotation} property's values. */
    private static final List<Integer> SIXTEEN_STEPS = IntStream.range(0, 16).boxed().toList();

    /**
     * Blocks that implement neither {@code rotate} nor {@code mirror}, so vanilla's leave them as they are (the earlier
     * fidelity run found them not turning in pastes): the modded facing fallback turns them.
     */
    static final List<String> NON_ROTATING = List.of("farmersdelight:cooking_pot", "farmersdelight:skillet",
            "farmersdelight:apple_pie", "farmersdelight:roast_chicken_block", "farmersdelight:honey_glazed_ham_block",
            "farmersdelight:rice_roll_medley_block", "chipped:big_lantern", "chipped:tall_soul_lantern",
            "chipped:wide_lantern", "chipped:donut_lantern");

    /** Relative position "x,y,z" and state of every non-floor block. */
    static final String[][] BUILD = {
            // Farmer's Delight block entities with contents (filled by fillContents).
            {"0,1,0", "farmersdelight:stove[facing=east,lit=false]"},
            {"1,1,0", "farmersdelight:cooking_pot[facing=south,support=none,waterlogged=false]"},
            {"2,1,0", "farmersdelight:skillet[facing=west,support=false,waterlogged=false]"},
            {"3,1,0", "farmersdelight:cutting_board[facing=north,waterlogged=true]"},
            {"4,1,0", "farmersdelight:oak_cabinet[facing=east,open=false]"},
            {"5,1,0", "farmersdelight:basket[enabled=true,facing=north,waterlogged=false]"},
            {"4,1,4", "farmersdelight:canvas_sign[rotation=6,waterlogged=false]"},
            {"5,1,4", "farmersdelight:canvas_wall_sign[facing=south,waterlogged=false]"},
            {"6,2,4", "farmersdelight:hanging_canvas_sign[attached=true,rotation=4,waterlogged=false]"},
            // Food with bite and serving states.
            {"6,1,0", "farmersdelight:apple_pie[bites=2,facing=west]"},
            {"7,1,0", "farmersdelight:roast_chicken_block[facing=east,servings=3]"},
            {"7,1,4", "farmersdelight:rice_roll_medley_block[facing=north,servings=5]"},
            // Chipped variants: a door, a waterlogged trapdoor, logs on two axes, lanterns, bars, a workbench pair.
            {"0,1,2", "chipped:barred_birch_door[facing=east,half=lower,hinge=left,open=false,powered=false]"},
            {"0,2,2", "chipped:barred_birch_door[facing=east,half=upper,hinge=left,open=false,powered=false]"},
            {"1,1,2", "chipped:airy_birch_trapdoor[facing=south,half=top,open=true,powered=false,waterlogged=true]"},
            {"2,1,2", "chipped:bundled_acacia_log[axis=x]"},
            {"3,1,2", "chipped:bundled_acacia_log[axis=z]"},
            {"4,1,2", "chipped:big_lantern[facing=east,waterlogged=true]"},
            {"5,1,2", "chipped:anguished_jack_o_lantern[facing=west]"},
            {"6,1,2", "chipped:barbed_iron_bars[east=true,north=true,south=false,waterlogged=false,west=false]"},
            {"7,1,2", "chipped:botanist_workbench[facing=east,model=main]"},
            {"7,1,3", "chipped:botanist_workbench[facing=east,model=side]"},
            {"2,1,4", "chipped:acacia_wall_torch[facing=south]"},
            {"3,1,4", "chipped:andesite_pointed_dripstone[thickness=tip,vertical_direction=up,waterlogged=false]"},
            {"3,3,4", "chipped:andesite_pointed_dripstone[thickness=frustum,vertical_direction=down,waterlogged=true]"},
            {"0,3,0", "chipped:circular_black_stained_glass"},
            {"1,3,1", "chipped:barky_black_carpet"},
            // Rope, tatami and a rug.
            {"0,1,4", "farmersdelight:rope[east=false,north=true,south=true,tied_to_bell=false,waterlogged=false,west=true]"},
            {"1,1,4", "farmersdelight:half_tatami_mat[facing=east]"},
            {"1,2,5", "farmersdelight:canvas_rug"},
            // The garden, on rich soil farmland: crops, wild crops, two-block wild rice and a mushroom colony.
            {"0,1,6", "farmersdelight:cabbages[age=5]"},
            {"1,1,6", "farmersdelight:onions[age=4]"},
            {"2,1,6", "farmersdelight:budding_tomatoes[age=2]"},
            {"3,1,6", "farmersdelight:tomatoes[age=3,ropelogged=false]"},
            {"4,1,6", "farmersdelight:wild_cabbages"},
            {"5,1,6", "farmersdelight:wild_rice[half=lower,waterlogged=true]"},
            {"5,2,6", "farmersdelight:wild_rice[half=upper,waterlogged=false]"},
            {"6,1,6", "farmersdelight:brown_mushroom_colony[age=2]"},
            {"7,1,7", "farmersdelight:sandy_shrub"},
    };

    /** Block-entity positions in {@link #BUILD}, relative. */
    static final List<String> TILES = List.of("0,1,0", "1,1,0", "2,1,0", "3,1,0", "4,1,0", "5,1,0", "4,1,4", "5,1,4",
            "6,2,4");

    private FidelitySupport() {}

    static Box buildBox(int x, int y, int z) {
        return box(x, y, z, x + SIZE - 1, y + HEIGHT - 1, z + SIZE - 1);
    }

    /** Writes the build with its minimum corner at (x, y, z) and fills its block entities. */
    static void build(Harness h, ServerWorld world, int x, int y, int z) {
        BlockWriter writer = h.runtime.writer(world, new BlockWriter.Options(false, true));
        for (int dx = 0; dx < SIZE; dx++) {
            for (int dz = 0; dz < SIZE; dz++) writer.write(x + dx, y, z + dz, h.state(floor(dx, dz)), null);
        }
        for (String[] cell : BUILD) {
            int[] at = xyz(cell[0]);
            writer.write(x + at[0], y + at[1], z + at[2], h.state(cell[1]), null);
        }
        fillContents(world, x, y, z);
        WorldSnapshot snapshot = EditTestSupport.capture(world, buildBox(x, y, z));
        check(snapshot.tiles.size() == TILES.size(), "the build has " + snapshot.tiles.size() + " block entities");
    }

    /** Chipped floors with rich soil under the garden and farmland under the crops. */
    static String floor(int dx, int dz) {
        if (dz == 6 && dx <= 3) return "farmersdelight:rich_soil_farmland[moisture=7]";
        if (dz >= 6) return "farmersdelight:rich_soil";
        return (dx + dz) % 3 == 0 ? "chipped:angry_mossy_stone_bricks" : "chipped:boxed_oak_planks";
    }

    static int[] xyz(String text) {
        String[] parts = text.split(",");
        return new int[] {Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }

    static Item item(String id) {
        Item item = Registries.ITEM.get(Identifier.of(id));
        if (item == Items.AIR) throw new GameTestException("unknown item " + id);
        return item;
    }

    static <T> T entity(ServerWorld world, int x, int y, int z, Class<T> type) {
        BlockEntity entity = world.getBlockEntity(pos(x, y, z));
        if (!type.isInstance(entity)) {
            throw new GameTestException("expected a " + type.getSimpleName() + " at " + x + "," + y + "," + z + ", got "
                    + entity);
        }
        return type.cast(entity);
    }

    /** Ingredients, tools with components, containers and sign text. */
    private static void fillContents(ServerWorld world, int x, int y, int z) {
        StoveBlockEntity stove = entity(world, x, y + 1, z, StoveBlockEntity.class);
        stove.getInventory().setStackInSlot(0, new ItemStack(Items.BEEF));
        stove.getInventory().setStackInSlot(4, new ItemStack(Items.COD));

        CookingPotBlockEntity pot = entity(world, x + 1, y + 1, z, CookingPotBlockEntity.class);
        pot.getInventory().setStackInSlot(0, new ItemStack(Items.CARROT, 2));
        pot.getInventory().setStackInSlot(1, new ItemStack(item("farmersdelight:tomato"), 3));
        pot.getInventory().setStackInSlot(3, new ItemStack(Items.POTATO));
        pot.getInventory().setStackInSlot(7, new ItemStack(Items.BOWL, 4));

        SkilletBlockEntity skillet = entity(world, x + 2, y + 1, z, SkilletBlockEntity.class);
        skillet.getInventory().setStackInSlot(0, new ItemStack(Items.CHICKEN, 2));

        ItemStack knife = new ItemStack(item("farmersdelight:iron_knife"));
        knife.setDamage(17);
        knife.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Chef's knife"));
        CuttingBoardBlockEntity board = entity(world, x + 3, y + 1, z, CuttingBoardBlockEntity.class);
        check(board.addItem(knife), "the cutting board refused the knife");

        Inventory cabinet = entity(world, x + 4, y + 1, z, Inventory.class);
        cabinet.setStack(0, new ItemStack(Items.BREAD, 5));
        cabinet.setStack(13, new ItemStack(item("farmersdelight:rice"), 12));
        Inventory basket = entity(world, x + 5, y + 1, z, Inventory.class);
        basket.setStack(2, new ItemStack(item("farmersdelight:onion"), 7));

        sign(world, x + 4, y + 1, z + 4, "Kitchen", DyeColor.BLUE);
        sign(world, x + 5, y + 1, z + 4, "Garden", DyeColor.GREEN);
        sign(world, x + 6, y + 2, z + 4, "Pantry", DyeColor.RED);
    }

    private static void sign(ServerWorld world, int x, int y, int z, String line, DyeColor color) {
        SignBlockEntity sign = entity(world, x, y, z, SignBlockEntity.class);
        sign.setText(new SignText().withMessage(0, Text.literal(line)).withMessage(1, Text.literal("Sculptory"))
                .withColor(color).withGlowing(true), true);
        sign.setText(new SignText().withMessage(2, Text.literal("back of " + line)), false);
    }

    /**
     * A timed step that passes once every chunk under {@code box} ticks block entities, and two more ticks have
     * passed. A block entity's ticker only runs once its chunk's entities have loaded, which takes a few ticks after a
     * chunk is first forced; some block entities change their NBT on their first tick (a basket's transfer cooldown
     * goes from -1 to 0), so reference snapshots are taken after this step.
     */
    static Runnable settled(TestContext context, ServerWorld world, Box box) {
        long[] readyAt = {-1};
        return () -> {
            if (readyAt[0] < 0) {
                for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
                    for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) {
                        check(world.isChunkLoaded(ChunkPos.toLong(cx, cz))
                                && world.getChunk(cx, cz).getLevelType().isAfter(ChunkLevelType.BLOCK_TICKING),
                                "waiting for chunk " + cx + "," + cz + " to tick block entities");
                    }
                }
                readyAt[0] = context.getTick();
            }
            check(context.getTick() >= readyAt[0] + 2, "letting the block entities tick");
        };
    }

    /** Item entities in or around a box: an edit must never drop a container's contents. */
    static int itemEntities(ServerWorld world, Box box) {
        return world.getEntitiesByClass(ItemEntity.class, new net.minecraft.util.math.Box(box.min().x() - 2,
                box.min().y() - 2, box.min().z() - 2, box.max().x() + 3, box.max().y() + 3, box.max().z() + 3),
                e -> true).size();
    }

    /**
     * The state {@code from} must take under {@code t} with the modded facing fallback on (the
     * default), worked out here with vanilla's own helpers rather than the engine's tables: the mirror,
     * then the turn, each vanilla's {@code BlockState.mirror}/{@code rotate}, except that where a modded block's own
     * step leaves it unchanged, its horizontal {@code facing}, its {@code axis}/{@code horizontal_axis} and a 16-step
     * {@code rotation} turn as vanilla turns furnaces, logs and signs ({@code BlockRotation.rotate},
     * {@code BlockMirror.apply}, {@code rotate(r, 16)}, {@code mirror(r, 16)}), provided every turned and mirrored form
     * of the state exists and the block's own other steps leave it unchanged or agree ({@link #handledByFallback}).
     */
    static BlockState expectedTurn(BlockState from, Transform t) {
        BlockMirror mirror = ClipboardGameTest.vanilla(t.mirror());
        BlockRotation rotation = ClipboardGameTest.vanilla(t.quarterTurnsCw());
        BlockState mirrored = mirror == BlockMirror.NONE ? from
                : fallbackWhereUnchanged(from, from.mirror(mirror), BlockRotation.NONE, mirror);
        return rotation == BlockRotation.NONE ? mirrored
                : fallbackWhereUnchanged(mirrored, mirrored.rotate(rotation), rotation, BlockMirror.NONE);
    }

    /** Vanilla's {@code own} result, or the fallback's when a modded block's own step left it unchanged. */
    private static BlockState fallbackWhereUnchanged(BlockState state, BlockState own, BlockRotation rotation,
                                                     BlockMirror mirror) {
        if (own != state || Registries.BLOCK.getId(state.getBlock()).getNamespace().equals("minecraft")) return own;
        return handledByFallback(state) ? turned(state, rotation, mirror) : own;
    }

    /**
     * Whether the fallback handles the state: every turned and mirrored form of it exists, and each of the block's own
     * steps either leaves it unchanged or gives that form (so every step of it ends up as the fallback's).
     */
    private static boolean handledByFallback(BlockState state) {
        for (BlockRotation r : List.of(BlockRotation.CLOCKWISE_90, BlockRotation.CLOCKWISE_180,
                BlockRotation.COUNTERCLOCKWISE_90)) {
            BlockState form = turned(state, r, BlockMirror.NONE);
            BlockState own = state.rotate(r);
            if (form == null || (own != state && own != form)) return false;
        }
        for (BlockMirror m : List.of(BlockMirror.FRONT_BACK, BlockMirror.LEFT_RIGHT)) {
            BlockState form = turned(state, BlockRotation.NONE, m);
            BlockState own = state.mirror(m);
            if (form == null || (own != state && own != form)) return false;
        }
        return true;
    }

    /**
     * The state with its horizontal facing, axis and 16-step rotation mirrored, then turned, by vanilla's helpers;
     * {@code null} if the block does not allow a resulting value.
     */
    static BlockState turned(BlockState state, BlockRotation rotation, BlockMirror mirror) {
        BlockState result = state;
        for (Property<?> property : state.getProperties()) {
            String name = property.getName();
            Object value = state.get(property);
            Object next = value;
            if (name.equals("facing") && value instanceof Direction facing && facing.getAxis().isHorizontal()) {
                next = rotation.rotate(mirror.apply(facing));
            } else if ((name.equals("axis") || name.equals("horizontal_axis")) && value instanceof Direction.Axis axis) {
                boolean quarter = rotation == BlockRotation.CLOCKWISE_90 || rotation == BlockRotation.COUNTERCLOCKWISE_90;
                if (quarter && axis == Direction.Axis.X) next = Direction.Axis.Z;
                else if (quarter && axis == Direction.Axis.Z) next = Direction.Axis.X;
            } else if (name.equals("rotation") && property instanceof IntProperty steps
                    && steps.getValues().equals(SIXTEEN_STEPS)) {
                next = rotation.rotate(mirror.mirror(state.get(steps), 16), 16);
            }
            if (next.equals(value)) continue;
            result = with(result, property, next);
            if (result == null) return null;
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, Object value) {
        T typed = property.getType().cast(value);
        return property.getValues().contains(typed) ? state.with(property, typed) : null;
    }

    /** Where a paste of {@code box} (anchor at its minimum corner) with {@code t} at {@code origin} lands. */
    static Box targetBox(Box box, BlockPos origin, Transform t) {
        int sx = box.sizeX(), sz = box.sizeZ();
        int minX = origin.x() - t.mapX(0, 0, sx, sz), minZ = origin.z() - t.mapZ(0, 0, sx, sz);
        return box(minX, origin.y(), minZ, minX + t.sizeX(sx, sz) - 1, origin.y() + box.sizeY() - 1,
                minZ + t.sizeZ(sx, sz) - 1);
    }

    /**
     * Every cell of {@code source} (a snapshot, anchor at its minimum corner) found transformed around {@code origin}:
     * the state is {@link #expectedTurn} of the source state, and a block entity's NBT is the source's, unchanged.
     * Nothing else in the target box (and one cell around it) is non-air.
     */
    static void checkTransformed(ServerWorld world, WorldSnapshot source, BlockPos origin, Transform t, String what) {
        Box target = targetBox(source.box, origin, t);
        Box box = source.box;
        int sx = box.sizeX(), sz = box.sizeZ();
        int minX = target.min().x(), minZ = target.min().z();
        WorldSnapshot actual = EditTestSupport.capture(world, target);
        int nonAir = 0;
        for (int y = 0; y < box.sizeY(); y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int sourceX = box.min().x() + x, sourceY = box.min().y() + y, sourceZ = box.min().z() + z;
                    BlockState from = Block.getStateFromRawId(source.get(sourceX, sourceY, sourceZ));
                    int tx = minX + t.mapX(x, z, sx, sz), ty = origin.y() + y, tz = minZ + t.mapZ(x, z, sx, sz);
                    BlockState expected = expectedTurn(from, t);
                    BlockState got = Block.getStateFromRawId(actual.get(tx, ty, tz));
                    if (got != expected) {
                        throw new GameTestException(what + " " + t + ": " + from + " at +" + x + "," + y + "," + z
                                + " should be " + expected + " at " + tx + "," + ty + "," + tz + ", got " + got);
                    }
                    if (!from.isAir()) nonAir++;
                    NbtCompound sourceTile = source.tiles.get(pos(sourceX, sourceY, sourceZ).asLong());
                    NbtCompound targetTile = actual.tiles.get(pos(tx, ty, tz).asLong());
                    if (!Objects.equals(sourceTile, targetTile)) {
                        throw new GameTestException(what + " " + t + ": block entity at " + tx + "," + ty + "," + tz
                                + " expected " + sourceTile + ", got " + targetTile);
                    }
                }
            }
        }
        check(actual.tiles.size() == source.tiles.size(), what + " " + t + ": " + actual.tiles.size()
                + " block entities, expected " + source.tiles.size());
        int placed = 0;
        for (int y = target.min().y(); y <= target.max().y(); y++) {
            for (int z = target.min().z() - 1; z <= target.max().z() + 1; z++) {
                for (int x = target.min().x() - 1; x <= target.max().x() + 1; x++) {
                    if (!world.getBlockState(pos(x, y, z)).isAir()) placed++;
                }
            }
        }
        check(placed == nonAir, what + " " + t + ": " + placed + " blocks placed, expected " + nonAir);
    }
}
