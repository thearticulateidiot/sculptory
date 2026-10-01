package dev.sculptory.fabric.client.mixin;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the dev-only play check see what the action bar shows (a refusal may be told there over gameplay). Fields
 * verified with javap against yarn 1.21.1+build.3: {@code private Text overlayMessage},
 * {@code private int overlayRemaining} in {@code net.minecraft.client.gui.hud.InGameHud}.
 */
@Mixin(InGameHud.class)
public interface InGameHudAccessor {
    @Accessor("overlayMessage")
    Text sculptory$overlayMessage();

    @Accessor("overlayRemaining")
    int sculptory$overlayRemaining();
}
