package dev.sculptory.core.nbt;

import java.io.IOException;

/** Malformed or truncated NBT. {@link NbtLimitException} is the subtype for input over a configured limit. */
public class NbtException extends IOException {
    public NbtException(String message) {
        super(message);
    }

    public NbtException(String message, Throwable cause) {
        super(message, cause);
    }
}
