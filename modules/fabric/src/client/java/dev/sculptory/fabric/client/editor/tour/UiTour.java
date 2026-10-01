package dev.sculptory.fabric.client.editor.tour;

import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.editor.EditorClient;
import dev.sculptory.fabric.client.editor.ExitReason;
import dev.sculptory.fabric.client.editor.commands.CommandMenu;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.LayoutStore;
import dev.sculptory.fabric.client.session.Notice;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.util.Window;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Util;
import net.minecraft.world.GameRules;
import org.lwjgl.glfw.GLFW;

/**
 * The dev-only screenshot tour: once the world is joined and the editor handshake is ready,
 * it opens the editor, plays {@link #steps()} (each one: an action, a few frames' wait, a screenshot
 * {@code <dir>/NN-name.png}), writes {@code <dir>/index.txt} and quits the client. Installed only when
 * {@code -Dsculptory.tour=<dir>} is set ({@link TourConfig}); {@code scripts/playtest.ps1 -Tour} runs it.
 *
 * <p>Wiki mode ({@code -Dsculptory.tour.wiki=<docs/wiki/images>}, {@code scripts/playtest.ps1 -Tour -Wiki}) plays
 * {@link WikiTour#steps()} instead: each picture is cropped to its step's area and written as
 * {@code <images>/<step name>.png}, and only {@code index.txt} goes to {@code <dir>}.
 *
 * <p>The steps run between frames: the world render queues one task per frame on the client thread, which runs at the
 * start of the next frame, when the last frame is complete in the main framebuffer and nothing of the next is drawn.
 */
public final class UiTour {
    /** How long to wait for the world and a ready editor before giving up. */
    private static final long START_TIMEOUT_MS = 300_000;
    /** Time after joining for chunks to load before the first picture... */
    private static final long SETTLE_MS = 7_000;
    /** ...and at most this long for the terrain meshes to be complete. */
    private static final long TERRAIN_WAIT_MS = 30_000;
    /** No frame for this long while running (window minimised, a hang): the tour gives up and quits. */
    private static final long STALL_TIMEOUT_MS = 120_000;
    /** A tooltip shows after the theme's 500 ms hover delay; wait comfortably longer. */
    private static final long TOOLTIP_WAIT_MS = 1_200;
    /** A setting in the brushes' Mask section. */
    private static final String MASK_SETTING = "mask.rules";
    /** The tour world's fixed time of day and how long its clear weather lasts (ticks). */
    private static final long NOON = 6_000;
    private static final int CLEAR_TICKS = 1_000_000;

    private enum Phase { WAITING, RUNNING, DONE }

    private final MinecraftClient client;
    private final Path dir;
    private final long installedMs;
    private Phase phase = Phase.WAITING;
    private long joinedMs = -1;
    private String waitingFor = "the world to load";
    private TourContext context;
    private TourRunner<TourContext> runner;
    private boolean frameQueued;
    private long lastFrameMs;
    private boolean pauseOnLostFocus;
    /**
     * The UI size and window layouts every step starts from ({@link SizedLayouts#EMPTY}: the default layout at every
     * size), and where they came from.
     */
    private final int baseUiPercent;
    private final SizedLayouts baseLayouts;
    private final String layoutSource;
    /** Maximise the game window once the world is joined. */
    private final boolean maximize;
    /** Where the wiki mode writes its pictures (docs/wiki/images); null for the ordinary tour. */
    private final Path wikiDir;
    /** The size of the first picture, for the index. */
    private String pictureSize = "";

    private UiTour(MinecraftClient client, Path dir, int baseUiPercent, SizedLayouts baseLayouts, String layoutSource,
            boolean maximize, Path wikiDir) {
        this.client = client;
        this.dir = dir;
        this.baseUiPercent = baseUiPercent;
        this.baseLayouts = baseLayouts;
        this.layoutSource = layoutSource;
        this.maximize = maximize;
        this.wikiDir = wikiDir;
        this.installedMs = Util.getMeasuringTimeMs();
    }

