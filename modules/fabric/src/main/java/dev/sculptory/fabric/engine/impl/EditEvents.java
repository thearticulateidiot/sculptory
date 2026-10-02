package dev.sculptory.fabric.engine.impl;

import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.impl.HistorySnapshot;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Pushes from {@link EngineEditService} that have no request to answer: history changes (including a stroke
 * committed by its idle timeout, and evictions caused by other players), dabs the brush lane skipped after
 * admitting them, and symmetric copies that found no ground. Called on the server thread, only for players who are
 * online. Every method defaults to doing nothing.
 */
public interface EditEvents {
    EditEvents NONE = new EditEvents() {};

    /**
     * The player's history changed: an entry was pushed, undone, redone or evicted. This may repeat what the
     * network layer sends after a {@code JobFinished}.
     */
    default void historyChanged(ServerPlayerEntity player, HistorySnapshot snapshot) {}

    /**
     * {@code steps} entries were discarded to fit the history caps ("oldest N steps discarded").
     * {@code includesNewest} is true when the entry just pushed did not fit on its own and was discarded too.
     */
    default void historyEvicted(ServerPlayerEntity player, int steps, boolean includesNewest) {}

    /**
     * The brush lane refused an admitted dab ({@code AREA_BUSY}: a job locked its area since; {@code UNLOADED};
     * {@code NO_PERMISSION}: the brush node was removed; {@code PROTECTED}: every cell it would change is
     * protected; {@code INVALID}: the kernel failed) and the stroke is now rejected: its remaining queued dabs are
     * skipped and later batches refused until a new stroke begins. Reported once per stroke. Batches are still
     * acknowledged, so the client's predictions revert; the network layer should end the stroke on the client
     * ({@code StrokeStatus(strokeId, dabIndex, REJECTED, reason)}).
     */
    default void dabRejected(ServerPlayerEntity player, int strokeId, int dabIndex, RejectReason reason) {}

    /**
     * The brush lane finished (wrote, skipped or dropped) an admitted batch of the stroke, whose last dab is
     * {@code lastIndex}: every dab of the stroke up to it is done. Batches finish in admission order. Clients pace
     * large dabs on this (they wait for the lane instead of being refused {@code RATE_LIMITED}).
     */
    default void dabsApplied(ServerPlayerEntity player, int strokeId, int lastIndex) {}

    /**
     * A scatter commit finished with {@code placements} placements skipped (or cut short) because cells they would
     * write were no longer open (something was built there since the preview), protected or outside the world border,
     * or their chunks did not load. The same count is in {@code JobFinished.skippedConflicts}.
     */
    default void scatterSkipped(ServerPlayerEntity player, long placements) {}

    /** The notice {@link #symmetryNoGround} becomes: {@code [copies, SymmetricStep.GROUND_SEARCH]}. */
    String SYMMETRY_NO_GROUND = "sculptory.notice.symmetry_no_ground";

    /**
     * {@code copies} symmetric copies of the dab {@code dabIndex} found no ground where they landed (within
     * {@code SymmetricStep.GROUND_SEARCH} blocks of the dab's height) and wrote nothing, while the rest of the step was
     * applied. Reported once per stroke, at the first such dab; the stroke goes on.
     */
    default void symmetryNoGround(ServerPlayerEntity player, int strokeId, int dabIndex, int copies) {}
}
