package dev.sculptory.core.history.store;

import static dev.sculptory.core.history.store.StoreTestSupport.DATA_VERSION;
import static dev.sculptory.core.history.store.StoreTestSupport.assertSameRecord;
import static dev.sculptory.core.history.store.StoreTestSupport.await;
import static dev.sculptory.core.history.store.StoreTestSupport.close;
import static dev.sculptory.core.history.store.StoreTestSupport.entry;
import static dev.sculptory.core.history.store.StoreTestSupport.journal;
import static dev.sculptory.core.history.store.StoreTestSupport.load;
import static dev.sculptory.core.history.store.StoreTestSupport.open;
import static dev.sculptory.core.history.store.StoreTestSupport.sparse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.history.BiomeChange;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.HistoryEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Biomes in the history journal (protocol 6): {@code BIOMES} records survive restarts, compactions and rewrites; header
 * flag bit 1 is set before the first one, so a build that knows only entity records leaves the file alone; damaged
 * biome records give up only their entry; and the codec refuses malformed bodies.
 */
class HistoryStoreBiomesTest {
    private static final UUID ALICE = new UUID(0xA11CE, 9);

    @TempDir
    Path dir;

    private Path file(UUID player) {
        return dir.resolve(player + HistoryStore.EXTENSION);
    }

    private int flags(UUID player) throws IOException {
        byte[] header = Files.readAllBytes(file(player));
        return ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
    }

