package dev.sculptory.fabric.client.editor;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.builder.BuilderClient;
import dev.sculptory.fabric.client.editor.blocks.BlockPicker;
import dev.sculptory.fabric.client.editor.clipboard.ClipboardActions;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.QuickStart;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.input.CameraLook;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.InputRouter;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.input.KeymapStore;
import dev.sculptory.fabric.client.editor.input.MovementForwarder;
import dev.sculptory.fabric.client.editor.mask.EditMaskModel;
import dev.sculptory.fabric.client.editor.mask.EditMaskStore;
import dev.sculptory.fabric.client.editor.mc.McBlockCatalog;
import dev.sculptory.fabric.client.editor.mc.McEditorPlatform;
import dev.sculptory.fabric.client.editor.mc.McTranslator;
import dev.sculptory.fabric.client.editor.mc.McWorldDraw;
import dev.sculptory.fabric.client.editor.palettes.PaletteActions;
import dev.sculptory.fabric.client.editor.palettes.PaletteButtons;
import dev.sculptory.fabric.client.editor.presets.PresetStore;
import dev.sculptory.fabric.client.editor.presets.Presets;
import dev.sculptory.fabric.client.editor.presets.ToolSettingsStore;
import dev.sculptory.fabric.client.editor.render.OverlayOpacity;
import dev.sculptory.fabric.client.editor.render.OverlayRenderer;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostRenderer;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.RegionWork;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tools.EditorToolSet;
import dev.sculptory.fabric.client.editor.tools.brush.BrushSettings;
import dev.sculptory.fabric.client.editor.tools.brush.McBrushServices;
import dev.sculptory.fabric.client.editor.tools.brush.ShapeBrushTool;
import dev.sculptory.fabric.client.editor.tools.brush.SymmetryCentre;
import dev.sculptory.fabric.client.editor.tools.brush.TerrainBrushTool;
import dev.sculptory.fabric.client.editor.tools.extrude.ExtrudeTool;
import dev.sculptory.fabric.client.editor.tools.fluid.FluidTool;
import dev.sculptory.fabric.client.editor.tools.tinker.TinkerTool;
import dev.sculptory.fabric.client.editor.tools.generate.GenerateTool;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTransformPanel;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterMixPanel;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterMixPreset;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterTool;
import dev.sculptory.fabric.client.editor.tools.select.SelectSettings;
import dev.sculptory.fabric.client.editor.tools.select.SelectTool;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.editor.tools.select.SelectionModel;
import dev.sculptory.fabric.client.editor.tutorial.StepTexts;
import dev.sculptory.fabric.client.editor.tutorial.TutorialStore;
import dev.sculptory.fabric.client.editor.ui.FadedGraphics;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiCursor;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.UiOpacity;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.render.DrawContextGraphics;
import dev.sculptory.fabric.client.editor.ui.render.GlfwCursors;
import dev.sculptory.fabric.client.editor.ui.render.MinecraftInput;
import dev.sculptory.fabric.client.editor.ui.render.MinecraftTextMeasure;
import dev.sculptory.fabric.client.editor.wiki.McWikiResources;
import dev.sculptory.fabric.client.editor.windows.LayoutStore;
import dev.sculptory.fabric.client.editor.windows.SectionStateStore;
import dev.sculptory.fabric.client.editor.windows.UiSizeStore;
import dev.sculptory.fabric.client.editor.world.BoxFace;
import dev.sculptory.fabric.client.editor.world.GizmoPick;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.fabric.client.nav.JumpKey;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.client.session.Subscription;
import dev.sculptory.fabric.client.tinker.McTinker;
import dev.sculptory.fabric.client.tinker.TinkerController;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import dev.sculptory.fabric.client.world.ClientBlockChanges;
import dev.sculptory.fabric.config.FolderMigration;
import dev.sculptory.server.engine.Perm;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SplittableRandom;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ConfirmLinkScreen;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Util;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Wires the editor into the game: the B key binding, the tick, render and HUD hooks, and the
 * Minecraft side of {@link EditorMode}. The editor parts are built on first use (the text renderer
 * and config directory are ready by then). Client thread only.
 */
public final class EditorClient implements EditorMode.Host {
    public static final String CATEGORY = "key.categories.sculptory";
    public static final String TOGGLE_KEY = "key.sculptory.toggle_editor";
    /**
     * The dev-only screenshot tour's switch ({@code TourConfig.PROPERTY}), held here so that this class, which ships, names
     * no tour class: the mod jar leaves the tour out (DevHarnessClassesTest).
     */
    public static final String TOUR_PROPERTY = "sculptory.tour";

    private static EditorClient instance;

