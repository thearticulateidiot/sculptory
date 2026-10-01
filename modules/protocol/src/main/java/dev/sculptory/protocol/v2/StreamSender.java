package dev.sculptory.protocol.v2;

import dev.sculptory.core.Sha256;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;

/**
 * Sends one stream: {@link StreamOpen}, then {@link StreamChunk}s sized to fit the frame cap, the remaining
 * credit and the caller's per-call byte budget, then {@link StreamEnd} with the payload's SHA-256.
 * Not thread-safe.
 */
public final class StreamSender {
    /** Bytes of a chunk frame that are not data: type, id, sequence and length varints. */
    public static final int CHUNK_OVERHEAD = 16;
    public static final int MAX_C2S_CHUNK = ProtocolV2.MAX_C2S_FRAME - CHUNK_OVERHEAD;
    public static final int MAX_S2C_CHUNK = ProtocolV2.MAX_S2C_FRAME - CHUNK_OVERHEAD;
    /** Stream data the server sends per player per tick. */
    public static final long SERVER_BYTES_PER_TICK = 512L << 10;
    /** Upload data a client sends per tick: at 20 ticks per second this stays under the 1 MiB/s upload limit. */
    public static final long CLIENT_BYTES_PER_TICK = 48L << 10;

    private final StreamOpen open;
    private final byte[] payload;
    private final Sha256 sha256;
    private final int maxChunk;
    private long credit;
    private int offset;
    private int nextSeq;
    private boolean opened;
    private boolean ended;
    private boolean aborted;

    /**
     * @param maxChunkBytes {@link #MAX_C2S_CHUNK} or {@link #MAX_S2C_CHUNK} (or less)
     * @param initialCredit the receiver's window ({@link StreamAssembler#DEFAULT_WINDOW} for server streams,
     *     {@code UploadGrant.creditBytes} for uploads)
     */
    public StreamSender(int id, StreamKind kind, byte[] payload, SortedMap<String, String> meta, int maxChunkBytes,
                        long initialCredit) {
        Objects.requireNonNull(payload);
        if (maxChunkBytes < StreamAssembler.MIN_CHUNK_BYTES || maxChunkBytes > MAX_S2C_CHUNK) {
            throw new IllegalArgumentException("Chunk size");
        }
        if (initialCredit < 0) throw new IllegalArgumentException("Negative credit");
        this.payload = payload.clone();
        this.open = new StreamOpen(id, kind, this.payload.length, meta);
        this.sha256 = Sha256.digest(this.payload);
        this.maxChunk = maxChunkBytes;
        this.credit = initialCredit;
    }

    public int id() {
        return open.id();
    }

    public StreamOpen open() {
        return open;
    }

    public long sentBytes() {
        return offset;
    }

    public long credit() {
        return credit;
    }

    /** True once the end or an abort has been produced. */
    public boolean done() {
        return ended || aborted;
    }

    /**
     * The next messages to send, using at most {@code byteBudget} bytes of chunk data: the open (first call),
     * chunks while credit and budget last, and the end once every byte is out. Empty when blocked on credit
     * or done. Every message is both {@link C2S} and {@link S2C}.
     */
    public List<Message> poll(long byteBudget) {
        List<Message> out = new ArrayList<>();
        if (done()) return out;
        if (!opened) {
            out.add(open);
            opened = true;
        }
        long budget = Math.max(0, byteBudget);
        while (offset < payload.length) {
            long allowance = Math.min(credit, budget);
            int remaining = payload.length - offset;
            int size = (int) Math.min(Math.min(maxChunk, remaining), allowance);
            // Only the final chunk may be under the receiver's minimum; otherwise wait for credit or budget.
            if (size <= 0 || (size < StreamAssembler.MIN_CHUNK_BYTES && size < remaining)) break;
            out.add(new StreamChunk(open.id(), nextSeq++, Arrays.copyOfRange(payload, offset, offset + size)));
            offset += size;
            credit -= size;
            budget -= size;
        }
        if (offset == payload.length) {
            out.add(new StreamEnd(open.id(), sha256));
            ended = true;
        }
        return out;
    }

    /** Adds credit granted by the receiver (saturating). */
    public void credit(StreamCredit grant) {
        Objects.requireNonNull(grant);
        if (grant.id() != open.id()) throw new IllegalArgumentException("Credit for stream " + grant.id());
        long sum = credit + grant.bytes();
        credit = sum < credit ? Long.MAX_VALUE : sum;
    }

    /** Stops the stream; returns the abort to send, or {@code null} if it had already ended. */
    public StreamAbort abort(String reason) {
        if (done()) return null;
        aborted = true;
        return new StreamAbort(open.id(), reason);
    }
}
