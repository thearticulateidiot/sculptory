package dev.sculptory.mc.client.mixin;

import dev.sculptory.mc.client.builder.BuilderClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Builder mode: a wheel notch outside the editor is offered to the powers
 * first (Tinker with Alt held changes the aimed block's property); one they take never reaches the hotbar. Target
 * verified with javap against Minecraft 1.21.1 (Mojang names): {@code private void onScroll(long, double, double)}.
 */
@Mixin(MouseHandler.class)
public abstract class MouseScrollMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void sculptory$builderScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (vertical == 0 || minecraft.screen != null || minecraft.player == null) return;
        if (BuilderClient.instance().map(builder -> builder.scrolled(Math.signum(vertical))).orElse(false)) {
            ci.cancel();
        }
    }
}
