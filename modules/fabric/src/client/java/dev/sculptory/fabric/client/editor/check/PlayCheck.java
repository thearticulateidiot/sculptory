package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.editor.EditorClient;
import dev.sculptory.fabric.client.editor.ExitReason;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts;
import dev.sculptory.fabric.client.session.SessionState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Util;
import net.minecraft.world.GameRules;

/**
 * The dev-only play check: once the world is joined and the editor is ready, it builds the
 * fixture areas (singleplayer), then plays each scenario on its own thread through the real client ({@link CheckDriver}),
 * records PASS/FAIL per check with pictures, writes {@code <dir>/report.txt} and quits the client. Installed only when
 * {@code -Dsculptory.check=<dir>} is set ({@link CheckConfig}); {@code scripts/playtest.ps1 -Check} runs it.
 */
public final class PlayCheck {
    /** How long to wait for the world and a ready editor. */
    private static final long START_TIMEOUT_MS = 300_000;
    /** Time after joining for chunks to load, and at most this long for the terrain meshes. */
    private static final long SETTLE_MS = 7_000;
    private static final long TERRAIN_WAIT_MS = 30_000;
    /** The whole check may take this long; then it stops where it is and writes what it has. */
    private static final long RUN_TIMEOUT_MS = 60 * 60_000;
    private static final long NOON = 6_000;
    private static final int CLEAR_TICKS = 1_000_000;
    /** The editor UI size the scenarios run at: the reference setup's, so windows cover as much of the world as they do there. */
    static final int UI_PERCENT = 50;

    private enum Phase { WAITING, RUNNING, DONE }

    private final MinecraftClient client;
    private final CheckConfig config;
    private final CheckReport report;
    private final AtomicLong frames = new AtomicLong();
    private final long installedMs = Util.getMeasuringTimeMs();
    private volatile Phase phase = Phase.WAITING;
    private long joinedMs = -1;
    private long startedMs;
    private String waitingFor = "the world to load";
    private Thread thread;
    private SizedLayouts savedLayouts;
    private int savedUiPercent;

