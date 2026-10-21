package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sculptory.protocol.v2.S2C;
import java.util.List;
import org.junit.jupiter.api.Test;

class HistorySnapshotTest {
    @Test
    void messageCarriesEveryField() {
        HistorySnapshot snapshot = new HistorySnapshot(true, false, List.of("Fill", "Paste"), List.of(), 4096, false);
        assertEquals(new S2C.HistoryState(true, false, "Fill", "", 4096, List.of("Fill", "Paste"), List.of()),
                snapshot.toMessage());
    }

    @Test
    void emptyHistoryHasEmptyLabels() {
        assertEquals(new S2C.HistoryState(false, false, "", "", 0, List.of(), List.of()), HistorySnapshot.EMPTY.toMessage());
    }
}