    /**
     * Starts the tour for the {@link TourConfig#PROPERTY} value: prepares the output directory and hooks the client
     * tick and the world render. Called from the client entrypoint only when the property is set.
     */
    public static void install(String propertyValue) {
        Path dir = TourConfig.outputDir(propertyValue)
                .orElseThrow(() -> new IllegalArgumentException("-D" + TourConfig.PROPERTY + " is empty"));
        try {
            TourConfig.prepare(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Screenshot tour: cannot prepare " + dir, e);
        }
        int uiSize = TourConfig.baseUiSize(System.getProperty(TourConfig.UI_SIZE_PROPERTY));
        Optional<Path> layoutFile = TourConfig.layoutFile(System.getProperty(TourConfig.LAYOUT_PROPERTY));
        SizedLayouts layouts = SizedLayouts.EMPTY;
        String layoutSource = "the default layout";
        if (layoutFile.isPresent()) {
            try {
                // A version-1 file is the arrangement of the UI size saved beside it, as in the game.
                layouts = LayoutStore.parse(Files.readString(layoutFile.get()),
                        TourConfig.layoutUiSize(layoutFile.get()));
                layoutSource = layoutFile.get().toString();
            } catch (IOException | IllegalArgumentException e) {
                layoutSource = "the default layout (" + layoutFile.get() + " unreadable: " + e.getMessage() + ")";
            }
        }
        boolean maximize = Boolean.parseBoolean(System.getProperty(TourConfig.MAXIMIZE_PROPERTY));
        Path wikiDir = TourConfig.wikiDir(System.getProperty(TourConfig.WIKI_PROPERTY)).orElse(null);
        if (wikiDir != null) {
            try {
                Files.createDirectories(wikiDir);
            } catch (IOException e) {
                throw new UncheckedIOException("Screenshot tour: cannot create " + wikiDir, e);
            }
        }
        UiTour tour = new UiTour(MinecraftClient.getInstance(), dir, uiSize, layouts, layoutSource, maximize, wikiDir);
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tour.tick());
        WorldRenderEvents.END.register(ignored -> tour.frameRendered());
        if (wikiDir == null) {
            SculptoryMod.LOG.info("Screenshot tour on: {} steps into {}", steps().size(), dir);
        } else {
            SculptoryMod.LOG.info("Screenshot tour on, wiki mode: {} pictures into {} (index in {})",
                    WikiTour.steps().size(), wikiDir, dir);
        }
    }

    /** What this run plays: the ordinary tour, or the wiki pictures. */
    private List<TourStep<TourContext>> tourSteps() {
        return wikiDir == null ? steps() : WikiTour.steps();
    }

    /** Where this run writes: numbered pictures with the index, or the wiki pictures by name with the index apart. */
    private TourRunner.Output output() {
        return wikiDir == null ? TourRunner.Output.of(dir) : TourRunner.Output.named(wikiDir, dir);
    }

