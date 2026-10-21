package dev.sculptory.neoforge.client;

import dev.sculptory.mc.loader.ClientLoader;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The hooks the client mixins of this module call ({@code MultiPlayerGameModeMixin}, {@code LevelRendererMixin}),
 * combined as Fabric API combines its event listeners: the hooks are asked in registration order and the first answer
 * that is not PASS wins (the rest are not asked). Render thread only.
 */
public final class Interactions {
    private static final List<ClientLoader.UseBlock> USE_BLOCK = new CopyOnWriteArrayList<>();
    private static final List<ClientLoader.UseItem> USE_ITEM = new CopyOnWriteArrayList<>();
    private static final List<ClientLoader.AttackBlock> ATTACK_BLOCK = new CopyOnWriteArrayList<>();
    private static final List<ClientLoader.AttackEntity> ATTACK_ENTITY = new CopyOnWriteArrayList<>();
    private static final List<Runnable> RENDER_STATE_INVALIDATED = new CopyOnWriteArrayList<>();

    private Interactions() {
    }

    static void useBlock(ClientLoader.UseBlock hook) {
        USE_BLOCK.add(hook);
    }

    static void useItem(ClientLoader.UseItem hook) {
        USE_ITEM.add(hook);
    }

    static void attackBlock(ClientLoader.AttackBlock hook) {
        ATTACK_BLOCK.add(hook);
    }

    static void attackEntity(ClientLoader.AttackEntity hook) {
        ATTACK_ENTITY.add(hook);
    }

    static void renderStateInvalidated(Runnable hook) {
        RENDER_STATE_INVALIDATED.add(hook);
    }

    public static InteractionResult useBlock(Player player, Level world, InteractionHand hand, BlockHitResult hit) {
        for (ClientLoader.UseBlock hook : USE_BLOCK) {
            InteractionResult result = hook.use(player, world, hand, hit);
            if (result != InteractionResult.PASS) return result;
        }
        return InteractionResult.PASS;
    }

    public static InteractionResultHolder<ItemStack> useItem(Player player, Level world, InteractionHand hand) {
        for (ClientLoader.UseItem hook : USE_ITEM) {
            InteractionResultHolder<ItemStack> result = hook.use(player, world, hand);
            if (result.getResult() != InteractionResult.PASS) return result;
        }
        return InteractionResultHolder.pass(player.getItemInHand(hand));
    }

    public static InteractionResult attackBlock(Player player, Level world, InteractionHand hand, BlockPos pos,
                                                Direction face) {
        for (ClientLoader.AttackBlock hook : ATTACK_BLOCK) {
            InteractionResult result = hook.attack(player, world, hand, pos, face);
            if (result != InteractionResult.PASS) return result;
        }
        return InteractionResult.PASS;
    }

    public static InteractionResult attackEntity(Player player, Level world, InteractionHand hand, Entity entity) {
        for (ClientLoader.AttackEntity hook : ATTACK_ENTITY) {
            InteractionResult result = hook.attack(player, world, hand, entity, null);
            if (result != InteractionResult.PASS) return result;
        }
        return InteractionResult.PASS;
    }

    public static void renderStateInvalidated() {
        RENDER_STATE_INVALIDATED.forEach(Runnable::run);
    }
}
