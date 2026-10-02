package dev.sculptory.fabric.engine.impl;

import com.mojang.authlib.GameProfile;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.scatter.FeatureCatalog;
import dev.sculptory.core.scatter.ScatterPlanner;
import dev.sculptory.core.state.ModdedFacingFallback;
import dev.sculptory.core.state.VerticalFlip;
import dev.sculptory.fabric.config.FolderMigration;
import dev.sculptory.fabric.perm.FabricPermissionService;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.ClientSync;
import dev.sculptory.fabric.world.EntityTypeRules;
import dev.sculptory.fabric.world.FabricEntities;
import dev.sculptory.fabric.world.FabricNeighbourShapes;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.fabric.world.FabricTile;
import dev.sculptory.fabric.world.FabricTileMatcher;
import dev.sculptory.fabric.world.FabricWorldEntities;
import dev.sculptory.fabric.world.FabricWorldReader;
import dev.sculptory.fabric.world.FeatureGrower;
import dev.sculptory.fabric.world.FluidTrails;
import dev.sculptory.fabric.world.Relighter;
import dev.sculptory.fabric.world.WorldChecks;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.TinkerService;
import dev.sculptory.server.engine.impl.AckSink;
import dev.sculptory.server.engine.impl.BuilderMode;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.engine.impl.EngineEditService;
import dev.sculptory.server.engine.impl.EngineHost;
import dev.sculptory.server.engine.impl.HistoryService;
import dev.sculptory.server.engine.impl.TicketWindow;
import dev.sculptory.server.platform.BorderBounds;
import dev.sculptory.server.platform.Profile;
import dev.sculptory.server.platform.WriteOptions;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.UserCache;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-server engine: config, state space, permissions and the executor, created at {@code SERVER_STARTING}
 * and shut down at {@code SERVER_STOPPING}. {@link #install()} registers the lifecycle hooks once per JVM and is
 * idempotent; the mod initializer calls it (the GameTest mod calls it too, so tests run before that wiring).
 *
 * <p>It is also the Fabric {@link EngineHost}: the game as the shared engine sees it, over this
 * {@link MinecraftServer}.
 */
public final class EngineRuntime implements EngineHost<ServerPlayerEntity, ServerWorld> {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    private static boolean installed;
    private static volatile EngineRuntime current;

    /** Region-op chunk ticket: radius 0, expires after 600 ticks unless refreshed ({@link #chunkTickets}). */
    public static final ChunkTicketType<ChunkPos> EDIT_TICKET =
            ChunkTicketType.create("sculptory:edit", Comparator.comparingLong(ChunkPos::toLong), 600);
    /**
     * Prediction acknowledgements the game's own way ({@code networkHandler.updateSequence}; negative sequences are
     * ignored): the edit service's default, for GameTests and servers without the network layer.
     */
    public static final AckSink<ServerPlayerEntity> VANILLA_ACKS = (player, seq) -> {
        if (seq >= 0 && player.networkHandler != null) player.networkHandler.updateSequence(seq);
    };

    private final MinecraftServer server;
    /** The settings in effect: replaced as a whole by {@link #reload}, so a reader sees one config or the other. */
    private volatile SculptoryConfig config;
    private final FabricStateSpace states;
    private final FabricPermissionService permissions;
    private final EditExecutor<ServerWorld> executor;
    private final FluidTrails fluidTrails;
    /** Told the new config after a reload has applied it (server thread). */
    private final List<Consumer<SculptoryConfig>> reloadListeners = new CopyOnWriteArrayList<>();

    public EngineRuntime(MinecraftServer server, SculptoryConfig config) {
        this.server = Objects.requireNonNull(server);
        this.config = Objects.requireNonNull(config);
        this.states = buildStates(config);
        this.permissions = new FabricPermissionService(config);
        this.executor = new EditExecutor<>(this, EditExecutor.Settings.from(config, server.isDedicated()));
        this.fluidTrails = new FluidTrails(server.getThread());
    }

    /**
     * The server's state space for {@code config}: the modded facing fallback as {@code transform.moddedFacingFallback}
     * says (the handshake offers it to clients exactly when it is on).
     */
    public static FabricStateSpace buildStates(SculptoryConfig config) {
        return FabricStateSpace.build(config.transform.moddedFacingFallback);
    }

    /** Registers the server lifecycle and tick hooks (once per JVM). */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        ServerLifecycleEvents.SERVER_STARTING.register(EngineRuntime::start);
        // Every entity type is known once the worlds exist: scanned before any player can send a file.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> EntityTypeRules.scan(server.getOverworld()));
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            EngineRuntime runtime = current;
            if (runtime == null || runtime.server != server) return;
            runtime.executor.tick();
            runtime.fluidTrails.tick();
        });
        // After the world and network ticks: bulk writes reach players after vanilla's acks (EditExecutor.endTick).
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            EngineRuntime runtime = current;
            if (runtime != null && runtime.server == server) runtime.executor.endTick();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            EngineRuntime runtime = current;
            if (runtime != null && runtime.server == server) runtime.executor.shutdown();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            EngineRuntime runtime = current;
            if (runtime == null || runtime.server != server) return;
            runtime.fluidTrails.close();
            current = null;
        });
    }

    /** Where the server config lives: {@code config/sculptory/server.json}. */
    public static Path configFile() {
        return FolderMigration.configDir().resolve("server.json");
    }

    /** The engine of a running server. */
    public static EngineRuntime get(MinecraftServer server) {
        return find(server).orElseThrow(() -> new IllegalStateException("Sculptory engine is not running"));
    }

    public static Optional<EngineRuntime> find(MinecraftServer server) {
        EngineRuntime runtime = current;
        return runtime != null && runtime.server == server ? Optional.of(runtime) : Optional.empty();
    }

    public MinecraftServer server() {
        return server;
    }

    /** The settings in effect ({@link #reload} replaces them). */
    @Override
    public SculptoryConfig config() {
        return config;
    }

    /** Calls {@code listener} with the new config after each successful {@link #reload} (server thread). */
    public void onReload(Consumer<SculptoryConfig> listener) {
        reloadListeners.add(Objects.requireNonNull(listener));
    }

    /**
     * The outcome of {@link #reload}: {@code problem} is why nothing changed (the running config is kept), or null;
     * {@code applied} are the settings now in effect with new values, {@code needRestart} those changed in the file
     * that take effect only at the next start ({@link SculptoryConfig#RESTART_SECTIONS}), {@code adjustments} the
     * values it applies that were clamped into range.
     */
    public record Reload(Path file, String problem, List<SculptoryConfig.Change> applied,
                         List<SculptoryConfig.Change> needRestart, List<String> adjustments) {
        public boolean ok() {
            return problem == null;
        }
    }

    /**
     * Reads {@code file} again and applies it ({@code /sculptory reload}; server thread). A missing, unreadable, empty or
     * invalid file changes nothing: the running config stays, and the result says why. Otherwise the new values apply
     * to everything checked from now on: editing on or off, the permission fallback, the executor's budgets and queue
     * caps, the limits (clients learn them within 2 seconds, {@code PermissionsChanged}), scatter and entities. Jobs
     * already admitted keep the limits they were admitted with. {@code history}, {@code library} and {@code transform}
     * are wired in at start: their changes are reported as needing a restart and the running values stay.
     */
    public Reload reload(Path file) {
        if (!server.isOnThread()) throw new IllegalStateException("reload the config on the server thread");
        SculptoryConfig.Read read = SculptoryConfig.read(file);
        if (read.config() == null) {
            LOG.warn("Sculptory config reload: {} could not be used ({}); the running settings stay as they were",
                    file.toAbsolutePath(), read.problem());
            return new Reload(file, read.problem(), List.of(), List.of(), List.of());
        }
        SculptoryConfig running = config;
        List<SculptoryConfig.Change> changes = SculptoryConfig.changes(running, read.config());
        List<SculptoryConfig.Change> applied = changes.stream().filter(c -> !c.needsRestart()).toList();
        List<SculptoryConfig.Change> needRestart = changes.stream().filter(SculptoryConfig.Change::needsRestart)
                .toList();
        SculptoryConfig next = read.config().keepRestartSectionsOf(running);
        EditExecutor.Settings settings = EditExecutor.Settings.from(next, server.isDedicated());
        permissions.configure(next);
        executor.updateSettings(settings);
        config = next;
        for (Consumer<SculptoryConfig> listener : reloadListeners) {
            try {
                listener.accept(next);
            } catch (RuntimeException e) {
                LOG.error("Sculptory config reload: applying the new settings failed in part", e);
            }
        }
        LOG.info("Sculptory config reloaded from {}: {}{}", file.toAbsolutePath(),
                applied.isEmpty() ? "no setting changed" : "changed " + applied,
                needRestart.isEmpty() ? "" : "; changed in the file but applied only after a restart: " + needRestart);
        // Only the clamps of values this reload applies: the rest wait for a restart with their sections.
        List<String> adjustments = read.config().adjustments().stream()
                .filter(a -> !SculptoryConfig.needsRestart(SculptoryConfig.adjustmentKey(a))).toList();
        return new Reload(file, null, applied, needRestart, adjustments);
    }

    @Override
    public FabricStateSpace states() {
        return states;
    }

    @Override
    public FabricPermissionService permissions() {
        return permissions;
    }

    /** What fluid written by history steps did since ({@link FluidTrails}). */
    @Override
    public FluidTrails fluidTrails() {
        return fluidTrails;
    }

    @Override
    public EditExecutor<ServerWorld> executor() {
        return executor;
    }

    @Override
    public FabricWorldReader reader(ServerWorld world) {
        return new FabricWorldReader(world, states);
    }

    @Override
    public BlockWriter writer(ServerWorld world, WriteOptions options) {
        return new BlockWriter(world, states, options);
    }

    // ================================================================== the platform

    /** {@link #EDIT_TICKET} tickets in {@code world}. */
    @Override
    public TicketWindow.Tickets chunkTickets(ServerWorld world) {
        ServerChunkManager manager = world.getChunkManager();
        return new TicketWindow.Tickets() {
            @Override
            public void add(int cx, int cz) {
                ChunkPos pos = new ChunkPos(cx, cz);
                manager.addTicket(EDIT_TICKET, pos, 0, pos);
            }

            @Override
            public void remove(int cx, int cz) {
                ChunkPos pos = new ChunkPos(cx, cz);
                manager.removeTicket(EDIT_TICKET, pos, 0, pos);
            }

            /** Adding an equal ticket again restarts its expiry timer. */
            @Override
            public void refresh(int cx, int cz) {
                add(cx, cz);
            }
        };
    }

    /** A {@link BuilderService}: vanilla's own placement and break steps. */
    @Override
    public BuilderMode<ServerPlayerEntity> builderMode(EngineEditService<ServerPlayerEntity, ServerWorld> edits,
                                                       HistoryService history, EditExecutor<ServerWorld> executor,
                                                       LongSupplier clock) {
        return new BuilderService(edits, this, executor, history, clock);
    }

    /** {@link TinkerEdits}. */
    @Override
    public TinkerService<ServerPlayerEntity> tinker(EngineEditService<ServerPlayerEntity, ServerWorld> edits) {
        return new TinkerEdits(edits, this);
    }

    /** {@link NavigateService}. */
    @Override
    public S2C.NavigateResult navigate(ServerPlayerEntity player, C2S.Navigate request) {
        return NavigateService.navigate(this, player, request);
    }

    @Override
    public boolean isOnThread() {
        return server.isOnThread();
    }

    @Override
    public void execute(Runnable task) {
        server.execute(task);
    }

    @Override
    public int ticks() {
        return server.getTicks();
    }

    @Override
    public UUID id(ServerPlayerEntity player) {
        return player.getUuid();
    }

    @Override
    public String name(ServerPlayerEntity player) {
        return player.getGameProfile().getName();
    }

    @Override
    public ServerWorld world(ServerPlayerEntity player) {
        return player.getServerWorld();
    }

    @Override
    public ServerPlayerEntity online(UUID id) {
        return server.getPlayerManager().getPlayer(id);
    }

    @Override
    public ServerPlayerEntity online(String name) {
        return server.getPlayerManager().getPlayer(name);
    }

    /** The server's user cache only ({@code UserCache.getByUuid}). */
    @Override
    public Optional<Profile> knownProfile(UUID id) {
        UserCache cache = server.getUserCache();
        return cache == null ? Optional.empty() : cache.getByUuid(id).map(EngineRuntime::profile);
    }

    /** {@code UserCache.findByName}: the cache, else Mojang's session service. */
    @Override
    public Optional<Profile> lookUpProfile(String name) {
        UserCache cache = server.getUserCache();
        return cache == null ? Optional.empty() : cache.findByName(name).map(EngineRuntime::profile);
    }

    private static Profile profile(GameProfile profile) {
        return new Profile(profile.getId(), profile.getName());
    }

    /** {@link #VANILLA_ACKS}: {@code networkHandler.updateSequence}. */
    @Override
    public void acknowledge(ServerPlayerEntity player, int sequence) {
        VANILLA_ACKS.ack(player, sequence);
    }

    @Override
    public Iterable<ServerWorld> worlds() {
        return server.getWorlds();
    }

    @Override
    public String worldId(ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    /** The world's registry key. */
    @Override
    public Object worldKey(ServerWorld world) {
        return world.getRegistryKey();
    }

    @Override
    public boolean exists(ServerWorld world) {
        return server.getWorld(world.getRegistryKey()) == world;
    }

    @Override
    public int bottomY(ServerWorld world) {
        return world.getBottomY();
    }

    @Override
    public int topY(ServerWorld world) {
        return world.getTopY();
    }

    @Override
    public int bottomSection(ServerWorld world) {
        return world.getBottomSectionCoord();
    }

    @Override
    public int topSection(ServerWorld world) {
        return world.getTopSectionCoord();
    }

    @Override
    public boolean inBuildLimit(ServerWorld world, int x, int y, int z) {
        return WorldChecks.inBuildLimit(world, x, y, z);
    }

    @Override
    public boolean intersectsBuildLimit(ServerWorld world, Box box) {
        return WorldChecks.intersectsBuildLimit(world, box);
    }

    @Override
    public boolean chunkLoaded(ServerWorld world, int cx, int cz) {
        return WorldChecks.isChunkLoaded(world, cx, cz);
    }

    @Override
    public boolean insideBorder(ServerWorld world, int x, int z) {
        return WorldChecks.insideBorder(world.getWorldBorder(), x, z);
    }

    @Override
    public BorderBounds border(ServerWorld world) {
        WorldBorder border = world.getWorldBorder();
        return new BorderBounds(border.getBoundWest(), border.getBoundEast(), border.getBoundNorth(),
                border.getBoundSouth());
    }

    /** A {@link ClientSync}. */
    @Override
    public ClientSync clientUpdates(ServerWorld world, Predicate<UUID> predicting) {
        Objects.requireNonNull(predicting);
        return new ClientSync(world, player -> predicting.test(player.getUuid()));
    }

    @Override
    public int relightSection(ServerWorld world, int sx, int sy, int sz) {
        return Relighter.relightSection(world, sx, sy, sz);
    }

    @Override
    public FabricTileMatcher tileMatcher(ServerWorld world) {
        return new FabricTileMatcher(world.getRegistryManager(), states);
    }

    @Override
    public FabricNeighbourShapes neighbourShapes(ServerWorld world) {
        return new FabricNeighbourShapes(world, states);
    }

    @Override
    public FabricWorldEntities entities(ServerWorld world) {
        return new FabricWorldEntities(world);
    }

    /** {@link EntityTypeRules#scan}. */
    @Override
    public EntityTypeRules entityRules(ServerWorld world) {
        return EntityTypeRules.scan(world);
    }

    /** {@link EntityTypeRules#current}. */
    @Override
    public EntityTypeRules entityRules() {
        return EntityTypeRules.current();
    }

    @Override
    public boolean knownEntityType(String typeId) {
        return FabricEntities.knownType(typeId);
    }

    @Override
    public float[] entitySize(String typeId) {
        return FabricEntities.size(typeId);
    }

    /** {@link FabricTile#isServerCaptured}. */
    @Override
    public boolean serverCaptured(BlockEntityData tile) {
        return FabricTile.isServerCaptured(tile);
    }

    @Override
    public BlockEntityData untrusted(BlockEntityData tile) {
        return tile instanceof FabricTile captured && captured.serverCaptured()
                ? FabricTile.of(captured.typeId(), captured.copyNbt()) : tile;
    }

    /** A {@link FeatureGrower}. */
    @Override
    public FeatureGrower featureGrower(ServerWorld world, List<FeatureCatalog.FeatureDef> features, boolean survive) {
        return new FeatureGrower(world, states, features, survive);
    }

    @Override
    public int featureReach(FeatureCatalog.FeatureDef feature) {
        return FeatureGrower.reach(feature);
    }

    @Override
    public int featureAbove(FeatureCatalog.FeatureDef feature) {
        return FeatureGrower.above(feature);
    }

    @Override
    public int featureBelow() {
        return FeatureGrower.SPAN_DOWN;
    }

    @Override
    public ScatterPlanner.SurvivalCheck survivalCheck(ServerWorld world, int[] blockStates) {
        return ScatterSurvival.survivalCheck(states, world, blockStates);
    }

    private static void start(MinecraftServer server) {
        SculptoryConfig config = SculptoryConfig.load(configFile());
        current = new EngineRuntime(server, config);
        current.fluidTrails.install();
        ModdedFacingFallback fallback = current.states.moddedFacingFallbackTables();
        VerticalFlip flip = current.states.verticalFlipTables();
        LOG.info("Sculptory engine started ({} block states, editing {}, modded facing fallback {}: {} states of {} "
                + "blocks, tables built in {} ms; upside-down flip: {} states kept, {} with unknown properties, built in "
                + "{} ms)",
                current.states.size(), config.editingEnabled ? "enabled" : "disabled",
                fallback.enabled() ? "on" : "off", fallback.turnedStates(), fallback.turnedBlocks(),
                fallback.buildNanos() / 1_000_000, flip.keptStates(), flip.unknownStates(), flip.buildNanos() / 1_000_000);
    }
}
