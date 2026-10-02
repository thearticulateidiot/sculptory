package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.runtime;

import com.mojang.authlib.GameProfile;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryLimits;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.engine.impl.AckSink;
import dev.sculptory.fabric.engine.impl.EditEvents;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.engine.impl.EngineEditService;
import dev.sculptory.fabric.engine.impl.EngineRuntime;
import dev.sculptory.fabric.engine.impl.JobRequest;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.HistorySnapshot;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.LoggerFactory;

/** Edit-service GameTest helpers: op'd mock players, a private service, world snapshots. Server thread only. */
final class EditTestSupport {
    private EditTestSupport() {}

    /**
     * A mock creative player with op level 2 (every node through the fallback, operator NBT allowed) and an
     * {@link EngineEditService} of the test's own over the live engine, recording acknowledgements and events.
     */
    static final class Harness {
        final TestContext context;
        final EngineRuntime runtime;
        final ServerWorld world;
        final ServerPlayerEntity player;
        final RecordingAcks acks = new RecordingAcks();
        final RecordingEvents events = new RecordingEvents();
        final EngineEditService service;
        private final List<ServerPlayerEntity> players = new ArrayList<>();

        Harness(TestContext context) {
            this(context, null);
        }

        Harness(TestContext context, HistoryLimits limits) {
            this(context, limits, System::nanoTime);
        }

        Harness(TestContext context, HistoryLimits limits, LongSupplier clock) {
            this(context, limits, clock, null);
        }

        /** With a private executor (ticked by the test) instead of the server's. */
        Harness(TestContext context, HistoryLimits limits, LongSupplier clock, EditExecutor executor) {
            this.context = context;
            this.runtime = runtime(context);
            this.world = context.getWorld();
            this.player = addPlayer();
            this.service = new EngineEditService(runtime, executor == null ? runtime.executor() : executor,
                    limits == null ? runtime.config().toHistoryLimits() : limits, p -> JobRequest.NO_LISTENER, acks,
                    events, clock);
            acks.service = service;
        }

        ServerPlayerEntity addPlayer() {
            return addPlayer(true);
        }

        /** Another mock creative player: op level 2, or (when {@code op} is false) level 0 with no nodes. */
        @SuppressWarnings("removal") // createMockCreativeServerPlayerInWorld is vanilla's test-only mock player
        ServerPlayerEntity addPlayer(boolean op) {
            ServerPlayerEntity p = context.createMockCreativeServerPlayerInWorld();
            if (op) world.getServer().getPlayerManager().getOpList().add(new OperatorEntry(p.getGameProfile(), 2, false));
            players.add(p);
            return p;
        }

        int state(String spec) {
            return EngineTestSupport.handle(runtime.states(), spec);
        }

        JobTicket fill(ServerPlayerEntity p, Box box, String state, JobListener listener) {
            try {
                return service.run(p, new OpSpec.Fill(box, new Pattern.Single(state(state)), CellMask.ANY),
                        RunOptions.DEFAULT, listener);
            } catch (EditRejected e) {
                throw new GameTestException("fill rejected: " + e.getMessage());
            }
        }

        JobTicket fill(Box box, String state, JobListener listener) {
            return fill(player, box, state, listener);
        }

        JobTicket undo(JobListener listener) {
            try {
                return service.undo(player, ConflictPolicy.SKIP_CONFLICTS, listener);
            } catch (EditRejected e) {
                throw new GameTestException("undo rejected: " + e.getMessage());
            }
        }

        JobTicket redo(JobListener listener) {
            try {
                return service.redo(player, ConflictPolicy.SKIP_CONFLICTS, listener);
            } catch (EditRejected e) {
                throw new GameTestException("redo rejected: " + e.getMessage());
            }
        }

        HistorySnapshot history() {
            return service.history(player);
        }

        /** Deops and removes the mock players. */
        void close() {
            MinecraftServer server = world.getServer();
            for (ServerPlayerEntity p : players) {
                GRANTS.remove(p.getUuid());
                DENIALS.remove(p.getUuid());
                FAILING.remove(p.getUuid());
                FAILED_CHECKS.remove(p.getUuid());
                WORLD_GRANTS.remove(p.getUuid());
                try {
                    server.getPlayerManager().getOpList().remove(p.getGameProfile());
                    service.playerLeft(p.getUuid());
                    server.getPlayerManager().remove(p);
                } catch (RuntimeException e) {
                    LoggerFactory.getLogger("sculptory").warn("Could not remove a mock player", e);
                }
            }
            players.clear();
        }
    }

