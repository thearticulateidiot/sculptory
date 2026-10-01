package dev.sculptory.core.history.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.core.testing.FakeStateSpace;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Shared fixtures for the history store tests. */
final class StoreTestSupport {
    static final FakeStateSpace STATES = new FakeStateSpace();
    static final int DATA_VERSION = 3955;
    static final HistoryCodec CODEC = HistoryCodec.of(STATES, DATA_VERSION);
    static final String WORLD = "minecraft:overworld";
    static final int STONE = STATES.state("minecraft:stone");
    static final int AIR = STATES.state("minecraft:air");
    static final int DIRT = STATES.state("minecraft:dirt");
    static final int CHEST = STATES.state("minecraft:chest[facing=north]");
    static final int LOG_X = STATES.state("minecraft:oak_log[axis=x]");
    /** Prints what the store logs, so a failing test shows it. */
    static final HistoryStore.Log LOG = new HistoryStore.Log() {
        @Override
        public void info(String message) {
            System.out.println("[store] " + message);
        }

        @Override
        public void warn(String message, Throwable cause) {
            System.out.println("[store WARN] " + message + (cause == null ? "" : " : " + cause));
            if (cause != null && Boolean.getBoolean("store.traces")) cause.printStackTrace(System.out);
        }
    };

    private StoreTestSupport() {}

    static EditRecord record(Consumer<RecordBuilder> body) {
        RecordBuilder builder = new RecordBuilder();
        body.accept(builder);
        return builder.build();
    }

    /** A dense fill of one section from mixed terrain to stone. */
    static EditRecord fill(int sx, int sy, int sz) {
        return record(b -> {
            for (int i = 0; i < SectionBuffer.SIZE; i++) {
                int x = (sx << 4) + SectionBuffer.localX(i), y = (sy << 4) + SectionBuffer.localY(i);
                int z = (sz << 4) + SectionBuffer.localZ(i);
                b.record(x, y, z, (i % 3 == 0) ? DIRT : AIR, null, STONE, null);
            }
        });
    }

    /** A few scattered cells over several sections, one of them a chest with content before and after. */
    static EditRecord sparse(int seed) {
        Random random = new Random(seed);
        return record(b -> {
            for (int n = 0; n < 40; n++) {
                int x = random.nextInt(64) - 32, y = random.nextInt(64) - 16, z = random.nextInt(64) - 32;
                b.record(x, y, z, AIR, null, random.nextBoolean() ? DIRT : LOG_X, null);
            }
            b.record(1, 2, 3, CHEST, tile("before" + seed), CHEST, tile("after" + seed));
        });
    }

    /** Hundreds of cells in one section (the bitmap presence mode). */
    static EditRecord medium() {
        return record(b -> {
            for (int i = 0; i < 1000; i++) b.record(i & 15, (i >> 8) & 15, (i >> 4) & 15, AIR, null, DIRT, null);
        });
    }

    static NbtBytes tile(String text) {
        byte[] bytes = ("nbt:" + text).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new NbtBytes("minecraft:chest", bytes);
    }

    static HistoryEntry entry(UUID player, String label, EditRecord record, long created) {
        return new HistoryEntry(UUID.randomUUID(), player, WORLD, label, record, created);
    }

    /** Journals an entry the way the server does for a finished edit: begin, its sections, seal, push. */
    static void journal(HistoryStore store, HistoryEntry e) {
        store.begin(e.owner(), e.id(), e.createdMillis(), e.world(), e.label());
        sections(store, e);
        store.entities(e.owner(), e.id(), e.record().entities());
        store.seal(e.owner(), e.id(), e.label(), e.createdMillis(), e.record().estimatedBytes());
        store.push(e.owner(), e.id());
    }

    static void sections(HistoryStore store, HistoryEntry e) {
        BlockBuffer before = e.record().before();
        for (long key : before.sortedKeys()) {
            store.section(e.owner(), e.id(), key, before.section(key), e.record().after().section(key));
        }
    }

    static void assertSameRecord(EditRecord expected, EditRecord actual) {
        assertSameBuffer(expected.before(), actual.before());
        assertSameBuffer(expected.after(), actual.after());
        assertEquals(expected.entities(), actual.entities(), "entities");
    }

