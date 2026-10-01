package dev.sculptory.fabric.client.session;

import java.util.List;
import java.util.Objects;

/** A message for the player (toast), from the server or from the client itself. {@code key} is translatable. */
public record Notice(Level level, String key, List<String> args) {
    public enum Level {
        INFO,
        SUCCESS,
        WARNING,
        ERROR
    }

    public Notice {
        Objects.requireNonNull(level);
        Objects.requireNonNull(key);
        args = List.copyOf(args);
    }

    public static Notice of(Level level, String key, String... args) {
        return new Notice(level, key, List.of(args));
    }
}
