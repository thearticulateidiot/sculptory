package dev.sculptory.server.engine;

import java.util.Locale;
import java.util.Objects;

/**
 * The answer to one builder-mode action ({@code BuilderPlace} or {@code BuilderBreak}). The action is acknowledged either way (its prediction settles); a refusal wrote nothing and the client is told
 * why ({@link #noticeKey}).
 *
 * @param refusal {@code null} when carried out
 * @param detail what the refusal is about ("" when nothing more)
 * @param changed cells changed (with their copies and what vanilla changed around them)
 * @param skipped cells left out: copies that were occupied, protected or unloaded, blocks of another kind in a
 *     same-kind drag, air
 */
public record BuilderOutcome(Refusal refusal, String detail, int changed, int skipped) {
    /** Why an action wrote nothing. */
    public enum Refusal {
        /** Editing is switched off on this server ({@code editingEnabled}). */
        DISABLED,
        /** The player lacks {@code sculptory.use} or {@code sculptory.builder}. */
        NO_PERMISSION,
        /** Builder mode works in Creative mode only. */
        NOT_CREATIVE,
        /** The target is farther than the builder reach (64 blocks) from the player's eyes. */
        OUT_OF_REACH,
        /** Outside the build height or the world border, or a symmetry centre beyond the world. */
        OUTSIDE_WORLD,
        /** The target's chunk is not loaded. */
        UNLOADED,
        /** The target is protected for the player (spawn protection, a claim). */
        PROTECTED,
        /** An editor job holds the target's area. */
        AREA_BUSY,
        /** Over the builder budget (blocks per second, copies included). */
        RATE_LIMITED,
        /** The player's history is loading, or their brush stroke is still being written. */
        BUSY,
        /** Another world than the player's, or an action the server cannot read. */
        INVALID,
        /** The hand holds no block. */
        NOT_A_BLOCK,
        /** The block is not enabled in this world. */
        DISABLED_BLOCK,
        /** The cell holds a block the new one may not replace. */
        OCCUPIED,
        /** The game refuses the block there (no support, an entity in the way); Force place overrides it. */
        GAME_REFUSES,
        /** The held item cannot break blocks in Creative (a sword), or the block is an operator block. */
        CANNOT_BREAK,
        /** Nothing to break there: air, a bare fluid, or another kind of block in a same-kind drag (no notice). */
        NOTHING,
        /** Mirror is on and the symmetry centre lies beyond the world. */
        SYMMETRY_OUTSIDE,
        /** The game failed while carrying it out (logged); what it changed stays and is in the history. */
        FAILED,
        /**
         * The global mask rejects a cell the action would change, judged against the
         * world before it: nothing is changed.
         */
        MASKED;

        /** The notice key the client translates: {@code sculptory.notice.builder.<name>}. */
        public String noticeKey() {
            return "sculptory.notice.builder." + name().toLowerCase(Locale.ROOT);
        }
    }

    public BuilderOutcome {
        Objects.requireNonNull(detail);
        if (changed < 0 || skipped < 0) throw new IllegalArgumentException("Negative counts");
    }

    public static BuilderOutcome done(int changed, int skipped) {
        return new BuilderOutcome(null, "", changed, skipped);
    }

    public static BuilderOutcome refused(Refusal refusal, String detail) {
        return new BuilderOutcome(Objects.requireNonNull(refusal), detail == null ? "" : detail, 0, 0);
    }

    public boolean accepted() {
        return refusal == null;
    }
}
