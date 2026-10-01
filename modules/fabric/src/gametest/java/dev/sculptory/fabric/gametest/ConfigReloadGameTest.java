package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.HistoryLimits;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.config.SculptoryConfig;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.engine.RunOptions;
import dev.sculptory.fabric.engine.impl.AckSink;
import dev.sculptory.fabric.engine.impl.EditEvents;
import dev.sculptory.fabric.engine.impl.EngineEditService;
import dev.sculptory.fabric.engine.impl.EngineRuntime;
import dev.sculptory.fabric.engine.impl.JobRequest;
import dev.sculptory.fabric.gametest.EditTestSupport.CapturedOutput;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.net.ServerNet;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.ProtocolV2;
import dev.sculptory.protocol.v2.RejectReason;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/**
 * {@code /sculptory reload}: a reload applies the file to what is checked from then on,
 * jobs already admitted keep the limits they were admitted with, an unusable file keeps the running config, sections
 * wired in at start wait for a restart, and only admins and the console may reload. Also {@code /sculptory version}. Region
 * slots 970-979.
 */
public final class ConfigReloadGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /**
     * On an engine of the test's own (so the server's config is never changed under other tests), ticked here, from a
     * file with {@code maxOpVolume} 100,000: a 16,384-block fill over 2 × 2 chunk columns is admitted (with 64 chunk
     * tickets per job); the file then lowers {@code maxOpVolume} to 1,000 and the tickets per job to 1, writes at most
     * 1,024 blocks a tick, sets a history cap past its maximum and asks for a brush radius over the maximum. The reload
     * applies the first three (a new fill of that size is refused, the executor has the new settings), keeps the history
     * cap for a restart and reports only the clamp of what it applies; the fill admitted before it keeps its limits: it
     * holds the tickets of all four columns at once, more than the new cap of one, and completes in full. Missing,
     * broken, empty and unreadable files
     * change nothing (the very same config stays). Then editing switched off and a higher op level refuse new work and
     * remove the op's nodes. Everything runs within the test's first tick, and the private engine, the forced chunks and
     * the temporary folder are released however it ends.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_config_reload", tickLimit = LIMIT)
    public void aReloadAppliesToNewWorkWhileAdmittedJobsKeepTheirLimits(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        Harness h = new Harness(context);
        // Not an op: ops hold limit.bypass, which lifts maxOpVolume.
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
        Path dir = tempDir();
        Path file = dir.resolve("server.json");
        SculptoryConfig first = SculptoryConfig.defaults();
        first.limits.maxOpVolume = 100_000;
        write(file, first);
        EngineRuntime runtime = new EngineRuntime(server, SculptoryConfig.read(file).config());
        EngineEditService service = new EngineEditService(runtime, runtime.executor(), runtime.config().toHistoryLimits(),
                p -> JobRequest.NO_LISTENER, AckSink.VANILLA, EditEvents.NONE, System::nanoTime);
        int[] at = regionCorner(context, 970);
        // Chunk-aligned: 2 × 2 columns.
        Box area = box(at[0], 100, at[1], at[0] + 31, 115, at[1] + 31);
        try {
            loadAndForce(h.world, area);
            RecordingListener admitted = new RecordingListener();
            // The same file again: nothing changes.
            EngineRuntime.Reload same = runtime.reload(file);
            check(same.ok() && same.applied().isEmpty() && same.needRestart().isEmpty(), "reloading the same file: " + same);

            fill(service, h, builder, area, admitted);
            check(runtime.executor().isWaiting(service.jobs(builder.getUuid()).get(0).jobId()), "the fill starts next tick");

            SculptoryConfig lowered = SculptoryConfig.defaults();
            lowered.limits.maxOpVolume = 1_000;
            lowered.executor.maxChunkTicketsPerJob = 1;
            lowered.executor.maxBlocksPerTick = 1_024;
            lowered.history.maxEntriesPerPlayer = 20_000; // clamped to 10,000, but only a restart applies it
            lowered.limits.maxBrushRadius = 64;
            write(file, lowered);
            SculptoryConfig before = runtime.config();
            EngineRuntime.Reload reload = runtime.reload(file);
            check(reload.ok(), "the reload failed: " + reload.problem());
            check(keys(reload.applied()).equals(Set.of("limits.maxOpVolume", "executor.maxChunkTicketsPerJob",
                    "executor.maxBlocksPerTick")), "applied " + reload.applied());
            check(keys(reload.needRestart()).equals(Set.of("history.maxEntriesPerPlayer")), "restart " + reload.needRestart());
            check(reload.adjustments().equals(List.of("limits.maxBrushRadius = 64 out of range [1, 32]; using 32")),
                    "adjustments " + reload.adjustments());
            check(reload.needRestart().get(0).after().equals("10000"), "the clamped value waits: " + reload.needRestart());
            check(runtime.config() != before && runtime.config().limits.maxOpVolume == 1_000, "the new limit is in effect");
            check(runtime.config().history == before.history && runtime.config().history.maxEntriesPerPlayer == HistoryLimits.DEFAULTS.maxEntries(),
                    "the history section waits for a restart");
            check(runtime.executor().settings().maxTicketsPerJob() == 1, "the executor has the new settings");
            try {
                service.run(builder, fillOp(h, area), RunOptions.DEFAULT, new RecordingListener());
                throw new GameTestException("a fill over the new maxOpVolume was admitted");
            } catch (EditRejected e) {
                check(e.reason() == RejectReason.TOO_LARGE, "refused " + e.reason());
            }

            // The admitted fill runs with the limits it was admitted with: 1,024 blocks a tick (the new block cap applies
            // to every tick), holding its four columns' tickets together (its cap of 64, not the new 1).
            tick(runtime);
            check(admitted.result == null, "the fill finished in one tick: the new block cap was not applied");
            check(runtime.executor().ticketsInFlight() > 1, "the admitted fill holds " + runtime.executor().ticketsInFlight()
                    + " chunk ticket(s): the lowered cap of 1 reached it");
            for (int i = 0; i < 200 && admitted.result == null; i++) tick(runtime);
            check(admitted.result != null && admitted.result.outcome() == JobOutcome.COMPLETED,
                    "the fill admitted before the reload: " + admitted.result);
            checkStone(h, area);

            // Unusable files change nothing: the very same config object stays.
            SculptoryConfig kept = runtime.config();
            unusable(runtime, dir.resolve("missing.json"), kept, "doesn't exist");
            for (String broken : new String[] {"{ \"limits\": { \"maxOpVolume\": 5 ", "", "{\"editingEnabled\": [1]}"}) {
                writeText(file, broken);
                unusable(runtime, file, kept, null);
                check(readText(file).equals(broken), "the reload rewrote a broken file");
            }
            unusable(runtime, Files.createDirectories(dir.resolve("folder.json")), kept, null);

            // Editing off and a higher op level: new work is refused, the op (level 2) loses the nodes.
            SculptoryConfig off = SculptoryConfig.defaults();
            off.editingEnabled = false;
            off.permissionFallbackOpLevel = 4;
            write(file, off);
            check(runtime.permissions().has(h.player, Perm.USE), "an op has the node before");
            reload = runtime.reload(file);
            check(reload.ok() && keys(reload.applied()).containsAll(Set.of("editingEnabled",
                    "permissionFallbackOpLevel", "limits.maxOpVolume")), "applied " + reload.applied());
            check(!runtime.permissions().has(h.player, Perm.USE), "a level-2 op kept the node at level 4");
            try {
                service.run(builder, fillOp(h, box(area.min().x(), 100, area.min().z(), area.min().x(), 100,
                        area.min().z())), RunOptions.DEFAULT, new RecordingListener());
                throw new GameTestException("a fill was admitted with editing switched off");
            } catch (EditRejected e) {
                check(e.reason() == RejectReason.DISABLED, "refused " + e.reason());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            cleanUp(h, runtime, service, area, dir);
        }
        context.complete();
    }

    /** One tick of the private engine's executor: its work, then its flush. */
    private static void tick(EngineRuntime runtime) {
        runtime.executor().tick();
        runtime.executor().endTick();
    }

    /**
     * {@code /sculptory reload} is an admin command: an editor user can't address it, an admin and the console can. Run by the
     * console on the server's own (unchanged) file it reports nothing changed and pushes every online player's limits
     * and permissions at once. {@code /sculptory version} needs only {@code sculptory.use} and names the server's build;
     * for the console it lists every player's client (none here: mock players have no editor).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_config_reload_command")
    public void reloadIsForAdminsAndVersionForEditorUsers(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        CommandDispatcher<ServerCommandSource> dispatcher = server.getCommandManager().getDispatcher();
        Harness h = new Harness(context);
        ServerPlayerEntity user = h.addPlayer(false);
        Set<UUID> pushed = new HashSet<>();
        Consumer<ServerPlayerEntity> observer = player -> pushed.add(player.getUuid());
        try {
            EditTestSupport.grant(user, Perm.USE, Perm.REGION);
            ServerCommandSource userSource = user.getCommandSource();
            check(!parses(dispatcher, "sculptory reload", userSource), "an editor user must not be able to address /sculptory reload");
            check(parses(dispatcher, "sculptory reload", h.player.getCommandSource()), "an admin must be able to address it");
            check(parses(dispatcher, "sculptory reload", server.getCommandSource()), "the console must be able to address it");
            check(parses(dispatcher, "sculptory version", userSource), "an editor user must be able to address /sculptory version");

            CapturedOutput version = new CapturedOutput();
            server.getCommandManager().executeWithPrefix(userSource.withOutput(version), "sculptory version");
            check(version.all().contains("Sculptory " + SculptoryMod.buildId() + " on this server (protocol "
                    + ProtocolV2.VERSION + ")") && version.all().contains("Your client: no Sculptory editor connected"),
                    "version output: " + version.all());
            check(version.lines.size() == 1 && version.all().lines().count() == 2,
                    "a non-admin sees the server's build and their own client only: " + version.all());
            CapturedOutput consoleVersion = new CapturedOutput();
            server.getCommandManager().executeWithPrefix(server.getCommandSource().withOutput(consoleVersion), "sculptory version");
            check(consoleVersion.all().contains(user.getGameProfile().getName() + ": no Sculptory editor connected"),
                    "the console's version output lists players: " + consoleVersion.all());

            EngineRuntime runtime = EngineTestSupport.runtime(context);
            String running = runtime.config().toJson();
            ServerNet.observePermissionChecks(observer);
            CapturedOutput out = new CapturedOutput();
            server.getCommandManager().executeWithPrefix(server.getCommandSource().withOutput(out), "sculptory reload");
            check(out.all().contains("Sculptory config reloaded from") && out.all().contains("Nothing changed."),
                    "reload output: " + out.all());
            check(runtime.config().toJson().equals(running), "the server's own file changed the running config");
            check(pushed.contains(user.getUuid()) && pushed.contains(h.player.getUuid()),
                    "every online player's limits must be pushed at once");
        } finally {
            ServerNet.observePermissionChecks(null);
            h.close();
        }
        context.complete();
    }

    // ---------------------------------------------------------------- helpers

    private static void unusable(EngineRuntime runtime, Path file, SculptoryConfig kept, String problem) {
        EngineRuntime.Reload reload = runtime.reload(file);
        check(!reload.ok() && reload.applied().isEmpty(), "an unusable file was applied: " + file);
        if (problem != null) check(reload.problem().contains(problem), "problem " + reload.problem());
        check(runtime.config() == kept, "an unusable file replaced the running config: " + file);
    }

    /** Every cell of {@code box} holds stone: the whole fill was written. */
    private static void checkStone(Harness h, Box box) {
        net.minecraft.block.BlockState stone = net.minecraft.block.Blocks.STONE.getDefaultState();
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    check(h.world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z)) == stone,
                            "the admitted fill left " + x + "," + y + "," + z + " unwritten");
                }
            }
        }
    }

    private static OpSpec fillOp(Harness h, Box box) {
        return new OpSpec.Fill(box, new Pattern.Single(h.state("minecraft:stone")), CellMask.ANY);
    }

    private static void fill(EngineEditService service, Harness h, ServerPlayerEntity player, Box box,
                             RecordingListener listener) {
        try {
            service.run(player, fillOp(h, box), RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException("fill refused: " + e.getMessage());
        }
    }

    private static Set<String> keys(List<SculptoryConfig.Change> changes) {
        Set<String> keys = new HashSet<>();
        for (SculptoryConfig.Change change : changes) keys.add(change.key());
        return keys;
    }

    private static boolean parses(CommandDispatcher<ServerCommandSource> dispatcher, String command,
                                  ServerCommandSource source) {
        ParseResults<ServerCommandSource> parsed = dispatcher.parse(command, source);
        return parsed.getExceptions().isEmpty() && !parsed.getReader().canRead();
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("sculptory-reload");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, SculptoryConfig config) {
        try {
            config.write(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeText(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void cleanUp(Harness h, EngineRuntime runtime, EngineEditService service, Box area, Path dir) {
        runtime.executor().shutdown();
        service.shutdown();
        runtime.fluidTrails().close();
        forceChunks(h.world, area, false);
        h.close();
        EngineTestBootstrap.deleteTree(dir);
    }
}
