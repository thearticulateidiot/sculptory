package dev.sculptory.mc.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A placement context whose block is the hit cell itself, whatever holds it: vanilla's context places into the hit cell
 * only when it is replaceable (air, grass) and next to it otherwise. Builder mode uses it for Replace (the clicked
 * block), and for the cells of mirrored copies.
 */
public final class BuilderPlacementContext extends BlockPlaceContext {
    public BuilderPlacementContext(Level world, Player player, InteractionHand hand, ItemStack stack, BlockHitResult hit) {
        super(world, player, hand, stack, hit);
        this.replaceClicked = true;
    }

    /** A context at {@code cell}, hit at the middle of its {@code side} face. */
    public static BuilderPlacementContext at(Level world, Player player, InteractionHand hand, ItemStack stack, BlockPos cell,
                                             Direction side) {
        Vec3 hit = Vec3.atCenterOf(cell).add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        return new BuilderPlacementContext(world, player, hand, stack, new BlockHitResult(hit, side, cell, false));
    }
}
