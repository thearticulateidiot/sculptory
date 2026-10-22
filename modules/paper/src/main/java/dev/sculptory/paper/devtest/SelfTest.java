package dev.sculptory.paper.devtest;

import com.mojang.authlib.GameProfile;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.net.ServerNet;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.engine.PaperEditHost;
import dev.sculptory.server.engine.Perm;
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
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.scheduler.BukkitTask;

/**
 * Dev-only adapter checks on a running Paper server, standing in for GameTests (Paper has none):
 * {@code /sculptory selftest [player]}, or at start with {@code -Dsculptory.selftest=<report.xml>} (plus
 * {@code -Dsculptory.selftest.stop=true} to stop the server afterwards, for CI, and
 * {@code -Dsculptory.selftest.player=<name>} to wait for that player's Sculptory client and run as them).
 *
 * <p>Safety: the commands exist only when the JVM was started with {@code -Dsculptory.selftest.enabled=true} or
 * {@code -Dsculptory.selftest=...} ({@link #enabled}), and then only for the console or a player holding
 * {@code sculptory.selftest} (default false; an operator does not get it). The edits run through a throwaway edit
 * service with history in memory only, so nothing reaches the world's history store or a player's undo. Every box a
 * check may change is noted first and put back at the end of the run ({@link SelfTestRun}), whatever happened: a
 * refused or failed check, the run's {@value #RUN_TIMEOUT_TICKS}-tick timeout, or the plugin being disabled mid-run;
 * the regions it adds are new each run and removed then too. The boxes' chunks are loaded for the run, and generated
 * if they never were. Whatever the checks throw, the report is written once, the clean-up runs first, and with
 * {@code sculptory.selftest.stop} the server stops afterwards (the timeout does the same), so CI never hangs.
 *
 * <p>The edits run as a player: the named online player, or a fake one (a {@link ServerPlayer} with no connection, not
 * op, given every Sculptory node through a Bukkit permission attachment, so region protection applies to it). Results
 * go to the log and to a JUnit-style report ({@code plugins/Sculptory/selftest-report.xml} by default). The checks
 * are in groups ({@link EditChecks}, {@link RegionChecks}, {@link NetChecks}, ...), each in its own corner of the
 * overworld 320+ blocks from spawn; a group that throws is reported as an error and the next group runs.
 */
public final class SelfTest {
    /** The permission a player needs for the dev commands (plugin.yml: default false). */
    public static final String PERMISSION = "sculptory.selftest";
    /** The whole run's limit; past it the run is cleaned up, the report says so and, in CI mode, the server stops. */
    private static final int RUN_TIMEOUT_TICKS = 20 * 180;
    /** How long a start-up run waits for {@code sculptory.selftest.player}'s client. */
    private static final int PLAYER_WAIT_TICKS = 20 * 600;
    private static volatile Running current;

    private SelfTest() {
    }

    /** One check (pass or fail; {@code error}: it threw), or an information entry ({@code info}: never failed). */
    record Result(String name, boolean passed, String detail, boolean info, boolean error) {
    }

    /** A named group of checks. */
    private record Group(String name, Function<SelfTestRun, CompletableFuture<Void>> checks) {
    }

