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
    void theWorldAndSpawnContextGoesAndPluginSettingsStay() {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:armor_stand");
        entity.put("Pos", new ListTag());
        entity.putBoolean("Invisible", true);
        // Stripped: world-bound or volatile.
        entity.putInt("Spigot.ticksLived", 1234);
        entity.putInt("Bukkit.updateLevel", 2);
        entity.put("Paper.Origin", new ListTag());
        entity.putUUID("Paper.OriginWorld", java.util.UUID.randomUUID());
        entity.putString("Paper.SpawnReason", "DEFAULT");
        entity.putBoolean("Paper.FromMobSpawner", true);
        entity.putBoolean("Paper.FromNetherPortal", true);
        entity.putLong("WorldUUIDMost", 1L);
        entity.putLong("WorldUUIDLeast", 2L);
        // Kept: settings a plugin made through Paper's API, and plugin data.
        entity.putBoolean("Bukkit.invisible", true);
        entity.putBoolean("Bukkit.persist", true);
        entity.putBoolean("Bukkit.Aware", false);
        entity.putBoolean("Paper.ShouldBurnInDay", false);
        CompoundTag pluginData = new CompoundTag();
        pluginData.putString("someplugin:owner", "x");
        entity.put("BukkitValues", pluginData);
        CraftBukkitKeys.strip(entity);
        assertEquals(Set.of("id", "Pos", "Invisible", "Bukkit.invisible", "Bukkit.persist", "Bukkit.Aware",
                "Paper.ShouldBurnInDay", "BukkitValues"), entity.getAllKeys());
        assertEquals("x", entity.getCompound("BukkitValues").getString("someplugin:owner"));
    }

    @Test
    void onlyTheListedKeysAreStripped() {
        assertTrue(CraftBukkitKeys.stripped("Paper.Origin"));
        assertTrue(CraftBukkitKeys.stripped("WorldUUIDLeast"));
        assertTrue(CraftBukkitKeys.stripped("Spigot.ticksLived"));
        assertFalse(CraftBukkitKeys.stripped("Bukkit.persist"));
        assertFalse(CraftBukkitKeys.stripped("Paper.ShouldBurnInDay"));
        assertFalse(CraftBukkitKeys.stripped("BukkitValues"));
        assertFalse(CraftBukkitKeys.stripped("UUID"));
    }
}
