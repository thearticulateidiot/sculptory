package dev.sculptory.paper.devtest;

import com.mojang.authlib.GameProfile;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.net.ServerNet;
import dev.sculptory.paper.PaperServerLoader;
import dev.sculptory.paper.S2CRoute;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.engine.PaperEditHost;
import dev.sculptory.paper.nms.PaperAccess;
import dev.sculptory.paper.perm.WorldGuardTestRegions;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EngineEditService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scheduler.BukkitTask;

/**
 * Dev-only adapter checks on a running Paper server, standing in for GameTests (Paper has none):
 * {@code /sculptory selftest [player]}, or at start with {@code -Dsculptory.selftest=<report.xml>} (plus
 * {@code -Dsculptory.selftest.stop=true} to stop the server afterwards, for CI).
 *
 * <p>Safety: the commands exist only when the JVM was started with {@code -Dsculptory.selftest.enabled=true} or
 * {@code -Dsculptory.selftest=...} ({@link #enabled}), and then only for the console or a player holding
 * {@code sculptory.selftest} (default false; an operator does not get it). The edits run through a throwaway edit
 * service with history in memory only, so nothing reaches the world's history store or a player's undo; every cell a
 * check touches is put back as it was (the chest cell included); the region ids are new each run, and a run refuses
 * to start if one exists. The checks start on a scheduled task; whatever they throw, the report is written, and with
 * {@code sculptory.selftest.stop} the server stops (an overall timeout does the same), so CI never hangs.
 *
 * <p>The edits run as a player: the named online player, or a fake one (a {@link ServerPlayer} with no connection,
 * not op, given every Sculptory node through a Bukkit permission attachment, so region protection applies to it).
 * Results go to the log and to a JUnit-style report ({@code plugins/Sculptory/selftest-report.xml} by default).
 *
 * <p>Checks, each in its own corner of the overworld 300+ blocks from spawn (chunks held by plugin tickets):
 * <ol>
 *   <li>fill-undo: a 10x5x10 fill is exactly what was asked, and its undo restores every cell as it was;</li>
 *   <li>physics-off: sand filled in the air stays (no falling block, no scheduled tick), a filled chest drops
 *       nothing when overwritten, and the undo brings the chest and its items back;</li>
 *   <li>worldguard (when installed): a 4x2x4 region inside one chunk, 2 high; a fill of the region's cells is refused
 *       cell by cell (all skipped as protected), a fill beside it in the same chunk is written, its undo is exact, a
 *       box of its own above the region's height is written; and one box over the region and the two layers above it
 *       keeps the region, while its layers above (which 3D permits would write, the 2D permits refuse) are
 *       reported.</li>
 * </ol>
 */
public final class SelfTest {
    /** The permission a player needs for the dev commands (plugin.yml: default false). */
    public static final String PERMISSION = "sculptory.selftest";
    private static final int JOB_TIMEOUT_TICKS = 400;
    /** The whole run's limit; past it the report says so and, in CI mode, the server stops. */
    private static final int RUN_TIMEOUT_TICKS = 20 * 120;
    private static final String REGION_PREFIX = "sculptory_selftest_";
    private static volatile boolean running;

    private final PaperEditHost host;
    /** A throwaway edit service: history in memory only, shut down after the run. */
    private final EngineEditService<ServerPlayer, ServerLevel> service;
    private final ServerLevel level;
    private final ServerPlayer player;
    private final PermissionAttachment attachment;
    private final List<Result> results = new ArrayList<>();
    private final List<long[]> tickets = new ArrayList<>();
    /** Cells changed outside the engine (the chest), with what to put back. */
    private final List<Runnable> restores = new ArrayList<>();
    private final String runId = Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
    private final int baseX;
    private final int baseZ;

    /**
     * One check (pass or fail; {@code error}: the run threw), or an information entry ({@code info}: reported, never
     * failed).
     */
    private record Result(String name, boolean passed, String detail, boolean info, boolean error) {
    }

