package dev.sculptory.server.engine;

import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.protocol.v2.OpLabel;
import java.util.Objects;

/**
 * Per-job options. Physics needs {@link Perm#PHYSICS}; the conflict policy matters only for undo/redo; the label names
 * the job and its history entry after the tool that sent the op ({@link OpLabel#NONE}: after the op itself).
 */
public record RunOptions(boolean physics, ConflictPolicy conflictPolicy, OpLabel label) {
    public static final RunOptions DEFAULT = new RunOptions(false, ConflictPolicy.SKIP_CONFLICTS);

    public RunOptions {
        Objects.requireNonNull(conflictPolicy);
        Objects.requireNonNull(label);
    }

    public RunOptions(boolean physics, ConflictPolicy conflictPolicy) {
        this(physics, conflictPolicy, OpLabel.NONE);
    }
}
