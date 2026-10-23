package dev.sculptory.mc.mixin;

import dev.sculptory.mc.builder.BuilderEntityCapture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Builder mode: every entity an item adds to a server world passes {@link BuilderEntityCapture}, which notes those a
 * builder-mode use of an item frame, painting or armor stand spawned, so the history records them. Item frames and
 * paintings ({@code HangingEntityItem}) are added with {@code addFreshEntity}, armor stands with
 * {@code addFreshEntityWithPassengers}, which adds each entity through it too. Only an entity the world accepted is
 * noted. Descriptor verified with javap against Minecraft 1.21.1 (Mojang names).
 */
@Mixin(ServerLevel.class)
public abstract class BuilderEntityCaptureMixin {
    @Inject(method = "addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", at = @At("RETURN"))
    private void sculptory$noteBuilderEntity(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) BuilderEntityCapture.added((ServerLevel) (Object) this, entity);
    }
}