    private SelfTest(PaperEditHost host, ServerPlayer player, PermissionAttachment attachment) {
        this.host = host;
        this.level = host.engine().server().overworld();
        this.player = player;
        this.attachment = attachment;
        this.service = new EngineEditService<>(host.engine(), null, null, null, null);
        BlockPos spawn = level.getSharedSpawnPos();
        // Chunk-aligned, well outside spawn protection.
        this.baseX = ((spawn.getX() >> 4) + 20) << 4;
        this.baseZ = ((spawn.getZ() >> 4) + 20) << 4;
    }

    /** Whether the dev commands and the start-up run exist at all (JVM properties; never on by default). */
    public static boolean enabled() {
        String run = System.getProperty("sculptory.selftest");
        return Boolean.getBoolean("sculptory.selftest.enabled") || (run != null && !run.isBlank());
    }

    /** Who may run them: the console, or a player with {@link #PERMISSION} (not implied by operator status). */
    public static boolean allowed(CommandSourceStack source) {
        if (!enabled()) return false;
        ServerPlayer player = source.getPlayer();
        return player == null ? source.hasPermission(4) : player.getBukkitEntity().hasPermission(PERMISSION);
    }

    /** From the command: starts the checks on the next tick and reports to the source and the log. */
    public static int run(PaperEditHost host, CommandSourceStack source, String playerName) {
        if (host == null) {
            source.sendFailure(Component.literal("The Sculptory engine is not running"));
            return 0;
        }
        Path report = reportPath();
        launch(host, playerName, report, summary ->
                source.sendSuccess(() -> Component.literal(summary.line() + " (report: " + report + ")"), false));
        source.sendSuccess(() -> Component.literal("Sculptory self-test started"), false);
        return 1;
    }

    /** At start ({@code -Dsculptory.selftest}): runs with a fake player, writes the report, and stops when asked. */
    public static void runAtStartup(PaperEditHost host) {
        String property = System.getProperty("sculptory.selftest");
        if (property == null || property.isBlank()) return;
        Path report = property.equals("true") ? reportPath() : Path.of(property);
        launch(host, "", report, summary -> {
            if (Boolean.getBoolean("sculptory.selftest.stop")) {
                Sculptory.LOG.info("Sculptory self-test: stopping the server (sculptory.selftest.stop)");
                Bukkit.shutdown();
            }
        });
    }

