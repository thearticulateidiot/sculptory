package dev.sculptory.paper.devtest;

import com.mojang.authlib.GameProfile;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.engine.PaperEditHost;
import dev.sculptory.paper.nms.PaperAccess;
import dev.sculptory.paper.perm.WorldGuardTestRegions;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.permissions.PermissionAttachment;

/**
 * Dev-only adapter checks on a running Paper server, standing in for GameTests (Paper has none):
 * {@code /sculptory selftest [player]} from the console, or at start with {@code -Dsculptory.selftest=<report.xml>}
 * (plus {@code -Dsculptory.selftest.stop=true} to stop the server afterwards, for CI). The edits run through the engine
 * as a player: the named online player, or a fake one (a {@link ServerPlayer} with no connection, not op, given every
 * Sculptory node through a Bukkit permission attachment, so region protection applies to it). Results go to the log
 * and to a JUnit-style report ({@code plugins/sculptory/selftest-report.xml} by default).
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
    private static final int JOB_TIMEOUT_TICKS = 400;
    private static volatile boolean running;

    private final PaperEditHost host;
    private final ServerLevel level;
    private final ServerPlayer player;
    private final PermissionAttachment attachment;
    private final List<Result> results = new ArrayList<>();
    private final List<long[]> tickets = new ArrayList<>();
    private final int baseX;
    private final int baseZ;

    private record Result(String name, boolean passed, String detail) {
    }

    private SelfTest(PaperEditHost host, ServerPlayer player, PermissionAttachment attachment) {
        this.host = host;
        this.level = host.engine().server().overworld();
        this.player = player;
        this.attachment = attachment;
        BlockPos spawn = level.getSharedSpawnPos();
        // Chunk-aligned, well outside spawn protection.
        this.baseX = ((spawn.getX() >> 4) + 20) << 4;
        this.baseZ = ((spawn.getZ() >> 4) + 20) << 4;
    }

    /** From the command: starts the checks (they take a few seconds) and reports to the source and the log. */
    public static int run(PaperEditHost host, CommandSourceStack source, String playerName) {
        if (host == null) {
            source.sendFailure(Component.literal("The Sculptory engine is not running"));
            return 0;
        }
        Path report = reportPath();
        start(host, playerName).thenAccept(summary -> {
            write(report, summary);
            source.sendSuccess(() -> Component.literal(summary.line() + " (report: " + report + ")"), false);
        });
        source.sendSuccess(() -> Component.literal("Sculptory self-test started"), false);
        return 1;
    }

    /** At start ({@code -Dsculptory.selftest}): runs with a fake player, writes the report, and stops when asked. */
    public static void runAtStartup(PaperEditHost host) {
        String property = System.getProperty("sculptory.selftest");
        if (property == null || property.isBlank()) return;
        Path report = property.equals("true") ? reportPath() : Path.of(property);
        start(host, "").thenAccept(summary -> {
            write(report, summary);
            if (Boolean.getBoolean("sculptory.selftest.stop")) {
                Sculptory.LOG.info("Sculptory self-test: stopping the server (sculptory.selftest.stop)");
                Bukkit.shutdown();
            }
        });
    }

    private static Path reportPath() {
        return SculptoryPlugin.instance().getDataFolder().toPath().getParent().resolve("sculptory")
                .resolve("selftest-report.xml");
    }

    record Summary(List<Result> results) {
        long failures() {
            return results.stream().filter(r -> !r.passed).count();
        }

        String line() {
            return "Sculptory self-test: " + (results.size() - failures()) + " of " + results.size() + " checks passed";
        }
    }

    private static CompletableFuture<Summary> start(PaperEditHost host, String playerName) {
        if (running) return CompletableFuture.failedFuture(new IllegalStateException("a self-test is already running"));
        running = true;
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
                running = false;
                return CompletableFuture.failedFuture(new IllegalArgumentException(playerName + " is not online"));
            }
        }
        SelfTest test = new SelfTest(host, player, attachment);
        Sculptory.LOG.info("Sculptory self-test: starting as {} at x={} z={} (protection: {})",
                player.getGameProfile().getName(), test.baseX, test.baseZ,
                host.engine().permissions().protection().name());
        return test.fillUndo()
                .thenCompose(v -> test.physicsOff())
                .thenCompose(v -> test.worldGuard())
                .handle((v, error) -> {
                    if (error != null) test.record("aborted", false, String.valueOf(error));
                    test.finish();
                    running = false;
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
        // A chest with items, placed the vanilla way (not through the engine, so the undo below is the engine's).
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
                    // Emptied first and removed without callbacks: nothing drops.
                    if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity box) box.clearContent();
                    PaperAccess.setBlockWithoutCallbacks(level, chestPos, Blocks.AIR.defaultBlockState());
                });
    }

    private CompletableFuture<Void> worldGuard() {
        // Asked of Bukkit, not of WorldGuardTestRegions: that class must not load without WorldGuard.
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            record("worldguard: skipped (WorldGuard not installed)", true, "");
            return CompletableFuture.completedFuture(null);
        }
        if (attachment == null && player.hasPermissions(2)) {
            record("worldguard: note", true, player.getGameProfile().getName()
                    + " is an operator; WorldGuard may let them bypass (run without a player for the fake one)");
        }
        // A region of 4x2x4 cells inside one chunk, y 101-102.
        int x = baseX + 80 + 4, z = baseZ + 4;
        String id = "sculptory_selftest";
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
                    // for the whole box height, the 3D permits (ws/permits-3d) must write them. Reported, not failed.
                    int regionChanged = countDiffering(inside, insideBefore);
                    int layersWritten = countNot(layersAbove, planks) == 0 ? 1 : 0;
                    record("worldguard: box over the region and above it keeps the region", regionChanged == 0,
                            regionChanged + " region cells differ");
                    record("worldguard: layers above the region in the same box (3D gap, informational)", true,
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
        return job("fill", listener -> host.service().run(player,
                new OpSpec.Fill(box, new Pattern.Single(state), CellMask.ANY), RunOptions.DEFAULT, listener));
    }

    private CompletableFuture<JobResult> undo() {
        return job("undo", listener -> host.service().undo(player, ConflictPolicy.SKIP_CONFLICTS, listener));
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

    private void record(String name, boolean passed, String detail) {
        results.add(new Result(name, passed, detail));
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

    private void finish() {
        org.bukkit.World world = level.getWorld();
        for (long[] t : tickets) world.removePluginChunkTicket((int) t[0], (int) t[1], SculptoryPlugin.instance());
        if (attachment != null) attachment.remove();
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
                .append("<testsuite name=\"sculptory-paper-selftest\" tests=\"").append(summary.results.size())
                .append("\" failures=\"").append(summary.failures()).append("\">\n");
        for (Result r : summary.results) {
            xml.append("  <testcase name=\"").append(escape(r.name)).append("\">");
            if (!r.passed) xml.append("<failure message=\"").append(escape(r.detail)).append("\"/>");
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
