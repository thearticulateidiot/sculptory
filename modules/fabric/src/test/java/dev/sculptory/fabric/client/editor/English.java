package dev.sculptory.fabric.client.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** The editor's English text from {@code en_us.json} (a key without a translation stays the key), for layout tests. */
public final class English implements Translator {
    public static final English INSTANCE = new English();

    private final JsonObject lang;

    private English() {
        try (InputStream in = English.class.getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            if (in == null) {
                throw new IllegalStateException("en_us.json is not on the test classpath");
            }
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String translate(String key, Object... args) {
        return lang.has(key) ? String.format(Locale.ROOT, lang.get(key).getAsString(), args) : key;
    }

    @Override
    public boolean has(String key) {
        return lang.has(key);
    }
}
