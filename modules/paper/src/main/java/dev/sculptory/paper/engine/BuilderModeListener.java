package dev.sculptory.paper.engine;

import dev.sculptory.server.engine.impl.EngineEditService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Builder mode on Paper, what Fabric's {@code ServerPlayerInteractionManagerMixin} does: while a player has a builder
 * power on, their block placements come as the client's own {@code BuilderPlace} and go through the engine; a vanilla
 * interact packet still arrives when the client's copy of the clicked block took the right-click. When the server's
 * block use then passes (a stale view, redstone ore that lights up), vanilla would go on to the item's {@code useOn} and
 * place the held block outside the history, unmirrored and with physics. So for a block item in builder mode the item
 * use is denied ({@code setUseItemInHand(DENY)}, which skips {@code useOn}); the block's own use (a door, a chest) still
 * runs, and other items (a bucket, bone meal) keep vanilla's way.
 */
final class BuilderModeListener implements Listener {
    private final EngineEditService<ServerPlayer, ServerLevel> service;

    BuilderModeListener(EngineEditService<ServerPlayer, ServerLevel> service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() == null) return;
        ServerPlayer player = ((CraftPlayer) event.getPlayer()).getHandle();
        InteractionHand hand = event.getHand() == EquipmentSlot.OFF_HAND ? InteractionHand.OFF_HAND
                : InteractionHand.MAIN_HAND;
        if (!(player.getItemInHand(hand).getItem() instanceof BlockItem)) return;
        if (service.builderPowersOf(player.getUUID()) == 0) return;
        event.setUseItemInHand(Event.Result.DENY);
    }
}
