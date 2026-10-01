package dev.sculptory.fabric.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Builder mode places a block the way {@code BlockItem.place(ItemPlacementContext)} does, but with a state it chose
 * (Replace keeps the old block's facing, Force place overrides the game's refusal): these are that method's steps.
 * The protected ones are virtual, so items that override them (doors, beds, signs, torches) keep their behaviour.
 * Signatures verified with javap against yarn 1.21.1+build.3.
 */
@Mixin(BlockItem.class)
public interface BlockItemInvoker {
    @Invoker("getPlacementState")
    BlockState sculptory$getPlacementState(ItemPlacementContext context);

    @Invoker("place")
    boolean sculptory$place(ItemPlacementContext context, BlockState state);

    @Invoker("placeFromNbt")
    BlockState sculptory$placeFromNbt(BlockPos pos, World world, ItemStack stack, BlockState state);

    @Invoker("postPlacement")
    boolean sculptory$postPlacement(BlockPos pos, World world, PlayerEntity player, ItemStack stack, BlockState state);

    @Invoker("getPlaceSound")
    SoundEvent sculptory$getPlaceSound(BlockState state);

    @Invoker("copyComponentsToBlockEntity")
    static void sculptory$copyComponentsToBlockEntity(World world, BlockPos pos, ItemStack stack) {
        throw new AssertionError("mixin");
    }
}
