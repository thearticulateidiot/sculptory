package dev.sculptory.fabric.client.editor.settings;

import dev.sculptory.protocol.v2.Limits;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Immutable values for every setting of one schema. {@link #with} returns a new instance.
 * {@link #encode()} gives a key → text map for presets; {@link #decode} reads it back leniently.
 */
public final class SettingsValues {
    private final SettingsSchema schema;
    private final Map<String, Object> values;

    private SettingsValues(SettingsSchema schema, Map<String, Object> values) {
        this.schema = schema;
        this.values = Collections.unmodifiableMap(values);
    }

    /** Every setting at its default. */
    public static SettingsValues defaults(SettingsSchema schema) {
        Objects.requireNonNull(schema);
        Map<String, Object> values = new HashMap<>();
        for (SettingDef<?> def : schema.defs()) values.put(def.key(), def.canonical(def.defaultValue()));
        return new SettingsValues(schema, values);
    }

    public SettingsSchema schema() {
        return schema;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(SettingDef<T> def) {
        requireKnown(def);
        return (T) values.get(def.key());
    }

    /**
     * A copy with {@code def} set to {@code value}. The value is stored as an immutable copy and is not
     * range-checked here; see {@link #validate}.
     *
     * @throws IllegalArgumentException if {@code def} is not in the schema or the value has the wrong type
     */
    public <T> SettingsValues with(SettingDef<T> def, T value) {
        requireKnown(def);
        Map<String, Object> copy = new HashMap<>(values);
        copy.put(def.key(), def.canonical(value));
        return new SettingsValues(schema, copy);
    }

    public boolean isVisible(SettingDef<?> def) {
        requireKnown(def);
        return def.visibleWhen().test(this);
    }

    /** Validation of every setting, in display order. {@code serverLimits} may be {@code null}. */
    public Map<String, Validation> validate(Limits serverLimits) {
        Map<String, Validation> results = new LinkedHashMap<>();
        for (SettingDef<?> def : schema.defs()) results.put(def.key(), validateOne(def, values.get(def.key()), serverLimits));
        return Collections.unmodifiableMap(results);
    }

    /** True when no setting has an error. */
    public boolean isValid(Limits serverLimits) {
        return validate(serverLimits).values().stream().allMatch(Validation::isValid);
    }

    /** Every value as text, keyed by setting key. */
    public SortedMap<String, String> encode() {
        SortedMap<String, String> encoded = new TreeMap<>();
        for (SettingDef<?> def : schema.defs()) encoded.put(def.key(), encodeOne(def, values.get(def.key())));
        return Collections.unmodifiableSortedMap(encoded);
    }

    /**
     * Reads {@link #encode()} output. Unknown keys are ignored. Missing values, values that do not parse, and
     * values outside the setting's own bounds fall back to the default. Never throws for bad content.
     */
    public static SettingsValues decode(SettingsSchema schema, Map<String, String> encoded) {
        Objects.requireNonNull(encoded);
        SettingsValues result = defaults(schema);
        Map<String, Object> values = new HashMap<>(result.values);
        for (SettingDef<?> def : schema.defs()) {
            String text = encoded.get(def.key());
            if (text == null) continue;
            try {
                Object value = def.canonical(def.decode(text));
                if (validateOne(def, value, null).isValid()) values.put(def.key(), value);
            } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
                // keep the default
            }
        }
        return new SettingsValues(schema, values);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SettingsValues other && schema.equals(other.schema) && values.equals(other.values);
    }

    @Override
    public int hashCode() {
        return 31 * schema.hashCode() + values.hashCode();
    }

    @Override
    public String toString() {
        return "SettingsValues" + encode();
    }

    private void requireKnown(SettingDef<?> def) {
        Objects.requireNonNull(def);
        if (!schema.def(def.key()).map(def::equals).orElse(false)) {
            throw new IllegalArgumentException("Setting not in this schema: " + def.key());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Validation validateOne(SettingDef<T> def, Object value, Limits serverLimits) {
        return def.validate((T) value, serverLimits);
    }

    @SuppressWarnings("unchecked")
    private static <T> String encodeOne(SettingDef<T> def, Object value) {
        return def.encode((T) value);
    }
}
