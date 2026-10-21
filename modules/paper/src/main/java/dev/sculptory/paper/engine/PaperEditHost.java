package dev.sculptory.paper.engine;

import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.store.HistoryStore;
import dev.sculptory.core.history.store.StorageIo;
import dev.sculptory.mc.config.FolderMigration;
import dev.sculptory.mc.engine.impl.McHistoryCodec;
import dev.sculptory.mc.loader.ServerLoader;
import dev.sculptory.mc.net.NetEditEvents;
import dev.sculptory.mc.net.ServerNet;
import dev.sculptory.mc.schem.McDataFixHook;
import dev.sculptory.mc.world.EntityTypeRules;
import dev.sculptory.paper.perm.Protection;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.BuilderOutcome;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.EditService;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.RunOptions;
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
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The engine on a Paper server, wired as the loaders' {@code EngineRuntime} and {@code EditServiceHost} wire it (same
 * hooks, same order), but once: a Paper server runs once per JVM and the plugin is enabled after the worlds load, so
 * there are no per-start facades. {@link #start} makes the engine, the edit service with its history store, the
 * clipboards with the library and scatter, and installs the common {@link ServerNet} over them.
 */
public final class PaperEditHost {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** How often the library index is written if it changed. */
    private static final int INDEX_FLUSH_TICKS = 40;
    /** How long the plugin's disable waits for the undo history to be written. */
    private static final long HISTORY_CLOSE_MILLIS = 30_000;
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

    private final PaperEngine engine;
    private final EngineEditService<ServerPlayer, ServerLevel> service;
    private final ServerClipboards<ServerPlayer, ServerLevel> clipboards;
    private final ServerScatter<ServerPlayer, ServerLevel> scatter;
    /** The crash-safe history hooks (set once by {@link #start}). */
    private HistorySaveHooks saveHooks;

    private PaperEditHost(PaperEngine engine, EngineEditService<ServerPlayer, ServerLevel> service,
                          ServerClipboards<ServerPlayer, ServerLevel> clipboards,
                          ServerScatter<ServerPlayer, ServerLevel> scatter) {
        this.engine = engine;
        this.service = service;
        this.clipboards = clipboards;
        this.scatter = scatter;
    }

    /** Where the config lives: {@code plugins/Sculptory/server.json} (the plugin's data folder). */
    public static Path configFile(Path dataFolder) {
        return dataFolder.resolve("server.json");
    }

    /** Starts the engine on {@code server} and registers its hooks with {@code loader}; once per JVM. */
    public static PaperEditHost start(Plugin plugin, MinecraftServer server, ServerLoader loader,
                                      Protection protection) {
        Path dataFolder = plugin.getDataFolder().toPath().toAbsolutePath();
        SculptoryConfig config = SculptoryConfig.load(configFile(dataFolder));
        PaperEngine engine = new PaperEngine(server, config, protection);
        engine.fluidTrails().install();
        EntityTypeRules.scan(server.overworld());
        var service = new EngineEditService<>(engine, ServerNet::jobListener, ServerNet::predictionApplied,
                new NetEditEvents(), openHistory(server, engine));
        var clips = new ServerClipboards<>(service, new Library(dataFolder.resolve("library"),
                config.toLibrarySettings()), ServerClipboards.newExecutor(), McDataFixHook.get());
        clips.start();
        var planning = new ServerScatter<>(service);
        engine.executor().addLane(planning.lane(), config.scatter.tickShare);
        PaperEditHost host = new PaperEditHost(engine, service, clips, planning);
        host.registerHooks(loader);
        host.saveHooks = HistorySaveHooks.register(plugin, service);
        // After the history hooks: on a chunk unload the trails are folded before the marks are written.
        PaperFluidTrails.register(plugin, engine.fluidTrails());
        dev.sculptory.paper.perm.PermissionWatch.register(plugin, service, planning);
        org.bukkit.Bukkit.getPluginManager().registerEvents(new BuilderModeListener(service), plugin);
        ServerNet.install(new NetFacade(service), clips, planning, service, engine.permissions(),
                () -> engine.config().toLimits(), engine::states);
        LOG.info("Sculptory engine started on Paper ({} block states, editing {}, protection: {})", engine.states().size(),
                config.editingEnabled ? "enabled" : "disabled", protection.name());
        return host;
    }

    public PaperEngine engine() {
        return engine;
    }

    public EngineEditService<ServerPlayer, ServerLevel> service() {
        return service;
    }

    /**
     * How often the history save hooks ran so far: chunk unloads, full world saves, periodic world saves (the dev
     * self-test checks which path a save took).
     */
    public long[] historySaveHookCounts() {
        return saveHooks == null ? new long[3] : saveHooks.counts();
    }

    /** The hooks in the loaders' order: the engine's ticks first, then the edit service's, then (ServerNet) the network's. */
    private void registerHooks(ServerLoader loader) {
        loader.onStartServerTick(server -> engine.tickStart());
        loader.onEndServerTick(server -> {
            engine.tickEnd();
            service.tick();
            scatter.housekeeping();
            if (server.getTickCount() % INDEX_FLUSH_TICKS == 0) clipboards.flushSoon();
        });
        loader.onWorldUnload((server, world) -> engine.worldUnloaded(world));
        loader.onPlayerJoin(player -> {
            try {
                service.playerJoined(player.getUUID());
            } catch (RuntimeException | LinkageError e) {
                LOG.error("Sculptory: loading {}'s undo history failed", player.getGameProfile().getName(), e);
            }
        });
        loader.onPlayerDisconnect(this::playerLeft);
        loader.onServerStopping(server -> stop());
        loader.onServerStopped(server -> engine.close());
    }

    /** As the loaders' EditServiceHost: re-check permissions once more, end the scatter preview, let the player go. */
    private void playerLeft(ServerPlayer player) {
        String name = player.getGameProfile().getName();
        try {
            service.revalidate(player, false);
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: re-checking {}'s permissions on leaving failed", name, e);
        }
        try {
            scatter.playerLeft(player.getUUID());
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: ending {}'s scatter preview on leaving failed", name, e);
        }
        try {
            service.playerLeft(player.getUUID());
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: letting go of {} on leaving failed", name, e);
        }
    }

    /** Before the worlds are saved (plugin disable): jobs end, the history is written, the library closes. */
    private void stop() {
        // Whatever the shutdown steps throw (a send to a client, a job listener), the history is still written.
        try {
            engine.shutdown();
            scatter.shutdown();
            service.shutdown();
        } finally {
            try {
                if (!service.historyService().closeStore(HISTORY_CLOSE_MILLIS)) {
                    LOG.warn("Sculptory: saving the undo history did not finish within {} s; the last steps may be "
                            + "missing after the restart", HISTORY_CLOSE_MILLIS / 1000);
                }
            } finally {
                clipboards.shutdown();
            }
        }
    }

    /** The world's undo history store ({@code <world>/sculptory/history}), or null for memory only. */
    private static HistoryService.Persistence openHistory(MinecraftServer server, PaperEngine engine) {
        SculptoryConfig config = engine.config();
        if (!config.history.persist) {
            LOG.info("Sculptory: undo history is kept in memory only (history.persist is off)");
            return null;
        }
        Path world = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path dir = FolderMigration.worldDir(world).resolve("history").normalize();
        try {
            HistoryStore store = HistoryStore.open(dir,
                    new McHistoryCodec(engine.states(), McDataFixHook.currentDataVersion()),
                    HistoryStore.Settings.DEFAULTS, StorageIo.SYSTEM, STORE_LOG);
            LOG.info("Sculptory: undo history is saved in {}", dir);
            return new HistoryService.Persistence(store, config.history.maxDiskBytes, config.historyMaxAgeMillis(),
                    System::currentTimeMillis);
        } catch (IOException | RuntimeException e) {
            LOG.error("Sculptory: the undo history folder {} cannot be used ({}); history is kept in memory only",
                    dir, e.toString());
            return null;
        }
    }

    /**
     * The edit service as the dispatcher sees it, plus {@link HistoryView} so it sends the history after the handshake,
     * jobs and strokes (the loaders' EditServiceHost facade does the same).
     */
    private record NetFacade(EngineEditService<ServerPlayer, ServerLevel> service)
            implements EditService<ServerPlayer>, HistoryView<ServerPlayer> {
        NetFacade {
            Objects.requireNonNull(service);
        }

        @Override
        public JobTicket run(ServerPlayer p, OpSpec s, RunOptions o, JobListener l) throws EditRejected {
            return service.run(p, s, o, l);
        }

        @Override
        public void beginStroke(ServerPlayer p, int strokeId, BrushSpec spec) throws EditRejected {
            service.beginStroke(p, strokeId, spec);
        }

        @Override
        public DabOutcome dabs(ServerPlayer p, int strokeId, int seq, List<Dab> dabs) {
            return service.dabs(p, strokeId, seq, dabs);
        }

        @Override
        public void predicted(ServerPlayer p) {
            service.predicted(p);
        }

        @Override
        public void endStroke(ServerPlayer p, int strokeId) {
            service.endStroke(p, strokeId);
        }

        @Override
        public JobTicket undo(ServerPlayer p, ConflictPolicy c) throws EditRejected {
            return service.undo(p, c);
        }

        @Override
        public JobTicket redo(ServerPlayer p, ConflictPolicy c) throws EditRejected {
            return service.redo(p, c);
        }

        @Override
        public JobTicket historyOverwrite(ServerPlayer p, boolean redo, int steps) throws EditRejected {
            return service.historyOverwrite(p, redo, steps);
        }

        @Override
        public void builderPowers(ServerPlayer p, int powers) {
            service.builderPowers(p, powers);
        }

        @Override
        public BuilderOutcome builderPlace(ServerPlayer p, C2S.BuilderPlace place) {
            return service.builderPlace(p, place);
        }

        @Override
        public BuilderOutcome builderBreak(ServerPlayer p, C2S.BuilderBreak breaks) {
            return service.builderBreak(p, breaks);
        }

        @Override
        public void builderDragEnd(ServerPlayer p, int dragId) {
            service.builderDragEnd(p, dragId);
        }

        @Override
        public S2C.NavigateResult navigate(ServerPlayer p, C2S.Navigate m) {
            return service.navigate(p, m);
        }

        @Override
        public boolean cancel(ServerPlayer p, UUID jobId) {
            return service.cancel(p, jobId);
        }

        @Override
        public S2C.HistoryState historyState(ServerPlayer player) {
            return service.history(player).toMessage();
        }
    }
}
