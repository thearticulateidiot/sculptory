package dev.sculptory.core.history.store;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.EntityChange;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.CRC32C;

/**
 * Crash-safe storage of every player's undo history: one append-only
 * {@link Journal} file per player, {@code <dir>/<uuid>.bshist}, written by one I/O thread.
 *
 * <p><b>Writing.</b> The server thread journals every history change ({@link #begin}, {@link #section}, {@link #seal},
 * {@link #push}, ...). Each call only queues the record and returns; the I/O thread encodes it (section data through
 * {@link SectionCodec}), appends it with a checksum, and forces the files to disk at most every
 * {@link Settings#syncIntervalMillis}. Records wait in one queue per player (a player's records are written in order;
 * players take turns). The queue is bounded ({@link Settings#maxQueuedBytes} in all, a quarter of it per player,
 * counting the section data a record references). A record that does not fit is dropped and that player's journal is
 * <em>out of sync</em>: their later records are dropped too until the server {@link #rewrite rewrites} the file from
 * its memory ({@link #needsRewrite}). A failed write (a full disk, a permission error) does the same. A rewrite runs in
 * the background, one piece at a time; that player's new records wait for it, nobody else's do. Nothing here blocks the
 * server thread, except {@link #awaitColumn} and {@link #flush}, which wait with a timeout.
 *
 * <p><b>Chunk saves.</b> {@link #awaitColumn} waits until every queued section of one chunk column (and every redo in
 * flight) has reached the operating system, writing those players' records first: the game calls it right before it
 * saves that chunk, so the chunk on disk is never newer than its history.
 *
 * <p><b>Reading.</b> At start the I/O thread scans every file: it checks each record's checksum (a section's in pieces,
 * never holding it whole) and stops at the first record that is cut short or damaged, truncating the file there, after
 * keeping a {@code .damaged} copy of it unless what is cut off holds no record data (a lone frame, zeros). A file whose
 * header is damaged is moved to {@code .corrupt} (copies kept aside get a new name each time; {@code .corrupt} files are
 * never deleted, and of the {@code .damaged} ones the newest {@value #MAX_KEPT_DAMAGED} per player are kept); a file of
 * another format version (or with flags this build does not know) is left alone and that player's history is not saved
 * in this run. A file that cannot be examined or read (locked by another program, an I/O error), or whose damaged part
 * cannot be copied (a full disk: the copy is not even tried without room for it) or moved aside, is left alone and read
 * again later, never replaced or cut, and a folder that cannot be listed is listed again later (no player is taken to
 * have no history). The scan
 * then settles what a crash left open (see {@link #recover}) and reports every player's saved stack without block data
 * ({@link Scanned}).
 * {@link #load} reads a player's entries back from where the scan found them ({@link Loaded}); loads go before other
 * background work, and a read that fails is tried again later rather than dropping anything.
 *
 * <p><b>Compaction.</b> When a file's dead records (evicted entries, replaced sections) outweigh its live ones, it is
 * rewritten in the background: the live records are copied into {@code .compact.tmp}, the records appended meanwhile
 * are copied after them, and the copy is forced and moved over the file atomically. A crash at any point leaves either
 * the old file or the new one; a stray temporary file is deleted at the next start.
 *
 * <p>Methods are called on one thread (the server thread) unless noted.
 */
