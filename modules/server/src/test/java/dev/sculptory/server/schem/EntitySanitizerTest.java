package dev.sculptory.server.schem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.testing.FakeStateSpace;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EntitySanitizerTest {
    private static final Set<String> OPERATOR = Set.of("minecraft:command_block_minecart", "minecraft:spawner_minecart",
            "minecraft:falling_block");
    private final FakeStateSpace states = new FakeStateSpace();

    private static EntitySnapshot entity(String type, NbtCompound data) {
        return new EntitySnapshot(type, 0.5, 0, 0.5, 0f, 0f, null, NbtIo.toBytes(data), false);
    }

    private static NbtCompound data(EntitySnapshot entity) {
        try {
            return EntityNbt.decode(entity.nbt());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void operatorEntitiesKeepOnlyWhatEveryEntityHas() {
        NbtCompound cart = NbtCompound.builder().putString("Command", "op @a").putString("LastOutput", "{}")
                .putInt("SuccessCount", 3).putString("CustomName", "\"Cart\"")
                .put("Tags", NbtList.of(NbtTag.STRING, List.of(new NbtTag.NbtString("t")))).build();
        int[] stripped = {0};
        EntitySnapshot clean = EntitySanitizer.sanitize(entity("minecraft:command_block_minecart", cart), OPERATOR::contains,
                stripped);
        assertEquals(1, stripped[0]);
        NbtCompound kept = data(clean);
        assertNull(kept.get("Command"));
        assertNull(kept.get("LastOutput"));
        assertNull(kept.get("SuccessCount"));
        assertEquals("\"Cart\"", kept.getString("CustomName"));
        assertTrue(kept.get("Tags") != null, "tags are kept");
        assertFalse(clean.trusted());
    }

    @Test
    void ordinaryEntitiesAreLeftAsTheyAre() {
        EntitySnapshot stand = entity("minecraft:armor_stand",
                NbtCompound.builder().putString("CustomName", "\"Bob\"").putInt("DisabledSlots", 7).build());
        int[] stripped = {0};
        assertSame(stand, EntitySanitizer.sanitize(stand, OPERATOR::contains, stripped));
        EntitySnapshot plainCart = entity("minecraft:command_block_minecart",
                NbtCompound.builder().putString("CustomName", "\"Empty\"").build());
        assertSame(plainCart, EntitySanitizer.sanitize(plainCart, OPERATOR::contains, stripped), "nothing operator-only to drop");
        assertEquals(0, stripped[0]);
    }

    @Test
    void passengersAreSanitizedToo() {
        NbtCompound rider = NbtCompound.builder().putString("id", "minecraft:command_block_minecart")
                .putString("Command", "say hi").build();
        NbtCompound boat = NbtCompound.builder().putString("Type", "oak")
                .put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(rider))).build();
        int[] stripped = {0};
        EntitySnapshot clean = EntitySanitizer.sanitize(entity("minecraft:boat", boat), OPERATOR::contains, stripped);
        assertEquals(1, stripped[0]);
        NbtCompound kept = data(clean);
        assertEquals("oak", kept.getString("Type"), "the boat's own data stays");
        NbtCompound keptRider = kept.getList("Passengers").compounds().get(0);
        assertEquals("minecraft:command_block_minecart", keptRider.getString("id"));
        assertNull(keptRider.get("Command"));
    }

    /** The game reads a passenger's id without a namespace (or with an empty one) as minecraft's: so does the strip. */
    @Test
    void passengerIdsWithoutANamespaceAreStrippedAsTheGameLoadsThem() {
        for (String id : List.of("command_block_minecart", ":command_block_minecart", "falling_block")) {
            NbtCompound rider = NbtCompound.builder().putString("id", id).putString("Command", "op @a")
                    .put("TileEntityData", NbtCompound.builder().putString("Command", "op @a").build()).build();
            NbtCompound boat = NbtCompound.builder()
                    .put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(rider))).build();
            int[] stripped = {0};
            NbtCompound kept = data(EntitySanitizer.sanitize(entity("minecraft:boat", boat), OPERATOR::contains, stripped));
            assertEquals(1, stripped[0], id);
            NbtCompound keptRider = kept.getList("Passengers").compounds().get(0);
            assertNull(keptRider.get("Command"), id);
            assertNull(keptRider.get("TileEntityData"), id);
        }
        assertEquals("minecraft:pig", dev.sculptory.core.entity.EntityNbt.loadedId("pig"));
        assertEquals("minecraft:pig", dev.sculptory.core.entity.EntityNbt.loadedId(":pig"));
        assertEquals("mod:pig", dev.sculptory.core.entity.EntityNbt.loadedId("mod:pig"));
    }

    @Test
    void undecodableDataIsDroppedAndCounted() {
        EntitySnapshot broken = new EntitySnapshot("minecraft:pig", 0.5, 0, 0.5, 0f, 0f, null, new byte[] {10, 5, 1},
                false);
        int[] stripped = {0};
        EntitySnapshot clean = EntitySanitizer.sanitize(broken, OPERATOR::contains, stripped);
        assertEquals(1, stripped[0]);
        assertEquals(0, data(clean).size());
        assertEquals("minecraft:pig", clean.typeId());
    }

    @Test
    void aClipboardIsReturnedAsItIsWhenNothingChanges() {
        Clipboard plain = Clipboard.builder(states, new BlockPos(2, 2, 2)).build()
                .withEntities(List.of(entity("minecraft:armor_stand", NbtCompound.EMPTY)));
        EntitySanitizer.Result same = EntitySanitizer.sanitize(plain, OPERATOR::contains);
        assertSame(plain, same.clipboard());
        assertFalse(same.changed());

        Clipboard withCart = plain.withEntities(List.of(entity("minecraft:armor_stand", NbtCompound.EMPTY),
                entity("minecraft:command_block_minecart", NbtCompound.builder().putString("Command", "op @a").build())));
        EntitySanitizer.Result result = EntitySanitizer.sanitize(withCart, OPERATOR::contains);
        assertTrue(result.changed());
        assertEquals(1, result.stripped());
        assertEquals(2, result.clipboard().entityCount());
        assertTrue(result.clipboard().entities().stream().allMatch(e -> data(e).get("Command") == null));
    }

    /** Riders deeper than the sanitizer looks are dropped, never passed through unchecked. */
    @Test
    void ridersTooDeepToCheckAreDropped() {
        NbtCompound deepest = NbtCompound.builder().putString("id", "minecraft:command_block_minecart")
                .putString("Command", "op @a").build();
        NbtCompound chain = deepest;
        for (int i = 0; i < EntityNbt.MAX_PASSENGER_DEPTH; i++) {
            chain = NbtCompound.builder().putString("id", "minecraft:pig")
                    .put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(chain))).build();
        }
        int[] stripped = {0};
        EntitySnapshot clean = EntitySanitizer.sanitize(entity("minecraft:boat", NbtCompound.builder()
                .put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(chain))).build()), OPERATOR::contains, stripped);
        assertEquals(1, stripped[0], "the minecart past the depth");
        assertEquals(EntityNbt.MAX_PASSENGER_DEPTH, EntityNbt.riderCount(data(clean)));
        assertTrue(EntitySanitizer.holdsOperatorType("minecraft:boat", NbtCompound.builder()
                .put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(chain))).build(), OPERATOR::contains),
                "the operator check looks at every depth");
    }
}
