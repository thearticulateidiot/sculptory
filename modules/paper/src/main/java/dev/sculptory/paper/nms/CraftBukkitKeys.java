package dev.sculptory.paper.nms;

import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/**
 * The keys CraftBukkit (Paper, Spigot) writes into a saved entity that describe where and when it was saved or
 * spawned rather than the entity itself. Paper's world layer takes them out of an entity's NBT as it is captured
 * ({@code McEntities.save}, patched by the modules/paper build), so a clipboard, a schematic or the history holds no
 * world id or spawn context from this server, and the game's own counters do not make undo see a placed entity as
 * changed. A placed entity gets fresh values from CraftBukkit.
 *
 * <p>Only the keys listed in {@link #STRIPPED} go, each for its reason. Every other CraftBukkit key stays, in particular
 * the settings a plugin made through Paper's API ({@code Bukkit.invisible}, {@code Bukkit.persist},
 * {@code Bukkit.Aware}, {@code Paper.ShouldBurnInDay}, {@code Bukkit.MaxHealth}, ...) and plugin data
 * ({@code BukkitValues}): they survive a copy, a paste and an undo.
 */
public final class CraftBukkitKeys {
    /** The keys taken out, each with why (checked against Paper 1.21.1 build 133's Entity.saveWithoutId). */
    public static final Map<String, String> STRIPPED = Map.of(
            "WorldUUIDMost", "the world the entity was saved in (with WorldUUIDLeast); a copy lands in another place",
            "WorldUUIDLeast", "the world the entity was saved in (with WorldUUIDMost)",
            "Bukkit.updateLevel", "CraftBukkit's data version for the entity, written on every save",
            "Spigot.ticksLived", "the entity's age in ticks, which grows every tick by itself",
            "Paper.Origin", "where the entity was first spawned (spawn context of this world)",
            "Paper.OriginWorld", "the world the entity was first spawned in",
            "Paper.SpawnReason", "how the entity was first spawned (a copy is placed, not spawned that way)",
            "Paper.FromMobSpawner", "whether a mob spawner spawned it (spawn context, not the entity)",
            "Paper.FromNetherPortal", "whether a nether portal spawned it (spawn context, not the entity)");

    private CraftBukkitKeys() {
    }

    /** Whether {@code key} is one of the {@link #STRIPPED} keys. */
    public static boolean stripped(String key) {
        return STRIPPED.containsKey(key);
    }

    /** Removes the {@link #STRIPPED} keys from the top level of {@code entity} (passengers are saved, and stripped, one by one). */
    public static CompoundTag strip(CompoundTag entity) {
        for (String key : STRIPPED.keySet()) entity.remove(key);
        return entity;
    }
}