    /**
     * Starts the run on a scheduled task. Whatever happens (a throw while starting, a failed check chain, the overall
     * timeout), the report is written exactly once and {@code done} is called.
     */
    private static void launch(PaperEditHost host, String playerName, Path report, Consumer<Summary> done) {
        AtomicBoolean finished = new AtomicBoolean();
        Consumer<Summary> finish = summary -> {
            if (!finished.compareAndSet(false, true)) return;
            write(report, summary);
            done.accept(summary);
        };
        SculptoryPlugin plugin = SculptoryPlugin.instance();
        Bukkit.getScheduler().runTask(plugin, () -> {
            CompletableFuture<Summary> run;
            try {
                run = start(host, playerName);
            } catch (Throwable e) {
                Sculptory.LOG.error("Sculptory self-test: could not start", e);
                run = CompletableFuture.failedFuture(e);
            }
            run.whenComplete((summary, error) -> finish.accept(error == null ? summary : Summary.error(error)));
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> finish.accept(Summary.error(new IllegalStateException(
                "the self-test did not finish within " + RUN_TIMEOUT_TICKS / 20 + " s"))), RUN_TIMEOUT_TICKS);
    }

    private static Path reportPath() {
        return SculptoryPlugin.instance().getDataFolder().toPath().resolve("selftest-report.xml");
    }

    record Summary(List<Result> results) {
        static Summary error(Throwable error) {
            return new Summary(List.of(new Result("self-test run", false, String.valueOf(error), false, true)));
        }

        List<Result> checks() {
            return results.stream().filter(r -> !r.info).toList();
        }

        long failures() {
            return checks().stream().filter(r -> !r.passed && !r.error).count();
        }

        long errors() {
            return checks().stream().filter(r -> r.error).count();
        }

        String line() {
            long infos = results.size() - checks().size();
            return "Sculptory self-test: " + (checks().size() - failures() - errors()) + " of " + checks().size()
                    + " checks passed (" + infos + " information entries" + (errors() > 0 ? ", " + errors()
                    + " errors" : "") + ")";
        }
    }

    private static CompletableFuture<Summary> start(PaperEditHost host, String playerName) {
        if (running) return CompletableFuture.failedFuture(new IllegalStateException("a self-test is already running"));
        MinecraftServer server = host.engine().server();
        ServerPlayer player;
        PermissionAttachment attachment = null;
        if (playerName.isEmpty()) {
            player = new ServerPlayer(server, server.overworld(),
                    new GameProfile(UUID.nameUUIDFromBytes("sculptory-selftest".getBytes(StandardCharsets.UTF_8)),
                            "SculptoryTest"), ClientInformation.createDefault());
            attachment = player.getBukkitEntity().addAttachment(SculptoryPlugin.instance());
            for (Perm perm : Perm.values()) attachment.setPermission(perm.node(), true);
        } else {
            player = server.getPlayerList().getPlayerByName(playerName);
            if (player == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(playerName + " is not online"));
            }
        }
        running = true;
        SelfTest test = new SelfTest(host, player, attachment);
        // The throwaway service's housekeeping (stroke timeouts, history polls) while the run lasts.
        BukkitTask ticker = Bukkit.getScheduler().runTaskTimer(SculptoryPlugin.instance(), test.service::tick, 1, 1);
        Sculptory.LOG.info("Sculptory self-test: starting as {} at x={} z={} (protection: {}, run {})",
                player.getGameProfile().getName(), test.baseX, test.baseZ,
                host.engine().permissions().protection().name(), test.runId);
        return test.fillUndo()
                .thenCompose(v -> test.physicsOff())
                .thenCompose(v -> test.worldGuard())
                .thenCompose(v -> test.worldGuardFlags())
                .thenCompose(v -> test.joinRace())
                .handle((v, error) -> {
                    if (error != null) test.results.add(new Result("aborted", false, String.valueOf(error), false, true));
                    try {
                        test.finish();
                    } catch (RuntimeException e) {
                        test.results.add(new Result("clean-up", false, String.valueOf(e), false, true));
                    } finally {
                        ticker.cancel();
                        running = false;
                    }
                    Summary summary = new Summary(List.copyOf(test.results));
                    Sculptory.LOG.info(summary.line());
                    return summary;
                });
    }

    // ================================================================== the checks

    private CompletableFuture<Void> fillUndo() {
        Box box = box(baseX + 2, 100, baseZ + 2, baseX + 11, 104, baseZ + 11);
        hold(box);
        int[] before = snapshot(box);
        int glass = Block.getId(Blocks.GLASS.defaultBlockState());
        return fill(box, glass).thenCompose(result -> {
            int wrong = countNot(box, glass);
            record("fill-undo: fill", wrong == 0 && result.skippedProtected() == 0,
                    result.changed() + " changed, " + wrong + " cells not glass");
            return undo();
        }).thenAccept(result -> {
            int differ = countDiffering(box, before);
            record("fill-undo: exact undo", differ == 0, result.changed() + " restored, " + differ + " cells differ");
        });
    }

