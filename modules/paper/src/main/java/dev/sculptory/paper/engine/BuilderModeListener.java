package dev.sculptory.paper.engine;

import dev.sculptory.mc.engine.impl.BuilderService;
import dev.sculptory.server.engine.impl.EngineEditService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.PlaceOnWaterBlockItem;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Builder mode on Paper, what Fabric's {@code ServerPlayerInteractionManagerMixin} does: while builder mode acts for a
 * player ({@link BuilderService#acts}: a power on, and Creative with the builder permission), their block placements
 * come as the client's own {@code BuilderPlace} and go through the engine; a vanilla interact packet still arrives
 * when the client's copy of the clicked block took the right-click. When the server's block use then passes (a stale
 * view, redstone ore that lights up), vanilla would go on to the item's {@code useOn} and place the held block outside
 * the history, unmirrored and with physics. So for a block item in builder mode the item use is denied
 * ({@code setUseItemInHand(DENY)}, which skips {@code useOn}); the block's own use (a door, a chest) still runs, and
 * other items (a bucket, bone meal) keep vanilla's way. A player whose client keeps a power on where builder mode
 * cannot act (Survival, no permission) clicks the vanilla way, so their placement is left alone.
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
        Item item = player.getItemInHand(hand).getItem();
        // A lily pad or frogspawn is placed by its own use on the water, which the client sends the vanilla way and
        // builder mode leaves alone; a denied block click just before may make CraftBukkit refuse that use too.
        if (!(item instanceof BlockItem) || item instanceof PlaceOnWaterBlockItem) return;
        if (!(service.builderMode() instanceof BuilderService builder) || !builder.acts(player)) return;
        event.setUseItemInHand(Event.Result.DENY);
    }
}
