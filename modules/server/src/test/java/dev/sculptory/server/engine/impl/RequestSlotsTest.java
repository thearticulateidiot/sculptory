package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RequestSlotsTest {
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);

    @Test
    void perPlayerRequestClipboardAndSaveLimits() throws EditRejected {
        RequestSlots slots = new RequestSlots();
        RequestSlots.Lease build = slots.acquire(A, true, false);
        assertTrue(slots.building(A));
        assertEquals(RejectReason.QUEUE_FULL, assertThrows(EditRejected.class, () -> slots.acquire(A, true, false)).reason(),
                "a second decode for one player");
        RequestSlots.Lease list = slots.acquire(A, false, false);
        assertEquals(RejectReason.QUEUE_FULL, assertThrows(EditRejected.class, () -> slots.acquire(A, false, false)).reason(),
                "a third request for one player");
        assertEquals(2, slots.tasks(A));
        // Another player is not affected.
        RequestSlots.Lease other = slots.acquire(B, true, true);
        assertEquals(RejectReason.QUEUE_FULL, assertThrows(EditRejected.class, () -> slots.acquire(B, false, true)).reason(),
                "a second save for one player");
        build.release();
        build.release(); // idempotent
        assertFalse(slots.building(A));
        assertEquals(1, slots.tasks(A));
        slots.acquire(A, true, false);
        list.release();
        other.release();
        assertEquals(1, slots.totalTasks());
        assertFalse(slots.building(B));
    }

    @Test
    void theExecutorIsBoundedAndRefusesBeyondItsQueue() throws InterruptedException {
        ExecutorService io = ServerClipboards.newExecutor();
        CountDownLatch release = new CountDownLatch(1);
        try {
            int accepted = 0;
            boolean refused = false;
            for (int i = 0; i < ServerClipboards.IO_THREADS + ServerClipboards.IO_QUEUE + 5 && !refused; i++) {
                try {
                    io.execute(() -> {
                        try {
                            release.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    accepted++;
                } catch (RejectedExecutionException e) {
                    refused = true;
                }
            }
            assertTrue(refused, "the queue is unbounded");
            assertEquals(ServerClipboards.IO_THREADS + ServerClipboards.IO_QUEUE, accepted);
        } finally {
            release.countDown();
            io.shutdown();
            assertTrue(io.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
