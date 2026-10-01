package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.widget.IconButton;
import java.util.List;
import java.util.Objects;
import net.minecraft.item.ItemStack;

/**
 * A tool palette slot: the tool's icon with its key in the top-left corner, as bound now ({@link #keyLabel(List)}:
 * "1", "0", "-", "=", "["; nothing when the slot's key is unbound). The editor sets the label every frame from the live
 * keymap, so a key changed in the Keys window shows at once.
 */
public final class PaletteButton extends IconButton {
    private final int slot;
    private String keyLabel = "";

    public PaletteButton(int slot, ItemStack icon, Runnable onClick) {
        super(icon, onClick);
        this.slot = slot;
    }

    public int slot() {
        return slot;
    }

    /** The key shown in the corner ("" for none). */
    public String keyLabel() {
        return keyLabel;
    }

    public PaletteButton setKeyLabel(String label) {
        this.keyLabel = Objects.requireNonNull(label);
        return this;
    }

    /**
     * The corner label for a slot's chords: its first chord, short: the key as shown ("1", "-", "F2") after "^" for
     * Ctrl, "⇧" for Shift and "⎇" for Alt ("^1"); "" when unbound. The tooltip names the chords in full.
     */
    public static String keyLabel(List<KeyChord> chords) {
        if (chords.isEmpty()) {
            return "";
        }
        KeyChord chord = chords.get(0);
        StringBuilder label = new StringBuilder();
        if (Modifiers.control(chord.modifiers())) {
            label.append('^');
        }
        if (Modifiers.shift(chord.modifiers())) {
            label.append('⇧');
        }
        if (Modifiers.alt(chord.modifiers())) {
            label.append('⎇');
        }
        return label.append(new KeyChord(chord.input(), chord.code(), 0).display()).toString();
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        super.render(g, ctx);
        if (keyLabel.isEmpty()) {
            return;
        }
        Theme theme = ctx.theme();
        String label = ctx.text().trimToWidth(keyLabel, Math.max(0, bounds.width() - 3));
        g.pushLayer(theme.itemOverlayZ + 1);
        g.text(label, bounds.x() + 2, bounds.y() + 1, isEffectivelyEnabled() ? theme.text : theme.textDisabled, true);
        g.popLayer();
    }
}
