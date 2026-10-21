package dev.sculptory.paper.engine;

import com.mojang.authlib.GameProfile;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.scatter.FeatureCatalog;
import dev.sculptory.core.scatter.ScatterPlanner;
import dev.sculptory.mc.engine.impl.McHost;
import dev.sculptory.mc.engine.impl.BuilderService;
import dev.sculptory.mc.engine.impl.NavigateService;
import dev.sculptory.mc.engine.impl.PaperEngineParts;
import dev.sculptory.mc.perm.McPermissionService;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.mc.world.ClientSync;
import dev.sculptory.mc.world.EntityTypeRules;
import dev.sculptory.mc.world.FeatureGrower;
import dev.sculptory.mc.world.FluidTrails;
import dev.sculptory.mc.world.McEntities;
import dev.sculptory.mc.world.McNeighbourShapes;
import dev.sculptory.mc.world.McStateSpace;
import dev.sculptory.mc.world.McTile;
import dev.sculptory.mc.world.McTileMatcher;
import dev.sculptory.mc.world.McWorldBiomes;
import dev.sculptory.mc.world.McWorldEntities;
import dev.sculptory.mc.world.McWorldReader;
import dev.sculptory.mc.world.Relighter;
import dev.sculptory.mc.world.WorldChecks;
import dev.sculptory.paper.perm.PaperPermissions;
import dev.sculptory.paper.perm.Protection;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.TinkerService;
import dev.sculptory.server.engine.impl.BuilderMode;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.engine.impl.EngineEditService;
import dev.sculptory.server.engine.impl.EngineHost;
import dev.sculptory.server.engine.impl.HistoryService;
import dev.sculptory.server.engine.impl.TicketWindow;
import dev.sculptory.server.platform.BorderBounds;
import dev.sculptory.server.platform.Profile;
import dev.sculptory.server.platform.WorldBiomes;
import dev.sculptory.server.platform.WriteOptions;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;
import org.bukkit.Location;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * The engine host on Paper: the shared engine sees the game through this. P is the NMS {@link ServerPlayer} and W the
 * NMS {@link ServerLevel} (Paper's server classes, Mojang-named), the same types as on Fabric and NeoForge, so the
 * common world layer (reader, writer, client sync, entities, tiles, biomes, features) is used as it is, compiled from
 * modules/common through the Paper patches (PaperAccess). What differs from the loaders' {@code EngineRuntime}:
 * permissions add a region plugin ({@link PaperPermissions}); builder mode carries out its steps with CraftBukkit's
 * block capture and fires Bukkit's block events ({@link PaperBuilderSteps}); Navigate moves the player through
 * Bukkit's teleport ({@link #teleport}); the light batch is off (Paper's Moonrise light engine); fluid trails are fed
 * by Bukkit's fluid events instead of mixins ({@code PaperFluidTrails}).
 */
public final class PaperEngine implements EngineHost<ServerPlayer, ServerLevel>, McHost {
    /** Region-op chunk ticket, as on the loaders: radius 0, expires after 600 ticks unless refreshed. */
    public static final TicketType<ChunkPos> EDIT_TICKET =
            TicketType.create("sculptory:edit", Comparator.comparingLong(ChunkPos::toLong), 600);

    private final MinecraftServer server;
    private volatile SculptoryConfig config;
    private final McStateSpace states;
    private final PaperPermissions permissions;
    private final EditExecutor<ServerLevel> executor;
    private final FluidTrails fluidTrails;
    /** Each world's biomes; removed when the world unloads. Server thread only. */
    private final Map<ServerLevel, McWorldBiomes> worldBiomes = new IdentityHashMap<>();

    public PaperEngine(MinecraftServer server, SculptoryConfig config, Protection protection) {
        this.server = Objects.requireNonNull(server);
        this.config = Objects.requireNonNull(config);
        this.states = McStateSpace.build(config.transform.moddedFacingFallback);
        this.permissions = new PaperPermissions(new McPermissionService(config), protection);
        this.executor = new EditExecutor<>(this, EditExecutor.Settings.from(config, server.isDedicatedServer()));
        this.fluidTrails = new FluidTrails(server.getRunningThread());
    }