    private final MinecraftClient client;
    private final KeyBinding toggleKey;
    private final OverlayRenderer overlay = new OverlayRenderer();
    private final McWorldDraw worldDraw = new McWorldDraw();
    private final McBlockCatalog blocks = new McBlockCatalog();
    private final Translator translator = new McTranslator();
    private final ToastStack toasts = new ToastStack(Util::getMeasuringTimeMs);
    private final EditorMode mode = new EditorMode(this);
    private final UiScale uiScale = new UiScale();
    /** View > Opacity…: loaded with the UI size and saved with it. */
    private final UiOpacity uiOpacity = new UiOpacity();
    /** Loaded with the UI size on first use; saves it once a change settles. */
    private UiSizeStore uiSizeStore;
    /** The keymap, loaded on first use by the editor or by builder mode (its undo and redo chords work outside). */
    private KeymapStore keymapStore;
    private EditorKeymap keymap;
    private Parts parts;
    /** Whether a builder-mode power is on, and the ring key as bound (set by the builder client for the tutorial). */
    private BooleanSupplier builderPowerOn = () -> false;
    private Supplier<String> builderRingKey = () -> StepTexts.DEFAULT_RING_KEY;
    private EditorSession noticeSession;
    private Subscription noticeSubscription;
    /** The Place tool's ghost previews, drawn each frame while the editor is active. */
    private List<GhostPlacement> ghosts = List.of();
    /** What the ghost renderer said about the last frame ("Preview simplified: ..."), or "". */
    private String ghostStatus = "";
    /** CPU time the last frame's ghost pass took (the Scatter tool adapts its ghost count to it). */
    private long ghostNanos;
    /** The screenshot tour's pointer (screen x, y in GUI pixels), used instead of the mouse; null normally. */
    private double[] pinnedPointer;
    /** The play check's Shift/Ctrl/Alt (GLFW bits), used instead of the keyboard's; -1 normally. */
    private int pinnedModifiers = -1;
    /** The play check's watchers of every toast shown (dev only; empty normally). */
    private final List<Consumer<Notice>> toastWatchers = new CopyOnWriteArrayList<>();
    /** A website a wiki link asked to open: Minecraft's "open this link?" screen shows it at the next tick. */
    private String pendingLink;

    private EditorClient(MinecraftClient client, KeyBinding toggleKey) {
        this.client = client;
        this.toggleKey = toggleKey;
    }