    private CompletableFuture<Void> physicsOff() {
        int x = baseX + 40, z = baseZ + 2;
        Box sand = box(x, 200, z, x + 3, 201, z + 3);
        BlockPos chestPos = new BlockPos(x + 8, 100, z);
        Box chest = box(chestPos.getX(), chestPos.getY(), chestPos.getZ(), chestPos.getX(), chestPos.getY(),
                chestPos.getZ());
        hold(sand);
        hold(chest);
        int[] sandBefore = snapshot(sand);
        // A chest with items, placed the vanilla way (not through the engine, so the undo below is the engine's), over
        // whatever is there; that cell is put back as it was at the end of the run.
        restoreLater(chestPos);
        level.setBlock(chestPos, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity box) box.setItem(0, new ItemStack(Items.DIAMOND, 7));
        int sandId = Block.getId(Blocks.SAND.defaultBlockState());
        int stone = Block.getId(Blocks.STONE.defaultBlockState());
        return fill(sand, sandId)
                .thenCompose(result -> ticks(10).thenApply(v -> result))
                .thenCompose(result -> {
                    int notSand = countNot(sand, sandId);
                    int falling = level.getEntitiesOfClass(FallingBlockEntity.class, aabb(sand).inflate(2, 40, 2)).size();
                    int scheduled = 0;
                    for (BlockPos pos : cells(sand)) {
                        if (level.getBlockTicks().hasScheduledTick(pos, Blocks.SAND)) scheduled++;
                    }
                    record("physics-off: sand stays", notSand == 0 && falling == 0 && scheduled == 0,
                            notSand + " cells not sand, " + falling + " falling blocks, " + scheduled + " scheduled ticks");
                    return undo();
                })
                .thenCompose(result -> {
                    record("physics-off: sand undo", countDiffering(sand, sandBefore) == 0,
                            countDiffering(sand, sandBefore) + " cells differ");
                    return fill(chest, stone);
                })
                .thenCompose(result -> ticks(5).thenApply(v -> result))
                .thenCompose(result -> {
                    // Only items that appeared during the check (younger than 40 ticks): others may be left from earlier runs.
                    int items = level.getEntitiesOfClass(ItemEntity.class, aabb(chest).inflate(3), item -> item.tickCount < 40)
                            .size();
                    boolean isStone = level.getBlockState(chestPos).is(Blocks.STONE);
                    record("physics-off: chest overwritten without drops", isStone && items == 0,
                            "stone=" + isStone + ", " + items + " new item entities nearby");
                    return undo();
                })
                .thenAccept(result -> {
                    BlockState back = level.getBlockState(chestPos);
                    int diamonds = level.getBlockEntity(chestPos) instanceof ChestBlockEntity box
                            && box.getItem(0).is(Items.DIAMOND) ? box.getItem(0).getCount() : 0;
                    record("physics-off: chest undo with contents", back.is(Blocks.CHEST) && diamonds == 7,
                            back + ", " + diamonds + " diamonds in slot 0");
                });
    }

