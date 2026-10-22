package dev.sculptory.mc.gametest;

import com.mojang.authlib.GameProfile;
import dev.sculptory.core.Box;
import dev.sculptory.mc.loader.Loaders;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Measuring tools for the opt-in benchmarks ({@link BenchGameTest}): whole-server tick times, a watching player that
 * collects the vanilla packets it would be sent (and sizes them as a real connection would), and a probe for when the
 * light engine has caught up. Server thread only.
 */
final class BenchSupport {
    static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** Vanilla's default {@code network-compression-threshold} on dedicated servers. */
    static final int COMPRESSION_THRESHOLD = 256;

    private BenchSupport() {}

    static boolean enabled() {
        return Boolean.getBoolean("sculptory.bench") || "1".equals(System.getenv("SCULPTORY_BENCH"));
    }

    /** Completes the test at once (and returns true) unless benchmarks are enabled. */
    static boolean skipped(GameTestHelper context, String name) {
        if (enabled()) return false;
        LOG.info("{} skipped (set SCULPTORY_BENCH=1 to run it)", name);
        context.succeed();
        return true;
    }

    static void log(String format, Object... args) {
        LOG.info(String.format(Locale.ROOT, format, args));
    }

    static double ms(long nanos) {
        return nanos / 1e6;
    }

    // ---------------------------------------------------------------- end-of-tick hooks

    private static final List<Consumer<MinecraftServer>> END_HOOKS = new CopyOnWriteArrayList<>();
    private static boolean hooksInstalled;

    private static synchronized void installHooks() {
        if (hooksInstalled) return;
        hooksInstalled = true;
        // The loader's end-of-tick hook (Fabric's END_SERVER_TICK, NeoForge's ServerTickEvent.Post).
        Loaders.server().onEndServerTick(server -> {
            for (Consumer<MinecraftServer> hook : END_HOOKS) hook.accept(server);
        });
    }

    /**
     * Whole-server tick durations: the wall time from one {@code END_SERVER_TICK} to the next, so everything a tick
     * does counts (the executor at {@code START_SERVER_TICK}, the world ticks, vanilla's flushing of block and light
     * updates, the network tick, and the edit engine's own {@code END_SERVER_TICK} work, which
     * {@code MinecraftServer.getTickTimes()} leaves out), plus the tasks run between ticks. The GameTest server ticks
     * without sleeping, so this is the work per tick.
     */
    static final class TickTimes implements AutoCloseable {
        private final LongArrayList nanos = new LongArrayList();
        private final Consumer<MinecraftServer> hook = this::record;
        private boolean recording;
        private boolean startPending;
        private boolean stopPending;

        TickTimes() {
            installHooks();
            END_HOOKS.add(hook);
        }

        /** Clears the times and records from the next tick on (the current tick's own work is not counted). */
        void startNextTick() {
            nanos.clear();
            recording = false;
            stopPending = false;
            startPending = true;
            gcCountStart = gcCount();
            gcMillisStart = gcMillis();
        }

        private long gcCountStart;
        private long gcMillisStart;
        private long gcCountEnd;
        private long gcMillisEnd;

        private static long gcCount() {
            long n = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, gc.getCollectionCount());
            return n;
        }

