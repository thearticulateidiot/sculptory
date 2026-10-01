package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {
    private static final long SECOND = TokenBucket.NANOS_PER_SECOND;

    @Test
    void bucketStartsFullAndRefillsAtItsRate() {
        AtomicLong now = new AtomicLong(123);
        TokenBucket bucket = new TokenBucket(5, 10, now::get);
        for (int i = 0; i < 10; i++) assertTrue(bucket.tryAcquire(1));
        assertFalse(bucket.tryAcquire(1));
        now.addAndGet(SECOND / 5 - 1);
        assertFalse(bucket.tryAcquire(1), "just under one token");
        now.addAndGet(1);
        assertTrue(bucket.tryAcquire(1));
        assertFalse(bucket.tryAcquire(1));
        now.addAndGet(100 * SECOND);
        assertEquals(10, bucket.available(), "capped at capacity");
        assertFalse(bucket.tryAcquire(11), "never more than capacity at once");
        assertEquals(10, bucket.available(), "a failed request takes nothing");
    }

    @Test
    void bucketsSlowerThanOnePerSecond() {
        AtomicLong now = new AtomicLong();
        TokenBucket perMinute = new TokenBucket(20, TokenBucket.NANOS_PER_MINUTE, 20, now::get);
        for (int i = 0; i < 20; i++) assertTrue(perMinute.tryAcquire(1));
        assertFalse(perMinute.tryAcquire(1));
        now.addAndGet(2_999_999_999L);
        assertFalse(perMinute.tryAcquire(1), "one token every 3 s");
        now.addAndGet(1);
        assertTrue(perMinute.tryAcquire(1));
        assertEquals(0, perMinute.ratePerSecond());
    }

    @Test
    void bucketIgnoresClockGoingBackwardsAndHugeGaps() {
        AtomicLong now = new AtomicLong(Long.MAX_VALUE - SECOND);
        TokenBucket bucket = new TokenBucket(1L << 20, 1L << 20, now::get);
        assertTrue(bucket.tryAcquire(1L << 20));
        now.addAndGet(-5 * SECOND);
        assertEquals(0, bucket.available());
        now.set(Long.MAX_VALUE);
        assertEquals(1L << 20, bucket.available());
        assertThrows(IllegalArgumentException.class, () -> bucket.tryAcquire(-1));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1, now::get));
    }

    @Test
    void framesAreChargedByTypeBeforeDecoding() {
        AtomicLong now = new AtomicLong();
        RateLimiter limiter = new RateLimiter(now::get);
        assertEquals(RateLimiter.Kind.OPS, RateLimiter.frameKind(MessageType.UNDO));
        assertEquals(RateLimiter.Kind.STROKES, RateLimiter.frameKind(MessageType.STROKE_BEGIN), "strokes have their own bucket");
        assertEquals(RateLimiter.Kind.RESYNC, RateLimiter.frameKind(MessageType.RESYNC), "so do resyncs");
        assertEquals(RateLimiter.Kind.DAB_FRAMES, RateLimiter.frameKind(MessageType.DABS));
        assertEquals(RateLimiter.Kind.UPLOAD, RateLimiter.frameKind(MessageType.STREAM_CHUNK));
        assertEquals(RateLimiter.Kind.CONTROL, RateLimiter.frameKind(MessageType.STROKE_END));
        assertEquals(5, limiter.bucket(RateLimiter.Kind.OPS).ratePerSecond());
        assertEquals(10, limiter.bucket(RateLimiter.Kind.STROKES).ratePerSecond());
        assertEquals(2, limiter.bucket(RateLimiter.Kind.RESYNC).ratePerSecond());
        assertEquals(60, limiter.bucket(RateLimiter.Kind.DABS).ratePerSecond());
        assertEquals(1L << 20, limiter.bucket(RateLimiter.Kind.UPLOAD).ratePerSecond());

        // Ops: burst of 10 shared by region ops and undo/redo, then 5 per second.
        for (int i = 0; i < 5; i++) assertTrue(limiter.tryAcquireFrame(MessageType.UNDO, 10));
        for (int i = 0; i < 5; i++) assertTrue(limiter.tryAcquireFrame(MessageType.RUN_OP, 300));
        assertFalse(limiter.tryAcquireFrame(MessageType.REDO, 10));

        // Quick brush taps and a resync burst don't touch the op bucket, nor each other.
        for (int i = 0; i < 20; i++) assertTrue(limiter.tryAcquireFrame(MessageType.STROKE_BEGIN, 300), "tap " + i);
        assertFalse(limiter.tryAcquireFrame(MessageType.STROKE_BEGIN, 300), "stroke burst is 20");
        for (int i = 0; i < 8; i++) assertTrue(limiter.tryAcquireFrame(MessageType.RESYNC, 30), "resync " + i);
        assertFalse(limiter.tryAcquireFrame(MessageType.RESYNC, 30), "resync burst is 8 (the client's per-timeout maximum)");

        now.addAndGet(SECOND);
        for (int i = 0; i < 5; i++) assertTrue(limiter.tryAcquireFrame(MessageType.RUN_OP, 10));
        assertFalse(limiter.tryAcquireFrame(MessageType.RUN_OP, 10));
        for (int i = 0; i < 10; i++) assertTrue(limiter.tryAcquireFrame(MessageType.STROKE_BEGIN, 300));
        assertFalse(limiter.tryAcquireFrame(MessageType.STROKE_BEGIN, 300), "then 10 strokes per second");
        for (int i = 0; i < 2; i++) assertTrue(limiter.tryAcquireFrame(MessageType.RESYNC, 30));
        assertFalse(limiter.tryAcquireFrame(MessageType.RESYNC, 30), "then 2 resyncs per second");

        // Dabs: frames and dabs are both limited.
        assertTrue(limiter.tryAcquireDabs(48));
        assertFalse(limiter.tryAcquireDabs(16));
        assertTrue(limiter.tryAcquireDabs(12));
        int frames = 0;
        while (limiter.tryAcquireFrame(MessageType.DABS, 100)) frames++;
        assertEquals(RateLimiter.Kind.DAB_FRAMES.burst(), frames);

        // Uploads: charged by frame size, 1 MiB/s.
        int chunks = 0;
        while (limiter.tryAcquireFrame(MessageType.STREAM_CHUNK, ProtocolV2.MAX_C2S_FRAME)) chunks++;
        assertEquals((1 << 20) / ProtocolV2.MAX_C2S_FRAME, chunks);

        // Control messages are not starved by the other classes.
        assertTrue(limiter.tryAcquireFrame(MessageType.STROKE_END, 5));
        assertTrue(limiter.tryAcquireFrame(MessageType.CANCEL_JOB, 17));
    }

    @Test
    void tinyStreamChunksCostAtLeastTheMinimumChunkSize() {
        AtomicLong now = new AtomicLong();
        RateLimiter limiter = new RateLimiter(now::get);
        assertEquals(StreamAssembler.MIN_CHUNK_BYTES, RateLimiter.frameCost(MessageType.STREAM_CHUNK, 5));
        assertEquals(StreamAssembler.MIN_CHUNK_BYTES, RateLimiter.frameCost(MessageType.STREAM_CHUNK, 1024));
        assertEquals(1025, RateLimiter.frameCost(MessageType.STREAM_CHUNK, 1025));
        // 1 MiB of burst: exactly 1024 tiny chunk frames, not a million.
        int frames = 0;
        while (limiter.tryAcquireFrame(MessageType.STREAM_CHUNK, 6)) frames++;
        assertEquals((1 << 20) / StreamAssembler.MIN_CHUNK_BYTES, frames);
        assertEquals(1, RateLimiter.frameCost(MessageType.STREAM_CREDIT, 5), "other frames still cost one token");
    }

    @Test
    void everyClientTypeHasAFrameClass() {
        for (MessageType type : MessageType.values()) {
            if (!type.clientToServer()) continue;
            assertTrue(RateLimiter.frameCost(type, 100) >= 1, type.name());
            RateLimiter.frameKind(type);
        }
    }
}
