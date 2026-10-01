package dev.sculptory.protocol.v2;

/** Any protocol v2 message. Stream messages are both {@link C2S} and {@link S2C}. */
public sealed interface Message permits C2S, S2C {
    /** The envelope type, derived from the record class. */
    default MessageType type() {
        return MessageType.of(getClass());
    }
}
