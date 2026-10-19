package dev.sculptory.fabric.client.util;

import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * JSON a config file holds that this build doesn't use but writes back as it was (fields and entries of another
 * build), kept as JSON text. Parsing has no depth limit, but writing JSON recurses: a value nested deeper than
 * {@link #MAX_DEPTH} (only a hand edit makes one) is dropped rather than overflow the stack when it is written.
 */
public final class KeptJson {
    /** How deep a kept value may nest. */
    public static final int MAX_DEPTH = 64;

    private KeptJson() {}

    /** Whether {@code element} nests arrays or objects more than {@code limit} levels deep, looked at level by level. */
    public static boolean deeperThan(JsonElement element, int limit) {
        List<JsonElement> level = List.of(element);
        for (int depth = 0; !level.isEmpty(); depth++) {
            if (depth > limit) {
                return true;
            }
            List<JsonElement> next = new ArrayList<>();
            for (JsonElement node : level) {
                if (node.isJsonArray()) {
                    node.getAsJsonArray().forEach(next::add);
                } else if (node.isJsonObject()) {
                    node.getAsJsonObject().entrySet().forEach(entry -> next.add(entry.getValue()));
                }
            }
            level = next;
        }
        return false;
    }

    /** Gives {@code keep} each field of {@code fields} (in order) as JSON text, except those nested too deep. */
    public static void eachField(Iterable<Map.Entry<String, JsonElement>> fields, BiConsumer<String, String> keep) {
        for (Map.Entry<String, JsonElement> field : fields) {
            if (!deeperThan(field.getValue(), MAX_DEPTH)) {
                keep.accept(field.getKey(), field.getValue().toString());
            }
        }
    }

    /** {@code element} as JSON text, or null when it nests too deep to keep. */
    public static String text(JsonElement element) {
        return deeperThan(element, MAX_DEPTH) ? null : element.toString();
    }
}
