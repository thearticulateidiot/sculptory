package dev.sculptory.fabric.mixin;

import net.minecraft.block.Block;
import net.minecraft.item.VerticallyAttachableBlockItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Builder mode's Force place puts a torch (or any wall-and-floor item) on the side of a block the game would not
 * support it on: that needs the item's wall block. Verified with javap against yarn 1.21.1+build.3.
 */
@Mixin(VerticallyAttachableBlockItem.class)
public interface VerticallyAttachableBlockItemAccessor {
    @Accessor("wallBlock")
    Block sculptory$wallBlock();
}
