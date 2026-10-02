package dev.sculptory.fabric.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.scatter.BlockVariants;
import dev.sculptory.core.state.ModdedFacingFallback;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.server.net.ServerDispatcher;
import java.util.List;
import java.util.Map;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The vanilla state space under fabric-loader-junit with bootstrapped registries (no world). */
class FabricStateSpaceTest {
    private static FabricStateSpace space;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        space = FabricStateSpace.build();
    }

    private static int h(String spec) {
        int handle = space.parse(spec);
        assertTrue(handle >= 0, "unknown " + spec);
        return handle;
    }

    private static boolean has(String spec, int flag) {
        return StateFlags.has(space.flags(h(spec)), flag);
    }

    @Test
    void everyRegisteredStateRoundTrips() {
        assertEquals(Block.STATE_IDS.size(), space.size());
        assertTrue(space.size() > 20_000, "suspiciously few states: " + space.size());
        for (int handle = 0; handle < space.size(); handle++) {
            BlockState state = Block.getStateFromRawId(handle);
            assertSame(state, space.state(handle));
            String text = space.format(handle);
            assertEquals(handle, space.parse(text), text);
            BlockDescriptor descriptor = space.describe(handle);
            assertEquals(text, descriptor.format());
            assertEquals(handle, space.resolve(descriptor), text);
            assertEquals(handle, space.resolve(BlockDescriptor.parse(text)), text);
            assertEquals(Registries.BLOCK.getId(state.getBlock()).toString(), space.blockId(handle).value());
            assertEquals(state.getProperties().size(), descriptor.properties().size(), text);
        }
        assertEquals("minecraft:air", space.format(space.air()));
    }

    @Test
    void missingPropertiesTakeDefaults() {
        assertEquals(Block.getRawIdFromState(net.minecraft.block.Blocks.OAK_STAIRS.getDefaultState()),
                h("minecraft:oak_stairs"));
        assertEquals(h("minecraft:oak_log[axis=y]"), h("minecraft:oak_log"));
        assertEquals(h("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"),
                h("minecraft:oak_stairs[facing=east]"));
        assertEquals(h("minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]"),
                h("minecraft:oak_stairs[waterlogged=false,shape=straight,half=top,facing=east]"));
    }

    @Test
    void fluidsAndBlockEntitiesAreOrdinaryStates() {
        for (String spec : List.of("minecraft:water[level=0]", "minecraft:water[level=7]", "minecraft:lava[level=0]",
                "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=true]",
                "minecraft:chest[facing=north,type=single,waterlogged=false]", "minecraft:kelp[age=25]",
                "minecraft:command_block[conditional=false,facing=up]")) {
            assertEquals(spec, space.format(h(spec)));
        }
    }

    @Test
    void unknownOrMalformedInputReturnsMinusOne() {
        for (String bad : List.of("minecraft:oak_log[]", "minecraft:oak_log[axis=x,axis=y]",
                "minecraft:oak_log[axis=x,extra=y]", "minecraft:oak_log[axis=q]", "minecraft:oak_log[axis=X]",
                "minecraft:oak_log[axis=x,]", "minecraft:oak_log[axis=x]garbage", "minecraft:oak_log[axis=x]{foo:1}",
                "minecraft:stone{}", "#minecraft:logs", "minecraft:missing_block", "minecraft:stone[axis=x]",
                "minecraft:oak_log[axis=]", "minecraft:oak_log[=x]", "minecraft:oak_log[axis=x=y]",
                "minecraft:oak_log[ axis=x]", "minecraft:oak_log[axis=x\n]", " minecraft:stone", "minecraft:stone ",
                "stone", "minecraft:stone;", "minecraft:stone[foo= ]", "minecraft:stone[foo=\u0000]",
                "minecraft:water[level=03]", "minecraft:water[level=16]", "Minecraft:stone", "",
                "x".repeat(8193), "minecraft:oak_log[axis=" + "x".repeat(129) + "]")) {
            assertEquals(-1, space.parse(bad), bad);
        }
        assertEquals(-1, space.parse(null));
        assertEquals(-1, space.resolve(BlockDescriptor.of(new NamespacedId("fixture:unregistered"))));
        assertEquals(-1, space.resolve(BlockDescriptor.of(new NamespacedId("minecraft:stone"), Map.of("axis", "x"))));
        assertEquals(-1, space.resolve(null));
    }

    @Test
    void handlesOutsideTheSpaceThrow() {
        assertThrows(IndexOutOfBoundsException.class, () -> space.flags(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> space.format(space.size()));
        assertThrows(IndexOutOfBoundsException.class, () -> space.rotate(space.size(), 1));
    }

    @Test
    void rotateAndMirrorMatchVanillaForEveryState() {
        BlockRotation[] rotations = {BlockRotation.NONE, BlockRotation.CLOCKWISE_90, BlockRotation.CLOCKWISE_180,
            BlockRotation.COUNTERCLOCKWISE_90};
        for (int handle = 0; handle < space.size(); handle++) {
            BlockState state = space.state(handle);
            for (int turns = -4; turns <= 7; turns++) {
                int expected = Block.getRawIdFromState(state.rotate(rotations[Math.floorMod(turns, 4)]));
                assertEquals(expected, space.rotate(handle, turns));
            }
            assertEquals(handle, space.mirror(handle, Mirror.NONE));
            assertEquals(Block.getRawIdFromState(state.mirror(BlockMirror.FRONT_BACK)), space.mirror(handle, Mirror.X));
            assertEquals(Block.getRawIdFromState(state.mirror(BlockMirror.LEFT_RIGHT)), space.mirror(handle, Mirror.Z));
        }
    }

    /**
     * The modded facing fallback (on by default, as the config) has nothing to turn in a vanilla registry, so the parity
     * above holds with it on; its views share handles, flags and text, and switch as asked (the client follows the
     * server's handshake). Its behaviour on modded blocks: core ModdedFacingFallbackTest and the fidelity GameTests.
     */
    @Test
    void theModdedFacingFallbackLeavesVanillaAloneAndSwitches() {
        assertTrue(space.moddedFacingFallback());
        assertEquals(0, space.moddedFacingFallbackTables().turnedStates());
        FabricStateSpace off = space.withModdedFacingFallback(false);
        assertFalse(off.moddedFacingFallback());
        assertSame(space, space.withModdedFacingFallback(true));
        assertSame(off, off.withModdedFacingFallback(false));
        assertTrue(off.withModdedFacingFallback(true).moddedFacingFallback());
        assertEquals(space.size(), off.size());
        int stairs = h("minecraft:oak_stairs[facing=north]");
        assertEquals(space.rotate(stairs, 1), off.rotate(stairs, 1));
        assertEquals(space.mirror(stairs, Mirror.Z), off.mirror(stairs, Mirror.Z));
        assertSame(space.state(stairs), off.state(stairs));
        assertEquals(space.format(stairs), off.format(stairs));
        assertEquals(space.flags(stairs), off.flags(stairs));
        assertFalse(space.followingServer(Features.NONE).moddedFacingFallback());
        assertFalse(space.followingServer(ServerDispatcher.SERVER_FEATURES).moddedFacingFallback(), "an older server");
        assertTrue(off.followingServer(Features.of(Features.MODDED_FACING_FALLBACK)).moddedFacingFallback());
        assertFalse(FabricStateSpace.build(false).moddedFacingFallback());
    }

    /** The fallback's fast property access answers exactly as the text forms do, over every state. */
    @Test
    void theFallbacksPropertyAccessMatchesTheTextForms() {
        ModdedFacingFallback.PropertyAccess fast = space.propertyAccess();
        ModdedFacingFallback.PropertyAccess text = ModdedFacingFallback.PropertyAccess.of(space);
        List<String> names = List.of("facing", "axis", "horizontal_axis", "rotation", "waterlogged", "no_such_property");
        List<String> values = List.of("north", "east", "south", "west", "up", "down", "x", "y", "z", "0", "4", "15", "16",
                "true", "false", "sideways");
        int checked = 0;
        for (int handle = 0; handle < space.size(); handle++) {
            int state = handle;
            for (String name : names) {
                String value = text.value(handle, name);
                assertEquals(value, fast.value(handle, name), () -> space.format(state) + " " + name);
                if (value == null) continue;
                for (String next : values) {
                    assertEquals(text.with(handle, name, next), fast.with(handle, name, next),
                            () -> space.format(state) + " " + name + "=" + next);
                    checked++;
                }
            }
        }
        assertTrue(checked > 10_000, "only " + checked + " checks");
        assertEquals(-1, fast.with(h("minecraft:oak_sign[rotation=3]"), "rotation", "16"));
        assertEquals(h("minecraft:oak_sign[rotation=7]"), fast.with(h("minecraft:oak_sign[rotation=3]"), "rotation", "7"));
    }

    @Test
    void rotationIsClockwiseFromAboveAndMirrorsSwapTheNamedAxis() {
        String stairs = "minecraft:oak_stairs[facing=%s,half=bottom,shape=straight,waterlogged=false]";
        int north = h(stairs.formatted("north"));
        assertEquals(h(stairs.formatted("east")), space.rotate(north, 1));
        assertEquals(h(stairs.formatted("south")), space.rotate(north, 2));
        assertEquals(h(stairs.formatted("west")), space.rotate(north, 3));
        assertEquals(h(stairs.formatted("west")), space.rotate(north, -1));
        // Mirror.X negates x: east <-> west; Mirror.Z negates z: north <-> south.
        assertEquals(h(stairs.formatted("west")), space.mirror(h(stairs.formatted("east")), Mirror.X));
        assertEquals(north, space.mirror(north, Mirror.X));
        assertEquals(h(stairs.formatted("south")), space.mirror(north, Mirror.Z));
        assertEquals(h(stairs.formatted("east")), space.mirror(h(stairs.formatted("east")), Mirror.Z));
        assertEquals(h("minecraft:oak_log[axis=z]"), space.rotate(h("minecraft:oak_log[axis=x]"), 1));
    }

    @Test
    void flagSanity() {
        assertTrue(has("minecraft:stone", StateFlags.TERRAIN_SOLID));
        assertTrue(has("minecraft:oak_log[axis=y]", StateFlags.TERRAIN_SOLID), "logs count as terrain");
        assertTrue(has("minecraft:grass_block", StateFlags.TERRAIN_SOLID));
        assertTrue(has("minecraft:mud", StateFlags.TERRAIN_SOLID), "14/16 collision counts");
        assertTrue(has("minecraft:dirt_path", StateFlags.TERRAIN_SOLID), "15/16 collision counts");
        assertFalse(has("minecraft:oak_slab[type=bottom,waterlogged=false]", StateFlags.TERRAIN_SOLID));
        assertFalse(has("minecraft:oak_stairs", StateFlags.TERRAIN_SOLID));

        assertTrue(has("minecraft:oak_leaves", StateFlags.VEGETATION));
        assertFalse(has("minecraft:oak_leaves", StateFlags.TERRAIN_SOLID));
        for (String plant : List.of("minecraft:short_grass", "minecraft:poppy", "minecraft:oak_sapling",
                "minecraft:vine", "minecraft:kelp", "minecraft:seagrass", "minecraft:sugar_cane", "minecraft:cactus")) {
            assertTrue(has(plant, StateFlags.VEGETATION), plant);
            assertFalse(has(plant, StateFlags.TERRAIN_SOLID), plant);
        }
        assertTrue(has("minecraft:short_grass", StateFlags.REPLACEABLE));
        assertFalse(has("minecraft:stone", StateFlags.VEGETATION));

        assertTrue(has("minecraft:water[level=0]", StateFlags.FLUID_BLOCK));
        assertTrue(has("minecraft:water[level=3]", StateFlags.FLUID_BLOCK));
        assertTrue(has("minecraft:lava[level=0]", StateFlags.FLUID_BLOCK));
        assertTrue(has("minecraft:water[level=0]", StateFlags.REPLACEABLE));
        assertFalse(has("minecraft:water[level=0]", StateFlags.TERRAIN_SOLID));
        assertFalse(has("minecraft:water[level=0]", StateFlags.VEGETATION));
        assertFalse(has("minecraft:seagrass", StateFlags.FLUID_BLOCK));

        String wet = "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=true]";
        String dry = "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]";
        assertTrue(has(wet, StateFlags.WATERLOGGED) && has(wet, StateFlags.WATERLOGGABLE));
        assertTrue(!has(dry, StateFlags.WATERLOGGED) && has(dry, StateFlags.WATERLOGGABLE));
        assertFalse(has("minecraft:stone", StateFlags.WATERLOGGABLE));

        assertTrue(has("minecraft:air", StateFlags.AIR) && has("minecraft:cave_air", StateFlags.AIR));
        assertTrue(has("minecraft:air", StateFlags.REPLACEABLE));
        assertFalse(has("minecraft:air", StateFlags.VEGETATION));

        assertTrue(has("minecraft:chest", StateFlags.HAS_BLOCK_ENTITY));
        assertFalse(has("minecraft:chest", StateFlags.OPERATOR_NBT));
        for (String operator : List.of("minecraft:command_block", "minecraft:oak_sign", "minecraft:spawner",
                "minecraft:lectern", "minecraft:structure_block", "minecraft:jigsaw")) {
            assertTrue(has(operator, StateFlags.OPERATOR_NBT), operator);
        }
        assertFalse(has("minecraft:stone", StateFlags.HAS_BLOCK_ENTITY));

        assertTrue(has("minecraft:sand", StateFlags.FALLING) && has("minecraft:gravel", StateFlags.FALLING));
        assertFalse(has("minecraft:stone", StateFlags.FALLING));
    }

    /**
     * The two halves of vanilla two-block plants and doors are flagged by block class, not by a {@code half}
     * property: stairs (top/bottom) and young pitcher crops (one cell tall) are not.
     */
    @Test
    void twoBlockPlantsAndDoorsAreDoubleTall() {
        for (String block : List.of("minecraft:tall_grass", "minecraft:large_fern", "minecraft:sunflower",
                "minecraft:lilac", "minecraft:rose_bush", "minecraft:peony", "minecraft:pitcher_plant",
                "minecraft:small_dripleaf", "minecraft:tall_seagrass", "minecraft:oak_door")) {
            assertTrue(has(block + "[half=lower]", StateFlags.LOWER_HALF), block);
            assertFalse(has(block + "[half=lower]", StateFlags.UPPER_HALF), block);
            assertTrue(has(block + "[half=upper]", StateFlags.UPPER_HALF), block);
            assertFalse(has(block + "[half=upper]", StateFlags.LOWER_HALF), block);
        }
        assertFalse(has("minecraft:pitcher_crop[age=1,half=lower]", StateFlags.DOUBLE_TALL), "one cell tall while young");
        assertTrue(has("minecraft:pitcher_crop[age=3,half=lower]", StateFlags.LOWER_HALF));
        assertFalse(has("minecraft:oak_stairs[half=top]", StateFlags.DOUBLE_TALL));
        assertFalse(has("minecraft:poppy", StateFlags.DOUBLE_TALL));
        assertFalse(has("minecraft:big_dripleaf", StateFlags.DOUBLE_TALL));
    }

    /** Plants that hold water whatever their properties, and waterlogged states, carry water. */
    @Test
    void waterPlantsCarryWater() {
        for (String wet : List.of("minecraft:seagrass", "minecraft:kelp", "minecraft:kelp_plant",
                "minecraft:bubble_column", "minecraft:tall_seagrass[half=lower]", "minecraft:sea_pickle")) {
            assertTrue(space.fluidSource(h(wet)) >= 0, wet);
        }
        int dryPickle = space.withWaterlogged(h("minecraft:sea_pickle"), false);
        assertEquals(-1, space.fluidSource(dryPickle));
        assertEquals(-1, space.fluidSource(space.withWaterlogged(h("minecraft:dead_tube_coral_fan"), false)));
    }

    /**
     * Water plants for scatter: WATER on every state holding water (not lava), AQUATIC on plants that hold water
     * whatever their properties and on live coral, ON_WATER on blocks placed with a PlaceableOnWaterItem; and the
     * medium each block variant then gets.
     */
    @Test
    void waterPlantsAreClassified() {
        for (String wet : List.of("minecraft:water[level=0]", "minecraft:water[level=3]", "minecraft:seagrass",
                "minecraft:kelp", "minecraft:kelp_plant", "minecraft:bubble_column", "minecraft:sea_pickle",
                "minecraft:oak_stairs[waterlogged=true]")) {
            assertTrue(has(wet, StateFlags.WATER), wet);
        }
        for (String dry : List.of("minecraft:lava[level=0]", "minecraft:stone", "minecraft:air",
                "minecraft:sea_pickle[waterlogged=false]", "minecraft:lily_pad")) {
            assertFalse(has(dry, StateFlags.WATER), dry);
        }
        for (String aquatic : List.of("minecraft:seagrass", "minecraft:tall_seagrass[half=lower]", "minecraft:kelp",
                "minecraft:kelp_plant", "minecraft:tube_coral", "minecraft:brain_coral_fan",
                "minecraft:fire_coral_wall_fan", "minecraft:horn_coral_block", "minecraft:tube_coral[waterlogged=false]")) {
            assertTrue(has(aquatic, StateFlags.AQUATIC), aquatic);
        }
        for (String not : List.of("minecraft:bubble_column", "minecraft:dead_tube_coral", "minecraft:dead_brain_coral_fan",
                "minecraft:dead_horn_coral_block", "minecraft:sea_pickle", "minecraft:water[level=0]", "minecraft:poppy",
                "minecraft:lily_pad")) {
            assertFalse(has(not, StateFlags.AQUATIC), not);
        }
        assertTrue(has("minecraft:lily_pad", StateFlags.ON_WATER));
        assertTrue(has("minecraft:frogspawn", StateFlags.ON_WATER));
        assertFalse(has("minecraft:poppy", StateFlags.ON_WATER));
        assertFalse(has("minecraft:seagrass", StateFlags.ON_WATER));

        assertEquals(BlockVariants.Medium.UNDERWATER, medium("minecraft:seagrass"));
        assertEquals(BlockVariants.Medium.UNDERWATER, medium("minecraft:tube_coral_fan[waterlogged=false]"));
        assertEquals(h("minecraft:tube_coral_fan[waterlogged=true]"),
                BlockVariants.resolve(space, "minecraft:tube_coral_fan[waterlogged=false]"), "live coral goes in wet");
        assertEquals(BlockVariants.Medium.UNDERWATER, medium("minecraft:sea_pickle"));
        assertEquals(BlockVariants.Medium.LAND, medium("minecraft:sea_pickle[waterlogged=false]"));
        assertEquals(BlockVariants.Medium.LAND, medium("minecraft:dead_tube_coral_fan[waterlogged=false]"));
        assertEquals(BlockVariants.Medium.WATER_SURFACE, medium("minecraft:lily_pad"));
        assertEquals(BlockVariants.Medium.LAND, medium("minecraft:poppy"));
        IllegalArgumentException bubbles = assertThrows(IllegalArgumentException.class,
                () -> BlockVariants.resolve(space, "minecraft:bubble_column"));
        assertEquals("minecraft:bubble_column carries water but is not a water plant", bubbles.getMessage());
    }

    private BlockVariants.Medium medium(String text) {
        return BlockVariants.medium(space, BlockVariants.resolve(space, text));
    }

    /** Column plants: sugar cane, cactus and bamboo repeat themselves; kelp and twisting vines grow a stem on a plant. */
    @Test
    void columnPlantsKnowTheirColumns() {
        for (String same : List.of("minecraft:sugar_cane", "minecraft:cactus", "minecraft:bamboo")) {
            assertEquals(h(same), space.columnPart(h(same), true), same);
            assertEquals(h(same), space.columnPart(h(same), false), same);
        }
        int kelp = h("minecraft:kelp[age=7]");
        assertEquals(kelp, space.columnPart(kelp, true));
        assertEquals(h("minecraft:kelp_plant"), space.columnPart(kelp, false));
        assertEquals(h("minecraft:kelp"), space.columnPart(h("minecraft:kelp_plant"), true), "the stem's default");
        assertEquals(h("minecraft:kelp_plant"), space.columnPart(h("minecraft:kelp_plant"), false));
        assertEquals(h("minecraft:twisting_vines_plant"), space.columnPart(h("minecraft:twisting_vines"), false));
        for (String not : List.of("minecraft:weeping_vines", "minecraft:cave_vines", "minecraft:poppy",
                "minecraft:bamboo_sapling", "minecraft:seagrass", "minecraft:stone")) {
            assertEquals(-1, space.columnPart(h(not), true), not + " grows no column up");
        }
        assertEquals(h("minecraft:kelp"), BlockVariants.resolve(space, "minecraft:kelp_plant"));
        Clipboard column = BlockVariants.column(space, h("minecraft:kelp"), 3);
        assertEquals(h("minecraft:kelp_plant"), column.get(0, 0, 0));
        assertEquals(h("minecraft:kelp"), column.get(0, 2, 0));
    }

    @Test
    void fluidSourceAndWaterlogging() {
        int water = h("minecraft:water[level=0]");
        int lava = h("minecraft:lava[level=0]");
        assertEquals(water, space.fluidSource(h("minecraft:water[level=3]")));
        assertEquals(water, space.fluidSource(water));
        assertEquals(lava, space.fluidSource(h("minecraft:lava[level=5]")));
        assertEquals(water, space.fluidSource(h("minecraft:oak_stairs[waterlogged=true]")));
        assertEquals(water, space.fluidSource(h("minecraft:kelp")));
        assertEquals(-1, space.fluidSource(h("minecraft:oak_stairs[waterlogged=false]")));
        assertEquals(-1, space.fluidSource(h("minecraft:stone")));

        int dry = h("minecraft:oak_slab[type=bottom,waterlogged=false]");
        int wet = h("minecraft:oak_slab[type=bottom,waterlogged=true]");
        assertEquals(wet, space.withWaterlogged(dry, true));
        assertEquals(dry, space.withWaterlogged(wet, false));
        assertEquals(h("minecraft:stone"), space.withWaterlogged(h("minecraft:stone"), true));
        assertNotEquals(dry, wet);
    }
}