    /**
     * The join race ({@code S2CRoute}): the Fabric client's hello arrives before its registration of
     * {@code sculptory:s2c}, and the Messenger would drop the Welcome. Needs a connected Sculptory client, so it runs
     * only for a named player. First reports whether this player's own join hit the race (frames sent before the
     * registration); then reproduces it: the client's registration is taken away on the server, as if it had not
     * arrived yet, a harmless frame (a chunk of a stream the client never opened, which its session ignores) is sent
     * through the loader, and it must have gone out as a vanilla packet; the registration is then given back, and
     * the client must still be connected a second later.
     */
    private CompletableFuture<Void> joinRace() {
        PaperServerLoader loader = SculptoryPlugin.instance().loader();
        if (attachment != null || player.connection == null) {
            info("network: join race", "skipped: needs a connected Sculptory client (/sculptory selftest <player>)");
            return CompletableFuture.completedFuture(null);
        }
        if (!ServerNet.isReady(player)) {
            record("network: join race: the handshake completed", false, name() + " has no ready Sculptory session");
            return CompletableFuture.completedFuture(null);
        }
        int atJoin = loader.sentBeforeRegistration(player);
        info("network: join race on this join", atJoin > 0
                ? atJoin + " frame(s), the Welcome first, went out before the client registered sculptory:s2c"
                : "the client's registration arrived before the Welcome this time");
        CraftPlayer bukkit = player.getBukkitEntity();
        int before = loader.sentBeforeRegistration(player);
        S2CRoute route;
        boolean canSend;
        bukkit.removeChannel(PaperServerLoader.S2C_CHANNEL);
        try {
            route = loader.route(player);
            canSend = loader.canSendS2C(player);
            loader.sendS2C(player, NetTest.frameOf(64));
        } catch (ProtocolException e) {
            bukkit.addChannel(PaperServerLoader.S2C_CHANNEL);
            return CompletableFuture.failedFuture(e);
        } finally {
            if (!bukkit.getListeningPluginChannels().contains(PaperServerLoader.S2C_CHANNEL)) {
                bukkit.addChannel(PaperServerLoader.S2C_CHANNEL);
            }
        }
        int sent = loader.sentBeforeRegistration(player) - before;
        record("network: join race: a frame before the registration goes as a vanilla packet",
                canSend && route == S2CRoute.PACKET && sent == 1, "canSend=" + canSend + ", route=" + route
                        + ", counted " + sent);
        return ticks(20).thenAccept(v -> record("network: join race: the client stays connected",
                player.connection != null && !player.hasDisconnected()
                        && bukkit.getListeningPluginChannels().contains(PaperServerLoader.S2C_CHANNEL),
                "connected=" + !player.hasDisconnected() + ", registration back="
                        + bukkit.getListeningPluginChannels().contains(PaperServerLoader.S2C_CHANNEL)));
    }

    private String name() {
        return player.getGameProfile().getName();
    }