    /** The checks, in order. */
    private static List<Group> groups() {
        return List.of(
                new Group("fill-undo", EditChecks::fillUndo),
                new Group("physics-off", EditChecks::physicsOff),
                new Group("worldguard", RegionChecks::cells),
                new Group("worldguard flags", RegionChecks::flags),
                new Group("worldguard loopholes", RegionChecks::loopholes),
                new Group("entities", RegionChecks::entities),
                new Group("biomes", RegionChecks::biomes),
                new Group("entities (CraftBukkit keys)", EntityChecks::craftBukkitKeys),
                new Group("builder mode", BuilderChecks::run),
                new Group("tinker", TinkerNavigateChecks::tinker),
                new Group("navigate", TinkerNavigateChecks::navigate),
                new Group("fluid trails", FluidChecks::run),
                new Group("scatter", ScatterChecks::run),
                new Group("permissions", PermissionChecks::run),
                new Group("history saving", HistoryChecks::saveHooks),
                new Group("network", NetChecks::joinRace));
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

    /**
     * At start ({@code -Dsculptory.selftest}): runs with a fake player, or once {@code sculptory.selftest.player} is
     * online with a Sculptory session; writes the report, and stops the server when asked.
     */
    public static void runAtStartup(PaperEditHost host) {
        String property = System.getProperty("sculptory.selftest");
        if (property == null || property.isBlank()) return;
        Path report = property.equals("true") ? reportPath() : Path.of(property);
        String playerName = System.getProperty("sculptory.selftest.player", "");
        Consumer<Summary> done = summary -> {
            if (Boolean.getBoolean("sculptory.selftest.stop")) {
                Sculptory.LOG.info("Sculptory self-test: stopping the server (sculptory.selftest.stop)");
                Bukkit.shutdown();
            }
        };
        if (playerName.isBlank()) {
            launch(host, "", report, done);
            return;
        }
        Sculptory.LOG.info("Sculptory self-test: waiting for {} to join with a Sculptory client", playerName);
        int[] waited = {0};
        Bukkit.getScheduler().runTaskTimer(SculptoryPlugin.instance(), task -> {
            ServerPlayer player = host.engine().server().getPlayerList().getPlayerByName(playerName);
            waited[0] += 20;
            if (player != null && ServerNet.isReady(player)) {
                task.cancel();
                // A moment for the client to settle (its own requests after the handshake).
                Bukkit.getScheduler().runTaskLater(SculptoryPlugin.instance(),
                        () -> launch(host, playerName, report, done), 40);
            } else if (waited[0] >= PLAYER_WAIT_TICKS) {
                task.cancel();
                Summary summary = Summary.error(new IllegalStateException(playerName + " did not join with a "
                        + "Sculptory client within " + PLAYER_WAIT_TICKS / 20 + " s"));
                write(report, summary);
                done.accept(summary);
            }
        }, 20, 20);
    }

    /**
     * The plugin is being disabled: a run in progress is cleaned up now (its scheduled tasks are about to be
     * cancelled) and its report written, with an error saying why.
     */
    public static void pluginDisabling() {
        Running running = current;
        if (running != null) running.end(new IllegalStateException("the plugin was disabled during the run"), false);
    }

    private static Path reportPath() {
        return SculptoryPlugin.instance().getDataFolder().toPath().resolve("selftest-report.xml");
    }

    /**
     * Starts the run on a scheduled task. Whatever happens (a throw while starting, a failed group, the overall timeout,
     * the plugin disabled), the run is cleaned up, then the report is written exactly once, then {@code done} runs.
     */
    private static void launch(PaperEditHost host, String playerName, Path report, Consumer<Summary> done) {
        Bukkit.getScheduler().runTask(SculptoryPlugin.instance(), () -> {
            if (current != null) {
                Sculptory.LOG.warn("Sculptory self-test: a run is already in progress");
                return;
            }
            SelfTestRun run;
            try {
                run = create(host, playerName);
            } catch (RuntimeException | LinkageError e) {
                Sculptory.LOG.error("Sculptory self-test: could not start", e);
                Summary summary = Summary.error(e);
                write(report, summary);
                done.accept(summary);
                return;
            }
            Running running = new Running(run, report, done);
            current = running;
            running.start();
        });
    }

    private static SelfTestRun create(PaperEditHost host, String playerName) {
        MinecraftServer server = host.engine().server();
        if (playerName.isEmpty()) {
            ServerPlayer player = new ServerPlayer(server, server.overworld(),
                    new GameProfile(UUID.nameUUIDFromBytes("sculptory-selftest".getBytes(StandardCharsets.UTF_8)),
                            "SculptoryTest"), ClientInformation.createDefault());
            PermissionAttachment attachment = player.getBukkitEntity().addAttachment(SculptoryPlugin.instance());
            for (Perm perm : Perm.values()) attachment.setPermission(perm.node(), true);
            return new SelfTestRun(host, player, attachment);
        }
        ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
        if (player == null) throw new IllegalArgumentException(playerName + " is not online");
        return new SelfTestRun(host, player, null);
    }

    /** A run in progress: its groups one after another, its timeout, and its single end. */
    private static final class Running {
        private final SelfTestRun run;
        private final Path report;
        private final Consumer<Summary> done;
        private final AtomicBoolean ended = new AtomicBoolean();
        private final List<BukkitTask> tasks = new ArrayList<>();

        Running(SelfTestRun run, Path report, Consumer<Summary> done) {
            this.run = run;
            this.report = report;
            this.done = done;
        }

        void start() {
            SculptoryPlugin plugin = SculptoryPlugin.instance();
            // The throwaway service's housekeeping (stroke timeouts, history polls) while the run lasts.
            tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, run.service::tick, 1, 1));
            tasks.add(Bukkit.getScheduler().runTaskLater(plugin, () -> end(new IllegalStateException(
                    "the self-test did not finish within " + RUN_TIMEOUT_TICKS / 20 + " s"), true), RUN_TIMEOUT_TICKS));
            Sculptory.LOG.info("Sculptory self-test: starting as {} at x={} z={} (protection: {}, run {})", run.name(),
                    run.baseX, run.baseZ, run.host.engine().permissions().protection().name(), run.runId);
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (Group group : groups()) {
                chain = chain.thenCompose(v -> {
                    if (ended.get()) return CompletableFuture.<Void>completedFuture(null);
                    CompletableFuture<Void> checks;
                    try {
                        checks = group.checks().apply(run);
                    } catch (RuntimeException | LinkageError e) {
                        checks = CompletableFuture.failedFuture(e);
                    }
                    return checks.handle((ok, error) -> {
                        if (error != null) run.error(group.name(), error);
                        return null;
                    });
                });
            }
            chain.whenComplete((v, error) -> end(error, true));
        }

        /**
         * Ends the run once: clean-up first, then the report, then {@code done} unless {@code notify} is false (the
         * plugin is being disabled: the server may be stopping already, so CI mode does not stop it again).
         */
        void end(Throwable error, boolean notify) {
            if (!ended.compareAndSet(false, true)) return;
            if (error != null) run.error("self-test run", error);
            List<Result> results = new ArrayList<>(run.results());
            try {
                run.finish();
            } catch (RuntimeException e) {
                results.add(new Result("clean-up", false, String.valueOf(e), false, true));
                Sculptory.LOG.error("Sculptory self-test: the clean-up failed", e);
            } finally {
                tasks.forEach(BukkitTask::cancel);
                current = null;
            }
            Summary summary = new Summary(results);
            Sculptory.LOG.info(summary.line());
            write(report, summary);
            if (notify) done.accept(summary);
        }
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