    /**
     * The tour, in order. Each step starts from
     * {@link TourContext#reset(int)}: no popup or help, the base window layout and UI size (the main
     * checkout's, or the defaults), the pointer parked over the world; the active tool and the selection carry over. A
     * step about another UI size says so in its name or description and runs at that size ({@link TourStep#atUiSize});
     * every other step shows the base size, whatever it is.
     */
    public static List<TourStep<TourContext>> steps() {
        List<TourStep<TourContext>> steps = new ArrayList<>();
        steps.add(TourStep.of("editor-opened",
                "Editor just opened: Select tool, nothing selected, the base window layout",
                tour -> {
                    tour.clearSelection();
                    tour.selectTool(ToolId.SELECT);
                }));
        List<ToolId> tools = List.of(ToolId.SELECT, ToolId.RAISE, ToolId.LOWER, ToolId.SMOOTH, ToolId.FLATTEN,
                ToolId.PAINT, ToolId.PALETTE, ToolId.PLACE, ToolId.SCATTER, ToolId.SHAPE, ToolId.GENERATE,
                ToolId.EXTRUDE, ToolId.FLUID, ToolId.TINKER, ToolId.WEATHER);
        for (int i = 0; i < tools.size(); i++) {
            ToolId tool = tools.get(i);
            steps.add(TourStep.of("tool-" + tool.value(), "Palette slot " + (i + 1) + " (" + tool.value()
                    + ") selected; Tool Settings shows its form", t -> t.selectTool(tool)));
        }
        steps.add(TourStep.of("tool-select-with-box",
                "Select tool with a 5x4x5 box selected where the pointer rests (outline, Selection window filled in)",
                tour -> {
                    tour.selectTool(ToolId.SELECT);
                    tour.selectBoxAtPointer(5, 4, 5);
                }));
        for (String window : List.of(EditorWindows.SELECTION, EditorWindows.CLIPBOARD, EditorWindows.LIBRARY,
                EditorWindows.HISTORY, EditorWindows.KEYS)) {
            steps.add(TourStep.of("window-" + window.replace('_', '-'), "The " + window
                    + " window opened over the base layout (brought to the front)", t -> t.openWindow(window)));
        }
        // The menu bar and Find a command at the base UI size, and one of each at 100% for overflow.
        for (CommandMenu menu : CommandMenu.values()) {
            String id = menu.name().toLowerCase(Locale.ROOT);
            steps.add(TourStep.of("menu-" + id, "The menu bar's " + id + " menu open", tour -> tour.openMenu(id)));
        }
        steps.add(TourStep.of("menu-view-ui-size", "View > UI size submenu open",
                tour -> tour.openMenu(EditorUi.MENU_VIEW_UI_SIZE)));
        steps.add(TourStep.<TourContext>of("menu-edit-100", "The Edit menu open at UI size 100%",
                tour -> tour.openMenu(EditorUi.MENU_EDIT)).atUiSize(100));
        steps.add(TourStep.<TourContext>of("menu-ui-size", "The top bar's UI size button menu open (UI size 100%)",
                tour -> tour.openMenu(EditorUi.MENU_UI_SIZE)).atUiSize(100));
        steps.add(TourStep.of("command-search-empty",
                "Find a command (Ctrl+K) with nothing typed: recent commands, then everything",
                tour -> tour.openCommandSearch("")));
        steps.add(TourStep.of("command-search-hol", "Find a command with \"hol\" typed",
                tour -> tour.openCommandSearch("hol")));
        steps.add(TourStep.of("command-search-raise-settings",
                "Find a command with \"s\" typed while Raise is active: commands, Raise's settings",
                tour -> {
                    tour.selectTool(ToolId.RAISE);
                    tour.openCommandSearch("s");
                }));
        steps.add(TourStep.<TourContext>of("command-search-100", "Find a command with \"undo\" typed at UI size 100%",
                tour -> tour.openCommandSearch("undo")).atUiSize(100));
        // The key sheet, the quick start card, Notifications and F6 at the base UI size; the key sheet and the card
        // also at the reference 50% and at 100% for overflow.
        steps.add(TourStep.<TourContext>of("help-sheet", "The key sheet (F1) open at UI size 100%",
                tour -> tour.showKeySheet("")).atUiSize(100));
        steps.add(TourStep.<TourContext>of("key-sheet-50", "The key sheet (F1) open, nothing typed (UI size 50%)",
                tour -> tour.showKeySheet("")).atUiSize(50));
        steps.add(TourStep.of("key-sheet-filtered", "The key sheet with \"sel\" typed in its filter",
                tour -> tour.showKeySheet("sel")));
        steps.add(TourStep.<TourContext>of("quick-start-50", "The quick start card (Help > Quick start; UI size 50%)",
                tour -> tour.showQuickStart()).atUiSize(50));
        steps.add(TourStep.<TourContext>of("quick-start-100", "The quick start card at UI size 100%",
                tour -> tour.showQuickStart()).atUiSize(100));
        steps.add(TourStep.of("window-notifications",
                "View > Notifications after three messages (aim at water and lava on and off, windows hidden and back)",
                tour -> tour.openNotificationsWithMessages()));
        steps.add(TourStep.of("focus-next-window",
                "F6 pressed once: the first control of the leftmost window has the keyboard (focus ring)",
                tour -> tour.focusNextWindow()));
        steps.add(TourStep.of("block-picker", "The block picker open from the top bar's block chip",
                tour -> tour.openMenu(EditorUi.MENU_BLOCK)));
        steps.add(TourStep.of("raise-mask-expanded",
                "Raise brush: Tool Settings with the Mask section expanded and scrolled to its header",
                tour -> {
                    tour.selectTool(ToolId.RAISE);
                    tour.expandSectionOf(MASK_SETTING);
                }));
        for (int percent : List.of(50, 100, 150)) {
            steps.add(TourStep.<TourContext>of("ui-size-" + percent,
                    "The editor at UI size " + percent + "% (Raise brush active)", tour -> {
                        // The size is the step's own (atUiSize): nothing else to do.
                    }).atUiSize(percent));
        }
        steps.add(TourStep.<TourContext>of("tooltip-palette-slot",
                "The pointer resting on palette slot 7 (Palette Paint; a middle slot, clear of the windows): its tooltip",
                tour -> tour.hoverPaletteSlot(7)).withMinMillis(TOOLTIP_WAIT_MS));
        addSettingsAndWindowSteps(steps);
        // A faded window hides the windows and toasts under it, the world still shows through.
        steps.add(TourStep.<TourContext>of("opacity-panels-30-100",
                "UI size 100%: Panels 30%, Notifications, Keys and Library open over Selection and Tool Settings with"
                        + " toasts: no window's text shows through another window, only the world", tour -> {
                    tour.ui().uiOpacity().set(tour.ui().uiOpacity().values().withPanels(30));
                    tour.openNotificationsWithMessages();
                    tour.openWindow(EditorWindows.KEYS);
                    tour.openWindow(EditorWindows.LIBRARY);
                }).atUiSize(100));
        return steps;
    }

