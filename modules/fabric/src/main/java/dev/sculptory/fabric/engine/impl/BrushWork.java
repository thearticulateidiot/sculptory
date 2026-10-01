package dev.sculptory.fabric.engine.impl;

import java.util.UUID;

/**
 * One piece of brush work for the executor's brush lane: a dab (with its copies), or a stroke's record being committed.
 * The lane serves players in turn ({@link #owner()}): each turn calls {@link #runPart} on the player's first item, which
 * does one or more pieces of it, and the player then waits for the others' turns. It goes on until its share of the tick
 * budget is used, starting a turn after the tick's first only when the item's next piece is predicted to end in time
 * ({@link #nextPieceNanos}). An item runs whole unless it overrides {@link #runPart}, which lets it stop between pieces
 * (a Shape step's parts, a large record's sections) and continue at the head of its player's queue on their next turn.
 */
@FunctionalInterface
public interface BrushWork {
    /** Runs the whole item on the server thread. Exceptions are logged and do not stop the lane. */
    void run();

    /**
     * Called instead of {@link #run()} when the executor will not run the item: it shuts down with the item queued, or
     * refuses it (stopped, or its queue full for an item that is not {@link #essential}).
     */
    default void dropped() {}

    /** The player the work is for; {@code null} for work of no player (it shares one queue). */
    default UUID owner() {
        return null;
    }

    /**
     * Runs the next pieces of the item: at least one, then more while each next one is predicted to end before
     * {@code deadline} ({@code System.nanoTime}); returns true when the item is done. The default runs it whole.
     */
    default boolean runPart(long deadline) {
        run();
        return true;
    }

    /** How long the item's next piece is predicted to take, in nanoseconds (0: unknown, or next to nothing). */
    default long nextPieceNanos() {
        return 0;
    }

    /**
     * Whether the executor must take the item even when its brush queue is full: work that ends what was already
     * admitted (a stroke's commit), bounded by the strokes rather than by the queue.
     */
    default boolean essential() {
        return false;
    }
}
