package dev.sculptory.fabric.client.session;

import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.protocol.v2.ScatterPlacements;
import dev.sculptory.protocol.v2.StreamKind;
import dev.sculptory.protocol.v2.StreamOpen;
import java.util.List;

/**
 * The {@code SCATTER_GENERATED} half of a scatter preview: after the
 * placements, the server streams what the plan's trees and features grow, as one sparse upload ({@code BSGU}) whose
 * meta carries the plan's id and the request's id. The protocol announces nothing else, so the client expects the
 * stream exactly when a placement's variant is a tree or feature ({@link #expected}), and takes it only for the plan
 * just received ({@link #matches}) and within {@link #maxPayloadBytes}. Decoding needs the game's blocks and is the
 * Scatter tool's ({@code SparseUpload.decodePreview}). Pure.
 */
final class ScatterGenerated {
    /** The payload's fixed overhead allowance: magic, version, the cell set's header and the palette. */
    static final long BASE_BYTES = 1L << 20;
    /** Bytes per grown cell at most: its share of the cell set bitmap and its palette index (a varint). */
    static final long BYTES_PER_CELL = 8;
    /** Never more than this, whatever the plan says. */
    static final long MAX_BYTES = 256L << 20;

    private ScatterGenerated() {}

    /** Per request variant, whether it is a tree or feature. */
    static boolean[] featureVariants(List<C2S.ScatterPreview.Variant> variants) {
        boolean[] features = new boolean[variants.size()];
        for (int v = 0; v < features.length; v++) features[v] = variants.get(v).source() instanceof ScatterSource.Feature;
        return features;
    }

    /** Whether the plan's grown cells follow: some placement is of a tree or feature variant. */
    static boolean expected(List<ScatterPlan.Placement> placements, boolean[] featureVariants) {
        for (ScatterPlan.Placement placement : placements) {
            int v = placement.variant();
            if (v >= 0 && v < featureVariants.length && featureVariants[v]) return true;
        }
        return false;
    }

    /** Whether {@code m} opens the grown cells of {@code plan}, the answer to request {@code reqId}. */
    static boolean matches(StreamOpen m, S2C.ScatterPlan plan, int reqId) {
        return m.kind() == StreamKind.SCATTER_GENERATED
                && plan.planId().toString().equals(m.meta().get(ScatterPlacements.META_PLAN_ID))
                && Integer.toString(reqId).equals(m.meta().get(ScatterPlacements.META_REQ_ID));
    }

    /** The most bytes the grown cells of a plan writing {@code totalCells} cells can take. */
    static long maxPayloadBytes(long totalCells) {
        long cells = Math.max(0, totalCells);
        if (cells > (MAX_BYTES - BASE_BYTES) / BYTES_PER_CELL) return MAX_BYTES;
        return BASE_BYTES + cells * BYTES_PER_CELL;
    }
}
