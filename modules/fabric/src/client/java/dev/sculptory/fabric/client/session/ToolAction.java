package dev.sculptory.fabric.client.session;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.protocol.v2.OpLabel;
import java.util.Objects;
import java.util.UUID;

/** A request a tool sends through {@link EditorSession#send}. */
public sealed interface ToolAction {
    /**
     * Runs a region op as a server job. {@code label} names the job and its history entry after the tool that sends
     * it ({@link OpLabel#NONE}: after the op, "Fill", "Paste"…); it must fit the op ({@link OpLabel#fits}).
     */
    record RunOp(OpSpec op, boolean physics, OpLabel label) implements ToolAction {
        public RunOp {
            Objects.requireNonNull(op);
            Objects.requireNonNull(label);
            if (!label.fits(op)) throw new IllegalArgumentException("The label " + label + " does not fit " + op.getClass().getSimpleName());
        }

        public RunOp(OpSpec op, boolean physics) {
            this(op, physics, OpLabel.NONE);
        }

        public RunOp(OpSpec op) {
            this(op, false);
        }
    }

    /** Cancels a running job. */
    record Cancel(UUID jobId) implements ToolAction {
        public Cancel {
            Objects.requireNonNull(jobId);
        }
    }

    /** M2. Copies or cuts the masked cells of a box into a server-held clipboard. */
    record Copy(Box box, BlockPos origin, boolean cut, CellMask mask) implements ToolAction {
        public Copy {
            Objects.requireNonNull(box);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(mask);
        }
    }
}