public final class HistoryStore {
    /** Journal file name extension. */
    public static final String EXTENSION = ".bshist";
    private static final String TEMP = ".tmp";
    /** Temporary files of a compaction and of a rewrite (never the same, so one never opens the other's). */
    private static final String COMPACT_TEMP = ".compact" + TEMP;
    private static final String REWRITE_TEMP = ".rewrite" + TEMP;
    private static final String CORRUPT = ".corrupt";
    private static final String DAMAGED = ".damaged";
    /** The time in the name of a file kept aside ({@code .corrupt}, {@code .damaged}). */
    private static final java.time.format.DateTimeFormatter ASIDE_STAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)
                    .withZone(java.time.ZoneOffset.UTC);
    /** After writing records for this long without a break, one background step runs. */
    private static final long BACKGROUND_TURN_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    /** Records of one player written in a row before the next player's turn. */
    private static final int TURN_ITEMS = 64;
    /** First wait before reading a file again after a read failed; doubled each time, up to {@link #RETRY_MAX_NANOS}. */
    static final long RETRY_FIRST_NANOS = TimeUnit.SECONDS.toNanos(1);
    static final long RETRY_MAX_NANOS = TimeUnit.SECONDS.toNanos(60);
    /** Reads of damaged data tried, 1 s apart, before its entry is given up (a copy of the file is kept). */
    static final int DAMAGED_ATTEMPTS = 3;
    /** A problem that lasts (a file that cannot be read, a folder that cannot be listed) is logged again this often. */
    static final long WARN_EVERY_NANOS = TimeUnit.MINUTES.toNanos(10);
    /**
     * {@code .damaged} copies kept per player. Right after the damaged part a new copy holds has been cut off or given
     * up, the older copies beyond this are deleted; the new one never is. They do not count toward the disk cap; this
     * bounds them instead (each is at most one of the player's files). {@code .corrupt} files (the only copy of a whole
     * history) are never deleted by the server.
     */
    static final int MAX_KEPT_DAMAGED = 3;
    /** Disk space that must stay free beyond a copy's own size, or the copy is not tried (it would fill the disk). */
    static final long COPY_FREE_MARGIN = 128L << 20;

    /** Where the store reports problems. */
    public interface Log {
        Log NONE = new Log() {
            @Override
            public void info(String message) {}

            @Override
            public void warn(String message, Throwable cause) {}
        };

        void info(String message);

        /** @param cause may be null */
        void warn(String message, Throwable cause);
    }

    /**
     * @param maxQueuedBytes records waiting to be written, by estimated size, before a player's record is dropped (a
     *     player may use a quarter of it)
     * @param syncIntervalMillis written data is forced to the device at most this long after it was written
     * @param compactMinGarbage a file is compacted once its dead bytes exceed both this and its live bytes
     * @param maxOpenFiles journal files kept open at once
     * @param stepBytes how much a background task (load, compaction, rewrite) reads or writes before letting records
     *     through
     */
    public record Settings(long maxQueuedBytes, long syncIntervalMillis, long compactMinGarbage, int maxOpenFiles,
                           int stepBytes) {
        public static final Settings DEFAULTS = new Settings(128L << 20, 1000, 64L << 10, 64, 1 << 20);

        public Settings {
            if (maxQueuedBytes < 1 || syncIntervalMillis < 0 || compactMinGarbage < 0 || maxOpenFiles < 1
                    || stepBytes < 1) {
                throw new IllegalArgumentException("Bad history store settings");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ events

    /** Reports from the I/O thread, collected by {@link #poll()}. */
    public sealed interface Event permits Scanned, Loaded, Stored, Failed, Rewritten, Stopped {}

    /**
     * The start-up scan finished: every player's saved stack (players with an empty history, and players whose file
     * could not be read, are left out).
     */
    public record Scanned(List<StoredHistory> histories) implements Event {}

    /**
     * A {@link #load} finished: the player's entries, oldest first, the first {@code applied} undoable. Entries that
     * cannot be restored (a block state the game does not know, another game data version, data still damaged after
     * {@value #DAMAGED_ATTEMPTS} reads) are left out and listed in {@code failed}, with the reasons in
     * {@code problems}. A read that fails is tried again, so a load may take long, but it never reports an entry as
     * failed because the file could not be read.
     */
    public record Loaded(UUID player, List<LoadedEntry> entries, int applied, List<UUID> failed, List<String> problems)
            implements Event {}

    /** An entry's data was written ({@code diskBytes} in its journal). */
    public record Stored(UUID player, UUID entry, long diskBytes) implements Event {}

    /** Records of the player could not be written; their journal is out of sync until it is rewritten. */
    public record Failed(UUID player, String reason) implements Event {}

    /** A {@link #rewrite} finished ({@code ok}) or failed. */
    public record Rewritten(UUID player, boolean ok, String problem) implements Event {}

    /** The store stopped working (its I/O thread failed); nothing more is saved. */
    public record Stopped(String reason) implements Event {}

    /** One entry read back by {@link #load}. */
    public record LoadedEntry(UUID id, String world, String label, long createdMillis, long bytes, long diskBytes,
                              EditRecord record) {}

    /**
     * One entry of a {@link #rewrite}: from {@code record} when given, else copied from the player's journal (an entry
     * the journal does not hold whole is left out).
     */
    public record SnapshotEntry(UUID id, String world, String label, long createdMillis, long bytes,
                                EditRecord record) {
        public SnapshotEntry {
            Objects.requireNonNull(id);
            Objects.requireNonNull(world);
            Objects.requireNonNull(label);
        }
    }

    /**
     * What {@link #awaitColumn} found.
     *
     * @param written every queued record the column needed reached the operating system in time
     * @param unprotected players with changes there whose records cannot be written now (their file is being rewritten,
     *     or cannot be written or read)
     */
    public record ColumnWait(boolean written, Set<UUID> unprotected) {}

    // ------------------------------------------------------------------------------------------------ queue items

    private abstract static class Item {
        final UUID player;
        /** Counted against the queue's bounds (0 for requests that are not records). */
        final long cost;
        long seq;

        Item(UUID player, long cost) {
            this.player = Objects.requireNonNull(player);
            this.cost = cost;
        }
    }

    /** A record whose payload was built on the server thread. */
    private static final class Append extends Item {
        final Journal.Type type;
        final UUID entry;
        final byte[] payload;

        Append(UUID player, Journal.Type type, UUID entry, byte[] payload) {
            super(player, 64 + payload.length);
            this.type = type;
            this.entry = entry;
            this.payload = payload;
        }
    }

    /** A section, encoded on the I/O thread. The buffers are not changed after they are handed over. */
    private static final class SectionAppend extends Item {
        final UUID entry;
        final long key;
        final SectionBuffer before;
        final SectionBuffer after;

        SectionAppend(UUID player, UUID entry, long key, SectionBuffer before, SectionBuffer after) {
            super(player, 64 + (before == null ? 0 : before.estimatedBytes() + after.estimatedBytes()));
            this.entry = entry;
            this.key = key;
            this.before = before;
            this.after = after;
        }
    }

    /** A batch of an entry's entity changes, encoded on the I/O thread. */
    private static final class EntityAppend extends Item {
        final UUID entry;
        final List<EntityChange> changes;
        /** The chunk columns the changes are in ({@link #column}), for {@link #awaitColumn}. */
        final long[] columns;

        EntityAppend(UUID player, UUID entry, List<EntityChange> changes, long[] columns) {
            super(player, 64 + bytesOf(changes));
            this.entry = entry;
            this.changes = changes;
            this.columns = columns;
        }

        private static long bytesOf(List<EntityChange> changes) {
            long bytes = 0;
            for (EntityChange change : changes) bytes += change.estimatedBytes();
            return bytes;
        }
    }

    private static final class LoadRequest extends Item {
        LoadRequest(UUID player) {
            super(player, 0);
        }
    }

    private static final class RewriteRequest extends Item {
        final List<SnapshotEntry> stack;
        final int applied;
        final List<SnapshotEntry> pending;
        final List<SnapshotEntry> open;

        RewriteRequest(UUID player, List<SnapshotEntry> stack, int applied, List<SnapshotEntry> pending,
                       List<SnapshotEntry> open) {
            super(player, 0);
            this.stack = stack;
            this.applied = applied;
            this.pending = pending;
            this.open = open;
        }
    }

    // ------------------------------------------------------------------------------------------------ state

    private final Path dir;
    private final HistoryCodec codec;
    private final Settings settings;
    private final StorageIo io;
    private final Log log;
    private final Thread thread;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wake = lock.newCondition();
    private final Condition progress = lock.newCondition();
    // Guarded by lock.
    /** Queued items per player, in order; only players with items. */
    private final Map<UUID, ArrayDeque<Item>> queued = new HashMap<>();
    /** Players with items, in the order they take turns. */
    private final ArrayDeque<UUID> turn = new ArrayDeque<>();
    private long queuedBytes;
    private final Map<UUID, Long> playerBytes = new HashMap<>();
    /** Queued sections by chunk column: player → sequence number of their last one there. */
    private final Map<Long, Map<UUID, Long>> columns = new HashMap<>();
    /** Queued redo-in-flight marks: player → sequence number. */
    private final Map<UUID, Long> redoBegins = new HashMap<>();
    /** Players whose file is being rewritten: their items wait. */
    private final Set<UUID> blocked = new HashSet<>();
    /** Players whose file cannot be written or read now (their records are dropped). */
    private final Set<UUID> failing = new HashSet<>();
    /** Chunk saves waiting: player → sequence number to write first. */
    private final Map<UUID, Long> urgent = new HashMap<>();
    /** Whose turn it is, and how many more of their items it covers. */
    private UUID current;
    private int currentLeft;
    /** Per player: sequence number of their last item handled. */
    private final Map<UUID, Long> done = new HashMap<>();
    private Item inFlight;
    private long enqueued;
    private long forceRequests;
    private long forcesDone;
    private boolean closing;
    private volatile boolean dead;
    private volatile boolean stopped;
    /** {@link Event}s, and the I/O thread's internal {@link FailedAt} and {@link RewrittenAt} reports. */
    private final ConcurrentLinkedQueue<Object> events = new ConcurrentLinkedQueue<>();

    // Server thread.
    /** Players whose later records are being dropped until a rewrite. */
    private final Set<UUID> outOfSync = new HashSet<>();
    /** Players with a rewrite queued or running: the sequence number of the request. */
    private final Map<UUID, Long> rewriteQueued = new HashMap<>();
    /** Players whose file this build cannot write (another format version). */
    private final Set<UUID> lockedPlayers = new HashSet<>();
    /** Records dropped per player while out of sync (reported when they are saved again). */
    private final Map<UUID, Long> dropped = new HashMap<>();

    // I/O thread.
    private final Map<UUID, PlayerFile> files = new HashMap<>();
    private final LinkedHashMap<UUID, PlayerFile> open = new LinkedHashMap<>(16, 0.75f, true);
    private final Set<PlayerFile> dirty = new HashSet<>();
    private final Set<UUID> locked = new HashSet<>();
    private final ArrayDeque<Task> background = new ArrayDeque<>();
    /** No background task is queued or running (read by {@link #awaitIdle}). */
    private volatile boolean backgroundIdle;
    /** Why the start-up scan cannot list the history folder, while it cannot (read by {@link #status()}). */
    private volatile String listingProblem;
    private long lastForceNanos = System.nanoTime();
    private List<Path> unscanned;

    private HistoryStore(Path dir, HistoryCodec codec, Settings settings, StorageIo io, Log log) {
        this.dir = dir;
        this.codec = codec;
        this.settings = settings;
        this.io = io;
        this.log = log;
        this.thread = new Thread(this::run, "Sculptory history");
        this.thread.setDaemon(true);
    }

    /**
     * Opens (creating) the store in {@code dir} and starts its I/O thread, which scans the existing files first.
     *
     * @throws IOException if the directory cannot be created
     */
    public static HistoryStore open(Path dir, HistoryCodec codec, Settings settings, StorageIo io, Log log)
            throws IOException {
        Objects.requireNonNull(dir);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(settings);
        Objects.requireNonNull(io);
        io.createDirectories(dir);
        HistoryStore store = new HistoryStore(dir, codec, settings, io, log == null ? Log.NONE : log);
        store.background.add(store.new ScanTask());
        store.thread.start();
        return store;
    }

    public Path dir() {
        return dir;
    }

    /** Whether records are still accepted (not closed, and the I/O thread has not failed). */
    public boolean usable() {
        return !dead && !stopped;
    }

    /** The key {@link #awaitColumn} takes for chunk column (cx, cz). */
    public static long column(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static long columnOf(long sectionKey) {
        return column(BlockBuffer.keyX(sectionKey), BlockBuffer.keyZ(sectionKey));
    }

    // ------------------------------------------------------------------------------------------------ server API

    /** An entry's data begins ({@code label} is the base label, used if the entry is interrupted). */
    public void begin(UUID player, UUID entry, long createdMillis, String world, String label) {
        offer(new Append(player, Journal.Type.BEGIN, entry, Journal.begin(entry, createdMillis, world, label)));
    }

    /**
     * One section of an entry, replacing any earlier one of the same key. {@code before} and {@code after} hold the
     * same cells, or are both {@code null} for a section with nothing kept. They must not change afterwards.
     */
    public void section(UUID player, UUID entry, long key, SectionBuffer before, SectionBuffer after) {
        if ((before == null) != (after == null)) throw new IllegalArgumentException("Unpaired section");
        offer(new SectionAppend(player, entry, key, before, after));
    }

    /**
     * A batch of an entry's entity changes (a later change of an entity replaces an earlier one, and a change with
     * neither side cancels it). The list must not change afterwards. A chunk save of a column one of them is in waits
     * for it ({@link #awaitColumn}).
     */
    public void entities(UUID player, UUID entry, List<EntityChange> changes) {
        if (changes.isEmpty()) return;
        java.util.TreeSet<Long> columns = new java.util.TreeSet<>();
        for (EntityChange change : changes) {
            if (change.before() != null) columns.add(column(change.before().chunkX(), change.before().chunkZ()));
            if (change.after() != null) columns.add(column(change.after().chunkX(), change.after().chunkZ()));
        }
        offer(new EntityAppend(player, entry, List.copyOf(changes),
                columns.stream().mapToLong(Long::longValue).toArray()));
    }

    /** The entry's data is complete: its final label, creation time and estimated heap bytes. */
    public void seal(UUID player, UUID entry, String label, long createdMillis, long bytes) {
        offer(new Append(player, Journal.Type.SEAL, entry, Journal.seal(entry, label, createdMillis, bytes)));
    }

    public void push(UUID player, UUID entry) {
        mark(player, Journal.Type.PUSH, entry);
    }

    public void undone(UUID player, UUID entry) {
        mark(player, Journal.Type.UNDONE, entry);
    }

    public void redone(UUID player, UUID entry) {
        mark(player, Journal.Type.REDONE, entry);
    }

    /** A redo job of the entry was admitted; if the server stops before it ends, recovery treats it as redone. */
    public void redoBegin(UUID player, UUID entry) {
        mark(player, Journal.Type.REDO_BEGIN, entry);
    }

    /** The redo job ended without applying anything. */
    public void redoAbort(UUID player, UUID entry) {
        mark(player, Journal.Type.REDO_ABORT, entry);
    }

    public void evict(UUID player, UUID entry) {
        mark(player, Journal.Type.EVICT, entry);
    }

    /** A begun entry that will never be pushed. */
    public void abort(UUID player, UUID entry) {
        mark(player, Journal.Type.ABORT, entry);
    }

    private void mark(UUID player, Journal.Type type, UUID entry) {
        offer(new Append(player, type, entry, Journal.mark(entry)));
    }

    /** Reads the player's saved history back; the result comes as a {@link Loaded} event. */
    public void load(UUID player) {
        Objects.requireNonNull(player);
        if (!enqueue(new LoadRequest(player), true)) {
            events.add(new Loaded(player, List.of(), 0, List.of(), List.of("history is not being saved")));
        }
    }

    /** Whether records of the player are accepted now (false while out of sync or when their file is locked). */
    public boolean accepting(UUID player) {
        return usable() && !outOfSync.contains(player) && !lockedPlayers.contains(player);
    }

    /** Players whose journal is out of sync and has no rewrite queued: {@link #rewrite} them from memory. */
    public Set<UUID> needsRewrite() {
        Set<UUID> players = new HashSet<>();
        for (UUID player : outOfSync) {
            if (!rewriteQueued.containsKey(player) && !lockedPlayers.contains(player)) players.add(player);
        }
        return players;
    }

    /** Whether the player's journal missed records that no queued rewrite covers yet. */
    public boolean outOfSync(UUID player) {
        return outOfSync.contains(player);
    }

    /**
     * The server found the player's journal wrong where its memory is right (a step it holds whole read back damaged):
     * their records are dropped from now on, as for a failed write, until the server {@link #rewrite rewrites} the
     * journal from memory ({@link #needsRewrite} lists them).
     */
    public void markOutOfSync(UUID player) {
        Objects.requireNonNull(player);
        if (!lockedPlayers.contains(player)) outOfSync.add(player);
    }

    /** Whether a rewrite is queued or running (the server starts one at a time). */
    public boolean rewriting() {
        return !rewriteQueued.isEmpty();
    }

    /**
     * Replaces the player's journal with {@code stack} (oldest first, the first {@code applied} undoable),
     * {@code pending} (sealed entries not pushed yet) and {@code open} (records still being built: begun, not sealed),
     * then accepts their records again. The records must not change afterwards. The rewrite runs in the background, in
     * pieces; the player's later records wait for it (nobody else's do). The result comes as a {@link Rewritten} event.
     * The snapshot must hold everything the journal should keep: records of entries it leaves out are lost.
     */
    public void rewrite(UUID player, List<SnapshotEntry> stack, int applied, List<SnapshotEntry> pending,
                        List<SnapshotEntry> open) {
        Objects.requireNonNull(player);
        if (applied < 0 || applied > stack.size()) throw new IllegalArgumentException("applied " + applied);
        if (lockedPlayers.contains(player)) return;
        RewriteRequest request = new RewriteRequest(player, List.copyOf(stack), applied, List.copyOf(pending),
                List.copyOf(open));
        if (enqueue(request, true)) {
            outOfSync.remove(player);
            rewriteQueued.put(player, request.seq);
        }
    }

    /**
     * Collects the I/O thread's reports and updates which players are out of sync. Server thread.
     */
    public List<Event> poll() {
        List<Event> out = new ArrayList<>();
        for (Object event; (event = events.poll()) != null; ) {
            switch (event) {
                case FailedAt failed -> {
                    Long queued = rewriteQueued.get(failed.player);
                    // A rewrite queued after the failed record replaces it anyway.
                    if (queued == null || queued < failed.seq) outOfSync.add(failed.player);
                    if (failed.permanent) lockedPlayers.add(failed.player);
                    out.add(new Failed(failed.player, failed.reason));
                }
                case RewrittenAt rewritten -> {
                    Long queued = rewriteQueued.get(rewritten.player);
                    if (queued != null && queued == rewritten.seq) rewriteQueued.remove(rewritten.player);
                    if (!rewritten.ok) {
                        outOfSync.add(rewritten.player);
                    } else {
                        Long lost = dropped.remove(rewritten.player);
                        if (lost != null) {
                            log.info("Sculptory: " + rewritten.player + "'s undo history is saved again ("
                                    + lost + " record(s) were not written meanwhile and are covered by the rewrite)");
                        }
                    }
                    out.add(new Rewritten(rewritten.player, rewritten.ok, rewritten.problem));
                }
                case Event plain -> out.add(plain);
                default -> throw new IllegalStateException("Unknown store event " + event);
            }
        }
        return out;
    }

    /** Sequence number of the last item accepted. */
    public long enqueued() {
        lock.lock();
        try {
            return enqueued;
        } finally {
            lock.unlock();
        }
    }

    /** Whether items were accepted that the I/O thread has not handled yet. Any thread. */
    public boolean hasUnwritten() {
        lock.lock();
        try {
            return lowestPending() != Long.MAX_VALUE;
        } finally {
            lock.unlock();
        }
    }

    /** Records queued and not yet written, by estimated size. */
    public long queuedBytes() {
        lock.lock();
        try {
            return queuedBytes;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits until every item up to {@code seq} has been handled (written to the operating system, or dropped), or the
     * timeout passes.
     */
    public boolean awaitWritten(long seq, long timeoutNanos) {
        long deadline = System.nanoTime() + Math.max(0, timeoutNanos);
        lock.lock();
        try {
            while (lowestPending() <= seq && !dead && !stopped) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                progress.awaitNanos(left);
            }
            return lowestPending() > seq;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The game is about to save chunk column {@code column} ({@link #column}): writes every queued section of it, of any
     * player, and every queued redo-in-flight mark (and each such player's records queued before them) ahead of other
     * work, and waits until they have reached the operating system or the timeout passes. Players whose file is being
     * rewritten, or cannot be written or read, are not waited for and are reported instead.
     */
    public ColumnWait awaitColumn(long column, long timeoutNanos) {
        long deadline = System.nanoTime() + Math.max(0, timeoutNanos);
        lock.lock();
        try {
            Map<UUID, Long> targets = new HashMap<>();
            Map<UUID, Long> here = columns.get(column);
            if (here != null) targets.putAll(here);
            for (Map.Entry<UUID, Long> redo : redoBegins.entrySet()) {
                targets.merge(redo.getKey(), redo.getValue(), Math::max);
            }
            Set<UUID> unprotected = new HashSet<>();
            for (Iterator<Map.Entry<UUID, Long>> it = targets.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, Long> target = it.next();
                UUID player = target.getKey();
                if (blocked.contains(player) || failing.contains(player)) {
                    unprotected.add(player);
                    it.remove();
                } else if (done.getOrDefault(player, 0L) >= target.getValue()) {
                    it.remove();
                }
            }
            if (targets.isEmpty()) return new ColumnWait(true, Set.copyOf(unprotected));
            for (Map.Entry<UUID, Long> target : targets.entrySet()) {
                urgent.merge(target.getKey(), target.getValue(), Math::max);
            }
            wake.signalAll();
            while (!dead && !stopped && !satisfied(targets)) {
                long left = deadline - System.nanoTime();
                if (left <= 0) break;
                progress.awaitNanos(left);
            }
            return new ColumnWait(satisfied(targets), Set.copyOf(unprotected));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ColumnWait(false, Set.of());
        } finally {
            lock.unlock();
        }
    }

    private boolean satisfied(Map<UUID, Long> targets) {
        for (Map.Entry<UUID, Long> target : targets.entrySet()) {
            if (done.getOrDefault(target.getKey(), 0L) < target.getValue() && !failing.contains(target.getKey())) {
                return false;
            }
        }
        return true;
    }

    /** Writes every record queued so far and forces the files to the device; false on timeout. */
    public boolean flush(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        lock.lock();
        try {
            long target = enqueued;
            while (lowestPending() <= target && !dead && !stopped) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                progress.awaitNanos(left);
            }
            long wanted = ++forceRequests;
            wake.signalAll();
            while (forcesDone < wanted && !dead && !stopped) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return false;
                progress.awaitNanos(left);
            }
            return forcesDone >= wanted;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Writes and forces everything queued, then stops the I/O thread (a compaction, rewrite or load in progress is
     * abandoned; the files stay as they were).
     *
     * @return false when the thread did not finish within the timeout (it is left to finish as a daemon)
     */
    public boolean close(long timeoutMillis) {
        lock.lock();
        try {
            closing = true;
            wake.signalAll();
        } finally {
            lock.unlock();
        }
        try {
            thread.join(Math.max(1, timeoutMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    /** Journal files open now (read once the store is idle); for tests. */
    int openFileCount() {
        return open.size();
    }

    /** Waits until the queue is written and no background work (scan, load, compaction) is left; for tests. */
    boolean awaitIdle(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (backgroundIdle && !hasUnwritten()) return true;
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** One line for logs and {@code /sculptory history}: where, and what is wrong now (a folder that cannot be listed). */
    public String status() {
        if (dead) return "not saving (the history writer failed; see the log)";
        if (stopped) return "closed";
        long queued = queuedBytes();
        String listing = listingProblem;
        return "saving to " + dir + (outOfSync.isEmpty() ? "" : ", " + outOfSync.size() + " player(s) out of sync")
                + (queued > 0 ? ", " + (queued >> 10) + " KiB waiting" : "") + (listing == null ? "" : "; " + listing);
    }

    private void offer(Item item) {
        if (!accepting(item.player)) {
            if (outOfSync.contains(item.player) && dropped.merge(item.player, 1L, Long::sum) == 1) {
                log.warn("Sculptory: " + item.player + "'s undo history is behind on disk; its records are kept "
                        + "in memory and written again once it can be saved", null);
            }
            return;
        }
        enqueue(item, false);
    }

    /**
     * Queues an item. A record over the queue's budget (in all, or a quarter of it for one player) is dropped and puts
     * its player out of sync, unless nothing of that player (for the per-player share) or nobody's (in all) is queued.
     */
    private boolean enqueue(Item item, boolean always) {
        lock.lock();
        try {
            if (closing || dead) return false;
            if (!always) {
                long mine = playerBytes.getOrDefault(item.player, 0L);
                boolean overMine = mine > 0 && mine + item.cost > settings.maxQueuedBytes() / 4;
                boolean overAll = queuedBytes > 0 && queuedBytes + item.cost > settings.maxQueuedBytes();
                if (overMine || overAll) {
                    outOfSync.add(item.player);
                    dropped.merge(item.player, 1L, Long::sum);
                    events.add(new Failed(item.player, "the history write queue is full (the disk is too slow)"));
                    return false;
                }
            }
            item.seq = ++enqueued;
            ArrayDeque<Item> mine = queued.get(item.player);
            if (mine == null) {
                mine = new ArrayDeque<>();
                queued.put(item.player, mine);
                turn.addLast(item.player);
            }
            mine.addLast(item);
            queuedBytes += item.cost;
            playerBytes.merge(item.player, item.cost, Long::sum);
            if (item instanceof SectionAppend section) {
                columns.computeIfAbsent(columnOf(section.key), c -> new HashMap<>()).put(item.player, item.seq);
            } else if (item instanceof EntityAppend batch) {
                for (long column : batch.columns) {
                    columns.computeIfAbsent(column, c -> new HashMap<>()).put(item.player, item.seq);
                }
            } else if (item instanceof Append append && append.type == Journal.Type.REDO_BEGIN) {
                redoBegins.put(item.player, item.seq);
            }
            wake.signalAll();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** The smallest sequence number queued or being handled ({@code Long.MAX_VALUE} when none). Under the lock. */
    private long lowestPending() {
        long lowest = inFlight == null ? Long.MAX_VALUE : inFlight.seq;
        for (ArrayDeque<Item> items : queued.values()) {
            if (!items.isEmpty()) lowest = Math.min(lowest, items.peekFirst().seq);
        }
        return lowest;
    }

    // ------------------------------------------------------------------------------------------------ I/O thread

    /** A failure report with the sequence number of the record that failed (resolved by {@link #poll()}). */
    private record FailedAt(UUID player, long seq, String reason, boolean permanent) {}

    private record RewrittenAt(UUID player, long seq, boolean ok, String problem) {}

    /** One player's journal file, as the I/O thread knows it. */
    private final class PlayerFile {
        final UUID player;
        final Path path;
        JournalState state;
        /** Bytes of valid records (the file is truncated to this). */
        long length;
        boolean exists;
        /** The header's flags ({@link Journal#FLAG_ENTITIES} once it holds entity records). */
        int flags;
        StorageIo.File file;
        /** Records can no longer be appended (a write failed); cleared by a rewrite. */
        boolean broken;
        /** The file could not be read: nothing is written to it; it is read again at {@link #retryAt}. */
        boolean unreadable;
        long retryAt;
        long retryWait;
        /** When a read failure of this file was last logged (warned again every {@link #WARN_EVERY_NANOS}). */
        long warnedAt;
        /** A rewrite of the file is running. */
        boolean rewriting;
        /** Bumped whenever the file is replaced or deleted, so background tasks over it stop or start over. */
        int generation;
        boolean compacting;
        long compactRetryNanos;
        /** Entries whose data cannot be saved (too large for a record): their later records are skipped. */
        final Set<UUID> unpersistable = new HashSet<>();

        PlayerFile(UUID player, JournalState state, long length, boolean exists) {
            this.player = player;
            this.path = pathOf(player);
            this.state = state;
            this.length = length;
            this.exists = exists;
        }
    }

    private Path pathOf(UUID player) {
        return dir.resolve(player + EXTENSION);
    }

    private void run() {
        try {
            loop();
        } catch (Throwable t) {
            dead = true;
            log.warn("Sculptory: the history writer stopped; undo history is no longer saved to disk", t);
            events.add(new Stopped(String.valueOf(t)));
            closeAll();
        } finally {
            stopped = true;
            lock.lock();
            try {
                progress.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    private void loop() throws InterruptedException {
        long busySince = System.nanoTime();
        while (true) {
            Item item;
            boolean closeNow = false;
            boolean forceNow = false;
            lock.lock();
            try {
                while (true) {
                    item = take();
                    if (item != null || closing) break;
                    if (forceRequests > forcesDone || forceDue() || readyTask() != null) break;
                    long wait = nextWakeNanos();
                    if (wait > 0) {
                        wake.awaitNanos(wait);
                    } else {
                        wake.await();
                    }
                }
                if (item == null && closing) closeNow = true;
                if (item == null && forceRequests > forcesDone) forceNow = true;
            } finally {
                lock.unlock();
            }
            if (item != null) {
                try {
                    process(item);
                } catch (RuntimeException e) {
                    log.warn("Sculptory: a history record could not be written", e);
                    PlayerFile pf = files.get(item.player);
                    if (pf != null) fail(pf, item.seq, e);
                }
                handled(item);
                // Records come first, but a steady stream of them must not starve loads and compactions.
                if (System.nanoTime() - busySince > BACKGROUND_TURN_NANOS) {
                    stepBackground();
                    busySince = System.nanoTime();
                }
                continue;
            }
            busySince = System.nanoTime();
            if (closeNow) {
                // Rewrites queued before the close (a server stop saves out-of-sync histories) are finished; loads,
                // compactions and the scan are given up.
                Task rewrite = null;
                for (Task task : background) {
                    if (task instanceof RewriteTask) rewrite = task;
                }
                if (rewrite != null) {
                    if (rewrite.step()) background.remove(rewrite);
                    continue;
                }
                abandonBackground();
                forceDirty();
                closeAll();
                return;
            }
            if (forceNow) {
                long wanted;
                lock.lock();
                try {
                    wanted = forceRequests;
                } finally {
                    lock.unlock();
                }
                forceDirty();
                lock.lock();
                try {
                    forcesDone = Math.max(forcesDone, wanted);
                    progress.signalAll();
                } finally {
                    lock.unlock();
                }
                continue;
            }
            if (forceDue()) {
                forceDirty();
                continue;
            }
            stepBackground();
        }
    }

    /**
     * The next item to handle, or null: an item a chunk save waits for first, else the next player's turn. Players
     * whose file is being rewritten are skipped (their items wait for it). Under the lock.
     */
    private Item take() {
        Item urgentItem = takeUrgent();
        if (urgentItem != null) return urgentItem;
        // A turn covers a run of the player's records (one file stays open and warm), then the next player's turn.
        if (current != null && currentLeft > 0 && !blocked.contains(current)) {
            ArrayDeque<Item> items = queued.get(current);
            if (items != null && !items.isEmpty()) {
                currentLeft--;
                Item item = items.pollFirst();
                if (items.isEmpty()) {
                    queued.remove(current);
                    turn.remove(current);
                }
                return taken(item);
            }
        }
        for (int n = turn.size(); n > 0; n--) {
            UUID player = turn.pollFirst();
            ArrayDeque<Item> items = queued.get(player);
            if (items == null || items.isEmpty()) {
                queued.remove(player);
                continue;
            }
            if (blocked.contains(player)) {
                turn.addLast(player);
                continue;
            }
            Item item = items.pollFirst();
            if (items.isEmpty()) {
                queued.remove(player);
            } else {
                turn.addLast(player);
            }
            current = player;
            currentLeft = TURN_ITEMS - 1;
            return taken(item);
        }
        return null;
    }

    private Item taken(Item item) {
        inFlight = item;
        queuedBytes -= item.cost;
        long left = playerBytes.getOrDefault(item.player, 0L) - item.cost;
        if (left <= 0) {
            playerBytes.remove(item.player);
        } else {
            playerBytes.put(item.player, left);
        }
        return item;
    }

    /** An item was handled: marks it done for its player and clears what waited for it. */
    private void handled(Item item) {
        lock.lock();
        try {
            inFlight = null;
            done.put(item.player, item.seq);
            if (item instanceof SectionAppend section) {
                handledColumn(columnOf(section.key), item);
            } else if (item instanceof EntityAppend batch) {
                for (long column : batch.columns) handledColumn(column, item);
            }
            Long redo = redoBegins.get(item.player);
            if (redo != null && redo <= item.seq) redoBegins.remove(item.player);
            Long target = urgent.get(item.player);
            if (target != null && target <= item.seq) urgent.remove(item.player);
            progress.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** A queued record of column {@code column} was handled: nothing more of its player waits there unless newer. */
    private void handledColumn(long column, Item item) {
        Map<UUID, Long> here = columns.get(column);
        if (here != null && here.get(item.player) != null && here.get(item.player) <= item.seq) {
            here.remove(item.player);
            if (here.isEmpty()) columns.remove(column);
        }
    }

    private void setBlocked(UUID player, boolean on) {
        lock.lock();
        try {
            if (on) {
                blocked.add(player);
            } else {
                blocked.remove(player);
            }
            wake.signalAll();
            progress.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void setFailing(UUID player, boolean on) {
        lock.lock();
        try {
            if (on) {
                failing.add(player);
            } else {
                failing.remove(player);
            }
            progress.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private boolean forceDue() {
        return !dirty.isEmpty()
                && System.nanoTime() - lastForceNanos >= TimeUnit.MILLISECONDS.toNanos(settings.syncIntervalMillis());
    }

    /** How long the thread may sleep: until the next force or the next background task is due (-1: no limit). */
    private long nextWakeNanos() {
        long now = System.nanoTime();
        long wait = -1;
        if (!dirty.isEmpty()) {
            wait = Math.max(1, lastForceNanos + TimeUnit.MILLISECONDS.toNanos(settings.syncIntervalMillis()) - now);
        }
        for (Task task : background) {
            long until = Math.max(1, task.readyAt - now);
            wait = wait < 0 ? until : Math.min(wait, until);
        }
        return wait;
    }

    /** Forces written files to the device; a chunk save waiting takes its turn between two files. */
    private void forceDirty() {
        List<PlayerFile> files = new ArrayList<>(dirty);
        dirty.clear();
        for (PlayerFile pf : files) {
            if (pf.file != null) {
                try {
                    pf.file.force();
                } catch (IOException e) {
                    log.warn("Sculptory: forcing the history of " + pf.player + " to disk failed", e);
                }
            }
            writeUrgent();
        }
        lastForceNanos = System.nanoTime();
    }

    /** Handles the items chunk saves are waiting for (between the pieces of other work). */
    private void writeUrgent() {
        while (true) {
            Item item;
            lock.lock();
            try {
                if (urgent.isEmpty()) return;
                item = takeUrgent();
            } finally {
                lock.unlock();
            }
            if (item == null) return;
            try {
                process(item);
            } catch (RuntimeException e) {
                log.warn("Sculptory: a history record could not be written", e);
                PlayerFile pf = files.get(item.player);
                if (pf != null) fail(pf, item.seq, e);
            }
            handled(item);
        }
    }

    /** The first item a chunk save waits for (its player's oldest), or null. Under the lock. */
    private Item takeUrgent() {
        for (Map.Entry<UUID, Long> target : urgent.entrySet()) {
            UUID player = target.getKey();
            if (blocked.contains(player)) continue;
            ArrayDeque<Item> items = queued.get(player);
            if (items == null || items.isEmpty() || items.peekFirst().seq > target.getValue()) continue;
            Item item = items.pollFirst();
            if (items.isEmpty()) {
                queued.remove(player);
                turn.remove(player);
            }
            return taken(item);
        }
        return null;
    }

    private void closeAll() {
        for (PlayerFile pf : files.values()) closeFile(pf);
        open.clear();
    }

    private void closeFile(PlayerFile pf) {
        if (pf.file == null) return;
        try {
            pf.file.close();
        } catch (IOException e) {
            log.warn("Sculptory: closing the history file of " + pf.player + " failed", e);
        }
        pf.file = null;
        open.remove(pf.player);
    }

    /**
     * Opens the player's file for appending, creating it with its header when it does not exist. A file found holding
     * data where none was expected is never written over: it is left as it is and read again later, as a file that
     * cannot be read (and the write fails).
     */
    private StorageIo.File ensureOpen(PlayerFile pf) throws IOException {
        if (pf.file != null) {
            open.get(pf.player); // access order
            return pf.file;
        }
        StorageIo.File file = io.open(pf.path);
        if (!pf.exists) {
            boolean holdsData;
            try {
                holdsData = file.size() > 0;
            } catch (IOException e) {
                file.close();
                throw e;
            }
            if (holdsData) {
                file.close();
                IOException found = new IOException("a history file holding data was found where none was");
                unreadable(pf.player, pf.path, pf, found);
                throw found;
            }
            try {
                file.truncate(0);
                file.write(ByteBuffer.wrap(Journal.header(pf.player)), 0);
            } catch (IOException e) {
                file.close();
                throw e;
            }
            pf.exists = true;
            pf.length = Journal.HEADER_BYTES;
            pf.flags = 0;
            pf.state = new JournalState(pf.player);
        }
        pf.file = file;
        open.put(pf.player, pf);
        trimOpen(pf);
        return file;
    }

    /**
     * Closes the least recently used files beyond {@link Settings#maxOpenFiles()} (forcing them first), never
     * {@code keep}.
     */
    private void trimOpen(PlayerFile keep) {
        while (open.size() > settings.maxOpenFiles()) {
            Iterator<PlayerFile> eldest = open.values().iterator();
            PlayerFile victim = eldest.next();
            if (victim == keep) break;
            if (dirty.contains(victim)) {
                try {
                    victim.file.force();
                } catch (IOException e) {
                    log.warn("Sculptory: forcing the history of " + victim.player + " to disk failed", e);
                }
                dirty.remove(victim);
            }
            closeFile(victim);
        }
    }

    /** The player's file, scanned first if the start-up scan has not reached it; null when it is locked. */
    private PlayerFile file(UUID player) {
        PlayerFile pf = files.get(player);
        if (pf != null || locked.contains(player)) return pf;
        pf = scanFile(player, pathOf(player));
        if (unscanned != null) unscanned.remove(pathOf(player));
        return pf;
    }

    /** Whether records can be appended to the player's file now. */
    private static boolean writable(PlayerFile pf) {
        return pf != null && !pf.broken && !pf.unreadable;
    }

    private void process(Item item) {
        switch (item) {
            case Append append -> {
                PlayerFile pf = file(append.player);
                if (!writable(pf) || pf.unpersistable.contains(append.entry)) return;
                Journal.Op op = append(pf, append.type, append.payload, item.seq);
                if (op != null) afterWrite(pf, op);
            }
            case SectionAppend section -> {
                PlayerFile pf = file(section.player);
                if (!writable(pf) || pf.unpersistable.contains(section.entry)) return;
                byte[] payload;
                try {
                    byte[] body = SectionCodec.encode(section.before, section.after, codec);
                    int cells = section.before == null ? 0 : section.before.presentCount();
                    payload = Journal.section(section.entry, section.key, codec.dataVersion(), cells, body);
                    if (!Journal.Type.SECTION.fits(payload.length)) {
                        throw new IllegalArgumentException("section of " + payload.length + " bytes");
                    }
                } catch (IllegalArgumentException | IllegalStateException e) {
                    unpersistable(pf, section.entry, e);
                    return;
                }
                Journal.Op op = append(pf, Journal.Type.SECTION, payload, item.seq);
                if (op != null) afterWrite(pf, op);
            }
            case EntityAppend batch -> {
                PlayerFile pf = file(batch.player);
                if (!writable(pf) || pf.unpersistable.contains(batch.entry)) return;
                List<byte[]> payloads = new ArrayList<>();
                try {
                    for (List<EntityChange> part : EntityCodec.batches(batch.changes)) {
                        byte[] payload = Journal.entities(batch.entry, codec.dataVersion(), part.size(),
                                EntityCodec.encode(part));
                        if (!Journal.Type.ENTITIES.fits(payload.length)) {
                            throw new IllegalArgumentException("entity batch of " + payload.length + " bytes");
                        }
                        payloads.add(payload);
                    }
                } catch (IllegalArgumentException | IllegalStateException e) {
                    unpersistable(pf, batch.entry, e);
                    return;
                }
                for (byte[] payload : payloads) {
                    Journal.Op op = append(pf, Journal.Type.ENTITIES, payload, item.seq);
                    if (op == null) return;
                    afterWrite(pf, op);
                }
            }
            case LoadRequest request -> {
                PlayerFile pf = file(request.player);
                if (pf == null) {
                    events.add(new Loaded(request.player, List.of(), 0, List.of(),
                            List.of("its history file was written by another version of Sculptory")));
                } else {
                    schedule(new LoadTask(request.player));
                }
            }
            case RewriteRequest request -> startRewrite(request);
            default -> throw new IllegalStateException("Unknown item " + item);
        }
    }

    /**
     * An entry whose data cannot be saved (a section too large for a record): it is aborted in the journal (it stays in
     * memory, and is gone after a restart) and its later records are skipped.
     */
    private void unpersistable(PlayerFile pf, UUID entry, RuntimeException cause) {
        if (!pf.unpersistable.add(entry)) return;
        log.warn("Sculptory: a step of " + pf.player + "'s undo history is too large to save (" + cause.getMessage()
                + "); it can be undone until the server stops", null);
        JournalState.Entry known = pf.state.entries.get(entry);
        if (known != null && !known.pushed) append(pf, Journal.Type.ABORT, Journal.mark(entry), 0);
    }

    /**
     * Appends one record to the player's file and applies it to the file's state.
     *
     * @return the record's operation, or null when the write failed (the player is then out of sync)
     */
    private Journal.Op append(PlayerFile pf, Journal.Type type, byte[] payload, long seq) {
        byte[] record = Journal.frame(type, payload);
        Journal.Op op;
        try {
            op = Journal.decode(type, record, payload.length);
        } catch (CorruptDataException e) {
            throw new IllegalStateException("A record this store made does not decode", e);
        }
        long at;
        try {
            StorageIo.File file = ensureOpen(pf);
            if (type == Journal.Type.ENTITIES && (pf.flags & Journal.FLAG_ENTITIES) == 0) {
                // A build that does not know entity records must leave this file alone rather than cut it off at the
                // first one: the header says so, on the device, before the record is written (once per file).
                int flags = pf.flags | Journal.FLAG_ENTITIES;
                file.write(ByteBuffer.wrap(Journal.header(pf.player, flags)), 0);
                file.force();
                pf.flags = flags;
            }
            at = pf.length;
            file.write(ByteBuffer.wrap(record), at);
        } catch (IOException e) {
            fail(pf, seq, e);
            return null;
        }
        pf.length = at + record.length;
        dirty.add(pf);
        pf.state.apply(op, new JournalState.Ref(at, record.length));
        return op;
    }

    private void afterWrite(PlayerFile pf, Journal.Op op) {
        if (op instanceof Journal.Seal seal) {
            JournalState.Entry entry = pf.state.entries.get(seal.entry());
            if (entry != null) events.add(new Stored(pf.player, entry.id, entry.diskBytes()));
        }
        // Only these can empty a history or leave dead records worth a look (a section replacing an earlier one is
        // followed by its entry's seal); measuring after every section would cost entries × sections each time.
        if (op instanceof Journal.Mark || op instanceof Journal.Seal) {
            if (pf.state.isEmpty()) {
                deleteFile(pf);
            } else {
                maybeCompact(pf);
            }
        }
    }

    /** A write for the player failed: their later records are dropped until a rewrite. */
    private void fail(PlayerFile pf, long seq, Exception cause) {
        if (!pf.broken) {
            log.warn("Sculptory: saving the undo history of " + pf.player + " failed (" + cause
                    + "); it is kept in memory and saved again once writing works", cause instanceof IOException
                    ? null : cause);
        }
        pf.broken = true;
        setFailing(pf.player, true);
        // Drops a partly written record, so the file stays readable up to the last whole one.
        if (pf.file != null) {
            try {
                pf.file.truncate(pf.length);
            } catch (IOException ignored) {
                // The next scan ignores the torn tail.
            }
        }
        events.add(new FailedAt(pf.player, seq, String.valueOf(cause.getMessage()), false));
    }

    private void deleteFile(PlayerFile pf) {
        closeFile(pf);
        dirty.remove(pf);
        pf.generation++;
        try {
            io.delete(pf.path);
            pf.exists = false;
            pf.length = 0;
            pf.state = new JournalState(pf.player);
        } catch (IOException e) {
            // An empty history left on disk replays as empty; it is deleted next time.
            log.warn("Sculptory: deleting the empty history file of " + pf.player + " failed", e);
        }
    }

    private void maybeCompact(PlayerFile pf) {
        if (pf.compacting || pf.rewriting || !writable(pf) || !pf.exists) return;
        long live = pf.state.liveBytes();
        long garbage = pf.length - live;
        if (garbage <= settings.compactMinGarbage() || garbage <= live) return;
        if (System.nanoTime() - pf.compactRetryNanos < 0) return;
        pf.compacting = true;
        schedule(new CompactTask(pf));
    }

    // ------------------------------------------------------------------------------------------------ scanning

    /** Reads a file sequentially through a 1 MiB buffer. */
    private static final class SequentialReader {
        private final StorageIo.File file;
        private final long size;
        private final ByteBuffer buffer = ByteBuffer.allocate(1 << 20);
        private long bufferStart;
        private int bufferLength;

        SequentialReader(StorageIo.File file, long size) {
            this.file = file;
            this.size = size;
        }

        /** Reads {@code length} (at most 1 MiB) bytes at {@code position}, or null when the file ends first. */
        byte[] read(long position, int length) throws IOException {
            if (length < 0 || length > buffer.capacity() || position + length > size) return null;
            byte[] out = new byte[length];
            int done = 0;
            while (done < length) {
                int n = fill(position + done);
                int offset = (int) (position + done - bufferStart);
                n = Math.min(length - done, n);
                System.arraycopy(buffer.array(), offset, out, done, n);
                done += n;
            }
            return out;
        }

        /**
         * Runs {@code length} bytes at {@code position} through {@code crc}, a buffer at a time.
         *
         * @return false when the file ends first
         */
        boolean update(CRC32C crc, long position, long length) throws IOException {
            if (length < 0 || position + length > size) return false;
            long done = 0;
            while (done < length) {
                int n = fill(position + done);
                int offset = (int) (position + done - bufferStart);
                n = (int) Math.min(length - done, n);
                crc.update(buffer.array(), offset, n);
                done += n;
            }
            return true;
        }

        /** Whether every byte from {@code position} to {@code end} is zero (read a buffer at a time). */
        boolean allZero(long position, long end) throws IOException {
            long at = position;
            while (at < Math.min(end, size)) {
                int n = (int) Math.min(end - at, fill(at));
                int offset = (int) (at - bufferStart);
                byte[] bytes = buffer.array();
                for (int i = 0; i < n; i++) {
                    if (bytes[offset + i] != 0) return false;
                }
                at += n;
            }
            return true;
        }

        /** Makes the buffer hold {@code at}; returns how many bytes from {@code at} it holds. */
        private int fill(long at) throws IOException {
            if (at < bufferStart || at >= bufferStart + bufferLength) {
                buffer.clear();
                int limit = (int) Math.min(buffer.capacity(), size - at);
                buffer.limit(limit);
                readFully(file, buffer, at);
                bufferStart = at;
                bufferLength = limit;
            }
            return (int) (bufferStart + bufferLength - at);
        }
    }

    private static void readFully(StorageIo.File file, ByteBuffer into, long position) throws IOException {
        long at = position;
        while (into.hasRemaining()) {
            int n = file.read(into, at);
            if (n < 0) throw new CorruptDataException("history file ends early");
            at += n;
        }
    }

    /** Reads one whole record at {@code ref} (its size was checked by the scan) and checks it. */
    private static byte[] readRecord(StorageIo.File file, JournalState.Ref ref) throws IOException {
        byte[] record = new byte[ref.length()];
        readFully(file, ByteBuffer.wrap(record), ref.offset());
        int length = Journal.getInt(record, 0);
        if (length != ref.length() - Journal.FRAME_BYTES || Journal.getInt(record, 5) != Journal.crc(record, length)) {
            throw new CorruptDataException("record at " + ref.offset() + " is damaged");
        }
        return record;
    }

    /**
     * One record found by a scan (its whole length), or why the scan stops there: with the length it claims when the
     * whole record is in the file ({@code wholeRecord}), or {@code atEnd} when the file ends inside it.
     */
    private record Scanned1(Journal.Op op, int length, String problem, boolean wholeRecord, boolean atEnd) {}

    /**
     * Reads the record at {@code position}: its frame, then its payload through the checksum. A section's payload is
     * never held whole (only its fixed fields); other payloads are small by type ({@link Journal.Type#fits}).
     */
    private static Scanned1 scanRecord(SequentialReader reader, long position, long size) throws IOException {
        byte[] frame = reader.read(position, Journal.FRAME_BYTES);
        if (frame == null) return new Scanned1(null, 0, "a record cut short", false, true);
        int length = Journal.getInt(frame, 0);
        Journal.Type type = Journal.Type.of(frame[4]);
        if (type == null || !type.fits(length)) return new Scanned1(null, 0, "a malformed record", false, false);
        long end = position + Journal.FRAME_BYTES + length;
        if (end > size) return new Scanned1(null, 0, "a record cut short", false, true);
        CRC32C crc = new CRC32C();
        crc.update(frame, 0, 5);
        Journal.Op op;
        try {
            if (type.large()) {
                boolean section = type == Journal.Type.SECTION;
                int headBytes = section ? Journal.SECTION_HEAD_BYTES : Journal.ENTITIES_HEAD_BYTES;
                byte[] head = reader.read(position + Journal.FRAME_BYTES, Math.min(length, headBytes));
                reader.update(crc, position + Journal.FRAME_BYTES, length);
                if (Journal.getInt(frame, 5) != (int) crc.getValue()) {
                    return new Scanned1(null, Journal.FRAME_BYTES + length, "a record with a bad checksum", true,
                            false);
                }
                op = section ? Journal.decodeSectionHead(head, length) : Journal.decodeEntitiesHead(head, length);
            } else {
                byte[] record = new byte[Journal.FRAME_BYTES + length];
                System.arraycopy(frame, 0, record, 0, Journal.FRAME_BYTES);
                byte[] payload = reader.read(position + Journal.FRAME_BYTES, length);
                System.arraycopy(payload, 0, record, Journal.FRAME_BYTES, length);
                if (Journal.getInt(record, 5) != Journal.crc(record, length)) {
                    return new Scanned1(null, Journal.FRAME_BYTES + length, "a record with a bad checksum", true,
                            false);
                }
                op = Journal.decode(type, record, length);
            }
        } catch (CorruptDataException e) {
            return new Scanned1(null, Journal.FRAME_BYTES + length,
                    "a record that does not decode (" + e.getMessage() + ")", true, false);
        }
        return new Scanned1(op, Journal.FRAME_BYTES + length, null, true, false);
    }

    /**
     * Scans one player's file: checks the header, replays every whole record, truncates a torn or damaged tail and
     * settles what a crash left open. Returns the file's state (an empty one when there is no file), or null when the
     * file is of another format version (it is left alone and the player's history is not saved). A file that cannot
     * be read is registered {@linkplain PlayerFile#unreadable unreadable}: left as it is and read again later.
     */
    private PlayerFile scanFile(UUID player, Path path) {
        PlayerFile known = files.get(player);
        StorageIo.File file = null;
        try {
            // Absent only when it certainly is: a file that cannot be examined is unreadable (read again later), never
            // taken as missing and later written anew over.
            if (!io.exists(path)) return register(new PlayerFile(player, new JournalState(player), 0, false));
            file = io.open(path);
            long size = file.size();
            if (size == 0) {
                file.close();
                io.delete(path);
                return register(new PlayerFile(player, new JournalState(player), 0, false));
            }
            SequentialReader reader = new SequentialReader(file, size);
            byte[] header = reader.read(0, (int) Math.min(size, Journal.HEADER_BYTES));
            int flags;
            try {
                Journal.Header read = Journal.readHeader(header);
                if (!read.player().equals(player)) {
                    throw new CorruptDataException("history file of " + read.player() + " under " + player);
                }
                flags = read.flags();
            } catch (Journal.UnsupportedVersionException e) {
                file.close();
                locked.add(player);
                log.warn("Sculptory: the history file " + path + " has " + e.getMessage() + "; it is left as it "
                        + "is and " + player + "'s history is not saved while this version runs", null);
                events.add(new FailedAt(player, Long.MAX_VALUE, e.getMessage(), true));
                return null;
            } catch (CorruptDataException e) {
                file.close();
                file = null;
                // A file that cannot be moved aside is left as it is and read again later (as an unreadable one).
                quarantine(path, e.getMessage());
                return register(new PlayerFile(player, new JournalState(player), 0, false));
            }
            JournalState state = new JournalState(player);
            long position = Journal.HEADER_BYTES;
            Scanned1 bad = null;
            Path copy = null;
            while (position < size) {
                Scanned1 next = scanRecord(reader, position, size);
                if (next.problem() != null) {
                    bad = next;
                    break;
                }
                state.apply(next.op(), new JournalState.Ref(position, next.length()));
                position += next.length();
            }
            if (bad != null) {
                // A write cut off at the end of the file (the file ends inside the record, or its last record is
                // bad) is what a crash or power loss leaves; anything else is damage with data after it. Unless what
                // is cut off holds no record data (a frame at most) or is zeros the file system never filled, a copy of
                // the file as it is is kept first: a damaged length field can look like a cut-off write. Without that
                // copy (a full disk) nothing is cut off: the file is left as it is and read again later, as a file
                // that cannot be read is, and the player's new steps stay in memory meanwhile.
                boolean atEnd = bad.atEnd() || (bad.wholeRecord() && position + bad.length() == size);
                boolean empty = size - position <= Journal.FRAME_BYTES || reader.allZero(position, size);
                if (!empty) {
                    try {
                        copy = keepCopy(path, size, bad.problem() + " at byte " + position);
                    } catch (IOException notCopied) {
                        throw new IOException("it has " + bad.problem() + " at byte " + position + ", and keeping a "
                                + "copy before cutting that off failed (" + notCopied.getMessage() + ")", notCopied);
                    }
                }
                log.warn(String.format(Locale.ROOT, "Sculptory: %s's history file has %s at byte %d (%s); the %d "
                        + "bytes from there are dropped", player, bad.problem(), position,
                        atEnd ? "at its end, as a crash or power loss leaves it" : "damage with data after it",
                        size - position), null);
            }
            PlayerFile pf = register(new PlayerFile(player, state, position, true));
            pf.flags = flags;
            pf.file = file;
            open.put(player, pf);
            if (bad != null) {
                try {
                    file.truncate(position);
                    dirty.add(pf);
                    // Only now that the damaged part is cut off (and never the copy that holds it) may old copies go.
                    if (copy != null) pruneDamaged(player, copy);
                } catch (IOException e) {
                    pf.broken = true;
                    setFailing(player, true);
                    log.warn("Sculptory: truncating " + path + " failed; " + player + "'s history is read but not "
                            + "saved any further", e);
                }
            }
            if (state.skipped > 0) {
                log.info("Sculptory: " + state.skipped + " record(s) in " + player + "'s history did not apply "
                        + "and were skipped");
            }
            if (writable(pf)) recover(pf);
            if (pf.state.isEmpty()) {
                deleteFile(pf);
            } else {
                maybeCompact(pf);
            }
            // A start-up scan of many files must not leave them all open.
            trimOpen(null);
            if (known != null && known.unreadable) {
                setFailing(player, !writable(pf));
                log.info("Sculptory: " + player + "'s history file can be read again");
            }
            return pf;
        } catch (IOException | RuntimeException e) {
            open.remove(player);
            if (file != null) {
                try {
                    file.close();
                } catch (IOException ignored) {
                    // Closing a file that failed to read.
                }
            }
            return unreadable(player, path, known, e);
        }
    }

    /**
     * The player's file could not be read (another program holds it, an I/O error): it is left exactly as it is,
     * nothing is written to it, and it is read again after a growing wait (a load keeps trying). Logged when it starts,
     * then every {@link #WARN_EVERY_NANOS} while it lasts.
     */
    private PlayerFile unreadable(UUID player, Path path, PlayerFile known, Exception cause) {
        boolean first = known == null || !known.unreadable;
        PlayerFile pf = known != null ? known : new PlayerFile(player, new JournalState(player), 0, true);
        closeFile(pf);
        dirty.remove(pf);
        pf.unreadable = true;
        pf.exists = true;
        pf.state = new JournalState(player);
        pf.retryWait = pf.retryWait == 0 ? RETRY_FIRST_NANOS : Math.min(RETRY_MAX_NANOS, pf.retryWait * 2);
        long now = System.nanoTime();
        pf.retryAt = now + pf.retryWait;
        pf.generation++;
        register(pf);
        if (first || now - pf.warnedAt >= WARN_EVERY_NANOS) {
            pf.warnedAt = now;
            log.warn("Sculptory: " + player + "'s history file " + path + (first ? "" : " still") + " cannot be "
                    + "read (" + cause + "); it is left as it is and read again every minute at most; their new steps "
                    + "are kept in memory only until then (lost if the server stops)", null);
        }
        if (first) {
            setFailing(player, true);
            events.add(new FailedAt(player, 0, "the history file cannot be read: " + cause.getMessage(), false));
        }
        return pf;
    }

    private PlayerFile register(PlayerFile pf) {
        files.put(pf.player, pf);
        return pf;
    }

    /**
     * A name next to {@code path} for a file kept aside: {@code <name>.<UTC time><suffix>}, with {@code -2}, {@code -3}
     * ... added when taken, so an older one is never replaced.
     *
     * @throws IOException when whether a name is taken cannot be told
     */
    private Path asideName(Path path, String suffix) throws IOException {
        String stamp = ASIDE_STAMP.format(java.time.Instant.now());
        String base = path.getFileName() + "." + stamp;
        Path aside = path.resolveSibling(base + suffix);
        for (int n = 2; io.exists(aside); n++) aside = path.resolveSibling(base + "-" + n + suffix);
        return aside;
    }

    /**
     * Moves a file whose header is damaged aside as {@code .corrupt} (a new name each time). The server never deletes
     * {@code .corrupt} files: each is the only copy of a whole history.
     *
     * @throws IOException when it cannot be moved: the file is then left as it is and read again later, as a file that
     *     cannot be read (logged there, at first and then every {@link #WARN_EVERY_NANOS})
     */
    private void quarantine(Path path, String problem) throws IOException {
        Path aside;
        try {
            aside = asideName(path, CORRUPT);
            io.replace(path, aside);
        } catch (IOException e) {
            throw new IOException("its header is damaged (" + problem + ") and moving it aside failed (" + e + ")", e);
        }
        log.warn("Sculptory: the history file " + path + " is damaged (" + problem + "); moved to " + aside
                + " and that history is not restored", null);
    }

    /**
     * Keeps a {@code .damaged} copy of a file whose data is damaged, next to it (a new name each time), before anything
     * of it is cut off or given up. The copy is not tried unless the disk has {@code bytes} (the file's size) plus
     * {@link #COPY_FREE_MARGIN} free, so a nearly full disk is never filled by retries. The caller prunes older copies
     * ({@link #pruneDamaged}) once the damaged part is cut off or given up.
     *
     * @return the copy
     * @throws IOException when it cannot be kept (a partial copy is deleted); the caller then cuts nothing off and logs,
     *     rate-limited
     */
    private Path keepCopy(Path path, long bytes, String problem) throws IOException {
        long free;
        try {
            free = io.usableSpace(dir);
        } catch (IOException | RuntimeException unknown) {
            free = Long.MAX_VALUE; // not known here: the copy itself tells
        }
        if (free < bytes + COPY_FREE_MARGIN) {
            throw new IOException(String.format(Locale.ROOT, "%d MiB free, a copy needs %d MiB and %d MiB more are "
                    + "kept free", free >> 20, bytes >> 20, COPY_FREE_MARGIN >> 20));
        }
        Path copy = asideName(path, DAMAGED);
        try {
            io.copy(path, copy);
        } catch (IOException e) {
            try {
                io.delete(copy);
            } catch (IOException ignored) {
                // A partial copy left behind is pruned with the other copies later.
            }
            throw e;
        }
        log.warn("Sculptory: the history file " + path + " holds damaged data (" + problem + "); a copy is kept as "
                + copy, null);
        return copy;
    }

    /** A {@code .damaged} copy ({@code <uuid>.bshist.<yyyyMMdd-HHmmss>[-n].damaged}) and when it was made. */
    private record Damaged(Path path, String stamp, int n) {}

    /** {@code path} as {@code player}'s {@code .damaged} copy, or null when its name is not one this store makes. */
    private static Damaged damaged(Path path, UUID player) {
        String name = path.getFileName().toString();
        String prefix = player + EXTENSION + ".";
        if (!name.startsWith(prefix) || !name.endsWith(DAMAGED)) return null;
        String rest = name.substring(prefix.length(), name.length() - DAMAGED.length());
        if (rest.length() < 15 || !rest.substring(0, 15).matches("\\d{8}-\\d{6}")) return null;
        int n = 1;
        if (rest.length() > 15) {
            if (rest.charAt(15) != '-' || !rest.substring(16).matches("[1-9]\\d{0,8}")) return null;
            n = Integer.parseInt(rest.substring(16));
        }
        return new Damaged(path, rest.substring(0, 15), n);
    }

    /**
     * After the damaged part that {@code made} holds has been cut off or given up: keeps {@code made} and the newest
     * {@link #MAX_KEPT_DAMAGED} - 1 other {@code .damaged} copies of the player (by the time in the name, then its
     * number), deleting the rest. {@code made} is never deleted, whatever the clock said when the others were made.
     * {@code .corrupt} files and names this store does not make are never touched.
     */
    private void pruneDamaged(UUID player, Path made) {
        List<Damaged> others = new ArrayList<>();
        try {
            for (Path path : io.list(dir)) {
                Damaged copy = damaged(path, player);
                if (copy != null && !path.getFileName().equals(made.getFileName())) others.add(copy);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Sculptory: listing the history folder to drop old copies of damaged history failed; tried "
                    + "again with the next copy", null);
            return;
        }
        int keep = MAX_KEPT_DAMAGED - 1;
        if (others.size() <= keep) return;
        others.sort(Comparator.comparing(Damaged::stamp).thenComparingInt(Damaged::n));
        int deleted = 0;
        for (Damaged old : others.subList(0, others.size() - keep)) {
            try {
                io.delete(old.path());
                deleted++;
            } catch (IOException e) {
                log.warn("Sculptory: deleting the old copy " + old.path() + " failed", e);
            }
        }
        if (deleted > 0) {
            log.info("Sculptory: deleted " + deleted + " older copies of " + player + "'s damaged history (the "
                    + "newest " + MAX_KEPT_DAMAGED + " are kept)");
        }
    }

    /**
     * Settles what a crash left open, so the file replays to a plain stack from now on (each step is journaled):
     * <ol>
     *   <li>a redo still in flight counts as redone (its job may have written some cells, which must stay undoable);
     *       an undo in flight needs nothing, since it stays the undo candidate and running it again finishes it;</li>
     *   <li>entries sealed but not pushed (their push waited behind an undo, redo or load) are pushed in the order they
     *       were sealed, which is the order their jobs finished and would have been pushed;</li>
     *   <li>entries begun but not sealed (a job or stroke still running) that recorded cells are sealed as
     *       "(interrupted)" and pushed: their sections are exactly what the job had written when they were
     *       journaled; entries that recorded nothing are aborted.</li>
     * </ol>
     */
    private void recover(PlayerFile pf) {
        JournalState state = pf.state;
        if (state.redoInFlight != null) {
            UUID redo = state.redoInFlight;
            boolean candidate = state.applied < state.stack.size() && state.stack.get(state.applied).id.equals(redo);
            append(pf, candidate ? Journal.Type.REDONE : Journal.Type.REDO_ABORT, Journal.mark(redo), 0);
            if (pf.broken) return;
        }
        List<JournalState.Entry> pending = new ArrayList<>(state.pending.values());
        List<JournalState.Entry> sealed = new ArrayList<>();
        for (JournalState.Entry entry : pending) {
            if (entry.sealed()) sealed.add(entry);
        }
        sealed.sort(Comparator.comparingLong(e -> e.sealOrder));
        for (JournalState.Entry entry : sealed) {
            append(pf, Journal.Type.PUSH, Journal.mark(entry.id), 0);
            if (pf.broken) return;
        }
        for (JournalState.Entry entry : pending) {
            if (entry.sealed()) continue;
            EditRecord record = null;
            String problem = "it recorded nothing";
            if (entry.hasData()) {
                try {
                    record = readRecord(pf, entry);
                } catch (IOException e) {
                    problem = e.getMessage();
                }
            }
            if (record == null || record.isEmpty()) {
                if (entry.hasData()) {
                    log.warn("Sculptory: an interrupted edit in " + pf.player + "'s history could not be restored ("
                            + problem + ")", null);
                }
                append(pf, Journal.Type.ABORT, Journal.mark(entry.id), 0);
            } else {
                String label = entry.baseLabel + " (interrupted) · " + blocks(record.before().cellCount())
                        + (record.entities().isEmpty() ? "" : " · " + entities(record.entities().size()));
                append(pf, Journal.Type.SEAL, Journal.seal(entry.id, label, entry.created, record.estimatedBytes()), 0);
                if (pf.broken) return;
                append(pf, Journal.Type.PUSH, Journal.mark(entry.id), 0);
                log.info("Sculptory: restored an edit interrupted by a stop as \"" + label + "\" in " + pf.player
                        + "'s history");
            }
            if (pf.broken) return;
        }
    }

    /** "1 block", "1,284 blocks" (as the edit service labels entries). */
    static String blocks(long n) {
        return n == 1 ? "1 block" : String.format(Locale.ROOT, "%,d blocks", n);
    }

    /** "1 entity", "12 entities" (as the edit service labels entries). */
    static String entities(long n) {
        return n == 1 ? "1 entity" : String.format(Locale.ROOT, "%,d entities", n);
    }

    /** Reads and decodes one entry's latest sections and its entities. */
    private EditRecord readRecord(PlayerFile pf, JournalState.Entry entry) throws IOException {
        StorageIo.File file = ensureOpen(pf);
        BlockBuffer before = new BlockBuffer();
        BlockBuffer after = new BlockBuffer();
        for (Map.Entry<Long, JournalState.SectionRef> section : entry.sections.entrySet()) {
            decodeSection(file, section.getKey(), section.getValue(), before, after);
        }
        LinkedHashMap<UUID, EntityChange> changes = new LinkedHashMap<>();
        for (JournalState.EntitiesRef batch : entry.entities) decodeEntities(file, batch, changes);
        return new EditRecord(before, after, keptChanges(changes));
    }

    /**
     * Reads one entities record into {@code changes} (a later change of an entity replaces an earlier one).
     *
     * @throws UnrestorableException for another game data version
     * @throws CorruptDataException if the record is damaged
     */
    private void decodeEntities(StorageIo.File file, JournalState.EntitiesRef batch,
                                LinkedHashMap<UUID, EntityChange> changes) throws IOException {
        if (batch.changes() == 0) return;
        if (batch.dataVersion() != codec.dataVersion()) {
            throw new UnrestorableException("entities recorded by game data version " + batch.dataVersion()
                    + ", this game is " + codec.dataVersion());
        }
        byte[] record = readRecord(file, batch.ref());
        Journal.Op op = Journal.decode(Journal.Type.ENTITIES, record, record.length - Journal.FRAME_BYTES);
        if (!(op instanceof Journal.Entities decoded) || decoded.changes() != batch.changes()) {
            throw new CorruptDataException("entities record does not match its index");
        }
        int start = Journal.FRAME_BYTES + decoded.bodyOffset();
        byte[] body = java.util.Arrays.copyOfRange(record, start, record.length);
        // A later change replaces an earlier one in place: entities keep the order they were first recorded in.
        for (EntityChange change : EntityCodec.decode(body, decoded.changes())) changes.put(change.id(), change);
    }

    /** The changes a record keeps: cancelled and unchanged ones left out. */
    private static List<EntityChange> keptChanges(Map<UUID, EntityChange> changes) {
        List<EntityChange> kept = new ArrayList<>(changes.size());
        for (EntityChange change : changes.values()) {
            if (!change.unchanged()) kept.add(change);
        }
        return kept;
    }

    private void decodeSection(StorageIo.File file, long key, JournalState.SectionRef section, BlockBuffer before,
                               BlockBuffer after) throws IOException {
        if (section.cells() == 0) return;
        if (section.dataVersion() != codec.dataVersion()) {
            throw new UnrestorableException("recorded by game data version " + section.dataVersion() + ", this game is "
                    + codec.dataVersion());
        }
        byte[] record = readRecord(file, section.ref());
        Journal.Op op = Journal.decode(Journal.Type.SECTION, record, record.length - Journal.FRAME_BYTES);
        if (!(op instanceof Journal.Section decoded) || decoded.key() != key) {
            throw new CorruptDataException("section record does not match its index");
        }
        int start = Journal.FRAME_BYTES + decoded.bodyOffset();
        byte[] body = java.util.Arrays.copyOfRange(record, start, record.length);
        SectionBuffer[] pair = SectionCodec.decode(body, codec);
        if (pair == null) return;
        before.putSection(key, pair[0]);
        after.putSection(key, pair[1]);
    }

    // ------------------------------------------------------------------------------------------------ background

    /** Work done in steps between records. */
    private abstract static class Task {
        /** {@code System.nanoTime()} before which the task has nothing to do (waiting to retry); ready when made. */
        long readyAt = System.nanoTime();

        /** Does one step; true when the task is done. */
        abstract boolean step();

        /** The store is closing: give up (clean up temporary files). */
        void abandon() {}

        /** Loads go before other background work (a player is waiting). */
        boolean urgent() {
            return false;
        }
    }

    private void schedule(Task task) {
        background.addLast(task);
        backgroundIdle = false;
    }

    /** The task to step next: a ready load first, else the first ready task; null when none is ready. */
    private Task readyTask() {
        long now = System.nanoTime();
        Task first = null;
        for (Task task : background) {
            if (now - task.readyAt < 0) continue;
            if (task.urgent()) return task;
            if (first == null) first = task;
        }
        return first;
    }

    /** Does one step of the next ready background task; false when none was ready. */
    private boolean stepBackground() {
        Task task = readyTask();
        if (task == null) {
            backgroundIdle = background.isEmpty();
            return false;
        }
        boolean done;
        try {
            done = task.step();
        } catch (RuntimeException e) {
            log.warn("Sculptory: a history background task failed", e);
            task.abandon();
            done = true;
        }
        if (done) background.remove(task);
        backgroundIdle = background.isEmpty();
        writeUrgent();
        return true;
    }

    private void abandonBackground() {
        for (Task task : background) task.abandon();
        background.clear();
    }

    /**
     * The start-up scan: one file per step, then the {@link Scanned} report. If the folder cannot be listed, nobody's
     * history is assumed empty: the listing is tried again after a growing wait (logged at first, then every
     * {@link #WARN_EVERY_NANOS}), and until it works no report is made, so every player is loaded (their own file is
     * read when they are) and nothing is rewritten on the belief that they had no history.
     */
    private final class ScanTask extends Task {
        long wait;
        long warnedAt;
        int attempts;

        @Override
        boolean step() {
            if (unscanned == null) {
                List<Path> listed;
                try {
                    listed = io.list(dir);
                } catch (java.nio.file.NoSuchFileException e) {
                    listed = List.of(); // no folder, no history
                } catch (IOException | java.io.UncheckedIOException | java.nio.file.DirectoryIteratorException e) {
                    // Failing part way (an iterator's unchecked exception) is a failed listing like any other.
                    long now = System.nanoTime();
                    wait = wait == 0 ? RETRY_FIRST_NANOS : Math.min(RETRY_MAX_NANOS, wait * 2);
                    readyAt = now + wait;
                    attempts++;
                    listingProblem = "the history folder cannot be listed (" + attempts + " attempt"
                            + (attempts == 1 ? "" : "s") + ", " + e + "); players' histories are read as they join";
                    if (warnedAt == 0 || now - warnedAt >= WARN_EVERY_NANOS) {
                        warnedAt = now;
                        log.warn("Sculptory: listing the history folder " + dir + " failed; it is tried again "
                                + "(players' histories are read one by one as they join meanwhile)", e);
                    }
                    return false;
                }
                listingProblem = null;
                unscanned = new ArrayList<>();
                for (Path path : listed) {
                    String name = path.getFileName().toString();
                    if (name.endsWith(TEMP) && name.contains(EXTENSION + ".")) {
                        // An unfinished compaction or rewrite of an earlier run (not one running now: the listing
                        // may have been retried after players were loaded).
                        UUID owner = playerOf(name.substring(0, name.indexOf(EXTENSION + ".") + EXTENSION.length()));
                        PlayerFile known = owner == null ? null : files.get(owner);
                        if (known != null && (known.compacting || known.rewriting)) continue;
                        try {
                            io.delete(path);
                        } catch (IOException e) {
                            log.warn("Sculptory: deleting the stray temporary file " + path + " failed", e);
                        }
                    } else if (name.endsWith(EXTENSION) && playerOf(name) != null) {
                        unscanned.add(path);
                    }
                }
                if (warnedAt != 0) log.info("Sculptory: the history folder " + dir + " can be listed again");
                return false;
            }
            if (!unscanned.isEmpty()) {
                Path path = unscanned.remove(unscanned.size() - 1);
                UUID player = playerOf(path.getFileName().toString());
                if (!files.containsKey(player) && !locked.contains(player)) scanFile(player, path);
                return false;
            }
            List<StoredHistory> histories = new ArrayList<>();
            for (PlayerFile pf : files.values()) {
                if (!pf.unreadable && !pf.state.stack.isEmpty()) histories.add(pf.state.toStored());
            }
            events.add(new Scanned(histories));
            return true;
        }
    }

    private static UUID playerOf(String name) {
        if (!name.endsWith(EXTENSION)) return null;
        try {
            UUID id = UUID.fromString(name.substring(0, name.length() - EXTENSION.length()));
            return (id + EXTENSION).equals(name) ? id : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Reads a player's stack entries back, a few sections per step. The stack is taken when the task starts (a
     * compaction queued before it may have moved every record) and taken again if the file is replaced meanwhile. A
     * file that cannot be read, or a read that fails, is tried again after a growing wait: nothing is reported as failed
     * for it. An entry whose data is damaged is read {@value #DAMAGED_ATTEMPTS} times (counted per entry, 1 s apart)
     * before it is given up for the rest of the load (one copy of the file is kept); an entry the game cannot restore
     * (unknown state, other data version) is given up at once. Retries are logged at first, then every
     * {@link #WARN_EVERY_NANOS}.
     */
    private final class LoadTask extends Task {
        final UUID player;
        int generation;
        List<JournalState.Entry> entries;
        int applied;
        final List<LoadedEntry> loaded = new ArrayList<>();
        final List<UUID> failed = new ArrayList<>();
        final List<String> problems = new ArrayList<>();
        int loadedApplied;
        int index;
        Iterator<Map.Entry<Long, JournalState.SectionRef>> sections;
        Iterator<JournalState.EntitiesRef> entityBatches;
        BlockBuffer before;
        BlockBuffer after;
        LinkedHashMap<UUID, EntityChange> entityChanges;
        /** Reads of each entry that found damaged data (kept when the load starts over). */
        final Map<UUID, Integer> damagedReads = new HashMap<>();
        /** Entries given up, with why (kept when the load starts over: they are not read again). */
        final Map<UUID, String> givenUp = new LinkedHashMap<>();
        boolean copyKept;
        long wait;
        long warnedAt;

        LoadTask(UUID player) {
            this.player = player;
        }

        @Override
        boolean urgent() {
            return true;
        }

        @Override
        boolean step() {
            PlayerFile pf = files.get(player);
            if (pf == null) {
                events.add(new Loaded(player, List.of(), 0, List.of(), List.of("its history file is locked")));
                return true;
            }
            if (pf.unreadable) {
                if (System.nanoTime() - pf.retryAt < 0) {
                    readyAt = pf.retryAt;
                    return false;
                }
                pf = scanFile(player, pf.path);
                if (pf == null) {
                    events.add(new Loaded(player, List.of(), 0, List.of(),
                            List.of("its history file was written by another version of Sculptory")));
                    return true;
                }
                if (pf.unreadable) {
                    readyAt = pf.retryAt;
                    return false;
                }
                restart();
            }
            if (entries == null || pf.generation != generation) {
                restart();
                generation = pf.generation;
                entries = new ArrayList<>(pf.state.stack);
                applied = pf.state.applied;
            }
            long budget = settings.stepBytes();
            while (index < entries.size() && budget > 0) {
                JournalState.Entry entry = entries.get(index);
                String gaveUp = givenUp.get(entry.id);
                if (gaveUp != null) {
                    fail(entry, gaveUp);
                    continue;
                }
                if (sections == null) {
                    sections = entry.sections.entrySet().iterator();
                    entityBatches = entry.entities.iterator();
                    before = new BlockBuffer();
                    after = new BlockBuffer();
                    entityChanges = new LinkedHashMap<>();
                }
                try {
                    StorageIo.File file = ensureOpen(pf);
                    while (sections.hasNext() && budget > 0) {
                        Map.Entry<Long, JournalState.SectionRef> section = sections.next();
                        budget -= section.getValue().ref().length();
                        decodeSection(file, section.getKey(), section.getValue(), before, after);
                    }
                    while (!sections.hasNext() && entityBatches.hasNext() && budget > 0) {
                        JournalState.EntitiesRef batch = entityBatches.next();
                        budget -= batch.ref().length();
                        decodeEntities(file, batch, entityChanges);
                    }
                } catch (UnrestorableException e) {
                    givenUp.put(entry.id, e.getMessage());
                    fail(entry, e.getMessage());
                    continue;
                } catch (CorruptDataException e) {
                    if (damagedReads.merge(entry.id, 1, Integer::sum) < DAMAGED_ATTEMPTS) {
                        return retry(pf, e, RETRY_FIRST_NANOS);
                    }
                    Path made = null;
                    if (!copyKept) {
                        // Nothing is given up without a copy: if it cannot be made (a full disk), the load waits.
                        try {
                            made = keepCopy(pf.path, pf.length, e.getMessage());
                        } catch (IOException notCopied) {
                            wait = wait == 0 ? RETRY_FIRST_NANOS : Math.min(RETRY_MAX_NANOS, wait * 2);
                            return retry(pf, new IOException("damaged data, and keeping a copy of the file before "
                                    + "giving it up failed (" + notCopied.getMessage() + ")", notCopied), wait);
                        }
                        copyKept = true;
                    }
                    givenUp.put(entry.id, "damaged data (" + e.getMessage() + ")");
                    fail(entry, givenUp.get(entry.id));
                    if (made != null) pruneDamaged(player, made);
                    continue;
                } catch (IOException | RuntimeException e) {
                    wait = wait == 0 ? RETRY_FIRST_NANOS : Math.min(RETRY_MAX_NANOS, wait * 2);
                    return retry(pf, e, wait);
                }
                if (sections.hasNext() || entityBatches.hasNext()) break;
                EditRecord record = new EditRecord(before, after, keptChanges(entityChanges));
                if (record.isEmpty()) {
                    givenUp.put(entry.id, "it holds nothing");
                    fail(entry, "it holds nothing");
                    continue;
                }
                long bytes = entry.bytes > 0 ? entry.bytes : record.estimatedBytes();
                loaded.add(new LoadedEntry(entry.id, entry.world, entry.label(), entry.createdMillis(), bytes,
                        entry.diskBytes(), record));
                if (index < applied) loadedApplied++;
                next();
            }
            if (index < entries.size()) return false;
            events.add(new Loaded(player, List.copyOf(loaded), loadedApplied, List.copyOf(failed),
                    List.copyOf(problems)));
            return true;
        }

        /** A read failed: start over after {@code delay} (nothing is given up for it). */
        private boolean retry(PlayerFile pf, Exception cause, long delay) {
            long now = System.nanoTime();
            readyAt = now + delay;
            if (warnedAt == 0 || now - warnedAt >= WARN_EVERY_NANOS) {
                warnedAt = now;
                log.warn("Sculptory: reading " + player + "'s history failed (" + cause + "); it is tried again "
                        + "until it works", null);
            }
            closeFile(pf);
            entries = null;
            return false;
        }

        private void restart() {
            entries = null;
            loaded.clear();
            failed.clear();
            problems.clear();
            loadedApplied = 0;
            index = 0;
            sections = null;
            entityBatches = null;
            entityChanges = null;
            before = null;
            after = null;
        }

        private void fail(JournalState.Entry entry, String problem) {
            failed.add(entry.id);
            problems.add("\"" + entry.label() + "\": " + problem);
            next();
        }

        private void next() {
            index++;
            sections = null;
            entityBatches = null;
            entityChanges = null;
            before = null;
            after = null;
        }
    }

    /**
     * Rewrites a file with only its live records: live entries' data copied as it is (latest sections only), a push
     * per stack entry, undo marks for the redo side, the redo in flight, then the pending entries' data. Records
     * appended while it runs are copied after them at the end.
     */
    private final class CompactTask extends Task {
        final PlayerFile pf;
        final Path temp;
        final JournalState state;
        /** Records to copy (a {@link JournalState.Ref}) or to write (a byte[] record), in order; null until started. */
        ArrayDeque<Object> plan;
        int generation;
        /** The file's length when the plan was made: records from there on are copied at the end. */
        long start;
        StorageIo.File out;
        long length;
        /** The flags the copy's header was written with. */
        int headerFlags;
        /** Whether an {@code ENTITIES} record went into the copy (even one of an entry dropped again since). */
        boolean copiedEntities;

        CompactTask(PlayerFile pf) {
            this.pf = pf;
            this.temp = pf.path.resolveSibling(pf.path.getFileName() + COMPACT_TEMP);
            this.state = new JournalState(pf.player);
        }

        /** Plans the copy from the file as it is now (the task may have waited behind others). */
        private void plan() {
            plan = new ArrayDeque<>();
            generation = pf.generation;
            start = pf.length;
            JournalState source = pf.state;
            for (JournalState.Entry entry : source.stack) {
                planData(entry);
                plan.add(Journal.frame(Journal.Type.PUSH, Journal.mark(entry.id)));
            }
            for (int i = source.stack.size() - 1; i >= source.applied; i--) {
                plan.add(Journal.frame(Journal.Type.UNDONE, Journal.mark(source.stack.get(i).id)));
            }
            if (source.redoInFlight != null) {
                plan.add(Journal.frame(Journal.Type.REDO_BEGIN, Journal.mark(source.redoInFlight)));
            }
            for (JournalState.Entry entry : source.pending.values()) planData(entry);
        }

        private void planData(JournalState.Entry entry) {
            plan.add(entry.begin);
            for (JournalState.SectionRef section : entry.sections.values()) {
                if (section.cells() > 0) plan.add(section.ref());
            }
            for (JournalState.EntitiesRef batch : entry.entities) plan.add(batch.ref());
            if (entry.seal != null) plan.add(entry.seal);
        }

        @Override
        boolean step() {
            if (plan == null) {
                if (!writable(pf) || pf.rewriting || !pf.exists || files.get(pf.player) != pf) {
                    pf.compacting = false;
                    return true;
                }
                plan();
            }
            if (pf.generation != generation || !writable(pf) || pf.rewriting) {
                abandon();
                return true;
            }
            try {
                if (out == null) {
                    io.delete(temp);
                    out = io.open(temp);
                    out.truncate(0);
                    headerFlags = pf.state.holdsEntities() ? Journal.FLAG_ENTITIES : 0;
                    append(Journal.header(pf.player, headerFlags), false);
                }
                StorageIo.File source = ensureOpen(pf);
                long budget = settings.stepBytes();
                while (!plan.isEmpty() && budget > 0) {
                    Object next = plan.pollFirst();
                    byte[] record = next instanceof JournalState.Ref ref ? readRecord(source, ref) : (byte[]) next;
                    budget -= record.length;
                    append(record, true);
                }
                if (!plan.isEmpty()) return false;
                finish(source);
            } catch (IOException | RuntimeException e) {
                log.warn("Sculptory: compacting " + pf.player + "'s history file failed; it is kept as it is", e);
                abandon();
                pf.compactRetryNanos = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
            }
            return true;
        }

        private void append(byte[] record, boolean apply) throws IOException {
            out.write(ByteBuffer.wrap(record), length);
            if (apply) {
                int payload = record.length - Journal.FRAME_BYTES;
                Journal.Type type = Journal.Type.of(record[4]);
                if (type == Journal.Type.ENTITIES) copiedEntities = true;
                state.apply(Journal.decode(type, record, payload), new JournalState.Ref(length, record.length));
            }
            length += record.length;
        }

        /** Copies the records appended since the start, forces the copy and moves it over the file. */
        private void finish(StorageIo.File source) throws IOException {
            long position = start;
            while (position < pf.length) {
                byte[] frame = new byte[Journal.FRAME_BYTES];
                readFully(source, ByteBuffer.wrap(frame), position);
                int payload = Journal.getInt(frame, 0);
                byte[] record = readRecord(source, new JournalState.Ref(position, Journal.FRAME_BYTES + payload));
                append(record, true);
                position += record.length;
            }
            // Entity records copied (appended meanwhile) need the flag the copy's header was written without, also when
            // their entry was aborted, evicted or dropped since: an older build must not cut the file at such a record.
            if (copiedEntities && (headerFlags & Journal.FLAG_ENTITIES) == 0) {
                headerFlags |= Journal.FLAG_ENTITIES;
                out.write(ByteBuffer.wrap(Journal.header(pf.player, headerFlags)), 0);
            }
            out.force();
            out.close();
            out = null;
            dirty.remove(pf);
            closeFile(pf);
            io.replace(temp, pf.path);
            long before = pf.length;
            pf.state = state;
            pf.length = length;
            pf.flags = headerFlags;
            pf.generation++;
            pf.compacting = false;
            log.info(String.format(Locale.ROOT, "Sculptory: compacted %s's history file from %d to %d bytes",
                    pf.player, before, length));
        }

        @Override
        void abandon() {
            pf.compacting = false;
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Deleted below.
                }
                out = null;
            }
            try {
                io.delete(temp);
            } catch (IOException ignored) {
                // Deleted at the next start.
            }
        }
    }

    /**
     * A rewrite request reached its turn: the player's later items wait (they are appended to the new file) while the
     * file is written anew in the background.
     */
    private void startRewrite(RewriteRequest request) {
        PlayerFile pf = file(request.player);
        if (pf == null) {
            events.add(new RewrittenAt(request.player, request.seq, false, "the history file is locked"));
            return;
        }
        if (pf.unreadable) {
            // Its content is not known here, so it must not be replaced: the load that reads it comes first.
            events.add(new RewrittenAt(request.player, request.seq, false, "the history file cannot be read yet"));
            return;
        }
        pf.rewriting = true;
        pf.generation++; // stops a compaction of the old file
        setBlocked(request.player, true);
        schedule(new RewriteTask(pf, request));
    }

    /**
     * Writes a player's file anew from a snapshot (records from memory, or copied from the old file), about
     * {@link Settings#stepBytes} per step, then moves it over the file. The player's items wait meanwhile; nobody
     * else's do. An entry whose data cannot be saved (a section too large for a record) is aborted in the copy and left
     * out; an empty snapshot deletes the file.
     */
    private final class RewriteTask extends Task {
        final PlayerFile pf;
        final RewriteRequest request;
        final Path temp;
        final JournalState state;
        /** The records to write, one piece each, in order. */
        final ArrayDeque<Piece> plan = new ArrayDeque<>();
        /** Entries given up (their later pieces are skipped). */
        final Set<UUID> skipped = new HashSet<>();
        StorageIo.File out;
        long length;
        int kept;
        int applied;
        /** The copy's header flags: {@link Journal#FLAG_ENTITIES} when a planned entry holds entities. */
        int flags;

        RewriteTask(PlayerFile pf, RewriteRequest request) {
            this.pf = pf;
            this.request = request;
            this.temp = pf.path.resolveSibling(pf.path.getFileName() + REWRITE_TEMP);
            this.state = new JournalState(pf.player);
            for (int i = 0; i < request.stack.size(); i++) {
                SnapshotEntry entry = request.stack.get(i);
                boolean undoable = i < request.applied;
                planEntry(entry, true);
                plan.add(() -> {
                    if (skipped.contains(entry.id()) || !state.entries.containsKey(entry.id())) return null;
                    kept++;
                    if (undoable) applied++;
                    return Journal.frame(Journal.Type.PUSH, Journal.mark(entry.id()));
                });
            }
            plan.add(() -> {
                // Undo marks for the redo side, newest first: made here, once every push is written.
                for (int i = state.stack.size() - 1; i >= applied; i--) {
                    write(Journal.frame(Journal.Type.UNDONE, Journal.mark(state.stack.get(i).id)));
                }
                return null;
            });
            for (SnapshotEntry entry : request.pending) planEntry(entry, true);
            for (SnapshotEntry entry : request.open) planEntry(entry, false);
        }

        /** Plans an entry's begin, its sections and (when {@code seal}) its seal, from memory or from the old file. */
        private void planEntry(SnapshotEntry entry, boolean seal) {
            UUID id = entry.id();
            if (entry.record() == null) {
                JournalState.Entry old = pf.state.entries.get(id);
                if (old == null || !old.sealed() || !pf.exists) {
                    skipped.add(id);
                    return;
                }
                plan.add(() -> copy(old.begin));
                for (JournalState.SectionRef section : old.sections.values()) {
                    if (section.cells() > 0) plan.add(() -> skipped.contains(id) ? null : copy(section.ref()));
                }
                if (!old.entities.isEmpty()) flags = Journal.FLAG_ENTITIES;
                for (JournalState.EntitiesRef batch : old.entities) {
                    plan.add(() -> skipped.contains(id) ? null : copy(batch.ref()));
                }
                plan.add(() -> skipped.contains(id) ? null : copy(old.seal));
                return;
            }
            BlockBuffer before = entry.record().before();
            BlockBuffer after = entry.record().after();
            if (entry.record().isEmpty()) {
                skipped.add(id);
                return;
            }
            plan.add(() -> Journal.frame(Journal.Type.BEGIN,
                    Journal.begin(id, entry.createdMillis(), entry.world(), entry.label())));
            for (long key : before.sortedKeys()) {
                if (before.section(key).isEmpty()) continue;
                plan.add(() -> {
                    if (skipped.contains(id)) return null;
                    SectionBuffer b = before.section(key);
                    try {
                        byte[] body = SectionCodec.encode(b, after.section(key), codec);
                        return Journal.frame(Journal.Type.SECTION,
                                Journal.section(id, key, codec.dataVersion(), b.presentCount(), body));
                    } catch (IllegalArgumentException | IllegalStateException e) {
                        log.warn("Sculptory: a step of " + pf.player + "'s undo history is too large to save ("
                                + e.getMessage() + "); it can be undone until the server stops", null);
                        skipped.add(id);
                        return Journal.frame(Journal.Type.ABORT, Journal.mark(id));
                    }
                });
            }
            List<EntityChange> entities = entry.record().entities();
            if (!entities.isEmpty()) {
                flags = Journal.FLAG_ENTITIES;
                List<List<EntityChange>> batches;
                try {
                    batches = EntityCodec.batches(entities);
                } catch (IllegalArgumentException e) {
                    batches = List.of(entities); // refused below, when its turn comes, like a section too large
                }
                for (List<EntityChange> batch : batches) {
                    plan.add(() -> {
                        if (skipped.contains(id)) return null;
                        try {
                            byte[] payload = Journal.entities(id, codec.dataVersion(), batch.size(),
                                    EntityCodec.encode(batch));
                            return Journal.frame(Journal.Type.ENTITIES, payload);
                        } catch (IllegalArgumentException | IllegalStateException e) {
                            log.warn("Sculptory: a step of " + pf.player + "'s undo history is too large to save ("
                                    + e.getMessage() + "); it can be undone until the server stops", null);
                            skipped.add(id);
                            return Journal.frame(Journal.Type.ABORT, Journal.mark(id));
                        }
                    });
                }
            }
            if (seal) {
                plan.add(() -> skipped.contains(id) ? null : Journal.frame(Journal.Type.SEAL,
                        Journal.seal(id, entry.label(), entry.createdMillis(), entry.bytes())));
            }
        }

        private byte[] copy(JournalState.Ref ref) throws IOException {
            return readRecord(ensureOpen(pf), ref);
        }

        @Override
        boolean urgent() {
            return true; // the player's records wait for it
        }

        @Override
        boolean step() {
            try {
                if (out == null) {
                    io.delete(temp);
                    out = io.open(temp);
                    out.truncate(0);
                    out.write(ByteBuffer.wrap(Journal.header(pf.player, flags)), 0);
                    length = Journal.HEADER_BYTES;
                }
                long stop = length + settings.stepBytes();
                while (!plan.isEmpty() && length < stop) {
                    byte[] record = plan.pollFirst().make();
                    if (record != null) write(record);
                }
                if (!plan.isEmpty()) return false;
                finish();
            } catch (IOException | RuntimeException e) {
                failed(e);
            }
            return true;
        }

        /** Writes one record to the copy and applies it to the copy's state. */
        private void write(byte[] record) throws IOException {
            out.write(ByteBuffer.wrap(record), length);
            Journal.Type type = Journal.Type.of(record[4]);
            state.apply(Journal.decode(type, record, record.length - Journal.FRAME_BYTES),
                    new JournalState.Ref(length, record.length));
            length += record.length;
        }

        private void finish() throws IOException {
            out.force();
            out.close();
            out = null;
            dirty.remove(pf);
            closeFile(pf);
            if (state.isEmpty()) {
                io.delete(temp);
                io.delete(pf.path);
                pf.exists = false;
                pf.length = 0;
            } else {
                io.replace(temp, pf.path);
                pf.exists = true;
                pf.length = length;
            }
            pf.flags = pf.exists ? flags : 0;
            pf.state = state;
            pf.broken = false;
            pf.rewriting = false;
            pf.generation++;
            pf.unpersistable.clear();
            pf.unpersistable.addAll(skipped);
            for (JournalState.Entry entry : state.entries.values()) {
                events.add(new Stored(pf.player, entry.id, entry.diskBytes()));
            }
            events.add(new RewrittenAt(request.player, request.seq, true, kept < request.stack.size()
                    ? (request.stack.size() - kept) + " step(s) could not be saved" : null));
            log.info("Sculptory: saved " + pf.player + "'s undo history again (" + kept + " steps)");
            setFailing(pf.player, false);
            setBlocked(pf.player, false);
        }

        private void failed(Exception e) {
            log.warn("Sculptory: saving " + pf.player + "'s undo history again failed; the old file is kept", e);
            cleanUp();
            pf.broken = true;
            pf.rewriting = false;
            setFailing(pf.player, true);
            setBlocked(pf.player, false);
            events.add(new RewrittenAt(request.player, request.seq, false, String.valueOf(e.getMessage())));
        }

        private void cleanUp() {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Deleted below.
                }
                out = null;
            }
            try {
                io.delete(temp);
            } catch (IOException ignored) {
                // Deleted at the next start.
            }
        }

        @Override
        void abandon() {
            cleanUp();
            pf.rewriting = false;
            setBlocked(pf.player, false);
        }
    }

    /** One record of a rewrite, made when its turn comes; null writes nothing. */
    @FunctionalInterface
    private interface Piece {
        byte[] make() throws IOException;
    }
}
