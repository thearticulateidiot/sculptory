package dev.sculptory.neoforge.client.mixin;

import dev.sculptory.neoforge.client.Interactions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The client loader's interaction hooks ({@code ClientLoader.onUseBlock} and the others), asked where Fabric API's
 * {@code ClientPlayerInteractionManagerMixin} (fabric-events-interaction-v0 in Fabric API 0.116.4+1.21.1, read with
 * javap) asks its events, with the same results: PASS goes on with vanilla (and then NeoForge's own events); SUCCESS
 * skips vanilla's handling and still sends the interaction packet; CONSUME and FAIL send nothing (for a block attack,
 * CONSUME still sends the start-destroy packet, as there). NeoForge's interaction events cannot do that (see
 * {@code NeoForgeClientLoader}). Each injection point exists in NeoForge 21.1's patched {@code MultiPlayerGameMode} and
 * comes before NeoForge's own event in the method.
 */
@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Shadow
    @Final
    private ClientPacketListener connection;

    @Shadow
    private GameType localPlayerMode;

    @Shadow
    protected abstract void startPrediction(ClientLevel level, PredictiveAction action);

    /** Right-click on a block: after the world-border check, before the prediction that runs vanilla's handling. */
    @Inject(method = "useItemOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;startPrediction(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/client/multiplayer/prediction/PredictiveAction;)V"),
            cancellable = true)
    private void sculptory$useBlock(LocalPlayer player, InteractionHand hand, BlockHitResult hit,
                                    CallbackInfoReturnable<InteractionResult> cir) {
        if (player.isSpectator()) return;
        InteractionResult result = Interactions.useBlock(player, player.level(), hand, hit);
        if (result == InteractionResult.PASS) return;
        if (result == InteractionResult.SUCCESS) {
            startPrediction(minecraft.level, sequence -> new ServerboundUseItemOnPacket(hand, hit, sequence));
        }
        cir.setReturnValue(result);
    }

    /** Right-click with the item in hand: after the spectator check, before vanilla's prediction. */
    @Inject(method = "useItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;ensureHasSentCarriedItem()V", ordinal = 0),
            cancellable = true)
    private void sculptory$useItem(Player player, InteractionHand hand, CallbackInfoReturnable<InteractionResult> cir) {
        InteractionResultHolder<ItemStack> result = Interactions.useItem(player, player.level(), hand);
        if (result.getResult() == InteractionResult.PASS) return;
        if (result.getResult() == InteractionResult.SUCCESS) {
            startPrediction((ClientLevel) player.level(), sequence ->
                    new ServerboundUseItemPacket(hand, sequence, player.getYRot(), player.getXRot()));
        }
        cir.setReturnValue(result.getResult());
    }

    /** Left-click on a block: after the restriction and world-border checks. */
    @Inject(method = "startDestroyBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/GameType;isCreative()Z", ordinal = 0), cancellable = true)
    private void sculptory$attackBlock(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        attackBlock(pos, face, cir);
    }

    /** Holding left-click in creative breaks a block every few ticks: those are attacks too, as on Fabric. */
    @Inject(method = "continueDestroyBlock", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/GameType;isCreative()Z", ordinal = 0), cancellable = true)
    private void sculptory$continueAttackBlock(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        if (localPlayerMode.isCreative()) attackBlock(pos, face, cir);
    }

    private void attackBlock(BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        InteractionResult result = Interactions.attackBlock(minecraft.player, minecraft.level, InteractionHand.MAIN_HAND,
                pos, face);
        if (result == InteractionResult.PASS) return;
        // True swings the arm and shows the hit particles: SUCCESS only.
        cir.setReturnValue(result == InteractionResult.SUCCESS);
        if (result.consumesAction()) {
            startPrediction(minecraft.level, sequence -> new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, face, sequence));
        }
    }

    /** Left-click on an entity: before the attack packet is sent. */
    @Inject(method = "attack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V",
            ordinal = 0), cancellable = true)
    private void sculptory$attackEntity(Player player, Entity target, CallbackInfo ci) {
        InteractionResult result = Interactions.attackEntity(player, player.level(), InteractionHand.MAIN_HAND, target);
        if (result == InteractionResult.PASS) return;
        if (result == InteractionResult.SUCCESS) {
            connection.send(ServerboundInteractPacket.createAttackPacket(target, player.isShiftKeyDown()));
        }
        ci.cancel();
    }
}