    /** A record of {@code n} painted quarts (and the cells of {@code cells}), from plains to cherry grove. */
    static EditRecord withBiomes(EditRecord cells, int n) {
        List<BiomeChange> biomes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            biomes.add(new BiomeChange(i % 7 - 3, i / 49 - 16, i / 7 % 7, i % 3 == 0 ? "minecraft:desert" : "minecraft:plains",
                    "minecraft:cherry_grove"));
        }
        return new EditRecord(cells.before(), cells.after(), cells.entities(), biomes);
    }

    @Test
    void biomeChangesSurviveARestartAndSetTheirFlagOnly() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry plain = entry(ALICE, "Fill", sparse(1), 1);
        journal(store, plain);
        close(store);
        assertEquals(0, flags(ALICE), "a file without biome records keeps the header earlier builds read");

        store = open(dir);
        HistoryEntry fill = entry(ALICE, "Biome fill", withBiomes(new EditRecord(new BlockBuffer(), new BlockBuffer()), 300),
                2);
        HistoryEntry paste = entry(ALICE, "Paste", withBiomes(sparse(3), 20), 3);
        journal(store, fill);
        journal(store, paste);
        close(store);
        assertEquals(Journal.FLAG_BIOMES, flags(ALICE), "bit 1, set before the first biome record");
        // A build knowing only entity records (flags 0 and 1) refuses the header and leaves the file alone.
        assertTrue((flags(ALICE) & ~Journal.FLAG_ENTITIES) != 0);

        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(3, loaded.entries().size(), loaded.problems().toString());
        assertSameRecord(plain.record(), loaded.entries().get(0).record());
        assertSameRecord(fill.record(), loaded.entries().get(1).record());
        assertSameRecord(paste.record(), loaded.entries().get(2).record());
        assertTrue(loaded.entries().get(1).record().before().isEmpty(), "an entry of biomes only");
        assertTrue(loaded.entries().get(1).record().biomes() instanceof dev.sculptory.core.history.BiomeChanges,
                "loaded compact, without an object a quart");
        close(reopened);
    }

    /**
     * An open record saved in batches: a later batch of the same entry replaces an earlier batch's change of the same
     * quart, and a quart painted back (the same biome on both sides) is dropped. Interrupted, it is recovered with its
     * biome cells counted in the label.
     */
    @Test
    void laterBatchesReplaceEarlierOnesAndAnInterruptedFillIsRecovered() throws IOException {
        HistoryStore store = open(dir);
        UUID entry = UUID.randomUUID();
        store.begin(ALICE, entry, 5, StoreTestSupport.WORLD, "Biome stroke");
        store.biomes(ALICE, entry, List.of(new BiomeChange(1, 2, 3, "minecraft:plains", "minecraft:desert"),
                new BiomeChange(4, 5, 6, "minecraft:plains", "minecraft:desert")));
        store.biomes(ALICE, entry, List.of(new BiomeChange(1, 2, 3, "minecraft:plains", "minecraft:badlands"),
                new BiomeChange(4, 5, 6, "minecraft:plains", "minecraft:plains")));
        close(store); // no seal: the server stopped mid-stroke
        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(1, loaded.entries().size(), loaded.problems().toString());
        assertEquals("Biome stroke (interrupted) · 0 blocks · 1 biome cell", loaded.entries().get(0).label());
        assertEquals(List.of(new BiomeChange(1, 2, 3, "minecraft:plains", "minecraft:badlands")),
                loaded.entries().get(0).record().biomes());
        close(reopened);
    }

    /** Compaction copies biome records and the flag; a rewrite from memory writes them; without them the flag goes. */
    @Test
    void compactionAndRewritesCarryBiomes() throws IOException {
        HistoryStore.Settings eager = new HistoryStore.Settings(128L << 20, 0, 0, 64, 1 << 20);
        HistoryStore store = open(dir, StorageIo.SYSTEM, eager);
        HistoryEntry a = entry(ALICE, "a", withBiomes(sparse(4), 40), 1);
        HistoryEntry b = entry(ALICE, "b", sparse(5), 2);
        journal(store, a);
        journal(store, b);
        store.evict(ALICE, b.id());
        assertTrue(store.awaitIdle(10_000));
        close(store);
        assertEquals(Journal.FLAG_BIOMES, flags(ALICE));
        HistoryStore reopened = open(dir, StorageIo.SYSTEM, eager);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(1, loaded.entries().size());
        assertSameRecord(a.record(), loaded.entries().get(0).record());

        HistoryEntry c = entry(ALICE, "c", withBiomes(sparse(6), 70_000), 3);
        reopened.rewrite(ALICE, List.of(snapshot(a), snapshot(c)), 2, List.of(), List.of());
        await(reopened, HistoryStore.Rewritten.class, r -> r.player().equals(ALICE), new ArrayList<>());
        close(reopened);
        assertEquals(Journal.FLAG_BIOMES, flags(ALICE));
        HistoryStore again = open(dir, StorageIo.SYSTEM, eager);
        HistoryStore.Loaded rewritten = load(again, ALICE);
        assertEquals(2, rewritten.entries().size(), rewritten.problems().toString());
        assertSameRecord(a.record(), rewritten.entries().get(0).record());
        assertSameRecord(c.record(), rewritten.entries().get(1).record());
        assertEquals(70_000, rewritten.entries().get(1).record().biomes().size(), "split into batches and joined");

        again.rewrite(ALICE, List.of(snapshot(b)), 1, List.of(), List.of());
        await(again, HistoryStore.Rewritten.class, r -> r.player().equals(ALICE), new ArrayList<>());
        close(again);
        assertEquals(0, flags(ALICE));
    }

    private static HistoryStore.SnapshotEntry snapshot(HistoryEntry e) {
        return new HistoryStore.SnapshotEntry(e.id(), e.world(), e.label(), e.createdMillis(), e.record().estimatedBytes(),
                e.record());
    }

    /** A damaged biome batch gives up only its entry; one of another data version is not restored. */
    @Test
    void damagedOrForeignBiomeBatchesGiveUpOnlyTheirEntry() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry good = entry(ALICE, "good", withBiomes(sparse(7), 5), 1);
        journal(store, good);
        UUID bad = UUID.randomUUID(), foreign = UUID.randomUUID();
        store.begin(ALICE, bad, 2, StoreTestSupport.WORLD, "bad");
        store.begin(ALICE, foreign, 3, StoreTestSupport.WORLD, "foreign");
        close(store);
        byte[] junk = {0, 1, 2, 3, 4, 5, 6, 7};
        byte[] body = BiomeCodec.encode(List.of(new BiomeChange(0, 0, 0, "minecraft:plains", "minecraft:desert")));
        Files.write(file(ALICE), concat(
                Journal.frame(Journal.Type.BIOMES, Journal.biomes(bad, DATA_VERSION, 1, junk)),
                Journal.frame(Journal.Type.SEAL, Journal.seal(bad, "bad", 2, 10)),
                Journal.frame(Journal.Type.PUSH, Journal.mark(bad)),
                Journal.frame(Journal.Type.BIOMES, Journal.biomes(foreign, DATA_VERSION - 1, 1, body)),
                Journal.frame(Journal.Type.SEAL, Journal.seal(foreign, "foreign", 3, 10)),
                Journal.frame(Journal.Type.PUSH, Journal.mark(foreign))), StandardOpenOption.APPEND);
        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(1, loaded.entries().size(), "the damaged and foreign entries are left out");
        assertSameRecord(good.record(), loaded.entries().get(0).record());
        assertEquals(List.of(bad, foreign), loaded.failed());
        close(reopened);
    }

    @Test
    void theCodecRoundTripsAndRefusesMalformedBatches() throws CorruptDataException {
        List<BiomeChange> batch = List.of(new BiomeChange(-7_000_000, -16, 7_000_000, "minecraft:plains", "mod:glade"),
                new BiomeChange(0, 79, 0, "mod:glade", "minecraft:plains"),
                new BiomeChange(1, 0, 1, "minecraft:plains", "minecraft:plains"));
        assertEquals(batch, BiomeCodec.decode(BiomeCodec.encode(batch), 3));
        byte[] body = BiomeCodec.encode(batch);
        assertThrows(CorruptDataException.class, () -> BiomeCodec.decode(body, 2), "the head's count must match");
        assertThrows(CorruptDataException.class, () -> BiomeCodec.decode(java.util.Arrays.copyOf(body, body.length - 2), 3));
        assertThrows(IllegalArgumentException.class, () -> BiomeCodec.encode(List.of(batch.get(0), batch.get(0))),
                "a quart once per batch");
        assertThrows(CorruptDataException.class, () -> BiomeCodec.decode(raw(w -> w.varint(1).varint(1)
                .utf("minecraft:plains").varint(0).varint(0).varint(0).varint(0).varint(1)), 1), "an index past the ids");
        assertThrows(CorruptDataException.class, () -> BiomeCodec.decode(raw(w -> w.varint(1).varint(1).utf("Not An Id")
                .varint(0).varint(0).varint(0).varint(0).varint(0)), 1), "a malformed id");
        assertThrows(CorruptDataException.class, () -> BiomeCodec.decode(raw(w -> w.varint(2).varint(1)
                .utf("minecraft:plains").varint(2).varint(2).varint(2).varint(0).varint(0).varint(2).varint(2).varint(2)
                .varint(0).varint(0)), 2), "the same quart twice");
        // Quarts must fit the history key: y in -2048..2047, |x| and |z| below 2^25.
        for (int[] q : new int[][] {{0, 2048, 0}, {0, -2049, 0}, {1 << 25, 0, 0}, {0, 0, -(1 << 25)}}) {
            assertThrows(IllegalArgumentException.class, () -> BiomeCodec.encode(List.of(new BiomeChange(q[0], q[1], q[2],
                    "minecraft:plains", "mod:glade"))), java.util.Arrays.toString(q));
            int zx = (q[0] << 1) ^ (q[0] >> 31), zy = (q[1] << 1) ^ (q[1] >> 31), zz = (q[2] << 1) ^ (q[2] >> 31);
            assertThrows(CorruptDataException.class, () -> BiomeCodec.decode(raw(w -> w.varint(1).varint(1)
                    .utf("minecraft:plains").varint(zx).varint(zy).varint(zz).varint(0).varint(0)), 1),
                    java.util.Arrays.toString(q));
        }
        List<BiomeChange> edges = List.of(new BiomeChange((1 << 25) - 1, -2048, -(1 << 25) + 1, "minecraft:plains",
                "mod:glade"), new BiomeChange(0, 2047, 0, "minecraft:plains", "mod:glade"));
        assertEquals(edges, BiomeCodec.decode(BiomeCodec.encode(edges), 2));
        assertEquals(2, BiomeCodec.batches(withBiomes(new EditRecord(new BlockBuffer(), new BlockBuffer()), 70_000)
                .biomes()).size(), "65,536 changes a batch");
    }

    private interface Body {
        void write(Bytes.Writer w);
    }

    /** A raw (not deflated) body. */
    private static byte[] raw(Body body) {
        Bytes.Writer w = new Bytes.Writer(64);
        w.u8(0);
        body.write(w);
        return w.toArray();
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) length += part.length;
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }
}
