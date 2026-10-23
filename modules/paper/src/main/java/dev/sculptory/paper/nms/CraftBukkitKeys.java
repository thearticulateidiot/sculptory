package dev.sculptory.paper.nms;

import dev.sculptory.core.entity.EntityNbt;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;

/**
 * The keys CraftBukkit (Paper, Spigot) writes into a saved entity that describe where and when it was saved or
 * spawned rather than the entity itself, and which captures keep which of them.
 *
 * <ul>
 *   <li><b>Every capture</b> (history and clipboards): Paper's world layer takes the {@link #STRIPPED} keys out of an
 *       entity's NBT as it is captured ({@code McEntities.save}, patched by the modules/paper build): the id of the world
 *       it was saved in and its age in ticks. So no capture holds this server's world id, and the age, which grows
 *       every tick by itself, does not make undo see an entity as changed.</li>
 *   <li><b>Clipboards only</b> (a copy, a move's or stack's copies, a schematic import): the spawn context, how and
 *       where the server first spawned the entity ({@link EntityNbt#SPAWN_CONTEXT_KEYS}: {@code Paper.SpawnReason},
 *       {@code Paper.Origin}, {@code Paper.OriginWorld}, {@code Paper.SpawningEntity}, {@code Paper.FromNetherPortal}),
 *       is left out by {@link EntityNbt#snapshotData}: a pasted copy is a new spawn and gets its own from the server
 *       (its spawn event carries the plugin reason {@code CUSTOM} the engine places with; Paper records the reason
 *       {@code DEFAULT}, as for any entity loaded from data without one, and the origin where it lands).
 *       History keeps it, so an undo or redo puts back the same entity, and plugins that read where a mob came from
 *       (farm limiters, mob stackers) see the same answer as before the edit.</li>
 *   <li><b>Kept everywhere</b>: every other CraftBukkit key, in particular {@code Bukkit.updateLevel} (CraftBukkit's data
 *       version: a mob applies a saved {@code CanPickUpLoot} or {@code PersistenceRequired} of false only when it is at
 *       least 1, so without it a fox, dolphin or panda would pick items up again after a paste or an undo), the
 *       settings a plugin made through Paper's API ({@code Bukkit.invisible}, {@code Bukkit.persist},
 *       {@code Bukkit.Aware}, {@code Paper.ShouldBurnInDay}, {@code Bukkit.MaxHealth}, ...) and plugin data
 *       ({@code BukkitValues}): they survive a copy, a paste and an undo. So does {@code Paper.FromMobSpawner}: it is
 *       spawn context, but plugins read it through Paper's {@code Entity.fromMobSpawner()} (mob stackers, farm and skill
 *       plugins treat spawner mobs differently), so a copied spawner mob stays one.</li>
 * </ul>
 */
public final class CraftBukkitKeys {
    /**
     * The keys every capture takes out, each with why (checked against Paper 1.21.1 build 133:
     * {@code Entity.saveWithoutId} writes them).
     */
    public static final Map<String, String> STRIPPED = Map.of(
            "WorldUUIDMost", "the world the entity was saved in (with WorldUUIDLeast); a copy lands in another place",
            "WorldUUIDLeast", "the world the entity was saved in (with WorldUUIDMost)",
            "Spigot.ticksLived", "the entity's age in ticks, which grows every tick by itself");

    private CraftBukkitKeys() {
    }

    /** Whether {@code key} is one of the {@link #STRIPPED} keys. */
    public static boolean stripped(String key) {
        return STRIPPED.containsKey(key);
    }

    /** Whether {@code key} is spawn context, which clipboards leave out and history keeps. */
    public static boolean spawnContext(String key) {
        return EntityNbt.SPAWN_CONTEXT_KEYS.contains(key);
    }

    /** Removes the {@link #STRIPPED} keys from the top level of {@code entity} (passengers are saved, and stripped, one by one). */
    public static CompoundTag strip(CompoundTag entity) {
        for (String key : STRIPPED.keySet()) entity.remove(key);
        return entity;
    }
}