    /**
     * WorldGuard's place and break flags: two regions with {@code passthrough allow} (membership does not decide
     * {@code build}) and {@code build} unset, one with {@code block-place deny}, one with {@code block-break deny}.
     * WorldGuard's own listener refuses a player there; an edit both breaks and places, so a fill inside either must be
     * refused too.
     */
    private CompletableFuture<Void> worldGuardFlags() {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) return CompletableFuture.completedFuture(null);
        int x = baseX + 120 + 4, z = baseZ + 4;
        String placeId = REGION_PREFIX + runId + "_place";
        String breakId = REGION_PREFIX + runId + "_break";
        if (WorldGuardTestRegions.exists(level, placeId) || WorldGuardTestRegions.exists(level, breakId)) {
            return CompletableFuture.failedFuture(new IllegalStateException("a WorldGuard region " + placeId + " or "
                    + breakId + " exists"));
        }
        Box placeBox = box(x, 101, z, x + 3, 102, z + 3);
        Box breakBox = box(x + 6, 101, z, x + 9, 102, z + 3);
        WorldGuardTestRegions.addHub(level, placeId, placeBox.min().x(), 101, placeBox.min().z(),
                placeBox.max().x(), 102, placeBox.max().z(), true, false);
        WorldGuardTestRegions.addHub(level, breakId, breakBox.min().x(), 101, breakBox.min().z(),
                breakBox.max().x(), 102, breakBox.max().z(), false, true);
        hold(placeBox);
        int[] placeBefore = snapshot(placeBox);
        int[] breakBefore = snapshot(breakBox);
        int planks = Block.getId(Blocks.OAK_PLANKS.defaultBlockState());
        return fill(placeBox, planks)
                .thenCompose(result -> {
                    int changed = countDiffering(placeBox, placeBefore);
                    record("worldguard flags: block-place deny refuses a fill", changed == 0,
                            result.changed() + " changed, " + result.skippedProtected() + " skipped as protected");
                    return changed > 0 ? undo() : CompletableFuture.completedFuture(result);
                })
                .thenCompose(result -> fill(breakBox, planks))
                .thenCompose(result -> {
                    int changed = countDiffering(breakBox, breakBefore);
                    record("worldguard flags: block-break deny refuses a fill", changed == 0,
                            result.changed() + " changed, " + result.skippedProtected() + " skipped as protected");
                    return changed > 0 ? undo() : CompletableFuture.completedFuture(result);
                })
                .whenComplete((v, error) -> {
                    WorldGuardTestRegions.remove(level, placeId);
                    WorldGuardTestRegions.remove(level, breakId);
                    int left = countDiffering(placeBox, placeBefore) + countDiffering(breakBox, breakBefore);
                    record("worldguard flags: everything restored", left == 0, left + " cells differ");
                })
                .thenApply(result -> (Void) null);
    }

    private CompletableFuture<Void> worldGuard() {
        // Asked of Bukkit, not of WorldGuardTestRegions: that class must not load without WorldGuard.
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            info("worldguard: skipped", "WorldGuard is not installed");
            return CompletableFuture.completedFuture(null);
        }
        if (attachment == null && player.hasPermissions(2)) {
            info("worldguard: note", player.getGameProfile().getName()
                    + " is an operator; WorldGuard may let them bypass (run without a player for the fake one)");
        }
        // A region of 4x2x4 cells inside one chunk, y 101-102.
        int x = baseX + 80 + 4, z = baseZ + 4;
        String id = REGION_PREFIX + runId;
        if (WorldGuardTestRegions.exists(level, id)) {
            return CompletableFuture.failedFuture(new IllegalStateException("a WorldGuard region " + id + " exists"));
        }
        WorldGuardTestRegions.add(level, id, x, 101, z, x + 3, 102, z + 3);
        Box inside = box(x, 101, z, x + 3, 102, z + 3);
        Box beside = box(x + 6, 101, z, x + 9, 102, z + 3);
        Box above = box(x, 110, z, x + 3, 111, z + 3);
        Box spanning = box(x, 101, z, x + 3, 104, z + 3);
        Box layersAbove = box(x, 103, z, x + 3, 104, z + 3);
        hold(inside);
        int[] insideBefore = snapshot(inside);
        int[] besideBefore = snapshot(beside);
        int[] aboveBefore = snapshot(above);
        int[] spanningBefore = snapshot(spanning);
        int planks = Block.getId(Blocks.OAK_PLANKS.defaultBlockState());
        return fill(inside, planks)
                .thenCompose(result -> {
                    int changed = countDiffering(inside, insideBefore);
                    record("worldguard: fill inside the region refused", changed == 0 && result.changed() == 0,
                            result.outcome() + ", " + result.changed() + " changed, " + result.skippedProtected()
                                    + " skipped as protected, " + changed + " cells differ");
                    return fill(beside, planks);
                })
                .thenCompose(result -> {
                    int wrong = countNot(beside, planks);
                    record("worldguard: fill beside the region (same chunk) written", wrong == 0
                            && result.skippedProtected() == 0, result.changed() + " changed, " + wrong + " not planks");
                    return undo();
                })
                .thenCompose(result -> {
                    record("worldguard: undo of the fill beside exact", countDiffering(beside, besideBefore) == 0,
                            countDiffering(beside, besideBefore) + " cells differ");
                    return fill(above, planks);
                })
                .thenCompose(result -> {
                    // A box of its own above the region's height: the permit is asked for the box's y range only, so
                    // even the 2D permits allow it.
                    int wrong = countNot(above, planks);
                    record("worldguard: fill above the region's height (own box) written", wrong == 0,
                            result.changed() + " changed, " + wrong + " not planks");
                    return undo();
                })
                .thenCompose(result -> fill(spanning, planks))
                .thenCompose(result -> {
                    // One box over the region's cells and the two layers above them. The region's cells must stay
                    // whatever the permits; the layers above are the 3D gap: the 2D permits deny the region's columns
                    // for the whole box height, the 3D permits (ws/permits-3d) must write them. An information entry, not
                    // a check: it flips from "refused" to "written" when the 3D permits land.
                    int regionChanged = countDiffering(inside, insideBefore);
                    int layersWritten = countNot(layersAbove, planks) == 0 ? 1 : 0;
                    record("worldguard: box over the region and above it keeps the region", regionChanged == 0,
                            regionChanged + " region cells differ");
                    info("worldguard: layers above the region in the same box (3D gap)",
                            layersWritten == 1 ? "written (3D permits)" : "refused: " + result.skippedProtected()
                                    + " cells skipped as protected, " + result.changed()
                                    + " changed (2D permits deny the region's columns for the whole box)");
                    return result.changed() > 0 ? undo().thenApply(r -> (Void) null)
                            : CompletableFuture.<Void>completedFuture(null);
                })
                .whenComplete((v, error) -> {
                    WorldGuardTestRegions.remove(level, id);
                    int left = countDiffering(above, aboveBefore) + countDiffering(spanning, spanningBefore);
                    record("worldguard: everything restored after the checks", left == 0, left + " cells differ");
                });
    }

    // ================================================================== helpers

    private CompletableFuture<JobResult> fill(Box box, int state) {
        return job("fill", listener -> service.run(player,
                new OpSpec.Fill(box, new Pattern.Single(state), CellMask.ANY), RunOptions.DEFAULT, listener));
    }

    private CompletableFuture<JobResult> undo() {
        return job("undo", listener -> service.undo(player, ConflictPolicy.SKIP_CONFLICTS, listener));
    }

    @FunctionalInterface
    private interface Starter {
        JobTicket start(JobListener listener) throws EditRejected;
    }

    /** Starts a job and completes with its result (or fails when it is refused or times out). */
    private CompletableFuture<JobResult> job(String what, Starter starter) {
        CompletableFuture<JobResult> done = new CompletableFuture<>();
        try {
            JobTicket ticket = starter.start(new JobListener() {
                @Override
                public void progress(UUID job, long done, long total, Phase ph) {
                }

                @Override
                public void finished(JobResult r) {
                    done.complete(r);
                }
            });
            Sculptory.LOG.info("Sculptory self-test: {} job {} ({} cells)", what, ticket.jobId(), ticket.estimatedCells());
        } catch (EditRejected e) {
            done.completeExceptionally(new IllegalStateException(what + " refused: " + e.getMessage(), e));
            return done;
        }
        Bukkit.getScheduler().runTaskLater(SculptoryPlugin.instance(), () -> done.completeExceptionally(
                new IllegalStateException(what + " did not finish within " + JOB_TIMEOUT_TICKS + " ticks")),
                JOB_TIMEOUT_TICKS);
        return done;
    }

    private static CompletableFuture<Void> ticks(int n) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(SculptoryPlugin.instance(), () -> done.complete(null), n);
        return done;
    }

    private void info(String name, String detail) {
        results.add(new Result(name, true, detail, true, false));
        Sculptory.LOG.info("Sculptory self-test: INFO {}{}", name, detail.isEmpty() ? "" : " (" + detail + ")");
    }

    private void record(String name, boolean passed, String detail) {
        results.add(new Result(name, passed, detail, false, false));
        Sculptory.LOG.info("Sculptory self-test: {} {}{}", passed ? "PASS" : "FAIL", name,
                detail.isEmpty() ? "" : " (" + detail + ")");
    }

    private void hold(Box box) {
        org.bukkit.World world = level.getWorld();
        for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
            for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) {
                world.addPluginChunkTicket(cx, cz, SculptoryPlugin.instance());
                world.getChunkAt(cx, cz);
                tickets.add(new long[] {cx, cz});
            }
        }
    }

    /** Puts back what was changed outside the engine, lets go of the chunks, and shuts the throwaway service down. */
    private void finish() {
        try {
            for (int i = restores.size() - 1; i >= 0; i--) restores.get(i).run();
        } finally {
            org.bukkit.World world = level.getWorld();
            for (long[] t : tickets) world.removePluginChunkTicket((int) t[0], (int) t[1], SculptoryPlugin.instance());
            if (attachment != null) attachment.remove();
            service.shutdown();
        }
    }

    /**
     * Remembers cell {@code pos} as it is now (state and block entity) and puts it back at the end of the run, without
     * callbacks, so nothing drops and nothing around it updates.
     */
    private void restoreLater(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockEntity entity = level.getBlockEntity(pos);
        CompoundTag nbt = entity == null ? null : entity.saveWithFullMetadata(level.registryAccess());
        restores.add(() -> {
            if (level.getBlockEntity(pos) instanceof Container container) container.clearContent();
            PaperAccess.setBlockWithoutCallbacks(level, pos, state);
            if (nbt != null) {
                BlockEntity restored = BlockEntity.loadStatic(pos, state, nbt, level.registryAccess());
                if (restored != null) level.setBlockEntity(restored);
            }
            level.getChunkSource().blockChanged(pos);
        });
    }

    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return Box.of(new dev.sculptory.core.BlockPos(x0, y0, z0), new dev.sculptory.core.BlockPos(x1, y1, z1));
    }

    private static AABB aabb(Box box) {
        return new AABB(box.min().x(), box.min().y(), box.min().z(), box.max().x() + 1, box.max().y() + 1,
                box.max().z() + 1);
    }

    private static List<BlockPos> cells(Box box) {
        List<BlockPos> out = new ArrayList<>();
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) out.add(new BlockPos(x, y, z));
            }
        }
        return out;
    }

    private int[] snapshot(Box box) {
        List<BlockPos> cells = cells(box);
        int[] ids = new int[cells.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = Block.getId(level.getBlockState(cells.get(i)));
        return ids;
    }

    private int countNot(Box box, int state) {
        int n = 0;
        for (BlockPos pos : cells(box)) {
            if (Block.getId(level.getBlockState(pos)) != state) n++;
        }
        return n;
    }

    private int countDiffering(Box box, int[] before) {
        int[] now = snapshot(box);
        int n = 0;
        for (int i = 0; i < now.length; i++) {
            if (now[i] != before[i]) n++;
        }
        return n;
    }

    private static void write(Path report, Summary summary) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<testsuite name=\"sculptory-paper-selftest\" tests=\"").append(summary.checks().size())
                .append("\" failures=\"").append(summary.failures()).append("\" errors=\"").append(summary.errors())
                .append("\">\n");
        // Information entries are suite properties: reported, never a test.
        xml.append("  <properties>\n");
        for (Result r : summary.results) {
            if (!r.info) continue;
            xml.append("    <property name=\"").append(escape(r.name)).append("\" value=\"").append(escape(r.detail))
                    .append("\"/>\n");
        }
        xml.append("  </properties>\n");
        for (Result r : summary.checks()) {
            // classname: the check group (the name before its first colon), as JUnit readers group test cases.
            int colon = r.name.indexOf(':');
            String group = colon < 0 ? r.name : r.name.substring(0, colon);
            xml.append("  <testcase classname=\"sculptory.paper.").append(escape(group)).append("\" name=\"")
                    .append(escape(r.name)).append("\">");
            if (r.error) {
                xml.append("<error message=\"").append(escape(r.detail)).append("\"/>");
            } else if (!r.passed) {
                xml.append("<failure message=\"").append(escape(r.detail)).append("\"/>");
            }
            xml.append("<system-out>").append(escape(r.detail)).append("</system-out></testcase>\n");
        }
        xml.append("</testsuite>\n");
        try {
            Files.createDirectories(report.toAbsolutePath().getParent());
            Files.writeString(report, xml.toString(), StandardCharsets.UTF_8);
            Sculptory.LOG.info("Sculptory self-test: report written to {}", report.toAbsolutePath());
        } catch (IOException e) {
            Sculptory.LOG.error("Sculptory self-test: could not write {}", report, e);
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
