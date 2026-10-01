package dev.sculptory.protocol.v2;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Per-connection C2S rate limits, one {@link TokenBucket} per class. Every frame is
 * charged by its message type <em>before</em> it is decoded ({@link #tryAcquireFrame}), so a flood costs no
 * decoding work; dabs are additionally charged per dab once decoded ({@link #tryAcquireDabs}).
 * Not thread-safe.
 */
public final class RateLimiter {
    /** Buckets: rate per second and burst. */
    public enum Kind {
        /** {@code Dabs} frames (one per client tick is normal). */
        DAB_FRAMES(25, 40),
        /** Dabs inside {@code Dabs} frames (docs: 60/s). */
        DABS(60, 60),
        /** Region ops, undo/redo and the M2/M3 requests. Burst: two seconds' worth. */
        OPS(5, 10),
        /** {@code StrokeBegin}: one per brush press, so quick tapping stays inside it. */
        STROKES(10, 20),
        /** {@code Resync}: the client sends at most 8 boxes per acknowledgement timeout. */
        RESYNC(2, 8),
        /** Bytes of {@code StreamChunk} frames (docs: 1 MiB/s). */
        UPLOAD(1L << 20, 1L << 20),
        /**
         * Tinker requests (protocol 5): one per scroll notch or panel change, but the client keeps at most one in flight
         * and merges what comes meanwhile, so a fast scroll stays well inside it.
         */
        TINKER(10, 20),
        /**
         * Builder mode's {@code BuilderPlace} and {@code BuilderBreak} frames: a held click repeats every 4 ticks and a
         * bulldozer drag sends one frame a tick (20/s); the cells themselves are rate-limited by the server's builder
         * budget.
         */
        BUILDER(40, 80),
        /** Handshake, stroke end, cancel and stream control. */
        CONTROL(100, 200);

        private final long ratePerSecond;
        private final long burst;

        Kind(long ratePerSecond, long burst) {
            this.ratePerSecond = ratePerSecond;
            this.burst = burst;
        }

        public long ratePerSecond() {
            return ratePerSecond;
        }

        public long burst() {
            return burst;
        }
    }

    private final Map<Kind, TokenBucket> buckets = new EnumMap<>(Kind.class);

    public RateLimiter(LongSupplier nanoClock) {
        Objects.requireNonNull(nanoClock);
        for (Kind kind : Kind.values()) buckets.put(kind, new TokenBucket(kind.ratePerSecond, kind.burst, nanoClock));
    }

    /** The bucket a frame of {@code type} is charged to before decoding. */
    public static Kind frameKind(MessageType type) {
        return switch (type) {
            case DABS -> Kind.DAB_FRAMES;
            case STROKE_BEGIN -> Kind.STROKES;
            case RESYNC -> Kind.RESYNC;
            case RUN_OP, UNDO, REDO, COPY, PREVIEW_REQUEST, LIBRARY_LIST, LIBRARY_LOAD, SAVE_ASSET, EXPORT_CLIPBOARD,
                 UPLOAD_BEGIN, SCATTER_PREVIEW, LIBRARY_MOVE, LIBRARY_DELETE, LIBRARY_CREATE_FOLDER,
                 HISTORY_OVERWRITE, PALETTE_SAVE, PALETTE_LOAD, SELECTION_UPLOAD, LIBRARY_ACCESS_GET,
                 LIBRARY_ACCESS_SET, GENERATED_UPLOAD, SET_EDIT_MASK, NAVIGATE -> Kind.OPS;
            case TINKER_BLOCK, TINKER_ENTITY -> Kind.TINKER;
            case BUILDER_PLACE, BUILDER_BREAK -> Kind.BUILDER;
            case STREAM_CHUNK -> Kind.UPLOAD;
            default -> Kind.CONTROL;
        };
    }

    /**
     * Tokens a frame costs before decoding: for stream chunks their size, but at least
     * {@link StreamAssembler#MIN_CHUNK_BYTES} (so tiny chunk frames cannot be sent by the thousand), otherwise 1.
     */
    public static long frameCost(MessageType type, int frameBytes) {
        return frameKind(type) == Kind.UPLOAD ? Math.max(StreamAssembler.MIN_CHUNK_BYTES, frameBytes) : 1;
    }

    /** Charges one frame of {@code type}; false (and nothing charged) when over the limit. */
    public boolean tryAcquireFrame(MessageType type, int frameBytes) {
        return buckets.get(frameKind(type)).tryAcquire(frameCost(type, frameBytes));
    }

    /** Charges the dabs of a decoded {@code Dabs} message. */
    public boolean tryAcquireDabs(int count) {
        return buckets.get(Kind.DABS).tryAcquire(count);
    }

    public TokenBucket bucket(Kind kind) {
        return buckets.get(kind);
    }
}
