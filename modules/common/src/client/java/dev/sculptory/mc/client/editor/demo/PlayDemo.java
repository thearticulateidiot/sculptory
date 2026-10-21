package dev.sculptory.mc.client.editor.demo;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.client.builder.BuilderClient;
import dev.sculptory.mc.client.editor.EditorClient;
import dev.sculptory.mc.client.editor.ExitReason;
import dev.sculptory.mc.client.editor.check.CheckDriver;
import dev.sculptory.mc.client.editor.hud.EditorUi;
import dev.sculptory.mc.client.session.SessionState;
import dev.sculptory.mc.engine.impl.EditServiceHost;
import dev.sculptory.mc.loader.ClientLoader;
import dev.sculptory.mc.loader.ClientLoaders;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameRules;
import org.lwjgl.glfw.GLFW;

/**
 * The dev-only scripted demo: once the world is joined and the editor is ready, it builds the
 * {@link DemoStage}, puts the player over it and shows "Press Enter when recording"; on Enter it plays
 * {@link DemoScript#steps()} through the real client on its own thread, captions on the {@link CaptionOverlay}, and
 * writes {@code <dir>/report.txt} (one line per step) and, at the end, {@code <dir>/chapters.txt} (the video's chapter
 * list, {@link DemoChapters}). The client stays open afterwards. Installed only when
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
    /** How long a chapter's title shows as the chapter starts, with captions on. */
    private static final long CHAPTER_TITLE_MS = 1_500;

    private enum Phase { WAITING, READY, RUNNING, DONE }

    private final Minecraft client;
    private final DemoConfig config;
    private final CaptionOverlay overlay = new CaptionOverlay();
    private final AtomicLong frames = new AtomicLong();
    private final long installedMs = Util.getMillis();
    private volatile Phase phase = Phase.WAITING;
    private volatile boolean enterPressed;
    /** When "Press Enter" came up (the auto-start counts from here). */
    private volatile long readyMs;
    private long joinedMs = -1;
    private long startedMs;
    private String waitingFor = "the world to load";
    private Thread thread;
    private boolean maximized;

    private PlayDemo(Minecraft client, DemoConfig config) {
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
        PlayDemo demo = new PlayDemo(Minecraft.getInstance(), config);
        ClientLoader loader = ClientLoaders.get();
        loader.onClientTickEnd(demo::tick);
        loader.onWorldRenderEnd(demo::frameRendered);
        // The caption bar over everything: after the HUD when no screen is open, after the screen otherwise (the
        // editor and the ring of powers are screens).
        loader.onHudRender((context, tickCounter) -> {
            if (demo.client.screen == null) {
                demo.renderOverlay(context);
            }
        });
        loader.onScreenAfterRender(demo::renderOverlay);
        Sculptory.LOG.info("Demo on: from '{}', captions {}, into {}", config.from(), config.captions(),
                config.dir());
    }

    private void renderOverlay(GuiGraphics context) {
        if (phase == Phase.WAITING || client.player == null) {
            return;
        }
        int bottom = context.guiHeight() - ABOVE_HOTBAR;
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
        if (phase == Phase.RUNNING && !client.isWindowActive()) {
            client.setWindowActive(true);
        }
    }

    private void tick() {
        keepFocus();
        long now = Util.getMillis();
        switch (phase) {
            case WAITING -> waitForGame(now);
            case READY -> {
                long window = client.getWindow().getWindow();
                boolean enter = client.screen == null && (InputConstants.isKeyDown(window, GLFW.GLFW_KEY_ENTER)
                        || InputConstants.isKeyDown(window, GLFW.GLFW_KEY_KP_ENTER));
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
                    Sculptory.LOG.warn("Demo: over {} min, stopping", RUN_TIMEOUT_MS / 60_000);
                    thread.interrupt();
                }
            }
            case DONE -> {
            }
        }
    }

    private void waitForGame(long now) {
        if (now - installedMs > START_TIMEOUT_MS) {
            Sculptory.LOG.error("Demo: gave up after {} s waiting for {}", START_TIMEOUT_MS / 1000, waitingFor);
            phase = Phase.DONE;
            return;
        }
        if (client.level == null || client.player == null) {
            joinedMs = -1;
            waitingFor = "the world to load";
            return;
        }
        if (joinedMs < 0) {
            joinedMs = now;
            client.options.pauseOnLostFocus = false;
            if (config.maximize() && !maximized) {
                maximized = true;
                GLFW.glfwMaximizeWindow(client.getWindow().getWindow());
            }
        }
        if (client.screen instanceof PauseScreen) {
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
        boolean terrain = client.levelRenderer.hasRenderedAllSections();
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
            // Tool settings are kept between games: the ones the script relies on go back to their defaults.
            driver.defaultScriptSettings();
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
            readyMs = Util.getMillis();
            DemoRunner<Demo> runner = new DemoRunner<>(DemoScript.steps(), new Host(demo, driver));
            List<DemoRunner.Result> results = runner.run(config.from());
            long ok = results.stream().filter(r -> r.status() == DemoRunner.Status.OK).count();
            long failed = results.stream().filter(r -> r.status() == DemoRunner.Status.FAILED).count();
            Sculptory.LOG.info("Demo done: {} OK, {} FAILED; report in {}", ok, failed,
                    config.dir().resolve(REPORT));
        } catch (RuntimeException | Error e) {
            Sculptory.LOG.error("Demo: setup failed", e);
            writeReport(List.of(), true, "setup failed: " + DemoRunner.describe(e));
        } finally {
            client.execute(this::finish);
        }
    }

    /** Noon, clear weather, no mobs, night vision and flying for the player, then the stage. */
    private DemoStage setUpWorld(CheckDriver driver) {
        long started = System.currentTimeMillis();
        DemoStage stage = driver.onServer(server -> {
            ServerLevel world = server.overworld();
            GameRules rules = server.getGameRules();
            rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
            rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
            rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
            world.setDayTime(NOON);
            world.setWeatherParameters(CLEAR_TICKS, 0, false, false);
            world.setRainLevel(0f);
            world.setThunderLevel(0f);
            server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(ClientboundGameEventPacket.STOP_RAINING,
                    0f));
            server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(
                    ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, 0f));
            server.getPlayerList().broadcastAll(new ClientboundGameEventPacket(
                    ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, 0f));
            ServerPlayer player = server.getPlayerList().getPlayer(client.player.getUUID());
            if (player != null) {
                player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, -1, 0, false, false));
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
            }
            return DemoStage.build(server, client.player.getUUID());
        });
        Sculptory.LOG.info("Demo: {} built in {} ms", stage, System.currentTimeMillis() - started);
        try {
            DemoLibrary.seed(EditServiceHost.defaultLibraryRoot(),
                    SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        } catch (IOException e) {
            throw new UncheckedIOException("Demo: cannot write the starter library", e);
        }
        driver.clearClientWeather();
        driver.onClient(() -> {
            if (client.player != null) {
                client.player.getInventory().selected = 0;
            }
        });
        return stage;
    }

    /** The runner's view of the game. */
    private final class Host implements DemoRunner.Host<Demo> {
        private final Demo demo;
        private final CheckDriver driver;
        private final List<DemoChapter<Demo>> chapters = DemoScript.chapters();
        private final Map<String, DemoChapter<Demo>> chapterOf = DemoChapters.byStep(chapters);

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
            DemoChapter<Demo> chapter = chapterOf.get(step.id());
            if (demo.pacing().showsCaptions() && chapter != null && chapter != chapters.get(0)
                    && chapter.steps().get(0).id().equals(step.id())) {
                // With captions, a chapter's title for a moment as it starts (the first one has the title card).
                demo.note(chapter.titleKey());
                demo.pause(CHAPTER_TITLE_MS);
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
                Sculptory.LOG.info(message);
            } else {
                Sculptory.LOG.warn(message, cause);
            }
        }

        @Override
        public void report(List<DemoRunner.Result> results, boolean done) {
            writeReport(results, done, "");
            if (done) {
                writeChapters(results);
            }
            if (!done && !results.isEmpty()) {
                picture(results.get(results.size() - 1));
            }
        }

        /** The video's chapter list, ready for the description; none when no step played. */
        private void writeChapters(List<DemoRunner.Result> results) {
            List<String> lines = DemoChapters.lines(chapters, results, driver::translate);
            if (lines.isEmpty()) {
                return;
            }
            List<String> file = new ArrayList<>();
            file.add("# Times from the Enter press: trim the recording to start where \"Press Enter\" goes.");
            file.add("# Paste the lines below into the video's description.");
            if (lines.size() < 3) {
                file.add("# YouTube shows chapters only from three on: this run has " + lines.size() + ".");
            }
            file.addAll(lines);
            try {
                Files.write(config.dir().resolve(DemoChapters.FILE), file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                Sculptory.LOG.warn("Demo: cannot write the chapters", e);
            }
        }

        /** The last frame of a step as {@code NN-<id>.png} in the demo directory, for checking a run afterwards. */
        private void picture(DemoRunner.Result result) {
            String file = String.format(Locale.ROOT, "%02d-%s.png", result.number(), result.id());
            try {
                driver.onClient(() -> {
                    try (NativeImage frame = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
                        frame.writeToFile(config.dir().resolve(file));
                    }
                });
            } catch (RuntimeException e) {
                Sculptory.LOG.warn("Demo: picture {} failed", file, e);
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
        lines.add("# Window: " + client.getWindow().getWidth() + "x"
                + client.getWindow().getHeight() + " px, GUI scale factor "
                + (int) client.getWindow().getGuiScale());
        if (!note.isEmpty()) {
            lines.add("# " + note);
        }
        for (DemoRunner.Result result : results) {
            lines.add(result.line());
        }
        try {
            Files.write(config.dir().resolve(REPORT), lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Sculptory.LOG.warn("Demo: cannot write the report", e);
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
                Sculptory.LOG.warn("Demo could not restore the editor state", e);
            }
        });
        BuilderClient.instance().ifPresent(builder -> {
            builder.pinRingKey(false);
            builder.pinKeys(Set.of());
        });
    }
}
