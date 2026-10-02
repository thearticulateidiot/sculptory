package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SectionLocksTest {
    private static final Object OVERWORLD = "overworld";
    private static final Object NETHER = "nether";

    /** Tracks blocked counts the way the executor does. */
    private final Map<String, Integer> blocked = new HashMap<>();
    private final SectionLocks<String> locks = new SectionLocks<>();

    private void acquire(Object world, String job, long... keys) {
        blocked.put(job, locks.acquire(world, keys, job));
    }

    private List<String> release(Object world, String job, long... keys) {
        List<String> heads = new ArrayList<>();
        locks.release(world, keys, job, head -> {
            heads.add(head);
            blocked.merge(head, -1, Integer::sum);
        });
        return heads;
    }

    @Test
    void overlappingJobsRunInAdmissionOrder() {
        acquire(OVERWORLD, "A", 1, 2, 3);
        acquire(OVERWORLD, "B", 3, 4);
        acquire(OVERWORLD, "C", 4, 5);
        acquire(OVERWORLD, "D", 9);
        assertEquals(0, blocked.get("A"));
        assertEquals(1, blocked.get("B"));
        assertEquals(1, blocked.get("C"), "C waits behind B on section 4");
        assertEquals(0, blocked.get("D"), "disjoint jobs are not blocked");
        assertTrue(locks.isLocked(OVERWORLD, 4));
        assertFalse(locks.isLocked(OVERWORLD, 6));

        assertEquals(List.of("B"), release(OVERWORLD, "A", 1, 2, 3));
        assertEquals(0, blocked.get("B"));
        assertEquals(1, blocked.get("C"));
        assertFalse(locks.isLocked(OVERWORLD, 1));

        assertEquals(List.of("C"), release(OVERWORLD, "B", 3, 4));
        assertEquals(0, blocked.get("C"));
        release(OVERWORLD, "C", 4, 5);
        release(OVERWORLD, "D", 9);
        assertEquals(0, locks.lockedSections());
    }

    @Test
    void cancellingAQueuedJobHandsOnlyItsHeadedKeys() {
        acquire(OVERWORLD, "A", 1);
        acquire(OVERWORLD, "B", 1, 2); // heads 2, waits on 1
        acquire(OVERWORLD, "C", 2); // waits behind B on 2
        assertEquals(1, blocked.get("C"));
        assertEquals(List.of("C"), release(OVERWORLD, "B", 1, 2));
        assertEquals(0, blocked.get("C"));
        assertEquals(0, blocked.get("A"));
        assertTrue(locks.isLocked(OVERWORLD, 1));
    }

    @Test
    void worldsAreSeparate() {
        acquire(OVERWORLD, "A", 7);
        acquire(NETHER, "B", 7);
        assertEquals(0, blocked.get("B"));
        release(OVERWORLD, "A", 7);
        assertFalse(locks.isLocked(OVERWORLD, 7));
        assertTrue(locks.isLocked(NETHER, 7));
    }
}
