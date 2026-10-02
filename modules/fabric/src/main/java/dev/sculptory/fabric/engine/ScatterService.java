package dev.sculptory.fabric.engine;

import dev.sculptory.core.Box;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Scatter previews (M3), called by the protocol dispatcher on the server thread. A preview refused up front throws
 * {@link EditRejected}; otherwise it is planned over later ticks and answered once through its
 * {@link PreviewReply}, on the server thread. The plan it produces is committed with
 * {@code RunOp(OpSpec.ScatterCommit(planId))} through the {@link EditService}.
 */
public interface ScatterService {
    /**
     * A finished preview.
     *
     * @param rejectedCounts per scatter {@code Outcome} name, the area columns that ended with it (non-zero only)
     * @param bounds the union of the placements' footprints, or {@code null} without placements
     * @param placementsPayload the {@code SCATTER_PLACEMENTS} stream payload ({@code ScatterPlacements})
     * @param generatedPayload the {@code SCATTER_GENERATED} stream payload (a {@code SparseUpload} of the cells the
     *     plan's trees and features grow), or {@code null} when they grow none
     */
    record PlanReady(UUID planId, int placements, SortedMap<String, Integer> rejectedCounts, long totalCells,
                     Box bounds, byte[] placementsPayload, byte[] generatedPayload) {
        public PlanReady {
            Objects.requireNonNull(planId);
            rejectedCounts = Collections.unmodifiableSortedMap(new TreeMap<>(rejectedCounts));
            Objects.requireNonNull(placementsPayload);
        }

        /** A plan without trees or features. */
        public PlanReady(UUID planId, int placements, SortedMap<String, Integer> rejectedCounts, long totalCells,
                         Box bounds, byte[] placementsPayload) {
            this(planId, placements, rejectedCounts, totalCells, bounds, placementsPayload, null);
        }
    }

    /** The answer to a preview; exactly one method is called, once, on the server thread. */
    interface PreviewReply {
        void done(PlanReady plan);

        /** Nothing was planned; {@code detail} is for the log and a notice. */
        void failed(RejectReason reason, String detail);

        /** A newer preview of the same player replaced this one before it finished. */
        void superseded();
    }

    /** Plans a scatter for the player; the player's newer preview replaces this one (and its plan). */
    void preview(ServerPlayerEntity p, C2S.ScatterPreview request, PreviewReply reply) throws EditRejected;

    /** Refuses everything with {@code DISABLED} (no engine running). */
    ScatterService DISABLED = (p, request, reply) -> {
        throw new EditRejected(RejectReason.DISABLED);
    };
}
