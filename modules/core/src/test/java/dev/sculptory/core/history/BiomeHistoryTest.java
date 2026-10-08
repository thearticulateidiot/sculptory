package dev.sculptory.core.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockBuffer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Biome history (protocol 6): the three outcomes, steps and re-apply runs, the record builder and the compact list. */
class BiomeHistoryTest {
    private static final String PLAINS = "minecraft:plains";
    private static final String DESERT = "minecraft:desert";
    private static final String CHERRY = "minecraft:cherry_grove";

    private static HistoryEntry entry(BiomeChange... changes) {
        return new HistoryEntry(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld", "Biome fill",
                new EditRecord(new BlockBuffer(), new BlockBuffer(), List.of(), List.of(changes)), 1);
    }

    @Test
    void decideSkipsTheTargetRestoresWhatTheStepLeftAndKeepsAnythingElse() {
        // Undo of plains → desert: expected desert, target plains.
        assertEquals(BiomeHistory.Action.SKIP, BiomeHistory.decide(DESERT, PLAINS, PLAINS, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(BiomeHistory.Action.APPLY, BiomeHistory.decide(DESERT, PLAINS, DESERT, ConflictPolicy.SKIP_CONFLICTS));
        assertEquals(BiomeHistory.Action.CONFLICT, BiomeHistory.decide(DESERT, PLAINS, CHERRY,
                ConflictPolicy.SKIP_CONFLICTS), "painted since the step");
        assertEquals(BiomeHistory.Action.APPLY, BiomeHistory.decide(DESERT, PLAINS, CHERRY, ConflictPolicy.OVERWRITE),
                "Undo anyway writes it all the same");
        assertEquals(BiomeHistory.Action.SKIP, BiomeHistory.decide(DESERT, PLAINS, PLAINS, ConflictPolicy.OVERWRITE));
    }

    @Test
    void stepsTurnTheRecordAroundForAnUndo() {
        HistoryEntry e = entry(new BiomeChange(1, 2, 3, PLAINS, DESERT), new BiomeChange(-1, -2, -3, CHERRY, DESERT));
        BiomeChanges undo = BiomeHistory.steps(e, false);
        assertEquals(List.of(new BiomeChange(1, 2, 3, DESERT, PLAINS), new BiomeChange(-1, -2, -3, DESERT, CHERRY)),
                undo, "before = expected, after = target");
        assertEquals(e.record().biomes(), BiomeHistory.steps(e, true));
    }

    @Test
    void reapplyKeepsTheLastWriterPerQuart() {
        // Two fills over one quart, plains → desert → cherry grove, undone; Undo anyway over both.
        HistoryEntry first = entry(new BiomeChange(0, 0, 0, PLAINS, DESERT), new BiomeChange(5, 0, 0, PLAINS, DESERT));
        HistoryEntry second = entry(new BiomeChange(0, 0, 0, DESERT, CHERRY));
        // The run of undo steps in the order applied: second, then first.
        BiomeChanges run = BiomeHistory.reapplySteps(List.of(second, first), false);
        assertEquals(List.of(new BiomeChange(0, 0, 0, DESERT, PLAINS), new BiomeChange(5, 0, 0, DESERT, PLAINS)), run,
                "the first entry's target wins at the shared quart, in its place");
    }

    @Test
    void theRecordBuilderKeepsFirstBeforeAndLastAfterAndDropsQuartsPaintedBack() {
        RecordBuilder builder = new RecordBuilder();
        builder.recordBiome(1, 2, 3, PLAINS, DESERT);
        builder.recordBiome(1, 2, 3, DESERT, CHERRY);
        builder.recordBiome(4, 5, 6, PLAINS, DESERT);
        builder.recordBiome(4, 5, 6, DESERT, PLAINS);
        assertEquals(List.of(new BiomeChange(1, 2, 3, PLAINS, CHERRY)), builder.build().biomes());
        assertEquals(2, builder.drainBiomes().size(), "a drain says the painted-back quart too, so it cancels");
        assertTrue(!builder.hasDirtyBiomes());
        builder.recordBiome(1, 2, 3, CHERRY, DESERT);
        assertEquals(List.of(new BiomeChange(1, 2, 3, PLAINS, DESERT)), builder.drainBiomes());
        assertThrows(IllegalArgumentException.class, () -> builder.recordBiome(0, 0, 0, "Not An Id", PLAINS));
    }

    @Test
    void theCompactListIsAListOfChangesThatTakesLittleRoom() {
        BiomeChanges.Builder builder = new BiomeChanges.Builder();
        for (int i = 0; i < 100_000; i++) builder.add(i, i % 96 - 16, -i, i % 2 == 0 ? PLAINS : DESERT, CHERRY);
        BiomeChanges changes = builder.build();
        assertEquals(100_000, changes.size());
        assertEquals(new BiomeChange(7, 7 % 96 - 16, -7, DESERT, CHERRY), changes.get(7));
        assertTrue(changes.estimatedBytes() < 16L * 100_000, changes.estimatedBytes() + " bytes");
        assertEquals(changes, BiomeChanges.of(List.copyOf(changes)), "equal as lists");
        assertThrows(IllegalArgumentException.class, () -> new BiomeChanges.Builder().add(0, 0, 0, PLAINS, DESERT)
                .add(0, 0, 0, PLAINS, CHERRY), "a quart once");
        EditRecord record = new EditRecord(new BlockBuffer(), new BlockBuffer(), List.of(),
                List.of(new BiomeChange(0, 1, 0, PLAINS, DESERT)));
        assertTrue(record.biomes() instanceof BiomeChanges);
        assertEquals(new dev.sculptory.core.Box(new dev.sculptory.core.BlockPos(0, 4, 0),
                new dev.sculptory.core.BlockPos(3, 7, 3)), record.bounds());
    }

    @Test
    void quartKeysPackEveryCoordinateTheWorldCanHave() {
        int[][] quarts = {{0, 0, 0}, {-7_500_000, -16, 7_500_000}, {7_500_000, 1015, -7_500_000}, {-1, -1, -1}};
        for (int[] q : quarts) {
            long key = BiomeHistory.key(q[0], q[1], q[2]);
            assertEquals(q[0], BiomeHistory.keyX(key));
            assertEquals(q[1], BiomeHistory.keyY(key));
            assertEquals(q[2], BiomeHistory.keyZ(key));
        }
    }
}
