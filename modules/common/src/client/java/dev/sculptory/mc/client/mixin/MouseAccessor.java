package dev.sculptory.mc.client.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets right-button look put the cursor back where it was: after re-showing the cursor, vanilla's
 * mouse handler must also believe it is there, or the next move event jumps. Fields verified with
 * javap: {@code private double x}, {@code private double y} in {@code net.minecraft.client.Mouse}.
 */
@Mixin(MouseHandler.class)
public interface MouseAccessor {
    @Accessor("xpos")
    void sculptory$setX(double x);

    @Accessor("ypos")
    void sculptory$setY(double y);
}
