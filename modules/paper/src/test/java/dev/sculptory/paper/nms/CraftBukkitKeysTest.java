package dev.sculptory.paper.nms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class CraftBukkitKeysTest {
    @Test
    void theWorldAndTheAgeGoAndEverythingElseStays() {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:armor_stand");
        entity.put("Pos", new ListTag());
        entity.putBoolean("Invisible", true);
        // Stripped from every capture: world-bound or volatile.
        entity.putInt("Spigot.ticksLived", 1234);
        entity.putLong("WorldUUIDMost", 1L);
        entity.putLong("WorldUUIDLeast", 2L);
        // Kept by every capture: CraftBukkit's data version (a mob's CanPickUpLoot false depends on it).
        entity.putInt("Bukkit.updateLevel", 2);
        // Kept here: spawn context, which history keeps and clipboards leave out (EntityNbt.snapshotData).
        entity.put("Paper.Origin", new ListTag());
        entity.putUUID("Paper.OriginWorld", java.util.UUID.randomUUID());
        entity.putString("Paper.SpawnReason", "DEFAULT");
        entity.putBoolean("Paper.FromNetherPortal", true);
        entity.putUUID("Paper.SpawningEntity", java.util.UUID.randomUUID());
        // Kept: settings a plugin made through Paper's API, and plugin data.
        entity.putBoolean("Bukkit.invisible", true);
        entity.putBoolean("Bukkit.persist", true);
        entity.putBoolean("Bukkit.Aware", false);
        entity.putBoolean("Paper.ShouldBurnInDay", false);
        // Kept: spawn context plugins act on (Entity.fromMobSpawner()).
        entity.putBoolean("Paper.FromMobSpawner", true);
        CompoundTag pluginData = new CompoundTag();
        pluginData.putString("someplugin:owner", "x");
        entity.put("BukkitValues", pluginData);
        CraftBukkitKeys.strip(entity);
        assertEquals(Set.of("id", "Pos", "Invisible", "Bukkit.updateLevel", "Paper.Origin", "Paper.OriginWorld",
                "Paper.SpawnReason", "Paper.FromNetherPortal", "Paper.SpawningEntity", "Bukkit.invisible",
                "Bukkit.persist", "Bukkit.Aware", "Paper.ShouldBurnInDay", "Paper.FromMobSpawner", "BukkitValues"),
                entity.getAllKeys());
        assertEquals("x", entity.getCompound("BukkitValues").getString("someplugin:owner"));
    }

    @Test
    void onlyTheListedKeysAreStripped() {
        assertTrue(CraftBukkitKeys.stripped("WorldUUIDLeast"));
        assertTrue(CraftBukkitKeys.stripped("WorldUUIDMost"));
        assertTrue(CraftBukkitKeys.stripped("Spigot.ticksLived"));
        assertFalse(CraftBukkitKeys.stripped("Bukkit.updateLevel"));
        assertFalse(CraftBukkitKeys.stripped("Paper.Origin"));
        assertFalse(CraftBukkitKeys.stripped("Paper.SpawnReason"));
        assertFalse(CraftBukkitKeys.stripped("Bukkit.persist"));
        assertFalse(CraftBukkitKeys.stripped("Paper.ShouldBurnInDay"));
        assertFalse(CraftBukkitKeys.stripped("Paper.FromMobSpawner"));
        assertFalse(CraftBukkitKeys.stripped("BukkitValues"));
        assertFalse(CraftBukkitKeys.stripped("UUID"));
    }

    @Test
    void theSpawnContextIsWhatClipboardsLeaveOut() {
        for (String key : Set.of("Paper.SpawnReason", "Paper.Origin", "Paper.OriginWorld", "Paper.SpawningEntity",
                "Paper.FromNetherPortal")) {
            assertTrue(CraftBukkitKeys.spawnContext(key), key);
            assertFalse(CraftBukkitKeys.stripped(key), key);
        }
        assertFalse(CraftBukkitKeys.spawnContext("Paper.FromMobSpawner"));
        assertFalse(CraftBukkitKeys.spawnContext("Bukkit.updateLevel"));
    }
}
