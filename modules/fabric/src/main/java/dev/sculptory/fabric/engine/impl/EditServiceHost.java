package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.store.HistoryStore;
import dev.sculptory.core.history.store.StorageIo;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.config.FolderMigration;
import dev.sculptory.fabric.schem.FabricDataFixHook;
import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.BuilderOutcome;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.EditService;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.PermissionService;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.ScatterService;
import dev.sculptory.server.engine.TinkerService;
import dev.sculptory.server.engine.impl.HistoryService;
import dev.sculptory.server.engine.impl.HistorySnapshot;
import dev.sculptory.server.library.Library;
import dev.sculptory.server.net.HistoryView;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Util;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates one {@link EngineEditService} (with its {@link ServerScatter}, and its {@link ServerClipboards} with the
 * library under {@code <gameDir>/sculptory/library/}) per running server and exposes server-independent facades,
 * so the network layer can be installed once from the mod initializer:
 * <pre>{@code
 * EditServiceHost.install(ServerNet::jobListener, ServerNet::predictionApplied, events);
 * ServerNet.install(EditServiceHost.service(), EditServiceHost.clipboards(), EditServiceHost.scatter(),
 *         EditServiceHost.tinker(), EditServiceHost.permissions(), EditServiceHost::limits, EditServiceHost::states);
 * }</pre>
 * Lifecycle: the service is created at {@code SERVER_STARTING} (after {@link EngineRuntime}), ticked at
 * {@code END_SERVER_TICK} (stroke idle timeout), told about disconnects, and shut down at {@code SERVER_STOPPING}
 * (after the executor, which drops queued dabs and ends jobs first).
 */
public final class EditServiceHost {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    private static final Facade FACADE = new Facade();
    private static final PermissionFacade PERMISSIONS = new PermissionFacade();
    private static final ClipboardFacade CLIPBOARDS = new ClipboardFacade();
    private static final ScatterFacade SCATTER = new ScatterFacade();
    private static final TinkerFacade TINKER = new TinkerFacade();
    /** How often the library index is written if it changed. */
    private static final int INDEX_FLUSH_TICKS = 40;
    /** How long a server stop waits for the undo history to be written. */
    static final long HISTORY_CLOSE_MILLIS = 30_000;
    /** Where the history store logs. */
    private static final HistoryStore.Log STORE_LOG = new HistoryStore.Log() {
        @Override
        public void info(String message) {
            LOG.info(message);
        }

        @Override
        public void warn(String message, Throwable cause) {
            if (cause == null) {
                LOG.warn(message);
            } else {
                LOG.warn(message, cause);
            }
        }
    };
    private static boolean installed;
    private static long lastChunkSaveError = System.nanoTime() - 120_000_000_000L;
    private static final java.util.concurrent.atomic.AtomicLong CHUNK_SAVE_HOOKS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong OFF_THREAD_HOOKS =
            new java.util.concurrent.atomic.AtomicLong();
    /** The missing chunk-save hook was warned about for the running server. */
    private static volatile boolean hookWarned;
    /** System property that moves the host's history folder (tests). */
    public static final String HISTORY_DIR_PROPERTY = "sculptory.historyDir";
    private static volatile EngineEditService current;
    private static volatile ServerClipboards clipboards;
    private static volatile ServerScatter scatter;

    private EditServiceHost() {}

