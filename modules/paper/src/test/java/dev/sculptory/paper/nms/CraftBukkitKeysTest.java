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
    void craftBukkitsKeysGoAndVanillasAndPluginDataStay() {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:armor_stand");
        entity.put("Pos", new ListTag());
        entity.putBoolean("Invisible", true);
        entity.putInt("Spigot.ticksLived", 1234);
        entity.putInt("Bukkit.updateLevel", 2);
        entity.putBoolean("Bukkit.Aware", true);
        entity.put("Paper.Origin", new ListTag());
        entity.putString("Paper.SpawnReason", "DEFAULT");
        entity.putLong("WorldUUIDMost", 1L);
        entity.putLong("WorldUUIDLeast", 2L);
        CompoundTag pluginData = new CompoundTag();
        pluginData.putString("someplugin:owner", "x");
        entity.put("BukkitValues", pluginData);
        CraftBukkitKeys.strip(entity);
        assertEquals(Set.of("id", "Pos", "Invisible", "BukkitValues"), entity.getAllKeys());
        assertEquals("x", entity.getCompound("BukkitValues").getString("someplugin:owner"));
    }

    @Test
    void onlyThePrefixesAndTheWorldIdCount() {
        assertTrue(CraftBukkitKeys.isCraftBukkit("Paper.FreezeLock"));
        assertTrue(CraftBukkitKeys.isCraftBukkit("WorldUUIDLeast"));
        assertFalse(CraftBukkitKeys.isCraftBukkit("BukkitValues"));
        assertFalse(CraftBukkitKeys.isCraftBukkit("PaperRolls"));
        assertFalse(CraftBukkitKeys.isCraftBukkit("UUID"));
        assertFalse(CraftBukkitKeys.isCraftBukkit("WorldUUID"));
    }
}
