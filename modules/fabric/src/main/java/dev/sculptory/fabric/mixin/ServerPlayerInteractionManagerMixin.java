package dev.sculptory.fabric.mixin;

import dev.sculptory.fabric.engine.impl.EditServiceHost;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Builder mode: while a player has a builder power on, their block
 * placements come as {@code BuilderPlace} and go through the engine; a vanilla interact packet still arrives when the
 * client's copy of the block took the right-click ({@code onUse} accepted on the client). When the server's block use
 * then passes (redstone ore lights on the client and passes here; a sign waxed meanwhile; a stale view), vanilla would
 * fall through to {@code ItemStack.useOnBlock} and place the held block outside the history, unmirrored and with
 * physics. This turns that fall-through into {@code PASS} for block items while builder mode is on; other items (a
 * bucket, bone meal) keep vanilla's way. Both {@code useOnBlock} calls of {@code interactBlock} (creative and not)
 * are covered. Descriptor verified with javap against yarn 1.21.1+build.3.
 */
@Mixin(ServerPlayerInteractionManager.class)
public abstract class ServerPlayerInteractionManagerMixin {
    @Shadow
    @Final
    protected ServerPlayerEntity player;

    @Redirect(method = "interactBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/item/ItemStack;useOnBlock(Lnet/minecraft/item/ItemUsageContext;)Lnet/minecraft/util/ActionResult;"))
    private ActionResult sculptory$noVanillaPlacementInBuilderMode(ItemStack stack, ItemUsageContext context) {
        if (stack.getItem() instanceof BlockItem && EditServiceHost.builderPowers(player.getUuid()) != 0) {
            return ActionResult.PASS;
        }
        return stack.useOnBlock(context);
    }
}
