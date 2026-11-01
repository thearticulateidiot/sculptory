package dev.sculptory.mc.engine.impl;

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
import dev.sculptory.mc.config.FolderMigration;
import dev.sculptory.mc.loader.Loaders;
import dev.sculptory.mc.loader.ServerLoader;
import dev.sculptory.mc.schem.McDataFixHook;
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
import dev.sculptory.server.engine.impl.AckSink;
import dev.sculptory.server.engine.impl.EditEvents;
import dev.sculptory.server.engine.impl.EngineEditService;
import dev.sculptory.server.engine.impl.HistoryService;
import dev.sculptory.server.engine.impl.ServerClipboards;
import dev.sculptory.server.engine.impl.ServerScatter;
import dev.sculptory.server.library.Library;
import dev.sculptory.server.net.HistoryView;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.storage.LevelResource;
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
 * Lifecycle: the service is created when the server is starting (after {@link EngineRuntime}), ticked at the end of
 * each server tick (stroke idle timeout), told about disconnects, and shut down when the server is stopping (after the
 * executor, which drops queued dabs and ends jobs first); the hooks are the installed {@link ServerLoader}'s.
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
    private static volatile EngineEditService<ServerPlayer, ServerLevel> current;
    private static volatile ServerClipboards<ServerPlayer, ServerLevel> clipboards;
    private static volatile ServerScatter<ServerPlayer, ServerLevel> scatter;

    private EditServiceHost() {}

    /**
     * Registers the lifecycle hooks (once per JVM; also installs {@link EngineRuntime}).
     *
     * @param listeners listeners for undo/redo jobs, e.g. {@code ServerNet::jobListener}
     * @param acks dab acknowledgements, e.g. {@code ServerNet::predictionApplied}
     */
    public static synchronized void install(Function<ServerPlayer, JobListener> listeners,
                                            AckSink<ServerPlayer> acks, EditEvents<ServerPlayer> events) {
        if (installed) throw new IllegalStateException("EditServiceHost is already installed");
        installed = true;
        Objects.requireNonNull(listeners);
        Objects.requireNonNull(acks);
        Objects.requireNonNull(events);
        // Its server-starting hook must run before ours: hooks registered earlier run earlier.
        EngineRuntime.install();
        ServerLoader loader = Loaders.server();
        loader.onServerStarting(server -> EngineRuntime.find(server).ifPresentOrElse(
                runtime -> {
                    CHUNK_SAVE_HOOKS.set(0);
                    OFF_THREAD_HOOKS.set(0);
                    hookWarned = false;
                    var service = new EngineEditService<>(runtime, listeners, acks, events,
                            openHistory(server, runtime));
                    var clips = new ServerClipboards<>(service, new Library(
                            defaultLibraryRoot(), runtime.config().toLibrarySettings()),
                            ServerClipboards.newExecutor(), McDataFixHook.get());
                    clips.start();
                    clipboards = clips;
                    var planning = new ServerScatter<>(service);
                    runtime.executor().addLane(planning.lane(), runtime.config().scatter.tickShare);
                    runtime.onReload(config -> runtime.executor().laneShare(planning.lane(), config.scatter.tickShare));
                    scatter = planning;
                    current = service;
                },
                () -> LOG.error("Sculptory: the engine did not start; editing is unavailable")));
        loader.onEndServerTick(server -> {
            var service = current;
            if (service != null && runs(service, server)) {
                service.tick();
                var planning = scatter;
                if (planning != null) planning.housekeeping();
                var clips = clipboards;
                if (clips != null && server.getTickCount() % INDEX_FLUSH_TICKS == 0) clips.flushSoon();
            }
        });
        loader.onAfterSave(server -> {
            try {
                checkChunkSaveHook(server);
            } catch (RuntimeException | LinkageError e) {
                LOG.error("Sculptory: checking the chunk-save hook failed", e);
            }
        });
        // Join and disconnect may arrive on a network thread (ServerLoader): hop onto the server thread.
        loader.onPlayerJoin(player -> {
            MinecraftServer server = player.server;
            if (server.isSameThread()) {
                playerJoined(server, player);
            } else {
                server.execute(() -> playerJoined(server, player));
            }
        });
        loader.onPlayerDisconnect(player -> {
            MinecraftServer server = player.server;
            if (server.isSameThread()) {
                playerLeft(server, player);
            } else {
                server.execute(() -> playerLeft(server, player));
            }
        });
        loader.onServerStopping(server -> {
            var service = current;
            if (service != null && runs(service, server)) {
                var planning = scatter;
                if (planning != null) planning.shutdown();
                service.shutdown();
                // Before the game saves the worlds: the history of everything in them is on disk first.
                if (!service.historyService().closeStore(HISTORY_CLOSE_MILLIS)) {
                    LOG.warn("Sculptory: saving the undo history did not finish within {} s; the last steps may "
                            + "be missing after the restart", HISTORY_CLOSE_MILLIS / 1000);
                }
                var clips = clipboards;
                if (clips != null) clips.shutdown();
            }
        });
        loader.onServerStopped(server -> {
            var service = current;
            if (service != null && runs(service, server)) {
                current = null;
                clipboards = null;
                scatter = null;
            }
        });
    }

    /** Where the asset library lives: {@code <gameDir>/sculptory/library}. */
    public static Path defaultLibraryRoot() {
        return FolderMigration.gameDir().resolve("library");
    }

    /**
     * Where a server saves its players' undo history: {@code <world>/sculptory/history}. The system property
     * {@value #HISTORY_DIR_PROPERTY} (tests only: the GameTest server sets it so the host's history is not left in the
     * test world) puts it in {@code <property>/<world folder name>} instead.
     */
    public static Path historyDir(MinecraftServer server) {
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
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
        var service = current;
        if (service == null || !runs(service, server) || !service.historyService().persistent()) return;
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
                    new McHistoryCodec(runtime.states(), McDataFixHook.currentDataVersion()),
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
    public static void playerJoined(MinecraftServer server, ServerPlayer player) {
        var service = current;
        if (service == null || !runs(service, server) || player == null) return;
        try {
            service.playerJoined(player.getUUID());
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: loading {}'s undo history failed", player.getGameProfile().getName(), e);
        }
    }

    /**
     * The game is about to save chunk (cx, cz) of {@code world} ({@code ServerChunkLoadingManagerMixin}): the undo
     * history of the engine's changes there is written first ({@link EngineEditService#beforeChunkSave}). Never throws.
     */
    public static void beforeChunkSave(ServerLevel world, int cx, int cz) {
        CHUNK_SAVE_HOOKS.incrementAndGet();
        var service = current;
        if (service == null || world == null || !runs(service, world.getServer())) return;
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
     * once more against their jobs in the world they were in ({@link EngineEditService#revalidate(Object,
     * boolean)}): a node removed just before leaving is not caught by the periodic re-check afterwards, which only
     * looks at online players. Then the scatter preview ends and the edit service lets go of the player (their jobs
     * that have not started are cancelled). A no-op without a running engine.
     */
    public static void playerLeft(MinecraftServer server, ServerPlayer player) {
        var service = current;
        if (service == null || !runs(service, server) || player == null) return;
        playerLeft(service, scatter, player);
    }

    /** {@link #playerLeft(MinecraftServer, ServerPlayer)} on the given services ({@code planning} may be null). */
    public static void playerLeft(EngineEditService<ServerPlayer, ServerLevel> service,
                                  ServerScatter<ServerPlayer, ServerLevel> planning, ServerPlayer player) {
        UUID id = player.getUUID();
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
     * right they no longer hold ({@link EngineEditService#revalidate(Object, boolean)}) and ends their
     * scatter preview if they may no longer scatter ({@link ServerScatter#revalidate}). A no-op without a running
     * engine. Safe from any thread: off the server thread it hops onto it.
     *
     * @param allWorlds true after {@code /op} or {@code /deop} (rights that hold everywhere); false when vanilla only
     *     resent the command tree (join, respawn, a change of world), which re-checks the jobs in the player's current
     *     world only, since a permissions mod may grant a node in one world only
     */
    public static void permissionsChanged(ServerPlayer player, boolean allWorlds) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if (!server.isSameThread()) {
            server.execute(() -> permissionsChanged(player, allWorlds));
            return;
        }
        var service = current;
        if (service == null || !runs(service, server)) return;
        try {
            service.revalidate(player, allWorlds);
            var planning = scatter;
            if (planning != null) planning.revalidate(player);
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: re-checking {}'s permissions failed", player.getGameProfile().getName(), e);
        }
    }

    /**
     * Builder mode: whether it acts for {@code player} now ({@link BuilderService#acts}: a power on and builder mode
     * allowed; false before the engine started), read by {@code ServerPlayerInteractionManagerMixin} to keep vanilla
     * from placing blocks while the player's clicks come as builder actions.
     */
    public static boolean builderActs(ServerPlayer player) {
        BuilderService builder = builder(player);
        return builder != null && builder.acts(player);
    }

    /** The running server's builder mode, or {@code null} before the engine started. */
    private static BuilderService builder(ServerPlayer player) {
        var service = current;
        if (service == null || !runs(service, player.getServer())) return null;
        return service.builderMode() instanceof BuilderService builder ? builder : null;
    }

    /**
     * Builder mode: the use of an item frame, painting or armor stand item on a block, from
     * {@code ServerPlayerInteractionManagerMixin} ({@link BuilderService#useEntityItem}); vanilla's own use without an
     * engine.
     */
    public static InteractionResult builderEntityItem(ServerPlayer player, ItemStack stack, UseOnContext context) {
        BuilderService builder = builder(player);
        if (builder == null) return stack.useOn(context);
        return builder.useEntityItem(player, context, stack::useOn);
    }

    /**
     * Builder mode: a player's interaction packet with {@code target} is about to run ({@code BuilderInteractMixin});
     * what to hand to {@link #afterEntityInteract}, or {@code null} when builder mode does not record it.
     */
    public static Object beforeEntityInteract(ServerPlayer player, Entity target) {
        BuilderService builder = builder(player);
        return builder == null ? null : builder.beforeInteract(player, target);
    }

    /** The interaction packet {@link #beforeEntityInteract} saw ran: what it changed becomes one history step. */
    public static void afterEntityInteract(Object touch) {
        if (!(touch instanceof BuilderService.EntityTouch entityTouch)) return;
        BuilderService builder = builder(entityTouch.player());
        if (builder != null) builder.afterInteract(entityTouch);
    }

    public static Optional<ServerScatter<ServerPlayer, ServerLevel>> findScatter(MinecraftServer server) {
        var service = scatter;
        var edits = current;
        return service != null && edits != null && runs(edits, server) ? Optional.of(service) : Optional.empty();
    }

    /** A {@link ScatterService} delegating to the running server's; refuses with {@code DISABLED} without one. */
    public static ScatterService<ServerPlayer> scatter() {
        return SCATTER;
    }

    /**
     * A {@link TinkerService} delegating to the running server's edit service; refuses with {@code DISABLED} without one.
     */
    public static TinkerService<ServerPlayer> tinker() {
        return TINKER;
    }

    /** The clipboard service of a running server. */
    public static Optional<ServerClipboards<ServerPlayer, ServerLevel>> findClipboards(MinecraftServer server) {
        var service = clipboards;
        return service != null && runs(service.edits(), server) ? Optional.of(service) : Optional.empty();
    }

    /** A {@link ClipboardService} delegating to the running server's; refuses with {@code DISABLED} without one. */
    public static ClipboardService<ServerPlayer> clipboards() {
        return CLIPBOARDS;
    }

    /** The service of a running server. */
    public static Optional<EngineEditService<ServerPlayer, ServerLevel>> find(MinecraftServer server) {
        var service = current;
        return service != null && runs(service, server) ? Optional.of(service) : Optional.empty();
    }

    /** An {@link EditService} (and {@link HistoryView}) delegating to the running server's service. */
    public static EditService<ServerPlayer> service() {
        return FACADE;
    }

    /** A {@link PermissionService} delegating to the running server's engine; denies everything without one. */
    public static PermissionService<ServerPlayer, ServerLevel> permissions() {
        return PERMISSIONS;
    }

    /** The running server's client-visible limits, or the defaults. */
    public static Limits limits() {
        var service = current;
        return service == null ? Limits.DEFAULTS : service.runtime().config().toLimits();
    }

    /** The running server's state space, or {@code null} before it starts. */
    public static StateSpace states() {
        var service = current;
        return service == null ? null : service.runtime().states();
    }

    /** Whether {@code service} is the engine of {@code server} (its platform is that server's runtime). */
    private static boolean runs(EngineEditService<ServerPlayer, ServerLevel> service, MinecraftServer server) {
        return service.runtime() instanceof EngineRuntime runtime && runtime.server() == server;
    }

    private static EngineEditService<ServerPlayer, ServerLevel> of(ServerPlayer p) throws EditRejected {
        return find(p.getServer()).orElseThrow(() -> new EditRejected(RejectReason.DISABLED, "engine not running"));
    }

    private static final class Facade implements EditService<ServerPlayer>, HistoryView<ServerPlayer> {
        @Override
        public JobTicket run(ServerPlayer p, OpSpec s, RunOptions o, JobListener l) throws EditRejected {
            return of(p).run(p, s, o, l);
        }

        @Override
        public void beginStroke(ServerPlayer p, int strokeId, BrushSpec spec) throws EditRejected {
            of(p).beginStroke(p, strokeId, spec);
        }

        @Override
        public DabOutcome dabs(ServerPlayer p, int strokeId, int seq, List<Dab> dabs) {
            var service = find(p.getServer());
            if (service.isPresent()) return service.get().dabs(p, strokeId, seq, dabs);
            int last = dabs == null || dabs.isEmpty() || dabs.get(dabs.size() - 1) == null
                    ? -1 : dabs.get(dabs.size() - 1).index();
            return DabOutcome.rejected(last, RejectReason.DISABLED);
        }

        @Override
        public void predicted(ServerPlayer p) {
            find(p.getServer()).ifPresent(service -> service.predicted(p));
        }

        @Override
        public void endStroke(ServerPlayer p, int strokeId) {
            find(p.getServer()).ifPresent(service -> service.endStroke(p, strokeId));
        }

        @Override
        public JobTicket undo(ServerPlayer p, ConflictPolicy c) throws EditRejected {
            return of(p).undo(p, c);
        }

        @Override
        public JobTicket redo(ServerPlayer p, ConflictPolicy c) throws EditRejected {
            return of(p).redo(p, c);
        }

        @Override
        public JobTicket historyOverwrite(ServerPlayer p, boolean redo, int steps) throws EditRejected {
            return of(p).historyOverwrite(p, redo, steps);
        }

        @Override
        public boolean editingEnabled(ServerPlayer p) {
            // Without a running engine every edit is refused: the client is told it may not edit at all.
            return find(p.getServer()).map(service -> service.editingEnabled(p)).orElse(false);
        }

        @Override
        public void builderPowers(ServerPlayer p, int powers) {
            find(p.getServer()).ifPresent(service -> service.builderPowers(p, powers));
        }

        @Override
        public BuilderOutcome builderPlace(ServerPlayer p, C2S.BuilderPlace place) {
            return find(p.getServer()).map(service -> service.builderPlace(p, place))
                    .orElse(BuilderOutcome.refused(BuilderOutcome.Refusal.DISABLED, "engine not running"));
        }

        @Override
        public BuilderOutcome builderBreak(ServerPlayer p, C2S.BuilderBreak breaks) {
            return find(p.getServer()).map(service -> service.builderBreak(p, breaks))
                    .orElse(BuilderOutcome.refused(BuilderOutcome.Refusal.DISABLED, "engine not running"));
        }

        @Override
        public void builderDragEnd(ServerPlayer p, int dragId) {
            find(p.getServer()).ifPresent(service -> service.builderDragEnd(p, dragId));
        }

        @Override
        public S2C.NavigateResult navigate(ServerPlayer p, C2S.Navigate m) {
            return find(p.getServer()).map(service -> service.navigate(p, m))
                    .orElse(S2C.NavigateResult.refused(m.reqId(), RejectReason.DISABLED));
        }

        @Override
        public boolean cancel(ServerPlayer p, UUID jobId) {
            return find(p.getServer()).map(service -> service.cancel(p, jobId)).orElse(false);
        }

        @Override
        public S2C.HistoryState historyState(ServerPlayer player) {
            return find(player.getServer()).map(service -> service.history(player).toMessage()).orElse(null);
        }
    }

    private static final class ClipboardFacade implements ClipboardService<ServerPlayer> {
        private static ServerClipboards<ServerPlayer, ServerLevel> of(ServerPlayer p) throws EditRejected {
            return findClipboards(p.getServer()).orElseThrow(() -> new EditRejected(RejectReason.DISABLED, "engine not running"));
        }

        @Override
        public JobTicket copy(ServerPlayer p, Region region, BlockPos origin, boolean cut, CellMask mask,
                              EntityFilter entities, JobListener cutListener, Reply<ClipboardInfo> reply)
                throws EditRejected {
            return of(p).copy(p, region, origin, cut, mask, entities, cutListener, reply);
        }

        @Override
        public JobTicket copy(ServerPlayer p, Box box, BlockPos origin, boolean cut, CellMask mask,
                              JobListener cutListener, Reply<ClipboardInfo> reply) throws EditRejected {
            return of(p).copy(p, box, origin, cut, mask, cutListener, reply);
        }

        @Override
        public void preview(ServerPlayer p, SourceRef source, Reply<Outbound> reply) throws EditRejected {
            of(p).preview(p, source, reply);
        }

        @Override
        public void export(ServerPlayer p, UUID clipboardId, SchematicFormat format, Reply<Outbound> reply)
                throws EditRejected {
            of(p).export(p, clipboardId, format, reply);
        }

        @Override
        public void export(ServerPlayer p, UUID clipboardId, Reply<Outbound> reply) throws EditRejected {
            of(p).export(p, clipboardId, reply);
        }

        @Override
        public Upload beginUpload(ServerPlayer p, String fileName, long totalBytes) throws EditRejected {
            return of(p).beginUpload(p, fileName, totalBytes);
        }

        @Override
        public SelectionUpload beginSelectionUpload(ServerPlayer p, Sha256 hash, Box bounds, long cells,
                                                    long totalBytes) throws EditRejected {
            return of(p).beginSelectionUpload(p, hash, bounds, cells, totalBytes);
        }

        @Override
        public Upload beginGeneratedUpload(ServerPlayer p, Box bounds, long cells, long totalBytes)
                throws EditRejected {
            return of(p).beginGeneratedUpload(p, bounds, cells, totalBytes);
        }

        @Override
        public void list(ServerPlayer p, String folder, Reply<Listing> reply) throws EditRejected {
            of(p).list(p, folder, reply);
        }

        @Override
        public void load(ServerPlayer p, String path, Reply<ClipboardInfo> reply) throws EditRejected {
            of(p).load(p, path, reply);
        }

        @Override
        public void save(ServerPlayer p, UUID clipboardId, String path, Reply<Saved> reply) throws EditRejected {
            of(p).save(p, clipboardId, path, reply);
        }

        // Every other method forwards too: an interface default here would answer DISABLED (or nothing) on a running
        // server. EditServiceHostFacadesTest fails when a facade leaves an interface method to its default.

        @Override
        public void move(ServerPlayer p, boolean folder, String from, String to, Reply<LibraryChange> reply)
                throws EditRejected {
            of(p).move(p, folder, from, to, reply);
        }

        @Override
        public void delete(ServerPlayer p, boolean folder, String path, Reply<LibraryChange> reply)
                throws EditRejected {
            of(p).delete(p, folder, path, reply);
        }

        @Override
        public void createFolder(ServerPlayer p, String path, Reply<LibraryChange> reply) throws EditRejected {
            of(p).createFolder(p, path, reply);
        }

        @Override
        public Optional<LibraryChange> shownTo(ServerPlayer viewer, LibraryChange change) {
            return findClipboards(viewer.getServer()).flatMap(service -> service.shownTo(viewer, change));
        }

        @Override
        public void savePalette(ServerPlayer p, String path, BlockPalette palette, Reply<LibraryChange> reply)
                throws EditRejected {
            of(p).savePalette(p, path, palette, reply);
        }

        @Override
        public void loadPalette(ServerPlayer p, String path, Reply<LoadedPalette> reply) throws EditRejected {
            of(p).loadPalette(p, path, reply);
        }

        @Override
        public void access(ServerPlayer p, String path, Reply<AssetAccess> reply) throws EditRejected {
            of(p).access(p, path, reply);
        }

        @Override
        public void setAccess(ServerPlayer p, String path, AssetAccess access, Reply<AccessChange> reply)
                throws EditRejected {
            of(p).setAccess(p, path, access, reply);
        }

        @Override
        public Optional<LibraryChange> shownAccessChange(ServerPlayer viewer, AccessChange change) {
            return findClipboards(viewer.getServer()).flatMap(service -> service.shownAccessChange(viewer, change));
        }
    }

    private static final class ScatterFacade implements ScatterService<ServerPlayer> {
        @Override
        public void preview(ServerPlayer p, C2S.ScatterPreview request, PreviewReply reply) throws EditRejected {
            findScatter(p.getServer()).orElseThrow(() -> new EditRejected(RejectReason.DISABLED, "engine not running"))
                    .preview(p, request, reply);
        }
    }

    private static final class TinkerFacade implements TinkerService<ServerPlayer> {
        @Override
        public void block(ServerPlayer p, BlockPos pos, int expected, int target,
                          dev.sculptory.core.tinker.SignText sign) throws EditRejected {
            of(p).block(p, pos, expected, target, sign);
        }

        @Override
        public dev.sculptory.core.tinker.EntityView entity(ServerPlayer p, UUID id,
                                                              List<dev.sculptory.core.tinker.EntityEdit> edits)
                throws EditRejected {
            return of(p).entity(p, id, edits);
        }
    }

    private static final class PermissionFacade implements PermissionService<ServerPlayer, ServerLevel> {
        @Override
        public boolean has(ServerPlayer p, Perm node) {
            return EngineRuntime.find(p.getServer()).map(runtime -> runtime.permissions().has(p, node)).orElse(false);
        }

        @Override
        public ChunkPermit chunk(ServerPlayer p, ServerLevel w, int cx, int cz, Box bounds) {
            return EngineRuntime.find(p.getServer()).map(runtime -> runtime.permissions().chunk(p, w, cx, cz, bounds))
                    .orElse(ChunkPermit.DENY);
        }
    }
}