    /** Permission nodes granted to (non-op) test players through fabric-permissions-api's check event. */
    private static final Map<UUID, Set<String>> GRANTS = new ConcurrentHashMap<>();
    /** Permission nodes a permissions mod explicitly refuses to test players, ops included. */
    private static final Map<UUID, Set<String>> DENIALS = new ConcurrentHashMap<>();
    /** Players whose permission checks throw, as a broken permissions mod would. */
    private static final Set<UUID> FAILING = ConcurrentHashMap.newKeySet();
    /** How many checks threw, per player. */
    private static final Map<UUID, Integer> FAILED_CHECKS = new ConcurrentHashMap<>();
    /** Nodes granted only while the player is in a given world. */
    private static final Map<UUID, Map<RegistryKey<World>, Set<String>>> WORLD_GRANTS = new ConcurrentHashMap<>();
    private static boolean grantsInstalled;

    private static synchronized void installGrants() {
        if (grantsInstalled) return;
        grantsInstalled = true;
        PermissionCheckEvent.EVENT.register((source, node) -> {
            if (source instanceof ServerCommandSource command && command.getEntity() != null) {
                UUID id = command.getEntity().getUuid();
                if (FAILING.contains(id)) {
                    FAILED_CHECKS.merge(id, 1, Integer::sum);
                    throw new IllegalStateException("permission backend unavailable");
                }
                Set<String> denied = DENIALS.get(id);
                if (denied != null && denied.contains(node)) return TriState.FALSE;
                Set<String> granted = GRANTS.get(id);
                if (granted != null && granted.contains(node)) return TriState.TRUE;
                // A grant scoped to one world, like a LuckPerms world context: it holds only while the check's
                // source (the player) is in that world.
                Map<RegistryKey<World>, Set<String>> scoped = WORLD_GRANTS.get(id);
                if (scoped != null && command.getWorld() != null) {
                    Set<String> here = scoped.get(command.getWorld().getRegistryKey());
                    if (here != null && here.contains(node)) return TriState.TRUE;
                }
            }
            return TriState.DEFAULT;
        });
    }

    /** Makes every permission check for {@code player} throw (or stop throwing). */
    static void failPermissionChecks(ServerPlayerEntity player, boolean failing) {
        installGrants();
        if (failing) {
            FAILING.add(player.getUuid());
        } else {
            FAILING.remove(player.getUuid());
        }
    }

    /**
     * Grants {@code nodes} to {@code player} the way a permissions mod would ({@code PermissionCheckEvent}), so a
     * non-op player can edit; {@link Harness#close()} revokes it. Other nodes keep the op-level fallback.
     */
    static synchronized void grant(ServerPlayerEntity player, Perm... nodes) {
        installGrants();
        Set<String> set = ConcurrentHashMap.newKeySet();
        for (Perm node : nodes) set.add(node.node());
        GRANTS.put(player.getUuid(), set);
    }

    /**
     * Grants {@code nodes} to {@code player} only while they are in {@code world} (a world-scoped grant, as LuckPerms
     * contexts give); other grants are kept. Replaces earlier scoped grants for that world; lifted by
     * {@link #clearWorldGrants}.
     */
    static synchronized void grantIn(ServerPlayerEntity player, RegistryKey<World> world, Perm... nodes) {
        installGrants();
        Set<String> set = ConcurrentHashMap.newKeySet();
        for (Perm node : nodes) set.add(node.node());
        WORLD_GRANTS.computeIfAbsent(player.getUuid(), id -> new ConcurrentHashMap<>()).put(world, set);
    }

    static void clearWorldGrants(ServerPlayerEntity player) {
        WORLD_GRANTS.remove(player.getUuid());
    }

