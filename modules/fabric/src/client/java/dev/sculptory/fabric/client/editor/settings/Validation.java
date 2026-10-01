package dev.sculptory.fabric.client.editor.settings;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** The result of validating one setting value. {@code messageKey} is translatable; "" when OK. */
public record Validation(Level level, String messageKey, List<String> args) {
    public enum Level {
        OK,
        WARNING,
        ERROR
    }

    private static final Validation OK = new Validation(Level.OK, "", List.of());

    public Validation {
        Objects.requireNonNull(level);
        Objects.requireNonNull(messageKey);
        args = List.copyOf(args);
    }

    public static Validation ok() {
        return OK;
    }

    public static Validation warning(String messageKey, Object... args) {
        return new Validation(Level.WARNING, messageKey, strings(args));
    }

    public static Validation error(String messageKey, Object... args) {
        return new Validation(Level.ERROR, messageKey, strings(args));
    }

    /** False only for errors; warnings still allow the value to be used. */
    public boolean isValid() {
        return level != Level.ERROR;
    }

    private static List<String> strings(Object[] args) {
        return Arrays.stream(args).map(String::valueOf).toList();
    }
}
