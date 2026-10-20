package dev.sculptory.fabric.client.mixin;

import dev.sculptory.fabric.client.editor.EditorVisuals;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hides the hotbar, status bars and crosshair while the editor is open (the editor's palette sits
 * where the hotbar was). Cosmetic, so {@code require = 0}: if another mod changes these methods the
 * game still runs, just with the vanilla HUD showing. Targets verified with javap against Minecraft 1.21.1
 * (Mojang names): {@code private void renderHotbarAndDecorations(GuiGraphics, DeltaTracker)} and
 * {@code private void renderCrosshair(GuiGraphics, DeltaTracker)}.
 */
@Mixin(Gui.class)
public abstract class InGameHudMixin {
    @Inject(method = "renderHotbarAndDecorations", at = @At("HEAD"), cancellable = true, require = 0)
    private void sculptory$hideMainHud(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (EditorVisuals.editing()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true, require = 0)
    private void sculptory$hideCrosshair(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (EditorVisuals.editing()) {
            ci.cancel();
        }
    }
}
