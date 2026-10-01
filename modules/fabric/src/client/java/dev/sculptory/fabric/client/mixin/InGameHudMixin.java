package dev.sculptory.fabric.client.mixin;

import dev.sculptory.fabric.client.editor.EditorVisuals;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the hotbar, status bars and crosshair while the editor is open (the editor's palette sits
 * where the hotbar was). Cosmetic, so {@code require = 0}: if another mod changes these methods the
 * game still runs, just with the vanilla HUD showing. Targets verified with javap against yarn
 * 1.21.1+build.3: {@code private void renderMainHud(DrawContext, RenderTickCounter)} and
 * {@code private void renderCrosshair(DrawContext, RenderTickCounter)}.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "renderMainHud", at = @At("HEAD"), cancellable = true, require = 0)
    private void sculptory$hideMainHud(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (EditorVisuals.editing()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true, require = 0)
    private void sculptory$hideCrosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (EditorVisuals.editing()) {
            ci.cancel();
        }
    }
}