    /**
     * Tool Settings and the windows: tooltips, typing a value, reset, wrapping rows, scrolling, the
     * Keys window's search and lines. At the base UI size unless the name ends in a size.
     */
    private static void addSettingsAndWindowSteps(List<TourStep<TourContext>> steps) {
        steps.add(TourStep.<TourContext>of("settings-tooltip",
                "Raise: the pointer on the Radius slider, the setting's tooltip",
                tour -> {
                    tour.selectTool(ToolId.RAISE);
                    tour.hover(tour.toolSettingsForm().control("radius").orElseThrow(), 0.3, 0.5);
                }).withMinMillis(TOOLTIP_WAIT_MS));
        steps.add(TourStep.<TourContext>of("settings-option-tooltip",
                "Raise: the pointer on Falloff's Smooth option, the option's own tooltip",
                tour -> {
                    tour.selectTool(ToolId.RAISE);
                    // Constant, Linear, Smooth, Sphere: Smooth is the third of four segments.
                    tour.hover(tour.toolSettingsForm().control("falloff").orElseThrow(), 0.625, 0.5);
                }).withMinMillis(TOOLTIP_WAIT_MS));
        steps.add(TourStep.of("settings-slider-typing",
                "Smooth: the Radius slider double-clicked, 12 typed in its value field (not yet kept)",
                tour -> {
                    tour.selectTool(ToolId.SMOOTH);
                    Slider radius = (Slider) tour.toolSettingsForm().control("radius").orElseThrow();
                    radius.startEditing(tour.ui().windows().context());
                    radius.editor().orElseThrow().setText("12");
                }));
        steps.add(TourStep.<TourContext>of("settings-reset",
                "Raise: Strength changed to 0.80; the pointer on its reset button, whose tooltip names the default",
                tour -> {
                    tour.selectTool(ToolId.RAISE);
                    tour.setDecimalSetting(ToolId.RAISE, "strength", 0.8);
                    tour.hover(tour.toolSettingsForm().resetButton("strength").orElseThrow(), 0.5, 0.5);
                }).withMinMillis(TOOLTIP_WAIT_MS));
        // Its own change, in view at the top.
        steps.add(TourStep.<TourContext>of("settings-reset-100",
                "Raise at UI size 100%: Strength changed to 0.80, its row scrolled into view with the value and the"
                        + " reset button at its right end (overflow check)",
                tour -> {
                    tour.selectTool(ToolId.RAISE);
                    tour.setDecimalSetting(ToolId.RAISE, "strength", 0.8);
                    tour.scrollIntoView(tour.toolSettingsForm().resetButton("strength").orElseThrow());
                }).atUiSize(100));
        steps.add(TourStep.of("settings-preset-row-narrow",
                "Raise: Tool Settings at its narrowest; Save as, Rename and Delete wrap",
                tour -> {
                    tour.selectTool(ToolId.RAISE);
                    // Strength back to its default after the two reset steps, so the preset isn't shown as changed.
                    tour.resetSetting(ToolId.RAISE, "strength");
                    tour.resizeWindow(EditorWindows.TOOL_SETTINGS, 120, 260);
                    tour.ui().toolSettings().pane().ifPresent(pane -> pane.scroll().setOffset(0));
                }));
        steps.add(TourStep.of("window-selection-narrow",
                "Select: a 5x4x5 box and the Selection window at its narrowest; its button rows wrap",
                tour -> {
                    tour.selectTool(ToolId.SELECT);
                    tour.selectBoxAtPointer(5, 4, 5);
                    tour.resizeWindow(EditorWindows.SELECTION, 150, 330);
                }));
        steps.add(TourStep.of("window-library-short",
                "The Library window short and narrow; its content scrolls, its rows wrap",
                tour -> tour.resizeWindow(EditorWindows.LIBRARY, 170, 140)));
        steps.add(TourStep.of("window-history-short",
                "The History window short and narrow; its content scrolls",
                tour -> tour.resizeWindow(EditorWindows.HISTORY, 140, 115)));
        steps.add(TourStep.<TourContext>of("window-keys-50",
                "UI size 50%: the Keys window, one line per action, tool keys named after their tools",
                tour -> tour.openWindow(EditorWindows.KEYS)).atUiSize(50));
        steps.add(TourStep.of("window-keys-search",
                "The Keys window with \"ctrl\" typed in its search box",
                tour -> {
                    tour.openWindow(EditorWindows.KEYS);
                    tour.ui().keysWindow().search("ctrl");
                }));
    }

