package dev.sculptory.mc.mixin;

import dev.sculptory.mc.engine.impl.EditServiceHost;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Builder mode: while a player has a builder power on, their block
 * placements come as {@code BuilderPlace} and go through the engine; a vanilla interact packet still arrives when the
 * client's copy of the block took the right-click ({@code useWithoutItem} accepted on the client). When the server's block use
 * then passes (redstone ore lights on the client and passes here; a sign waxed meanwhile; a stale view), vanilla would
 * fall through to {@code ItemStack.useOn} and place the held block outside the history, unmirrored and with
 * physics. This turns that fall-through into {@code PASS} for block items while builder mode is on; other items (a
 * bucket, bone meal) keep vanilla's way. Both {@code useOn} calls of {@code useItemOn} (creative and not)
 * are covered. Descriptor verified with javap against Minecraft 1.21.1 (Mojang names).
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerInteractionManagerMixin {
    @Shadow
    @Final
    protected ServerPlayer player;

    @Redirect(method = "useItemOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/ItemStack;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;"))
    private InteractionResult sculptory$noVanillaPlacementInBuilderMode(ItemStack stack, UseOnContext context) {
        if (stack.getItem() instanceof BlockItem && EditServiceHost.builderPowers(player.getUUID()) != 0) {
            return InteractionResult.PASS;
        }
        return stack.useOn(context);
    }
}