    static void assertSameBuffer(BlockBuffer expected, BlockBuffer actual) {
        List<Long> keys = new ArrayList<>();
        for (long key : expected.sortedKeys()) {
            if (!expected.section(key).isEmpty()) keys.add(key);
        }
        List<Long> actualKeys = new ArrayList<>();
        for (long key : actual.sortedKeys()) {
            if (!actual.section(key).isEmpty()) actualKeys.add(key);
        }
        assertEquals(keys, actualKeys, "section keys");
        for (long key : keys) assertSameSection(expected.section(key), actual.section(key));
    }

    static void assertSameSection(SectionBuffer expected, SectionBuffer actual) {
        assertEquals(expected.presentCount(), actual.presentCount(), "cells");
        for (int i = 0; i < SectionBuffer.SIZE; i++) {
            assertEquals(expected.get(i), actual.get(i), "state at " + i);
            BlockEntityData a = expected.tile(i), b = actual.tile(i);
            if (a == null || b == null) {
                assertEquals(a == null, b == null, "tile presence at " + i);
            } else {
                assertEquals(a.typeId(), b.typeId());
                assertArrayEquals(a.nbtBytes(), b.nbtBytes(), "tile at " + i);
            }
        }
    }

    /**
     * Polls the store until an event matches, or fails after 10 s. Events polled are kept in {@code seen}; a match is
     * taken out of it, so a later call with the same list still finds the events polled with it.
     */
    @SuppressWarnings("unchecked")
    static <T extends HistoryStore.Event> T await(HistoryStore store, Class<T> type, Predicate<T> test,
                                                  List<HistoryStore.Event> seen) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (java.util.Iterator<HistoryStore.Event> it = seen.iterator(); it.hasNext(); ) {
                HistoryStore.Event event = it.next();
                if (type.isInstance(event) && test.test((T) event)) {
                    it.remove();
                    return (T) event;
                }
            }
            List<HistoryStore.Event> polled = store.poll();
            seen.addAll(polled);
            if (!polled.isEmpty()) continue;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
        fail("no " + type.getSimpleName() + " event; saw " + seen);
        return null;
    }

    static HistoryStore.Loaded load(HistoryStore store, UUID player) {
        store.load(player);
        HistoryStore.Loaded loaded = await(store, HistoryStore.Loaded.class, l -> l.player().equals(player),
                new ArrayList<>());
        assertNotNull(loaded);
        return loaded;
    }

    static HistoryStore open(Path dir) throws IOException {
        return open(dir, StorageIo.SYSTEM, HistoryStore.Settings.DEFAULTS);
    }

    static HistoryStore open(Path dir, StorageIo io, HistoryStore.Settings settings) throws IOException {
        return HistoryStore.open(dir, CODEC, settings, io, LOG);
    }

    static void close(HistoryStore store) {
        assertTrue(store.close(10_000), "the store closed");
    }

    /**
     * A {@link StorageIo} over the real file system whose writes, renames, opens and reads can be made to fail, block
     * or slow down, and which records the largest read it was asked for.
     */
    static final class FaultyIo implements StorageIo {
        /** Bytes that may still be written before writes fail as a full disk (negative: no limit). */
        final AtomicLong writeBudget = new AtomicLong(-1);
        final AtomicBoolean failReplace = new AtomicBoolean();
        final AtomicBoolean failForce = new AtomicBoolean();
        /** Writes to temporary files (a compaction's or a rewrite's copy) fail as a full disk. */
        final AtomicBoolean failTempWrites = new AtomicBoolean();
        /** Listing a folder fails. */
        final AtomicBoolean failList = new AtomicBoolean();
        /** The next this-many listings fail part way, with the iterator's unchecked exception. */
        final AtomicInteger listFailsPartWay = new AtomicInteger();
        /** Copying a file fails as a full disk, after writing part of the copy. */
        final AtomicBoolean failCopy = new AtomicBoolean();
        final AtomicInteger copies = new AtomicInteger();
        final AtomicInteger replaces = new AtomicInteger();
        /** Whether a file of this name exists cannot be told (its attributes cannot be read). */
        volatile String failExists;
        /** A file of this name is said not to exist although it does (a file system that answers wrongly). */
        volatile String existsLies;
        /** Free disk space reported (negative: the real one). */
        final AtomicLong usableSpace = new AtomicLong(-1);
        /** While set, every write waits for it. */
        volatile CountDownLatch gate;
        /** While set, writes to temporary files wait for it. */
        volatile CountDownLatch tempGate;
        /** Opening a file whose name contains this fails (another program holds it). */
        volatile String failOpen;
        /** The next this-many reads fail with an I/O error. */
        final AtomicInteger failReads = new AtomicInteger();
        /** Every write to a journal file sleeps this long (a slow disk). */
        volatile long writeDelayMillis;
        final AtomicInteger writes = new AtomicInteger();
        /** The largest read asked for, in bytes. */
        final AtomicLong largestRead = new AtomicLong();

        private static void await(CountDownLatch latch) throws IOException {
            if (latch == null) return;
            try {
                latch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }

        @Override
        public File open(Path file) throws IOException {
            String held = failOpen;
            if (held != null && file.getFileName().toString().contains(held)) {
                throw new java.nio.file.AccessDeniedException(file.toString(), null, "used by another process");
            }
            File inner = SYSTEM.open(file);
            boolean temp = file.toString().endsWith(".tmp");
            return new File() {
                @Override
                public long size() throws IOException {
                    return inner.size();
                }

                @Override
                public int read(ByteBuffer dst, long position) throws IOException {
                    largestRead.accumulateAndGet(dst.remaining(), Math::max);
                    if (failReads.get() > 0 && failReads.getAndDecrement() > 0) {
                        throw new IOException("The process cannot access the file (read failed)");
                    }
                    return inner.read(dst, position);
                }

                @Override
                public void write(ByteBuffer src, long position) throws IOException {
                    await(gate);
                    if (temp) await(tempGate);
                    long delay = writeDelayMillis;
                    if (delay > 0 && !temp) {
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            throw new IOException(e);
                        }
                    }
                    writes.incrementAndGet();
                    if (failTempWrites.get() && temp) throw new IOException("No space left on device");
                    long budget = writeBudget.get();
                    if (budget >= 0 && src.remaining() > budget) {
                        // A torn write: part of the buffer lands, then the disk is full.
                        ByteBuffer part = src.duplicate();
                        part.limit(part.position() + (int) budget);
                        inner.write(part, position);
                        writeBudget.set(0);
                        throw new IOException("No space left on device");
                    }
                    if (budget >= 0) writeBudget.addAndGet(-src.remaining());
                    inner.write(src, position);
                }

                @Override
                public void truncate(long size) throws IOException {
                    inner.truncate(size);
                }

                @Override
                public void force() throws IOException {
                    if (failForce.get()) throw new IOException("force failed");
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
            String name = file.getFileName().toString();
            String unknown = failExists;
            if (unknown != null && name.equals(unknown)) throw new java.nio.file.AccessDeniedException(file.toString());
            String lie = existsLies;
            if (lie != null && name.equals(lie)) return false;
            return SYSTEM.exists(file);
        }

        @Override
        public long usableSpace(Path dir) throws IOException {
            long free = usableSpace.get();
            return free >= 0 ? free : SYSTEM.usableSpace(dir);
        }

        @Override
        public List<Path> list(Path dir) throws IOException {
            if (failList.get()) throw new IOException("listing failed");
            if (listFailsPartWay.get() > 0 && listFailsPartWay.getAndDecrement() > 0) {
                throw new java.nio.file.DirectoryIteratorException(new IOException("the folder changed while listed"));
            }
            return SYSTEM.list(dir);
        }

        @Override
        public void createDirectories(Path dir) throws IOException {
            SYSTEM.createDirectories(dir);
        }

        @Override
        public void replace(Path source, Path target) throws IOException {
            replaces.incrementAndGet();
            if (failReplace.get()) throw new IOException("rename failed");
            SYSTEM.replace(source, target);
        }

        @Override
        public void delete(Path file) throws IOException {
            SYSTEM.delete(file);
        }

        @Override
        public void copy(Path source, Path target) throws IOException {
            copies.incrementAndGet();
            if (failCopy.get()) {
                java.nio.file.Files.write(target, new byte[] {1, 2, 3}); // part of the copy landed
                throw new IOException("No space left on device");
            }
            SYSTEM.copy(source, target);
        }
    }
}
