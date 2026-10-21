package dev.sculptory.mc.mixin;

import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Builder mode's Force place puts a torch (or any wall-and-floor item) on the side of a block the game would not
 * support it on: that needs the item's wall block. Verified with javap against Minecraft 1.21.1 (Mojang names).
 */
@Mixin(StandingAndWallBlockItem.class)
public interface VerticallyAttachableBlockItemAccessor {
    @Accessor("wallBlock")
    Block sculptory$wallBlock();
}
