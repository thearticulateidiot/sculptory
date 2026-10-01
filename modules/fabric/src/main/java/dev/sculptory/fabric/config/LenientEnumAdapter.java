package dev.sculptory.fabric.config;

import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.Locale;

/**
 * Reads enum constants by name without regard to case; anything unrecognised (unknown names, numbers, objects)
 * reads as {@code null} so the caller can substitute a safe value. Writes the constant's name.
 */
final class LenientEnumAdapter<E extends Enum<E>> extends TypeAdapter<E> {
    private final Class<E> type;

    LenientEnumAdapter(Class<E> type) {
        this.type = type;
    }

    @Override
    public void write(JsonWriter out, E value) throws IOException {
        if (value == null) {
            out.nullValue();
        } else {
            out.value(value.name());
        }
    }

    @Override
    public E read(JsonReader in) throws IOException {
        if (in.peek() != JsonToken.STRING) {
            in.skipValue();
            return null;
        }
        String text = in.nextString().trim().toUpperCase(Locale.ROOT);
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(text)) return constant;
        }
        return null;
    }
}
