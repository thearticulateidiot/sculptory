package dev.sculptory.fabric.client.session;

import dev.sculptory.core.Box;
import dev.sculptory.core.scatter.ScatterPlan;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * M3. A planned scatter the server now holds as {@code planId} (until it is committed, replaced by the next preview
 * or expires): its summary ({@code ScatterPlan}), its decoded placements ({@code SCATTER_PLACEMENTS}) and, when its
 * trees and features grew something, those cells as the server sent them ({@code SCATTER_GENERATED}, a sparse upload
 * the tool decodes with the game's blocks).
 *
 * @param rejectedCounts per scatter {@code Outcome} name, the area columns that ended with it (non-zero only)
 * @param totalCells the cells the placements write at most
 * @param bounds the union of the placements' footprints, or {@code null} without placements
 * @param placements in plan order; each {@code variant} indexes the request's variant list
 * @param grownPayload the grown cells' {@code SparseUpload} bytes, or {@code null} when nothing grew
 */
public record ScatterPreviewResult(int reqId, UUID planId, SortedMap<String, Integer> rejectedCounts, long totalCells,
                                   Box bounds, List<ScatterPlan.Placement> placements, byte[] grownPayload) {
    public ScatterPreviewResult {
        Objects.requireNonNull(planId);
        rejectedCounts = Collections.unmodifiableSortedMap(new TreeMap<>(rejectedCounts));
        placements = List.copyOf(placements);
        if (totalCells < 0) throw new IllegalArgumentException("Negative cell count");
    }

    /** A plan whose trees and features (if any) grew nothing. */
    public ScatterPreviewResult(int reqId, UUID planId, SortedMap<String, Integer> rejectedCounts, long totalCells,
                                Box bounds, List<ScatterPlan.Placement> placements) {
        this(reqId, planId, rejectedCounts, totalCells, bounds, placements, null);
    }

    public Optional<Box> boundsIfAny() {
        return Optional.ofNullable(bounds);
    }

    /** The area columns that ended without a placement, over every outcome. */
    public long rejectedTotal() {
        long total = 0;
        for (int count : rejectedCounts.values()) total += count;
        return total;
    }
}