    /** Registers the key binding and event hooks. Called once from {@code SculptoryClientMod}. */
    public static EditorClient init() {
        if (instance != null) {
            return instance;
        }
        KeyBinding toggle = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(TOGGLE_KEY, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_B, CATEGORY));
        instance = new EditorClient(MinecraftClient.getInstance(), toggle);
        instance.registerEvents();
        JumpKey.init(instance);
        return instance;
    }

    public EditorMode mode() {
        return mode;
    }

    /**
     * Builder mode tells the editor whether any of its powers is on and how its ring key is bound (the tutorial's
     * builder-mode lesson asks).
     */
    public void setBuilderMode(BooleanSupplier powerOn, Supplier<String> ringKey) {
        builderPowerOn = Objects.requireNonNull(powerOn);
        builderRingKey = Objects.requireNonNull(ringKey);
    }

    // ---- Screenshot tour hooks (dev only: -Dsculptory.tour) ----

    /** The editor client, once {@link #init} has run. */
    public static Optional<EditorClient> instance() {
        return Optional.ofNullable(instance);
    }

    /** The editor UI (built on first use). */
    public EditorUi ui() {
        return parts().ui;
    }

    /** The editor controller (built on first use). */
    public EditorController controller() {
        return parts().controller;
    }

    /**
     * Makes the editor draw, hover and pick as if the mouse were at a screen position (GUI pixels) until
     * {@link #unpinPointer}, and sends a mouse move there. Moves of the real mouse are ignored meanwhile.
     */
    public void pinPointer(double x, double y) {
        pinnedPointer = new double[] {x, y};
        if (parts != null) {
            parts.router.mouseMoved(x, y);
        }
    }

    /** Whether {@link #pinPointer} holds the pointer (the real mouse's moves are then ignored). */
    boolean pointerPinned() {
        return pinnedPointer != null;
    }

    /** Back to the real mouse. */
    public void unpinPointer() {
        pinnedPointer = null;
    }

    /**
     * Makes the editor read Shift, Ctrl and Alt as {@code modifiers} (GLFW bits) instead of the keyboard's until
     * {@link #unpinModifiers} (the play check's Ctrl+drag and Alt+click).
     */
    public void pinModifiers(int modifiers) {
        pinnedModifiers = modifiers;
        if (parts != null) {
            parts.ctx.setModifiers(modifiers);
        }
    }

    /** Back to the keyboard's modifier keys. */
    public void unpinModifiers() {
        pinnedModifiers = -1;
    }

    /** Projects world points into GUI pixels as the last rendered frame saw them (empty before the first frame). */
    public Optional<ScreenProjector> projector() {
        return parts().platform.projector();
    }

    /** The editor's toasts (the play check asks whether a message was drawn on screen). */
    public ToastStack toastStack() {
        return toasts;
    }

    /** Calls {@code watcher} with every toast the editor shows from now on (the play check reads refusals). */
    public Subscription watchToasts(Consumer<Notice> watcher) {
        toastWatchers.add(watcher);
        return () -> toastWatchers.remove(watcher);
    }

    private void registerEvents() {
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> tick());
        WorldRenderEvents.LAST.register(this::renderWorld);
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, hit) -> !EditorVisuals.editing());
        HudRenderCallback.EVENT.register(this::renderHud);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(mode::onDisconnect));
        ClientLifecycleEvents.CLIENT_STOPPING.register(ignored -> shutdown());
    }

    // ---- Parts ----

    /** Everything built on first use. */
    private final class Parts {
        final McEditorPlatform platform;
        final EditorKeymap keymap;
        final EditorContext ctx;
        final CameraLook look;
        final SelectionActions actions;
        final EditorController controller;
        final ClipboardActions clipboard;
        final PlaceTool place;
        final ScatterTool scatter;
        final EditorUi ui;
        final InputRouter router;
        final EditorScreen screen;
        final LayoutStore layout;
        final Presets presets;
        final ToolSettingsStore toolSettings;
        final GlfwCursors cursors;

        Parts() {
            platform = new McEditorPlatform(client, overlay, toggleKey, blocks);
            cursors = new GlfwCursors(client.getWindow().getHandle());
            Path config = configDirectory();
            AtomicFileStore store = new AtomicFileStore();
            // The keymap may already be loaded: builder mode reads its undo and redo chords outside the editor.
            KeymapStore keymapStore = keymapStore();
            keymap = EditorClient.this.keymap();
            layout = new LayoutStore(new ConfigFile(config.resolve(LayoutStore.FILE_NAME), store,
                    EditorClient.this::configProblem));
            ctx = new EditorContext(SessionProvider::get, EditorClient.this::toast);
            // Exact counts of large shapes and other heavy selection work: one background thread, results back on the
            // client thread (vanilla's worker pool meshes chunks and must not wait for it).
            ctx.setRegionWork(new RegionWork(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "Sculptory selection work");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }), client::execute));
            look = new CameraLook(platform.lookBackend());
            SplittableRandom seeds = new SplittableRandom();
            // One symmetry centre for the brushes, the Select operations and the Place tool.
            SymmetryCentre symmetryCentre = new SymmetryCentre();
            actions = new SelectionActions(() -> ctx.contextFor(ToolId.SELECT), ctx::activeBlock,
                    (message, onConfirm) -> ui().confirm(message, onConfirm), translator, seeds::nextLong,
                    symmetryCentre, keymap::display);
            place = new PlaceTool(placeServices(), () -> ctx.session()
                    .map(session -> session.permissions().has(Perm.PHYSICS)).orElse(false), symmetryCentre);
            scatter = new ScatterTool(scatterServices());
            McBrushServices brushServices = new McBrushServices(client, overlay, keymap, () -> ctx.cursor().ray());
            EditorToolSet.register(ctx.tools(), new SelectTool(actions, selectServices()), brushServices, place, scatter,
                    ctx::activeBlock, symmetryCentre, new GenerateTool(generateServices()),
                    new ExtrudeTool(extrudeServices(), actions),
                    new FluidTool(fluidServices(), brushServices, symmetryCentre),
                    new TinkerTool(tinkerServices(), actions, () -> ctx.contextFor(ToolId.TINKER)));
            controller = new EditorController(ctx, mode, platform, actions, keymap, translator, look::isLooking);
            controller.setNavigator(JumpKey.editorNavigator(EditorClient.this));
            // The Shape brush's Line mode builds like Generate: the same ghosts, background work and confirmations.
            ctx.tools().get(ToolId.SHAPE).filter(ShapeBrushTool.class::isInstance).map(ShapeBrushTool.class::cast)
                    .ifPresent(shape -> shape.setLineServices(generateServices()));
            clipboard = new ClipboardActions(clipboardHost());
            controller.setClipboard(clipboard);
            ui = new EditorUi(ctx, controller, platform, actions, new MinecraftTextMeasure(client.textRenderer), toasts,
                    new EditorUi.Services(translator, blocks, McEditorPlatform::itemIcon, platform::vanillaKeys,
                            "config/sculptory/editor-keys.json", keymapStore::save, uiScale(), uiOpacity));
            controller.setUi(ui);
            // The wiki bundled in the mod; a link to a website asks first, on Minecraft's own screen.
            McWikiResources wiki = new McWikiResources(client);
            ui.setWiki(wiki, wiki, EditorClient.this::openLink);
            ui.setQuickStartFlag(new QuickStart.Flag() {
                @Override
                public boolean seen() {
                    return uiSizeStore.quickStartSeen();
                }

                @Override
                public void markSeen() {
                    uiSizeStore.markQuickStartSeen();
                }
            });
            // Presets get their own store: a full file (100 presets per tool) is larger than the default limit.
            presets = new Presets(new PresetStore(new ConfigFile(config.resolve(PresetStore.FILE_NAME),
                    new AtomicFileStore(PresetStore.MAX_BYTES), EditorClient.this::configProblem)), ctx, translator,
                    this::blockAvailable);
            presets.addExtra(ToolId.SCATTER, ScatterMixPreset.KEY, new ScatterMixPreset(scatter));
            presets.load();
            ui.toolSettings().setPresets(presets);
            // Each tool's settings as last left, restored when the editor first opens (before the selected presets).
            toolSettings = new ToolSettingsStore(new ConfigFile(config.resolve(ToolSettingsStore.FILE_NAME), store,
                    EditorClient.this::configProblem), ctx, translator, this::blockAvailable);
            toolSettings.addExtra(ToolId.SCATTER, ScatterMixPreset.KEY, new ScatterMixPreset(scatter));
            toolSettings.load();
            ctx.onSettingsChanged(id -> toolSettings.changed(Util.getMeasuringTimeMs()));
            if (System.getProperty(TOUR_PROPERTY) != null) {
                // The screenshot tour changes settings on its way; they aren't kept (the constant is inlined).
                toolSettings.keepForSessionOnly();
            }
            SectionStateStore sections = SectionStateStore.of(new ConfigFile(config.resolve(SectionStateStore.FILE_NAME),
                    store, EditorClient.this::configProblem));
            sections.load();
            ui.toolSettings().setSectionStates(sections);
            // The global mask's rules, kept across games (the mask itself starts off every game).
            new EditMaskStore(new ConfigFile(config.resolve(EditMaskStore.FILE_NAME), store,
                    EditorClient.this::configProblem), EditMaskModel.global()).load();
            // Tutorial: progress in editor-tutorial.json, and a light tick when a step is done.
            ui.tutorial().setStore(TutorialStore.of(new ConfigFile(config.resolve(TutorialStore.FILE_NAME), store,
                    EditorClient.this::configProblem)));
            ui.tutorial().setStepSound(() -> client.getSoundManager()
                    .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, 0.35f)));
            ui.tutorial().setBuilderMode(() -> builderPowerOn.getAsBoolean(), () -> builderRingKey.get());
            ScatterMixPanel mixPanel = new ScatterMixPanel(scatter, ctx::session,
                    () -> clipboard.source().isEmpty() ? translator.translate("sculptory.scatter.clipboard")
                            : clipboard.source(),
                    (anchor, onPick) -> BlockPicker.open(ui.windows().context(), anchor, blocks, translator, onPick),
                    translator);
            ui.toolSettings().addPanel(ToolId.SCATTER, mixPanel);
            PaletteButtons palettes = new PaletteButtons(paletteActions(), ctx::session, ui.windows()::context,
                    translator);
            mixPanel.setPalettes(palettes);
            mixPanel.setMenus(ui.windows()::context);
            ui.toolSettings().addPanel(ToolId.PAINT, palettes.panel(ToolId.PAINT));
            ui.toolSettings().addPanel(ToolId.PALETTE, palettes.panel(ToolId.PALETTE));
            ui.toolSettings().addPanel(ToolId.SHAPE, palettes.panel(ToolId.SHAPE));
            ui.toolSettings().addPanel(ToolId.PLACE, new PlaceTransformPanel(clipboard, translator));
            ui.libraryWindow().ifPresent(window -> window.setAddToScatter(this::addToScatter));
            router = new InputRouter(ui, controller, controller, look, new MovementForwarder(platform.movementKeys()), keymap);
            screen = new EditorScreen(EditorClient.this);
            // Each UI size has its own arrangement; a version-1 file is the one of the size in use.
            int uiPercent = uiScale().percent();
            layout.load(uiPercent).ifPresent(saved -> ui.windows().restore(saved, uiPercent));
        }

        EditorUi ui() {
            return ui;
        }

        /** Whether a block exists in the game joined; true when that can't be told (no block-state space yet). */
        private boolean blockAvailable(BlockDescriptor block) {
            if (ctx.session().isEmpty()) {
                return true;
            }
            try {
                return ctx.backend().states().resolve(block) >= 0;
            } catch (IllegalStateException noStateSpace) {
                return true;
            }
        }

        /** Palettes for Paint, Palette Paint and Scatter (Save palette… and Load palette… in Tool Settings). */
        private PaletteActions paletteActions() {
            BrushSettings paint = ((TerrainBrushTool) ctx.tools().get(ToolId.PAINT).orElseThrow()).settings();
            BrushSettings palette = ((TerrainBrushTool) ctx.tools().get(ToolId.PALETTE).orElseThrow()).settings();
            return new PaletteActions(new PaletteActions.Host() {
                @Override
                public Optional<EditorSession> session() {
                    return ctx.session();
                }

                @Override
                public SettingsValues settings(ToolId id) {
                    return ctx.settings(id);
                }

                @Override
                public void updateSettings(ToolId id, SettingsValues values) {
                    ctx.updateSettings(id, values);
                }

                @Override
                public boolean selectTool(ToolId id) {
                    return controller.selectTool(id);
                }

                @Override
                public void notify(Notice notice) {
                    ctx.notify(notice);
                }

                @Override
                public Optional<StateSpace> states() {
                    if (ctx.session().isEmpty()) return Optional.empty();
                    try {
                        return Optional.of(ctx.backend().states());
                    } catch (IllegalStateException noStateSpace) {
                        return Optional.empty();
                    }
                }
            }, paint, palette, scatter);
        }

        /** The Library's Add to scatter: adds the asset to the Scatter tool's mix and brings that tool up. */
        private boolean addToScatter(String path, String contentHash) {
            boolean added = scatter.addAsset(path, contentHash);
            if (added && controller.activeScatterTool().isEmpty()) controller.selectTool(ToolId.SCATTER);
            return added;
        }

        private ScatterTool.Services scatterServices() {
            return new ScatterTool.Services() {
                @Override
                public Optional<double[]> eye() {
                    return platform.eye();
                }

                @Override
                public void showGhosts(List<GhostPlacement> placements) {
                    ghosts = List.copyOf(placements);
                }

                @Override
                public String ghostStatus() {
                    return ghostStatus;
                }

                @Override
                public long ghostRenderNanos() {
                    return ghostNanos;
                }

                @Override
                public void releaseGhost(GhostVolume volume) {
                    GhostRenderer.shared().release(volume);
                }

                @Override
                public Executor background() {
                    return Util.getMainWorkerExecutor();
                }

                @Override
                public String keyLabel(KeyAction action) {
                    return keymap.display(action);
                }

                @Override
                public Optional<KeyAction> scrollAction(int modifiers) {
                    return keymap.match(KeyChord.scroll(modifiers));
                }

                @Override
                public Translator translator() {
                    return translator;
                }

                @Override
                public long changeStamp() {
                    return ClientBlockChanges.stamp();
                }

                @Override
                public ScatterTool.ChangeWatch watchChanges(Box box) {
                    ClientBlockChanges.Watch watch = ClientBlockChanges.watch(box);
                    return new ScatterTool.ChangeWatch() {
                        @Override
                        public long changes() {
                            return watch.changes();
                        }

                        @Override
                        public void close() {
                            watch.close();
                        }
                    };
                }

                @Override
                public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
                    ui().confirm(translator.translate("sculptory.confirm.large_op", translator.translate(opNameKey),
                            SessionNotices.count(blocks)), onConfirm);
                }

                @Override
                public boolean windowFocused() {
                    return client.isWindowFocused();
                }

                @Override
                public void notify(Notice notice) {
                    ctx.notify(notice);
                }

                @Override
                public String blockName(BlockDescriptor block) {
                    return platform.blockName(block);
                }
            };
        }

        private ExtrudeTool.Services extrudeServices() {
            return new ExtrudeTool.Services() {
                @Override
                public Optional<Ray> cursorRay() {
                    return ctx.cursor().ray();
                }

                @Override
                public String keyLabel(KeyAction action) {
                    return keymap.display(action);
                }

                @Override
                public void showGhosts(List<GhostPlacement> placements) {
                    ghosts = List.copyOf(placements);
                }

                @Override
                public void releaseGhost(GhostVolume volume) {
                    GhostRenderer.shared().release(volume);
                }

                @Override
                public Executor background() {
                    return Util.getMainWorkerExecutor();
                }
            };
        }

        private FluidTool.Services fluidServices() {
            return new FluidTool.Services() {
                @Override
                public String keyLabel(KeyAction action) {
                    return keymap.display(action);
                }

                @Override
                public void confirm(String opNameKey, long blocks, int copies, Runnable onConfirm) {
                    String op = translator.translate(opNameKey);
                    String message = copies > 1
                            ? translator.translate("sculptory.confirm.large_op_copies", op, SessionNotices.count(blocks),
                                    Integer.toString(copies))
                            : translator.translate("sculptory.confirm.large_op", op, SessionNotices.count(blocks));
                    ui().confirm(message, onConfirm);
                }
            };
        }

        private TinkerTool.Services tinkerServices() {
            return new TinkerTool.Services() {
                @Override
                public Optional<TinkerController.EntityTarget> entityAt(WorldCursor cursor) {
                    Optional<Ray> ray = ctx.cursor().ray();
                    if (ray.isEmpty()) return Optional.empty();
                    double reach = McTinker.REACH;
                    if (!cursor.missed()) {
                        Ray r = ray.get();
                        double dx = cursor.hitX() - r.originX(), dy = cursor.hitY() - r.originY();
                        double dz = cursor.hitZ() - r.originZ();
                        reach = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    }
                    return McTinker.entityAt(client.world, ray.get(), reach);
                }

                @Override
                public String blockName(StateSpace states, int state) {
                    return blocks.name(states.describe(state));
                }

                @Override
                public Optional<SignText> signText(BlockPos pos) {
                    return McTinker.signText(client.world, pos);
                }

                @Override
                public String translate(String key, Object... args) {
                    return translator.translate(key, args);
                }

                @Override
                public void openPanel() {
                    ui().openTinker();
                }

                @Override
                public String keyLabel(KeyAction action) {
                    return keymap.display(action);
                }
            };
        }

        private GenerateTool.Services generateServices() {
            return new GenerateTool.Services() {
                @Override
                public void showGhosts(List<GhostPlacement> placements) {
                    ghosts = List.copyOf(placements);
                }

                @Override
                public void releaseGhost(GhostVolume volume) {
                    GhostRenderer.shared().release(volume);
                }

                @Override
                public Executor background() {
                    return Util.getMainWorkerExecutor();
                }

                @Override
                public String keyLabel(KeyAction action) {
                    return keymap.display(action);
                }

                @Override
                public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
                    ui().confirm(translator.translate("sculptory.confirm.large_op", translator.translate(opNameKey),
                            SessionNotices.count(blocks)), onConfirm);
                }
            };
        }

        private PlaceTool.Services placeServices() {
            return new PlaceTool.Services() {
                @Override
                public Optional<Ray> cursorRay() {
                    return ctx.cursor().ray();
                }

                @Override
                public Optional<ScreenProjector> projector() {
                    return platform.projector();
                }

                @Override
                public Optional<double[]> eye() {
                    return platform.eye();
                }

                @Override
                public float cameraYaw() {
                    return platform.cameraYaw();
                }

                @Override
                public void showGizmo(double x, double y, double z, GizmoPick.Handle hovered) {
                    overlay.setGizmo(x, y, z, hovered);
                }

                @Override
                public void clearGizmo() {
                    overlay.clearGizmo();
                }

                @Override
                public void showGhosts(List<GhostPlacement> placements) {
                    ghosts = List.copyOf(placements);
                }

                @Override
                public String ghostStatus() {
                    return ghostStatus;
                }

                @Override
                public void releaseGhost(GhostVolume volume) {
                    GhostRenderer.shared().release(volume);
                }

                @Override
                public String keyLabel(KeyAction action) {
                    return keymap.display(action);
                }

                @Override
                public Executor background() {
                    return Util.getMainWorkerExecutor();
                }

                @Override
                public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
                    ui().confirm(translator.translate("sculptory.confirm.large_op", translator.translate(opNameKey),
                            SessionNotices.count(blocks)), onConfirm);
                }

                @Override
                public void confirm(String opNameKey, long blocks, int copies, Runnable onConfirm) {
                    if (copies <= 1) {
                        confirm(opNameKey, blocks, onConfirm);
                        return;
                    }
                    ui().confirm(translator.translate("sculptory.confirm.large_op_copies",
                            translator.translate(opNameKey), SessionNotices.count(blocks), Integer.toString(copies)), onConfirm);
                }

                @Override
                public void finished() {
                    controller.placeFinished();
                }
            };
        }

        private ClipboardActions.Host clipboardHost() {
            return new ClipboardActions.Host() {
                @Override
                public Optional<EditorSession> session() {
                    return ctx.session();
                }

                @Override
                public Optional<Box> selection() {
                    return ctx.selection();
                }

                @Override
                public Optional<Region> selectionRegion() {
                    return ctx.selectionRegion();
                }

                @Override
                public RegionWork regionWork() {
                    return ctx.regionWork();
                }

                @Override
                public void notify(Notice notice) {
                    ctx.notify(notice);
                }

                @Override
                public boolean place(PlaceTool.Request request) {
                    return controller.place(request);
                }

                @Override
                public Optional<PlaceTool> activePlaceTool() {
                    return controller.activePlaceTool();
                }

                @Override
                public String keyLabel(KeyAction action) {
                    return keymap.display(action);
                }

                @Override
                public Path exportDirectory() {
                    return FolderMigration.gameDir().resolve("exports");
                }

                @Override
                public Executor io() {
                    return Util.getIoWorkerExecutor();
                }

                @Override
                public Executor mainThread() {
                    return client::execute;
                }

                @Override
                public EntityFilter copyEntities() {
                    return ctx.settings(ToolId.SELECT).get(SelectSettings.ENTITIES);
                }
            };
        }

        private SelectTool.Services selectServices() {
            return new SelectTool.Services() {
                @Override
                public Optional<Ray> cursorRay() {
                    return ctx.cursor().ray();
                }

                @Override
                public Optional<ScreenProjector> projector() {
                    return platform.projector();
                }

                @Override
                public Optional<double[]> eye() {
                    return platform.eye();
                }

                @Override
                public float cameraYaw() {
                    return platform.cameraYaw();
                }

                @Override
                public void setHoveredFace(BoxFace face) {
                    ctx.setHoveredFace(face);
                }

                @Override
                public String keyLabel(KeyAction action) {
                    return keymap.display(action);
                }

                @Override
                public Optional<String> outlineNote() {
                    return overlay.selectionNote();
                }
            };
        }
    }

    private Parts parts() {
        if (parts == null) {
            parts = new Parts();
        }
        return parts;
    }

    private static Path configDirectory() {
        return FolderMigration.configDir();
    }

    /**
     * The editor UI size, read from {@code editor-ui.json} on first use (the HUD callback may need
     * it for toasts before the editor has ever opened), with the opacity of View > Opacity… ({@link #uiOpacity}).
     * Changes are saved once they settle and when the editor closes.
     */
    private KeymapStore keymapStore() {
        if (keymapStore == null) {
            keymapStore = new KeymapStore(new ConfigFile(configDirectory().resolve("editor-keys.json"),
                    new AtomicFileStore(), this::configProblem));
        }
        return keymapStore;
    }

    /** The editor keymap ({@code editor-keys.json}), loaded on first use. */
    public EditorKeymap keymap() {
        if (keymap == null) {
            keymap = keymapStore().load();
        }
        return keymap;
    }

    /**
     * The symmetry builder mode's Mirror power uses: the Place tool's symmetry mode around the set centre (M), as a
     * placement in the editor would; empty while the mode is off, no centre is set or the editor was never opened.
     */
    public Optional<dev.sculptory.core.brush.Symmetry> builderSymmetry() {
        if (parts == null) {
            return Optional.empty();
        }
        return parts.place.symmetry(parts.ctx.contextFor(ToolId.PLACE)).filter(symmetry -> !symmetry.isOff());
    }

    private UiScale uiScale() {
        if (uiSizeStore == null) {
            uiSizeStore = new UiSizeStore(new ConfigFile(configDirectory().resolve("editor-ui.json"),
                    new AtomicFileStore(), this::configProblem));
            uiScale.set(uiSizeStore.load());
            uiScale.addListener(percent -> uiSizeStore.changed(percent, Util.getMeasuringTimeMs()));
            uiOpacity.set(uiSizeStore.opacity());
            uiOpacity.addListener(values -> uiSizeStore.opacityChanged(values, Util.getMeasuringTimeMs()));
            // The tools' world overlays follow the Tool outlines setting.
            OverlayOpacity.follow(uiOpacity);
        }
        return uiScale;
    }

    // ---- Screen callbacks ----

    InputRouter router() {
        return parts().router;
    }

    void layoutScreen(int width, int height) {
        parts().ui.layout(width, height);
    }

    void renderScreen(DrawContext context, int mouseX, int mouseY) {
        Parts p = parts();
        p.look.frame();
        liveModifiers();
        // The render mouse is rounded down to whole GUI pixels. The frame's world pick and the UI's hover use the same
        // sub-pixel position the mouse events get (vanilla's formula), so a smaller UI size's hover and click never
        // disagree, and a frame's cursor ray is the press's while the mouse is still (up to a GUI pixel off, about
        // 0.3° at a large GUI scale, would make the Shape brush take a click held still for a drag).
        Window window = client.getWindow();
        double pointerX = window.getWidth() > 0 ? client.mouse.getX() * window.getScaledWidth() / window.getWidth()
                : mouseX;
        double pointerY = window.getHeight() > 0 ? client.mouse.getY() * window.getScaledHeight() / window.getHeight()
                : mouseY;
        if (pinnedPointer != null) {
            pointerX = pinnedPointer[0];
            pointerY = pinnedPointer[1];
        }
        // World picking uses the GUI-scaled screen position; the UI maps it to its own units.
        p.controller.frame(pointerX, pointerY, System.nanoTime(), client.getRenderTickCounter().getTickDelta(true));
        p.ui.render(new DrawContextGraphics(context, client.textRenderer), pointerX, pointerY,
                Util.getMeasuringTimeMs(), p.look.isLooking());
        boolean worldOwnsPointer = p.look.isLooking() || p.router.isToolDragging();
        p.cursors.set(worldOwnsPointer ? UiCursor.DEFAULT : p.ui.cursor());
    }

    /** Reads Shift/Ctrl/Alt from the keyboard, stores them for tools, and returns them. */
    int liveModifiers() {
        int modifiers = pinnedModifiers >= 0 ? pinnedModifiers : MinecraftInput.modifiers();
        if (parts != null) {
            parts.ctx.setModifiers(modifiers);
        }
        return modifiers;
    }

    void setModifiers(int modifiers) {
        if (parts != null) {
            parts.ctx.setModifiers(modifiers);
        }
    }

    void closeRequested() {
        mode.exit(ExitReason.TOGGLED);
    }

    void screenRemoved() {
        if (parts == null) {
            return;
        }
        if (parts.look.isLooking()) {
            parts.look.end();
        }
        parts.cursors.set(UiCursor.DEFAULT);
    }

    // ---- Hooks ----

    private void tick() {
        SessionProvider.get().tick();
        if (uiSizeStore != null) {
            uiSizeStore.saveIfSettled(Util.getMeasuringTimeMs());
        }
        if (parts != null) {
            parts.toolSettings.saveIfSettled(Util.getMeasuringTimeMs());
        }
        syncNotices();
        while (toggleKey.wasPressed()) {
            if (client.currentScreen == null && client.player != null) {
                mode.toggle();
            }
        }
        if (mode.isEditing()) {
            mode.tick(observe());
        }
        if (mode.isActive() && parts != null) {
            parts.controller.tick();
        }
    }

    private EditorMode.Observation observe() {
        boolean inWorld = client.world != null && client.player != null;
        boolean dead = client.player != null && (client.player.isDead() || client.currentScreen instanceof DeathScreen);
        EditorMode.ScreenState screen = client.currentScreen == null ? EditorMode.ScreenState.NONE
                : parts != null && client.currentScreen == parts.screen ? EditorMode.ScreenState.EDITOR
                : EditorMode.ScreenState.OTHER;
        return new EditorMode.Observation(inWorld, dead, client.world, screen);
    }

    private void syncNotices() {
        EditorSession session = SessionProvider.get().session().orElse(null);
        if (session == noticeSession) {
            return;
        }
        if (noticeSubscription != null) {
            noticeSubscription.close();
        }
        noticeSession = session;
        noticeSubscription = session == null ? null : session.onNotice(this::toast);
    }

    private void renderWorld(WorldRenderContext context) {
        if (mode.isEditing() && parts != null) {
            Optional<Selection> selection = parts.ctx.selectionState();
            if (selection.isPresent()) {
                // The region as built plus its pending move: a moved cell set is never rebuilt per frame.
                int[] moved = selection.get().offset();
                overlay.setSelection(SelectionModel.toAabb(selection.get().bounds()), parts.ctx.hoveredFace());
                overlay.setSelectionRegion(selection.get().base(), moved[0], moved[1], moved[2]);
            } else {
                overlay.clearSelection();
            }
        } else {
            overlay.clearAll();
        }
        overlay.render(context);
        if (mode.isActive() && parts != null) {
            parts.ctx.tools().active().ifPresent(tool -> {
                Vec3d camera = context.camera().getPos();
                worldDraw.begin(camera.x, camera.y, camera.z, context.positionMatrix(), context.projectionMatrix());
                try {
                    tool.renderWorld(parts.ctx.contextFor(tool.descriptor().id()), worldDraw);
                } finally {
                    worldDraw.end();
                }
            });
            renderGhosts(context);
        } else {
            ghostStatus = "";
        }
    }

    /**
     * Draws the Place or Scatter tool's ghost previews, and keeps the renderer's note for the HUD and the time the pass
     * took (the Scatter tool adapts its ghost count to it).
     */
    private void renderGhosts(WorldRenderContext context) {
        if (ghosts.isEmpty()) {
            ghostStatus = "";
            ghostNanos = 0;
            return;
        }
        long start = System.nanoTime();
        GhostRenderer renderer = GhostRenderer.shared();
        String status = "";
        for (GhostPlacement placement : ghosts) {
            String text = renderer.render(context, placement).hudText();
            if (status.isEmpty()) {
                status = text;
            }
        }
        ghostStatus = status;
        ghostNanos = System.nanoTime() - start;
    }

    // ---- Dropped files ----

    /**
     * Files dropped onto the editor screen: a schematic (.schem, .litematic, .nbt) is imported and placed, or, with the
     * Scatter tool active, added to its mix.
     */
    void filesDragged(List<Path> paths) {
        if (parts == null || !mode.isActive()) {
            return;
        }
        if (parts.controller.activeScatterTool().isPresent()) {
            parts.clipboard.upload(paths, (name, entry) -> parts.scatter.addClipboard(entry, name));
        } else {
            parts.clipboard.upload(paths);
        }
    }

    private void renderHud(DrawContext context, RenderTickCounter tickCounter) {
        if (mode.isActive() || client.options.hudHidden) {
            return;
        }
        Window window = client.getWindow();
        DrawContextGraphics graphics = new DrawContextGraphics(context, client.textRenderer);
        if (parts != null) {
            parts.ui.renderPassive(graphics, window.getScaledWidth(), window.getScaledHeight(), Util.getMeasuringTimeMs());
        } else {
            UiScale scale = uiScale();
            UiGraphics faded = FadedGraphics.of(graphics, Theme.DARK, uiOpacity.values().panelAlpha());
            scale.draw(graphics, () -> toasts.render(faded, new MinecraftTextMeasure(client.textRenderer), Theme.DARK,
                    scale.uiLength(window.getScaledWidth()) - 4, 4));
        }
    }

    private void shutdown() {
        if (parts != null && mode.isEditing()) {
            parts.layout.save(parts.ui.windows().layouts());
        }
        if (parts != null) {
            parts.toolSettings.saveNow();
        }
        if (uiSizeStore != null) {
            uiSizeStore.saveNow();
        }
        if (parts != null) {
            parts.cursors.close();
        }
        overlay.close();
        worldDraw.close();
    }

    private void configProblem(String message) {
        SculptoryMod.LOG.warn("Sculptory editor config: {}", message);
        toasts.show(Notice.Level.WARNING, message);
    }

    // ---- EditorMode.Host ----

    @Override
    public Optional<EditorSession> session() {
        return SessionProvider.get().session();
    }

    @Override
    public Object currentWorld() {
        return client.world;
    }

    @Override
    public void showEditorScreen() {
        Parts p = parts();
        p.ui.setEditing(true);
        client.setScreen(p.screen);
    }

    @Override
    public void hideEditorScreen() {
        if (parts != null && client.currentScreen == parts.screen) {
            client.setScreen(null);
        }
    }

    @Override
    public void openVanillaScreen(EditorMode.SuspendTarget target) {
        switch (target) {
            case CHAT -> client.setScreen(new ChatScreen(""));
            case COMMAND -> client.setScreen(new ChatScreen("/"));
            case INVENTORY -> {
                if (client.player == null || client.interactionManager == null) {
                    return;
                }
                if (client.interactionManager.hasRidingInventory()) {
                    client.player.openRidingInventory();
                } else {
                    client.setScreen(new InventoryScreen(client.player));
                }
            }
            case LINK -> {
                String url = pendingLink;
                pendingLink = null;
                if (url != null) {
                    // No screen afterwards: the editor then comes back, as after chat.
                    client.setScreen(new ConfirmLinkScreen(open -> {
                        if (open) {
                            Util.getOperatingSystem().open(url);
                        }
                        client.setScreen(null);
                    }, url, true));
                }
            }
        }
    }

    /**
     * A wiki link to a website: the editor steps aside (as for chat) and Minecraft's "open this link?" screen asks
     * first; the editor comes back when that screen closes.
     */
    private void openLink(String url) {
        pendingLink = url;
        mode.suspend(EditorMode.SuspendTarget.LINK);
    }

    @Override
    public void setEditorVisuals(boolean editing) {
        EditorVisuals.setEditing(editing);
        client.gameRenderer.setRenderHand(!editing);
        if (parts != null) {
            parts.ui.setEditing(editing);
        }
        if (!editing) {
            overlay.clearAll();
            // The tools' outline meshes and vertex buffers go now, not at the next editor frame.
            worldDraw.releaseOutlines();
        }
    }

    @Override
    public void activated() {
        Parts p = parts();
        p.keymap.setReservedKeys(p.platform.reservedKeys());
        p.router.reset();
        // Once per game start (the server's limits are known by now): each tool's settings as last left, and for the
        // tools without them their remembered preset.
        p.presets.restoreSelections(p.toolSettings.restore());
        p.controller.onEntered();
        // The first time (until dismissed): the quick start card.
        p.ui.showQuickStartIfNew();
    }

    @Override
    public void deactivated(DeactivateReason reason) {
        if (parts == null) {
            return;
        }
        parts.router.reset();
        parts.controller.onExited(reason);
        parts.toolSettings.saveNow();
        if (uiSizeStore != null) {
            uiSizeStore.saveNow();
        }
        if (reason != DeactivateReason.SUSPENDED) {
            parts.layout.save(parts.ui.windows().layouts());
        }
    }

    @Override
    public void toast(Notice notice) {
        // Builder mode's refusals go to the action bar while the editor is closed (BuilderClient shows them).
        if (!mode.isEditing() && notice.key().startsWith(BuilderClient.NOTICE_PREFIX)) {
            return;
        }
        toasts.show(notice.level(), translator.translate(notice.key(), notice.args()));
        for (Consumer<Notice> watcher : toastWatchers) {
            watcher.accept(notice);
        }
    }
}