    private PlayCheck(MinecraftClient client, CheckConfig config) {
        this.client = client;
        this.config = config;
        this.report = new CheckReport("Sculptory play check, " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)) + ", role "
                + config.role().name().toLowerCase(Locale.ROOT) + ", renderer " + config.label() + ", suite "
                + config.suite().name().toLowerCase(Locale.ROOT));
    }

    /** Starts the check. Called from the client entrypoint only when the property is set. */
    public static void install(CheckConfig config) {
        try {
            prepare(config.dir());
        } catch (IOException e) {
            throw new UncheckedIOException("Play check: cannot prepare " + config.dir(), e);
        }
        PlayCheck check = new PlayCheck(MinecraftClient.getInstance(), config);
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> check.tick());
        WorldRenderEvents.END.register(ignored -> check.frameRendered());
        SculptoryMod.LOG.info("Play check on: role {}, suite {}, into {}", config.role(), config.suite(),
                config.dir());
    }

    /** Creates the directory and removes the pictures and report an earlier run left there. */
    static void prepare(Path dir) throws IOException {
        Files.createDirectories(dir);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (Files.isRegularFile(file) && (name.endsWith(".png") || name.equals(CheckReport.FILE))) {
                    Files.delete(file);
                }
            }
        }
    }

    /** The scenario groups, in the order they run. */
    static List<ScenarioGroup> groups() {
        return List.of(SelectChecks.GROUP, BrushChecks.GROUP, WaterChecks.GROUP, PlaceChecks.GROUP,
                SelectModeChecks.GROUP, ToolChecks.GROUP, EntityChecks.GROUP, UiChecks.GROUP, FeatureChecks.GROUP,
                TwoPlayerChecks.GROUP_A, TwoPlayerChecks.GROUP_B);
    }

    // ---- Waiting ----

    /**
     * End of a world render: counts the frame, and keeps the game believing its window has focus while the check runs
     * (a brush stroke ends when the window loses focus, and two clients side by side can't both have it).
     */
    private void frameRendered() {
        frames.incrementAndGet();
        keepFocus();
    }

    private void keepFocus() {
        if (phase == Phase.RUNNING && !client.isWindowFocused()) {
            client.onWindowFocusChanged(true);
        }
    }

    private void tick() {
        keepFocus();
        long now = Util.getMeasuringTimeMs();
        switch (phase) {
            case WAITING -> waitForGame(now);
            case RUNNING -> {
                if (now - startedMs > RUN_TIMEOUT_MS && thread != null && thread.isAlive()) {
                    SculptoryMod.LOG.warn("Play check: over {} min, stopping", RUN_TIMEOUT_MS / 60_000);
                    thread.interrupt();
                }
            }
            case DONE -> {
            }
        }
    }

    private void waitForGame(long now) {
        if (now - installedMs > START_TIMEOUT_MS) {
            report.fail("setup", "the game became ready", "gave up after " + START_TIMEOUT_MS / 1000
                    + " s waiting for " + waitingFor);
            finish();
            return;
        }
        if (client.world == null || client.player == null) {
            joinedMs = -1;
            waitingFor = "the world to load";
            return;
        }
        if (joinedMs < 0) {
            joinedMs = now;
            // An unfocused window (two clients side by side) would otherwise pause.
            client.options.pauseOnLostFocus = false;
        }
        if (client.currentScreen instanceof GameMenuScreen) {
            client.setScreen(null);
        }
        if (EditorClient.instance().isEmpty()) {
            waitingFor = "the editor to initialise";
            return;
        }
        SessionState state = EditorClient.instance().get().controller().context().session()
                .map(session -> session.state()).orElse(SessionState.DISCONNECTED);
        if (state == SessionState.HANDSHAKING || state == SessionState.DISCONNECTED) {
            waitingFor = "the editor handshake (" + state + ")";
            return;
        }
        boolean terrain = client.worldRenderer.isTerrainRenderComplete();
        if (now - joinedMs < SETTLE_MS || (!terrain && now - joinedMs < TERRAIN_WAIT_MS)) {
            waitingFor = "the terrain to load";
            return;
        }
        start();
    }

    private void start() {
        phase = Phase.RUNNING;
        startedMs = Util.getMeasuringTimeMs();
        EditorClient editor = EditorClient.instance().orElseThrow();
        savedLayouts = editor.ui().windows().layouts();
        savedUiPercent = editor.ui().uiScale().percent();
        report.note("Window: " + client.getWindow().getFramebufferWidth() + "x"
                + client.getWindow().getFramebufferHeight() + " px, GUI scale factor "
                + (int) client.getWindow().getScaleFactor() + ", the player's editor UI size " + savedUiPercent
                + "%; the check runs at " + UI_PERCENT + "% (the reference size: a screen that shows about 850x490 UI units)");
        CheckDriver driver = new CheckDriver(client, editor, config.dir(), report, frames);
        thread = new Thread(() -> runAll(driver), "Sculptory play check");
        thread.setDaemon(true);
        thread.start();
    }

    // ---- Running ----

    private void runAll(CheckDriver driver) {
        try {
            Map<String, Area> areas = config.role() == CheckConfig.Role.SOLO ? setUpWorld(driver) : Map.of();
            for (ScenarioGroup group : groups()) {
                for (Scenario scenario : group.scenarios()) {
                    if (Thread.currentThread().isInterrupted()) {
                        report.skip(scenario.name(), "run", "the check ran out of time");
                        continue;
                    }
                    if (!group.roles().contains(config.role()) || !config.runs(scenario.name(), scenario.visual())) {
                        continue;
                    }
                    run(driver, areas, scenario);
                }
            }
        } catch (RuntimeException | Error e) {
            SculptoryMod.LOG.error("Play check: setup failed", e);
            report.fail("setup", "the check could start", describe(e));
        } finally {
            writeReport();
            client.execute(this::finish);
        }
    }

    /**
     * Singleplayer: noon, clear weather, no mobs, night vision and flying for the player, then every fixture area of
     * the scenarios that run.
     */
    private Map<String, Area> setUpWorld(CheckDriver driver) {
        List<Fixtures.Fixture> fixtures = new ArrayList<>();
        for (ScenarioGroup group : groups()) {
            if (!group.roles().contains(config.role())) {
                continue;
            }
            for (Fixtures.Fixture fixture : group.fixtures()) {
                boolean used = group.scenarios().stream().anyMatch(scenario -> fixture.area().equals(scenario.area())
                        && config.runs(scenario.name(), scenario.visual()));
                if (used) {
                    fixtures.add(fixture);
                }
            }
        }
        long started = System.currentTimeMillis();
        Map<String, Area> areas = driver.onServer(server -> {
            ServerWorld world = server.getOverworld();
            GameRules rules = server.getGameRules();
            rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
            rules.get(GameRules.DO_WEATHER_CYCLE).set(false, server);
            rules.get(GameRules.DO_MOB_SPAWNING).set(false, server);
            // No random ticks unless a scenario asks for them: grass spreading onto a fixture's dirt would change
            // an area between its before and after.
            rules.get(GameRules.RANDOM_TICK_SPEED).set(0, server);
            world.setTimeOfDay(NOON);
            world.setWeather(CLEAR_TICKS, 0, false, false);
            // At once: rain that is fading out still lays snow at the fixtures' height. The server tells clients
            // about the rain only as its gradient changes tick by tick, so zeroing it here means telling them
            // ourselves (else the client rains on for the whole check).
            world.setRainGradient(0f);
            world.setThunderGradient(0f);
            server.getPlayerManager().sendToAll(new GameStateChangeS2CPacket(GameStateChangeS2CPacket.RAIN_STOPPED,
                    0f));
            server.getPlayerManager().sendToAll(new GameStateChangeS2CPacket(
                    GameStateChangeS2CPacket.RAIN_GRADIENT_CHANGED, 0f));
            server.getPlayerManager().sendToAll(new GameStateChangeS2CPacket(
                    GameStateChangeS2CPacket.THUNDER_GRADIENT_CHANGED, 0f));
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(client.player.getUuid());
            if (player != null) {
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.NIGHT_VISION, -1, 0, false, false));
                player.getAbilities().flying = true;
                player.sendAbilitiesUpdate();
            }
            Map<String, Area> placed = Fixtures.layout(fixtures.stream().map(Fixtures.Fixture::area).toList(),
                    world.getSpawnPos().getX(), world.getSpawnPos().getZ());
            Map<String, Area> built = new LinkedHashMap<>();
            for (Fixtures.Fixture fixture : fixtures) {
                Area area = placed.get(fixture.area());
                Fixtures.build(world, area, fixture.build());
                built.put(fixture.area(), area);
            }
            return built;
        });
        report.note("Fixtures: " + areas.size() + " areas built in " + (System.currentTimeMillis() - started)
                + " ms: " + areas.values().stream().map(a -> a.name() + " at " + a.x0() + " " + a.y0() + " " + a.z0())
                        .toList());
        // The frozen world was saved in rain; the client must show none before the first picture.
        String rain = driver.clearClientWeather();
        if (rain.isEmpty()) {
            report.pass("setup", "the weather is clear on the client", "");
        } else {
            report.fail("setup", "the weather is clear on the client", rain);
        }
        return areas;
    }

    private void run(CheckDriver driver, Map<String, Area> areas, Scenario scenario) {
        driver.setScenario(scenario.name());
        long started = System.currentTimeMillis();
        SculptoryMod.LOG.info("Play check: {} ({})", scenario.name(), scenario.description());
        CheckRun run = new CheckRun(driver, report, config, areas, scenario);
        try {
            if (config.role() != CheckConfig.Role.B) {
                resetEditor(driver);
            }
            scenario.body().run(run);
        } catch (CheckRun.Stop stop) {
            // Recorded by the failed require.
        } catch (Exception | Error e) {
            SculptoryMod.LOG.warn("Play check: {} stopped", scenario.name(), e);
            report.fail(scenario.name(), "script", describe(e) + driver.toastNote());
        } finally {
            try {
                driver.onClient(() -> {
                    driver.editor().unpinModifiers();
                    driver.editor().unpinPointer();
                });
            } catch (RuntimeException ignored) {
                // The client is going away.
            }
            report.note(scenario.name() + ": " + (System.currentTimeMillis() - started) / 1000 + " s");
            writeReport();
        }
    }

    /**
     * Each scenario starts in the editor with the Select tool, nothing selected, no popup, help or quick start card,
     * the player's window layout and UI size.
     */
    private void resetEditor(CheckDriver driver) {
        driver.enterEditor();
        driver.standardEditor();
        driver.onClient(() -> {
            driver.controller().selectTool(ToolId.SELECT);
            driver.ctx().setSelection(null);
        });
        driver.frames(2);
    }

    private void writeReport() {
        try {
            report.write(config.dir());
        } catch (IOException e) {
            SculptoryMod.LOG.warn("Play check: cannot write the report", e);
        }
    }

    /** Leaves the editor, puts the player's layout back and stops the client. Client thread. */
    private void finish() {
        if (phase == Phase.DONE) {
            return;
        }
        phase = Phase.DONE;
        writeReport();
        EditorClient.instance().ifPresent(editor -> {
            try {
                editor.unpinPointer();
                editor.unpinModifiers();
                if (savedLayouts != null) {
                    editor.ui().uiScale().set(savedUiPercent);
                    editor.ui().windows().restore(savedLayouts, savedUiPercent);
                }
                if (editor.mode().isActive()) {
                    editor.mode().exit(ExitReason.TOGGLED);
                }
            } catch (RuntimeException e) {
                SculptoryMod.LOG.warn("Play check could not restore the editor state", e);
            }
        });
        SculptoryMod.LOG.info("Play check done: {} PASS, {} FAIL, {} SKIPPED; report in {}",
                report.count(CheckReport.Status.PASS), report.count(CheckReport.Status.FAIL),
                report.count(CheckReport.Status.SKIPPED), config.dir().resolve(CheckReport.FILE));
        client.scheduleStop();
    }

    static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
