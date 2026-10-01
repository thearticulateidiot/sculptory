package dev.sculptory.fabric.client.builder;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.protocol.v2.BuilderPower;
import java.util.Locale;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

/**
 * The powers that are on, as small tagged chips above the right end of the hotbar; nothing while none is on. Drawn each frame by {@link BuilderClient}'s HUD hook while the editor is closed.
 */
final class BuilderHud {
    static final int CHIP_HEIGHT = 11;
    static final int GAP = 2;
    /** Above the hotbar (22 tall at the bottom) and the row the experience bar and status bars use. */
    static final int ABOVE_HOTBAR = 22 + 16;
    /** The hotbar is 182 wide, centred. */
    static final int HOTBAR_HALF_WIDTH = 91;
    private static final int PLATE = 0xA0101216;
    private static final int TEXT = 0xFFE8EBF2;

    private BuilderHud() {}

    static String tagKey(BuilderPower power) {
        return "sculptory.builder.tag." + power.name().toLowerCase(Locale.ROOT);
    }

    /** Draws the chips of the powers in {@code mask}, right-aligned to the hotbar's right edge. */
    static void render(DrawContext context, TextRenderer text, int mask, Translator translator) {
        if (mask == 0) return;
        int right = context.getScaledWindowWidth() / 2 + HOTBAR_HALF_WIDTH;
        int y = context.getScaledWindowHeight() - ABOVE_HOTBAR - CHIP_HEIGHT;
        BuilderPower[] powers = BuilderPower.values();
        for (int i = powers.length - 1; i >= 0; i--) {
            if (!powers[i].in(mask)) continue;
            String tag = translator.translate(tagKey(powers[i]));
            int width = text.getWidth(tag) + 6;
            int x = right - width;
            context.fill(x, y, x + width, y + CHIP_HEIGHT, PLATE);
            context.drawText(text, tag, x + 3, y + 2, TEXT, false);
            right = x - GAP;
        }
    }
}
