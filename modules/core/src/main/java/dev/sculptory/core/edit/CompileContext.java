package dev.sculptory.core.edit;

import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.mask.MaskSide;
import dev.sculptory.core.state.StateSpace;
import java.util.Optional;
import java.util.UUID;

/** What {@link OpCompiler#compile} may use to turn an {@link OpSpec} into an {@link EditProgram}. */
public interface CompileContext {
    StateSpace states();

    /** Looks up server-held paste sources (clipboards and library assets). Empty if unknown. */
    Optional<SourceBlocks> source(SourceRef ref);

    /**
     * Looks up the server-held scatter plan an {@link OpSpec.ScatterCommit} names (M3), as the placements it writes
     * ({@code ScatterPlan.toMultiPaste()}). Empty if the plan is unknown, expired, or not the requesting player's
     * for this world; {@link OpCompiler#compile} then refuses the commit. The default knows no plans.
     */
    default Optional<MultiPaste> scatterPlan(UUID planId) {
        return Optional.empty();
    }

    /**
     * The target world's lowest build height. Programs clip their bounds, section order and estimates to
     * {@code [bottomY, topYExclusive)}. The default is unbounded; the server must override both.
     */
    default int bottomY() {
        return Integer.MIN_VALUE;
    }

    /** One above the target world's highest build height; see {@link #bottomY()}. The default is unbounded. */
    default int topYExclusive() {
        return Integer.MAX_VALUE;
    }

    /**
     * The largest {@link OpCompiler#targetVolume target} or {@link OpCompiler#sourceVolume source} volume
     * {@link OpCompiler#compile} accepts. Larger ops are refused with {@link EditTooLargeException} before any
     * work or allocation. The default is unbounded.
     */
    default long maxCells() {
        return Long.MAX_VALUE;
    }

    /**
     * The most sections a program over a region other than a box may list (its own, and for a move or stack those it
     * writes); a region reaching more is refused with {@link EditTooLargeException} as soon as that shows, before they
     * are all listed. The default is 4,194,304; a server gives what its executor would admit.
     */
    default long maxSections() {
        return RegionProgram.MAX_SECTIONS;
    }

    /**
     * The most rows a shape in a box of more than 2^29 cells may span ({@link OpCompiler#checkShape(Region, long)}).
     * The default is {@link OpCompiler#MAX_BIG_SHAPE_ROWS}; a server gives {@link OpCompiler#MAX_BIG_SHAPE_ROWS_BYPASS}
     * to a player with {@code limit.bypass}.
     */
    default long maxBigShapeRows() {
        return OpCompiler.MAX_BIG_SHAPE_ROWS;
    }

    /**
     * How Update blocks reshapes a cell from its neighbours ({@link OpSpec.UpdateBlocks}), or {@code null} when this
     * context cannot (the op is then refused). The default cannot; the server gives the world's.
     */
    default NeighbourShapes neighbourShapes() {
        return null;
    }

    /**
     * The global mask for an op masked where its blocks are lifted from ({@link MaskSide#SOURCE}: a Move takes only
     * the source cells it accepts, the rest stays), bound to {@link #states()}. The
     * default, and a player without a mask on, is {@link BoundMask#ALL}. Ops masked where they land are wrapped by
     * {@code MaskedProgram} instead and never read this.
     */
    default BoundMask sourceMask() {
        return BoundMask.ALL;
    }
}
