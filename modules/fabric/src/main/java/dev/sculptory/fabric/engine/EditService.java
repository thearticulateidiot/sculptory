package dev.sculptory.fabric.engine;

import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.network.ServerPlayerEntity;

/** Server-side editing entry point, called by the protocol dispatcher on the server thread. */
public interface EditService {
    /** Admits a region op as a job. */
    JobTicket run(ServerPlayerEntity p, OpSpec s, RunOptions o, JobListener l) throws EditRejected;

    void beginStroke(ServerPlayerEntity p, int strokeId, BrushSpec spec) throws EditRejected;

    /**
     * Queues dabs for the brush lane; the prediction sequence {@code seq} is acknowledged after they apply. Counts as
     * {@link #predicted}.
     */
    DabOutcome dabs(ServerPlayerEntity p, int strokeId, int seq, List<Dab> dabs);

    /**
     * Notes that the player's client predicted block changes just now (it sent dabs, which the dispatcher may refuse
     * before {@link #dabs} sees them, for example over a rate limit), so bulk writes near them go out as per-block
     * updates for a while instead of whole-column resends that would overwrite those predictions.
     */
    default void predicted(ServerPlayerEntity p) {}

    /** Ends a stroke, turning its coalesced record into one history entry. */
    void endStroke(ServerPlayerEntity p, int strokeId);

    JobTicket undo(ServerPlayerEntity p, ConflictPolicy c) throws EditRejected;

    JobTicket redo(ServerPlayerEntity p, ConflictPolicy c) throws EditRejected;

    /**
     * Undo anyway ({@code redo} false) or Redo anyway: re-applies, with {@link ConflictPolicy#OVERWRITE}, the run of
     * {@code steps} consecutive undo (or redo) steps the player made since their last other history change. The history position does not move. Refused when the run does not match.
     */
    default JobTicket historyOverwrite(ServerPlayerEntity p, boolean redo, int steps) throws EditRejected {
        throw new EditRejected(RejectReason.DISABLED, "not supported");
    }

    /**
     * Builder mode: the powers the player switched on ({@code BuilderPower} bits), kept while they are connected. The
     * service applies the ones it carries out itself (Long reach raises the player's block interaction range while they
     * may use builder mode).
     */
    default void builderPowers(ServerPlayerEntity p, int powers) {}

    /** Builder mode: one right-click placement, with its mirrored copies; one history step. */
    default BuilderOutcome builderPlace(ServerPlayerEntity p, C2S.BuilderPlace place) {
        return BuilderOutcome.refused(BuilderOutcome.Refusal.DISABLED, "not supported");
    }

    /** Builder mode: breaks of one click or drag; the drag's breaks are one history step when it ends. */
    default BuilderOutcome builderBreak(ServerPlayerEntity p, C2S.BuilderBreak breaks) {
        return BuilderOutcome.refused(BuilderOutcome.Refusal.DISABLED, "not supported");
    }

    /** Builder mode: the drag ended; its breaks become one history step. */
    default void builderDragEnd(ServerPlayerEntity p, int dragId) {}

    /**
     * Jump or Through: moves the player onto the block they look at, or
     * through the wall there, and answers where their feet landed, or why not. Needs the {@code navigate} node and
     * Creative or Spectator; a refusal for want of either is {@code NO_PERMISSION} (with a notice saying which).
     */
    default S2C.NavigateResult navigate(ServerPlayerEntity p, C2S.Navigate m) {
        return S2C.NavigateResult.refused(m.reqId(), RejectReason.INVALID);
    }

    /** Cancels one of the player's jobs at the next section boundary. False if it is unknown or finished. */
    boolean cancel(ServerPlayerEntity p, UUID jobId);
}