    public MinecraftServer server() {
        return server;
    }

    void worldUnloaded(ServerLevel world) {
        worldBiomes.remove(world);
    }

    void tickStart() {
        executor.tick();
        fluidTrails.tick();
    }

    void tickEnd() {
        executor.endTick();
    }

    void shutdown() {
        executor.shutdown();
    }

    void close() {
        fluidTrails.close();
    }

    @Override
    public SculptoryConfig config() {
        return config;
    }

    @Override
    public McStateSpace states() {
        return states;
    }

    @Override
    public PaperPermissions permissions() {
        return permissions;
    }

    @Override
    public FluidTrails fluidTrails() {
        return fluidTrails;
    }

    @Override
    public EditExecutor<ServerLevel> executor() {
        return executor;
    }

    @Override
    public McWorldReader reader(ServerLevel world) {
        return new McWorldReader(world, states);
    }

    @Override
    public BlockWriter writer(ServerLevel world, WriteOptions options) {
        return new BlockWriter(world, states, options);
    }

    @Override
    public TicketWindow.Tickets chunkTickets(ServerLevel world) {
        ServerChunkCache chunks = world.getChunkSource();
        return new TicketWindow.Tickets() {
            @Override
            public void add(int cx, int cz) {
                ChunkPos pos = new ChunkPos(cx, cz);
                chunks.addRegionTicket(EDIT_TICKET, pos, 0, pos);
            }

            @Override
            public void remove(int cx, int cz) {
                ChunkPos pos = new ChunkPos(cx, cz);
                chunks.removeRegionTicket(EDIT_TICKET, pos, 0, pos);
            }

            @Override
            public void refresh(int cx, int cz) {
                add(cx, cz);
            }
        };
    }

    @Override
    public BuilderMode<ServerPlayer> builderMode(EngineEditService<ServerPlayer, ServerLevel> edits,
                                                 HistoryService history, EditExecutor<ServerLevel> executor,
                                                 LongSupplier clock) {
        return new BuilderService(edits, this, server, new PaperBuilderSteps(), executor, history, clock);
    }

    @Override
    public TinkerService<ServerPlayer> tinker(EngineEditService<ServerPlayer, ServerLevel> edits) {
        return PaperEngineParts.tinker(edits, this);
    }

    @Override
    public S2C.NavigateResult navigate(ServerPlayer player, C2S.Navigate request) {
        return NavigateService.navigate(this, player, request);
    }

    /**
     * Navigate's move through Bukkit's {@code Player.teleport} (cause PLUGIN), so {@code PlayerTeleportEvent} fires:
     * CMI's {@code /back} notes where the player was, WorldGuard's entry rules may refuse it (then nothing moves).
     */
    @Override
    public boolean teleport(ServerPlayer player, double x, double y, double z, float yaw, float pitch) {
        Location to = new Location(player.serverLevel().getWorld(), x, y, z, yaw, pitch);
        return player.getBukkitEntity().teleport(to, PlayerTeleportEvent.TeleportCause.PLUGIN);
    }

    @Override
    public boolean isOnThread() {
        return server.isSameThread();
    }

    @Override
    public void execute(Runnable task) {
        server.execute(task);
    }

    @Override
    public int ticks() {
        return server.getTickCount();
    }

    @Override
    public UUID id(ServerPlayer player) {
        return player.getUUID();
    }

    @Override
    public String name(ServerPlayer player) {
        return player.getGameProfile().getName();
    }

    @Override
    public ServerLevel world(ServerPlayer player) {
        return player.serverLevel();
    }

    @Override
    public ServerPlayer online(UUID id) {
        return server.getPlayerList().getPlayer(id);
    }

    @Override
    public ServerPlayer online(String name) {
        return server.getPlayerList().getPlayerByName(name);
    }

    @Override
    public Optional<Profile> knownProfile(UUID id) {
        GameProfileCache cache = server.getProfileCache();
        return cache == null ? Optional.empty() : cache.get(id).map(PaperEngine::profile);
    }

    @Override
    public Optional<Profile> lookUpProfile(String name) {
        GameProfileCache cache = server.getProfileCache();
        return cache == null ? Optional.empty() : cache.get(name).map(PaperEngine::profile);
    }

