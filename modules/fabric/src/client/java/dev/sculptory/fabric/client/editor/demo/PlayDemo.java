package dev.sculptory.fabric.client.editor.demo;

import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.builder.BuilderClient;
import dev.sculptory.fabric.client.editor.EditorClient;
import dev.sculptory.fabric.client.editor.ExitReason;
import dev.sculptory.fabric.client.editor.check.CheckDriver;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.session.SessionState;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.s2c.play.GameStateChangeS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Util;
import net.minecraft.world.GameRules;
import org.lwjgl.glfw.GLFW;

/**
 * The dev-only scripted demo: once the world is joined and the editor is ready, it builds the
 * {@link DemoStage}, puts the player over it and shows "Press Enter when recording"; on Enter it plays
 * {@link DemoScript#steps()} through the real client on its own thread, captions on the {@link CaptionOverlay}, and
 * writes {@code <dir>/report.txt} (one line per step). The client stays open afterwards. Installed only when
 * {@code -Dsculptory.demo=<dir>} is set ({@link DemoConfig}); {@code scripts/playtest.ps1 -Demo} runs it.
 */
public final class PlayDemo {
    public static final String REPORT = "report.txt";
    private static final long START_TIMEOUT_MS = 300_000;
    private static final long SETTLE_MS = 6_000;
    private static final long TERRAIN_WAIT_MS = 30_000;
    /** The whole demo may take this long; then it stops where it is. */
    private static final long RUN_TIMEOUT_MS = 40 * 60_000;
    private static final long NOON = 6_000;
    private static final int CLEAR_TICKS = 1_000_000;
    /** The caption's bottom edge above the hotbar, outside the editor (GUI pixels). */
    private static final int ABOVE_HOTBAR = 48;
    private static final int ABOVE_UI = 6;

    private enum Phase { WAITING, READY, RUNNING, DONE }

    private final MinecraftClient client;
    private final DemoConfig config;
    private final CaptionOverlay overlay = new CaptionOverlay();
    private final AtomicLong frames = new AtomicLong();
    private final long installedMs = Util.getMeasuringTimeMs();
    private volatile Phase phase = Phase.WAITING;
    private volatile boolean enterPressed;
    /** When "Press Enter" came up (the auto-start counts from here). */
    private volatile long readyMs;
    private long joinedMs = -1;
    private long startedMs;
    private String waitingFor = "the world to load";
    private Thread thread;
    private boolean maximized;

    private PlayDemo(MinecraftClient client, DemoConfig config) {
        this.client = client;
        this.config = config;
    }

