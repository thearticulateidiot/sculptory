package dev.sculptory.mc.mixin;

import dev.sculptory.mc.engine.impl.EditServiceHost;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Builder mode, at the item's own use on a block ({@code ItemStack.useOn} in {@code useItemOn}; both of its calls,
 * creative and not, are covered). Descriptor verified with javap against Minecraft 1.21.1 (Mojang names).
 *
 * <p><b>Block items.</b> While a player has a builder power on, their block placements come as {@code BuilderPlace} and
 * go through the engine; a vanilla interact packet still arrives when the client's copy of the block took the
 * right-click ({@code useWithoutItem} accepted on the client). When the server's block use then passes (redstone ore
 * lights on the client and passes here; a sign waxed meanwhile; a stale view), vanilla would fall through to
 * {@code useOn} and place the held block outside the history, unmirrored and with physics. This turns that
 * fall-through into {@code PASS} for block items while builder mode is on.
 *
 * <p><b>Item frames, paintings, armor stands.</b> Their use goes to builder mode ({@code BuilderService.useEntityItem}):
 * refused like a block where builder mode would refuse a block (reach, protection, the mask), else vanilla's own use,
 * whose spawned entity becomes one history step. Other items (a bucket, bone meal) keep vanilla's way.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerInteractionManagerMixin {
    @Shadow
    @Final
    protected ServerPlayer player;

    @Redirect(method = "useItemOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult sculptory$noVanillaPlacementInBuilderMode(ItemStack stack, UseOnContext context) {
        if (EditServiceHost.builderPowers(player.getUUID()) != 0) {
            if (stack.getItem() instanceof BlockItem) return InteractionResult.PASS;
            if (stack.getItem() instanceof HangingEntityItem || stack.getItem() instanceof ArmorStandItem) {
                return EditServiceHost.builderEntityItem(player, stack, context);
            }
        }
        return stack.useOn(context);
    }
}
