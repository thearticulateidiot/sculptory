package dev.sculptory.mc.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Builder mode places a block the way {@code BlockItem.place(BlockPlaceContext)} does, but with a state it chose
 * (Replace keeps the old block's facing, Force place overrides the game's refusal): these are that method's steps.
 * The protected ones are virtual, so items that override them (doors, beds, signs, torches) keep their behaviour.
 * Signatures verified with javap against Minecraft 1.21.1 (Mojang names).
 */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
    @Invoker("getPlacementState")
    BlockState sculptory$getPlacementState(BlockPlaceContext context);

    @Invoker("placeBlock")
    boolean sculptory$place(BlockPlaceContext context, BlockState state);

    @Invoker("updateBlockStateFromTag")
    BlockState sculptory$placeFromNbt(BlockPos pos, Level world, ItemStack stack, BlockState state);

    @Invoker("updateCustomBlockEntityTag")
    boolean sculptory$postPlacement(BlockPos pos, Level world, Player player, ItemStack stack, BlockState state);

    @Invoker("getPlaceSound")
    SoundEvent sculptory$getPlaceSound(BlockState state);

    @Invoker("updateBlockEntityComponents")
    static void sculptory$copyComponentsToBlockEntity(Level world, BlockPos pos, ItemStack stack) {
        throw new AssertionError("mixin");
    }
}
