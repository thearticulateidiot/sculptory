package dev.sculptory.server.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PredictionAcksTest {
    private final List<Integer> acks = new ArrayList<>();
    private final PredictionAcks ordered = new PredictionAcks(acks::add);

    @Test
    void refusalsNeverOvertakeLowerAdmittedBatches() {
        ordered.admitted(3);
        ordered.admitted(4);
        ordered.refused(5);
        ordered.refused(2);
        assertEquals(List.of(2), acks, "2 is below every pending batch");
        ordered.applied(4); // applied out of order: 3 is still pending
        assertEquals(List.of(2), acks);
        ordered.applied(3);
        assertEquals(List.of(2, 5), acks, "one cumulative ack for 3, 4 and 5");
        assertEquals(0, ordered.pending());
    }

    @Test
    void reusedSequencesAreCounted() {
        ordered.admitted(0);
        ordered.admitted(0);
        ordered.refused(0);
        ordered.applied(0);
        assertTrue(acks.isEmpty(), "another batch under sequence 0 is still pending");
        ordered.applied(0);
        assertEquals(List.of(0), acks);
    }

    @Test
    void anUnreportedBacklogIsEventuallyReleased() {
        for (int seq = 0; seq <= PredictionAcks.MAX_TRACKED; seq++) ordered.admitted(seq);
        assertEquals(PredictionAcks.MAX_TRACKED, ordered.pending(), "the oldest unreported batch is dropped");
        ordered.refused(1000);
        assertTrue(acks.isEmpty());
        ordered.clear();
        ordered.refused(7);
        assertEquals(List.of(7), acks);
        ordered.refused(-1);
        ordered.admitted(-1);
        assertEquals(List.of(7), acks, "negative sequences are ignored");
    }
}
