package dev.sculptory.fabric.client.session;

import dev.sculptory.protocol.v2.Features;
import java.util.Objects;

/** What the connected server negotiated in Welcome. {@link #NONE} before the handshake. */
public record Capabilities(int protocol, Features features, long sessionEpoch) {
    public static final Capabilities NONE = new Capabilities(0, Features.NONE, 0L);

    public Capabilities {
        Objects.requireNonNull(features);
    }
}
