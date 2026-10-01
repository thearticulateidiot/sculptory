package dev.sculptory.core;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * A platform-neutral block state: a block id plus its property values, sorted by name (UTF-8 byte order).
 * Text form: {@code namespace:path} or {@code namespace:path[key=value,...]}.
 *
 * <p>Property names and values are non-empty tokens of at most 128 UTF-8 bytes containing no whitespace,
 * control characters or any of {@code ,[]=;{}#"\}, so {@link #format()} and {@link #parse(String)} always
 * round-trip. At most {@value #MAX_PROPERTIES} properties; the text form is at most {@value #MAX_SPEC_BYTES}
 * UTF-8 bytes.
 */
public record BlockDescriptor(NamespacedId block, SortedMap<String, String> properties) {
    public static final int MAX_PROPERTIES = 64;
    public static final int MAX_COMPONENT_BYTES = 128;
    public static final int MAX_SPEC_BYTES = 8192;

    public BlockDescriptor {
        Objects.requireNonNull(block);
        Objects.requireNonNull(properties);
        if (properties.size() > MAX_PROPERTIES) throw new IllegalArgumentException("Too many properties");
        TreeMap<String, String> copy = new TreeMap<>(Checks.UTF8);
        properties.forEach((key, value) -> copy.put(token(key), token(value)));
        properties = Collections.unmodifiableSortedMap(copy);
    }

    public static BlockDescriptor of(NamespacedId block, Map<String, String> properties) {
        Objects.requireNonNull(properties);
        if (properties.size() > MAX_PROPERTIES) throw new IllegalArgumentException("Too many properties");
        TreeMap<String, String> sorted = new TreeMap<>(Checks.UTF8);
        properties.forEach((key, value) -> sorted.put(token(key), token(value)));
        return new BlockDescriptor(block, sorted);
    }

    /** A state with no properties. */
    public static BlockDescriptor of(NamespacedId block) {
        return new BlockDescriptor(block, new TreeMap<>());
    }

    /** {@code id} when there are no properties, otherwise {@code id[k=v,...]} in property order. */
    public String format() {
        if (properties.isEmpty()) return block.value();
        StringJoiner values = new StringJoiner(",", block.value() + "[", "]");
        properties.forEach((key, value) -> values.add(key + "=" + value));
        return values.toString();
    }

    /**
     * Parses the {@link #format()} text form. The syntax is strict: no whitespace, no empty brackets,
     * no duplicate properties.
     *
     * @throws IllegalArgumentException if {@code spec} is malformed or exceeds the limits
     */
    public static BlockDescriptor parse(String spec) {
        Objects.requireNonNull(spec);
        if (spec.length() > MAX_SPEC_BYTES || spec.getBytes(StandardCharsets.UTF_8).length > MAX_SPEC_BYTES) {
            throw new IllegalArgumentException("Block state text too long");
        }
        int bracket = spec.indexOf('[');
        String id = bracket < 0 ? spec : spec.substring(0, bracket);
        NamespacedId block = new NamespacedId(id);
        TreeMap<String, String> properties = new TreeMap<>(Checks.UTF8);
        if (bracket >= 0) {
            if (!spec.endsWith("]")) throw new IllegalArgumentException("Unterminated property list: " + spec);
            String body = spec.substring(bracket + 1, spec.length() - 1);
            if (body.isEmpty()) throw new IllegalArgumentException("Empty property list: " + spec);
            for (String pair : body.split(",", -1)) {
                String[] pieces = pair.split("=", -1);
                if (pieces.length != 2) throw new IllegalArgumentException("Malformed property: " + pair);
                if (properties.putIfAbsent(token(pieces[0]), token(pieces[1])) != null) {
                    throw new IllegalArgumentException("Duplicate property: " + pieces[0]);
                }
                if (properties.size() > MAX_PROPERTIES) throw new IllegalArgumentException("Too many properties");
            }
        }
        return new BlockDescriptor(block, properties);
    }

    /** The value of {@code property}, or {@code null}. */
    public String get(String property) {
        return properties.get(property);
    }

    /** A copy with {@code property} set to {@code value}. */
    public BlockDescriptor with(String property, String value) {
        TreeMap<String, String> copy = new TreeMap<>(Checks.UTF8);
        copy.putAll(properties);
        copy.put(property, value);
        return new BlockDescriptor(block, copy);
    }

    @Override
    public String toString() {
        return format();
    }

    private static String token(String text) {
        Checks.text(text, MAX_COMPONENT_BYTES);
        boolean ok = text.codePoints().noneMatch(c -> Character.isWhitespace(c)
                || Character.isSpaceChar(c)
                || Character.isISOControl(c)
                || ",[]=;{}#\"\\".indexOf(c) >= 0);
        if (!ok) throw new IllegalArgumentException("Invalid property token: " + text);
        return text;
    }
}