    // ---- Driving ----

    private void tick() {
        long now = Util.getMeasuringTimeMs();
        switch (phase) {
            case WAITING -> waitForEditor(now);
            case RUNNING -> {
                if (now - lastFrameMs > STALL_TIMEOUT_MS) {
                    runner.abort("no frame rendered for " + STALL_TIMEOUT_MS / 1000 + " s");
                    quit();
                }
            }
            case DONE -> {
            }
        }
    }

    /** Waits for the world, a ready editor session and loaded terrain, then opens the editor and starts the steps. */
    private void waitForEditor(long now) {
        if (now - installedMs > START_TIMEOUT_MS) {
            runner = new TourRunner<>(tourSteps(), output(), host());
            setupNotes().forEach(runner.index()::addNote);
            runner.abort("gave up after " + START_TIMEOUT_MS / 1000 + " s waiting for " + waitingFor);
            quit();
            return;
        }
        if (client.world == null || client.player == null) {
            joinedMs = -1;
            waitingFor = "the world to load";
            return;
        }
        if (joinedMs < 0) {
            joinedMs = now;
            // An unfocused dev window would otherwise open the pause menu (and pause the integrated server).
            pauseOnLostFocus = client.options.pauseOnLostFocus;
            client.options.pauseOnLostFocus = false;
            fixDaylight();
            if (maximize) {
                // Like a typical game: maximised on its screen (not fullscreen). Earlier, during loading, it
                // does not stick.
                GLFW.glfwMaximizeWindow(client.getWindow().getHandle());
                SculptoryMod.LOG.info("Screenshot tour: maximising the game window");
            }
        }
        if (client.currentScreen instanceof GameMenuScreen) {
            client.setScreen(null);
        }
        Optional<EditorClient> editor = EditorClient.instance();
        if (editor.isEmpty()) {
            waitingFor = "the editor to initialise";
            return;
        }
        Optional<Notice> refusal = editor.get().mode().entryRefusal();
        if (refusal.isPresent()) {
            waitingFor = "the editor handshake (" + refusal.get().key() + ")";
            return;
        }
        boolean terrain = client.worldRenderer.isTerrainRenderComplete();
        if (now - joinedMs < SETTLE_MS || (!terrain && now - joinedMs < TERRAIN_WAIT_MS)) {
            waitingFor = "the terrain to load";
            return;
        }
        if (client.currentScreen != null) {
            client.setScreen(null);
        }
        if (!editor.get().mode().enter()) {
            waitingFor = "the editor to open";
            return;
        }
        context = new TourContext(client, editor.get(), baseUiPercent, baseLayouts,
                wikiDir == null ? TourContext.PITCH : TourContext.WIKI_PITCH);
        context.saveState();
        context.look();
        runner = new TourRunner<>(tourSteps(), output(), host());
        setupNotes().forEach(runner.index()::addNote);
        phase = Phase.RUNNING;
        lastFrameMs = now;
        SculptoryMod.LOG.info("Screenshot tour started ({} ms after joining; terrain complete: {})", now - joinedMs,
                terrain);
    }