    /**
     * Registers the lifecycle hooks (once per JVM; also installs {@link EngineRuntime}).
     *
     * @param listeners listeners for undo/redo jobs, e.g. {@code ServerNet::jobListener}
     * @param acks dab acknowledgements, e.g. {@code ServerNet::predictionApplied}
     */
    public static synchronized void install(Function<ServerPlayerEntity, JobListener> listeners, AckSink acks,
                                            EditEvents events) {
        if (installed) throw new IllegalStateException("EditServiceHost is already installed");
        installed = true;
        Objects.requireNonNull(listeners);
        Objects.requireNonNull(acks);
        Objects.requireNonNull(events);
        EngineRuntime.install(); // its SERVER_STARTING hook must run before ours
        ServerLifecycleEvents.SERVER_STARTING.register(server -> EngineRuntime.find(server).ifPresentOrElse(
                runtime -> {
                    CHUNK_SAVE_HOOKS.set(0);
                    OFF_THREAD_HOOKS.set(0);
                    hookWarned = false;
                    EngineEditService service = new EngineEditService(runtime, listeners, acks, events,
                            openHistory(server, runtime));
                    ServerClipboards clips = new ServerClipboards(service, new Library(
                            ServerClipboards.defaultLibraryRoot(), runtime.config().toLibrarySettings()),
                            ServerClipboards.newExecutor(), FabricDataFixHook.get());
                    clips.start();
                    clipboards = clips;
                    ServerScatter planning = new ServerScatter(service);
                    runtime.executor().addLane(planning.lane(), runtime.config().scatter.tickShare);
                    runtime.onReload(config -> runtime.executor().laneShare(planning.lane(), config.scatter.tickShare));
                    scatter = planning;
                    current = service;
                },
                () -> LOG.error("Sculptory: the engine did not start; editing is unavailable")));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            EngineEditService service = current;
            if (service != null && service.server() == server) {
                service.tick();
                ServerScatter planning = scatter;
                if (planning != null) planning.housekeeping();
                ServerClipboards clips = clipboards;
                if (clips != null && server.getTicks() % INDEX_FLUSH_TICKS == 0) clips.flushSoon();
            }
        });
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> {
            try {
                checkChunkSaveHook(server);
            } catch (RuntimeException | LinkageError e) {
                LOG.error("Sculptory: checking the chunk-save hook failed", e);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            if (server.isOnThread()) {
                playerJoined(server, player);
            } else {
                server.execute(() -> playerJoined(server, player));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.player;
            if (server.isOnThread()) {
                playerLeft(server, player);
            } else {
                server.execute(() -> playerLeft(server, player));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            EngineEditService service = current;
            if (service != null && service.server() == server) {
                ServerScatter planning = scatter;
                if (planning != null) planning.shutdown();
                service.shutdown();
                // Before the game saves the worlds: the history of everything in them is on disk first.
                if (!service.historyService().closeStore(HISTORY_CLOSE_MILLIS)) {
                    LOG.warn("Sculptory: saving the undo history did not finish within {} s; the last steps may "
                            + "be missing after the restart", HISTORY_CLOSE_MILLIS / 1000);
                }
                ServerClipboards clips = clipboards;
                if (clips != null) clips.shutdown();
            }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            EngineEditService service = current;
            if (service != null && service.server() == server) {
                current = null;
                clipboards = null;
                scatter = null;
            }
        });
    }

    /**
     * Where a server saves its players' undo history: {@code <world>/sculptory/history}. The system property
     * {@value #HISTORY_DIR_PROPERTY} (tests only: the GameTest server sets it so the host's history is not left in the
     * test world) puts it in {@code <property>/<world folder name>} instead.
     */
    public static Path historyDir(MinecraftServer server) {
        Path world = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        String override = System.getProperty(HISTORY_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).resolve(String.valueOf(world.getFileName())).normalize();
        }
        return FolderMigration.worldDir(world).resolve("history").normalize();
    }

    /** The chunk-save hook's state, for {@code /sculptory history} and the log. */
    public static String chunkSaveHookStatus() {
        long calls = CHUNK_SAVE_HOOKS.get();
        long offThread = OFF_THREAD_HOOKS.get();
        if (calls == 0) return "chunk-save hook not called yet";
        return "chunk-save hook active (" + calls + " chunk saves)"
                + (offThread > 0 ? ", " + offThread + " of them off the server thread (not protected)" : "");
    }

    /**
     * After a world save (autosave, {@code /save-all}): if the chunk-save hook never ran, or ran off the server
     * thread, another mod has replaced the game's chunk saving; history is then saved every few seconds instead of
     * before each chunk, so a crash can leave up to about 5 s of edits without undo. Warned once per server.
     */
    private static void checkChunkSaveHook(MinecraftServer server) {
        EngineEditService service = current;
        if (service == null || service.server() != server || !service.historyService().persistent()) return;
        if (hookWarned) return;
        if (CHUNK_SAVE_HOOKS.get() == 0) {
            hookWarned = true;
            LOG.warn("Sculptory: the world was saved without Sculptory's chunk-save hook running (another mod "
                    + "may replace chunk saving); undo history is still saved every few seconds, but a crash can "
                    + "leave the last few seconds of edits without undo");
        } else if (OFF_THREAD_HOOKS.get() > 0) {
            hookWarned = true;
            LOG.warn("Sculptory: chunks are saved off the server thread (another mod changes chunk saving); undo "
                    + "history is still saved every few seconds, but a crash can leave the last few seconds of edits "
                    + "without undo");
        }
    }

    /**
     * Opens the undo history store of the server's world ({@link #historyDir}) when {@code history.persist} is on; null
     * (history in memory only, with a log line) when it is off or the folder cannot be used. The store scans its files
     * on its own thread, so a damaged or huge journal never holds up the start.
     */
    static HistoryService.Persistence openHistory(MinecraftServer server, EngineRuntime runtime) {
        SculptoryConfig config = runtime.config();
        if (!config.history.persist) {
            LOG.info("Sculptory: undo history is kept in memory only (history.persist is off)");
            return null;
        }
        Path dir = historyDir(server);
        try {
            HistoryStore store = HistoryStore.open(dir,
                    new FabricHistoryCodec(runtime.states(), FabricDataFixHook.currentDataVersion()),
                    HistoryStore.Settings.DEFAULTS, StorageIo.SYSTEM, STORE_LOG);
            LOG.info("Sculptory: undo history is saved in {}", dir);
            return new HistoryService.Persistence(store, config.history.maxDiskBytes, config.historyMaxAgeMillis(),
                    System::currentTimeMillis);
        } catch (IOException | RuntimeException e) {
            LOG.error("Sculptory: the undo history folder {} cannot be used ({}); history is kept in memory only "
                    + "and lost when players leave", dir, e.toString());
            return null;
        }
    }

    /** How many chunk saves have called {@link #beforeChunkSave} (tests check that the hook is in place). */
    public static long chunkSaveHooks() {
        return CHUNK_SAVE_HOOKS.get();
    }

    /** A player joined (the {@code JOIN} hook; server thread): their saved history starts loading. */
    public static void playerJoined(MinecraftServer server, ServerPlayerEntity player) {
        EngineEditService service = current;
        if (service == null || service.server() != server || player == null) return;
        try {
            service.playerJoined(player.getUuid());
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: loading {}'s undo history failed", player.getGameProfile().getName(), e);
        }
    }

    /**
     * The game is about to save chunk (cx, cz) of {@code world} ({@code ServerChunkLoadingManagerMixin}): the undo
     * history of the engine's changes there is written first ({@link EngineEditService#beforeChunkSave}). Never throws.
     */
    public static void beforeChunkSave(ServerWorld world, int cx, int cz) {
        CHUNK_SAVE_HOOKS.incrementAndGet();
        EngineEditService service = current;
        if (service == null || world == null || service.server() != world.getServer()) return;
        try {
            if (!service.beforeChunkSave(world, cx, cz)) OFF_THREAD_HOOKS.incrementAndGet();
        } catch (RuntimeException | LinkageError e) {
            long now = System.nanoTime();
            if (now - lastChunkSaveError > 60_000_000_000L) {
                lastChunkSaveError = now;
                LOG.error("Sculptory: saving undo history before a chunk save failed", e);
            }
        }
    }

    /**
     * A player's connection ended (the {@code DISCONNECT} hook; server thread). First their permissions are checked
     * once more against their jobs in the world they were in ({@link EngineEditService#revalidate(ServerPlayerEntity,
     * boolean)}): a node removed just before leaving is not caught by the periodic re-check afterwards, which only
     * looks at online players. Then the scatter preview ends and the edit service lets go of the player (their jobs
     * that have not started are cancelled). A no-op without a running engine.
     */
    public static void playerLeft(MinecraftServer server, ServerPlayerEntity player) {
        EngineEditService service = current;
        if (service == null || service.server() != server || player == null) return;
        playerLeft(service, scatter, player);
    }

    /** {@link #playerLeft(MinecraftServer, ServerPlayerEntity)} on the given services ({@code planning} may be null). */
    public static void playerLeft(EngineEditService service, ServerScatter planning, ServerPlayerEntity player) {
        UUID id = player.getUuid();
        String name = player.getGameProfile().getName();
        // Each step on its own: none may skip the next, nor vanilla's disconnect handling after this hook.
        try {
            service.revalidate(player, false);
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: re-checking {}'s permissions on leaving failed", name, e);
        }
        if (planning != null) {
            try {
                planning.playerLeft(id);
            } catch (RuntimeException | LinkageError e) {
                LOG.error("Sculptory: ending {}'s scatter preview on leaving failed", name, e);
            }
        }
        try {
            service.playerLeft(id);
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: letting go of {} on leaving failed", name, e);
        }
    }

    /**
     * The player's permissions may have changed ({@code PlayerManagerMixin}): cancels their admitted jobs that need a
     * right they no longer hold ({@link EngineEditService#revalidate(ServerPlayerEntity, boolean)}) and ends their
     * scatter preview if they may no longer scatter ({@link ServerScatter#revalidate}). A no-op without a running
     * engine. Safe from any thread: off the server thread it hops onto it.
     *
     * @param allWorlds true after {@code /op} or {@code /deop} (rights that hold everywhere); false when vanilla only
     *     resent the command tree (join, respawn, a change of world), which re-checks the jobs in the player's current
     *     world only, since a permissions mod may grant a node in one world only
     */
    public static void permissionsChanged(ServerPlayerEntity player, boolean allWorlds) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (!server.isOnThread()) {
            server.execute(() -> permissionsChanged(player, allWorlds));
            return;
        }
        EngineEditService service = current;
        if (service == null || service.server() != server) return;
        try {
            service.revalidate(player, allWorlds);
            ServerScatter planning = scatter;
            if (planning != null) planning.revalidate(player);
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: re-checking {}'s permissions failed", player.getGameProfile().getName(), e);
        }
    }

    /** The scatter service of a running server. */
    /**
     * Builder mode: the powers {@code player} has on (0 when none, or before the engine started), read by
     * {@code ServerPlayerInteractionManagerMixin} to keep vanilla from placing blocks while builder mode is on.
     */
    public static int builderPowers(UUID player) {
        EngineEditService service = current;
        return service == null ? 0 : service.builderPowersOf(player);
    }

    public static Optional<ServerScatter> findScatter(MinecraftServer server) {
        ServerScatter service = scatter;
        EngineEditService edits = current;
        return service != null && edits != null && edits.server() == server ? Optional.of(service) : Optional.empty();
    }

    /** A {@link ScatterService} delegating to the running server's; refuses with {@code DISABLED} without one. */
    public static ScatterService<ServerPlayerEntity> scatter() {
        return SCATTER;
    }

    /**
     * A {@link TinkerService} delegating to the running server's edit service; refuses with {@code DISABLED} without one.
     */
    public static TinkerService<ServerPlayerEntity> tinker() {
        return TINKER;
    }

    /** The clipboard service of a running server. */
    public static Optional<ServerClipboards> findClipboards(MinecraftServer server) {
        ServerClipboards service = clipboards;
        return service != null && service.edits().server() == server ? Optional.of(service) : Optional.empty();
    }

    /** A {@link ClipboardService} delegating to the running server's; refuses with {@code DISABLED} without one. */
    public static ClipboardService<ServerPlayerEntity> clipboards() {
        return CLIPBOARDS;
    }

    /** The service of a running server. */
    public static Optional<EngineEditService> find(MinecraftServer server) {
        EngineEditService service = current;
        return service != null && service.server() == server ? Optional.of(service) : Optional.empty();
    }

    /** An {@link EditService} (and {@link HistoryView}) delegating to the running server's service. */
    public static EditService<ServerPlayerEntity> service() {
        return FACADE;
    }

    /** A {@link PermissionService} delegating to the running server's engine; denies everything without one. */
    public static PermissionService<ServerPlayerEntity, ServerWorld> permissions() {
        return PERMISSIONS;
    }

    /** The running server's client-visible limits, or the defaults. */
    public static Limits limits() {
        EngineEditService service = current;
        return service == null ? Limits.DEFAULTS : service.runtime().config().toLimits();
    }

    /** The running server's state space, or {@code null} before it starts. */
    public static StateSpace states() {
        EngineEditService service = current;
        return service == null ? null : service.runtime().states();
    }

    /** The protocol form of a history snapshot. */
    public static S2C.HistoryState toHistoryState(HistorySnapshot s) {
        return new S2C.HistoryState(s.canUndo(), s.canRedo(), s.undoLabel(), s.redoLabel(), s.bytes(), s.undoLabels(),
                s.redoLabels());
    }

    private static EngineEditService of(ServerPlayerEntity p) throws EditRejected {
        return find(p.getServer()).orElseThrow(() -> new EditRejected(RejectReason.DISABLED, "engine not running"));
    }

    private static final class Facade implements EditService<ServerPlayerEntity>, HistoryView<ServerPlayerEntity> {
        @Override
        public JobTicket run(ServerPlayerEntity p, OpSpec s, RunOptions o, JobListener l) throws EditRejected {
            return of(p).run(p, s, o, l);
        }

        @Override
        public void beginStroke(ServerPlayerEntity p, int strokeId, BrushSpec spec) throws EditRejected {
            of(p).beginStroke(p, strokeId, spec);
        }

        @Override
        public DabOutcome dabs(ServerPlayerEntity p, int strokeId, int seq, List<Dab> dabs) {
            Optional<EngineEditService> service = find(p.getServer());
            if (service.isPresent()) return service.get().dabs(p, strokeId, seq, dabs);
            int last = dabs == null || dabs.isEmpty() || dabs.get(dabs.size() - 1) == null
                    ? -1 : dabs.get(dabs.size() - 1).index();
            return DabOutcome.rejected(last, RejectReason.DISABLED);
        }

        @Override
        public void predicted(ServerPlayerEntity p) {
            find(p.getServer()).ifPresent(service -> service.predicted(p));
        }

        @Override
        public void endStroke(ServerPlayerEntity p, int strokeId) {
            find(p.getServer()).ifPresent(service -> service.endStroke(p, strokeId));
        }

        @Override
        public JobTicket undo(ServerPlayerEntity p, ConflictPolicy c) throws EditRejected {
            return of(p).undo(p, c);
        }

        @Override
        public JobTicket redo(ServerPlayerEntity p, ConflictPolicy c) throws EditRejected {
            return of(p).redo(p, c);
        }

        @Override
        public JobTicket historyOverwrite(ServerPlayerEntity p, boolean redo, int steps) throws EditRejected {
            return of(p).historyOverwrite(p, redo, steps);
        }

        @Override
        public void builderPowers(ServerPlayerEntity p, int powers) {
            find(p.getServer()).ifPresent(service -> service.builderPowers(p, powers));
        }

        @Override
        public BuilderOutcome builderPlace(ServerPlayerEntity p, C2S.BuilderPlace place) {
            return find(p.getServer()).map(service -> service.builderPlace(p, place))
                    .orElse(BuilderOutcome.refused(BuilderOutcome.Refusal.DISABLED, "engine not running"));
        }

        @Override
        public BuilderOutcome builderBreak(ServerPlayerEntity p, C2S.BuilderBreak breaks) {
            return find(p.getServer()).map(service -> service.builderBreak(p, breaks))
                    .orElse(BuilderOutcome.refused(BuilderOutcome.Refusal.DISABLED, "engine not running"));
        }

        @Override
        public void builderDragEnd(ServerPlayerEntity p, int dragId) {
            find(p.getServer()).ifPresent(service -> service.builderDragEnd(p, dragId));
        }

        @Override
        public S2C.NavigateResult navigate(ServerPlayerEntity p, C2S.Navigate m) {
            return find(p.getServer()).map(service -> service.navigate(p, m))
                    .orElse(S2C.NavigateResult.refused(m.reqId(), RejectReason.DISABLED));
        }

        @Override
        public boolean cancel(ServerPlayerEntity p, UUID jobId) {
            return find(p.getServer()).map(service -> service.cancel(p, jobId)).orElse(false);
        }

        @Override
        public S2C.HistoryState historyState(ServerPlayerEntity player) {
            return find(player.getServer()).map(service -> toHistoryState(service.history(player))).orElse(null);
        }
    }

    private static final class ClipboardFacade implements ClipboardService<ServerPlayerEntity> {
        private static ServerClipboards of(ServerPlayerEntity p) throws EditRejected {
            return findClipboards(p.getServer()).orElseThrow(() -> new EditRejected(RejectReason.DISABLED, "engine not running"));
        }

        @Override
        public JobTicket copy(ServerPlayerEntity p, Region region, BlockPos origin, boolean cut, CellMask mask,
                              EntityFilter entities, JobListener cutListener, Reply<ClipboardInfo> reply)
                throws EditRejected {
            return of(p).copy(p, region, origin, cut, mask, entities, cutListener, reply);
        }

        @Override
        public JobTicket copy(ServerPlayerEntity p, Box box, BlockPos origin, boolean cut, CellMask mask,
                              JobListener cutListener, Reply<ClipboardInfo> reply) throws EditRejected {
            return of(p).copy(p, box, origin, cut, mask, cutListener, reply);
        }

        @Override
        public void preview(ServerPlayerEntity p, SourceRef source, Reply<Outbound> reply) throws EditRejected {
            of(p).preview(p, source, reply);
        }

        @Override
        public void export(ServerPlayerEntity p, UUID clipboardId, SchematicFormat format, Reply<Outbound> reply)
                throws EditRejected {
            of(p).export(p, clipboardId, format, reply);
        }

        @Override
        public void export(ServerPlayerEntity p, UUID clipboardId, Reply<Outbound> reply) throws EditRejected {
            of(p).export(p, clipboardId, reply);
        }

        @Override
        public Upload beginUpload(ServerPlayerEntity p, String fileName, long totalBytes) throws EditRejected {
            return of(p).beginUpload(p, fileName, totalBytes);
        }

        @Override
        public SelectionUpload beginSelectionUpload(ServerPlayerEntity p, Sha256 hash, Box bounds, long cells,
                                                    long totalBytes) throws EditRejected {
            return of(p).beginSelectionUpload(p, hash, bounds, cells, totalBytes);
        }

        @Override
        public Upload beginGeneratedUpload(ServerPlayerEntity p, Box bounds, long cells, long totalBytes)
                throws EditRejected {
            return of(p).beginGeneratedUpload(p, bounds, cells, totalBytes);
        }

        @Override
        public void list(ServerPlayerEntity p, String folder, Reply<Listing> reply) throws EditRejected {
            of(p).list(p, folder, reply);
        }

        @Override
        public void load(ServerPlayerEntity p, String path, Reply<ClipboardInfo> reply) throws EditRejected {
            of(p).load(p, path, reply);
        }

        @Override
        public void save(ServerPlayerEntity p, UUID clipboardId, String path, Reply<Saved> reply) throws EditRejected {
            of(p).save(p, clipboardId, path, reply);
        }

        // Every other method forwards too: an interface default here would answer DISABLED (or nothing) on a running
        // server. EditServiceHostFacadesTest fails when a facade leaves an interface method to its default.

        @Override
        public void move(ServerPlayerEntity p, boolean folder, String from, String to, Reply<LibraryChange> reply)
                throws EditRejected {
            of(p).move(p, folder, from, to, reply);
        }

        @Override
        public void delete(ServerPlayerEntity p, boolean folder, String path, Reply<LibraryChange> reply)
                throws EditRejected {
            of(p).delete(p, folder, path, reply);
        }

        @Override
        public void createFolder(ServerPlayerEntity p, String path, Reply<LibraryChange> reply) throws EditRejected {
            of(p).createFolder(p, path, reply);
        }

        @Override
        public Optional<LibraryChange> shownTo(ServerPlayerEntity viewer, LibraryChange change) {
            return findClipboards(viewer.getServer()).flatMap(service -> service.shownTo(viewer, change));
        }

        @Override
        public void savePalette(ServerPlayerEntity p, String path, BlockPalette palette, Reply<LibraryChange> reply)
                throws EditRejected {
            of(p).savePalette(p, path, palette, reply);
        }

        @Override
        public void loadPalette(ServerPlayerEntity p, String path, Reply<LoadedPalette> reply) throws EditRejected {
            of(p).loadPalette(p, path, reply);
        }

        @Override
        public void access(ServerPlayerEntity p, String path, Reply<AssetAccess> reply) throws EditRejected {
            of(p).access(p, path, reply);
        }

        @Override
        public void setAccess(ServerPlayerEntity p, String path, AssetAccess access, Reply<AccessChange> reply)
                throws EditRejected {
            of(p).setAccess(p, path, access, reply);
        }

        @Override
        public Optional<LibraryChange> shownAccessChange(ServerPlayerEntity viewer, AccessChange change) {
            return findClipboards(viewer.getServer()).flatMap(service -> service.shownAccessChange(viewer, change));
        }
    }

    private static final class ScatterFacade implements ScatterService<ServerPlayerEntity> {
        @Override
        public void preview(ServerPlayerEntity p, C2S.ScatterPreview request, PreviewReply reply) throws EditRejected {
            findScatter(p.getServer()).orElseThrow(() -> new EditRejected(RejectReason.DISABLED, "engine not running"))
                    .preview(p, request, reply);
        }
    }

    private static final class TinkerFacade implements TinkerService<ServerPlayerEntity> {
        @Override
        public void block(ServerPlayerEntity p, BlockPos pos, int expected, int target,
                          dev.sculptory.core.tinker.SignText sign) throws EditRejected {
            of(p).block(p, pos, expected, target, sign);
        }

        @Override
        public dev.sculptory.core.tinker.EntityView entity(ServerPlayerEntity p, UUID id,
                                                              List<dev.sculptory.core.tinker.EntityEdit> edits)
                throws EditRejected {
            return of(p).entity(p, id, edits);
        }
    }

    private static final class PermissionFacade implements PermissionService<ServerPlayerEntity, ServerWorld> {
        @Override
        public boolean has(ServerPlayerEntity p, Perm node) {
            return EngineRuntime.find(p.getServer()).map(runtime -> runtime.permissions().has(p, node)).orElse(false);
        }

        @Override
        public ChunkPermit chunk(ServerPlayerEntity p, ServerWorld w, int cx, int cz, Box bounds) {
            return EngineRuntime.find(p.getServer()).map(runtime -> runtime.permissions().chunk(p, w, cx, cz, bounds))
                    .orElse(ChunkPermit.DENY);
        }
    }
}
