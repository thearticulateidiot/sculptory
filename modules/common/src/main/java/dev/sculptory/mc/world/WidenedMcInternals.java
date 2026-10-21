package dev.sculptory.mc.world;

import dev.sculptory.mc.mixin.BlockItemInvoker;
import dev.sculptory.mc.mixin.VerticallyAttachableBlockItemAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * {@link McInternals} through the access widener (sculptory.accesswidener; NeoForge applies the access transformer
 * generated from it): plain field reads; and builder mode's item steps through the mixin invokers
 * ({@link BlockItemInvoker}, {@link VerticallyAttachableBlockItemAccessor}). The Fabric and NeoForge loaders install it.
 */
public final class WidenedMcInternals implements McInternals {
    public static final WidenedMcInternals INSTANCE = new WidenedMcInternals();

    private WidenedMcInternals() {
    }

    @Override
    public Direction growthDirection(GrowingPlantBlock block) {
        return block.growthDirection;
    }

    @Override
    public <T> PaletteData<T> paletteData(PalettedContainer<T> container) {
        PalettedContainer.Data<T> data = container.data;
        return new PaletteData<>(data.storage(), data.palette());
    }

    @Override
    public BlockState placementState(BlockItem item, BlockPlaceContext context) {
        return ((BlockItemInvoker) item).sculptory$getPlacementState(context);
    }

    @Override
    public boolean placeBlock(BlockItem item, BlockPlaceContext context, BlockState state) {
        return ((BlockItemInvoker) item).sculptory$place(context, state);
    }

    @Override
    public BlockState updateBlockStateFromTag(BlockItem item, BlockPos pos, Level world, ItemStack stack,
                                              BlockState state) {
        return ((BlockItemInvoker) item).sculptory$placeFromNbt(pos, world, stack, state);
    }

    @Override
    public boolean updateCustomBlockEntityTag(BlockItem item, BlockPos pos, Level world, Player player, ItemStack stack,
                                              BlockState state) {
        return ((BlockItemInvoker) item).sculptory$postPlacement(pos, world, player, stack, state);
    }

    @Override
    public SoundEvent placeSound(BlockItem item, BlockState state) {
        return ((BlockItemInvoker) item).sculptory$getPlaceSound(state);
    }

    @Override
    public void updateBlockEntityComponents(Level world, BlockPos pos, ItemStack stack) {
        BlockItemInvoker.sculptory$copyComponentsToBlockEntity(world, pos, stack);
    }

    @Override
    public Block wallBlock(StandingAndWallBlockItem item) {
        return ((VerticallyAttachableBlockItemAccessor) item).sculptory$wallBlock();
    }
}
