package dev.sculptory.fabric.net;

import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntConsumer;

/**
 * Orders one player's block-prediction acknowledgements. Vanilla acknowledgements are cumulative: acking
 * sequence {@code n} settles every client prediction up to {@code n}, reverting any whose server blocks have
 * not arrived. So an ack must never overtake a lower sequence that the brush lane admitted but has not applied
 * yet, or that batch's prediction flickers back until its blocks arrive.
 *
 * <p><b>Contract (dispatcher and brush lane, WS2b):</b>
 * <ul>
 *   <li>The dispatcher calls {@link #admitted} when {@code EditService.dabs} accepts a batch, and
 *       {@link #refused} for every batch it or the engine's {@code DabOutcome} refuses. The engine never acks a
 *       batch it refused in its {@code DabOutcome}.</li>
 *   <li>The brush lane calls {@link ServerNet#predictionApplied} exactly once per admitted batch, after writing
 *       its blocks (or after dropping it), instead of calling {@code updateSequence} itself.</li>
 *   <li>Acks go out in sequence order and never ahead of a pending lower sequence.</li>
 * </ul>
 * Server thread only.
 */
public final class PredictionAcks {
    /** Most admitted-but-unapplied or waiting sequences tracked; beyond this the oldest is released. */
    public static final int MAX_TRACKED = 256;

    private final IntConsumer sink;
    /** Admitted, not yet applied: sequence to count (clients may reuse a sequence when not predicting). */
    private final TreeMap<Integer, Integer> pending = new TreeMap<>();
    /** Applied or refused, waiting for every lower pending sequence. */
    private final TreeSet<Integer> ready = new TreeSet<>();
    private int pendingCount;

    /** @param sink sends one cumulative acknowledgement (vanilla {@code updateSequence}) */
    public PredictionAcks(IntConsumer sink) {
        this.sink = Objects.requireNonNull(sink);
    }

    /** The engine accepted a batch predicted under {@code seq}; it will call {@link #applied} later. */
    public void admitted(int seq) {
        if (seq < 0) return;
        pending.merge(seq, 1, Integer::sum);
        pendingCount++;
        if (pendingCount > MAX_TRACKED) {
            // The lane never reported this batch; stop holding later acks behind it.
            release(pending.firstKey());
        }
        flush();
    }

    /** The brush lane wrote (or dropped) an admitted batch. */
    public void applied(int seq) {
        if (seq < 0) return;
        release(seq);
        ready.add(seq);
        flush();
    }

    /** A batch was refused: acknowledge it once every lower admitted batch has been applied. */
    public void refused(int seq) {
        if (seq < 0) return;
        ready.add(seq);
        if (ready.size() > MAX_TRACKED) ready.pollFirst();
        flush();
    }

    /** Admitted batches not yet applied. */
    public int pending() {
        return pendingCount;
    }

    public void clear() {
        pending.clear();
        ready.clear();
        pendingCount = 0;
    }

    private void release(int seq) {
        Integer count = pending.get(seq);
        if (count == null) return;
        if (count == 1) pending.remove(seq);
        else pending.put(seq, count - 1);
        pendingCount--;
    }

    private void flush() {
        Integer lowestPending = pending.isEmpty() ? null : pending.firstKey();
        int highest = -1;
        while (!ready.isEmpty() && (lowestPending == null || ready.first() < lowestPending)) highest = ready.pollFirst();
        if (highest >= 0) sink.accept(highest);
    }
}
