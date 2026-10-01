package dev.sculptory.fabric.client.session;

import dev.sculptory.protocol.v2.RejectReason;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The answer to an M2 request (clipboards, previews, the library, schematic files).
 *
 * <p>Toasts: the session raises the toast for every {@link Refused} and {@link Failed} answer itself (the server's
 * own refusal notice when it sends one), except {@link Failure#CANCELLED} and {@link Failure#DISCONNECTED}. Callers
 * only react to the outcome; they never toast it again.
 *
 * @param <T> the value of a successful answer
 */
public sealed interface Reply<T> {
    /** The request succeeded. */
    record Ok<T>(T value) implements Reply<T> {
        public Ok {
            Objects.requireNonNull(value);
        }
    }

    /** The server refused the request; nothing changed. {@code detail} may be empty. */
    record Refused<T>(RejectReason reason, String detail) implements Reply<T> {
        public Refused {
            Objects.requireNonNull(reason);
            Objects.requireNonNull(detail);
        }
    }

    /** The request did not complete for a reason on the way (a timeout, a broken transfer, the connection). */
    record Failed<T>(Failure failure, String detail) implements Reply<T> {
        public Failed {
            Objects.requireNonNull(failure);
            Objects.requireNonNull(detail);
        }
    }

    /** Why a request failed without a server refusal. */
    enum Failure {
        /** Nothing arrived for {@link ClipboardTransfers#STALL_NANOS}. */
        TIMED_OUT,
        /** The transfer was aborted by the server, or its data was damaged. */
        ABORTED,
        /** The data arrived but could not be read. */
        CORRUPT,
        /** Too many requests are waiting on this client already. */
        BUSY,
        /** The player cancelled it (no toast). */
        CANCELLED,
        /** The connection ended (no toast: the editor closes). */
        DISCONNECTED;

        /** The toast key: {@code sculptory.transfer.<failure>}, with the request's label as its argument. */
        public String noticeKey() {
            return "sculptory.transfer." + name().toLowerCase(Locale.ROOT);
        }

        /** Whether the session raises a toast for this failure. */
        public boolean announced() {
            return this != CANCELLED && this != DISCONNECTED;
        }
    }

    static <T> Reply<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> Reply<T> refused(RejectReason reason, String detail) {
        return new Refused<>(reason, detail);
    }

    static <T> Reply<T> failed(Failure failure, String detail) {
        return new Failed<>(failure, detail);
    }

    default boolean isOk() {
        return this instanceof Ok<T>;
    }

    /** The value of an {@link Ok} answer. */
    default Optional<T> toOptional() {
        return this instanceof Ok<T> ok ? Optional.of(ok.value()) : Optional.empty();
    }
}