    /**
     * A mock creative player named {@code name}, connected like vanilla's {@code createMockCreativeServerPlayerInWorld}
     * (which names every mock player "test-mock-player"). The caller removes it from the player manager.
     */
    static ServerPlayerEntity namedMockPlayer(TestContext context, String name) {
        ServerWorld world = context.getWorld();
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        ConnectedClientData data = ConnectedClientData.createDefault(profile, false);
        ServerPlayerEntity player = new ServerPlayerEntity(world.getServer(), world, data.gameProfile(), data.syncedOptions()) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return true;
            }
        };
        ClientConnection connection = new ClientConnection(NetworkSide.SERVERBOUND);
        new EmbeddedChannel(connection);
        world.getServer().getPlayerManager().onPlayerConnect(connection, player, data);
        return player;
    }

    /** How many of {@code player}'s permission checks threw ({@link #failPermissionChecks}). */
    static int failedChecks(ServerPlayerEntity player) {
        return FAILED_CHECKS.getOrDefault(player.getUuid(), 0);
    }

    /**
     * Refuses {@code nodes} to {@code player} the way a permissions mod would (an explicit {@code false}, which beats
     * the op-level fallback); {@link Harness#close()} lifts it. Replaces earlier denials.
     */
    static synchronized void deny(ServerPlayerEntity player, Perm... nodes) {
        installGrants();
        Set<String> set = ConcurrentHashMap.newKeySet();
        for (Perm node : nodes) set.add(node.node());
        DENIALS.put(player.getUuid(), set);
    }

    /** Command output collected as plain strings. */
    static final class CapturedOutput implements CommandOutput {
        final List<String> lines = new ArrayList<>();

        @Override
        public void sendMessage(Text message) {
            lines.add(message.getString());
        }

        @Override
        public boolean shouldReceiveFeedback() {
            return true;
        }

        @Override
        public boolean shouldTrackOutput() {
            return true;
        }

        @Override
        public boolean shouldBroadcastConsoleToOps() {
            return false;
        }

        String all() {
            return String.join("\n", lines);
        }
    }

    /** Acknowledged sequences, in order, with the player's queued dabs at each acknowledgement. */
    static final class RecordingAcks implements AckSink {
        final List<Integer> seqs = new ArrayList<>();
        final List<Integer> queuedAtAck = new ArrayList<>();
        EngineEditService service;

        @Override
        public void ack(ServerPlayerEntity player, int seq) {
            seqs.add(seq);
            queuedAtAck.add(service == null ? -1 : service.queuedDabs(player.getUuid()));
        }
    }

    static final class RecordingEvents implements EditEvents {
        final List<RejectReason> dabRejections = new ArrayList<>();
        final List<String> evictions = new ArrayList<>();
        int historyChanges;

        @Override
        public void historyChanged(ServerPlayerEntity player, HistorySnapshot snapshot) {
            historyChanges++;
        }

        @Override
        public void historyEvicted(ServerPlayerEntity player, int steps, boolean includesNewest) {
            evictions.add(player.getUuid() + ":" + steps + (includesNewest ? ":newest" : ""));
        }

        @Override
        public void dabRejected(ServerPlayerEntity player, int strokeId, int dabIndex, RejectReason reason) {
            dabRejections.add(reason);
        }

        final List<Long> scatterSkips = new ArrayList<>();

        @Override
        public void scatterSkipped(ServerPlayerEntity player, long placements) {
            scatterSkips.add(placements);
        }

        /** "stroke:dab:copies" per report. */
        final List<String> noGround = new ArrayList<>();

        @Override
        public void symmetryNoGround(ServerPlayerEntity player, int strokeId, int dabIndex, int copies) {
            noGround.add(strokeId + ":" + dabIndex + ":" + copies);
        }
    }

    /** Forces the chunks under {@code box} and loads them now (generating if needed). */
    static void loadAndForce(ServerWorld world, Box box) {
        EngineTestSupport.forceChunks(world, box, true);
        for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
            for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) world.getChunk(cx, cz);
        }
    }

    /** Every cell's state and every block entity's NBT in a box. */
    static final class WorldSnapshot {
        final Box box;
        final int[] states;
        final Map<Long, NbtCompound> tiles = new HashMap<>();

        WorldSnapshot(Box box, int[] states) {
            this.box = box;
            this.states = states;
        }

        int index(int x, int y, int z) {
            return ((y - box.min().y()) * box.sizeZ() + (z - box.min().z())) * box.sizeX() + (x - box.min().x());
        }

        int get(int x, int y, int z) {
            return states[index(x, y, z)];
        }
    }

    static WorldSnapshot capture(ServerWorld world, Box box) {
        int[] states = new int[Math.toIntExact(box.volume())];
        WorldSnapshot snapshot = new WorldSnapshot(box, states);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int i = 0;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    states[i++] = Block.getRawIdFromState(world.getBlockState(pos.set(x, y, z)));
                }
            }
        }
        for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
            for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) {
                WorldChunk chunk = world.getChunk(cx, cz);
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                    BlockPos p = entry.getKey();
                    if (!box.contains(p.getX(), p.getY(), p.getZ())) continue;
                    snapshot.tiles.put(p.asLong(), entry.getValue().createNbtWithId(world.getRegistryManager()));
                }
            }
        }
        return snapshot;
    }

    /** The first difference between two snapshots of the same box, or {@code null} when identical. */
    static String difference(WorldSnapshot expected, WorldSnapshot actual) {
        Box box = expected.box;
        int diffs = 0;
        String first = null;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    int a = expected.get(x, y, z), b = actual.get(x, y, z);
                    if (a == b) continue;
                    diffs++;
                    if (first == null) {
                        first = x + "," + y + "," + z + ": expected " + Block.getStateFromRawId(a) + ", got "
                                + Block.getStateFromRawId(b);
                    }
                }
            }
        }
        if (first != null) return diffs + " cells differ; first at " + first;
        if (!expected.tiles.keySet().equals(actual.tiles.keySet())) {
            return "block entities at " + positions(expected.tiles) + ", expected; got " + positions(actual.tiles);
        }
        for (Map.Entry<Long, NbtCompound> entry : expected.tiles.entrySet()) {
            NbtCompound got = actual.tiles.get(entry.getKey());
            if (!Objects.equals(entry.getValue(), got)) {
                return "block entity at " + BlockPos.fromLong(entry.getKey()).toShortString() + ": expected "
                        + entry.getValue() + ", got " + got;
            }
        }
        return null;
    }

    private static String positions(Map<Long, NbtCompound> tiles) {
        List<String> list = new ArrayList<>();
        for (long key : tiles.keySet()) list.add(BlockPos.fromLong(key).toShortString());
        return list.toString();
    }

    static void checkSame(WorldSnapshot expected, WorldSnapshot actual, String what) {
        String difference = difference(expected, actual);
        check(difference == null, what + ": " + difference);
    }

    /** A dab centred on block column (x, z) at height y, full pressure. */
    static Dab dab(int index, int x, int y, int z) {
        return new Dab(index, x * 16 + 8, y * 16, z * 16 + 8, Dab.FULL_PRESSURE);
    }

    /**
     * A copy of a box of the world as a {@link WorldReader} (like the core {@code FakeWorld}, which the GameTest
     * source set cannot see): states only, all its chunks loaded, no height hints. Reads outside the box throw.
     */
    static final class SnapshotWorld implements WorldReader {
        final StateSpace states;
        final Box box;
        final int bottomY;
        final int topY;
        final BlockBuffer blocks = new BlockBuffer();

        SnapshotWorld(ServerWorld world, StateSpace states, Box box) {
            this.states = states;
            this.box = box;
            this.bottomY = world.getBottomY();
            this.topY = world.getTopY();
            BlockPos.Mutable pos = new BlockPos.Mutable();
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    for (int x = box.min().x(); x <= box.max().x(); x++) {
                        blocks.set(x, y, z, Block.getRawIdFromState(world.getBlockState(pos.set(x, y, z))));
                    }
                }
            }
        }

        void set(int x, int y, int z, int h) {
            inside(x, y, z);
            blocks.set(x, y, z, h);
        }

        @Override
        public StateSpace states() {
            return states;
        }

        @Override
        public int bottomY() {
            return bottomY;
        }

        @Override
        public int topYExclusive() {
            return topY;
        }

        @Override
        public boolean isLoaded(int cx, int cz) {
            return true;
        }

        @Override
        public int get(int x, int y, int z) {
            if (y < bottomY || y >= topY) return states.air();
            inside(x, y, z);
            return blocks.get(x, y, z);
        }

        @Override
        public BlockEntityData tile(int x, int y, int z) {
            return null;
        }

        @Override
        public void copySection(int sx, int sy, int sz, SectionBuffer into) {
            throw new UnsupportedOperationException();
        }

        private void inside(int x, int y, int z) {
            if (!box.contains(x, y, z)) throw new GameTestException("snapshot read outside its box at " + x + "," + y + "," + z);
        }
    }
}
