package dev.sculptory.fabric.client.editor.tools.scatter;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.SparseUpload;
import dev.sculptory.core.generate.SparseUploadException;
import dev.sculptory.core.scatter.BlockVariants;
import dev.sculptory.core.scatter.FeatureCatalog;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.HudDraw;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolDescriptor;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.place.GhostBaker;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.LibraryFolder;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.client.session.ScatterPreviewResult;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.client.session.ToolResult;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.RejectReason;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.Executor;
import org.jetbrains.annotations.Nullable;

/**
 * Tool 9: scatters library assets and clipboards over painted terrain as one server edit.
 *
 * <p><b>Area.</b> LMB drag paints disc stamps along the cursor path (every half radius); Alt+LMB drag erases. Ctrl+Scroll
 * sets the radius (up to 64; Shift ×4). The Tool Settings window's Use selection takes the selection box instead.
 * Ctrl+Z undoes the last painted stroke while nothing has been committed and the server history has not moved since
 * (an edit with another tool, an undo); then it undoes the server history as usual, and the strokes painted before
 * stay out of its reach. Delete clears the area. See {@link PaintedArea} for the caps.
 *
 * <p><b>Mix and settings.</b> Variants come from the Library ({@link #addAsset}), the clipboard ({@link #addClipboard}),
 * a dropped {@code .schem}, or single blocks ({@link #addBlock}: the mix window's + Block, or middle-click on a block
 * in the world, which with this tool adds it to the mix instead of picking it); see {@link ScatterMix} and
 * {@link ScatterToolSettings}. Block variants go only where they can survive unless that setting is off. Vanilla trees
 * and features ({@link #addFeature}: the mix window's + Tree and + Feature) grow on the server, each spot seeded on
 * its own; their plan streams the grown cells, drawn as one ghost ({@link #grownGhost()}), which is exactly what the
 * commit writes. Alt+click (without dragging) places one item of the mix at the pointed spot at once
 * ({@link #placeOne}: a one-spot preview, committed when it comes back with its placement).
 *
 * <p><b>Preview.</b> {@value PreviewScheduler#DEBOUNCE_MILLIS} ms after the area, the mix or a setting changes, the
 * variants' previews are made sure of (the server must hold each asset) and a {@code ScatterPreview} is sent; only the
 * latest one counts. The plan's placements are drawn by {@link ScatterGhosts} within {@link ScatterLod}'s caps (ghosts
 * nearest the camera, then footprint outlines, then dots), and the HUD sums it up ({@link ScatterSummary}). A plan goes
 * out of date after the server's time limit or when blocks around its placements change ({@link PlanStaleness}); R
 * previews again. Refusals are shown in the HUD; nothing is retried on its own.
 *
 * <p><b>Commit.</b> Enter commits the plan ({@code RunOp(ScatterCommit)}): one job and one history entry, followed in
 * the job bars; the session toasts the result with the placements skipped because their cells were no longer open.
 * The preview is then cleared, the area and settings kept, so a re-roll (the seed's Random button) is one click.
 *
 * <p><b>Esc</b> steps back: cancel the stroke being painted, then clear the preview, then clear the area.
 */
public final class ScatterTool implements Tool {
    public static final ToolDescriptor DESCRIPTOR =
            new ToolDescriptor(ToolId.SCATTER, "sculptory.tool.scatter", "minecraft:oak_sapling", Perm.SCATTER);
    public static final String LEFT_DRAG = "LMB drag";
    public static final String LEFT_CLICK = "LMB";

    static final int AREA_TINT = 0x3857D068;
    static final int AREA_EDGE = 0xFF57D068;
    static final int PAINT_RING = 0xFF57D068;
    static final int ERASE_RING = 0xFFE5655D;
    static final int FOOTPRINT = 0xB0FFD27F;
    static final int DOT = 0xE0FFFFFF;
    /** Outlines of underwater placements, drawn through the water surface (which hides their ghosts from above). */
    static final int UNDERWATER = 0xC060C8FF;
    static final int HUD_TEXT = 0xFFFFFFFF;
    static final int HUD_DIM = 0xFFB8C0C8;
    static final int HUD_WARN = 0xFFE0B040;
    static final int HUD_ERROR = 0xFFE5655D;
    static final int HUD_BACKGROUND = 0xA0101216;
    /** The painted area is tinted this far around the camera, and outlined this far. */
    static final int TINT_RADIUS = 48;
    static final int EDGE_RADIUS = 128;
    static final int MAX_EDGES = 16_384;
    static final int RING_SEGMENTS = 64;
    /** The draw plan is made again when the camera moved this far (or the plan, a preview or the caps changed). */
    static final double LOD_MOVE = 4.0;
    static final long TUNE_INTERVAL_NANOS = 250_000_000L;

    /** Changes in a watched box since a plan arrived. */
    public interface ChangeWatch extends AutoCloseable {
        long changes();

        @Override
        void close();

        ChangeWatch NONE = new ChangeWatch() {
            @Override
            public long changes() {
                return 0;
            }

            @Override
            public void close() {}
        };
    }

    /** What the editor supplies beyond {@link ToolContext}. */
    public interface Services {
        /** The camera position of the last rendered frame, as {x, y, z}. */
        Optional<double[]> eye();

        /** Shows these ghost previews from now on (an empty list shows none). */
        void showGhosts(List<GhostPlacement> placements);

        /** What the renderer reported for the last frame's ghosts ("Preview simplified: ..."), or "". */
        String ghostStatus();

        /** CPU time the last frame's ghost pass took. */
        long ghostRenderNanos();

        /** Frees a volume's meshes now (a baked volume that is no longer shown). */
        void releaseGhost(GhostVolume volume);

        /** Where ghost volumes are baked, off the render thread. */
        Executor background();

        String keyLabel(KeyAction action);

        /** The keymap action a scroll with {@code modifiers} triggers, if any. */
        Optional<KeyAction> scrollAction(int modifiers);

        Translator translator();

        /** Changes whenever client blocks may have changed. */
        long changeStamp();

        /** Counts block changes inside {@code box} from now on. */
        ChangeWatch watchChanges(Box box);

        /** Asks the player to confirm a large edit; {@code onConfirm} runs only if they do. */
        void confirm(String opNameKey, long blocks, Runnable onConfirm);

        /** False while the game window does not have focus: a stroke in progress ends. */
        boolean windowFocused();

        /** Shows a toast (also while the tool is not active, e.g. Library's Add to scatter). */
        void notify(Notice notice);

        /** A block's display name ("Pink Petals"), for the mix; the id without the game's names. */
        default String blockName(BlockDescriptor block) {
            return block.block().value();
        }