        private static long gcMillis() {
            long n = 0;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, gc.getCollectionTime());
            return n;
        }

        /** Records the current tick, then stops. */
        void stopAtTickEnd() {
            stopPending = true;
        }

        /** True once recording has stopped (after the tick {@link #stopAtTickEnd} was called in has ended). */
        boolean stopped() {
            return !recording && !startPending && !stopPending;
        }

        private long lastEnd;

        private void record(MinecraftServer server) {
            long now = System.nanoTime();
            if (recording && lastEnd != 0) nanos.add(now - lastEnd);
            lastEnd = now;
            if (stopPending) {
                recording = false;
                stopPending = false;
                gcCountEnd = gcCount();
                gcMillisEnd = gcMillis();
            }
            if (startPending) {
                recording = true;
                startPending = false;
            }
        }

        long max() {
            long max = 0;
            for (int i = 0; i < nanos.size(); i++) max = Math.max(max, nanos.getLong(i));
            return max;
        }

        /**
         * "n ticks, mean a ms, p50 b, p95 c, max d (tick k); GC g collections, h ms; slow ticks [...]": slow ticks are
         * those over 25 ms, by their position in the recording.
         */
        String summary() {
            if (nanos.isEmpty()) return "0 ticks";
            long[] sorted = nanos.toLongArray();
            Arrays.sort(sorted);
            long sum = 0;
            for (long n : sorted) sum += n;
            int maxAt = 0;
            StringBuilder slow = new StringBuilder();
            for (int i = 0; i < nanos.size(); i++) {
                if (nanos.getLong(i) > nanos.getLong(maxAt)) maxAt = i;
                if (nanos.getLong(i) > 25_000_000L && slow.length() < 200) {
                    slow.append(slow.length() == 0 ? "" : ", ").append(i).append(':')
                            .append(String.format(Locale.ROOT, "%.0f", ms(nanos.getLong(i))));
                }
            }
            return String.format(Locale.ROOT, "%d ticks, mean %.1f ms, p50 %.1f, p95 %.1f, max %.1f (tick %d); GC %d "
                            + "collections, %d ms; slow ticks [%s]", sorted.length,
                    ms(sum) / sorted.length, ms(sorted[sorted.length / 2]),
                    ms(sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95) - 1)]),
                    ms(sorted[sorted.length - 1]), maxAt, gcCountEnd - gcCountStart, gcMillisEnd - gcMillisStart, slow);
        }

        @Override
        public void close() {
            END_HOOKS.remove(hook);
        }
    }

    // ---------------------------------------------------------------- watching player

    /**
     * A creative player on an embedded channel that watches the chunks around a point, like a real client there.
     * While {@link #record() recording}, the vanilla packets it is sent are kept (drained at every tick's end, after
     * the tick time is taken) and {@link #report} sizes them with the play codec: raw bytes and, above the
     * compression threshold, deflated bytes plus the frame headers, as a real connection would send them. Chunk
     * batches are acknowledged by the test ({@link #ready}), since nothing ticks the embedded connection.
     */
    static final class Watcher implements AutoCloseable {
        final ServerPlayer player;
        private final ServerLevel world;
        private final EmbeddedChannel channel;
        private final int serverViewDistance;
        private final Consumer<MinecraftServer> hook = server -> drain();
        private final List<Packet<?>> packets = new ArrayList<>();
        private Consumer<Packet<?>> observer = packet -> {};
        private boolean recording;

        private Watcher(ServerLevel world, ServerPlayer player, EmbeddedChannel channel, int serverViewDistance) {
            this.world = world;
            this.player = player;
            this.channel = channel;
            this.serverViewDistance = serverViewDistance;
            OPEN.add(this);
        }

        /** Watchers not closed yet, with when they joined. */
        private static final List<Watcher> OPEN = new CopyOnWriteArrayList<>();
        private final long joinedNanos = System.nanoTime();

        /**
         * Closes watchers a failed test left open (a test closes its own on success only): those joined more than two
         * minutes ago, longer than any test using one runs. Each test with a watcher runs in a batch of its own.
         */
        private static void closeLeaked() {
            long now = System.nanoTime();
            for (Watcher watcher : OPEN) {
                if (now - watcher.joinedNanos > 120_000_000_000L) {
                    LOG.warn("Closing a bench watcher a failed test left open");
                    watcher.close();
                }
            }
        }

        /**
         * Joins a watcher at block (x, y, z) with the given view distance. The GameTest server's own view distance is
         * tiny; it is raised to at least that much until {@link #close()}.
         */
        static Watcher join(ServerLevel world, int x, int y, int z, int viewDistance) {
            installHooks();
            closeLeaked();
            MinecraftServer server = world.getServer();
            int serverViewDistance = server.getPlayerList().getViewDistance();
            if (serverViewDistance < viewDistance) server.getPlayerList().setViewDistance(viewDistance);
            GameProfile profile = new GameProfile(UUID.randomUUID(), "bench-watcher");
            ClientInformation options = new ClientInformation("en_us", viewDistance, ChatVisiblity.FULL, true, 0,
                    HumanoidArm.RIGHT, false, false);
            ServerPlayer player = new ServerPlayer(server, world, profile, options) {
                @Override
                public boolean isSpectator() {
                    return false;
                }

                @Override
                public boolean isCreative() {
                    return true;
                }
            };
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            EmbeddedChannel channel = new EmbeddedChannel(connection);
            server.getPlayerList().placeNewPlayer(connection, player, new CommonListenerCookie(profile, 0, options, false));
            player.teleportTo(world, x + 0.5, y, z + 0.5, 0f, 0f);
            world.getChunkSource().move(player);
            Watcher watcher = new Watcher(world, player, channel, serverViewDistance);
            END_HOOKS.add(watcher.hook);
            return watcher;
        }

        /**
         * Sends and acknowledges chunk batches; true once every chunk under {@code box} is tracked by the player
         * (so block and light updates there reach it) and every chunk in its view has reached it. Call once per tick
         * until true.
         */
        boolean ready(Box box) {
            player.connection.chunkSender.sendNextChunks(player);
            player.connection.chunkSender.onChunkBatchReceivedByClient(64f);
            var loading = world.getChunkSource().chunkMap;
            for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
                for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) {
                    if (!loading.getPlayers(new ChunkPos(cx, cz), false).contains(player)) return false;
                }
            }
            // The rest of the view too, so later chunk packets are the edits' own: every chunk in view received. A
            // loaded chunk is not enough: vanilla sends a chunk only once it ticks and its light is ready
            // (ChunkHolder.getChunkToSend), and only then queues it for the player, so a chunk loaded but not yet
            // queued reached the watcher after the test had started counting.
            drain();
            boolean[] all = {true};
            player.getChunkTrackingView().forEach(pos -> {
                if (all[0] && !received.contains(pos.toLong())) all[0] = false;
            });
            return all[0];
        }

        /** The chunks the player was sent and not told to forget. */
        private final LongOpenHashSet received = new LongOpenHashSet();

        /** Drops what was sent so far and keeps what is sent from now on. */
        void record() {
            drain();
            packets.clear();
            recording = true;
        }

        void pause() {
            drain();
            recording = false;
        }

        /** Hands every packet sent to the player from now on to {@code observer}, in order (bundles unpacked). */
        Watcher observe(Consumer<Packet<?>> observer) {
            this.observer = observer;
            return this;
        }

        /** Takes what was sent so far off the channel (also done at every tick's end). */
        void drain() {
            Object message;
            while ((message = channel.readOutbound()) != null) {
                if (!(message instanceof Packet<?> packet)) continue;
                if (packet instanceof ClientboundBundlePacket bundle) {
                    for (Packet<?> inner : bundle.subPackets()) see(inner);
                } else {
                    see(packet);
                }
                if (recording) packets.add(packet);
            }
        }

        private void see(Packet<?> packet) {
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunk) {
                received.add(ChunkPos.asLong(chunk.getX(), chunk.getZ()));
            } else if (packet instanceof ClientboundForgetLevelChunkPacket forget) {
                received.remove(forget.pos().toLong());
            }
            observer.accept(packet);
        }

        /** Per packet type: count, raw bytes, wire bytes; plus a total line. Clears the kept packets. */
        String report(String what) {
            drain();
            StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec = GameProtocols.CLIENTBOUND_TEMPLATE
                    .bind(RegistryFriendlyByteBuf.decorator(world.getServer().registryAccess())).codec();
            Map<String, long[]> byType = new TreeMap<>();
            Deflater deflater = new Deflater();
            try {
                for (Packet<?> packet : packets) measure(codec, deflater, packet, byType);
            } finally {
                deflater.end();
            }
            packets.clear();
            StringBuilder out = new StringBuilder(what).append(" network:");
            long totalCount = 0, totalRaw = 0, totalWire = 0;
            for (Map.Entry<String, long[]> entry : byType.entrySet()) {
                long[] v = entry.getValue();
                totalCount += v[0];
                totalRaw += v[1];
                totalWire += v[2];
                out.append(String.format(Locale.ROOT, "%n    %-34s %,8d packets %,13d B raw %,13d B wire", entry.getKey(),
                        v[0], v[1], v[2]));
            }
            out.append(String.format(Locale.ROOT, "%n    %-34s %,8d packets %,13d B raw %,13d B wire", "total", totalCount,
                    totalRaw, totalWire));
            return out.toString();
        }

        @SuppressWarnings("unchecked")
        private static void measure(StreamCodec<ByteBuf, Packet<? super ClientGamePacketListener>> codec, Deflater deflater,
                                    Packet<?> packet, Map<String, long[]> byType) {
            if (packet instanceof ClientboundBundlePacket bundle) {
                for (Packet<?> inner : bundle.subPackets()) measure(codec, deflater, inner, byType);
                return;
            }
            String name = packet.getClass().getSimpleName();
            long[] v = byType.computeIfAbsent(name, k -> new long[3]);
            ByteBuf buf = Unpooled.buffer();
            try {
                codec.encode(buf, (Packet<? super ClientGamePacketListener>) packet);
                int raw = buf.readableBytes();
                byte[] bytes = new byte[raw];
                buf.readBytes(bytes);
                v[0]++;
                v[1] += raw;
                v[2] += wireBytes(bytes, deflater);
            } catch (RuntimeException e) {
                byType.computeIfAbsent(name + " (not encodable)", k -> new long[3])[0]++;
            } finally {
                buf.release();
            }
        }

        /** Frame length + data length + (deflated) body, as vanilla's compressed framing sends it. */
        private static long wireBytes(byte[] raw, Deflater deflater) {
            long body;
            if (raw.length < COMPRESSION_THRESHOLD) {
                body = 1 + raw.length;
            } else {
                deflater.reset();
                deflater.setInput(raw);
                deflater.finish();
                byte[] out = new byte[8192];
                long compressed = 0;
                while (!deflater.finished()) compressed += deflater.deflate(out);
                body = varintSize(raw.length) + compressed;
            }
            return varintSize(body) + body;
        }

        private static int varintSize(long value) {
            int size = 1;
            while ((value & ~0x7FL) != 0) {
                value >>>= 7;
                size++;
            }
            return size;
        }

        @Override
        public void close() {
            if (!OPEN.remove(this)) return;
            END_HOOKS.remove(hook);
            recording = false;
            packets.clear();
            try {
                world.getServer().getPlayerList().remove(player);
            } catch (RuntimeException e) {
                LOG.warn("Could not remove the bench watcher", e);
            }
            channel.finishAndReleaseAll();
            if (world.getServer().getPlayerList().getViewDistance() != serverViewDistance) {
                world.getServer().getPlayerList().setViewDistance(serverViewDistance);
            }
        }
    }

    // ---------------------------------------------------------------- light

    /**
     * Whether the light engine has caught up with the edits made before {@link #LightWait construction}: a marker task
     * queued behind them on every chunk column of the box has run, and no light propagation is pending.
     */
    static final class LightWait {
        private final ThreadedLevelLightEngine light;
        private final List<CompletableFuture<?>> markers = new ArrayList<>();
        private final long startNanos = System.nanoTime();
        private long settledNanos = -1;

        LightWait(ServerLevel world, Box box) {
            this.light = world.getChunkSource().getLightEngine();
            for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
                for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) markers.add(light.waitForPendingTasks(cx, cz));
            }
        }

        boolean settled() {
            if (settledNanos >= 0) return true;
            for (CompletableFuture<?> marker : markers) {
                if (!marker.isDone()) return false;
            }
            if (light.hasLightWork()) return false;
            settledNanos = System.nanoTime();
            return true;
        }

        /** Wall time from construction until {@link #settled()} first returned true. */
        long nanos() {
            return settledNanos - startNanos;
        }
    }
}
