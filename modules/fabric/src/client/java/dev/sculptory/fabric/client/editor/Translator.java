package dev.sculptory.fabric.client.editor;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns translation keys into display text. In game this is {@code I18n.translate}; tests use
 * {@link #KEYS}, which returns the key itself (with arguments appended) so assertions stay stable.
 */
public interface Translator {
    /** Returns the key, followed by its arguments in brackets when there are any. */
    Translator KEYS = new Translator() {
        @Override
        public String translate(String key, Object... args) {
            if (args.length == 0) {
                return key;
            }
            return key + Arrays.stream(args).map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
        }

        @Override
        public boolean has(String key) {
            return false;
        }
    };

    String translate(String key, Object... args);

    /** Whether {@code key} has a translation. */
    boolean has(String key);

    default String translate(String key, List<String> args) {
        return translate(key, args.toArray());
    }

    /** The translation of {@code key}, or {@code fallback} when there is none. */
    default String translateOr(String key, String fallback) {
        return has(key) ? translate(key) : fallback;
    }
}
