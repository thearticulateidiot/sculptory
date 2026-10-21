package dev.sculptory.paper.nms;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.nbt.CompoundTag;

/**
 * The keys CraftBukkit (Paper, Spigot) adds to every saved entity, beside vanilla's: {@code Bukkit.*}
 * ({@code Bukkit.updateLevel}, {@code Bukkit.Aware}, ...), {@code Paper.*} ({@code Paper.Origin},
 * {@code Paper.SpawnReason}, ...), {@code Spigot.ticksLived}, and the world the entity was saved in
 * ({@code WorldUUIDMost}/{@code WorldUUIDLeast}). Paper's world layer takes them out of an entity's NBT as it is
 * captured ({@code McEntities.save}, patched by the modules/paper build), so a clipboard, a schematic or the history
 * holds the entity as vanilla saves it: the same on every platform, comparable with what a placement of it saves (the
 * game rewrites several of these by itself, so undo would see every entity a step placed as changed), and without a
 * world id or spawn origin from another server. A placed entity gets fresh values from CraftBukkit. Plugin data
 * ({@code BukkitValues}, the persistent data container) is not among them and is kept.
 *
 * <p>Some of these hold settings a plugin made through Paper's API ({@code Bukkit.invisible}, {@code Bukkit.persist},
 * {@code Paper.ShouldBurnInDay}, ...); a copy or an undo does not carry them.
 */
public final class CraftBukkitKeys {
    private CraftBukkitKeys() {
    }

    /** Whether {@code key} is one of CraftBukkit's top-level entity keys. */
    public static boolean isCraftBukkit(String key) {
        return key.startsWith("Bukkit.") || key.startsWith("Paper.") || key.startsWith("Spigot.")
                || key.equals("WorldUUIDMost") || key.equals("WorldUUIDLeast");
    }

    /** Removes CraftBukkit's keys from the top level of {@code entity} (passengers are saved, and stripped, one by one). */
    public static CompoundTag strip(CompoundTag entity) {
        List<String> found = new ArrayList<>();
        for (String key : entity.getAllKeys()) {
            if (isCraftBukkit(key)) found.add(key);
        }
        for (String key : found) entity.remove(key);
        return entity;
    }
}
