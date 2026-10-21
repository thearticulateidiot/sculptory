package dev.sculptory.neoforge.client.mixin;

import dev.sculptory.neoforge.client.Interactions;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@code ClientLoader.onRenderStateInvalidated}: NeoForge has no event for it. Fabric API's
 * {@code InvalidateRenderStateCallback} fires at the head of {@code LevelRenderer.allChanged} (resource reloads, video
 * settings, F3+A); so does this.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "allChanged", at = @At("HEAD"))
    private void sculptory$renderStateInvalidated(CallbackInfo ci) {
        Interactions.renderStateInvalidated();
    }
}
