package dev.sculptory.core.history.store;

import static dev.sculptory.core.history.store.StoreTestSupport.AIR;
import static dev.sculptory.core.history.store.StoreTestSupport.DATA_VERSION;
import static dev.sculptory.core.history.store.StoreTestSupport.STONE;
import static dev.sculptory.core.history.store.StoreTestSupport.assertSameRecord;
import static dev.sculptory.core.history.store.StoreTestSupport.await;
import static dev.sculptory.core.history.store.StoreTestSupport.close;
import static dev.sculptory.core.history.store.StoreTestSupport.entry;
import static dev.sculptory.core.history.store.StoreTestSupport.journal;
import static dev.sculptory.core.history.store.StoreTestSupport.load;
import static dev.sculptory.core.history.store.StoreTestSupport.open;
import static dev.sculptory.core.history.store.StoreTestSupport.record;
import static dev.sculptory.core.history.store.StoreTestSupport.sparse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.EntityChange;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
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
 * Entities in the history journal: {@code ENTITIES} records survive restarts,
 * compactions, rewrites and crashes; the header flag keeps earlier builds from cutting a file at the first one; damaged
 * or cut-off entity records are handled like damaged sections; and the codec refuses malformed bodies.
 */
class HistoryStoreEntitiesTest {
    private static final UUID ALICE = new UUID(0xA11CE, 7);

    @TempDir
    Path dir;

    private Path file(UUID player) {
        return dir.resolve(player + HistoryStore.EXTENSION);
    }

    /** An entity's whole NBT as the world holds it. */
    static EntityState state(UUID id, String type, double x, String name) {
        long m = id.getMostSignificantBits(), l = id.getLeastSignificantBits();
        NbtCompound nbt = NbtCompound.builder().putString("id", type).put("Pos", EntityNbt.doubles(x, 64, 2.5))
                .putIntArray("UUID", new int[] {(int) (m >> 32), (int) m, (int) (l >> 32), (int) l})
                .putString("CustomName", "\"" + name + "\"").build();
        return new EntityState(type, x, 64, 2.5, NbtIo.toBytes(nbt));
    }

    /** A paste's record: a few cells and entities placed; a cut's: entities removed. */
    static EditRecord withEntities(EditRecord cells, int placed, int removed) {
        List<EntityChange> changes = new ArrayList<>();
        for (int i = 0; i < placed; i++) {
            UUID id = UUID.randomUUID();
            changes.add(new EntityChange(id, null, state(id, "minecraft:item_frame", i + 0.5, "placed" + i)));
        }
        for (int i = 0; i < removed; i++) {
            UUID id = UUID.randomUUID();
            changes.add(new EntityChange(id, state(id, "minecraft:armor_stand", -i - 0.5, "removed" + i), null));
        }
        return new EditRecord(cells.before(), cells.after(), changes);
    }

    private int flags(UUID player) throws IOException {
        byte[] header = Files.readAllBytes(file(player));
        return ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
    }

    @Test
    void entityChangesSurviveARestartAndTheHeaderSaysSoOnlyOnceNeeded() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry plain = entry(ALICE, "Fill", sparse(1), 1);
        journal(store, plain);
        StoreTestSupport.close(store);
        assertEquals(0, flags(ALICE), "a file without entity records keeps the header earlier builds read");

