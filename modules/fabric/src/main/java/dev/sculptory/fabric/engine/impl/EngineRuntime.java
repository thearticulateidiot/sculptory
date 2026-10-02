package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.Box;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.state.ModdedFacingFallback;
import dev.sculptory.core.state.VerticalFlip;
import dev.sculptory.fabric.config.FolderMigration;
import dev.sculptory.fabric.perm.FabricPermissionService;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.EntityTypeRules;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.fabric.world.FabricWorldReader;
import dev.sculptory.fabric.world.FluidTrails;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.RecordSink;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-server engine: config, state space, permissions and the executor, created at {@code SERVER_STARTING}
 * and shut down at {@code SERVER_STOPPING}. {@link #install()} registers the lifecycle hooks once per JVM and is
 * idempotent; the mod initializer calls it (the GameTest mod calls it too, so tests run before that wiring).
 */
public final class EngineRuntime {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    private static boolean installed;
    private static volatile EngineRuntime current;

    private final MinecraftServer server;
    /** The settings in effect: replaced as a whole by {@link #reload}, so a reader sees one config or the other. */
    private volatile SculptoryConfig config;
    private final FabricStateSpace states;
    private final FabricPermissionService permissions;
    private final EditExecutor executor;
    private final FluidTrails fluidTrails;
    /** Told the new config after a reload has applied it (server thread). */
    private final List<Consumer<SculptoryConfig>> reloadListeners = new CopyOnWriteArrayList<>();

    public EngineRuntime(MinecraftServer server, SculptoryConfig config) {
        this.server = Objects.requireNonNull(server);
        this.config = Objects.requireNonNull(config);
        this.states = buildStates(config);
        this.permissions = new FabricPermissionService(config);
        this.executor = new EditExecutor(server, states, EditExecutor.Settings.from(config, server.isDedicated()));
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

    public FabricStateSpace states() {
        return states;
    }

    public FabricPermissionService permissions() {
        return permissions;
    }

    /** What fluid written by history steps did since ({@link FluidTrails}). */
    public FluidTrails fluidTrails() {
        return fluidTrails;
    }

    public EditExecutor executor() {
        return executor;
    }

    public FabricWorldReader reader(ServerWorld world) {
        return new FabricWorldReader(world, states);
    }

    public BlockWriter writer(ServerWorld world, BlockWriter.Options options) {
        return new BlockWriter(world, states, options);
    }

    /**
     * A job request for a player, resolving the permission-dependent parts: editing enabled, physics
     * ({@code sculptory.physics}), operator NBT, per-chunk protection and loading unloaded chunks. Does not
     * check the op-level nodes for the op itself ({@code use}/{@code region}) or volume limits.
     *
     * @throws EditRejected {@code DISABLED} when editing is off, {@code NO_PERMISSION} for physics without the node
     */
    public JobRequest forPlayer(ServerPlayerEntity player, EditProgram program, RunOptions options,
                                JobListener listener, RecordSink records) throws EditRejected {
        if (!config.editingEnabled) throw new EditRejected(RejectReason.DISABLED);
        if (options.physics() && !permissions.has(player, Perm.PHYSICS)) {
            throw new EditRejected(RejectReason.NO_PERMISSION, Perm.PHYSICS.node());
        }
        ServerWorld world = player.getServerWorld();
        Box bounds = program.bounds();
        PermitSource permits = PermitSource.forPlayer(permissions, player, world, bounds);
        BlockWriter.Options write = new BlockWriter.Options(options.physics(), permissions.mayWriteOperatorNbt(player));
        return new JobRequest(player.getUuid(), world, program, write, permits,
                permissions.has(player, Perm.EDIT_UNLOADED), ThreadLocalRandom.current().nextLong(), listener, records);
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