    /** Starts the demo. Called from the client entrypoint only when the property is set. */
    public static void install(DemoConfig config) {
        try {
            Files.createDirectories(config.dir());
        } catch (IOException e) {
            throw new IllegalStateException("Demo: cannot create " + config.dir(), e);
        }
        // A bad -DemoFrom fails now, before the game starts.
        DemoRunner.startIndex(DemoScript.steps(), config.from());
        PlayDemo demo = new PlayDemo(MinecraftClient.getInstance(), config);
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> demo.tick());
        WorldRenderEvents.END.register(ignored -> demo.frameRendered());
        // The caption bar over everything: after the HUD when no screen is open, after the screen otherwise (the
        // editor and the ring of powers are screens).
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            if (demo.client.currentScreen == null) {
                demo.renderOverlay(context);
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, delta) ->
                        demo.renderOverlay(context)));
        SculptoryMod.LOG.info("Demo on: from '{}', captions {}, into {}", config.from(), config.captions(),
                config.dir());
    }

    private void renderOverlay(DrawContext context) {
        if (phase == Phase.WAITING || client.player == null) {
            return;
        }
        int bottom = context.getScaledWindowHeight() - ABOVE_HOTBAR;
        EditorClient editor = EditorClient.instance().orElse(null);
        if (editor != null && editor.mode().isActive()) {
            EditorUi ui = editor.ui();
            int top = Integer.MAX_VALUE;
            if (!ui.hintLine().bounds().isEmpty()) {
                top = Math.min(top, ui.hintLine().bounds().y());
            }
            ui.paletteSlotBounds(1).filter(rect -> !rect.isEmpty()).ifPresent(rect -> {});
            var slot = ui.paletteSlotBounds(1);
            if (slot.isPresent() && !slot.get().isEmpty()) {
                top = Math.min(top, slot.get().y());
            }
            if (top != Integer.MAX_VALUE) {
                bottom = Math.min(bottom, (int) (top * ui.uiScale().factor()) - ABOVE_UI);
            }
        }
        overlay.render(context, client, bottom);
    }

    // ---- Waiting ----

    private void frameRendered() {
        frames.incrementAndGet();
        keepFocus();
    }

    /** The game keeps believing its window has focus while the demo runs (a brush stroke ends when focus goes). */
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
            case READY -> {
                long window = client.getWindow().getHandle();
                boolean enter = client.currentScreen == null && (InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_ENTER)
                        || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_KP_ENTER));
                boolean auto = config.autoStartSeconds() > 0 && readyMs > 0
                        && now - readyMs > config.autoStartSeconds() * 1_000L;
                if (enter || auto) {
                    enterPressed = true;
                    phase = Phase.RUNNING;
                    startedMs = now;
                    // The prompt goes the moment Enter is pressed (a take without captions shows nothing after it).
                    overlay.clear();
                }
            }
            case RUNNING -> {
                if (now - startedMs > RUN_TIMEOUT_MS && thread != null && thread.isAlive()) {
                    SculptoryMod.LOG.warn("Demo: over {} min, stopping", RUN_TIMEOUT_MS / 60_000);
                    thread.interrupt();
                }
            }
            case DONE -> {
            }
        }
    }

    private void waitForGame(long now) {
        if (now - installedMs > START_TIMEOUT_MS) {
            SculptoryMod.LOG.error("Demo: gave up after {} s waiting for {}", START_TIMEOUT_MS / 1000, waitingFor);
            phase = Phase.DONE;
            return;
        }
        if (client.world == null || client.player == null) {
            joinedMs = -1;
            waitingFor = "the world to load";
            return;
        }
        if (joinedMs < 0) {
            joinedMs = now;
            client.options.pauseOnLostFocus = false;
            if (config.maximize() && !maximized) {
                maximized = true;
                GLFW.glfwMaximizeWindow(client.getWindow().getHandle());
            }
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
        phase = Phase.READY;
        EditorClient editor = EditorClient.instance().orElseThrow();
        CheckDriver driver = new CheckDriver(client, editor, config.dir(), frames);
        thread = new Thread(() -> run(driver, editor), "Sculptory demo");
        thread.setDaemon(true);
        thread.start();
    }

    // ---- Running (the demo thread) ----

    private void run(CheckDriver driver, EditorClient editor) {
        try {
            overlay.show(driver.translate(DemoCaptions.PREPARING));
            DemoStage stage = setUpWorld(driver);
            Demo demo = new Demo(client, editor, driver, overlay, stage, config.pacing());
            // Over the stage, outside the editor, for the opening view.
            driver.view(stage.x(56) + 0.5, stage.y(52), stage.z(136) + 0.5, stage.x(56) + 0.5, stage.y(0),
                    stage.z(52) + 0.5);
            driver.onClient(() -> {
                if (editor.mode().isActive()) {
                    editor.mode().exit(ExitReason.TOGGLED);
                }
            });
            overlay.show(driver.translate(DemoCaptions.READY));
            readyMs = Util.getMeasuringTimeMs();
            DemoRunner<Demo> runner = new DemoRunner<>(DemoScript.steps(), new Host(demo, driver));
            List<DemoRunner.Result> results = runner.run(config.from());
            long ok = results.stream().filter(r -> r.status() == DemoRunner.Status.OK).count();
            long failed = results.stream().filter(r -> r.status() == DemoRunner.Status.FAILED).count();
            SculptoryMod.LOG.info("Demo done: {} OK, {} FAILED; report in {}", ok, failed,
                    config.dir().resolve(REPORT));
        } catch (RuntimeException | Error e) {
            SculptoryMod.LOG.error("Demo: setup failed", e);
            writeReport(List.of(), true, "setup failed: " + DemoRunner.describe(e));
        } finally {
            client.execute(this::finish);
        }
    }

    /** Noon, clear weather, no mobs, night vision and flying for the player, then the stage. */
    private DemoStage setUpWorld(CheckDriver driver) {
        long started = System.currentTimeMillis();
        DemoStage stage = driver.onServer(server -> {
            ServerWorld world = server.getOverworld();
            GameRules rules = server.getGameRules();
            rules.get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
            rules.get(GameRules.DO_WEATHER_CYCLE).set(false, server);
            rules.get(GameRules.DO_MOB_SPAWNING).set(false, server);
            rules.get(GameRules.RANDOM_TICK_SPEED).set(0, server);
            world.setTimeOfDay(NOON);
            world.setWeather(CLEAR_TICKS, 0, false, false);
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
            return DemoStage.build(server, client.player.getUuid());
        });
        SculptoryMod.LOG.info("Demo: {} built in {} ms", stage, System.currentTimeMillis() - started);
        driver.clearClientWeather();
        driver.onClient(() -> {
            if (client.player != null) {
                client.player.getInventory().selectedSlot = 0;
            }
        });
        return stage;
    }

    /** The runner's view of the game. */
    private final class Host implements DemoRunner.Host<Demo> {
        private final Demo demo;
        private final CheckDriver driver;

        Host(Demo demo, CheckDriver driver) {
            this.demo = demo;
            this.driver = driver;
        }

        @Override
        public Demo context() {
            return demo;
        }

        @Override
        public boolean awaitEnter() {
            while (!enterPressed) {
                if (phase == Phase.DONE || Thread.currentThread().isInterrupted()) {
                    return false;
                }
                driver.millis(50);
            }
            return true;
        }

        @Override
        public void started() {
            demo.clearCaption();
        }

        @Override
        public void prepare(DemoStep<Demo> step) {
            driver.onClient(() -> {
                driver.editor().unpinModifiers();
                BuilderClient.instance().ifPresent(builder -> builder.pinKeys(Set.of()));
            });
            if (step.startsInEditor()) {
                if (!driver.onClient(() -> driver.editor().mode().isActive())) {
                    driver.enterEditor();
                    driver.onClient(() -> driver.ui().quickStart().hide());
                }
                demo.tidyEditor();
            }
        }

        @Override
        public void finish() {
            demo.clearCaption();
        }

        @Override
        public long nowMs() {
            return System.currentTimeMillis();
        }

        @Override
        public void log(String message, Throwable cause) {
            if (cause == null) {
                SculptoryMod.LOG.info(message);
            } else {
                SculptoryMod.LOG.warn(message, cause);
            }
        }

        @Override
        public void report(List<DemoRunner.Result> results, boolean done) {
            writeReport(results, done, "");
            if (!done && !results.isEmpty()) {
                picture(results.get(results.size() - 1));
            }
        }

        /** The last frame of a step as {@code NN-<id>.png} in the demo directory, for checking a run afterwards. */
        private void picture(DemoRunner.Result result) {
            String file = String.format(Locale.ROOT, "%02d-%s.png", result.number(), result.id());
            try {
                driver.onClient(() -> {
                    try (NativeImage frame = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
                        frame.writeTo(config.dir().resolve(file));
                    }
                });
            } catch (RuntimeException e) {
                SculptoryMod.LOG.warn("Demo: picture {} failed", file, e);
            }
        }
    }

    private void writeReport(List<DemoRunner.Result> results, boolean done, String note) {
        List<String> lines = new ArrayList<>();
        lines.add("# Sculptory demo, " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm",
                Locale.ROOT)) + (done ? "" : " (running)"));
        long ok = results.stream().filter(r -> r.status() == DemoRunner.Status.OK).count();
        long failed = results.stream().filter(r -> r.status() == DemoRunner.Status.FAILED).count();
        long skipped = results.stream().filter(r -> r.status() == DemoRunner.Status.SKIPPED).count();
        long total = results.stream().mapToLong(DemoRunner.Result::millis).sum();
        lines.add(String.format(Locale.ROOT, "# %d steps: %d OK, %d FAILED, %d SKIPPED; %d:%02d played", results.size(),
                ok, failed, skipped, total / 60_000, (total / 1000) % 60));
        lines.add("# Window: " + client.getWindow().getFramebufferWidth() + "x"
                + client.getWindow().getFramebufferHeight() + " px, GUI scale factor "
                + (int) client.getWindow().getScaleFactor());
        if (!note.isEmpty()) {
            lines.add("# " + note);
        }
        for (DemoRunner.Result result : results) {
            lines.add(result.line());
        }
        try {
            Files.write(config.dir().resolve(REPORT), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            SculptoryMod.LOG.warn("Demo: cannot write the report", e);
        }
    }

    /** Leaves the editor closed, the pointer free and the caption gone; the client stays open. Client thread. */
    private void finish() {
        if (phase == Phase.DONE) {
            return;
        }
        phase = Phase.DONE;
        overlay.clear();
        overlay.setPointerShown(false);
        EditorClient.instance().ifPresent(editor -> {
            try {
                editor.unpinPointer();
                editor.unpinModifiers();
                if (editor.mode().isActive()) {
                    editor.mode().exit(ExitReason.TOGGLED);
                }
            } catch (RuntimeException e) {
                SculptoryMod.LOG.warn("Demo could not restore the editor state", e);
            }
        });
        BuilderClient.instance().ifPresent(builder -> {
            builder.pinRingKey(false);
            builder.pinKeys(Set.of());
        });
    }
}
