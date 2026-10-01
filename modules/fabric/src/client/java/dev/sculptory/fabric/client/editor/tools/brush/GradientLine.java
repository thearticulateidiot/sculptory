package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.MixLayout;
import java.util.Objects;
import java.util.Optional;

/**
 * The line a Gradient pattern runs along: from the block an Alt+drag starts on
 * to the block it ends on. Palette Paint, the Shape brush and Fill share one (it hangs off the {@link SymmetryCentre}
 * they already share), so a line drawn in one tool is the one the others use. Like the symmetry centre it is a place in
 * the world: it lives in memory for the session and is not part of presets or palettes. Client thread only.
 */
public final class GradientLine {
    private BlockPos from;
    private BlockPos to;

    /** Whether a line is drawn. */
    public boolean isSet() {
        return from != null;
    }

    /** The block the line starts on, or {@code null}. */
    public BlockPos from() {
        return from;
    }

    /** The block the line ends on, or {@code null}. */
    public BlockPos to() {
        return to;
    }

    /**
     * Draws the line from {@code from} to {@code to}; false (nothing changes) when they are the same block or one lies
     * beyond a gradient's range ({@link MixLayout.Gradient#validEnd}).
     */
    public boolean set(BlockPos from, BlockPos to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (from.equals(to) || !MixLayout.Gradient.validEnd(from) || !MixLayout.Gradient.validEnd(to)) {
            return false;
        }
        this.from = from;
        this.to = to;
        return true;
    }

    /** Forgets the line. */
    public void clear() {
        from = null;
        to = null;
    }

    /** The Gradient layout along the line with a dithered edge of {@code edge} blocks, or empty without a line. */
    public Optional<MixLayout> layout(int edge) {
        return isSet() ? Optional.of(new MixLayout.Gradient(from, to, edge)) : Optional.empty();
    }

    /** The line's length in blocks, between the two blocks' centres (0 without a line). */
    public double length() {
        if (!isSet()) return 0;
        long dx = (long) to.x() - from.x(), dy = (long) to.y() - from.y(), dz = (long) to.z() - from.z();
        return Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
    }
}
