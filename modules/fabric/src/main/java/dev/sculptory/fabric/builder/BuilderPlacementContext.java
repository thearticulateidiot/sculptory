package dev.sculptory.fabric.builder;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * A placement context whose block is the hit cell itself, whatever holds it: vanilla's context places into the hit cell
 * only when it is replaceable (air, grass) and next to it otherwise. Builder mode uses it for Replace (the clicked
 * block), and for the cells of mirrored copies.
 */
public final class BuilderPlacementContext extends ItemPlacementContext {
    public BuilderPlacementContext(World world, PlayerEntity player, Hand hand, ItemStack stack, BlockHitResult hit) {
        super(world, player, hand, stack, hit);
        this.canReplaceExisting = true;
    }

    /** A context at {@code cell}, hit at the middle of its {@code side} face. */
    public static BuilderPlacementContext at(World world, PlayerEntity player, Hand hand, ItemStack stack, BlockPos cell,
                                             Direction side) {
        Vec3d hit = Vec3d.ofCenter(cell).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        return new BuilderPlacementContext(world, player, hand, stack, new BlockHitResult(hit, side, cell, false));
    }
}
