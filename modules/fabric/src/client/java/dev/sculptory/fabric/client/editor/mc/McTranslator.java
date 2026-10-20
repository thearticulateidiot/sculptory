package dev.sculptory.fabric.client.editor.mc;

import dev.sculptory.fabric.client.editor.Translator;
import net.minecraft.client.resources.language.I18n;

/** {@link Translator} over the game's current language. */
public final class McTranslator implements Translator {
    @Override
    public String translate(String key, Object... args) {
        return I18n.get(key, args);
    }

    @Override
    public boolean has(String key) {
        return I18n.exists(key);
    }
}
