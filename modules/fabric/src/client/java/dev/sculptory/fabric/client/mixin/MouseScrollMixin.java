package dev.sculptory.fabric.client.mixin;

import dev.sculptory.fabric.client.builder.BuilderClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Builder mode: a wheel notch outside the editor is offered to the powers
 * first (Tinker with Alt held changes the aimed block's property); one they take never reaches the hotbar. Target
 * verified with javap against yarn 1.21.1+build.3: {@code private void onMouseScroll(long, double, double)}.
 */
@Mixin(Mouse.class)
public abstract class MouseScrollMixin {
    @Shadow
    @Final
    private MinecraftClient client;

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void sculptory$builderScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (vertical == 0 || client.currentScreen != null || client.player == null) return;
        if (BuilderClient.instance().map(builder -> builder.scrolled(Math.signum(vertical))).orElse(false)) {
            ci.cancel();
        }
    }
}
