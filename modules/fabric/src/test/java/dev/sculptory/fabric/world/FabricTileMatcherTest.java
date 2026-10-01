package dev.sculptory.fabric.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.fabric.schem.TileSanitizer;
import java.util.List;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The canonical comparison undo and redo use for block entities,
 * with vanilla block entities under fabric-loader-junit. "Live" content is made the way the world makes it: the recorded
 * data loaded into a block entity (as {@link BlockWriter} loads it) and captured ({@link FabricTile#capture}). Untouched
 * content matches whatever form it was recorded in (key order, missing defaults, other number types, x/y/z, a sanitized
 * sign, no data at all); items added, items taken or a sign's text edited do not.
 */
class FabricTileMatcherTest {
    private static FabricStateSpace states;
    private static RegistryWrapper.WrapperLookup registries;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        states = FabricStateSpace.build();
        registries = BuiltinRegistries.createWrapperLookup();
    }

    private static int h(String spec) {
        int handle = states.parse(spec);
        assertTrue(handle >= 0, "unknown " + spec);
        return handle;
    }

    private static FabricTileMatcher matcher() {
        return new FabricTileMatcher(registries, states);
    }

    /** Recorded data as a file or the wire carries it: the compound's bytes in the order given. */
    private static NbtBytes recorded(String type, NbtCompound data) {
        return new NbtBytes(type, NbtIo.toBytes(data));
    }

    /** What the world holds after the writer writes {@code tile}: loaded into a block entity of the state. */
    private static BlockEntity placed(int handle, BlockEntityData tile) throws Exception {
        BlockState state = states.state(handle);
        BlockEntity entity = tile == null
                ? ((BlockEntityProvider) state.getBlock()).createBlockEntity(BlockPos.ORIGIN, state)
                : BlockEntity.createFromNbt(BlockPos.ORIGIN, state, FabricTile.from(tile).copyNbt(), registries);
        assertNotNull(entity, "no block entity for " + state);
        return entity;
    }

    private static BlockEntityData live(BlockEntity entity) {
        return FabricTile.capture(entity, registries);
    }

    private static NbtCompound item(int slot, String id, int count, boolean byteCount) {
        NbtCompound.Builder item = NbtCompound.builder().putByte("Slot", (byte) slot).putString("id", id);
        return (byteCount ? item.putByte("count", (byte) count) : item.putInt("count", count)).build();
    }

    private static NbtList items(NbtTag... entries) {
        return NbtList.of(List.of(entries));
    }

    private static NbtCompound signSide(String json) {
        return NbtCompound.builder()
                .put("messages", NbtList.ofStrings(List.of(json, "\"\"", "\"\"", "\"\"")))
                .putString("color", "black")
                .build();
    }

    @Test
    void untouchedContainersMatchWhateverFormTheirDataWasRecordedIn() throws Exception {
        FabricTileMatcher matcher = matcher();
        int chest = h("minecraft:chest[facing=east]");
        // Items first and a byte count (as older tools write it), no id: the game writes an int count and adds id.
        NbtBytes itemsFirst = recorded("minecraft:chest", NbtCompound.builder()
                .put("Items", items(item(0, "minecraft:diamond", 5, true), item(13, "minecraft:oak_log", 64, true)))
                .build());
        // The same content, id first, int counts, and a position the game never keeps.
        NbtBytes idFirst = recorded("minecraft:chest", NbtCompound.builder()
                .putString("id", "minecraft:chest").putInt("x", 7).putInt("y", 64).putInt("z", -3)
                .put("Items", items(item(13, "minecraft:oak_log", 64, false), item(0, "minecraft:diamond", 5, false)))
                .build());
        BlockEntityData world = live(placed(chest, itemsFirst));
        assertFalse(world.sameContent(itemsFirst), "the bytes differ: the canonical form decides");
        assertTrue(matcher.matches(chest, world, itemsFirst));
        assertTrue(matcher.matches(chest, world, idFirst));
        assertTrue(matcher.matches(chest, live(placed(chest, idFirst)), itemsFirst));

        // A chest placed without data (every record of such a chest, old journals included, holds none): the empty
        // chest the game makes, whose Items list the game adds.
        assertTrue(matcher.matches(chest, live(placed(chest, null)), null));
        assertTrue(matcher.matches(chest, null, null), "no block entity made yet, none recorded");
        assertTrue(matcher.matches(chest, null, recorded("minecraft:chest", NbtCompound.EMPTY)),
                "a block entity not made yet is the default the game makes");

        // A furnace recorded with its input only: the game adds its timers and recipe counts.
        int furnace = h("minecraft:furnace[facing=north,lit=false]");
        NbtBytes smelting = recorded("minecraft:furnace", NbtCompound.builder()
                .put("Items", items(item(0, "minecraft:raw_iron", 3, true))).build());
        BlockEntityData furnaceNow = live(placed(furnace, smelting));
        assertFalse(furnaceNow.sameContent(smelting));
        assertTrue(matcher.matches(furnace, furnaceNow, smelting));
    }

    @Test
    void anUntouchedSignMatchesItsSanitizedOrForeignText() throws Exception {
        FabricTileMatcher matcher = matcher();
        int sign = h("minecraft:oak_sign[rotation=3,waterlogged=false]");
        String click = "{\"text\":\"Hi\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op @a\"}}";
        byte[] fromFile = NbtIo.toBytes(NbtCompound.builder()
                .put("front_text", signSide(click)).put("back_text", signSide("{\"text\":\"\"}")).build());
        BlockEntityData sanitized = TileSanitizer.restoredSign("minecraft:sign", fromFile);
        BlockEntityData world = live(placed(sign, sanitized));
        assertFalse(world.sameContent(sanitized), "the game writes the text its own way");
        assertTrue(matcher.matches(sign, world, sanitized));

        NbtBytes foreign = recorded("minecraft:sign", NbtCompound.builder()
                .put("front_text", signSide("{\"text\":\"Hi\"}")).putByte("is_waxed", (byte) 0).build());
        assertTrue(matcher.matches(sign, live(placed(sign, foreign)), foreign));
    }

    @Test
    void changedContentsDoNotMatch() throws Exception {
        FabricTileMatcher matcher = matcher();
        int chest = h("minecraft:chest[facing=east]");
        NbtBytes recorded = recorded("minecraft:chest", NbtCompound.builder()
                .put("Items", items(item(0, "minecraft:diamond", 5, true))).build());
        ChestBlockEntity added = (ChestBlockEntity) placed(chest, recorded);
        added.setStack(1, new ItemStack(Items.EMERALD, 2));
        assertFalse(matcher.matches(chest, live(added), recorded), "items added");
        ChestBlockEntity taken = (ChestBlockEntity) placed(chest, recorded);
        taken.setStack(0, new ItemStack(Items.DIAMOND, 4));
        assertFalse(matcher.matches(chest, live(taken), recorded), "an item taken");
        ChestBlockEntity filled = (ChestBlockEntity) placed(chest, null);
        filled.setStack(0, new ItemStack(Items.DIAMOND, 1));
        assertFalse(matcher.matches(chest, live(filled), null), "a chest placed empty and filled since");

        int sign = h("minecraft:oak_sign[rotation=3,waterlogged=false]");
        NbtBytes text = recorded("minecraft:sign", NbtCompound.builder().put("front_text", signSide("\"Hi\"")).build());
        // Edited the way the game stores an edit (setText needs a world): the same sign, other text.
        SignBlockEntity edited = (SignBlockEntity) placed(sign, text);
        net.minecraft.nbt.NbtCompound bye = edited.createNbt(registries);
        bye.put("front_text", SignText.CODEC.encodeStart(registries.getOps(net.minecraft.nbt.NbtOps.INSTANCE),
                new SignText().withMessage(0, Text.literal("Bye"))).getOrThrow());
        edited.read(bye, registries);
        assertTrue(matcher.matches(sign, live(placed(sign, text)), text), "untouched");
        assertFalse(matcher.matches(sign, live(edited), text), "the sign's text edited");

        int furnace = h("minecraft:furnace[facing=north,lit=false]");
        AbstractFurnaceBlockEntity fuelled = (AbstractFurnaceBlockEntity) placed(furnace, null);
        fuelled.setStack(1, new ItemStack(Items.COAL, 8));
        assertFalse(matcher.matches(furnace, live(fuelled), null), "fuel put in");
    }

    /**
     * A hopper's transfer cooldown changes on its first tick without anyone touching it: left out, so an untouched hopper
     * still matches; its items are compared as for any container.
     */
    @Test
    void volatileTimersAreLeftOutButContentsAreNot() throws Exception {
        FabricTileMatcher matcher = matcher();
        int hopper = h("minecraft:hopper[enabled=true,facing=down]");
        HopperBlockEntity ticked = (HopperBlockEntity) placed(hopper, null);
        net.minecraft.nbt.NbtCompound nbt = ticked.createNbt(registries);
        nbt.putInt("TransferCooldown", 0); // -1 when made, 0 after a tick
        ticked.read(nbt, registries);
        BlockEntityData world = live(ticked);
        assertFalse(world.sameContent(live(placed(hopper, null))), "the cooldown did change");
        assertTrue(matcher.matches(hopper, world, null));
        ticked.setStack(0, new ItemStack(Items.IRON_INGOT, 1));
        assertFalse(matcher.matches(hopper, live(ticked), null), "items in the hopper");

        // Only the hopper's own keys are left out: a chest with such a key compares it.
        net.minecraft.nbt.NbtCompound a = new net.minecraft.nbt.NbtCompound();
        a.putString("id", "minecraft:chest");
        a.putInt("TransferCooldown", 3);
        net.minecraft.nbt.NbtCompound b = a.copy();
        b.putInt("TransferCooldown", 4);
        assertFalse(FabricTileMatcher.sameIgnoring(a, b, FabricTileMatcher.VOLATILE_KEYS.get("minecraft:chest")));
        assertTrue(FabricTileMatcher.sameIgnoring(a, b, FabricTileMatcher.VOLATILE_KEYS.get("minecraft:hopper")));
        b.remove("TransferCooldown");
        assertTrue(FabricTileMatcher.sameIgnoring(a, b, FabricTileMatcher.VOLATILE_KEYS.get("minecraft:hopper")),
                "a left-out key missing on one side");
        b.putInt("Extra", 1);
        assertFalse(FabricTileMatcher.sameIgnoring(a, b, FabricTileMatcher.VOLATILE_KEYS.get("minecraft:hopper")));
    }

    /** One bee in a nest as the game saves it, {@code ticks} into its stay. */
    private static net.minecraft.nbt.NbtCompound bee(int ticks, boolean nectar) {
        net.minecraft.nbt.NbtCompound entity = new net.minecraft.nbt.NbtCompound();
        entity.putString("id", "minecraft:bee");
        entity.putBoolean("HasNectar", nectar);
        net.minecraft.nbt.NbtCompound bee = new net.minecraft.nbt.NbtCompound();
        bee.put("entity_data", entity);
        bee.putInt("ticks_in_hive", ticks);
        bee.putInt("min_ticks_in_hive", 600);
        return bee;
    }

    private static BlockEntity nest(int handle, net.minecraft.nbt.NbtCompound... bees) throws Exception {
        BlockEntity nest = placed(handle, null);
        net.minecraft.nbt.NbtCompound nbt = nest.createNbt(registries);
        net.minecraft.nbt.NbtList list = new net.minecraft.nbt.NbtList();
        for (net.minecraft.nbt.NbtCompound bee : bees) list.add(bee);
        nbt.put("bees", list);
        nest.read(nbt, registries);
        return nest;
    }

    /**
     * Each bee in a nest counts its time there every tick, so a nest nobody touched changes every tick: that count is
     * left out (an untouched nest grown by a scatter still undoes), but a bee that left, came in or changed is not.
     */
    @Test
    void aBeeNestMatchesWhileItsBeesOnlyWaitButNotWhenTheyChange() throws Exception {
        FabricTileMatcher matcher = matcher();
        int nestState = h("minecraft:bee_nest[facing=north,honey_level=0]");
        BlockEntityData planned = live(nest(nestState, bee(12, false), bee(300, false)));
        BlockEntityData waited = live(nest(nestState, bee(19, false), bee(307, false)));
        assertFalse(waited.sameContent(planned), "the bees' time in the nest did change");
        assertTrue(matcher.matches(nestState, waited, planned), "an untouched nest matches");
        assertFalse(matcher.matches(nestState, live(nest(nestState, bee(19, false))), planned), "a bee left");
        assertFalse(matcher.matches(nestState, live(nest(nestState, bee(19, false), bee(307, false), bee(0, true))),
                planned), "a bee came in");
        assertFalse(matcher.matches(nestState, live(nest(nestState, bee(19, true), bee(307, false))), planned),
                "a bee's own data changed");
        BlockEntity flowered = nest(nestState, bee(19, false), bee(307, false));
        net.minecraft.nbt.NbtCompound nbt = flowered.createNbt(registries);
        nbt.put("flower_pos", net.minecraft.nbt.NbtHelper.fromBlockPos(new BlockPos(1, 2, 3)));
        flowered.read(nbt, registries);
        assertFalse(matcher.matches(nestState, live(flowered), planned), "the bees found a flower");
        // Only a beehive's bees are left out like that: another type's list is compared whole.
        net.minecraft.nbt.NbtCompound a = new net.minecraft.nbt.NbtCompound();
        net.minecraft.nbt.NbtList bees = new net.minecraft.nbt.NbtList();
        bees.add(bee(1, false));
        a.put("bees", bees);
        net.minecraft.nbt.NbtCompound b = a.copy();
        b.getList("bees", net.minecraft.nbt.NbtElement.COMPOUND_TYPE).getCompound(0).putInt("ticks_in_hive", 2);
        assertTrue(FabricTileMatcher.sameLeavingOutVolatile(a, b, "minecraft:beehive"));
        assertFalse(FabricTileMatcher.sameLeavingOutVolatile(a, b, "minecraft:chest"));
    }

    /** A spawner's countdown (it runs while a player is near) is left out; the mob it spawns is not. */
    @Test
    void aSpawnerCountdownIsLeftOutButItsMobIsNot() throws Exception {
        FabricTileMatcher matcher = matcher();
        int spawner = h("minecraft:spawner");
        BlockEntity counted = placed(spawner, null);
        net.minecraft.nbt.NbtCompound nbt = counted.createNbt(registries);
        nbt.putShort("Delay", (short) 7);
        counted.read(nbt, registries);
        assertFalse(live(counted).sameContent(live(placed(spawner, null))), "the countdown did change");
        assertTrue(matcher.matches(spawner, live(counted), null));

        BlockEntity zombies = placed(spawner, null);
        net.minecraft.nbt.NbtCompound mob = zombies.createNbt(registries);
        net.minecraft.nbt.NbtCompound entity = new net.minecraft.nbt.NbtCompound();
        entity.putString("id", "minecraft:zombie");
        net.minecraft.nbt.NbtCompound spawnData = new net.minecraft.nbt.NbtCompound();
        spawnData.put("entity", entity);
        mob.put("SpawnData", spawnData);
        zombies.read(mob, registries);
        assertFalse(matcher.matches(spawner, live(zombies), null), "a spawn egg used on it since");
    }

    /** Live data that cannot be read counts as changed: the block is kept. */
    @Test
    void liveDataThatCannotBeReadCountsAsChanged() {
        FabricTileMatcher matcher = matcher();
        int chest = h("minecraft:chest[facing=east]");
        NbtBytes garbage = new NbtBytes("minecraft:chest", new byte[] {1, 2, 3});
        assertFalse(matcher.matches(chest, garbage, null));
        assertFalse(matcher.matches(chest, garbage, recorded("minecraft:chest", NbtCompound.EMPTY)));
    }

    /**
     * Recorded data the writer could not have loaded (bytes that do not decode, an unknown type, a type that no longer
     * fits the block) compares as the block's default, which is what the writer left: an untouched default block matches,
     * a filled one does not.
     */
    @Test
    void recordedDataThatWillNotLoadComparesAsTheDefault() throws Exception {
        FabricTileMatcher matcher = matcher();
        int chest = h("minecraft:chest[facing=east]");
        BlockEntityData untouched = live(placed(chest, null));
        ChestBlockEntity filled = (ChestBlockEntity) placed(chest, null);
        filled.setStack(0, new ItemStack(Items.DIAMOND, 1));
        List<BlockEntityData> unloadable = List.of(
                new NbtBytes("minecraft:chest", new byte[] {1, 2, 3}),
                recorded("nosuchmod:crate", NbtCompound.EMPTY),
                recorded("minecraft:furnace", NbtCompound.builder()
                        .put("Items", items(item(0, "minecraft:raw_iron", 3, true))).build()));
        for (BlockEntityData recorded : unloadable) {
            assertTrue(matcher.matches(chest, untouched, recorded), "untouched against " + recorded);
            assertFalse(matcher.matches(chest, live(filled), recorded), "filled against " + recorded);
        }
    }

    /**
     * A detached block entity whose load throws (a jigsaw block whose name is no valid id) compares as the default,
     * and the failure is logged once per block-entity type, not once per tile.
     */
    @Test
    void aFailingDetachedBlockEntityComparesAsTheDefaultAndIsLoggedOncePerType() throws Exception {
        FabricTileMatcher matcher = matcher();
        int jigsaw = h("minecraft:jigsaw[orientation=north_up]");
        BlockEntityData untouched = live(placed(jigsaw, null));
        int before = FabricTileMatcher.WARNINGS.get();
        for (int i = 0; i < 3; i++) {
            NbtBytes broken = recorded("minecraft:jigsaw", NbtCompound.builder()
                    .putString("name", "Not A Valid Id " + i + "!").build());
            assertTrue(matcher.matches(jigsaw, untouched, broken), "tile " + i);
        }
        matcher.section(1);
        assertTrue(matcher().matches(jigsaw, untouched, recorded("minecraft:jigsaw", NbtCompound.builder()
                .putString("name", "Still Not Valid!").build())), "another undo");
        assertEquals(1, FabricTileMatcher.WARNINGS.get() - before, "logged once for the type");
    }

    /** A state without a block entity holds no contents to lose: nothing recorded there is compared. */
    @Test
    void aStateWithoutABlockEntityHasNothingToCompare() {
        FabricTileMatcher matcher = matcher();
        int stone = h("minecraft:stone");
        assertTrue(matcher.matches(stone, null, recorded("minecraft:chest", NbtCompound.EMPTY)));
        assertTrue(matcher.matches(stone, null, null));
    }
}
