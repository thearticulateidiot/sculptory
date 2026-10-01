package dev.sculptory.core.history.store;

import java.io.IOException;

/** Stored history data that does not decode: a bad checksum, a malformed record or an impossible value. */
public class CorruptDataException extends IOException {
    public CorruptDataException(String message) {
        super(message);
    }

    public CorruptDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
