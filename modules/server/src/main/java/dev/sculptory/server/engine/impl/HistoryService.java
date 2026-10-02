package dev.sculptory.server.engine.impl;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryLimits;
import dev.sculptory.core.history.PlayerHistory;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.core.history.TrailFold;
import dev.sculptory.core.history.store.HistoryStore;
import dev.sculptory.core.history.store.StoredHistory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every player's undo history: one {@link PlayerHistory} per player, with a global byte
 * cap across players, saved to disk through a {@link HistoryStore} when {@link Persistence} is given.
 * Server thread only; no Minecraft types.
 *
 * <p><b>One history operation at a time per player.</b> An undo or redo is in flight from its admission
 * ({@link #begin}) until its job finishes ({@link #finish}). Meanwhile:
 * <ul>
 *   <li>pushes for that player are deferred, then applied in order right after the undo or redo is marked, so an
 *       applied redo never loses its entry;</li>
 *   <li>a second undo or redo is refused by the caller ({@link Session#busy()}), not queued: the client paces
 *       repeated undo/redo on {@code JobFinished} anyway;</li>
 *   <li>the global cap never evicts that player's entries, so the in-flight entry stays the candidate.</li>
 * </ul>
 * An undo or redo may not begin while an edit of the same player is running ({@link #editStarted} without its
 * {@link #editFinished}; {@link Session#editsRunning()}): that edit's entry would be pushed after the undo and
 * clear the entry just undone. So every push deferred behind an undo or redo comes from an edit admitted after
 * it, a genuinely newer edit, and clearing the redo side then is the normal "new edit clears redo" rule.
 *
 * <p><b>Caps.</b> Each player's {@link PlayerHistory} applies {@link HistoryLimits#maxEntries()} and
 * {@link HistoryLimits#maxBytesPerPlayer()}. After every push this service enforces
 * {@link HistoryLimits#maxBytesTotal()} by evicting the oldest entry (by creation time) across players until the
 * total fits. The total includes deferred pushes (they cannot be evicted before they land, so others' entries
 * go first) and, when saving, the saved histories of players who are not loaded (offline). Evictions are reported per
 * player so a notice can say "oldest N steps discarded". Records still being built (running jobs, open strokes) are
 * not counted; the edit service bounds an open stroke's record.
 *
 * <p><b>Sessions.</b> A player's history lives in a {@link Session} created on first use. Without persistence it is
 * dropped by {@link #clear} (disconnect): work started in that session (a job still running when the player left)
 * finds it closed and its push is dropped. With persistence, a new session first {@linkplain Session#loading() loads}
 * the player's saved history (pushes wait, undo and redo are refused as busy), {@link #clear} only marks it offline,
 * and it is unloaded (kept as {@link StoredHistory} metadata only) once nothing of the player is running or waiting and
 * its journal is in sync; a player who comes back before that finds it as they left it.
 *
 * <p><b>Saving</b> (with persistence). Every change is journaled as it happens: an entry's data (begin, sections,
 * seal) and each stack operation (push, undo, redo, eviction). Records being built are {@link OpenRecord}s: a job's
 * sections are saved as it finishes them, jobs' open records' changed sections at least every
 * {@value #SAVE_OPEN_TICKS} ticks ({@link #saveOpen}; a stroke's record is saved by its owner, a few sections a tick,
 * with {@link #saveSection}) and, for a chunk about to be saved by the game, before it ({@link #saveOpen(String, int,
 * int)}), so a crash leaves what was written undoable. When an entry is pushed, the sections of its open record changed
 * since they were last saved are journaled from the entry's record as built. An entry that grows afterwards (what its
 * fluid did folded in: {@link #foldTrail} at a chunk save, before the history is unloaded, at a stop, and by a step)
 * has the sections that changed journaled again and is sealed again ({@link #replace}). A player whose journal fell out
 * of sync (a failed write, a full queue) is rewritten from memory ({@link #poll}).
 *
 * <p><b>Runs (Undo anyway).</b> Each session keeps its current {@link Run}: the consecutive undo (or redo) steps the
 * player made since their last other history change, in the order they were applied, and the conflicts they skipped.
 * A completed step in the run's direction extends it; one in the other direction starts a new run. Anything else that
 * changes the history ends it: a push (deferred ones included), an eviction of any of the player's entries (the client
 * sees every eviction in its history state, so both sides agree on the run), a step that did not complete (a cancelled
 * or failed undo or redo), a completed overwrite of the run, and leaving. {@link #beginOverwrite} re-applies the run
 * ({@code HistoryPrograms.reapply}) as one more history operation: in flight like an undo, it moves no entry, and a
 * cancelled or failed overwrite keeps the run so it can be tried again. Runs are not saved.
 */
public final class HistoryService {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    /** Open records' changed sections are saved at least this often, in {@link #poll} calls (server ticks). */
    public static final int SAVE_OPEN_TICKS = 100;
    /** After this long loading, the player may edit; their saved steps are merged underneath when they arrive. */
    static final long LOAD_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);
    /** How long after a failed rewrite a player's journal is rewritten again. */
    static final long REWRITE_RETRY_NANOS = TimeUnit.SECONDS.toNanos(30);
    /** How often offline histories are checked against the age limit. */
    static final long EXPIRE_EVERY_NANOS = TimeUnit.MINUTES.toNanos(10);
    /** Longest a chunk save waits for the history writer ({@link #barrier}). */
    static final long BARRIER_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    /** After a barrier timed out, chunk saves do not wait for this long. */
    static final long BARRIER_PAUSE_NANOS = TimeUnit.SECONDS.toNanos(10);

    public enum Op { UNDO, REDO }

    /**
     * Where what an entry's fluid did since its last step comes from ({@code FluidTrails}, through the edit service):
     * taken to be folded into the entry when its history is unloaded or the server stops.
     */
    @FunctionalInterface
    public interface TrailSource {
        TrailSource NONE = entry -> null;

        /** Removes and returns what {@code entry}'s fluid changed since it was last taken or folded, or null. */
        EditRecord drain(HistoryEntry entry);
    }

    /** Change notifications, on the server thread. */
    public interface Listener {
        Listener NONE = new Listener() {
            @Override
            public void changed(UUID player) {}

            @Override
            public void evicted(UUID player, int steps, boolean includesNewest) {}
        };

        /** The player's stack changed (push, undo, redo, eviction, or their saved history was loaded). */
        void changed(UUID player);

        /** {@code steps} entries were evicted; {@code includesNewest} when the entry just pushed was one of them. */
        void evicted(UUID player, int steps, boolean includesNewest);
    }

    /**
     * The undo (or redo) steps a player made in a row since their last other history change.
     *
     * @param entries the entries in the order their steps were applied (never empty)
     * @param conflicts the cells those steps skipped because they had changed since
     */
    public record Run(Op op, List<HistoryEntry> entries, long conflicts) {
        public Run {
            Objects.requireNonNull(op);
            entries = List.copyOf(entries);
            if (entries.isEmpty()) throw new IllegalArgumentException("Empty run");
        }
    }

    /**
     * Saving history to disk.
     *
     * @param maxDiskBytes what the journal files may hold together: live data is kept under half of it, the rest is room
     *     for dead records until files are compacted
     * @param maxAgeMillis entries older than this are dropped from offline histories and when a history is loaded; 0
     *     keeps them
     * @param wallClock the time entries' creation times are compared with ({@code System::currentTimeMillis})
     */
    public record Persistence(HistoryStore store, long maxDiskBytes, long maxAgeMillis, LongSupplier wallClock) {
        public Persistence {
            Objects.requireNonNull(store);
            Objects.requireNonNull(wallClock);
            if (maxDiskBytes < 1 || maxAgeMillis < 0) throw new IllegalArgumentException("Bad persistence limits");
        }
    }

    /**
     * A record being built by a running job or an open stroke, which becomes the entry {@link #entry()} (its builder's
     * id). Its changed sections are journaled while it is built, so a crash leaves what was written undoable.
     */
    public static final class OpenRecord {
        final Session session;
        final String world;
        final String label;
        final long created;
        final RecordBuilder builder;
        /** Its begin record was journaled. */
        boolean begun;
        /** A stroke's record: its owner saves it a few sections at a time, not {@link #saveOpen()}. */
        final boolean stroke;

        OpenRecord(Session session, String world, String label, long created, RecordBuilder builder, boolean stroke) {
            this.session = session;
            this.world = world;
            this.label = label;
            this.created = created;
            this.builder = builder;
            this.stroke = stroke;
        }

        public UUID entry() {
            return builder.id();
        }

        public RecordBuilder builder() {
            return builder;
        }
    }

    private record Deferred(HistoryEntry entry, long bytes) {}

    /** One player's history, resident in memory. */
    public static final class Session {
        private final UUID player;
        private PlayerHistory history;
        private final ArrayDeque<Deferred> deferred = new ArrayDeque<>();
        private long deferredBytes;
        private int editsRunning;
        private Op inFlightOp;
        private UUID inFlightEntry;
        /** The operation in flight is an overwrite of the run ({@link #beginOverwrite}), not a step. */
        private boolean inFlightOverwrite;
        private Run run;
        private boolean closed;
        /** The player is connected (sessions of players who left linger until they can be unloaded). */
        private boolean online = true;
        /** Pushes wait and undo is refused until the saved history is read back (or that takes too long). */
        private boolean loading;
        private long loadingSince;
        /**
         * The saved history has not been merged in yet (it may still arrive after the session stopped waiting for it).
         * Until it is, the journal holds steps this session does not, so it is never rewritten from memory nor unloaded.
         */
        private boolean loadPending;
        /** A load that arrived while an undo or redo was in flight: merged when it ends. */
        private HistoryStore.Loaded arrived;
        /**
         * Entries pushed while the saved history was not merged yet ({@link #loadPending}): the journal holds their
         * records after the saved ones, so the merge never adds them from the load and drops the saved redo side if
         * there are any (whatever happened to them since: undone, dropped by a later push, evicted).
         */
        private final java.util.Set<UUID> pushedWhileLoading = new java.util.HashSet<>();
        /** Journal sizes of the entries, as the store reported them. */
        private final Map<UUID, Long> diskBytes = new HashMap<>();

        private Session(UUID player, HistoryLimits limits) {
            this.player = player;
            this.history = new PlayerHistory(limits);
        }

        public UUID player() {
            return player;
        }

        /** An undo or redo is in flight, or the saved history is still loading. */
        public boolean busy() {
            return inFlightOp != null || loading;
        }

        /** The player's saved history is being read back; pushes wait and undo and redo are refused. */
        public boolean loading() {
            return loading;
        }

        /** Dropped by {@link #clear} (or unloaded); pushes and finishes for it are ignored. */
        public boolean closed() {
            return closed;
        }

        /** Pushes waiting for the in-flight undo or redo. */
        public int deferredCount() {
            return deferred.size();
        }

        /** Edits started ({@link #editStarted}) whose entry has not been pushed or abandoned yet. */
        public int editsRunning() {
            return editsRunning;
        }

        /** The current run of undo (or redo) steps, if any. */
        public Optional<Run> run() {
            return Optional.ofNullable(run);
        }

        /** An overwrite of the run is in flight. */
        public boolean overwriting() {
            return inFlightOverwrite;
        }

        /** The player is connected. */
        public boolean online() {
            return online;
        }
    }

    private final HistoryLimits limits;
    private final Listener listener;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final Persistence persistence;
    /** Null when history is not saved, or once the store stopped working. */
    private HistoryStore store;
    /** Saved histories of players without a session. */
    private final Map<UUID, StoredHistory> offline = new LinkedHashMap<>();
    /** Records being built, by entry id. */
    private final Map<UUID, OpenRecord> open = new LinkedHashMap<>();
    /** When a failed rewrite of a player's journal may be tried again. */
    private final Map<UUID, Long> rewriteRetry = new HashMap<>();
    private final LongSupplier nanos;
    private int ticksSinceSave;
    private long nextExpireNanos;
    private long barrierPausedUntil;
    /** The store's start-up scan was reported: {@link #offline} holds every saved history without a session. */
    private boolean scanned;
    /** Players the store reported a file for that it could not read (they are always loaded, never assumed empty). */
    private final java.util.Set<UUID> unreadAtScan = new java.util.HashSet<>();
    /** Players whose chunk-save protection was last reported missing, and when (rate-limits the warning). */
    private final Map<UUID, Long> unprotectedLogged = new HashMap<>();
    private TrailSource trails = TrailSource.NONE;

    public HistoryService(HistoryLimits limits, Listener listener) {
        this(limits, listener, null);
    }

    /** With {@code persistence} (null: memory only). */
    public HistoryService(HistoryLimits limits, Listener listener, Persistence persistence) {
        this(limits, listener, persistence, System::nanoTime);
    }

    HistoryService(HistoryLimits limits, Listener listener, Persistence persistence, LongSupplier nanos) {
        this.limits = Objects.requireNonNull(limits);
        this.listener = listener == null ? Listener.NONE : listener;
        this.persistence = persistence;
        this.store = persistence == null ? null : persistence.store();
        this.nanos = Objects.requireNonNull(nanos);
        this.nextExpireNanos = nanos.getAsLong() + EXPIRE_EVERY_NANOS;
    }

    public HistoryLimits limits() {
        return limits;
    }

    /** Where fluid trails are drained from when a history is unloaded or the server stops ({@link TrailSource}). */
    public void trailSource(TrailSource source) {
        this.trails = Objects.requireNonNull(source);
    }

    /** History is being saved to disk (persistence was given and the store still works). */
    public boolean persistent() {
        return store != null && store.usable();
    }

    /** The store, if history is saved. */
    public Optional<HistoryStore> store() {
        return Optional.ofNullable(store);
    }

    /** The player's current session, created if needed (loading their saved history first when saving). */
    public Session session(UUID player) {
        Objects.requireNonNull(player);
        Session session = sessions.get(player);
        if (session != null) {
            session.online = true;
            return session;
        }
        session = new Session(player, limits);
        sessions.put(player, session);
        // Once the start-up scan is known, a player without a saved history needs nothing loaded (a file the scan could
        // not read is not in its report, so its player is loaded too).
        if (persistent() && (offline.remove(player) != null || !scanned || unreadAtScan.contains(player))) {
            // The saved history comes back through poll(); until then pushes wait and undo is refused.
            session.loading = true;
            session.loadPending = true;
            session.loadingSince = nanos.getAsLong();
            store.load(player);
        }
        return session;
    }

    public Optional<Session> find(UUID player) {
        return Optional.ofNullable(sessions.get(player));
    }

    /**
     * The player left. Without persistence their history is dropped (a running undo/redo or job of that session is
     * ignored later). With it the session is kept until nothing of theirs runs or waits and its journal is in sync,
     * then unloaded; their Undo anyway run ends.
     */
    public void clear(UUID player) {
        if (!persistent()) {
            Session session = sessions.remove(player);
            if (session != null) session.closed = true;
            return;
        }
        Session session = sessions.get(player);
        if (session == null) return;
        session.online = false;
        session.run = null;
        maybeUnload(session);
    }

    /**
     * Server stop: what the entries' fluid did since it was last folded goes into them and the journal, journals are
     * brought in sync from memory where they fell behind, then every session is dropped. The caller closes the store
     * afterwards (which writes what is queued).
     */
    public void clearAll() {
        if (persistent()) {
            for (Session session : sessions.values()) foldTrails(session);
            for (Session session : sessions.values()) {
                if (store.outOfSync(session.player)) rewrite(session);
            }
            for (StoredHistory history : offline.values()) {
                if (store.outOfSync(history.player())) rewriteOffline(history);
            }
        }
        for (Session session : sessions.values()) session.closed = true;
        sessions.clear();
        open.clear();
        offline.clear();
    }

    // ---------------------------------------------------------------------------------------------- pushes

    /**
     * Pushes a finished edit: deferred while the session has an undo or redo in flight (or is loading), dropped if the
     * session is closed. The record must not be empty.
     */
    public void push(Session session, HistoryEntry entry) {
        push(session, entry, null);
    }

    /**
     * {@link #push(Session, HistoryEntry)} of the entry built from {@code record} (its {@link OpenRecord}, or null):
     * sections the record already journaled are not written again.
     */
    public void push(Session session, HistoryEntry entry, OpenRecord record) {
        Objects.requireNonNull(entry);
        if (!isCurrent(session)) {
            LOG.debug("Sculptory: dropping history entry '{}' of a closed session", entry.label());
            if (record != null) {
                discard(record);
            } else {
                discard(entry.id());
            }
            return;
        }
        long bytes = entry.record().estimatedBytes();
        journalEntry(session, entry, record, bytes);
        if (session.busy()) {
            session.deferred.addLast(new Deferred(entry, bytes));
            session.deferredBytes += bytes;
            enforceTotal();
            return;
        }
        apply(session, entry, bytes);
        enforceTotal();
        listener.changed(session.player);
        maybeUnload(session);
    }

    /** An edit that will push an entry when it finishes (a job) was admitted. */
    public void editStarted(Session session) {
        if (isCurrent(session)) session.editsRunning++;
    }

    /**
     * {@link #editStarted(Session)} of a job building {@code record} (from {@link #record}, or null): from now on the
     * record is saved while the job writes.
     */
    public void editStarted(Session session, OpenRecord record) {
        editStarted(session);
        if (record != null && isCurrent(session) && persistent()) open.put(record.entry(), record);
    }

    /**
     * The edit started with {@link #editStarted} finished: pushes its entry, or nothing when {@code entry} is
     * {@code null} (it changed nothing).
     */
    public void editFinished(Session session, HistoryEntry entry) {
        editFinished(session, entry, null);
    }

    /** {@link #editFinished(Session, HistoryEntry)} of the entry built from {@code record} (or null). */
    public void editFinished(Session session, HistoryEntry entry, OpenRecord record) {
        if (session.editsRunning > 0) session.editsRunning--;
        if (entry != null) {
            push(session, entry, record);
        } else {
            if (record != null) discard(record);
            maybeUnload(session);
        }
    }

    // ---------------------------------------------------------------------------------------------- open records

    /**
     * The record a job of the player will build, saved while it is built once the job is admitted
     * ({@link #editStarted(Session, OpenRecord)}); null when history is not saved. {@code label} is its base label
     * ("Fill"), used if a crash interrupts it.
     */
    public OpenRecord record(Session session, String world, String label, LongSupplier createdMillis,
                             RecordBuilder builder) {
        if (!persistent() || !isCurrent(session)) return null;
        return new OpenRecord(session, world, label, createdMillis.getAsLong(), builder, false);
    }

    /**
     * The record an open stroke builds ({@code builder}), registered on first use so it is saved while it is built;
     * null when history is not saved. The same builder gives the same record.
     */
    public OpenRecord openRecord(Session session, String world, String label, LongSupplier createdMillis,
                                 RecordBuilder builder) {
        if (!persistent() || !isCurrent(session)) return null;
        OpenRecord record = open.get(builder.id());
        if (record == null) {
            record = new OpenRecord(session, world, label, createdMillis.getAsLong(), builder, true);
            open.put(builder.id(), record);
        }
        return record;
    }

    /** {@code record} will never become an entry: forgets it, and journals that if it had begun. */
    public void discard(OpenRecord record) {
        if (record == null) return;
        open.remove(record.entry());
        if (record.begun && persistent()) store.abort(record.session.player, record.entry());
        record.begun = false;
    }

    /** {@link #discard(OpenRecord)} of the open record of entry {@code entry}, if there is one. */
    public void discard(UUID entry) {
        OpenRecord record = open.get(entry);
        if (record != null) discard(record);
    }

    /**
     * A {@link RecordSink} recording into {@code builder} that also saves each section as the job finishes it (only
     * {@code RecordSink.into(builder)} when {@code record} is null).
     */
    public RecordSink sink(RecordBuilder builder, OpenRecord record) {
        RecordSink plain = RecordSink.into(builder);
        if (record == null) return plain;
        return new RecordSink() {
            @Override
            public void record(int x, int y, int z, int before, BlockEntityData beforeTile, int after,
                               BlockEntityData afterTile) {
                builder.record(x, y, z, before, beforeTile, after, afterTile);
            }

            @Override
            public void sectionFinished(long key) {
                builder.prepare(key);
                saveSection(record, key);
            }

            @Override
            public void entity(UUID id, EntityState before, EntityState after) {
                builder.recordEntity(id, before, after);
            }

            @Override
            public void entitiesFinished() {
                saveEntities(record);
            }
        };
    }

    /**
     * Journals the entities of an open record recorded since they were last saved (a job calls this after each chunk
     * column of entity work, so a crash leaves them undoable, and a chunk save of theirs waits for them).
     */
    public void saveEntities(OpenRecord record) {
        if (record == null || !persistent() || !open.containsKey(record.entry())) return;
        if (!record.builder.hasDirtyEntities()) return;
        begin(record);
        store.entities(record.session.player, record.entry(), record.builder.drainEntities());
    }

    /** Journals one section of an open record if it changed since it was last saved. */
    public void saveSection(OpenRecord record, long key) {
        if (!persistent() || !open.containsKey(record.entry())) return;
        if (!record.builder.takeDirty(key)) return;
        journalSection(record, key);
    }

    /** Journals every section and entity of an open record that changed since it was last saved. */
    public void save(OpenRecord record) {
        if (record == null || !persistent()) return;
        if (record.builder.hasDirty()) {
            for (long key : record.builder.drainDirty()) journalSection(record, key);
        }
        saveEntities(record);
    }

    /** Journals the changed sections of an open record in chunk column (cx, cz). */
    public void save(OpenRecord record, int cx, int cz) {
        if (record == null || !persistent() || !record.builder.dirtyIn(cx, cz)) return;
        for (long key : record.builder.drainDirty(cx, cz)) journalSection(record, key);
    }

    /**
     * Journals the changed sections of every registered open record of a job (the periodic save). Strokes' records are
     * left to their owner, which saves them a few sections a tick: a large stroke's would take long in one tick.
     */
    public void saveOpen() {
        if (!persistent()) return;
        for (OpenRecord record : open.values()) {
            if (!record.stroke) save(record);
        }
    }

    /**
     * Journals the changed sections in chunk column (cx, cz) of {@code world} of every registered open record, before
     * the game saves that chunk.
     *
     * @return the players whose records had changed sections there (empty when nothing was journaled)
     */
    public java.util.Set<UUID> saveOpen(String world, int cx, int cz) {
        if (!persistent()) return java.util.Set.of();
        java.util.Set<UUID> players = new java.util.HashSet<>();
        for (OpenRecord record : open.values()) {
            if (!record.world.equals(world)) continue;
            if (record.builder.dirtyIn(cx, cz)) {
                save(record, cx, cz);
                players.add(record.session.player);
            }
            // Entities are saved as each column of entity work ends; anything left goes now (its chunk may be this).
            if (record.builder.hasDirtyEntities()) {
                saveEntities(record);
                players.add(record.session.player);
            }
        }
        return players;
    }

    private void journalSection(OpenRecord record, long key) {
        UUID player = record.session.player;
        begin(record);
        SectionBuffer[] pair = record.builder.snapshotSection(key);
        store.section(player, record.entry(), key, pair == null ? null : pair[0], pair == null ? null : pair[1]);
    }

    private void begin(OpenRecord record) {
        if (record.begun) return;
        record.begun = true;
        store.begin(record.session.player, record.entry(), record.created, record.world, record.label);
    }

    /** Journals an entry's data and seal when it is pushed (or deferred), unless it was journaled while built. */
    private void journalEntry(Session session, HistoryEntry entry, OpenRecord record, long bytes) {
        if (!persistent()) return;
        OpenRecord built = record != null ? record : open.get(entry.id());
        if (built != null) open.remove(built.entry());
        if (built != null && built.begun && built.entry().equals(entry.id())) {
            // The sections changed since they were last saved, as the entry holds them (the builder would build them
            // again: building the entry took its prepared parts).
            BlockBuffer before = entry.record().before();
            BlockBuffer after = entry.record().after();
            for (long key : built.builder.drainDirty()) {
                SectionBuffer b = before.section(key);
                boolean kept = b != null && !b.isEmpty();
                store.section(session.player, entry.id(), key, kept ? b : null, kept ? after.section(key) : null);
            }
            if (built.builder.hasDirtyEntities()) {
                store.entities(session.player, entry.id(), built.builder.drainEntities());
            }
        } else {
            store.begin(session.player, entry.id(), entry.createdMillis(), entry.world(), entry.label());
            BlockBuffer before = entry.record().before();
            BlockBuffer after = entry.record().after();
            for (long key : before.sortedKeys()) {
                if (!before.section(key).isEmpty()) {
                    store.section(session.player, entry.id(), key, before.section(key), after.section(key));
                }
            }
            store.entities(session.player, entry.id(), entry.record().entities());
        }
        store.seal(session.player, entry.id(), entry.label(), entry.createdMillis(), bytes);
    }

    // ---------------------------------------------------------------------------------------------- undo and redo

    /** The entry the next undo or redo would apply, if any. */
    public Optional<HistoryEntry> candidate(Session session, Op op) {
        return op == Op.UNDO ? session.history.undoCandidate() : session.history.redoCandidate();
    }

    /**
     * Marks an undo or redo of {@code entry} as in flight; call once its job is admitted.
     *
     * @throws IllegalStateException if one is already in flight, the history is loading, an edit of the player is
     *     still running, or {@code entry} is not the candidate
     */
    public void begin(Session session, Op op, HistoryEntry entry) {
        Objects.requireNonNull(op);
        if (session.busy()) throw new IllegalStateException("An undo or redo is already in flight");
        if (session.editsRunning > 0) throw new IllegalStateException("An edit is still running");
        HistoryEntry expected = candidate(session, op).orElse(null);
        if (expected == null || !expected.id().equals(entry.id())) {
            throw new IllegalStateException("Not the " + op + " candidate: " + entry.label());
        }
        session.inFlightOp = op;
        session.inFlightEntry = entry.id();
        session.inFlightOverwrite = false;
        // A redo that stops with the server may have written cells: recovery then counts it as redone.
        if (op == Op.REDO && persistent()) store.redoBegin(session.player, entry.id());
    }

    /**
     * Ends the in-flight undo or redo. When {@code applied} its entry moves to the other side; otherwise the
     * stack stays as it was. The edit service passes {@code applied} for a completed undo, and for a redo that
     * completed or changed anything: a partly applied redo must become undoable (its undo skips the cells still
     * at "before"), while a partly applied undo stays the undo candidate (running it again finishes it). Then
     * deferred pushes are applied and the global cap enforced. The step counts as completed when applied, with no
     * conflicts ({@link #finish(Session, boolean, boolean, long)}).
     */
    public void finish(Session session, boolean applied) {
        finish(session, applied, applied, 0);
    }

    /**
     * {@link #finish(Session, boolean)} for a step whose job {@code completed} (not cancelled or failed) after skipping
     * {@code conflicts} cells. A completed, applied step extends the run in its direction (or starts a new one); any
     * other end of a step ends the run. An in-flight overwrite ends through {@link #finishOverwrite} instead.
     */
    public void finish(Session session, boolean applied, boolean completed, long conflicts) {
        if (session.inFlightOp == null) return;
        if (session.inFlightOverwrite) {
            finishOverwrite(session, completed);
            return;
        }
        Op op = session.inFlightOp;
        UUID entry = session.inFlightEntry;
        session.inFlightOp = null;
        session.inFlightEntry = null;
        if (!isCurrent(session)) return;
        boolean marked = false;
        if (applied) {
            marked = op == Op.UNDO ? session.history.markUndone(entry) : session.history.markRedone(entry);
            if (!marked) {
                // PlayerHistory: never retry or re-run; leave the stack and send a fresh state.
                LOG.error("Sculptory internal error: {} of history entry {} finished but it is no longer the "
                        + "candidate; the stack is left unchanged", op, entry);
            }
        }
        if (persistent()) {
            if (marked) {
                if (op == Op.UNDO) {
                    store.undone(session.player, entry);
                } else {
                    store.redone(session.player, entry);
                }
            } else if (op == Op.REDO) {
                store.redoAbort(session.player, entry);
            }
        }
        if (marked && completed) {
            extendRun(session, op, entry, conflicts);
        } else {
            session.run = null;
        }
        afterOperation(session);
    }

    /**
     * Why the player's run cannot be overwritten as {@code op} of {@code steps} steps, or {@code null} when it can: the run must be {@code steps} {@code op} steps, have skipped
     * conflicts, lie in one world, and its entries must still be the ones next to the position (the nearest
     * {@code steps} entries of the other side, the last one applied nearest).
     */
    public String overwriteRefusal(Session session, Op op, int steps) {
        Run run = session.run;
        if (run == null || run.op() != op || run.entries().size() != steps) {
            return "the history changed since those " + (op == Op.UNDO ? "undo" : "redo") + " steps";
        }
        if (run.conflicts() <= 0) return "those steps kept no changed blocks";
        List<HistoryEntry> side = op == Op.UNDO ? session.history.redoEntries() : session.history.undoEntries();
        if (side.size() < steps) return "a step of that run is no longer in the history";
        for (int i = 0; i < steps; i++) {
            if (!side.get(i).id().equals(run.entries().get(steps - 1 - i).id())) {
                return "a step of that run is no longer in the history";
            }
        }
        String world = run.entries().get(0).world();
        for (HistoryEntry entry : run.entries()) {
            if (!entry.world().equals(world)) return "those steps were made in more than one world";
        }
        return null;
    }

    /**
     * Marks an overwrite of the player's run as in flight (one history operation at a time); call once its job is
     * admitted, after {@link #overwriteRefusal} found nothing to refuse.
     *
     * @return the run it overwrites
     * @throws IllegalStateException if an operation is in flight, an edit is still running or there is no run
     */
    public Run beginOverwrite(Session session) {
        if (session.busy()) throw new IllegalStateException("An undo or redo is already in flight");
        if (session.editsRunning > 0) throw new IllegalStateException("An edit is still running");
        Run run = session.run;
        if (run == null) throw new IllegalStateException("No run to overwrite");
        session.inFlightOp = run.op();
        session.inFlightEntry = null;
        session.inFlightOverwrite = true;
        return run;
    }

    /**
     * Ends the in-flight overwrite. No entry moves. A {@code completed} overwrite ends the run (its conflicts are
     * resolved); a cancelled or failed one keeps it, so it can be tried again. Then deferred pushes are applied (each
     * ends the run) and the global cap enforced.
     */
    public void finishOverwrite(Session session, boolean completed) {
        if (session.inFlightOp == null || !session.inFlightOverwrite) return;
        session.inFlightOp = null;
        session.inFlightEntry = null;
        session.inFlightOverwrite = false;
        if (!isCurrent(session)) return;
        if (completed) session.run = null;
        afterOperation(session);
    }

    private static void extendRun(Session session, Op op, UUID entryId, long conflicts) {
        HistoryEntry entry = null;
        for (HistoryEntry e : op == Op.UNDO ? session.history.redoEntries() : session.history.undoEntries()) {
            if (e.id().equals(entryId)) {
                entry = e;
                break;
            }
        }
        if (entry == null) {
            session.run = null;
            return;
        }
        Run run = session.run;
        List<HistoryEntry> entries = new ArrayList<>();
        long skipped = Math.max(0, conflicts);
        if (run != null && run.op() == op) {
            entries.addAll(run.entries());
            skipped += run.conflicts();
        }
        entries.add(entry);
        session.run = new Run(op, entries, skipped);
    }

    /** After an undo, redo, overwrite or load ended: deferred pushes, the global cap, and a change notification. */
    private void afterOperation(Session session) {
        // Saved steps that arrived while the step ran go in now, under the new ones (before the waiting pushes).
        if (session.arrived != null && session.inFlightOp == null && isCurrent(session)) {
            merge(session, session.arrived);
        }
        while (!session.deferred.isEmpty()) {
            Deferred next = session.deferred.pollFirst();
            apply(session, next.entry(), next.bytes());
        }
        session.deferredBytes = 0;
        enforceTotal();
        listener.changed(session.player);
        maybeUnload(session);
    }

    // ---------------------------------------------------------------------------------------------- views

    /**
     * The player's history for the client; {@link HistorySnapshot#EMPTY} without a session. Its size leaves out what
     * folded fluid trails added ({@link PlayerHistory#pushedBytes()}): the client takes a change of it that no step of
     * its own explains for an eviction, which ends its Undo anyway run, and a fold is none.
     */
    public HistorySnapshot snapshot(UUID player) {
        Session session = sessions.get(player);
        if (session == null) return HistorySnapshot.EMPTY;
        List<HistoryEntry> undo = session.history.undoEntries();
        List<HistoryEntry> redo = session.history.redoEntries();
        return new HistorySnapshot(!undo.isEmpty(), !redo.isEmpty(), labels(undo), labels(redo),
                session.history.pushedBytes(), session.busy());
    }

    /** Undoable entries, next undo first (empty without a session). */
    public List<HistoryEntry> undoEntries(UUID player) {
        Session session = sessions.get(player);
        return session == null ? List.of() : session.history.undoEntries();
    }

    /** Redoable entries, next redo first (empty without a session). */
    public List<HistoryEntry> redoEntries(UUID player) {
        Session session = sessions.get(player);
        return session == null ? List.of() : session.history.redoEntries();
    }

    /**
     * {@code entry} with {@code trail} (what its fluid changed since its last step, {@code FluidTrails}) folded into its
     * record, toward its {@code before} (for an undo or Undo anyway) or its {@code after} ({@code TrailFold}); same id,
     * label and time. {@code entry} itself when there is no trail.
     */
    public static HistoryEntry withTrail(HistoryEntry entry, EditRecord trail, boolean towardBefore) {
        Objects.requireNonNull(entry);
        EditRecord folded = TrailFold.fold(entry.record(), trail, towardBefore);
        if (folded == entry.record()) return entry;
        return new HistoryEntry(entry.id(), entry.owner(), entry.world(), entry.label(), folded, entry.createdMillis());
    }

    /**
     * Puts {@code entry} (an entry {@link #withTrail} folded: once its step is admitted, or between steps by
     * {@link #foldTrail}) in place of the session's entry with its id, in the stack and in the current run. When saving,
     * the sections the fold changed are journaled again (the fold shares the others, so they are told by identity) and
     * the entry is sealed again with its new size: a later section of an entry replaces an earlier one, so after a
     * restart the entry reads back as it is now.
     */
    public void replace(Session session, HistoryEntry entry) {
        Objects.requireNonNull(entry);
        if (!isCurrent(session)) return;
        HistoryEntry old = entryIn(session.history, entry.id());
        if (old == null || !session.history.replace(entry)) return;
        Run run = session.run;
        if (run != null) {
            List<HistoryEntry> entries = new ArrayList<>(run.entries());
            entries.replaceAll(e -> e.id().equals(entry.id()) ? entry : e);
            session.run = new Run(run.op(), entries, run.conflicts());
        }
        if (!persistent() || old.record() == entry.record()) return;
        BlockBuffer before = entry.record().before(), after = entry.record().after();
        boolean changed = false;
        for (long key : before.sortedKeys()) {
            SectionBuffer section = before.section(key);
            if (section == old.record().before().section(key) || section.isEmpty()) continue;
            store.section(session.player, entry.id(), key, section, after.section(key));
            changed = true;
        }
        if (changed) {
            store.seal(session.player, entry.id(), entry.label(), entry.createdMillis(),
                    session.history.bytesOf(entry.id()));
        }
    }

    /**
     * Whether a trail of entry {@code id} can be folded into it now ({@link #foldTrail}): a current session holds it in
     * its stack, and no undo, redo or overwrite of that player is in flight (its entries' fluid is held still then,
     * and the step replaces the entry itself).
     */
    public boolean foldable(UUID id) {
        return holder(id) != null;
    }

    /**
     * Folds {@code trail} (what entry {@code id}'s fluid did since its last step or fold; the game is about to save the
     * chunk column it lies in) into the entry, toward its {@code before} when it is done and its {@code after} when it
     * is undone, as its next step would, and journals it ({@link #replace}).
     *
     * @return the player whose entry it is, or {@code null} when it is not {@link #foldable} (nothing changes)
     */
    public UUID foldTrail(UUID id, EditRecord trail) {
        Session session = holder(id);
        if (session == null) return null;
        boolean done = false;
        HistoryEntry entry = null;
        for (HistoryEntry e : session.history.undoEntries()) {
            if (e.id().equals(id)) {
                entry = e;
                done = true;
                break;
            }
        }
        if (entry == null) entry = entryIn(session.history, id);
        if (entry == null) return null;
        replace(session, withTrail(entry, trail, done));
        return session.player;
    }

    /**
     * Folds what the fluid of each of the session's entries did since it was last folded into it (its history is about
     * to be unloaded, or the server stops), when it can be ({@link #foldable}).
     */
    private void foldTrails(Session session) {
        if (session.busy()) return;
        List<HistoryEntry> all = new ArrayList<>(session.history.undoEntries());
        all.addAll(session.history.redoEntries());
        for (HistoryEntry entry : all) {
            EditRecord trail = trails.drain(entry);
            if (trail != null && !trail.isEmpty()) foldTrail(entry.id(), trail);
        }
    }

    /** The current session that may fold a trail into entry {@code id} now ({@link #foldable}), or {@code null}. */
    private Session holder(UUID id) {
        for (Session session : sessions.values()) {
            if (!isCurrent(session) || session.busy()) continue;
            if (session.history.bytesOf(id) >= 0) return session;
        }
        return null;
    }

    private static HistoryEntry entryIn(PlayerHistory history, UUID id) {
        for (HistoryEntry e : history.undoEntries()) {
            if (e.id().equals(id)) return e;
        }
        for (HistoryEntry e : history.redoEntries()) {
            if (e.id().equals(id)) return e;
        }
        return null;
    }

    /**
     * The ids of every entry a history here holds or will hold: the sessions' stacks and waiting pushes, open records
     * and offline histories ({@code FluidTrails} forgets the others); {@code null} while that is not known yet: a saved
     * history is still being read (or waits to be merged), or the store has not reported its scan.
     */
    public java.util.Set<UUID> liveEntries() {
        if (persistent() && !scanned) return null;
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (Session session : sessions.values()) {
            if (session.loading || session.loadPending || session.arrived != null) return null;
            for (HistoryEntry e : session.history.undoEntries()) ids.add(e.id());
            for (HistoryEntry e : session.history.redoEntries()) ids.add(e.id());
            for (Deferred deferred : session.deferred) ids.add(deferred.entry().id());
        }
        ids.addAll(open.keySet());
        for (StoredHistory history : offline.values()) {
            for (StoredHistory.Entry e : history.entries()) ids.add(e.id());
        }
        return ids;
    }

    /** The saved history of a player without a session (offline), if any. */
    public Optional<StoredHistory> offline(UUID player) {
        return Optional.ofNullable(offline.get(player));
    }

    /** Estimated bytes held across all players, deferred pushes and offline histories included. */
    public long totalBytes() {
        long total = 0;
        for (Session session : sessions.values()) total += session.history.bytes() + session.deferredBytes;
        for (StoredHistory history : offline.values()) total += history.bytes();
        return total;
    }

    /** Journal bytes of every kept entry, as the store reported them. */
    public long diskBytes() {
        long total = 0;
        for (Session session : sessions.values()) {
            for (Long bytes : session.diskBytes.values()) total += bytes;
        }
        for (StoredHistory history : offline.values()) total += history.diskBytes();
        return total;
    }

    private boolean isCurrent(Session session) {
        return session != null && !session.closed && sessions.get(session.player) == session;
    }

    /** Whether the session holds the entry, pushed or waiting to be. */
    private static boolean holds(Session session, UUID entry) {
        if (session.history.bytesOf(entry) >= 0) return true;
        for (Deferred deferred : session.deferred) {
            if (deferred.entry().id().equals(entry)) return true;
        }
        return false;
    }

    private void apply(Session session, HistoryEntry entry, long bytes) {
        // A push is another history change: the run of undo or redo steps ends.
        session.run = null;
        List<HistoryEntry> dropped = session.history.redoEntries();
        List<HistoryEntry> evicted;
        try {
            evicted = session.history.push(entry, bytes);
        } catch (IllegalArgumentException e) {
            LOG.error("Sculptory: history entry '{}' refused", entry.label(), e);
            if (persistent()) store.abort(session.player, entry.id());
            return;
        }
        if (persistent()) store.push(session.player, entry.id());
        if (session.loadPending) session.pushedWhileLoading.add(entry.id());
        for (HistoryEntry gone : dropped) session.diskBytes.remove(gone.id());
        if (!evicted.isEmpty()) {
            boolean newest = false;
            for (HistoryEntry e : evicted) {
                newest |= e.id().equals(entry.id());
                forget(session, e);
            }
            listener.evicted(session.player, evicted.size(), newest);
        }
    }

    /** An entry left the session's history by eviction: journals it. */
    private void forget(Session session, HistoryEntry entry) {
        session.diskBytes.remove(entry.id());
        if (persistent()) store.evict(session.player, entry.id());
    }

    /**
     * Evicts the oldest entries across players until the total fits {@link HistoryLimits#maxBytesTotal()} (and, when
     * saving, the kept entries' journal bytes fit half of {@link Persistence#maxDiskBytes()}). Players with an undo or
     * redo in flight (or loading) are skipped; the cap is enforced again when theirs finishes. Offline histories count
     * and are evicted like the others.
     */
    private void enforceTotal() {
        long maxDisk = persistence == null || !persistent() ? Long.MAX_VALUE : persistence.maxDiskBytes() / 2;
        long total = totalBytes();
        long disk = maxDisk == Long.MAX_VALUE ? 0 : diskBytes();
        if (total <= limits.maxBytesTotal() && disk <= maxDisk) return;
        Map<UUID, Integer> evicted = new LinkedHashMap<>();
        while (total > limits.maxBytesTotal() || disk > maxDisk) {
            Session victim = null;
            StoredHistory offlineVictim = null;
            long oldest = Long.MAX_VALUE;
            for (Session session : sessions.values()) {
                if (session.busy() || session.history.size() == 0) continue;
                HistoryEntry candidate = evictionCandidate(session.history);
                if (candidate.createdMillis() < oldest) {
                    oldest = candidate.createdMillis();
                    victim = session;
                }
            }
            for (StoredHistory history : offline.values()) {
                Optional<StoredHistory.Entry> candidate = history.evictionCandidate();
                if (candidate.isPresent() && candidate.get().createdMillis() < oldest) {
                    oldest = candidate.get().createdMillis();
                    offlineVictim = history;
                    victim = null;
                }
            }
            if (offlineVictim != null) {
                evictOffline(offlineVictim);
            } else if (victim != null) {
                Optional<HistoryEntry> gone = victim.history.evictOldest();
                if (gone.isEmpty()) break;
                forget(victim, gone.get());
                // Any eviction ends the victim's run (the client sees it change the history state).
                victim.run = null;
                evicted.merge(victim.player, 1, Integer::sum);
            } else {
                break;
            }
            total = totalBytes();
            if (maxDisk != Long.MAX_VALUE) disk = diskBytes();
        }
        for (Map.Entry<UUID, Integer> entry : evicted.entrySet()) {
            listener.evicted(entry.getKey(), entry.getValue(), false);
            listener.changed(entry.getKey());
        }
    }

    private void evictOffline(StoredHistory history) {
        history.evictOldest().ifPresent(gone -> {
            if (persistent()) store.evict(history.player(), gone.id());
        });
        if (history.isEmpty()) offline.remove(history.player());
    }

    /** The entry {@link PlayerHistory#evictOldest()} removes next. */
    private static HistoryEntry evictionCandidate(PlayerHistory history) {
        List<HistoryEntry> undo = history.undoEntries();
        if (!undo.isEmpty()) return undo.get(undo.size() - 1);
        List<HistoryEntry> redo = history.redoEntries();
        return redo.get(redo.size() - 1);
    }

    private static List<String> labels(List<HistoryEntry> entries) {
        int n = Math.min(entries.size(), HistorySnapshot.MAX_LABELS);
        List<String> labels = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            labels.add(HistorySnapshot.truncateUtf8(entries.get(i).label(), HistorySnapshot.MAX_LABEL_BYTES));
        }
        return labels;
    }

    // ---------------------------------------------------------------------------------------------- persistence

    /**
     * Takes in what the store reported (saved histories found at start, loaded histories, journal sizes, failures),
     * rewrites journals that fell out of sync, saves open records every {@value #SAVE_OPEN_TICKS} calls, applies the age
     * limit to offline histories and unloads sessions of players who left. Call once per server tick.
     */
    public void poll() {
        if (store == null) return;
        boolean stored = false;
        for (HistoryStore.Event event : store.poll()) {
            switch (event) {
                case HistoryStore.Scanned scanned -> scanned(scanned);
                case HistoryStore.Loaded loaded -> loaded(loaded);
                case HistoryStore.Stored entry -> {
                    Session session = sessions.get(entry.player());
                    if (session != null && holds(session, entry.entry())) {
                        session.diskBytes.put(entry.entry(), entry.diskBytes());
                        stored = true;
                    }
                }
                case HistoryStore.Failed failed -> {
                    // Its file may hold steps this service has not seen: that player is always loaded, never assumed
                    // empty.
                    if (!sessions.containsKey(failed.player())) unreadAtScan.add(failed.player());
                    LOG.warn("Sculptory: {}'s undo history is not being saved for now ({}); it is kept in memory "
                            + "and saved again when writing works", failed.player(), failed.reason());
                }
                case HistoryStore.Rewritten rewritten -> {
                    if (rewritten.ok()) {
                        rewriteRetry.remove(rewritten.player());
                        Session session = sessions.get(rewritten.player());
                        if (session != null) maybeUnload(session);
                    } else {
                        rewriteRetry.put(rewritten.player(), nanos.getAsLong() + REWRITE_RETRY_NANOS);
                        LOG.warn("Sculptory: saving {}'s undo history again failed ({}); trying later",
                                rewritten.player(), rewritten.problem());
                    }
                }
                case HistoryStore.Stopped stopped -> stopped(stopped.reason());
            }
        }
        if (!persistent()) {
            if (store != null && !store.usable()) stopped("the history store stopped");
            return;
        }
        // The journal sizes just reported count toward the disk cap.
        if (stored) enforceTotal();
        long now = nanos.getAsLong();
        for (Session session : List.copyOf(sessions.values())) {
            if (session.loading && now - session.loadingSince > LOAD_TIMEOUT_NANOS) {
                // The player may edit and undo their new steps; the saved ones are merged underneath when they arrive.
                // Nothing is rewritten or dropped meanwhile: the file keeps every saved step.
                LOG.warn("Sculptory: loading {}'s saved undo history takes long; they can edit meanwhile, and "
                        + "their saved steps are added under the new ones once they are read", session.player);
                session.loading = false;
                afterOperation(session);
            }
        }
        if (!store.rewriting()) {
            // One rewrite at a time: each writes a whole history.
            for (UUID player : store.needsRewrite()) {
                Long retry = rewriteRetry.get(player);
                if (retry != null && now - retry < 0) continue;
                Session session = sessions.get(player);
                if (session != null) {
                    if (session.loadPending) continue; // its saved steps are not in memory yet
                    rewriteRetry.put(player, now + REWRITE_RETRY_NANOS);
                    rewrite(session);
                    break;
                } else if (offline.containsKey(player)) {
                    rewriteRetry.put(player, now + REWRITE_RETRY_NANOS);
                    rewriteOffline(offline.get(player));
                    break;
                }
            }
        }
        if (++ticksSinceSave >= SAVE_OPEN_TICKS) {
            ticksSinceSave = 0;
            saveOpen();
        }
        if (now - nextExpireNanos >= 0) {
            nextExpireNanos = now + EXPIRE_EVERY_NANOS;
            expireOffline();
        }
    }

    /**
     * Right before the game saves chunk (cx, cz) (after {@link #saveOpen(String, int, int)} journaled its open records):
     * waits, at most {@link #BARRIER_WAIT_NANOS}, until the store has handed the records of that column (and every redo
     * in flight) to the operating system, writing them ahead of other players' records, so a process crash can never
     * leave the chunk newer than its history. After a wait times out, barriers do not wait for
     * {@link #BARRIER_PAUSE_NANOS} (a slow disk must not stall every chunk save). Players whose changes there cannot be
     * saved now ({@code touched} players whose journal is out of sync, or whose file is being rewritten or cannot be
     * written) are logged, at most once a minute each.
     *
     * @return whether the chunk's history is on disk ahead of it
     */
    public boolean barrier(int cx, int cz, java.util.Set<UUID> touched) {
        if (!persistent()) return true;
        java.util.Set<UUID> unprotected = new java.util.HashSet<>();
        for (UUID player : touched) {
            if (!store.accepting(player)) unprotected.add(player);
        }
        boolean written;
        long now = nanos.getAsLong();
        if (now - barrierPausedUntil < 0) {
            written = !store.hasUnwritten();
        } else {
            HistoryStore.ColumnWait wait = store.awaitColumn(HistoryStore.column(cx, cz), BARRIER_WAIT_NANOS);
            unprotected.addAll(wait.unprotected());
            written = wait.written();
            if (!written) {
                barrierPausedUntil = now + BARRIER_PAUSE_NANOS;
                LOG.warn("Sculptory: the undo history writer is behind; chunks are saved without waiting for it "
                        + "for {} s (a crash now could leave recent edits without undo)",
                        TimeUnit.NANOSECONDS.toSeconds(BARRIER_PAUSE_NANOS));
            }
        }
        for (UUID player : unprotected) {
            Long last = unprotectedLogged.get(player);
            if (last != null && now - last < TimeUnit.MINUTES.toNanos(1)) continue;
            unprotectedLogged.put(player, now);
            LOG.warn("Sculptory: chunk {}, {} is saved while {}'s undo history of it is not on disk (their history "
                    + "is being saved again); a crash now could leave those edits without undo", cx, cz, player);
        }
        return written && unprotected.isEmpty();
    }

    /** One line for {@code /sculptory history}: whether and where history is saved. */
    public String storageStatus() {
        if (persistence == null) return "kept in memory only (history.persist is off)";
        if (store == null || !store.usable()) return "kept in memory only (saving stopped after an error; see the log)";
        return store.status();
    }

    /** Closes the store after writing everything queued; false when it did not finish within the timeout. */
    public boolean closeStore(long timeoutMillis) {
        HistoryStore closing = store;
        if (closing == null) return true;
        store = null;
        return closing.close(timeoutMillis);
    }

    private void stopped(String reason) {
        if (store == null) return;
        LOG.error("Sculptory: undo history is no longer saved to disk ({}); it stays in memory until players "
                + "leave", reason);
        store = null;
        offline.clear();
        open.clear();
        for (Session session : List.copyOf(sessions.values())) {
            session.loadPending = false;
            session.arrived = null;
            session.pushedWhileLoading.clear();
            if (session.loading) {
                session.loading = false;
                afterOperation(session);
            }
            if (!session.online) {
                sessions.remove(session.player);
                session.closed = true;
            }
        }
    }

    private void scanned(HistoryStore.Scanned scanned) {
        this.scanned = true;
        for (StoredHistory history : scanned.histories()) {
            // A session, or an offline history this service unloaded meanwhile, is newer than the scan.
            if (sessions.containsKey(history.player()) || offline.containsKey(history.player())) continue;
            if (!history.isEmpty()) offline.put(history.player(), history);
        }
        long disk = 0;
        for (StoredHistory history : scanned.histories()) disk += history.diskBytes();
        LOG.info("Sculptory: found the saved undo history of {} player(s) ({} KiB of data)",
                scanned.histories().size(), disk >> 10);
        expireOffline();
        enforceTotal();
    }

    /** A load arrived: merged at once, or when the undo or redo in flight ends. */
    private void loaded(HistoryStore.Loaded loaded) {
        Session session = sessions.get(loaded.player());
        if (session == null || !session.loadPending) return;
        if (session.inFlightOp != null) {
            session.arrived = loaded;
            return;
        }
        merge(session, loaded);
        afterOperation(session);
    }

    /**
     * Puts the loaded (saved) entries into the session. Usually the session holds nothing yet and gets them as they
     * were. If it gave up waiting ({@link #LOAD_TIMEOUT_NANOS}) and the player pushed steps meanwhile, the saved steps
     * that were undoable go underneath what the session holds and the saved redo side is dropped, which is what the
     * journal replays to (those pushes follow the saved records and drop that redo side). The entries pushed meanwhile
     * are never taken from the load (the load may have read the file after their push; the session knows what became
     * of them since, undone and dropped or evicted). Saved entries that could not be restored are evicted (the store kept
     * a copy of damaged data); one pushed meanwhile is not (memory holds it whole), and the journal is marked out of sync
     * and written anew from memory instead (now, or by poll() once no other rewrite runs). Then the caps and the age limit apply. The run of undo or redo steps
     * ends (the history changed).
     */
    private void merge(Session session, HistoryStore.Loaded loaded) {
        session.loading = false;
        session.loadPending = false;
        session.arrived = null;
        session.run = null;
        java.util.Set<UUID> pushed = new java.util.HashSet<>(session.pushedWhileLoading);
        session.pushedWhileLoading.clear();
        List<HistoryEntry> currentUndo = session.history.undoEntries();
        List<HistoryEntry> current = new ArrayList<>();
        for (int i = currentUndo.size() - 1; i >= 0; i--) current.add(currentUndo.get(i));
        current.addAll(session.history.redoEntries());
        for (HistoryEntry e : current) pushed.add(e.id()); // the session holds only what was pushed meanwhile
        boolean pushedMeanwhile = !pushed.isEmpty();
        List<HistoryEntry> entries = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        int applied = 0;
        for (int i = 0; i < loaded.entries().size(); i++) {
            HistoryStore.LoadedEntry e = loaded.entries().get(i);
            if (pushed.contains(e.id())) continue;
            boolean undoable = i < loaded.applied();
            if (pushedMeanwhile && !undoable) continue; // a saved redo side, dropped by the pushes meanwhile
            entries.add(new HistoryEntry(e.id(), session.player, e.world(), e.label(), e.record(), e.createdMillis()));
            sizes.add(e.bytes());
            session.diskBytes.put(e.id(), e.diskBytes());
            if (undoable) applied++;
        }
        int restored = entries.size();
        for (HistoryEntry e : current) {
            entries.add(e);
            sizes.add(Math.max(0, session.history.bytesOf(e.id())));
        }
        applied += currentUndo.size();
        long[] bytes = new long[sizes.size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = sizes.get(i);
        // Only saved steps are evicted. A step pushed meanwhile belongs to the session, which journals what becomes of it
        // (undone and dropped, evicted); one the load read back damaged is whole in memory, and evicting it would journal
        // the loss of a step the player can still undo. Its saved copy is replaced by writing the journal anew below.
        boolean damagedMeanwhile = false;
        for (UUID failed : loaded.failed()) {
            if (!pushed.contains(failed)) {
                store.evict(session.player, failed);
            } else if (current.stream().anyMatch(e -> e.id().equals(failed))) {
                damagedMeanwhile = true;
            }
        }
        if (!loaded.problems().isEmpty()) {
            LOG.warn("Sculptory: {} step(s) of {}'s saved undo history could not be restored: {}",
                    loaded.failed().size(), session.player, String.join("; ", loaded.problems()));
        }
        try {
            session.history = PlayerHistory.restore(limits, entries, bytes, applied);
        } catch (IllegalArgumentException e) {
            // Cannot happen with a well-formed load; the session keeps what it had and the file is left as it is.
            LOG.error("Sculptory: {}'s saved undo history could not be merged; it stays on disk", session.player, e);
            return;
        }
        for (HistoryEntry gone : session.history.trimToLimits()) forget(session, gone);
        expire(session);
        if (restored > 0) LOG.info("Sculptory: restored {} undo step(s) of {}", restored, session.player);
        // The journal may have missed the steps made while it was read (a file that could not be read), or hold a step
        // made meanwhile damaged: with every saved step in memory now, it can be written anew. Marked out of sync, it is
        // rewritten by poll() when another player's rewrite is running now.
        if (damagedMeanwhile) store.markOutOfSync(session.player);
        if (store.outOfSync(session.player) && !store.rewriting()) rewrite(session);
    }

    /** Drops the session's entries older than the age limit (oldest first). */
    private void expire(Session session) {
        long cutoff = ageCutoff();
        if (cutoff == Long.MIN_VALUE) return;
        while (session.history.size() > 0 && evictionCandidate(session.history).createdMillis() < cutoff) {
            forget(session, session.history.evictOldest().orElseThrow());
        }
    }

    private void expireOffline() {
        long cutoff = ageCutoff();
        if (cutoff == Long.MIN_VALUE) return;
        for (StoredHistory history : List.copyOf(offline.values())) {
            while (history.evictionCandidate().map(e -> e.createdMillis() < cutoff).orElse(false)) {
                evictOffline(history);
            }
        }
    }

    private long ageCutoff() {
        if (persistence == null || persistence.maxAgeMillis() <= 0) return Long.MIN_VALUE;
        return persistence.wallClock().getAsLong() - persistence.maxAgeMillis();
    }

    /**
     * Unloads a session of a player who left, once nothing of theirs runs or waits, their saved history has been merged
     * in and their journal is in sync. What their entries' fluid did since it was last folded goes into them first
     * (what it does while they are away is followed in memory and folded once their history is loaded again).
     */
    private void maybeUnload(Session session) {
        if (session.online || !persistent() || !isCurrent(session)) return;
        if (session.busy() || session.loadPending || session.editsRunning > 0 || !session.deferred.isEmpty()) return;
        if (store.outOfSync(session.player)) return;
        for (OpenRecord record : open.values()) {
            if (record.session == session) return;
        }
        foldTrails(session);
        List<StoredHistory.Entry> entries = new ArrayList<>();
        List<HistoryEntry> undo = session.history.undoEntries();
        for (int i = undo.size() - 1; i >= 0; i--) entries.add(stored(session, undo.get(i)));
        for (HistoryEntry e : session.history.redoEntries()) entries.add(stored(session, e));
        sessions.remove(session.player);
        session.closed = true;
        if (!entries.isEmpty()) offline.put(session.player, new StoredHistory(session.player, entries, undo.size()));
    }

    private static StoredHistory.Entry stored(Session session, HistoryEntry e) {
        return new StoredHistory.Entry(e.id(), e.world(), e.label(), e.createdMillis(),
                Math.max(0, session.history.bytesOf(e.id())), session.diskBytes.getOrDefault(e.id(), 0L));
    }

    /**
     * Replaces the player's journal with what the session holds: its stack, its waiting pushes and its open records
     * (as they are now; their later sections follow the rewrite as usual). Never while the saved history has not been
     * merged in (the journal holds steps memory does not), so a rewrite never loses a saved step.
     */
    private void rewrite(Session session) {
        if (session.loadPending || session.loading) return;
        List<HistoryStore.SnapshotEntry> stack = new ArrayList<>();
        List<HistoryEntry> undo = session.history.undoEntries();
        for (int i = undo.size() - 1; i >= 0; i--) stack.add(snapshot(undo.get(i), session.history));
        for (HistoryEntry e : session.history.redoEntries()) stack.add(snapshot(e, session.history));
        List<HistoryStore.SnapshotEntry> pending = new ArrayList<>();
        for (Deferred deferred : session.deferred) {
            HistoryEntry e = deferred.entry();
            pending.add(new HistoryStore.SnapshotEntry(e.id(), e.world(), e.label(), e.createdMillis(),
                    deferred.bytes(), e.record()));
        }
        List<HistoryStore.SnapshotEntry> openEntries = new ArrayList<>();
        List<OpenRecord> snapshotted = new ArrayList<>();
        for (OpenRecord record : open.values()) {
            if (record.session != session) continue;
            BlockBuffer before = new BlockBuffer();
            BlockBuffer after = new BlockBuffer();
            for (long key : record.builder.sectionKeys()) {
                SectionBuffer[] pair = record.builder.snapshotSection(key);
                if (pair == null) continue;
                before.putSection(key, pair[0]);
                after.putSection(key, pair[1]);
            }
            snapshotted.add(record);
            dev.sculptory.core.history.EditRecord now =
                    new dev.sculptory.core.history.EditRecord(before, after, record.builder.entityChanges());
            if (now.isEmpty()) continue;
            openEntries.add(new HistoryStore.SnapshotEntry(record.entry(), record.world, record.label, record.created, 0,
                    now));
        }
        store.rewrite(session.player, stack, undo.size(), pending, openEntries);
        for (OpenRecord record : snapshotted) {
            // The rewrite holds each open record as it is now; later changes are journaled after it as usual.
            record.builder.drainDirty();
            record.builder.drainEntities();
            record.begun = hasCells(openEntries, record.entry());
        }
    }

    private static boolean hasCells(List<HistoryStore.SnapshotEntry> entries, UUID id) {
        for (HistoryStore.SnapshotEntry entry : entries) {
            if (entry.id().equals(id)) return true;
        }
        return false;
    }

    private static HistoryStore.SnapshotEntry snapshot(HistoryEntry e, PlayerHistory history) {
        return new HistoryStore.SnapshotEntry(e.id(), e.world(), e.label(), e.createdMillis(),
                Math.max(0, history.bytesOf(e.id())), e.record());
    }

    /** Rewrites an offline player's journal to the entries kept in memory (copied from the file). */
    private void rewriteOffline(StoredHistory history) {
        List<HistoryStore.SnapshotEntry> stack = new ArrayList<>();
        for (StoredHistory.Entry e : history.entries()) {
            stack.add(new HistoryStore.SnapshotEntry(e.id(), e.world(), e.label(), e.createdMillis(), e.bytes(), null));
        }
        store.rewrite(history.player(), stack, history.applied(), List.of(), List.of());
    }
}
