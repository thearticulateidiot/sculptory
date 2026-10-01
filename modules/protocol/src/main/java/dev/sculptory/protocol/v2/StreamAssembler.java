package dev.sculptory.protocol.v2;

import dev.sculptory.core.Sha256;
import java.util.Arrays;
import java.util.Objects;

/**
 * Receives one stream: appends {@link StreamChunk}s, which must arrive in sequence ({@code seq} 0, 1, 2...),
 * into one contiguous buffer, grants credit so the sender keeps at most {@code window} bytes in flight, and at
 * {@link StreamEnd} checks that every byte arrived and the SHA-256 matches.
 *
 * <p>Memory: the buffer grows with the bytes actually received (doubling, never beyond the declared size, which
 * is checked against the receiver's cap when the stream opens), and every chunk except the final one must
 * carry at least {@link #MIN_CHUNK_BYTES}, so a sender cannot amplify memory or per-chunk work.
 *
 * <p>Credit: the sender starts with {@code window} bytes of credit. {@link #takeCredit()} returns a
 * {@link StreamCredit} to send back once enough data has arrived; data beyond the credit granted so far is a
 * protocol violation. Not thread-safe.
 */
public final class StreamAssembler {
    /** The receiver's credit window. */
    public static final long DEFAULT_WINDOW = 4L << 20;
    /** Smallest chunk accepted, except the one that completes the stream. */
    public static final int MIN_CHUNK_BYTES = 1024;

    private static final int INITIAL_CAPACITY = 64 << 10;

    private enum State {
        OPEN,
        FINISHED,
        ABORTED
    }

    private final StreamOpen open;
    private final long window;
    private byte[] buffer = new byte[0];
    private int received;
    private int nextSeq;
    private long granted;
    private State state = State.OPEN;

    /**
     * @param maxBytes the largest stream this receiver accepts
     * @param window the credit window; also the sender's initial credit
     * @throws ProtocolException ({@code TOO_LARGE}) if the declared size is over {@code maxBytes}
     */
    public StreamAssembler(StreamOpen open, long maxBytes, long window) throws ProtocolException {
        this.open = Objects.requireNonNull(open);
        if (window < 1) throw new IllegalArgumentException("Window must be positive");
        if (open.totalBytes() > maxBytes) {
            throw WireReader.tooLarge("Stream of " + open.totalBytes() + " bytes over the " + maxBytes + " byte cap");
        }
        if (open.totalBytes() > Integer.MAX_VALUE - 8) throw WireReader.tooLarge("Stream over 2 GiB");
        this.window = window;
        this.granted = window;
    }

    public StreamOpen open() {
        return open;
    }

    public int id() {
        return open.id();
    }

    public long receivedBytes() {
        return received;
    }

    public boolean isOpen() {
        return state == State.OPEN;
    }

    /**
     * Appends the next chunk.
     *
     * @throws ProtocolException for a chunk out of sequence (including duplicates), an empty or undersized
     *     chunk that does not finish the stream, data beyond the declared size or beyond the credit granted,
     *     or a closed stream
     */
    public void accept(StreamChunk chunk) throws ProtocolException {
        Objects.requireNonNull(chunk);
        if (state != State.OPEN) throw unexpected("Chunk for a closed stream " + open.id());
        if (chunk.id() != open.id()) throw WireReader.malformed("Chunk for stream " + chunk.id() + " sent to " + open.id());
        if (chunk.seq() != nextSeq) {
            throw WireReader.malformed("Stream " + open.id() + " expected chunk " + nextSeq + ", got " + chunk.seq());
        }
        int length = chunk.length();
        if (length == 0) throw WireReader.malformed("Empty stream chunk");
        long after = (long) received + length;
        if (after > open.totalBytes()) {
            throw WireReader.tooLarge("Stream " + open.id() + " exceeds its declared " + open.totalBytes() + " bytes");
        }
        if (length < MIN_CHUNK_BYTES && after != open.totalBytes()) {
            throw WireReader.malformed("Stream " + open.id() + " chunk of " + length + " bytes is under the minimum");
        }
        if (after > granted) {
            throw WireReader.tooLarge("Stream " + open.id() + " exceeds its credit of " + granted + " bytes");
        }
        ensureCapacity((int) after);
        System.arraycopy(chunk.bytes(), 0, buffer, received, length);
        received = (int) after;
        nextSeq++;
    }

    /**
     * Credit to send back to the sender now, or {@code null}. Keeps up to {@code window} bytes available
     * beyond what has arrived, and grants in steps of at least a quarter window (or whatever finishes the
     * stream) to avoid chatty credit messages.
     */
    public StreamCredit takeCredit() {
        if (state != State.OPEN) return null;
        long target = Math.min(open.totalBytes(), received + window);
        long extra = target - granted;
        if (extra <= 0) return null;
        if (extra < window / 4 && target < open.totalBytes()) return null;
        granted = target;
        return new StreamCredit(open.id(), extra);
    }

    /**
     * Completes the stream and returns its bytes.
     *
     * @throws ProtocolException if bytes are missing or the hash does not match
     */
    public byte[] finish(StreamEnd end) throws ProtocolException {
        Objects.requireNonNull(end);
        if (state != State.OPEN) throw unexpected("End of a closed stream " + open.id());
        if (end.id() != open.id()) throw WireReader.malformed("End for stream " + end.id() + " sent to " + open.id());
        state = State.ABORTED; // any failure below leaves the stream unusable
        byte[] data = buffer;
        buffer = null;
        if (received != open.totalBytes()) {
            throw WireReader.malformed(
                    "Stream " + open.id() + " ended after " + received + " of " + open.totalBytes() + " bytes");
        }
        if (data.length != received) data = Arrays.copyOf(data, received);
        if (!Sha256.digest(data).equals(end.sha256())) throw WireReader.malformed("Stream " + open.id() + " SHA-256 mismatch");
        state = State.FINISHED;
        return data;
    }

    /** Drops everything received; later chunks for this stream are refused. */
    public void abort() {
        state = State.ABORTED;
        buffer = null;
    }

    private void ensureCapacity(int needed) {
        if (needed <= buffer.length) return;
        long grown = Math.max(needed, Math.max(INITIAL_CAPACITY, (long) buffer.length * 2));
        buffer = Arrays.copyOf(buffer, (int) Math.min(open.totalBytes(), grown));
    }

    private static ProtocolException unexpected(String message) {
        return new ProtocolException(ProtocolException.Reason.UNEXPECTED, message);
    }
}
