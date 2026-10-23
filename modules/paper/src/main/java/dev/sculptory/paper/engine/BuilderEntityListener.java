package dev.sculptory.paper.engine;

import dev.sculptory.core.history.EntityChange;
import dev.sculptory.core.history.EntityMatcher;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.mc.engine.impl.BuilderService;
import dev.sculptory.mc.world.EntityWriter;
import dev.sculptory.mc.world.McEntities;
import dev.sculptory.server.engine.BuilderOutcome.Refusal;
import dev.sculptory.server.engine.impl.EngineEditService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * Builder mode on Paper, what the loaders' {@code ServerPlayerInteractionManagerMixin} (item frames, paintings, armor
 * stands placed) and {@code BuilderInteractMixin} (those entities right-clicked or punched) do, through Bukkit's events,
 * since a plugin has no mixins. While a player has a builder power on and builder mode is allowed
 * ({@link BuilderService#recordsEntities}):
 * <ul>
 *   <li>a placement ({@link HangingPlaceEvent}, the armor stand's {@link EntityPlaceEvent}) where builder mode would
 *       refuse a block (reach, protection, the mask: {@link BuilderService#entityPlacementRefusal}) is cancelled; one
 *       that goes ahead (no plugin cancelled it) is one history step, "Place · item frame", read once the game has
 *       added the entity;</li>
 *   <li>an interaction ({@link PlayerInteractEntityEvent}, {@link PlayerInteractAtEntityEvent},
 *       {@link PlayerArmorStandManipulateEvent}) or an attack ({@link EntityDamageByEntityEvent},
 *       {@link HangingBreakByEntityEvent}) that no plugin cancelled has the entity read before the game applies it, and compared afterwards: a change is one step,
 *       "Change · armor stand", a removal "Break · painting"; no change, nothing.</li>
 * </ul>
 * The events come before the game acts, so each read waits for the end of the tick ({@link #tickEnded}), or for the next
 * event about the same entity, whichever comes first; the steps are pushed in the order their events came. Since the
 * entity ticked once by then, the comparison leaves out where it stands, its motion and fall; and a frame or painting
 * that drops off by itself in between ({@link HangingBreakEvent} not by an entity) is not recorded as the player's.
 * A step is pushed after the block steps of the same tick: one tick's mix of block and entity actions may be ordered
 * differently in the history than it happened.
 */
final class BuilderEntityListener implements Listener {
    private final EngineEditService<ServerPlayer, ServerLevel> service;
    /** One open read per entity, in the order the events came. */
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    /** An entity read when an event announced an action on it: placed ({@code before} null), or changed. */
    private record Pending(ServerPlayer player, Entity entity, EntityState before, boolean placed) {}

    BuilderEntityListener(EngineEditService<ServerPlayer, ServerLevel> service) {
        this.service = service;
    }

    // ---- placements

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void refuseHanging(HangingPlaceEvent event) {
        if (event.getPlayer() == null) return;
        if (refused(handle(event.getPlayer()), ((CraftEntity) event.getEntity()).getHandle())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void placedHanging(HangingPlaceEvent event) {
        if (event.getPlayer() == null) return;
        placed(handle(event.getPlayer()), ((CraftEntity) event.getEntity()).getHandle());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void refuseStand(EntityPlaceEvent event) {
        if (event.getPlayer() == null) return;
        Entity entity = ((CraftEntity) event.getEntity()).getHandle();
        if (!(entity instanceof net.minecraft.world.entity.decoration.ArmorStand)) return;
        if (refused(handle(event.getPlayer()), entity)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void placedStand(EntityPlaceEvent event) {
        if (event.getPlayer() == null) return;
        Entity entity = ((CraftEntity) event.getEntity()).getHandle();
        if (entity instanceof net.minecraft.world.entity.decoration.ArmorStand) placed(handle(event.getPlayer()), entity);
    }

    /** Whether builder mode refuses the entity about to be placed where it would refuse a block. */
    private boolean refused(ServerPlayer player, Entity entity) {
        BuilderService builder = builder();
        if (builder == null || !BuilderService.recordedKind(entity) || !builder.recordsEntities(player)) return false;
        Refusal refusal = builder.entityPlacementRefusal(player, player.serverLevel(), McEntities.cell(entity));
        return refusal != null;
    }

    private void placed(ServerPlayer player, Entity entity) {
        BuilderService builder = builder();
        if (builder == null || !BuilderService.recordedKind(entity) || !builder.recordsEntities(player)) return;
        settle(entity.getUUID());
        pending.put(entity.getUUID(), new Pending(player, entity, null, true));
    }

    // ---- interactions and attacks

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void interacted(PlayerInteractEntityEvent event) {
        touched(handle(event.getPlayer()), ((CraftEntity) event.getRightClicked()).getHandle());
    }

    // The interact-at and armor stand events extend the interact event but keep handler lists of their own: a listener
    // of the interact event never hears them.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void interactedAt(PlayerInteractAtEntityEvent event) {
        touched(handle(event.getPlayer()), ((CraftEntity) event.getRightClicked()).getHandle());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void manipulated(PlayerArmorStandManipulateEvent event) {
        touched(handle(event.getPlayer()), ((CraftEntity) event.getRightClicked()).getHandle());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void attacked(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof org.bukkit.entity.Player player)) {
            // Damage by something else (an arrow emptying a frame, a mob) is not the change a player's read waits for.
            Pending read = pending.get(event.getEntity().getUniqueId());
            if (read != null && !read.placed()) pending.remove(event.getEntity().getUniqueId());
            return;
        }
        touched(handle(player), ((CraftEntity) event.getEntity()).getHandle());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(HangingBreakByEntityEvent event) {
        if (!(event.getRemover() instanceof org.bukkit.entity.Player player)) return;
        touched(handle(player), ((CraftEntity) event.getEntity()).getHandle());
    }

    /**
     * A frame or painting removed by anything but a player (its wall gone, its space filled, an explosion, an arrow, a
     * mob) between the player's action and the end of the tick: what the player did to it is not what removed it, so
     * its read is dropped rather than recorded as the player's Break. A player breaking it is handled as their action
     * ({@link #broken}); the by-entity event shares this event's handler list.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void droppedOff(HangingBreakEvent event) {
        // A player breaking it is an action of theirs ({@link #broken}); anything else (an arrow, a creeper, the game)
        // is not the player's who acted on it.
        if (event instanceof HangingBreakByEntityEvent byEntity
                && byEntity.getRemover() instanceof org.bukkit.entity.Player) {
            return;
        }
        pending.remove(event.getEntity().getUniqueId());
    }

    /**
     * The game is about to apply an action of {@code player} on {@code entity}: read it now, unless this tick already
     * read it for the same player's action (the armor stand's interact, interact-at and manipulate events are one
     * click). A read of an earlier action by someone else is settled first.
     */
    private void touched(ServerPlayer player, Entity entity) {
        BuilderService builder = builder();
        if (builder == null || entity.isRemoved() || entity.isPassenger() || !BuilderService.recordedKind(entity)
                || !builder.recordsEntities(player)) {
            return;
        }
        Pending open = pending.get(entity.getUUID());
        if (open != null && !open.placed() && open.player() == player) return;
        settle(entity.getUUID());
        EntityState before = EntityWriter.live(entity);
        if (before != null) pending.put(entity.getUUID(), new Pending(player, entity, before, false));
    }

    // ---- settling

    /** End of the server tick: every read of the tick is settled, in the order the events came. */
    void tickEnded() {
        if (pending.isEmpty()) return;
        List<UUID> ids = new ArrayList<>(pending.keySet());
        for (UUID id : ids) settle(id);
    }

    /** Settles the open read of entity {@code id}, if any: what the action did becomes one history step. */
    private void settle(UUID id) {
        Pending read = pending.remove(id);
        BuilderService builder = builder();
        if (read == null || builder == null) return;
        Entity entity = read.entity();
        ServerLevel world = read.player().serverLevel();
        boolean there = !entity.isRemoved() && entity.level() instanceof ServerLevel level
                && level.getEntity(entity.getUUID()) == entity;
        if (read.placed()) {
            if (!there) return; // turned away after the event (another plugin, a full chunk): nothing placed
            EntityState after = EntityWriter.live(entity);
            if (after == null) {
                entity.discard(); // history could not keep it: nothing undo does not know about stays
                return;
            }
            builder.chargePlacement(read.player());
            builder.pushEntities(read.player(), (ServerLevel) entity.level(), "Place · " + BuilderService.name(entity),
                    List.of(new EntityChange(entity.getUUID(), null, after)));
            return;
        }
        EntityState after = entity.isRemoved() ? null : EntityWriter.live(entity);
        if (!entity.isRemoved() && after == null) return; // too large to record now
        // Read at the end of the tick, after the entity ticked once: where it stands, its motion and fall are left out
        // (volatile), only what it holds, wears and shows counts.
        if (after != null && EntityMatcher.ignoringVolatile().matches(read.before(), after)) return;
        String verb = after == null ? "Break" : "Change";
        builder.pushEntities(read.player(), entity.level() instanceof ServerLevel level ? level : world,
                verb + " · " + BuilderService.name(entity),
                List.of(new EntityChange(entity.getUUID(), read.before(), after)));
    }

    private BuilderService builder() {
        return service.builderMode() instanceof BuilderService builder ? builder : null;
    }

    private static ServerPlayer handle(org.bukkit.entity.Player player) {
        return ((CraftPlayer) player).getHandle();
    }
}