        store = open(dir);
        HistoryEntry paste = entry(ALICE, "Paste", withEntities(sparse(2), 3, 0), 2);
        HistoryEntry cut = entry(ALICE, "Cut", withEntities(sparse(3), 0, 2), 3);
        HistoryEntry only = entry(ALICE, "Paste", withEntities(new EditRecord(new BlockBuffer(), new BlockBuffer()), 1, 0),
                4);
        journal(store, paste);
        journal(store, cut);
        journal(store, only);
        close(store);
        assertEquals(Journal.FLAG_ENTITIES, flags(ALICE), "set before the first entity record");

        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(4, loaded.entries().size(), loaded.problems().toString());
        assertSameRecord(plain.record(), loaded.entries().get(0).record());
        assertSameRecord(paste.record(), loaded.entries().get(1).record());
        assertSameRecord(cut.record(), loaded.entries().get(2).record());
        assertSameRecord(only.record(), loaded.entries().get(3).record());
        assertTrue(loaded.entries().get(3).record().before().isEmpty(), "an entry of entities only");
        close(reopened);
    }

    /** A later batch of the same entry replaces an earlier batch's change of the same entity (a cancelled placement). */
    @Test
    void laterBatchesReplaceEarlierOnesAndCancelledPlacementsAreDropped() throws IOException {
        HistoryStore store = open(dir);
        UUID entry = UUID.randomUUID(), kept = UUID.randomUUID(), cancelled = UUID.randomUUID();
        EntityState first = state(kept, "minecraft:item_frame", 1.5, "first");
        EntityState last = state(kept, "minecraft:item_frame", 1.5, "last");
        EntityState gone = state(cancelled, "minecraft:boat", 3.5, "gone");
        store.begin(ALICE, entry, 5, StoreTestSupport.WORLD, "Paste");
        store.entities(ALICE, entry, List.of(new EntityChange(kept, null, first), new EntityChange(cancelled, null, gone)));
        store.entities(ALICE, entry, List.of(new EntityChange(kept, null, last), new EntityChange(cancelled, null, null)));
        store.seal(ALICE, entry, "Paste · 0 blocks · 1 entity", 5, 100);
        store.push(ALICE, entry);
        close(store);
        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(List.of(new EntityChange(kept, null, last)), loaded.entries().get(0).record().entities());
        close(reopened);
    }

    /** Compaction copies entity records (and the flag); dropping every entity entry drops the flag. */
    @Test
    void compactionAndRewritesCarryEntities() throws IOException {
        HistoryStore.Settings eager = new HistoryStore.Settings(128L << 20, 0, 0, 64, 1 << 20);
        HistoryStore store = open(dir, StorageIo.SYSTEM, eager);
        HistoryEntry a = entry(ALICE, "a", withEntities(sparse(4), 2, 1), 1);
        HistoryEntry b = entry(ALICE, "b", sparse(5), 2);
        journal(store, a);
        journal(store, b);
        // Evicting b leaves dead records worth compacting; a's entities are copied into the new file.
        store.evict(ALICE, b.id());
        assertTrue(store.awaitIdle(10_000));
        close(store);
        assertEquals(Journal.FLAG_ENTITIES, flags(ALICE));
        HistoryStore reopened = open(dir, StorageIo.SYSTEM, eager);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(1, loaded.entries().size());
        assertSameRecord(a.record(), loaded.entries().get(0).record());

        // A rewrite from memory writes the entities of every entry it is given.
        HistoryEntry c = entry(ALICE, "c", withEntities(record(r -> r.record(0, 70, 0, AIR, null, STONE, null)), 1, 1), 3);
        reopened.rewrite(ALICE, List.of(snapshot(a), snapshot(c)), 2, List.of(), List.of());
        await(reopened, HistoryStore.Rewritten.class, r -> r.player().equals(ALICE), new ArrayList<>());
        close(reopened);
        assertEquals(Journal.FLAG_ENTITIES, flags(ALICE));
        HistoryStore again = open(dir, StorageIo.SYSTEM, eager);
        HistoryStore.Loaded rewritten = load(again, ALICE);
        assertEquals(2, rewritten.entries().size(), rewritten.problems().toString());
        assertSameRecord(a.record(), rewritten.entries().get(0).record());
        assertSameRecord(c.record(), rewritten.entries().get(1).record());

        // Without entity entries left, a compacted copy has the plain header again.
        again.rewrite(ALICE, List.of(snapshot(b)), 1, List.of(), List.of());
        await(again, HistoryStore.Rewritten.class, r -> r.player().equals(ALICE), new ArrayList<>());
        close(again);
        assertEquals(0, flags(ALICE));
    }

    /** The real file system, noting each write (its position and first bytes) and each force of a journal file. */
    private static final class RecordingIo implements StorageIo {
        final List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public File open(Path file) throws IOException {
            File inner = SYSTEM.open(file);
            boolean journal = file.toString().endsWith(HistoryStore.EXTENSION);
            return new File() {
                @Override
                public long size() throws IOException {
                    return inner.size();
                }

                @Override
                public int read(java.nio.ByteBuffer dst, long position) throws IOException {
                    return inner.read(dst, position);
                }

                @Override
                public void write(java.nio.ByteBuffer src, long position) throws IOException {
                    if (journal) {
                        int type = src.remaining() > 4 ? src.get(src.position() + 4) : -1;
                        events.add(position == 0 ? "header" : "record " + type);
                    }
                    inner.write(src, position);
                }

                @Override
                public void truncate(long size) throws IOException {
                    inner.truncate(size);
                }

                @Override
                public void force() throws IOException {
                    if (journal) events.add("force");
                    inner.force();
                }

                @Override
                public void close() throws IOException {
                    inner.close();
                }
            };
        }

        @Override
        public boolean exists(Path file) throws IOException {
            return SYSTEM.exists(file);
        }

        @Override
        public List<Path> list(Path dir) throws IOException {
            return SYSTEM.list(dir);
        }

        @Override
        public void createDirectories(Path dir) throws IOException {
            SYSTEM.createDirectories(dir);
        }

        @Override
        public void replace(Path source, Path target) throws IOException {
            SYSTEM.replace(source, target);
        }

        @Override
        public void delete(Path file) throws IOException {
            SYSTEM.delete(file);
        }

        @Override
        public void copy(Path source, Path target) throws IOException {
            SYSTEM.copy(source, target);
        }
    }

    /**
     * The header saying a file holds entity records reaches the device before the first such record is written: a
     * crash in between never leaves an entity record under a header an earlier build would read and cut.
     */
    @Test
    void theEntityFlagIsForcedBeforeTheFirstEntityRecord() throws IOException {
        RecordingIo io = new RecordingIo();
        HistoryStore store = open(dir, io, HistoryStore.Settings.DEFAULTS);
        journal(store, entry(ALICE, "Fill", sparse(1), 1));
        journal(store, entry(ALICE, "Paste", withEntities(sparse(2), 1, 0), 2));
        journal(store, entry(ALICE, "Paste again", withEntities(sparse(3), 1, 0), 3));
        close(store);
        List<String> events = new ArrayList<>(io.events);
        int flagged = events.lastIndexOf("header");
        int firstEntities = events.indexOf("record " + Journal.Type.ENTITIES.code);
        assertTrue(flagged >= 0 && firstEntities > flagged, events.toString());
        assertTrue(events.subList(flagged, firstEntities).contains("force"), "forced in between: " + events);
        assertEquals(1, events.stream().filter("header"::equals).count() - 1, "the flag is set once: " + events);
        assertEquals(Journal.FLAG_ENTITIES, flags(ALICE));
    }

    /** Whether the journal file holds an {@code ENTITIES} record (walking its frames from the header on). */
    private boolean holdsEntityRecord(UUID player) throws IOException {
        byte[] bytes = Files.readAllBytes(file(player));
        int position = Journal.HEADER_BYTES;
        while (position + Journal.FRAME_BYTES <= bytes.length) {
            int payload = Journal.getInt(bytes, position);
            if (bytes[position + 4] == Journal.Type.ENTITIES.code) return true;
            position += Journal.FRAME_BYTES + payload;
        }
        return false;
    }

    /**
     * Records written while a compaction runs are copied into it at its end. An entity entry written then and dropped
     * again before the end (aborted, evicted) leaves its ENTITIES record in the copy: the copy's header must still say
     * so, or an earlier build would cut the file there. Plain entries keep files compacting; every entity entry is
     * dropped at once, so the compaction plans never hold entities themselves.
     */
    @Test
    void entityRecordsCopiedByACompactionKeepTheFlag() throws IOException {
        HistoryStore.Settings eager = new HistoryStore.Settings(1L << 30, 10, 16 << 10, 4, 1 << 10);
        HistoryStore store = open(dir, StorageIo.SYSTEM, eager);
        java.util.Random random = new java.util.Random(5);
        java.util.ArrayDeque<UUID> live = new java.util.ArrayDeque<>();
        for (int step = 0; step < 600; step++) {
            if (random.nextInt(8) == 0) {
                HistoryEntry e = entry(ALICE, "e" + step, withEntities(sparse(step), 1, 1), step);
                store.begin(ALICE, e.id(), e.createdMillis(), e.world(), e.label());
                store.entities(ALICE, e.id(), e.record().entities());
                if (random.nextBoolean()) {
                    store.abort(ALICE, e.id());
                } else {
                    store.seal(ALICE, e.id(), e.label(), e.createdMillis(), 100);
                    store.push(ALICE, e.id());
                    store.evict(ALICE, e.id());
                }
            } else {
                HistoryEntry e = entry(ALICE, "e" + step, sparse(step), step);
                journal(store, e);
                live.add(e.id());
                if (live.size() > 3) store.evict(ALICE, live.poll());
            }
        }
        assertTrue(store.flush(20_000));
        assertTrue(store.awaitIdle(20_000));
        close(store);
        if (holdsEntityRecord(ALICE)) {
            assertEquals(Journal.FLAG_ENTITIES, flags(ALICE), "an ENTITIES record under a header without the flag");
        }
        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(live.size(), loaded.entries().size(), loaded.problems().toString());
        close(reopened);
    }

    private static HistoryStore.SnapshotEntry snapshot(HistoryEntry e) {
        return new HistoryStore.SnapshotEntry(e.id(), e.world(), e.label(), e.createdMillis(), e.record().estimatedBytes(),
                e.record());
    }

    /** An edit cut off by a crash that had only removed entities is sealed as interrupted and can be undone. */
    @Test
    void anInterruptedEditOfEntitiesOnlyIsRecovered() throws IOException {
        HistoryStore store = open(dir);
        UUID entry = UUID.randomUUID(), removed = UUID.randomUUID();
        EntityState stand = state(removed, "minecraft:armor_stand", 4.5, "stand");
        store.begin(ALICE, entry, 9, StoreTestSupport.WORLD, "Cut");
        store.entities(ALICE, entry, List.of(new EntityChange(removed, stand, null)));
        close(store); // no seal, no push: the server stopped mid-job
        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(1, loaded.entries().size(), loaded.problems().toString());
        assertEquals("Cut (interrupted) · 0 blocks · 1 entity", loaded.entries().get(0).label());
        assertEquals(List.of(new EntityChange(removed, stand, null)), loaded.entries().get(0).record().entities());
        close(reopened);
    }

    /** A cut-off entity record at the end of the file is a torn tail: the file is cut there and the rest loads. */
    @Test
    void aTornEntityRecordIsCutOff() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry a = entry(ALICE, "a", withEntities(sparse(6), 1, 0), 1);
        journal(store, a);
        close(store);
        long size = Files.size(file(ALICE));
        UUID entry = UUID.randomUUID();
        byte[] record = Journal.frame(Journal.Type.ENTITIES, Journal.entities(entry, DATA_VERSION, 1,
                EntityCodec.encode(List.of(new EntityChange(entry, null, state(entry, "minecraft:boat", 1, "b"))))));
        byte[] torn = java.util.Arrays.copyOf(record, record.length - 7);
        Files.write(file(ALICE), torn, StandardOpenOption.APPEND);
        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(1, loaded.entries().size());
        assertSameRecord(a.record(), loaded.entries().get(0).record());
        close(reopened);
        assertEquals(size, Files.size(file(ALICE)), "cut back to the last whole record");
    }

    /**
     * An entity record whose frame and checksum are whole but whose body does not decode is damaged data: its entry is
     * given up (after the retries, with a copy of the file kept) and the other entries load.
     */
    @Test
    void aDamagedEntityBatchGivesUpOnlyItsEntry() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry good = entry(ALICE, "good", withEntities(sparse(7), 1, 1), 1);
        journal(store, good);
        UUID bad = UUID.randomUUID();
        store.begin(ALICE, bad, 2, StoreTestSupport.WORLD, "bad");
        close(store);
        // A batch claiming one change whose body is junk, then the seal and push of its entry.
        byte[] junk = {0, 1, 2, 3, 4, 5, 6, 7};
        byte[] records = concat(Journal.frame(Journal.Type.ENTITIES, Journal.entities(bad, DATA_VERSION, 1, junk)),
                Journal.frame(Journal.Type.SEAL, Journal.seal(bad, "bad · 0 blocks · 1 entity", 2, 10)),
                Journal.frame(Journal.Type.PUSH, Journal.mark(bad)));
        Files.write(file(ALICE), records, StandardOpenOption.APPEND);
        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(1, loaded.entries().size(), "the damaged entry is left out");
        assertSameRecord(good.record(), loaded.entries().get(0).record());
        assertEquals(List.of(bad), loaded.failed());
        assertTrue(loaded.problems().get(0).contains("damaged"), loaded.problems().toString());
        close(reopened);
    }

    /** Entities recorded by another game data version are not restored: their entry is dropped at load. */
    @Test
    void entitiesOfAnotherDataVersionAreNotRestored() throws IOException {
        HistoryStore store = open(dir);
        UUID entry = UUID.randomUUID(), id = UUID.randomUUID();
        store.begin(ALICE, entry, 3, StoreTestSupport.WORLD, "Paste");
        close(store);
        byte[] body = EntityCodec.encode(List.of(new EntityChange(id, null, state(id, "minecraft:boat", 1, "b"))));
        Files.write(file(ALICE), concat(
                Journal.frame(Journal.Type.ENTITIES, Journal.entities(entry, DATA_VERSION - 1, 1, body)),
                Journal.frame(Journal.Type.SEAL, Journal.seal(entry, "Paste", 3, 10)),
                Journal.frame(Journal.Type.PUSH, Journal.mark(entry))), StandardOpenOption.APPEND);
        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertTrue(loaded.entries().isEmpty());
        assertEquals(List.of(entry), loaded.failed());
        assertTrue(loaded.problems().get(0).contains("data version"), loaded.problems().toString());
        close(reopened);
    }

    @Test
    void theCodecRoundTripsAndRefusesMalformedBatches() throws CorruptDataException {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        List<EntityChange> batch = List.of(new EntityChange(a, null, state(a, "minecraft:item_frame", 1.5, "a")),
                new EntityChange(b, state(b, "minecraft:boat", -3.25, "b"), null),
                new EntityChange(c, null, null));
        assertEquals(batch, EntityCodec.decode(EntityCodec.encode(batch), 3));
        byte[] body = EntityCodec.encode(batch);
        assertThrows(CorruptDataException.class, () -> EntityCodec.decode(body, 2), "the head's count must match");
        assertThrows(CorruptDataException.class, () -> EntityCodec.decode(java.util.Arrays.copyOf(body, body.length - 3), 3));
        assertThrows(CorruptDataException.class, () -> EntityCodec.decode(concat(body, new byte[] {0}), 3));
        assertThrows(IllegalArgumentException.class, () -> EntityCodec.encode(List.of(batch.get(0), batch.get(0))),
                "an entity once per batch");

        // Hand-made raw bodies: flag 0, then the raw layout.
        assertThrows(CorruptDataException.class, () -> EntityCodec.decode(raw(w -> w.varint(1).uuid(a).u8(4)), 1),
                "unknown sides");
        assertThrows(CorruptDataException.class, () -> EntityCodec.decode(raw(w -> w.varint(1).uuid(a).u8(4).uuid(a)), 1),
                "bit 2 means nothing: a record written with it is refused");
        assertThrows(CorruptDataException.class, () -> EntityCodec.decode(raw(w -> w.varint(2).uuid(a).u8(0).uuid(a)
                .u8(0)), 2), "the same entity twice");
        assertThrows(CorruptDataException.class, () -> EntityCodec.decode(raw(w -> w.varint(1).uuid(a).u8(2)
                .utf("Not An Id").i64(0).i64(0).i64(0).varint(0)), 1), "a bad type id");
        assertThrows(CorruptDataException.class, () -> EntityCodec.decode(raw(w -> w.varint(1).uuid(a).u8(2)
                .utf("minecraft:boat").i64(Double.doubleToRawLongBits(Double.NaN)).i64(0).i64(0).varint(0)), 1),
                "a position that is not finite");
        assertThrows(CorruptDataException.class, () -> EntityCodec.decode(raw(w -> w.varint(1).uuid(a).u8(2)
                .utf("minecraft:boat").i64(0).i64(0).i64(0).varint(EntityState.MAX_NBT_BYTES + 1)), 1),
                "an entity larger than any the store keeps");
        assertThrows(CorruptDataException.class, () -> EntityCodec.decode(new byte[] {2}, 0), "unknown body flags");
    }

    @Test
    void largeBatchesAreSplit() {
        List<EntityChange> many = new ArrayList<>();
        byte[] big = new byte[3 << 20];
        for (int i = 0; i < 25; i++) {
            UUID id = UUID.randomUUID();
            many.add(new EntityChange(id, null, new EntityState("minecraft:chest_minecart", i, 0, 0, big)));
        }
        List<List<EntityChange>> batches = EntityCodec.batches(many);
        assertTrue(batches.size() >= 3, batches.size() + " batches");
        List<EntityChange> joined = new ArrayList<>();
        for (List<EntityChange> batch : batches) {
            assertFalse(batch.isEmpty());
            joined.addAll(batch);
            assertTrue(EntityCodec.encode(batch).length > 0, "each batch encodes");
        }
        assertEquals(many, joined);
    }

    private interface Body {
        void write(Bytes.Writer w);
    }

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
