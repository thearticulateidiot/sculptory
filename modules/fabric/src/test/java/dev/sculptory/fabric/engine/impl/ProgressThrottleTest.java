package dev.sculptory.fabric.engine.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProgressThrottleTest {
    private static final long MS = 1_000_000L;

    @Test
    void atMostFourPerSecondAndOnFivePercentSteps() {
        ProgressThrottle throttle = new ProgressThrottle();
        long total = 1000;
        assertTrue(throttle.shouldEmit(0, 10, total), "first event");
        throttle.emitted(0, 10);
        assertFalse(throttle.shouldEmit(100 * MS, 500, total), "within 250 ms");
        assertFalse(throttle.shouldEmit(300 * MS, 40, total), "less than 5% and not yet silent for a second");
        assertTrue(throttle.shouldEmit(300 * MS, 60, total), "5% step after 250 ms");
        throttle.emitted(300 * MS, 60);
        assertFalse(throttle.shouldEmit(1200 * MS, 60, total), "no change");
        assertTrue(throttle.shouldEmit(1300 * MS, 61, total), "a second of silence with progress");
    }

    @Test
    void sixtyTicksOfSteadyProgressStayWithinTheRate() {
        ProgressThrottle throttle = new ProgressThrottle();
        int events = 0;
        for (int tick = 0; tick < 60; tick++) { // 3 s at 20 TPS, 1% per tick
            long now = tick * 50 * MS;
            if (throttle.shouldEmit(now, tick + 1, 100)) {
                throttle.emitted(now, tick + 1);
                events++;
            }
        }
        assertTrue(events <= 12 && events >= 3, "events " + events);
    }
}
