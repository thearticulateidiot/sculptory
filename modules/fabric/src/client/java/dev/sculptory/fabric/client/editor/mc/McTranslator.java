package dev.sculptory.fabric.client.editor.mc;

import dev.sculptory.fabric.client.editor.Translator;
import net.minecraft.client.resource.language.I18n;

/** {@link Translator} over the game's current language. */
public final class McTranslator implements Translator {
    @Override
    public String translate(String key, Object... args) {
        return I18n.translate(key, args);
    }

    @Override
    public boolean has(String key) {
        return I18n.hasTranslation(key);
    }
}
