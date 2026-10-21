package dev.sculptory.mc.client.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the dev-only play check see what the action bar shows (a refusal may be told there over gameplay). Fields
 * verified with javap against Minecraft 1.21.1 (Mojang names): {@code private Component overlayMessageString},
 * {@code private int overlayMessageTime} in {@code net.minecraft.client.gui.Gui}.
 */
@Mixin(Gui.class)
public interface InGameHudAccessor {
    @Accessor("overlayMessageString")
    Component sculptory$overlayMessage();

    @Accessor("overlayMessageTime")
    int sculptory$overlayRemaining();
}
