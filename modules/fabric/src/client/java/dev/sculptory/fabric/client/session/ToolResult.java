package dev.sculptory.fabric.client.session;

import dev.sculptory.protocol.v2.RejectReason;
import java.util.Objects;
import java.util.UUID;

/** The server's answer to a {@link ToolAction}. */
public sealed interface ToolResult {
    /** A job was admitted; follow it through {@link EditorSession#jobs()}. */
    record Accepted(UUID jobId, long estimatedCells) implements ToolResult {
        public Accepted {
            Objects.requireNonNull(jobId);
        }
    }

    /** The action completed without a job (e.g. a cancel was honoured, a clipboard is ready). */
    record Done() implements ToolResult {}

    /** Refused; nothing changed. {@code detail} may be empty. */
    record Rejected(RejectReason reason, String detail) implements ToolResult {
        public Rejected {
            Objects.requireNonNull(reason);
            Objects.requireNonNull(detail);
        }
    }
}