    private static Profile profile(GameProfile profile) {
        return new Profile(profile.getId(), profile.getName());
    }

    @Override
    public void acknowledge(ServerPlayer player, int sequence) {
        if (sequence >= 0 && player.connection != null) player.connection.ackBlockChangesUpTo(sequence);
    }

    @Override
    public Iterable<ServerLevel> worlds() {
        return server.getAllLevels();
    }

    @Override
    public String worldId(ServerLevel world) {
        return world.dimension().location().toString();
    }

    @Override
    public Object worldKey(ServerLevel world) {
        return world.dimension();
    }

    @Override
    public boolean exists(ServerLevel world) {
        return server.getLevel(world.dimension()) == world;
    }

    @Override
    public int bottomY(ServerLevel world) {
        return world.getMinBuildHeight();
    }

    @Override
    public int topY(ServerLevel world) {
        return world.getMaxBuildHeight();
    }

    @Override
    public int bottomSection(ServerLevel world) {
        return world.getMinSection();
    }

    @Override
    public int topSection(ServerLevel world) {
        return world.getMaxSection();
    }

    @Override
    public boolean inBuildLimit(ServerLevel world, int x, int y, int z) {
        return WorldChecks.inBuildLimit(world, x, y, z);
    }

    @Override
    public boolean intersectsBuildLimit(ServerLevel world, Box box) {
        return WorldChecks.intersectsBuildLimit(world, box);
    }

    @Override
    public boolean chunkLoaded(ServerLevel world, int cx, int cz) {
        return WorldChecks.isChunkLoaded(world, cx, cz);
    }

    @Override
    public boolean insideBorder(ServerLevel world, int x, int z) {
        return WorldChecks.insideBorder(world.getWorldBorder(), x, z);
    }

    @Override
    public BorderBounds border(ServerLevel world) {
        WorldBorder border = world.getWorldBorder();
        return new BorderBounds(border.getMinX(), border.getMaxX(), border.getMinZ(), border.getMaxZ());
    }

    @Override
    public ClientSync clientUpdates(ServerLevel world, Predicate<UUID> predicting) {
        Objects.requireNonNull(predicting);
        return new ClientSync(world, player -> predicting.test(player.getUUID()));
    }

    @Override
    public int relightSection(ServerLevel world, int sx, int sy, int sz) {
        return Relighter.relightSection(world, sx, sy, sz);
    }

    @Override
    public McTileMatcher tileMatcher(ServerLevel world) {
        return new McTileMatcher(world.registryAccess(), states);
    }

    @Override
    public McNeighbourShapes neighbourShapes(ServerLevel world) {
        return new McNeighbourShapes(world, states);
    }

    @Override
    public McWorldEntities entities(ServerLevel world) {
        return new McWorldEntities(world);
    }

    @Override
    public WorldBiomes biomes(ServerLevel world) {
        return worldBiomes.computeIfAbsent(world, McWorldBiomes::new);
    }

    @Override
    public EntityTypeRules entityRules(ServerLevel world) {
        return EntityTypeRules.scan(world);
    }

    @Override
    public EntityTypeRules entityRules() {
        return EntityTypeRules.current();
    }

    @Override
    public boolean knownEntityType(String typeId) {
        return McEntities.knownType(typeId);
    }

    @Override
    public float[] entitySize(String typeId) {
        return McEntities.size(typeId);
    }

    @Override
    public boolean serverCaptured(BlockEntityData tile) {
        return McTile.isServerCaptured(tile);
    }

    @Override
    public BlockEntityData untrusted(BlockEntityData tile) {
        return tile instanceof McTile captured && captured.serverCaptured()
                ? McTile.of(captured.typeId(), captured.copyNbt()) : tile;
    }

    @Override
    public FeatureGrower featureGrower(ServerLevel world, List<FeatureCatalog.FeatureDef> features, boolean survive) {
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
    public ScatterPlanner.SurvivalCheck survivalCheck(ServerLevel world, int[] blockStates) {
        return PaperEngineParts.survivalCheck(states, world, blockStates);
    }
}