    /**
     * Noon, clear skies and both cycles stopped in the tour world (the integrated server's own copy), so every tour
     * shows the terrain in the same light. Does nothing on a dedicated server.
     */
    private void fixDaylight() {
        IntegratedServer server = client.getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            server.getGameRules().get(GameRules.DO_DAYLIGHT_CYCLE).set(false, server);
            server.getGameRules().get(GameRules.DO_WEATHER_CYCLE).set(false, server);
            ServerWorld world = server.getOverworld();
            world.setTimeOfDay(NOON);
            world.setWeather(CLEAR_TICKS, 0, false, false);
        });
    }

    /** The setup lines under index.txt's summary: window, GUI scale, UI size, layout. */
    private List<String> setupNotes() {
        Window window = client.getWindow();
        int guiScale = client.options.getGuiScale().getValue();
        double factor = window.getScaleFactor();
        int scaledWidth = window.getScaledWidth();
        int scaledHeight = window.getScaledHeight();
        return List.of(
                "Window: " + window.getFramebufferWidth() + "x" + window.getFramebufferHeight() + " px"
                        + (GLFW.glfwGetWindowAttrib(window.getHandle(), GLFW.GLFW_MAXIMIZED) == GLFW.GLFW_TRUE
                                ? " (maximised)" : ""),
                "GUI scale: " + (guiScale == 0 ? "Auto" : Integer.toString(guiScale)) + ", factor "
                        + (int) factor + ", " + scaledWidth + "x" + scaledHeight + " GUI px",
                "Base UI size: " + baseUiPercent + "% (" + scaledWidth * 100 / baseUiPercent + "x"
                        + scaledHeight * 100 / baseUiPercent + " UI units)",
                "Base window layout: " + layoutSource);
    }

    /** End of a world render: queue the tour's between-frames task (at most one at a time). */
    private void frameRendered() {
        if (phase != Phase.RUNNING || frameQueued) {
            return;
        }
        frameQueued = true;
        client.send(this::betweenFrames);
    }

    private void betweenFrames() {
        frameQueued = false;
        if (phase != Phase.RUNNING) {
            return;
        }
        lastFrameMs = Util.getMeasuringTimeMs();
        context.look();
        if (runner.frame()) {
            quit();
        }
    }

    /** Puts back what the tour changed, leaves the editor and stops the client. */
    private void quit() {
        phase = Phase.DONE;
        try {
            if (context != null) {
                context.restoreState();
                context.editor().mode().exit(ExitReason.TOGGLED);
            }
        } catch (RuntimeException e) {
            SculptoryMod.LOG.warn("Screenshot tour could not restore the editor state", e);
        }
        if (joinedMs >= 0) {
            client.options.pauseOnLostFocus = pauseOnLostFocus;
        }
        SculptoryMod.LOG.info("Screenshot tour done: {}", dir);
        client.scheduleStop();
    }

    /**
     * Writes the {@code source} pixels of {@code frame} as a {@code size} picture: copied as they are when the sizes
     * match (a resampling filter would soften them), otherwise scaled down; then saved as a small palette PNG
     * ({@link TourPng}).
     */
    private static void writePart(NativeImage frame, TourCrop.Pixels source, TourCrop.Pixels size, Path file)
            throws IOException {
        try (NativeImage picture = new NativeImage(size.width(), size.height(), false)) {
            if (size.width() == source.width() && size.height() == source.height()) {
                // Reads from (x, y) in the frame, writes from (0, 0) in the picture.
                frame.copyRect(picture, source.x(), source.y(), 0, 0, source.width(), source.height(), false, false);
            } else {
                frame.resizeSubRectTo(source.x(), source.y(), source.width(), source.height(), picture);
            }
            int[] rgb = new int[size.width() * size.height()];
            for (int y = 0; y < size.height(); y++) {
                for (int x = 0; x < size.width(); x++) {
                    int abgr = picture.getColor(x, y);
                    rgb[y * size.width() + x] = (abgr & 0xFF) << 16 | (abgr & 0xFF00) | (abgr >> 16) & 0xFF;
                }
            }
            Files.write(file, TourPng.encode(rgb, size.width(), size.height()));
        }
    }

    private TourRunner.Host<TourContext> host() {
        return new TourRunner.Host<>() {
            @Override
            public TourContext context() {
                return context;
            }

            @Override
            public void prepare(TourStep<TourContext> step) {
                context.reset(step.uiPercent());
            }

            @Override
            public void capture(Path file, Optional<Rect> area, int maxWidth) throws IOException {
                try (NativeImage frame = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
                    if (pictureSize.isEmpty()) {
                        pictureSize = frame.getWidth() + "x" + frame.getHeight();
                        runner.index().addNote("Pictures: " + pictureSize + " px");
                    }
                    TourCrop.Pixels source = area.isPresent()
                            ? TourCrop.pixels(area.get(), context.ui().uiScale().factor(),
                                    client.getWindow().getScaleFactor(), frame.getWidth(), frame.getHeight())
                            : new TourCrop.Pixels(0, 0, frame.getWidth(), frame.getHeight());
                    TourCrop.Pixels size = TourCrop.fitted(source.width(), source.height(), maxWidth);
                    if (area.isEmpty() && size.width() == frame.getWidth()) {
                        frame.writeTo(file);
                        return;
                    }
                    writePart(frame, source, size, file);
                }
            }

            @Override
            public long nowMs() {
                return Util.getMeasuringTimeMs();
            }

            @Override
            public void log(String message, Throwable cause) {
                if (cause == null) {
                    SculptoryMod.LOG.info(message);
                } else {
                    SculptoryMod.LOG.warn(message, cause);
                }
            }
        };
    }
}
