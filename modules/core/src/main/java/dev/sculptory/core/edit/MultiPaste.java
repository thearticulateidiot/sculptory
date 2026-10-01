package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.transform.Transform;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Many pastes of a few sources, compiled into one {@link EditProgram} (one job, one history entry). This is what
 * a scatter commit writes: {@link CompileContext#scatterPlan} resolves an {@link OpSpec.ScatterCommit} to one.
 *
 * <p>Each placement pastes {@code sources.get(source)} exactly as {@link OpSpec.Paste} with
 * {@link PasteOptions#DEFAULT} would: its transformed anchor lands on {@code origin}, absent and air source cells
 * are not written, states are rotated/mirrored and tiles copied unchanged. Where placements overlap, the later one
 * in the list wins. {@link #replace()} limits which world cells may be written (a scatter commit writes only into
 * cells that are still open); {@code sourceRules}, when not empty, gives each source its own rule instead (a scatter
 * mixing land and underwater variants).
 *
 * @param sourceRules empty, or one rule per source (then a cell no placement is known to write is never written;
 *     without, {@link #replace()} decides it)
 * @param expected per source that follows {@link Replace#EXPECTED}, by source index: what each of its cells must still
 *     hold (the same local cells as the source); empty when no source follows it
 */
public record MultiPaste(List<SourceBlocks> sources, List<Placement> placements, Replace replace,
                         List<Replace> sourceRules, Map<Integer, SourceBlocks> expected) {
    /** The most placements one program takes. */
    public static final int MAX_PLACEMENTS = 1 << 20;

    /**
     * Which world cells a placement may write. With any rule but {@link #ANY} (a scatter commit) the world is checked
     * when the program first reaches a placement, after the runner has loaded the placement's other chunks
     * ({@link EditProgram#readColumns}): if any of its cells no longer passes the rule (for example a chest was placed
     * there since the preview), lies in a column the job may not write ({@link ComputeContext#mayWrite}: protection,
     * the world border) or in a chunk that is still not loaded, the whole placement is skipped, so nothing is
     * overwritten and no placement is left half built. Every write is then also guarded per cell, against the
     * section's captured content and against the cell's live content right before it is written
     * ({@link EditProgram#mayReplace}), so a cell that stops passing in between is not written either; its placement
     * is then cut short. Skipped and cut-short placements are reported through {@link ComputeContext#conflicts}. These
     * rules assume placements do not overlap, as a scatter plan's never do. Every rule but {@link #ANY} and
     * {@link #EXPECTED} takes only open cells (air, replaceable, vegetation, fluid; no block entity).
     */
    public enum Replace {
        /** Every cell, as a paste. */
        ANY,
        /** Only open cells that hold no fluid. */
        OPEN,
        /** Open cells, fluid blocks and waterlogged cells included (aquatic scatters). */
        OPEN_OR_FLUID,
        /** Only still water: the water block at its source level (underwater scatter placements). */
        WATER,
        /**
         * Open cells that hold still water: the water source block, and open blocks holding water such as seagrass,
         * kelp or a waterlogged sea pickle (underwater scatter placements with fluids allowed).
         */
        WATER_OR_WET_PLANT,
        /**
         * Grown trees and features: every present source cell is written,
         * air included, and only while the world cell still holds exactly the state the source's
         * {@link MultiPaste#expected} cell gives (what the cell held when the tree grew), so grass under a trunk may
         * become dirt but nothing built since is overwritten. Unlike the OPEN rules it does not ask for open cells or
         * both halves of a two-block plant (the growth wrote what it changes). Placements of such a source are never
         * transformed.
         */
        EXPECTED
    }

    /** Placements that overwrite whatever is there ({@link Replace#ANY}). */
    public MultiPaste(List<SourceBlocks> sources, List<Placement> placements) {
        this(sources, placements, Replace.ANY);
    }

    /** Placements that all follow {@code replace}. */
    public MultiPaste(List<SourceBlocks> sources, List<Placement> placements, Replace replace) {
        this(sources, placements, replace, List.of());
    }

    /** Placements with per-source rules, none of them {@link Replace#EXPECTED}. */
    public MultiPaste(List<SourceBlocks> sources, List<Placement> placements, Replace replace,
                      List<Replace> sourceRules) {
        this(sources, placements, replace, sourceRules, Map.of());
    }

    public MultiPaste {
        Objects.requireNonNull(replace);
        sources = List.copyOf(sources);
        placements = List.copyOf(placements);
        sourceRules = List.copyOf(sourceRules);
        expected = Map.copyOf(expected);
        if (!sourceRules.isEmpty() && sourceRules.size() != sources.size()) {
            throw new IllegalArgumentException("One rule per source, or none");
        }
        if (placements.size() > MAX_PLACEMENTS) throw new IllegalArgumentException("Too many placements");
        for (int s = 0; s < sources.size(); s++) {
            Replace rule = sourceRules.isEmpty() ? replace : sourceRules.get(s);
            SourceBlocks want = expected.get(s);
            if ((rule == Replace.EXPECTED) != (want != null)) {
                throw new IllegalArgumentException("Source " + s + ": expected cells go with the EXPECTED rule only");
            }
            if (want != null && !want.size().equals(sources.get(s).size())) {
                throw new IllegalArgumentException("Source " + s + ": expected cells of another size");
            }
        }
        for (Integer s : expected.keySet()) {
            if (s < 0 || s >= sources.size()) throw new IllegalArgumentException("Expected cells of no source " + s);
        }
        for (Placement placement : placements) {
            if (placement.source() >= sources.size()) {
                throw new IllegalArgumentException("Placement source " + placement.source() + " is not in the list");
            }
            Replace rule = sourceRules.isEmpty() ? replace : sourceRules.get(placement.source());
            if (rule == Replace.EXPECTED && !placement.transform().isIdentity()) {
                throw new IllegalArgumentException("A grown placement is never transformed");
            }
        }
    }

    /** The rule source {@code source}'s placements follow. */
    public Replace rule(int source) {
        return sourceRules.isEmpty() ? replace : sourceRules.get(source);
    }

    /** Whether some placement is checked against the world ({@link #rule} other than {@link Replace#ANY}). */
    public boolean guarded() {
        if (replace != Replace.ANY) return true;
        for (Replace rule : sourceRules) {
            if (rule != Replace.ANY) return true;
        }
        return false;
    }

    /** Pastes source {@code source} with its anchor on {@code origin} after {@code transform}. */
    public record Placement(BlockPos origin, int source, Transform transform) {
        public Placement {
            Objects.requireNonNull(origin);
            Objects.requireNonNull(transform);
            // Scatter placements turn and mirror only; the parts' height ranges below rely on it.
            if (transform.upsideDown()) throw new IllegalArgumentException("A multi-paste placement is never upside down");
            if (source < 0) throw new IllegalArgumentException("Negative source index");
        }
    }
}
