package dev.sculptory.core.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class EntityNbtTest {
    /** A boat as {@code Entity.saveSelfNbt} writes it, with a rider that has a rider. */
    static NbtCompound boat() {
        NbtCompound chicken = NbtCompound.builder().putString("id", "minecraft:chicken")
                .put("Pos", EntityNbt.doubles(1, 2, 3)).putIntArray("UUID", new int[] {1, 2, 3, 4})
                .put("Motion", EntityNbt.doubles(0, -0.08, 0)).putInt("EggLayTime", 900).build();
        NbtCompound cow = NbtCompound.builder().putString("id", "minecraft:cow")
                .put("Pos", EntityNbt.doubles(1, 2, 3)).putIntArray("UUID", new int[] {5, 6, 7, 8})
                .putString("CustomName", "\"Daisy\"").put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(chicken)))
                .build();
        return NbtCompound.builder().putString("id", "minecraft:oak_boat")
                .put("Pos", EntityNbt.doubles(10.5, 64, -3.25))
                .put("Motion", EntityNbt.doubles(0.1, 0, 0))
                .put("Rotation", NbtList.of(NbtTag.FLOAT, List.of(new NbtTag.NbtFloat(90f), new NbtTag.NbtFloat(0f))))
                .putIntArray("UUID", new int[] {9, 9, 9, 9})
                .putString("Type", "oak")
                .put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(cow)))
                .build();
    }

    @Test
    void aClipboardKeepsNoIdentityOrPlace() {
        NbtCompound data = EntityNbt.snapshotData(boat());
        for (String key : EntityNbt.PLACEMENT_KEYS) assertFalse(data.contains(key), key);
        assertEquals("oak", data.getString("Type"));
        NbtCompound cow = data.getList("Passengers").compounds().get(0);
        assertEquals("minecraft:cow", cow.getString("id"), "riders keep their type");
        assertEquals("\"Daisy\"", cow.getString("CustomName"));
        for (String key : EntityNbt.PASSENGER_KEYS) assertFalse(cow.contains(key), key);
        NbtCompound chicken = cow.getList("Passengers").compounds().get(0);
        assertFalse(chicken.contains("UUID"));
        assertEquals(Integer.valueOf(900), chicken.getInt("EggLayTime"));
    }

    @Test
    void aClipboardLeavesOutHowAPaperServerSpawnedIt() {
        NbtCompound rider = NbtCompound.builder().putString("id", "minecraft:zombie")
                .putString("Paper.SpawnReason", "NATURAL").putByte("Paper.FromNetherPortal", (byte) 1)
                .putInt("Bukkit.updateLevel", 2).build();
        NbtCompound horse = NbtCompound.builder().putString("id", "minecraft:skeleton_horse")
                .putString("Paper.SpawnReason", "TRAP")
                .put("Paper.Origin", EntityNbt.doubles(1, 2, 3))
                .putIntArray("Paper.OriginWorld", new int[] {1, 2, 3, 4})
                .putIntArray("Paper.SpawningEntity", new int[] {5, 6, 7, 8})
                .putInt("Bukkit.updateLevel", 2)
                .put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(rider)))
                .build();
        NbtCompound data = EntityNbt.snapshotData(horse);
        for (String key : EntityNbt.SPAWN_CONTEXT_KEYS) assertFalse(data.contains(key), key);
        NbtCompound kept = data.getList("Passengers").compounds().get(0);
        for (String key : EntityNbt.SPAWN_CONTEXT_KEYS) assertFalse(kept.contains(key), "rider " + key);
        // CraftBukkit's data version stays: without it a mob's saved CanPickUpLoot false is ignored when it loads.
        assertEquals(Integer.valueOf(2), data.getInt("Bukkit.updateLevel"));
        assertEquals(Integer.valueOf(2), kept.getInt("Bukkit.updateLevel"));
    }

    @Test
    void aMovedCopyHasTheFormOfItsSource() {
        NbtCompound source = NbtCompound.builder().putString("id", "minecraft:pig")
                .putIntArray("UUID", new int[] {1, 2, 3, 4})
                .put("Pos", EntityNbt.doubles(1.5, 64, 1.5)).putShort("Air", (short) 300)
                .putString("Paper.SpawnReason", "BREEDING").putString("CustomName", "\"Wilbur\"").build();
        NbtCompound copy = NbtCompound.builder().putString("id", "minecraft:pig")
                .putIntArray("UUID", new int[] {9, 8, 7, 6})
                .put("Pos", EntityNbt.doubles(11.5, 64, 1.5)).putShort("Air", (short) 120)
                .putString("Paper.SpawnReason", "DEFAULT").putString("CustomName", "\"Wilbur\"").build();
        NbtCompound other = copy.toBuilder().putString("CustomName", "\"Babe\"").build();
        assertArrayEquals(EntityNbt.copyForm(NbtIo.toBytes(source)), EntityNbt.copyForm(NbtIo.toBytes(copy)));
        assertFalse(java.util.Arrays.equals(EntityNbt.copyForm(NbtIo.toBytes(source)),
                EntityNbt.copyForm(NbtIo.toBytes(other))), "a different pig is not its copy");
        assertNull(EntityNbt.copyForm(new byte[] {1, 2, 3}), "data that does not decode pairs with nothing");
    }

    @Test
    void aPutBackKeepsOnlyTheRidersTheRemovalTook() {
        NbtCompound chicken = NbtCompound.builder().putString("id", "minecraft:chicken")
                .putIntArray("UUID", new int[] {1, 1, 1, 1}).build();
        NbtCompound zombie = NbtCompound.builder().putString("id", "minecraft:zombie")
                .putIntArray("UUID", new int[] {2, 2, 2, 2}).build();
        NbtCompound recorded = NbtCompound.builder().putString("id", "minecraft:oak_boat")
                .putIntArray("UUID", new int[] {9, 9, 9, 9})
                .put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(chicken))).build();
        // Since the step: a zombie boarded too.
        NbtCompound live = recorded.toBuilder().put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(chicken, zombie)))
                .putString("CustomName", "\"Ark\"").build();
        NbtCompound back = decodeOrFail(EntityNbt.withRidersOf(NbtIo.toBytes(live), NbtIo.toBytes(recorded)));
        List<NbtCompound> riders = back.getList("Passengers").compounds();
        assertEquals(1, riders.size(), "only the chicken the removal took");
        assertEquals("minecraft:chicken", riders.get(0).getString("id"));
        assertEquals("\"Ark\"", back.getString("CustomName"), "the rest is as it is now");
        NbtCompound alone = decodeOrFail(EntityNbt.withRidersOf(NbtIo.toBytes(live),
                NbtIo.toBytes(recorded.toBuilder().remove("Passengers").build())));
        assertFalse(alone.contains("Passengers"), "a removal that took no rider gives none back");
    }

    @Test
    void anUntouchedCopyHasTheExactFormOfItsSource() {
        NbtCompound source = NbtCompound.builder().putString("id", "minecraft:cow")
                .putIntArray("UUID", new int[] {1, 2, 3, 4}).put("Pos", EntityNbt.doubles(1.5, 64, 1.5))
                .putShort("Air", (short) 300).build();
        NbtCompound copy = source.toBuilder().putIntArray("UUID", new int[] {5, 6, 7, 8})
                .put("Pos", EntityNbt.doubles(9.5, 64, 1.5)).build();
        NbtCompound breathless = copy.toBuilder().putShort("Air", (short) 12).build();
        assertArrayEquals(EntityNbt.exactCopyForm(NbtIo.toBytes(source)), EntityNbt.exactCopyForm(NbtIo.toBytes(copy)));
        assertFalse(java.util.Arrays.equals(EntityNbt.exactCopyForm(NbtIo.toBytes(source)),
                EntityNbt.exactCopyForm(NbtIo.toBytes(breathless))), "the exact form keeps what the game changes");
        assertArrayEquals(EntityNbt.copyForm(NbtIo.toBytes(source)), EntityNbt.copyForm(NbtIo.toBytes(breathless)));
    }

    @Test
    void paperKeysEveryEntityCarriesAreComparedWithout() {
        for (String key : List.of("Bukkit.updateLevel", "Bukkit.Aware", "AgeLocked", "Bukkit.MaxDomestication",
                "Paper.ShouldBurnInDay", "Paper.canWander", "Bukkit.BarterList", "Bukkit.InterestList")) {
            assertTrue(EntityNbt.PLATFORM_KEYS.contains(key), key);
        }
        for (String key : List.of("Bukkit.invisible", "Bukkit.persist", "Bukkit.visibleByDefault", "BukkitValues")) {
            assertFalse(EntityNbt.PLATFORM_KEYS.contains(key), key + " is a setting someone made: compared");
        }
    }

    private static NbtCompound decodeOrFail(byte[] nbt) {
        try {
            return EntityNbt.decode(nbt);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void aPlacementSetsIdentityAndPlace() {
        NbtCompound data = EntityNbt.snapshotData(boat());
        NbtCompound spawn = EntityNbt.spawnCompound("minecraft:oak_boat", data, 1.5, 70, 2.5, -90f, 10f, null);
        assertEquals("minecraft:oak_boat", spawn.getString("id"));
        assertArrayEquals(new double[] {1.5, 70, 2.5}, EntityNbt.position(spawn));
        assertArrayEquals(new float[] {-90f, 10f}, EntityNbt.rotation(spawn));
        assertFalse(spawn.contains("UUID"), "the game gives it a new one");
        assertFalse(spawn.contains("TileX"));
        NbtCompound cow = spawn.getList("Passengers").compounds().get(0);
        assertArrayEquals(new double[] {1.5, 70, 2.5}, EntityNbt.position(cow), "riders stand at the vehicle");
        assertFalse(cow.contains("UUID"));
        NbtCompound chicken = cow.getList("Passengers").compounds().get(0);
        assertArrayEquals(new double[] {1.5, 70, 2.5}, EntityNbt.position(chicken));

        NbtCompound frame = EntityNbt.spawnCompound("minecraft:item_frame", NbtCompound.builder().putByte("Facing", (byte) 3)
                .build(), 4.5, 5.5, 6.03, 0f, 0f, new BlockPos(4, 5, 6));
        assertEquals(Integer.valueOf(4), frame.getInt("TileX"));
        assertEquals(Integer.valueOf(5), frame.getInt("TileY"));
        assertEquals(Integer.valueOf(6), frame.getInt("TileZ"));
        assertEquals(Integer.valueOf(3), frame.getInt("Facing"));
    }

    @Test
    void volatileKeysAreLeftOutEverywhere() {
        NbtCompound boat = boat();
        NbtCompound drifted = boat.toBuilder().put("Pos", EntityNbt.doubles(40, 63, 0))
                .put("Motion", EntityNbt.doubles(0, 0, 0)).putShort("Fire", (short) 20).build();
        assertEquals(EntityNbt.withoutVolatile(boat), EntityNbt.withoutVolatile(drifted), "a drifting boat is unchanged");
        NbtCompound renamed = boat.toBuilder().putString("CustomName", "\"Mine\"").build();
        assertNotEquals(EntityNbt.withoutVolatile(boat), EntityNbt.withoutVolatile(renamed), "a named boat changed");
        // A rider's timers do not count either, its name does.
        NbtCompound cow = boat.getList("Passengers").compounds().get(0);
        NbtCompound hurtCow = cow.toBuilder().putShort("HurtTime", (short) 5).put("Pos", EntityNbt.doubles(0, 0, 0)).build();
        NbtCompound withHurtCow = boat.toBuilder().put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(hurtCow))).build();
        assertEquals(EntityNbt.withoutVolatile(boat), EntityNbt.withoutVolatile(withHurtCow));
        NbtCompound renamedCow = cow.toBuilder().putString("CustomName", "\"Bessie\"").build();
        NbtCompound withRenamedCow = boat.toBuilder().put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(renamedCow)))
                .build();
        assertNotEquals(EntityNbt.withoutVolatile(boat), EntityNbt.withoutVolatile(withRenamedCow));
    }

    @Test
    void canonicalBytesIgnoreKeyOrder() throws IOException {
        NbtCompound a = NbtCompound.builder().putInt("b", 1).putString("a", "x").build();
        NbtCompound b = NbtCompound.builder().putString("a", "x").putInt("b", 1).build();
        assertArrayEquals(EntityNbt.canonical(NbtIo.toBytes(a)), EntityNbt.canonical(NbtIo.toBytes(b)));
        assertEquals(a, EntityNbt.decode(EntityNbt.canonical(NbtIo.toBytes(a))));
        byte[] junk = {1, 2, 3};
        assertArrayEquals(junk, EntityNbt.canonical(junk), "undecodable bytes stay as they are");
    }

    @Test
    void malformedPositionsAndRotationsAreAbsent() {
        assertNull(EntityNbt.position(NbtCompound.EMPTY));
        assertNull(EntityNbt.position(NbtCompound.builder().put("Pos", NbtList.of(NbtTag.DOUBLE,
                List.of(new NbtTag.NbtDouble(1), new NbtTag.NbtDouble(2)))).build()));
        assertNull(EntityNbt.position(NbtCompound.builder().put("Pos", EntityNbt.doubles(1, Double.NaN, 2)).build()));
        assertNull(EntityNbt.rotation(NbtCompound.builder().put("Rotation", EntityNbt.doubles(1, 2, 3)).build()));
    }

    @Test
    void snapshotsValidateAndCompareWithoutTrust() {
        byte[] nbt = NbtIo.toBytes(NbtCompound.builder().putString("Type", "oak").build());
        EntitySnapshot a = new EntitySnapshot("minecraft:oak_boat", 1.5, 2, 3.5, 90f, 0f, null, nbt, true);
        assertEquals(a, a.untrusted(), "trust is not content");
        assertFalse(a.untrusted().trusted());
        assertEquals(new BlockPos(1, 2, 3), a.cell());
        assertEquals(new BlockPos(3, 4, 5), a.offset(2, 2, 2).cell());
        EntitySnapshot frame = new EntitySnapshot("minecraft:item_frame", 0.5, 0.5, 0.97, 0f, 0f, new BlockPos(0, 0, 1),
                nbt, false);
        assertEquals(new BlockPos(0, 0, 1), frame.cell(), "its block, not its position");
        nbt[0] = 0;
        assertNotEquals(nbt[0], a.nbt()[0], "the data is copied in");
        assertThrows(IllegalArgumentException.class, () -> new EntitySnapshot("Boat", 0, 0, 0, 0, 0, null, nbt, true));
        assertThrows(IllegalArgumentException.class,
                () -> new EntitySnapshot("minecraft:boat", Double.NaN, 0, 0, 0, 0, null, nbt, true));
        assertThrows(IllegalArgumentException.class,
                () -> new EntitySnapshot("minecraft:boat", 0, 0, 0, Float.POSITIVE_INFINITY, 0, null, nbt, true));
        assertThrows(IllegalArgumentException.class, () -> new EntitySnapshot("minecraft:boat", 0, 0, 0, 0, 0, null,
                new byte[EntitySnapshot.MAX_NBT_BYTES + 1], true));
        assertTrue(EntitySnapshot.CANONICAL_ORDER.compare(a, frame) > 0, "ordered by cell: y, z, x");
    }

    /** A {@code type} entity carrying {@code riders} passengers side by side (each of {@code type} too). */
    private static NbtCompound carrying(String type, int riders) {
        List<NbtCompound> list = new java.util.ArrayList<>();
        for (int i = 0; i < riders; i++) {
            list.add(NbtCompound.builder().putString("id", type).putInt("n", i).build());
        }
        return NbtCompound.builder().putString("id", type).put("Passengers", NbtList.of(NbtTag.COMPOUND, list)).build();
    }

    /** A chain of {@code depth} passengers, each riding the one before. */
    private static NbtCompound chain(int depth) {
        NbtCompound top = NbtCompound.builder().putString("id", "minecraft:pig").build();
        for (int i = 0; i < depth; i++) {
            top = NbtCompound.builder().putString("id", "minecraft:pig")
                    .put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(top))).build();
        }
        return top;
    }

    @Test
    void passengersAreCountedAndCappedPerEntity() {
        assertEquals(0, EntityNbt.riderCount(NbtCompound.EMPTY));
        assertEquals(90_000, EntityNbt.riderCount(carrying("minecraft:armor_stand", 90_000)), "iterative, any size");
        assertEquals(40, EntityNbt.riderCount(chain(40)));

        int[] dropped = {0};
        NbtCompound kept = EntityNbt.snapshotData(carrying("minecraft:armor_stand", 20), dropped);
        assertEquals(EntityNbt.MAX_RIDERS, EntityNbt.riderCount(kept));
        assertEquals(4, dropped[0]);
        assertEquals(0, EntityNbt.riders(kept).get(0).getInt("n"), "the first are kept");

        dropped[0] = 0;
        NbtCompound deep = EntityNbt.snapshotData(chain(20), dropped);
        assertEquals(EntityNbt.MAX_RIDERS, EntityNbt.riderCount(deep));
        assertEquals(4, dropped[0], "a rider left out counts with everything riding it");

        dropped[0] = 0;
        NbtCompound small = EntityNbt.snapshotData(carrying("minecraft:boat", 2), dropped);
        assertEquals(2, EntityNbt.riderCount(small));
        assertEquals(0, dropped[0]);

        IllegalArgumentException tooMany = assertThrows(IllegalArgumentException.class, () -> EntityNbt.spawnCompound(
                "minecraft:armor_stand", carrying("minecraft:armor_stand", EntityNbt.MAX_RIDERS + 1), 0, 0, 0, 0, 0,
                null));
        assertTrue(tooMany.getMessage().contains("passengers"), tooMany.getMessage());
        EntityNbt.spawnCompound("minecraft:armor_stand", carrying("minecraft:armor_stand", EntityNbt.MAX_RIDERS), 0, 0,
                0, 0, 0, null);
        assertEquals(-1, EntityNbt.riderCount(new byte[] {1, 2, 3}), "undecodable");
    }

    @Test
    void typeIdsAreComparedAsTheGameLoadsThem() {
        assertEquals("minecraft:command_block_minecart", EntityNbt.loadedId("command_block_minecart"));
        assertEquals("minecraft:command_block_minecart", EntityNbt.loadedId(":command_block_minecart"));
        assertEquals("minecraft:command_block_minecart", EntityNbt.loadedId("minecraft:command_block_minecart"));
        assertEquals("mymod:thing", EntityNbt.loadedId("mymod:thing"));
        assertEquals("", EntityNbt.loadedId(null));
    }

    /** On Paper (CraftBukkit) every entity saves its age; an armor stand left alone is still the one a step placed. */
    @Test
    void craftBukkitsTickAgeIsVolatile() {
        NbtCompound stand = NbtCompound.builder().putString("id", "minecraft:armor_stand").putInt("Spigot.ticksLived", 1)
                .putString("CustomName", "\"Stand\"").build();
        NbtCompound later = stand.toBuilder().putInt("Spigot.ticksLived", 60).build();
        assertEquals(EntityNbt.withoutVolatile(stand), EntityNbt.withoutVolatile(later));
        NbtCompound renamed = stand.toBuilder().putString("CustomName", "\"Other\"").build();
        assertNotEquals(EntityNbt.withoutVolatile(stand), EntityNbt.withoutVolatile(renamed), "names stay compared");
    }

    @Test
    void countersMobsKeepByThemselvesAreVolatile() {
        NbtCompound chicken = NbtCompound.builder().putString("id", "minecraft:chicken").putInt("EggLayTime", 6000)
                .putByte("PersistenceRequired", (byte) 0).putString("CustomName", "\"Hen\"").build();
        NbtCompound later = chicken.toBuilder().putInt("EggLayTime", 5980).putByte("PersistenceRequired", (byte) 1)
                .build();
        assertEquals(EntityNbt.withoutVolatile(chicken), EntityNbt.withoutVolatile(later));
        NbtCompound renamed = chicken.toBuilder().putString("CustomName", "\"Rooster\"").build();
        assertNotEquals(EntityNbt.withoutVolatile(chicken), EntityNbt.withoutVolatile(renamed), "names stay compared");
        for (String key : List.of("DespawnDelay", "AngerTime", "Sleeping", "HomePosX", "wander_target", "PuffState",
                "carriedBlockState", "Gossips", "RestocksToday")) {
            assertTrue(EntityNbt.VOLATILE_KEYS.contains(key), key);
        }
        assertFalse(EntityNbt.VOLATILE_KEYS.contains("VillagerData"), "a profession stays compared");
        assertFalse(EntityNbt.VOLATILE_KEYS.contains("Item"), "a frame's item stays compared");
    }
}