        /** Services without Minecraft: no camera or ghosts, default keys, bakes on the caller's thread. */
        static Services headless() {
            EditorKeymap keymap = EditorKeymap.defaults();
            return new Services() {
                @Override
                public Optional<double[]> eye() {
                    return Optional.empty();
                }

                @Override
                public void showGhosts(List<GhostPlacement> placements) {}

                @Override
                public String ghostStatus() {
                    return "";
                }

                @Override
                public long ghostRenderNanos() {
                    return 0;
                }

                @Override
                public void releaseGhost(GhostVolume volume) {}

                @Override
                public Executor background() {
                    return Runnable::run;
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
                    return Translator.KEYS;
                }

                @Override
                public long changeStamp() {
                    return 0;
                }

                @Override
                public ChangeWatch watchChanges(Box box) {
                    return ChangeWatch.NONE;
                }

                @Override
                public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
                    onConfirm.run();
                }

                @Override
                public boolean windowFocused() {
                    return true;
                }

                @Override
                public void notify(Notice notice) {}
            };
        }
    }

    /** One line of the HUD block. */
    public record HudLine(String text, int color) {}

    /** Why no plan is shown: a refusal or failure of the last preview. */
    private record Problem(String key, List<String> args) {}

    private final Services services;
    private final ScatterToolSettings settings = new ScatterToolSettings();
    private final PaintedArea area = new PaintedArea();
    private final ScatterMix mix = new ScatterMix();
    private final StampPainter painter = new StampPainter();
    private final PreviewScheduler scheduler = new PreviewScheduler();
    private final VariantPreviews previews = new VariantPreviews();
    private final ScatterGhosts ghosts;
    private final SurfaceHeights heights = new SurfaceHeights();

    private ToolContext context;
    private long now;
    private WorldCursor cursor = WorldCursor.miss(0, 0, 0);

    // The press being painted
    private boolean erasing;
    private boolean warnedFull;
    private boolean warnedWide;
    /** Ctrl+Z undoes painted strokes until the next commit, or until the server history moves ({@link #undoMark}). */
    private boolean localUndo;
    /** The server history's {@code version()} when the undoable strokes were painted. */
    private long undoMark;

    // The preview
    /** Bumped for every preview sent and whenever the preview is cleared: answers to older ones are ignored. */
    private int serial;
    private boolean planning;
    private ScatterRequestBuilder.Missing missing;
    private Problem problem;
    private boolean assetsReloaded;
    private @Nullable ScatterPreviewResult plan;
    private List<ScatterMix.Variant> planVariants = List.of();
    /** {@link #underwaterVariants}, for the plan variants (by identity) and previews version it was worked out for. */
    private boolean[] underwater;
    private List<ScatterMix.Variant> underwaterFor;
    private int underwaterVersion;
    private long planReceivedAt;
    private ChangeWatch watch = ChangeWatch.NONE;
    private long watchBaseline;
    private int previewVersion = -1;

    // Drawing the plan
    private ScatterLod.Caps caps = ScatterLod.DEFAULT;
    private @Nullable ScatterLod.Plan lod;
    private double[] lodCamera;
    private long tunedAt;
    private final List<Integer> ghostIndices = new ArrayList<>();

    // The grown cells of the plan shown (trees and features)
    /** The ghost of the grown cells, once built; where it goes; how many plan placements it stands for. */
    private @Nullable GhostVolume grownGhost;
    private BlockPos grownOrigin = BlockPos.ORIGIN;
    private int grownPlacements;
    /** The grown ghost being built off the render thread, for the plan shown. */
    private @Nullable CompletableFuture<GrownBuilt> grownBuild;
    /** Builds given up before they landed, whose volumes are released when they do (on the render thread). */
    private final List<CompletableFuture<GrownBuilt>> abandonedBuilds = new ArrayList<>();
    /** Per plan variant, whether it is a tree or feature. */
    private boolean[] featureVariants = new boolean[0];

    // Alt+click: one item at the pointed spot
    /** An Alt press that has not moved yet: a click places one, a drag erases. */
    private boolean altPressed;
    private WorldCursor altPress = WorldCursor.miss(0, 0, 0);
    /** Bumped for every one-spot preview sent; its answer is ignored when another was sent (or cleared) since. */
    private int oneSerial;
    private boolean placingOne;

    public ScatterTool(Services services) {
        this.services = Objects.requireNonNull(services);
        this.ghosts = new ScatterGhosts(services.background(), services::releaseGhost);
    }

    // ---- Tool ----

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public SettingsSchema schema() {
        return settings.schema();
    }

    public ScatterToolSettings settings() {
        return settings;
    }

    @Override
    public RaycastMode raycastMode(SettingsValues s) {
        return RaycastMode.TERRAIN;
    }

    @Override
    public void activate(ToolContext c) {
        context = c;
    }

    @Override
    public void deactivate(ToolContext c, DeactivateReason r) {
        if (painter.pressed()) endPress(c);
        altPressed = false;
        c.setPointerCapture(false);
        services.showGhosts(List.of());
        if (r == DeactivateReason.WORLD_CHANGED) {
            // The area and plan belong to the world just left.
            clearPreview();
            area.clear();
            previews.clear();
            heights.clear();
        }
        context = null;
    }

    // ---- The area and the mix (also used by the Tool Settings panel, the Library and dropped files) ----

    public PaintedArea area() {
        return area;
    }

    public ScatterMix mix() {
        return mix;
    }

    /** Library's Add to scatter: adds an asset by content hash, and toasts what happened. */
    public boolean addAsset(String path, String contentHash) {
        Optional<SourceRef> asset = LibraryFolder.asset(contentHash);
        if (asset.isEmpty()) {
            services.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.asset_not_indexed", path));
            return false;
        }
        return add(new ScatterSource.Held(asset.get()), path);
    }

    /** Adds the player's current clipboard (named {@code name}); toasts when there is none. */
    public boolean addClipboard(EditorSession session, String name) {
        Optional<ClipboardCache.Entry> current = session.clipboards().current();
        if (current.isEmpty()) {
            services.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.nothing_copied",
                    services.keyLabel(KeyAction.COPY)));
            return false;
        }
        return addClipboard(current.get(), name);
    }

    /** Adds a clipboard the server holds (a copy, a load or a dropped {@code .schem}). */
    public boolean addClipboard(ClipboardCache.Entry entry, String name) {
        return add(new ScatterSource.Held(new SourceRef.Clipboard(entry.clipboardId())), name);
    }

    /**
     * Adds one block as a variant (the mix window's + Block, a middle-click in the world): its exact state, the lower
     * half for a double-tall block and the top for a column plant, made waterlogged if it only lives under water (live
     * coral) and dry if it goes on water (lily pads). A water plant or a waterlogged state goes under water, a lily pad
     * on the water surface ({@link BlockVariants#medium}). Air, fluids and blocks that carry water without being water
     * plants (bubble columns) are refused with a toast, and so is any block for a player without {@code brush} or
     * {@code region} (the server's rule).
     */
    public boolean addBlock(BlockDescriptor block) {
        Objects.requireNonNull(block);
        EditorSession session = context == null ? null : sessionOrNull(context);
        if (session != null && !mayScatterBlocks(session.permissions())) {
            services.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.scatter_block_permission",
                    Perm.BRUSH.node(), Perm.REGION.node()));
            return false;
        }
        String text = block.format();
        StateSpace states = context == null ? null : statesOrNull(context);
        if (states != null) {
            try {
                text = states.format(BlockVariants.resolve(states, text));
            } catch (IllegalArgumentException refused) {
                services.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.scatter_block_refused",
                        services.blockName(block), refused.getMessage()));
                return false;
            }
        }
        return add(new ScatterSource.Block(text), services.blockName(BlockDescriptor.parse(text)));
    }

    /**
     * Whether these permissions allow plain blocks as variants: {@code brush} or {@code region} (the server's rule too;
     * those players can place any block already).
     */
    public static boolean mayScatterBlocks(Permissions permissions) {
        return permissions.has(Perm.BRUSH) || permissions.has(Perm.REGION);
    }

    /**
     * The mix window's + Tree and + Feature: adds a vanilla tree or feature ({@link FeatureCatalog}), grown by the server
     * at each spot. Like blocks, trees and features need {@code brush} or {@code region} (the server's rule too).
     */
    public boolean addFeature(FeatureCatalog.FeatureDef def) {
        Objects.requireNonNull(def);
        EditorSession session = context == null ? null : sessionOrNull(context);
        if (session != null && !mayScatterBlocks(session.permissions())) {
            services.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.scatter_feature_permission",
                    Perm.BRUSH.node(), Perm.REGION.node()));
            return false;
        }
        return add(new ScatterSource.Feature(def.id()), featureName(def.id()));
    }

    /** The name the mix shows for a tree or feature ("Big oak"); the id when the catalog does not list it. */
    public String featureName(String id) {
        return FeatureCatalog.find(id).map(def -> services.translator().translateOr(def.nameKey(), id)).orElse(id);
    }

    /**
     * A block variant's state text as the mix keeps it ({@link #addBlock}'s normal form), or empty when this game
     * can't scatter it: an unknown block, air, a fluid, one that carries water without being a water plant, or text
     * over the size cap. Before a world is joined the text is kept as it is.
     */
    public Optional<String> blockVariantText(String text) {
        if (text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > ScatterSource.MAX_STATE_BYTES) {
            return Optional.empty();
        }
        StateSpace states = context == null ? null : statesOrNull(context);
        if (states == null) {
            return Optional.of(text);
        }
        try {
            return Optional.of(states.format(BlockVariants.resolve(states, text)));
        } catch (IllegalArgumentException refused) {
            return Optional.empty();
        }
    }

    /** The name the mix shows for a block variant's state text. */
    public String blockVariantName(String state) {
        try {
            return services.blockName(BlockDescriptor.parse(state));
        } catch (IllegalArgumentException malformed) {
            return state;
        }
    }

    private boolean add(ScatterSource source, String name) {
        ScatterMix.AddResult result = mix.add(source, name);
        switch (result) {
            case ADDED -> services.notify(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.scatter_added", name,
                    Integer.toString(mix.size())));
            case REPLACED_CLIPBOARD -> services.notify(Notice.of(Notice.Level.INFO,
                    "sculptory.notice.scatter_clipboard_replaced", name));
            case DUPLICATE -> services.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.scatter_duplicate", name));
            case FULL -> services.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.scatter_mix_full",
                    Integer.toString(ScatterMix.MAX_VARIANTS)));
        }
        boolean added = result == ScatterMix.AddResult.ADDED || result == ScatterMix.AddResult.REPLACED_CLIPBOARD;
        if (added) changed();
        return added;
    }

    public void removeVariant(int index) {
        mix.remove(index);
        changed();
    }

    public void setWeight(int index, int weight) {
        if (mix.setWeight(index, weight)) changed();
    }

    /** Replaces the whole mix, without toasts (a preset: {@link ScatterMixPreset}). */
    public void replaceMix(List<ScatterMix.Variant> variants) {
        if (mix.variants().equals(variants)) return;
        mix.replaceAll(variants);
        changed();
    }

    /** Use selection: the selection box becomes the area. */
    public boolean useSelection() {
        ToolContext c = context;
        Optional<Box> selection = c == null ? Optional.empty() : c.selection();
        if (selection.isEmpty()) {
            services.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.select_first"));
            return false;
        }
        Box box = selection.get();
        if ((long) box.sizeX() * box.sizeZ() > ScatterArea.MAX_COLUMNS) {
            services.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.scatter_area_too_wide",
                    SessionNotices.count(ScatterArea.MAX_COLUMNS)));
            return false;
        }
        beginUndoStep(c);
        area.useBox(box);
        localUndo = true;
        changed();
        return true;
    }

    /** Delete, or the panel's Clear: forgets the area and its preview. */
    public void clearArea() {
        if (painter.pressed() && context != null) endPress(context);
        area.clear();
        clearPreview();
    }

    /** Where a variant's preview stands (for the panel). */
    public VariantPreviews.Status variantStatus(ScatterSource source) {
        return previews.status(source);
    }

    /**
     * Where a block variant's placements go (under water, on the water surface, on land), for the mix's row tag; empty
     * for clipboards, assets and blocks not known here.
     */
    public Optional<BlockVariants.Medium> variantMedium(ScatterSource source) {
        return previews.medium(source);
    }

    /** A variant's preview, once downloaded (for its dimensions). */
    public Optional<ClipboardCache.Preview> variantPreview(ScatterSource source) {
        return previews.preview(source);
    }

    /** Whether a variant can be planned now (see {@link ScatterRequestBuilder}). */
    boolean usable(EditorSession session, ScatterMix.Variant variant) {
        return switch (variant.source()) {
            case ScatterSource.Held held -> switch (held.ref()) {
                case SourceRef.Clipboard clipboard -> session.clipboards().get(clipboard.id()).isPresent();
                case SourceRef.Asset asset -> previews.status(held) == VariantPreviews.Status.READY;
            };
            // The server resolves the state; one this client does not know is left out (the rest still plans).
            case ScatterSource.Block block -> previews.status(block) != VariantPreviews.Status.FAILED;
            // Grown on the server; one this build does not list is left out.
            case ScatterSource.Feature feature -> FeatureCatalog.find(feature.id()).isPresent();
        };
    }

    /** Something the preview depends on changed: a preview is due after the debounce. */
    private void changed() {
        problem = null;
        assetsReloaded = false;
        scheduler.changed(now);
    }

    // ---- Frames ----

    @Override
    public void frame(ToolContext c, FrameInfo f) {
        now = f.nanoTime();
        cursor = f.cursor();
        if (painter.pressed() && !services.windowFocused()) endPress(c);
        EditorSession session = sessionOrNull(c);
        if (session != null) {
            previews.sync(session, statesOrNull(c), mix.variants());
            if (previews.version() != previewVersion) {
                previewVersion = previews.version();
                // A variant's preview arrived: its placements can now show ghosts.
                if (plan != null) {
                    ghosts.set(plan.placements(), planPreviews(), columnPreviews(planVariants));
                    lod = null;
                }
            }
            if (scheduler.due(now)) send(c, session);
        }
        pollGrown();
        heights.beginFrame(worldOrNull(c), services.changeStamp(), SurfaceHeights.FRAME_BUDGET);
        updateGhosts(c);
    }

    /** Sends the due preview, once every asset variant's preview is in. */
    private void send(ToolContext c, EditorSession session) {
        if (previews.loadingAssets() > 0) return; // due again next frame
        if (placingOne) return; // an Alt+click's one-spot preview is on its way: sending would replace it
        ScatterRequestBuilder.Result built = ScatterRequestBuilder.build(area, c.settings(), settings, mix.variants(),
                variant -> usable(session, variant));
        if (built instanceof ScatterRequestBuilder.Missing why) {
            // The plan shown no longer matches the area or the mix: it must not be committed.
            scheduler.cancel();
            missing = why;
            dropPlan();
            return;
        }
        ScatterRequestBuilder.Ready ready = (ScatterRequestBuilder.Ready) built;
        missing = null;
        problem = null;
        scheduler.sent(now);
        int sent = ++serial;
        planning = true;
        CompletionStage<Reply<ScatterPreviewResult>> answer = session.scatterPreview(ready.request());
        answer.thenAccept(reply -> answered(sent, ready.variants(), reply));
    }

    private void answered(int sent, List<ScatterMix.Variant> variants, Reply<ScatterPreviewResult> reply) {
        if (sent != serial) return; // replaced or cleared meanwhile
        planning = false;
        switch (reply) {
            case Reply.Ok<ScatterPreviewResult> ok -> show(ok.value(), variants);
            case Reply.Refused<ScatterPreviewResult> refused -> refused(refused.reason());
            case Reply.Failed<ScatterPreviewResult> failed -> {
                if (failed.failure() != Reply.Failure.CANCELLED) {
                    // The server replaced its plan when this preview arrived there: the one shown is gone too.
                    dropPlan();
                    problem = new Problem("sculptory.scatter.status.failed", List.of(refreshKey()));
                }
            }
        }
    }

    /**
     * The server refused the preview (its own notice already said why). An asset it no longer holds is refused as
     * {@code ASSET_NOT_LOADED}: every asset's preview is then asked for again once, and the preview sent when they are
     * in. Any other refusal ({@code INVALID} settings included) is shown, and nothing is downloaded again.
     */
    private void refused(RejectReason reason) {
        dropPlan();
        ToolContext c = context;
        EditorSession session = c == null ? null : sessionOrNull(c);
        boolean assets = mix.variants().stream().anyMatch(v -> ScatterMix.isAsset(v.source()));
        if (reason == RejectReason.ASSET_NOT_LOADED && assets && !assetsReloaded && session != null) {
            assetsReloaded = true;
            previews.reloadAssets(session);
            scheduler.refresh(now);
            return;
        }
        problem = switch (reason) {
            case AREA_BUSY -> new Problem("sculptory.scatter.status.refused.area_busy", List.of(refreshKey()));
            case RATE_LIMITED -> new Problem("sculptory.scatter.status.refused.rate_limited", List.of(refreshKey()));
            default -> new Problem("sculptory.scatter.status.refused",
                    List.of(services.translator().translate(SessionNotices.reasonKey(reason))));
        };
    }

    /**
     * Shows a new plan in place of the old one. The ghost geometry is replaced, not cleared, so the baked volumes of
     * variants in both plans (a re-roll) are kept, and with them their meshes.
     */
    private void show(ScatterPreviewResult result, List<ScatterMix.Variant> variants) {
        // Everything that can fail comes first, so a plan that cannot be shown is dropped whole, never half shown (the
        // session already refuses plans outside the world; this is the second line).
        List<ScatterMix.Variant> nextVariants = List.copyOf(variants);
        ChangeWatch nextWatch = ChangeWatch.NONE;
        try {
            Optional<Box> bounds = result.boundsIfAny();
            if (bounds.isPresent()) nextWatch = services.watchChanges(grow(bounds.get()));
            ghosts.set(result.placements(), previewsOf(nextVariants), columnPreviews(nextVariants));
        } catch (RuntimeException impossible) {
            nextWatch.close();
            dropPlan();
            problem = new Problem("sculptory.scatter.status.failed", List.of(refreshKey()));
            return;
        }
        watch.close();
        watch = nextWatch;
        watchBaseline = watch.changes();
        plan = result;
        planVariants = nextVariants;
        planReceivedAt = now;
        lod = null;
        ghostIndices.clear();
        problem = null;
        showGrown(result, nextVariants);
    }

    /** What building the grown ghost gave: the volume and where it goes, or {@code null} when nothing can be drawn. */
    private record GrownBuilt(ScatterPreviewResult plan, GhostVolume volume, BlockPos origin) {}

    /**
     * The grown cells of a new plan: decoded ({@code SparseUpload.decodePreview}, with this game's blocks) and baked into
     * one ghost volume off the render thread; until then, and when the cells are too many or cannot be read, the trees
     * and features show as dots. The old plan's grown ghost is released.
     */
    private void showGrown(ScatterPreviewResult result, List<ScatterMix.Variant> variants) {
        releaseGrown();
        featureVariants = new boolean[variants.size()];
        for (int v = 0; v < featureVariants.length; v++) {
            featureVariants[v] = variants.get(v).source() instanceof ScatterSource.Feature;
        }
        grownPlacements = 0;
        for (dev.sculptory.core.scatter.ScatterPlan.Placement p : result.placements()) {
            if (isFeature(p.variant())) grownPlacements++;
        }
        byte[] payload = result.grownPayload();
        StateSpace states = context == null ? null : statesOrNull(context);
        if (payload == null || states == null) return;
        grownBuild = CompletableFuture.supplyAsync(() -> {
            try {
                GeneratedSource cells = SparseUpload.decodePreview(payload, states, MAX_GROWN_GHOST_CELLS,
                        MAX_GROWN_GHOST_SECTIONS);
                return new GrownBuilt(result, GhostBaker.fromSparse(cells, states), cells.bounds().min());
            } catch (SparseUploadException tooManyOrDamaged) {
                return null;
            }
        }, services.background());
    }

    /** The most grown cells drawn as a ghost (the server's default cap on what one plan may grow). */
    static final long MAX_GROWN_GHOST_CELLS = 1L << 20;
    static final int MAX_GROWN_GHOST_SECTIONS = 1 << 14;

    /** Takes the finished grown ghost if it still belongs to the plan shown. */
    private void pollGrown() {
        abandonedBuilds.removeIf(abandoned -> {
            if (!abandoned.isDone()) return false;
            GrownBuilt built = abandoned.isCompletedExceptionally() ? null : abandoned.getNow(null);
            if (built != null) services.releaseGhost(built.volume());
            return true;
        });
        CompletableFuture<GrownBuilt> running = grownBuild;
        if (running == null || !running.isDone()) return;
        grownBuild = null;
        GrownBuilt built;
        try {
            built = running.getNow(null);
        } catch (CompletionException | java.util.concurrent.CancellationException failed) {
            built = null;
        }
        if (built == null) return;
        if (plan != built.plan()) {
            services.releaseGhost(built.volume());
            return;
        }
        grownGhost = built.volume();
        grownOrigin = built.origin();
    }

    /**
     * Frees the grown ghost and gives up a build in flight: its volume is released on this thread once it lands
     * ({@link #pollGrown}).
     */
    private void releaseGrown() {
        if (grownGhost != null) services.releaseGhost(grownGhost);
        grownGhost = null;
        if (grownBuild != null) abandonedBuilds.add(grownBuild);
        grownBuild = null;
    }

    /** Whether plan variant {@code variant} is a tree or feature. */
    private boolean isFeature(int variant) {
        return variant >= 0 && variant < featureVariants.length && featureVariants[variant];
    }

    /** The ghost of the plan's grown cells (trees and features), once built. */
    public Optional<GhostVolume> grownGhost() {
        return Optional.ofNullable(grownGhost);
    }

    /** The plan's footprints plus the ground below them and a block around. */
    private static Box grow(Box box) {
        return new Box(box.min().offset(-1, -1, -1), box.max().offset(1, 1, 1));
    }

    private List<ClipboardCache.Preview> planPreviews() {
        return previewsOf(planVariants);
    }

    private List<ClipboardCache.Preview> previewsOf(List<ScatterMix.Variant> variants) {
        List<ClipboardCache.Preview> list = new ArrayList<>(variants.size());
        for (ScatterMix.Variant variant : variants) list.add(previews.preview(variant.source()).orElse(null));
        return list;
    }

    /** The previews of the column plants' taller columns among {@code variants} (built on first use). */
    private ScatterGhosts.ColumnPreviews columnPreviews(List<ScatterMix.Variant> variants) {
        return (variant, height) -> variant < variants.size()
                ? previews.columnPreview(variants.get(variant).source(), height).orElse(null) : null;
    }

    /**
     * Per plan variant, whether it goes under water (its outlines are drawn through the water surface); worked out again
     * only when the plan or the previews change.
     */
    private boolean[] underwaterVariants() {
        if (underwater != null && underwaterFor == planVariants && underwaterVersion == previews.version()) {
            return underwater;
        }
        underwaterFor = planVariants;
        underwaterVersion = previews.version();
        underwater = new boolean[planVariants.size()];
        for (int v = 0; v < underwater.length; v++) {
            underwater[v] = previews.medium(planVariants.get(v).source())
                    .map(medium -> medium == BlockVariants.Medium.UNDERWATER).orElse(false);
        }
        return underwater;
    }

    /** Forgets the plan shown (not the request in flight). */
    private void dropPlan() {
        plan = null;
        planVariants = List.of();
        releaseGrown();
        featureVariants = new boolean[0];
        grownPlacements = 0;
        watch.close();
        watch = ChangeWatch.NONE;
        ghosts.clear();
        lod = null;
        ghostIndices.clear();
        services.showGhosts(List.of());
    }

    /** Clears the preview: the plan shown, the request in flight (its answer is ignored) and any due change. */
    public void clearPreview() {
        serial++;
        // An Alt+click whose plan has not come back is cancelled too: its answer is ignored, nothing is committed.
        oneSerial++;
        placingOne = false;
        planning = false;
        problem = null;
        missing = null;
        scheduler.cancel();
        dropPlan();
    }

    // ---- Staleness ----

    /** Whether the plan shown still stands. */
    public PlanStaleness staleness() {
        if (plan == null) return PlanStaleness.FRESH;
        return PlanStaleness.of(now, planReceivedAt, EditorSession.SCATTER_PLAN_TTL_NANOS, watchBaseline, watch.changes());
    }

    // ---- Drawing the plan ----

    private void updateGhosts(ToolContext c) {
        if (plan == null || (ghosts.size() == 0 && grownGhost == null)) {
            services.showGhosts(List.of());
            return;
        }
        double[] camera = services.eye().orElse(null);
        if (camera == null) {
            camera = cursor.missed() ? new double[] {0, 0, 0} : new double[] {cursor.hitX(), cursor.hitY(), cursor.hitZ()};
        }
        if (now - tunedAt >= TUNE_INTERVAL_NANOS && lod != null) {
            tunedAt = now;
            int cap = ScatterLod.tune(caps.ghosts(), services.ghostRenderNanos(), lod.ghostsCapped(),
                    ScatterLod.DEFAULT.ghosts());
            if (cap != caps.ghosts()) {
                caps = caps.withGhosts(cap);
                lod = null;
            }
        }
        if (lod == null || lodCamera == null || distance(lodCamera, camera) >= LOD_MOVE) {
            lod = ScatterLod.plan(ghosts.centres(), ghosts.draws(), camera[0], camera[1], camera[2], caps);
            lodCamera = camera;
            ghostIndices.clear();
            for (int i = 0; i < ghosts.size(); i++) {
                if (lod.tier(i) == ScatterLod.Tier.GHOST) ghostIndices.add(i);
            }
        }
        ghosts.updateBakes(ghostIndices, statesOrNull(c));
        List<GhostPlacement> shown = new ArrayList<>(ghostIndices.size() + 1);
        for (int index : ghostIndices) {
            GhostPlacement ghost = ghosts.ghost(index);
            if (ghost != null) shown.add(ghost);
        }
        if (grownGhost != null) {
            shown.add(GhostPlacement.of(grownGhost, grownOrigin.x(), grownOrigin.y(), grownOrigin.z()));
        }
        services.showGhosts(shown);
    }

    private static double distance(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The last draw plan (for tests and the HUD). */
    public Optional<ScatterLod.Plan> drawPlan() {
        return Optional.ofNullable(lod);
    }

    /** The ghost caps in use (the ghost count adapts to the frame cost). */
    public ScatterLod.Caps caps() {
        return caps;
    }

    @Override
    public void renderWorld(ToolContext c, WorldDraw d) {
        renderArea(d);
        renderCursor(c, d);
        renderPlacements(d);
    }

    private void renderArea(WorldDraw d) {
        Optional<Box> box = area.box();
        if (box.isPresent()) {
            d.seeThrough(true);
            d.boxOutline(box.get(), AREA_EDGE);
            d.seeThrough(false);
            return;
        }
        PaintedArea.Mask mask = area.mask();
        if (mask.columns() == 0) return;
        double[] eye = services.eye().orElse(null);
        int cx = eye != null ? (int) Math.floor(eye[0]) : cursor.missed() ? (mask.x0 + mask.x1) / 2 : cursor.pos().x();
        int cz = eye != null ? (int) Math.floor(eye[2]) : cursor.missed() ? (mask.z0 + mask.z1) / 2 : cursor.pos().z();
        d.seeThrough(false);
        for (int z = Math.max(mask.z0, cz - TINT_RADIUS); z <= Math.min(mask.z1, cz + TINT_RADIUS); z++) {
            for (int x = Math.max(mask.x0, cx - TINT_RADIUS); x <= Math.min(mask.x1, cx + TINT_RADIUS); x++) {
                if (!mask.contains(x, z)) continue;
                int h = heights.height(x, z);
                if (h == SurfaceHeights.NONE || h == SurfaceHeights.UNKNOWN) continue;
                d.surfaceQuad(x, z, x + 1, z + 1, h + 1.02, AREA_TINT);
            }
        }
        int edges = 0;
        int lastZ = Math.min(mask.z1, cz + EDGE_RADIUS);
        for (int z = Math.max(mask.z0, cz - EDGE_RADIUS); z <= lastZ && edges < MAX_EDGES; z++) {
            for (int x = Math.max(mask.x0, cx - EDGE_RADIUS); x <= Math.min(mask.x1, cx + EDGE_RADIUS); x++) {
                if (!mask.contains(x, z)) continue;
                boolean west = !mask.contains(x - 1, z), east = !mask.contains(x + 1, z);
                boolean north = !mask.contains(x, z - 1), south = !mask.contains(x, z + 1);
                if (!west && !east && !north && !south) continue;
                int h = heights.height(x, z);
                if (h == SurfaceHeights.NONE || h == SurfaceHeights.UNKNOWN) continue;
                double y = h + 1.05;
                if (west) d.line(x, y, z, x, y, z + 1, AREA_EDGE);
                if (east) d.line(x + 1, y, z, x + 1, y, z + 1, AREA_EDGE);
                if (north) d.line(x, y, z, x + 1, y, z, AREA_EDGE);
                if (south) d.line(x, y, z + 1, x + 1, y, z + 1, AREA_EDGE);
                edges++;
            }
        }
    }

    /** A terrain-following ring of the painting radius; red while erasing. */
    private void renderCursor(ToolContext c, WorldDraw d) {
        if (cursor.missed()) return;
        int radius = radius(c.settings());
        boolean erase = painter.pressed() ? erasing : Modifiers.alt(c.modifiers());
        int color = erase ? ERASE_RING : PAINT_RING;
        double cx = cursor.pos().x() + 0.5, cz = cursor.pos().z() + 0.5, ring = radius + 0.5;
        double fallback = cursor.pos().y() + 1.05;
        double px = 0, py = 0, pz = 0;
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double angle = 2 * Math.PI * (i % RING_SEGMENTS) / RING_SEGMENTS;
            double x = cx + Math.cos(angle) * ring, z = cz + Math.sin(angle) * ring;
            // The column just inside the ring, so the ring hugs the disc's edge.
            int h = heights.height((int) Math.floor(cx + Math.cos(angle) * radius),
                    (int) Math.floor(cz + Math.sin(angle) * radius));
            double y = h == SurfaceHeights.NONE || h == SurfaceHeights.UNKNOWN ? fallback : h + 1.05;
            if (i > 0) d.line(px, py, pz, x, y, z, color);
            px = x;
            py = y;
            pz = z;
        }
    }

    /**
     * Footprint outlines and dots for the placements that are not ghosts (or whose ghost could not be baked). An
     * underwater placement's outline and dot are drawn see-through, and a ghost one gets a see-through outline too:
     * the water surface hides what lies below it from above (vanilla's water writes depth), ghosts included.
     */
    private void renderPlacements(WorldDraw d) {
        ScatterLod.Plan draw = lod;
        if (plan == null || draw == null || draw.tiers().length != ghosts.size()) return;
        boolean[] underwater = underwaterVariants();
        for (int i = 0; i < ghosts.size(); i++) {
            ScatterLod.Tier tier = draw.tier(i);
            if (tier == ScatterLod.Tier.GHOST && ghosts.bakeFailed(i)) tier = ScatterLod.Tier.BOX;
            int variant = ghosts.placements().get(i).variant();
            if (grownGhost != null && isFeature(variant)) continue; // drawn by the grown ghost
            boolean wet = variant >= 0 && variant < underwater.length && underwater[variant];
            if (tier == ScatterLod.Tier.GHOST) {
                Box footprint = wet ? ghosts.footprint(i) : null;
                if (footprint != null) {
                    d.seeThrough(true);
                    d.boxOutline(footprint, UNDERWATER);
                    d.seeThrough(false);
                }
                continue;
            }
            d.seeThrough(wet);
            if (tier == ScatterLod.Tier.BOX) {
                Box footprint = ghosts.footprint(i);
                if (footprint != null) {
                    d.boxOutline(footprint, wet ? UNDERWATER : FOOTPRINT);
                    d.seeThrough(false);
                    continue;
                }
            }
            if (tier == ScatterLod.Tier.BOX || tier == ScatterLod.Tier.DOT) {
                BlockPos anchor = ghosts.anchor(i);
                d.line(anchor.x() + 0.5, anchor.y(), anchor.z() + 0.5, anchor.x() + 0.5, anchor.y() + 1.5,
                        anchor.z() + 0.5, wet ? UNDERWATER : DOT);
            }
            d.seeThrough(false);
        }
    }

    // ---- Pointer and scroll ----

    @Override
    public boolean onPointer(ToolContext c, PointerEvent e) {
        switch (e.kind()) {
            case PRESS -> {
                if (e.button() != PointerEvent.LEFT) return false;
                cursor = e.cursor();
                if (Modifiers.alt(e.modifiers())) {
                    // A click places one item, a drag erases: decided when the pointer moves or lets go.
                    altPressed = true;
                    altPress = e.cursor();
                    c.setPointerCapture(true);
                    return true;
                }
                beginPress(c, false);
                return true;
            }
            case DRAG -> {
                if (e.button() != PointerEvent.LEFT) return false;
                if (altPressed) {
                    if (sameColumn(altPress, e.cursor())) return true;
                    altPressed = false;
                    cursor = altPress;
                    beginPress(c, true);
                }
                if (!painter.pressed()) return false;
                cursor = e.cursor();
                paintAtCursor(c);
                return true;
            }
            case RELEASE -> {
                if (e.button() != PointerEvent.LEFT) return false;
                if (altPressed) {
                    altPressed = false;
                    c.setPointerCapture(false);
                    placeOne(c, altPress);
                    return true;
                }
                if (!painter.pressed()) return false;
                endPress(c);
                return true;
            }
            case MOVE -> {
                cursor = e.cursor();
                return false;
            }
        }
        return false;
    }

    /** Whether two cursors point at the same column (both off the terrain counts as the same). */
    private static boolean sameColumn(WorldCursor a, WorldCursor b) {
        if (a.missed() || b.missed()) return a.missed() == b.missed();
        return a.pos().x() == b.pos().x() && a.pos().z() == b.pos().z();
    }

    private void beginPress(ToolContext c, boolean erase) {
        if (area.leaveBox()) changed(); // painting takes the area back from the selection box
        erasing = erase;
        warnedFull = false;
        warnedWide = false;
        beginUndoStep(c);
        area.beginGroup();
        c.setPointerCapture(true);
        painter.press();
        paintAtCursor(c);
    }

    /** Stamps along the path to the cursor (nothing while it is off the terrain). */
    private void paintAtCursor(ToolContext c) {
        if (cursor.missed()) return;
        for (int[] centre : painter.moveTo(cursor.hitX(), cursor.hitZ(), StampPainter.spacing(radius(c.settings())))) {
            stamp(c, centre[0], centre[1]);
        }
    }

    private void stamp(ToolContext c, int x, int z) {
        ScatterArea.Stamp stamp;
        try {
            stamp = new ScatterArea.Stamp(x, z, radius(c.settings()), erasing);
        } catch (IllegalArgumentException outsideTheWorld) {
            return;
        }
        switch (area.add(stamp)) {
            case ADDED -> {
                localUndo = true;
                changed();
            }
            case FULL -> {
                if (!warnedFull) {
                    warnedFull = true;
                    c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.scatter_area_full",
                            Integer.toString(PaintedArea.MAX_STAMPS)));
                }
            }
            case TOO_WIDE -> {
                if (!warnedWide) {
                    warnedWide = true;
                    c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.scatter_area_too_wide",
                            SessionNotices.count(ScatterArea.MAX_COLUMNS)));
                }
            }
            case SKIPPED -> { }
        }
    }

    private void endPress(ToolContext c) {
        area.endGroup();
        painter.release();
        c.setPointerCapture(false);
    }

    /**
     * Before a stroke or Use selection (a new local undo step): if the server history moved since the undoable steps
     * were made (an edit with another tool, an undo, a job finishing), those steps are older than it and are sealed,
     * so Ctrl+Z never skips over the newer server entry.
     */
    private void beginUndoStep(ToolContext c) {
        long version = historyVersion(c);
        if (version != undoMark) {
            area.seal();
            undoMark = version;
        }
    }

    /** Whether Ctrl+Z still undoes painted steps: none committed, and the server history unchanged since. */
    private boolean localUndoAvailable(ToolContext c) {
        return localUndo && historyVersion(c) == undoMark && area.undoable();
    }

    private static long historyVersion(ToolContext c) {
        EditorSession session = c == null ? null : sessionOrNull(c);
        return session == null ? 0 : session.history().version();
    }

    @Override
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        if (e.amount() == 0 || services.scrollAction(e.modifiers()).orElse(null) != KeyAction.TOOL_SIZE) return false;
        int step = (e.amount() > 0 ? 1 : -1) * (Modifiers.shift(e.modifiers()) ? 4 : 1);
        SettingsValues values = c.settings();
        int radius = Math.max(1, Math.min(ScatterArea.MAX_RADIUS, values.get(settings.radius) + step));
        c.updateSettings(values.with(settings.radius, radius));
        return true;
    }

    private int radius(SettingsValues values) {
        return Math.max(0, Math.min(ScatterArea.MAX_RADIUS, values.get(settings.radius)));
    }

    @Override
    public void onSettingsChanged(ToolContext c, SettingsValues before, SettingsValues after) {
        if (settings.affectsPlan(before, after)) changed();
    }

    // ---- Keys ----

    @Override
    public boolean onAction(ToolContext c, EditorAction a) {
        switch (a) {
            case COMMIT -> {
                commit(c);
                return true;
            }
            case CANCEL -> {
                return escape(c);
            }
            case ERASE_SELECTION -> {
                if (area.isEmpty() && area.stampCount() == 0 && plan == null && !planning) return false;
                clearArea();
                return true;
            }
            case UNDO -> {
                if (painter.pressed()) return false;
                if (!localUndoAvailable(c)) {
                    // Ctrl+Z is the server history's now: the painted steps stay, out of undo's reach.
                    area.seal();
                    localUndo = false;
                    return false;
                }
                if (!area.undoGroup()) return false;
                if (area.isEmpty()) {
                    clearPreview();
                } else {
                    changed();
                }
                return true;
            }
            case ROTATE_CW, ROTATE_CCW -> {
                refresh(c);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Esc: cancel the stroke, then clear the preview, then clear the area. */
    private boolean escape(ToolContext c) {
        if (altPressed) {
            altPressed = false;
            c.setPointerCapture(false);
            return true;
        }
        if (painter.pressed()) {
            boolean had = area.cancelGroup();
            painter.release();
            c.setPointerCapture(false);
            if (had) changed();
            return true;
        }
        if (plan != null || planning || placingOne || scheduler.waiting() || problem != null) {
            boolean one = placingOne;
            clearPreview();
            if (one && !area.isEmpty()) scheduler.refresh(now); // the painted area is previewed again
            return true;
        }
        if (!area.isEmpty() || area.stampCount() > 0) {
            clearArea();
            return true;
        }
        return false;
    }

    /** R: asks for a fresh preview now (retrying variants whose previews failed). */
    private void refresh(ToolContext c) {
        EditorSession session = sessionOrNull(c);
        if (session != null) previews.retryFailed(session, statesOrNull(c));
        problem = null;
        assetsReloaded = false;
        scheduler.refresh(now);
    }

    // ---- Alt+click: one item ----

    /**
     * Alt+click: places one item of the mix at the pointed column right away, as a scatter of one: a one-spot preview (a
     * stamp of radius 0, a target count of 1, a fresh seed, the settings and filters as they are) that is committed as
     * soon as it comes back with its placement; when nothing fits there, a toast says why. Esc before the plan comes back cancels it. The server keeps one plan per
     * player, so this replaces the plan shown; the painted area is previewed again afterwards.
     */
    void placeOne(ToolContext c, WorldCursor at) {
        EditorSession session = sessionOrNull(c);
        if (session == null) return;
        if (at.missed()) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.scatter_one_miss"));
            return;
        }
        if (!session.permissions().has(Perm.SCATTER)) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.needs_permission", Perm.SCATTER.node()));
            return;
        }
        if (mix.isEmpty()) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.scatter_add_variant"));
            return;
        }
        if (previews.loadingAssets() > 0) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.scatter_planning"));
            return;
        }
        ScatterArea one;
        try {
            one = new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(at.pos().x(), at.pos().z(), 0)));
        } catch (IllegalArgumentException outsideTheWorld) {
            return;
        }
        ScatterRequestBuilder.Result built = ScatterRequestBuilder.buildOne(one, ThreadLocalRandom.current().nextLong(),
                c.settings(), settings, mix.variants(), variant -> usable(session, variant));
        if (!(built instanceof ScatterRequestBuilder.Ready ready)) {
            c.notify(Notice.of(Notice.Level.INFO, ((ScatterRequestBuilder.Missing) built).reasonKey()));
            return;
        }
        // The server replaces the plan shown with this one: it is gone here too, and the area is previewed again.
        clearPreview();
        placingOne = true;
        int sent = ++oneSerial;
        session.scatterPreview(ready.request()).thenAccept(reply -> placed(c, session, sent, ready.variants(), reply));
    }

    private void placed(ToolContext c, EditorSession session, int sent, List<ScatterMix.Variant> variants,
                        Reply<ScatterPreviewResult> reply) {
        if (sent != oneSerial) return;
        placingOne = false;
        switch (reply) {
            case Reply.Ok<ScatterPreviewResult> ok -> {
                ScatterPreviewResult one = ok.value();
                if (one.placements().size() == 1) {
                    session.scatterCommit(one.planId()).thenAccept(result -> {
                        if (result instanceof ToolResult.Rejected rejected) {
                            c.notify(commitRefusal(rejected.reason(), session));
                        }
                    });
                    area.seal();
                    localUndo = false;
                } else {
                    String why = ScatterSummary.skipped(services.translator(), one.rejectedCounts());
                    c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.scatter_one_nothing",
                            why.isEmpty() ? "-" : why));
                }
            }
            case Reply.Refused<ScatterPreviewResult> refused -> { } // the server's notice said why
            case Reply.Failed<ScatterPreviewResult> failed -> {
                if (failed.failure() != Reply.Failure.CANCELLED) {
                    c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.scatter_one_failed"));
                }
            }
        }
        if (!area.isEmpty() && !mix.isEmpty()) scheduler.refresh(now);
    }

    /** Whether an Alt+click's one-spot preview is on its way. */
    public boolean placingOne() {
        return placingOne;
    }

    // ---- Commit ----

    /** Enter: commits the plan shown. */
    void commit(ToolContext c) {
        ScatterPreviewResult shown = plan;
        if (planning || scheduler.waiting()) {
            // The plan shown (if any) is being replaced: the server drops it as soon as the new preview arrives.
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.scatter_planning"));
            return;
        }
        if (shown == null) {
            if (area.isEmpty()) {
                c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.scatter_paint_first"));
            } else if (mix.isEmpty()) {
                c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.scatter_add_variant"));
            } else {
                c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.scatter_no_preview", refreshKey()));
            }
            return;
        }
        if (staleness() == PlanStaleness.EXPIRED) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.scatter_expired"));
            dropPlan();
            refresh(c);
            return;
        }
        if (shown.placements().isEmpty()) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.scatter_nothing"));
            return;
        }
        EditorSession session = sessionOrNull(c);
        if (session == null) return;
        Permissions permissions = session.permissions();
        if (!permissions.has(Perm.SCATTER)) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.needs_permission", Perm.SCATTER.node()));
            return;
        }
        long blocks = shown.totalCells();
        long limit = permissions.limits().maxOpVolume();
        if (blocks > limit && !permissions.has(Perm.LIMIT_BYPASS)) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large", SessionNotices.count(blocks),
                    SessionNotices.count(limit)));
            return;
        }
        UUID planId = shown.planId();
        Runnable send = () -> sendCommit(c, session, planId);
        if (blocks > SelectionActions.CONFIRM_VOLUME) {
            services.confirm("sculptory.op.scatter", blocks, send);
        } else {
            send.run();
        }
    }

    private void sendCommit(ToolContext c, EditorSession session, UUID planId) {
        if (plan == null || !plan.planId().equals(planId)) return; // replaced while the player confirmed
        session.scatterCommit(planId).thenAccept(result -> {
            if (result instanceof ToolResult.Rejected rejected) c.notify(commitRefusal(rejected.reason(), session));
        });
        // The plan is spent: clear the preview, keep the area and the settings for the next roll. Ctrl+Z now undoes
        // the scatter on the server, and later strokes never reach back to the ones painted before it.
        clearPreview();
        area.seal();
        localUndo = false;
    }

    private Notice commitRefusal(RejectReason reason, EditorSession session) {
        return switch (reason) {
            case INVALID -> Notice.of(Notice.Level.WARNING, "sculptory.notice.scatter_commit_invalid", refreshKey());
            case NO_PERMISSION -> Notice.of(Notice.Level.WARNING, "sculptory.notice.scatter_commit_no_permission");
            default -> SessionNotices.rejection(reason, SessionNotices.Subject.EDIT, session.permissions().limits());
        };
    }

    // ---- HUD ----

    @Override
    public void renderHud(ToolContext c, HudDraw d) {
        List<HudLine> lines = hudLines();
        if (lines.isEmpty()) return;
        int lineHeight = 11;
        int width = 0;
        for (HudLine line : lines) width = Math.max(width, d.textWidth(line.text()));
        int top = 26;
        int left = (d.width() - width) / 2;
        d.fill(left - 4, top - 2, left + width + 4, top + lines.size() * lineHeight, HUD_BACKGROUND);
        for (int i = 0; i < lines.size(); i++) {
            HudLine line = lines.get(i);
            d.text(line.text(), (d.width() - d.textWidth(line.text())) / 2, top + i * lineHeight, line.color());
        }
    }

    /** The HUD block: the plan's summary, then what the preview is doing or why it is missing. */
    public List<HudLine> hudLines() {
        Translator tr = services.translator();
        List<HudLine> lines = new ArrayList<>();
        ScatterPreviewResult shown = plan;
        if (shown != null) lines.add(new HudLine(ScatterSummary.line(tr, shown), HUD_TEXT));
        PlanStaleness staleness = staleness();
        if (problem != null) {
            lines.add(new HudLine(tr.translate(problem.key(), problem.args()), HUD_ERROR));
        } else if (staleness.stale()) {
            lines.add(new HudLine(tr.translate("sculptory.scatter.status.stale", refreshKey()), HUD_WARN));
        } else if (planning) {
            lines.add(new HudLine(tr.translate("sculptory.scatter.status.planning"), HUD_DIM));
        } else if (previews.loadingAssets() > 0 && scheduler.waiting()) {
            lines.add(new HudLine(tr.translate("sculptory.scatter.status.loading",
                    Integer.toString((int) Math.round(previews.progress() * 100))), HUD_DIM));
        } else if (missing != null && shown == null) {
            lines.add(new HudLine(tr.translate(missing.reasonKey()), HUD_DIM));
        }
        ScatterLod.Plan draw = lod;
        int grown = grownGhost != null ? grownPlacements : 0;
        if (shown != null && draw != null && draw.ghosts() < ghosts.size() - grown) {
            lines.add(new HudLine(tr.translate("sculptory.scatter.status.lod", SessionNotices.count(draw.ghosts()),
                    SessionNotices.count(Math.max(0, draw.boxes() + draw.dots() - grown))), HUD_DIM));
        }
        if (placingOne) lines.add(new HudLine(tr.translate("sculptory.scatter.status.placing_one"), HUD_DIM));
        String ghostStatus = services.ghostStatus();
        if (shown != null && !ghostStatus.isEmpty()) lines.add(new HudLine(ghostStatus, HUD_DIM));
        if (area.nearCap()) {
            lines.add(new HudLine(tr.translate("sculptory.scatter.status.stamps",
                    Integer.toString(area.stampCount()), Integer.toString(PaintedArea.MAX_STAMPS)), HUD_WARN));
        }
        return lines;
    }

    @Override
    public List<KeyHint> hints(ToolContext c) {
        List<KeyHint> hints = new ArrayList<>();
        if (painter.pressed()) {
            hints.add(new KeyHint("Esc", "sculptory.hint.scatter.cancel_stroke"));
            return hints;
        }
        hints.add(new KeyHint(LEFT_DRAG, "sculptory.hint.scatter.paint"));
        hints.add(new KeyHint("Alt+" + LEFT_DRAG, "sculptory.hint.scatter.erase"));
        hints.add(new KeyHint("Alt+" + LEFT_CLICK, "sculptory.hint.scatter.place_one"));
        hints.add(new KeyHint(services.keyLabel(KeyAction.EYEDROPPER), "sculptory.hint.scatter.add_block"));
        hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.scatter.radius",
                List.of(Integer.toString(radius(c.settings())))));
        if (plan != null && !plan.placements().isEmpty()) {
            hints.add(new KeyHint(services.keyLabel(KeyAction.COMMIT), "sculptory.hint.scatter.commit",
                    List.of(SessionNotices.count(plan.placements().size()))));
        }
        if (!area.isEmpty() && !mix.isEmpty()) {
            hints.add(new KeyHint(refreshKey(), "sculptory.hint.scatter.refresh"));
        }
        if (localUndoAvailable(c)) {
            hints.add(new KeyHint(services.keyLabel(KeyAction.UNDO), "sculptory.hint.scatter.undo"));
        }
        if (!area.isEmpty()) {
            hints.add(new KeyHint(services.keyLabel(KeyAction.ERASE_SELECTION), "sculptory.hint.scatter.clear"));
        }
        if (mix.isEmpty()) hints.add(KeyHint.text("sculptory.hint.scatter.add_variants"));
        return hints;
    }

    private String refreshKey() {
        return services.keyLabel(KeyAction.ROTATE_CW);
    }

    // ---- State for tests and the panel ----

    /** The plan shown, if any. */
    public Optional<ScatterPreviewResult> plan() {
        return Optional.ofNullable(plan);
    }

    /** The mix entries the plan's variant indices stand for. */
    public List<ScatterMix.Variant> planVariants() {
        return planVariants;
    }

    /** Whether a preview is being planned on the server. */
    public boolean planning() {
        return planning;
    }

    /** Whether a preview is waiting for its debounce (or for the variants' previews). */
    public boolean previewDue() {
        return scheduler.waiting();
    }

    /** Whether the painting press is in progress. */
    public boolean painting() {
        return painter.pressed();
    }

    /** The ghost geometry (for tests). */
    ScatterGhosts ghosts() {
        return ghosts;
    }

    /** Bumped when the variants' previews change (for the panel). */
    public int previewsVersion() {
        return previews.version();
    }

    // ---- Helpers ----

    private static EditorSession sessionOrNull(ToolContext c) {
        try {
            return c.session();
        } catch (IllegalStateException noSession) {
            return null;
        }
    }

    private static WorldReader worldOrNull(ToolContext c) {
        try {
            return c.world();
        } catch (IllegalStateException noWorld) {
            return null;
        }
    }

    private static StateSpace statesOrNull(ToolContext c) {
        try {
            return c.states();
        } catch (IllegalStateException noWorld) {
            return null;
        }
    }

}
