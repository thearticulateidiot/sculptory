package dev.sculptory.fabric.client.editor.tools.place;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.OpSymmetry;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntityPlacement;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostSection;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
import dev.sculptory.fabric.client.editor.tool.RegionWork;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolDescriptor;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.brush.BrushOutlines;
import dev.sculptory.fabric.client.editor.tools.brush.SymmetryCentre;
import dev.sculptory.fabric.client.editor.tools.select.Nudge;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.editor.tools.select.SelectionModel;
import dev.sculptory.fabric.client.editor.world.GizmoPick;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.client.session.Subscription;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.fabric.client.session.ToolResult;
import dev.sculptory.fabric.client.session.Transfer;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.net.PreviewPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.jetbrains.annotations.Nullable;

/**
 * Tool 8: places a ghost preview and commits it as a paste, move or stack.
 *
 * <p><b>Entry points</b> ({@link #request}): Ctrl+V pastes the clipboard; the Library's Place and a dropped
 * {@code .schem} paste an asset or upload; Move and Stack in the Selection window work on the selection. Choosing the
 * tool from the palette pastes the current clipboard, if there is one.
 *
 * <p><b>Placing.</b> The ghost follows the cursor, its bottom centre on the top face of the block under the cursor
 * (a TERRAIN raycast). Left-click drops it; left-click again (off the gizmo) picks it up. A dropped ghost shows the
 * gizmo: its arrows drag it along an axis in whole blocks, its ring turns it in quarter turns. R / Shift+R rotate,
 * F / Shift+F mirror across X / Z, V flips it upside down within its own height (a stack's copies too, which never turn or mirror), the arrow keys nudge it (camera-relative,
 * Shift ×10) and PgUp/PgDn move it up and down. Once a flipped ghost is baked, a toast counts the blocks that have no
 * upside-down form and stay as they are. In stack mode Ctrl+Scroll sets the number of copies (Shift ×10).
 * Enter commits, Esc cancels.
 *
 * <p><b>Ghost accuracy.</b> A transform change first only turns the ghost's model matrix (geometry). Once the
 * placement has kept the same transform for {@link #BAKE_IDLE_NANOS}, a copy of the volume with the block states
 * rotated and mirrored ({@link GhostBaker#bake}) is built off the render thread and shown instead, so stairs and logs
 * face the way the server will place them. With "Paste into" set to existing blocks or air only, each ghost is cut
 * down to the cells the server would write, judged by the client world a few sections per frame
 * ({@link GhostFilter}), so a moving ghost shows whole until it rests.
 */
public final class PlaceTool implements Tool {
    /**
     * The tool itself needs only {@code use}: a paste needs {@code clipboard} and a move or stack {@code region} (as on
     * the server), which {@link #commit} checks, so a player with either can use it.
     */
    public static final ToolDescriptor DESCRIPTOR =
            new ToolDescriptor(ToolId.PLACE, "sculptory.tool.place", "minecraft:chest", Perm.USE);
    /** How long a transform must stay unchanged before the ghost's block states are turned for real. */
    public static final long BAKE_IDLE_NANOS = 300_000_000L;
    /** Stack copies drawn as ghosts; the rest are outlines. */
    public static final int MAX_GHOST_COPIES = 16;
    public static final int TARGET_COLOUR = 0xFF7FD4FF;
    public static final int SOURCE_COLOUR = 0xFFFF4A4A;
    public static final int SOURCE_FILL = 0x33FF3030;
    /** Outline of a clipboard entity where the paste would place it. */
    public static final int ENTITY_COLOUR = 0xFFFFD166;
    /** Clipboard entities outlined per frame at most. */
    static final int MAX_ENTITY_OUTLINES = 512;
    /** Move and Stack selections over this many cells get an outline instead of a ghost (see {@link #setMaxCaptureCells}). */
    public static final long DEFAULT_MAX_CAPTURE_CELLS = 1L << 20;
    /** World sections a Move or Stack preview reads per frame. */
    public static final int CAPTURE_SECTIONS_PER_FRAME = 16;

    /** What starts a placement. */
    public sealed interface Request {
        /** Pastes a clipboard or library asset, starting with {@code transform} (carried from the Clipboard window). */
        record Paste(SourceRef source, String label, Transform transform) implements Request {
            public Paste {
                Objects.requireNonNull(source);
                Objects.requireNonNull(label);
                Objects.requireNonNull(transform);
            }
        }

        /** Moves the selection: a box, a shape or a cell set (only its cells move). */
        record Move(Region region) implements Request {
            public Move {
                Objects.requireNonNull(region);
            }

            public Move(Box box) {
                this(new Region.Cuboid(box));
            }
        }

        /** Stacks copies of the selection: a box, a shape or a cell set (only its cells repeat). */
        record Stack(Region region) implements Request {
            public Stack {
                Objects.requireNonNull(region);
            }

            public Stack(Box box) {
                this(new Region.Cuboid(box));
            }
        }
    }

    /** What the editor supplies beyond {@link ToolContext}. */
    public interface Services {
        /** The cursor ray of the event being handled (the crosshair ray while looking). */
        Optional<Ray> cursorRay();

        /** Projects world points into scaled GUI pixels, from the last rendered frame. */
        Optional<ScreenProjector> projector();

        /** The camera position of the last rendered frame, as {x, y, z}. */
        Optional<double[]> eye();

        float cameraYaw();

        /** Shows the gizmo at a world point, highlighting {@code hovered} (null for none). */
        void showGizmo(double x, double y, double z, @Nullable GizmoPick.Handle hovered);

        void clearGizmo();

        /** Shows these ghost previews from now on (an empty list shows none). */
        void showGhosts(List<GhostPlacement> placements);

        /** What the renderer reported for the last frame's ghosts ("Preview simplified: ..."), or "". */
        String ghostStatus();

        /** Frees a volume's meshes now (a baked volume that is no longer shown). */
        void releaseGhost(GhostVolume volume);

        String keyLabel(KeyAction action);

        /** Where ghost volumes are baked, off the render thread. */
        Executor background();

        /** Asks the player to confirm a large placement; {@code onConfirm} runs only if they do. */
        void confirm(String opNameKey, long blocks, Runnable onConfirm);

        /**
         * {@link #confirm(String, long, Runnable)} for a placement of {@code copies} symmetric copies ({@code blocks}
         * counts them all); the default leaves the copies out of the question.
         */
        default void confirm(String opNameKey, long blocks, int copies, Runnable onConfirm) {
            confirm(opNameKey, blocks, onConfirm);
        }

        /** A placement started from another tool ended (committed move or stack, or cancelled): go back to it. */
        void finished();

        /** Services without Minecraft: no camera, gizmo or ghosts, default key names, bakes on the caller's thread. */
        static Services headless() {
            return new Services() {
                @Override
                public Optional<Ray> cursorRay() {
                    return Optional.empty();
                }

                @Override
                public Optional<ScreenProjector> projector() {
                    return Optional.empty();
                }

                @Override
                public Optional<double[]> eye() {
                    return Optional.empty();
                }

                @Override
                public float cameraYaw() {
                    return 0;
                }

                @Override
                public void showGizmo(double x, double y, double z, @Nullable GizmoPick.Handle hovered) {}

                @Override
                public void clearGizmo() {}

                @Override
                public void showGhosts(List<GhostPlacement> placements) {}

                @Override
                public String ghostStatus() {
                    return "";
                }

                @Override
                public void releaseGhost(GhostVolume volume) {}

                @Override
                public String keyLabel(KeyAction action) {
                    return dev.sculptory.fabric.client.editor.input.EditorKeymap.defaults().display(action);
                }

                @Override
                public Executor background() {
                    return Runnable::run;
                }

                @Override
                public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
                    onConfirm.run();
                }

                @Override
                public void finished() {}
            };
        }
    }

    private final Services services;
    private final PlaceSettings settings;
    /** The symmetry centre shared with the brushes and the Select tool (M sets it). */
    private final SymmetryCentre symmetryCentre;
    private ToolContext context;
    /** The last frame's cursor (the symmetry centre key uses it). */
    private WorldCursor cursor = WorldCursor.miss(0, 0, 0);
    private Request pendingRequest;
    /** Bumped for every new placement, so late preview answers for an old one are ignored. */
    private int generation;

    private @Nullable Placement placement;
    /** A paste waiting for its preview before its size is known (library assets). */
    private @Nullable Request.Paste waitingFor;
    private String label = "";
    private @Nullable Transfer<ClipboardCache.Preview> previewTransfer;
    /** The entities of the paste's preview, outlined where they would land. */
    private List<PreviewPayload.Entity> entities = List.of();
    private @Nullable GhostVolume volume;
    private long sourceCells;
    private boolean following;
    private boolean positioned;
    private boolean returnWhenDone;
    /** The pasted clipboard was replaced or cleared; the placement ends at the next frame (or Enter). */
    private boolean clipboardGone;
    private @Nullable Subscription clipboardWatch;
    /** A paste of an asset was refused once and its preview asked for again (at most once per placement). */
    private boolean assetRetried;

    // Move and Stack previews: the selection is read a few sections per frame, then built off the render thread.
    private long maxCaptureCells = DEFAULT_MAX_CAPTURE_CELLS;
    private @Nullable GhostBaker.Capture capture;
    private @Nullable CompletableFuture<GhostVolume> captureBuild;
    private boolean outlineOnly;

    // Symmetric copies of a Move or Stack preview: the image regions' own world content, read like the original. A paste's copies reuse its volume.
    private final List<CopyPreview> copies = new ArrayList<>();
    /** The symmetry the copy previews were started for ({@code null}: none started). */
    private @Nullable Symmetry copiesFor;
    /** The copies' cells together are over the capture cap: outline boxes only. */
    private boolean copiesOutlineOnly;

    /** The ghosts cut down to what the "Paste into" setting would write. */
    private final GhostFilter ghostFilter;

    // Baked ghost (states turned for real) and the bake in progress.
    private @Nullable GhostVolume baked;
    private @Nullable Transform bakedFor;
    private @Nullable GhostVolume bakedFrom;
    private @Nullable CompletableFuture<GhostVolume> baking;
    private @Nullable Transform bakingFor;
    private @Nullable GhostVolume bakingFrom;
    /** What the bake in progress counts of a flipped volume, or null. */
    private @Nullable GhostBaker.FlipTally bakingTally;
    /** Whether the blocks the flip upside down keeps have had their toast since the flip last went on. */
    private boolean flipCounted;
    /** The (volume, transform) whose bake failed, shown as outlines only. */
    private @Nullable Transform bakeFailedFor;
    private @Nullable GhostVolume bakeFailedFrom;
    private long lastFrameNanos;
    private long transformChangedAt;

    // Gizmo
    private @Nullable GizmoPick.Handle hovered;
    private @Nullable GizmoPick.AxisDrag axisDrag;
    private boolean ringDrag;
    private double grabAngle;
    private Transform grabTransform = Transform.IDENTITY;
    private BlockPos grabPivot = BlockPos.ORIGIN;

    /** @param physicsAllowed whether the player may paste with physics (shows the setting) */
    public PlaceTool(Services services, java.util.function.BooleanSupplier physicsAllowed) {
        this(services, physicsAllowed, new SymmetryCentre());
    }

    /**
     * @param physicsAllowed whether the player may paste with physics (shows the setting)
     * @param symmetryCentre the symmetry centre placements use, shared with the brushes and the Select tool
     */
    public PlaceTool(Services services, java.util.function.BooleanSupplier physicsAllowed, SymmetryCentre symmetryCentre) {
        this.services = Objects.requireNonNull(services);
        this.settings = new PlaceSettings(physicsAllowed);
        this.symmetryCentre = Objects.requireNonNull(symmetryCentre);
        this.ghostFilter = new GhostFilter(services::releaseGhost);
    }

    /** The symmetry centre placements use. */
    public SymmetryCentre symmetryCentre() {
        return symmetryCentre;
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

    public PlaceSettings settings() {
        return settings;
    }

    @Override
    public RaycastMode raycastMode(SettingsValues s) {
        return RaycastMode.TERRAIN;
    }

    /** Move and Stack selections larger than this get an outline instead of a ghost. */
    public void setMaxCaptureCells(long cells) {
        this.maxCaptureCells = Math.max(0, cells);
    }

    @Override
    public void activate(ToolContext c) {
        context = c;
        clipboardWatch = c.session().clipboards().onChange(this::clipboardChanged);
        Request request = pendingRequest;
        pendingRequest = null;
        if (request != null) {
            start(request);
        } else if (placement == null && waitingFor == null) {
            // Chosen from the palette (or resumed without a placement): paste the clipboard, if there is one.
            c.session().clipboards().current().ifPresent(entry -> start(new Request.Paste(
                    new SourceRef.Clipboard(entry.clipboardId()), "clipboard", Transform.IDENTITY)));
            returnWhenDone = false;
        }
    }

    @Override
    public void deactivate(ToolContext c, DeactivateReason r) {
        endDrag(c, false);
        services.clearGizmo();
        services.showGhosts(List.of());
        if (clipboardWatch != null) {
            clipboardWatch.close();
            clipboardWatch = null;
        }
        if (r != DeactivateReason.SUSPENDED) {
            clear();
        }
        context = null;
    }

    /** The session's clipboard changed: a paste of the old clipboard can no longer run (its id is dead). */
    private void clipboardChanged() {
        ToolContext c = context;
        SourceRef source = placement != null ? placement.source() : waitingFor != null ? waitingFor.source() : null;
        if (c == null || !(source instanceof SourceRef.Clipboard clipboard)) return;
        if (c.session().clipboards().get(clipboard.id()).isEmpty()) clipboardGone = true;
    }

    /** Ends a paste whose clipboard was replaced; returns whether it did. */
    private boolean endIfClipboardGone(ToolContext c) {
        if (!clipboardGone) return false;
        clipboardGone = false;
        SourceRef source = placement != null ? placement.source() : waitingFor != null ? waitingFor.source() : null;
        if (!(source instanceof SourceRef.Clipboard clipboard) || c.session().clipboards().get(clipboard.id()).isPresent()) {
            return false;
        }
        c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.clipboard_changed", services.keyLabel(KeyAction.PASTE)));
        done();
        return true;
    }

    /**
     * Starts a placement. While the tool is active it starts now; otherwise it starts when the tool is next activated
     * (the editor activates it right after).
     */
    public void request(Request request) {
        Objects.requireNonNull(request);
        if (context != null) {
            start(request);
            returnWhenDone = true;
        } else {
            pendingRequest = request;
        }
    }

    /** Whether a placement (or a paste waiting for its preview) is in progress. */
    public boolean placing() {
        return placement != null || waitingFor != null;
    }

    /** The placement in progress, if its size is known. */
    public Optional<Placement> placement() {
        return Optional.ofNullable(placement);
    }

    /** Whether the ghost follows the cursor (false once dropped). */
    public boolean following() {
        return following;
    }

    /** The ghost volume shown (the source's, untransformed), if loaded. */
    public Optional<GhostVolume> sourceVolume() {
        return Optional.ofNullable(volume);
    }

    /** The baked volume (states turned for the current transform), once built. */
    public Optional<GhostVolume> bakedVolume() {
        return baked != null && volume == bakedFrom && placement != null && placement.transform().equals(bakedFor)
                ? Optional.of(baked) : Optional.empty();
    }

    private void start(Request request) {
        clear();
        generation++;
        returnWhenDone = true;
        ToolContext c = context;
        switch (request) {
            case Request.Paste paste -> startPaste(c, paste);
            case Request.Move move -> {
                placement = Placement.move(move.region());
                label = "selection";
                capture(c, move.region());
                following = false;
                positioned = true;
            }
            case Request.Stack stack -> {
                int[] direction = Nudge.direction(EditorAction.NUDGE_FORWARD, services.cameraYaw()).orElse(new int[] {1, 0, 0});
                placement = Placement.stack(stack.region(), direction);
                label = "selection";
                capture(c, stack.region());
                following = false;
                positioned = true;
            }
        }
        transformChanged();
    }

    private void startPaste(ToolContext c, Request.Paste paste) {
        label = paste.label();
        following = true;
        positioned = false;
        EditorSession session = c.session();
        if (paste.source() instanceof SourceRef.Clipboard clipboard) {
            Optional<ClipboardCache.Entry> entry = session.clipboards().get(clipboard.id());
            if (entry.isPresent()) {
                placement = Placement.paste(paste.source(), entry.get().dims(), entry.get().anchor(), BlockPos.ORIGIN);
                placement.setTransform(paste.transform());
                sourceCells = entry.get().cells();
            }
        }
        if (placement == null) waitingFor = paste;
        int started = generation;
        Transfer<ClipboardCache.Preview> transfer = session.requestPreview(paste.source());
        previewTransfer = transfer;
        transfer.result().thenAccept(reply -> {
            if (started != generation) return;
            previewArrived(paste, reply);
        });
    }

    private void previewArrived(Request.Paste paste, Reply<ClipboardCache.Preview> reply) {
        if (reply instanceof Reply.Ok<ClipboardCache.Preview> ok) {
            ClipboardCache.Preview preview = ok.value();
            if (placement != null && !placement.dims().equals(preview.dims())) {
                // Not this clipboard's content (the session checks too): keep the outline, show no ghost.
                return;
            }
            volume = preview.volume();
            entities = preview.entities();
            if (placement == null) {
                placement = Placement.paste(paste.source(), preview.dims(), preview.anchor(), BlockPos.ORIGIN);
                placement.setTransform(paste.transform());
                sourceCells = preview.cells();
            }
            waitingFor = null;
            transformChanged();
            return;
        }
        // The session already said why. A clipboard can still be pasted blind (its size is known); an asset cannot.
        if (placement == null) {
            waitingFor = null;
            done();
        }
    }

    /**
     * Starts reading a Move or Stack selection for its ghost: a few sections per frame ({@link #frame}), then built off
     * the render thread. Only the region's own cells are read (a shape's or cell set's sections, and their cells).
     * Selections over the client cap get only their outline.
     */
    private void capture(ToolContext c, Region region) {
        // A large shape is counted in the background (the hint shows its count once known); until then its bounds
        // decide whether it gets a ghost.
        OptionalLong cells = c.regionWork().countNow(region);
        sourceCells = cells.orElse(-1);
        volume = null;
        if (cells.orElse(RegionWork.atMost(region)) > maxCaptureCells) {
            outlineOnly = true;
            return;
        }
        capture = region instanceof Region.Cuboid(Box box) ? new GhostBaker.Capture(c.world(), box)
                : new GhostBaker.Capture(c.world(), region.bounds(), region::contains, region.sectionKeys());
    }

    /** Reads the next slice of a Move or Stack selection, and takes the finished volume. */
    private void updateCapture() {
        if (capture != null && capture.step(CAPTURE_SECTIONS_PER_FRAME)) {
            GhostBaker.Capture read = capture;
            capture = null;
            captureBuild = CompletableFuture.supplyAsync(read::build, services.background());
        }
        if (captureBuild != null && captureBuild.isDone()) {
            GhostVolume built = GhostBaker.finished(captureBuild); // a failed build falls back to outlines
            captureBuild = null;
            if (built != null) {
                volume = built;
                transformChanged();
            } else {
                outlineOnly = true;
            }
        }
    }

    /** Whether the placement shows only outlines (its selection is over the capture cap). */
    public boolean outlineOnly() {
        return outlineOnly;
    }

    /** Drops everything about the current placement (not the pending request). */
    private void clear() {
        // First, so answers for this placement (including the cancellation below) are ignored from now on.
        generation++;
        if (previewTransfer != null && !previewTransfer.finished() && waitingFor != null) previewTransfer.cancel();
        previewTransfer = null;
        placement = null;
        waitingFor = null;
        volume = null;
        sourceCells = 0;
        entities = List.of();
        capture = null;
        captureBuild = null;
        outlineOnly = false;
        clearCopies();
        clipboardGone = false;
        assetRetried = false;
        ghostFilter.clear();
        releaseBaked();
        baking = null;
        bakingFor = null;
        bakingFrom = null;
        bakingTally = null;
        flipCounted = false;
        bakeFailedFor = null;
        bakeFailedFrom = null;
        axisDrag = null;
        ringDrag = false;
        hovered = null;
        services.clearGizmo();
        services.showGhosts(List.of());
    }

    private void releaseBaked() {
        if (baked != null) services.releaseGhost(baked);
        baked = null;
        bakedFor = null;
        bakedFrom = null;
    }

    /** The placement ended; go back to the tool it was started from, if any. */
    private void done() {
        boolean back = returnWhenDone;
        clear();
        returnWhenDone = false;
        if (back) services.finished();
    }

    private void transformChanged() {
        transformChangedAt = lastFrameNanos;
    }

    // ---- Frames ----

    @Override
    public void frame(ToolContext c, FrameInfo f) {
        lastFrameNanos = f.nanoTime();
        cursor = f.cursor();
        if (endIfClipboardGone(c) || placement == null) {
            services.clearGizmo();
            services.showGhosts(List.of());
            return;
        }
        updateCapture();
        updateCopies(c);
        if (sourceCells < 0 && placement.region() != null) {
            c.regionWork().countNow(placement.region()).ifPresent(cells -> sourceCells = cells);
        }
        if (following) {
            followCursor(f.cursor());
            hovered = null;
        } else if (axisDrag == null && !ringDrag) {
            hovered = pickGizmo(f.mouseX(), f.mouseY()).orElse(null);
        }
        if (positioned && !following) {
            double[] g = gizmoOrigin();
            services.showGizmo(g[0], g[1], g[2], axisDrag != null ? GizmoPick.Handle.ofAxis(axisDrag.axis())
                    : ringDrag ? GizmoPick.Handle.ROTATE_Y : hovered);
        } else {
            services.clearGizmo();
        }
        updateBake(c, f.nanoTime());
        services.showGhosts(ghostFilter.apply(c.world(), ghosts(), c.settings().get(settings.into())));
    }

    /** Whether a ghost is still being cut down to the "Paste into" setting (it shows whole meanwhile; tests). */
    boolean filteringGhosts() {
        return ghostFilter.pending();
    }

    private void followCursor(WorldCursor cursor) {
        if (cursor.missed()) return;
        // The bottom centre sits on the top face of the block under the cursor.
        placement.moveTo(cursor.pos().offset(0, 1, 0));
        positioned = true;
    }

    private double[] gizmoOrigin() {
        BlockPos pivot = placement.pivotWorld();
        return new double[] {pivot.x() + 0.5, pivot.y(), pivot.z() + 0.5};
    }

    private Optional<GizmoPick.Handle> pickGizmo(double mouseX, double mouseY) {
        Optional<ScreenProjector> projector = services.projector();
        Optional<double[]> eye = services.eye();
        if (projector.isEmpty() || eye.isEmpty() || placement == null || !positioned) return Optional.empty();
        double[] g = gizmoOrigin();
        double dx = g[0] - eye.get()[0];
        double dy = g[1] - eye.get()[1];
        double dz = g[2] - eye.get()[2];
        double scale = GizmoPick.scale(Math.sqrt(dx * dx + dy * dy + dz * dz));
        return GizmoPick.pick(projector.get(), g[0], g[1], g[2], scale, mouseX, mouseY, GizmoPick.PICK_RADIUS_PX)
                .map(GizmoPick.Picked::handle)
                .filter(handle -> handle.isAxis() || placement.mode() != Placement.Mode.STACK);
    }

    /** Starts, collects or drops the off-thread bake of the current transform. */
    private void updateBake(ToolContext c, long now) {
        if (baking != null && baking.isDone()) {
            GhostVolume result = GhostBaker.finished(baking);
            boolean current = placement != null && volume == bakingFrom && placement.transform().equals(bakingFor);
            if (result != null && current) {
                releaseBaked();
                baked = result;
                bakedFor = bakingFor;
                bakedFrom = bakingFrom;
                if (bakingTally != null && !flipCounted) {
                    flipCounted = true;
                    announceFlip(c, bakingTally);
                }
            } else if (result != null) {
                services.releaseGhost(result);
            } else {
                // The bake failed: that transform shows outlines only, and is not baked again.
                bakeFailedFor = bakingFor;
                bakeFailedFrom = bakingFrom;
            }
            baking = null;
            bakingTally = null;
        }
        Transform transform = placement.transform();
        if (volume == null || transform.isIdentity() || baking != null) return;
        if (volume == bakedFrom && transform.equals(bakedFor)) return;
        if (bakeFailed()) return;
        if (now - transformChangedAt < BAKE_IDLE_NANOS) return;
        Box frame = volume.frame();
        if (frame == null) return;
        List<GhostSection> sections = List.copyOf(volume.sections());
        bakingFor = transform;
        bakingFrom = volume;
        var states = c.states();
        GhostBaker.FlipTally tally = transform.upsideDown() ? new GhostBaker.FlipTally() : null;
        bakingTally = tally;
        baking = CompletableFuture.supplyAsync(() -> GhostBaker.bake(frame, sections, transform, states, tally),
                services.background());
        if (baking.isDone()) updateBake(c, now);
    }

    /**
     * The toast after a flipped bake: how many blocks have no upside-down form and stay as they are, and how many are
     * modded blocks with settings the flip does not know. Nothing when
     * every block turned over.
     */
    private void announceFlip(ToolContext c, GhostBaker.FlipTally tally) {
        long kept = tally.kept();
        long unknown = tally.unknown();
        if (kept == 0 && unknown == 0) return;
        Notice notice = unknown == 0
                ? Notice.of(Notice.Level.INFO, "sculptory.notice.flip_kept", SessionNotices.count(kept))
                : kept == 0
                ? Notice.of(Notice.Level.INFO, "sculptory.notice.flip_unknown", SessionNotices.count(unknown))
                : Notice.of(Notice.Level.INFO, "sculptory.notice.flip_kept_unknown", SessionNotices.count(kept),
                        SessionNotices.count(unknown));
        c.notify(notice);
    }

    /** Whether baking the current transform failed (the placement then shows outlines only). */
    boolean bakeFailed() {
        return placement != null && volume != null && volume == bakeFailedFrom
                && placement.transform().equals(bakeFailedFor);
    }

    /** The ghost previews for this frame: none (outlines only) when the current transform could not be baked. */
    List<GhostPlacement> ghosts() {
        List<GhostPlacement> list = new ArrayList<>();
        if (placement == null || volume == null || !positioned || bakeFailed()) return list;
        Symmetry symmetry = copiesFor == null ? Symmetry.NONE : copiesFor;
        if (placement.mode() == Placement.Mode.STACK) {
            // Copies never turn or mirror; flipped upside down, each shows the baked flip (or its geometry until then).
            Transform flip = placement.transform();
            Optional<GhostVolume> flipped = bakedVolume();
            for (Box copy : placement.copies(MAX_GHOST_COPIES)) {
                list.add(flipped.isPresent()
                        ? GhostPlacement.of(flipped.get(), copy.min().x(), copy.min().y(), copy.min().z())
                        : GhostPlacement.of(volume, copy.min().x(), copy.min().y(), copy.min().z())
                                .withTransform(flip));
                for (CopyPreview preview : copies) {
                    if (preview.volume == null) continue;
                    Box imaged = symmetry.imageBox(preview.image, copy);
                    list.add(GhostPlacement.of(preview.volume, imaged.min().x(), imaged.min().y(), imaged.min().z())
                            .withTransform(flip));
                }
            }
            return list;
        }
        BlockPos min = placement.targetMin();
        Optional<GhostVolume> turned = bakedVolume();
        list.add(turned.isPresent()
                ? GhostPlacement.of(turned.get(), min.x(), min.y(), min.z())
                : GhostPlacement.of(volume, min.x(), min.y(), min.z()).withTransform(placement.transform()));
        Box target = placement.targetBox();
        if (placement.mode() == Placement.Mode.PASTE) {
            // The same source, placed with the op's transform then the image (the baked volume with the image alone).
            for (Symmetry.Image image : pasteImages(symmetry)) {
                Box imaged = symmetry.imageBox(image, target);
                list.add(turned.isPresent()
                        ? GhostPlacement.of(turned.get(), imaged.min().x(), imaged.min().y(), imaged.min().z())
                                .withTransform(image.transform())
                        : GhostPlacement.of(volume, imaged.min().x(), imaged.min().y(), imaged.min().z())
                                .withTransform(placement.transform().compose(image.transform())));
            }
            return list;
        }
        for (CopyPreview preview : copies) {
            if (preview.volume == null) continue;
            Box imaged = symmetry.imageBox(preview.image, target);
            Transform conjugated = preview.image.inverse().transform().compose(placement.transform())
                    .compose(preview.image.transform());
            list.add(GhostPlacement.of(preview.volume, imaged.min().x(), imaged.min().y(), imaged.min().z())
                    .withTransform(conjugated));
        }
        return list;
    }

    // ---- Symmetry ----

    /** A symmetric copy of a Move or Stack preview: the image region, read from the world, and its ghost volume. */
    private static final class CopyPreview {
        final Symmetry.Image image;
        final Region region;
        @Nullable GhostBaker.Capture capture;
        @Nullable CompletableFuture<GhostVolume> build;
        @Nullable GhostVolume volume;

        CopyPreview(Symmetry.Image image, Region region) {
            this.image = image;
            this.region = region;
        }
    }

    /** The symmetry mode the Place settings choose. */
    private Symmetry.Mode symmetryMode(ToolContext c) {
        return c.settings().get(settings.symmetry());
    }

    /**
     * The symmetry a commit runs with now: {@link Symmetry#NONE} with the mode off, the mode around the set centre, or
     * empty when the mode is on and no centre is set.
     */
    public Optional<Symmetry> symmetry(ToolContext c) {
        return symmetryCentre.forOp(symmetryMode(c));
    }

    /**
     * How many copies the placement makes under {@code symmetry} (1 without one), as sizes and hints count them: the
     * server's count of the op that would be sent (a region symmetric about the plane moved or stacked across it is
     * two copies), or of the region alone while a move or stack has not moved yet.
     */
    int copyCount(Symmetry symmetry) {
        if (placement == null || symmetry.isOff()) return 1;
        if (placement.mode() == Placement.Mode.PASTE) return pasteImages(symmetry).size() + 1;
        if (placement.unmoved()) return SelectionActions.copies(placement.region(), symmetry);
        try {
            return OpSymmetry.copyCount(OpSymmetry.withSymmetry(placement.op(PasteOptions.DEFAULT, 0), symmetry));
        } catch (IllegalArgumentException | ArithmeticException beyondTheWorld) {
            return SelectionActions.copies(placement.region(), symmetry);
        }
    }

    /** The images (the identity left out) of a paste's copies under {@code symmetry}, repeats dropped. */
    private List<Symmetry.Image> pasteImages(Symmetry symmetry) {
        if (symmetry.isOff() || placement == null) return List.of();
        try {
            List<Symmetry.Image> images = OpSymmetry.images(new OpSpec.Paste(placement.source(), placement.pasteOrigin(),
                    placement.transform(), PasteOptions.DEFAULT, symmetry));
            return images.subList(1, images.size());
        } catch (IllegalArgumentException | ArithmeticException beyondTheWorld) {
            return List.of();
        }
    }

    /** The images (the identity left out) of a move's or stack's copies under {@code symmetry}, repeats dropped. */
    private List<Symmetry.Image> regionImages(Symmetry symmetry) {
        if (symmetry.isOff() || placement == null || placement.region() == null) return List.of();
        try {
            List<Symmetry.Image> images = OpSymmetry.images(new OpSpec.Erase(placement.region(), CellMask.ANY, symmetry));
            return images.subList(1, images.size());
        } catch (IllegalArgumentException | ArithmeticException beyondTheWorld) {
            return List.of();
        }
    }

    /** Whether the copy previews show only outline boxes (their cells together are over the capture cap). */
    public boolean copiesOutlineOnly() {
        return copiesOutlineOnly;
    }

    /** The symmetric copies with a ghost volume read so far (tests). */
    List<GhostVolume> copyVolumes() {
        List<GhostVolume> volumes = new ArrayList<>();
        for (CopyPreview preview : copies) {
            if (preview.volume != null) volumes.add(preview.volume);
        }
        return volumes;
    }

    private void clearCopies() {
        copies.clear();
        copiesFor = null;
        copiesOutlineOnly = false;
    }

    /**
     * Keeps the copy previews in step with the symmetry: a Move or Stack with a mode on and a centre set reads each
     * image region from the world a few sections per frame (as the original is read) while the copies' cells together
     * fit the capture cap, else shows them as outline boxes; a paste needs no reading (its copies reuse its volume).
     * A changed mode or centre starts over.
     */
    private void updateCopies(ToolContext c) {
        Symmetry symmetry = symmetry(c).orElse(Symmetry.NONE);
        if (!symmetry.equals(copiesFor)) {
            clearCopies();
            copiesFor = symmetry;
            if (!symmetry.isOff() && placement.mode() != Placement.Mode.PASTE && placement.region() != null) {
                startCopies(c, symmetry);
            }
        }
        for (CopyPreview preview : copies) {
            if (preview.capture != null && preview.capture.step(CAPTURE_SECTIONS_PER_FRAME)) {
                GhostBaker.Capture read = preview.capture;
                preview.capture = null;
                preview.build = CompletableFuture.supplyAsync(read::build, services.background());
            }
            if (preview.build != null && preview.build.isDone()) {
                GhostVolume built = GhostBaker.finished(preview.build);
                preview.build = null;
                if (built != null) {
                    preview.volume = built;
                } else {
                    copiesOutlineOnly = true;
                }
            }
        }
    }

    /** The most cells of a magic selection mapped on the client thread for its copies' previews. */
    static final long MAX_COPIED_CELL_SET = 1L << 16;

    private void startCopies(ToolContext c, Symmetry symmetry) {
        Region region = placement.region();
        List<Symmetry.Image> images = regionImages(symmetry);
        if (images.isEmpty()) return;
        long cells = sourceCells >= 0 ? sourceCells : RegionWork.atMost(region);
        long together = cells > Long.MAX_VALUE / (images.size() + 1) ? Long.MAX_VALUE : cells * (images.size() + 1);
        if (outlineOnly || together > maxCaptureCells || (region instanceof Region.Cells && cells > MAX_COPIED_CELL_SET)) {
            copiesOutlineOnly = true;
            return;
        }
        for (Symmetry.Image image : images) {
            Region imaged;
            try {
                imaged = Regions.image(region, symmetry, image);
            } catch (IllegalArgumentException beyondTheWorld) {
                continue;
            }
            CopyPreview preview = new CopyPreview(image, imaged);
            preview.capture = imaged instanceof Region.Cuboid(Box box) ? new GhostBaker.Capture(c.world(), box)
                    : new GhostBaker.Capture(c.world(), imaged.bounds(), imaged::contains, imaged.sectionKeys());
            copies.add(preview);
        }
    }

    /** The symmetry centre key ({@link SymmetryCentre#keyPressed}). */
    private void setSymmetryCentre(ToolContext c) {
        symmetryCentre.keyPressed(c, cursor, symmetryMode(c) != Symmetry.Mode.OFF);
    }

    @Override
    public void renderWorld(ToolContext c, WorldDraw d) {
        if (placement == null || !positioned) return;
        d.seeThrough(true);
        if (placement.mode() == Placement.Mode.MOVE) {
            d.boxFill(placement.box(), SOURCE_FILL);
            d.boxOutline(placement.box(), SOURCE_COLOUR);
        }
        for (Box copy : placement.copies(Placement.MAX_STACK)) {
            d.boxOutline(copy, TARGET_COLOUR);
        }
        if (placement.mode() == Placement.Mode.PASTE) drawEntities(d);
        if (placement.mode() != Placement.Mode.PASTE) drawPivot(d);
        d.seeThrough(false);
        drawSymmetry(c, d);
    }

    /**
     * A move's or stack's pivot while it is placed: an upright centre line through the pivot the gizmo sits on (the
     * bottom centre of the moved box), from a block under it to a block over the box, and for a move a line from
     * where the pivot was (the source box's bottom centre) to where it goes, so the offset reads at a glance. A paste
     * has its gizmo and outlines alone (its source is the clipboard, not a place in the world). Through the terrain.
     */
    private void drawPivot(WorldDraw d) {
        BlockPos pivot = placement.pivotWorld();
        double x = pivot.x() + 0.5, z = pivot.z() + 0.5;
        int height = placement.size().y();
        int line = OverlayColors.scaleAlpha(TARGET_COLOUR, 0.8);
        d.line(x, pivot.y() - 1, z, x, pivot.y() + height + 1, z, line);
        d.line(x - 0.5, pivot.y(), z, x + 0.5, pivot.y(), z, line);
        d.line(x, pivot.y(), z - 0.5, x, pivot.y(), z + 0.5, line);
        if (placement.mode() == Placement.Mode.MOVE && !placement.unmoved()) {
            BlockPos from = placement.box().min().offset(placement.pivot().x(), placement.pivot().y(), placement.pivot().z());
            d.line(from.x() + 0.5, from.y(), from.z() + 0.5, x, pivot.y(), z, OverlayColors.scaleAlpha(SOURCE_COLOUR, 0.8));
        }
    }

    /**
     * With a symmetry mode on and a centre set: the centre line and mirror planes as the brushes draw them, and each
     * copy's boxes (a move's source too) dimmer than the placement's own.
     */
    private void drawSymmetry(ToolContext c, WorldDraw d) {
        Symmetry symmetry = symmetry(c).orElse(Symmetry.NONE);
        if (symmetry.isOff()) return;
        BlockPos size = placement.size();
        BrushOutlines.symmetry(d, symmetry, placement.pivotWorld().y(), Math.max(size.x(), size.z()) / 2, TARGET_COLOUR);
        int target = OverlayColors.scaleAlpha(TARGET_COLOUR, 0.5);
        int source = OverlayColors.scaleAlpha(SOURCE_COLOUR, 0.5);
        List<Symmetry.Image> images = placement.mode() == Placement.Mode.PASTE ? pasteImages(symmetry) : regionImages(symmetry);
        d.seeThrough(true);
        try {
            for (Symmetry.Image image : images) {
                if (placement.mode() == Placement.Mode.MOVE) d.boxOutline(symmetry.imageBox(image, placement.box()), source);
                for (Box copy : placement.copies(Placement.MAX_STACK)) {
                    d.boxOutline(symmetry.imageBox(image, copy), target);
                }
            }
        } catch (IllegalArgumentException beyondTheWorld) {
            // A copy beyond the coordinate range is not drawn (the server refuses such a placement).
        }
        d.seeThrough(false);
    }

    /**
     * An outline box around each clipboard entity (its type's size, standing on its position) where the paste would
     * place it: the position turned with the placement's transform (mirror first, as the server places it).
     */
    private void drawEntities(WorldDraw d) {
        if (entities.isEmpty()) return;
        BlockPos min = placement.targetMin();
        BlockPos dims = placement.dims();
        Transform t = placement.transform();
        int drawn = 0;
        for (PreviewPayload.Entity entity : entities) {
            if (drawn++ >= MAX_ENTITY_OUTLINES) break;
            double x = min.x() + EntityPlacement.mapX(entity.x(), entity.z(), t, dims.x(), dims.z());
            // Flipped, its box turns over within the placement's height, as the server lowers it by its height (a hanging
            // entity, placed by its centre, is drawn the same way here).
            double y = min.y() + (t.upsideDown()
                    ? EntityPlacement.flippedFeet(EntityPlacement.mapY(entity.y(), t, dims.y()), entity.height())
                    : entity.y());
            double z = min.z() + EntityPlacement.mapZ(entity.x(), entity.z(), t, dims.x(), dims.z());
            double half = entity.width() / 2.0;
            outline(d, x - half, y, z - half, x + half, y + entity.height(), z + half, ENTITY_COLOUR);
        }
    }

    /** The twelve edges of a box. */
    private static void outline(WorldDraw d, double x0, double y0, double z0, double x1, double y1, double z1,
                                int argb) {
        for (double y : new double[] {y0, y1}) {
            d.line(x0, y, z0, x1, y, z0, argb);
            d.line(x1, y, z0, x1, y, z1, argb);
            d.line(x1, y, z1, x0, y, z1, argb);
            d.line(x0, y, z1, x0, y, z0, argb);
        }
        d.line(x0, y0, z0, x0, y1, z0, argb);
        d.line(x1, y0, z0, x1, y1, z0, argb);
        d.line(x1, y0, z1, x1, y1, z1, argb);
        d.line(x0, y0, z1, x0, y1, z1, argb);
    }

    /** The clipboard entities the paste's preview carries (local positions and sizes). */
    List<PreviewPayload.Entity> previewEntities() {
        return entities;
    }

    // ---- Pointer ----

    @Override
    public boolean onPointer(ToolContext c, PointerEvent e) {
        if (placement == null) return e.kind() == PointerEvent.Kind.PRESS && e.button() == PointerEvent.LEFT && placing();
        return switch (e.kind()) {
            case MOVE -> false;
            case PRESS -> e.button() == PointerEvent.LEFT && press(c, e);
            case DRAG -> {
                if (e.button() != PointerEvent.LEFT || (axisDrag == null && !ringDrag)) yield false;
                drag();
                yield true;
            }
            case RELEASE -> {
                if (e.button() != PointerEvent.LEFT || (axisDrag == null && !ringDrag)) yield false;
                endDrag(c, true);
                yield true;
            }
        };
    }

    private boolean press(ToolContext c, PointerEvent e) {
        if (!following) {
            Optional<GizmoPick.Handle> handle = pickGizmo(e.mouseX(), e.mouseY());
            Optional<Ray> ray = services.cursorRay();
            if (handle.isPresent() && ray.isPresent() && beginDrag(handle.get(), ray.get())) {
                c.setPointerCapture(true);
                return true;
            }
            // Off the gizmo: pick the ghost up again.
            following = true;
            followCursor(e.cursor());
            return true;
        }
        followCursor(e.cursor());
        if (positioned) following = false;
        return true;
    }

    private boolean beginDrag(GizmoPick.Handle handle, Ray ray) {
        double[] g = gizmoOrigin();
        grabPivot = placement.pivotWorld();
        grabTransform = placement.transform();
        if (handle.isAxis()) {
            axisDrag = GizmoPick.AxisDrag.begin(handle, g[0], g[1], g[2], ray).orElse(null);
            return axisDrag != null;
        }
        if (placement.mode() == Placement.Mode.STACK) return false;
        grabAngle = GizmoPick.ringAngle(g[0], g[1], g[2], ray);
        ringDrag = !Double.isNaN(grabAngle);
        return ringDrag;
    }

    private void drag() {
        Optional<Ray> ray = services.cursorRay();
        if (ray.isEmpty()) return;
        if (axisDrag != null) {
            int blocks = axisDrag.update(ray.get());
            int axis = axisDrag.axis();
            placement.moveTo(grabPivot.offset(axis == 0 ? blocks : 0, axis == 1 ? blocks : 0, axis == 2 ? blocks : 0));
        } else if (ringDrag) {
            double[] g = gizmoOrigin();
            int turns = GizmoPick.snapQuarterTurns(grabAngle, GizmoPick.ringAngle(g[0], g[1], g[2], ray.get()));
            Transform next = grabTransform.compose(Transform.rotation(turns));
            if (!next.equals(placement.transform())) {
                placement.setTransform(next);
                transformChanged();
            }
        }
    }

    /** Ends a gizmo drag; {@code keep} false puts the placement back where the drag started. */
    private void endDrag(ToolContext c, boolean keep) {
        if (axisDrag == null && !ringDrag) return;
        if (!keep && placement != null) {
            placement.moveTo(grabPivot);
            if (!placement.transform().equals(grabTransform)) {
                placement.setTransform(grabTransform);
                transformChanged();
            }
        }
        axisDrag = null;
        ringDrag = false;
        c.setPointerCapture(false);
    }

    // ---- Scroll and keys ----

    /** Ctrl+Scroll in stack mode: the number of copies (Shift ×10). */
    @Override
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        if (placement == null || placement.mode() != Placement.Mode.STACK || !Modifiers.control(e.modifiers())
                || e.amount() == 0) {
            return false;
        }
        int step = (e.amount() > 0 ? 1 : -1) * (Modifiers.shift(e.modifiers()) ? 10 : 1);
        placement.setCount(placement.count() + step);
        return true;
    }

    @Override
    public boolean onAction(ToolContext c, EditorAction a) {
        if (a == EditorAction.SET_SYMMETRY_CENTRE) {
            setSymmetryCentre(c);
            return true;
        }
        if (!placing()) return false;
        switch (a) {
            case COMMIT -> {
                commit(c);
                return true;
            }
            case CANCEL -> {
                if (axisDrag != null || ringDrag) {
                    endDrag(c, false);
                } else {
                    done();
                }
                return true;
            }
            case ROTATE_CW -> {
                return turn(c, () -> placement.rotate(1));
            }
            case ROTATE_CCW -> {
                return turn(c, () -> placement.rotate(-1));
            }
            case FLIP_LEFT_RIGHT -> {
                return turn(c, () -> placement.mirror(Mirror.X));
            }
            case FLIP_FRONT_BACK -> {
                return turn(c, () -> placement.mirror(Mirror.Z));
            }
            case FLIP_UPSIDE_DOWN -> {
                return flip(c);
            }
            case NUDGE_FORWARD, NUDGE_BACK, NUDGE_LEFT, NUDGE_RIGHT, NUDGE_UP, NUDGE_DOWN -> {
                if (placement == null || !positioned || axisDrag != null || ringDrag) return true;
                int step = Modifiers.shift(c.modifiers()) ? 10 : 1;
                Nudge.direction(a, services.cameraYaw()).ifPresent(d -> placement.nudge(d[0] * step, d[1] * step, d[2] * step));
                following = false;
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Rotates the current placement by {@code steps} quarter turns clockwise (the Clipboard window's Rotate). */
    public boolean rotate(int steps) {
        return context != null && placement != null && turn(context, () -> placement.rotate(steps));
    }

    /** Mirrors the current placement (the Clipboard window's Flip). */
    public boolean mirror(Mirror mirror) {
        return context != null && placement != null && turn(context, () -> placement.mirror(mirror));
    }

    /** Flips the current placement upside down, or back (the Clipboard window's Flip upside down). */
    public boolean flipUpsideDown() {
        return context != null && placement != null && flip(context);
    }

    /**
     * Flips the placement upside down or back; the next flipped bake counts the blocks that stay as they are. A lambda,
     * not a method reference: while a preview is loading there is no placement yet, and {@link #turn} checks that.
     */
    private boolean flip(ToolContext c) {
        boolean handled = turn(c, () -> placement.flipUpsideDown());
        flipCounted = false;
        return handled;
    }

    private boolean turn(ToolContext c, java.util.function.BooleanSupplier change) {
        if (placement == null) return true;
        if (axisDrag != null || ringDrag) return true;
        if (change.getAsBoolean()) {
            transformChanged();
        } else {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.stack_no_turn"));
        }
        return true;
    }

    // ---- Commit ----

    /** Enter: sends the paste, move or stack. */
    void commit(ToolContext c) {
        if (endIfClipboardGone(c)) return;
        if (placement == null) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.preview_loading"));
            return;
        }
        if (!positioned) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.place_aim_first"));
            return;
        }
        if (placement.unmoved()) {
            c.notify(Notice.of(Notice.Level.INFO, placement.mode() == Placement.Mode.MOVE
                    ? "sculptory.notice.move_first" : "sculptory.notice.stack_first"));
            return;
        }
        Permissions permissions = c.session().permissions();
        Perm needed = placement.mode() == Placement.Mode.PASTE ? Perm.CLIPBOARD : Perm.REGION;
        if (!permissions.has(needed)) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.needs_permission", needed.node()));
            return;
        }
        Optional<Symmetry> chosen = symmetry(c);
        if (chosen.isEmpty()) {
            c.notify(Notice.of(Notice.Level.WARNING, SelectionActions.SYMMETRY_CENTRE_NEEDED,
                    services.keyLabel(KeyAction.SET_SYMMETRY_CENTRE)));
            return;
        }
        Symmetry symmetry = chosen.get();
        int copyCount = copyCount(symmetry);
        if (placement.mode() != Placement.Mode.PASTE) {
            if (!RegionWork.countable(placement.region())) {
                c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large",
                        SessionNotices.count(RegionWork.atMost(placement.region())),
                        SessionNotices.count(permissions.limits().maxOpVolume())));
                return;
            }
            OptionalLong cells = c.regionWork().countNow(placement.region());
            long stacked = placement.mode() == Placement.Mode.STACK ? placement.count() : 1;
            long atMost = saturatingTimes(saturatingTimes(RegionWork.atMost(placement.region()), stacked), copyCount);
            if (cells.isEmpty() && atMost > Math.min(opLimit(permissions), SelectionActions.CONFIRM_VOLUME)) {
                // A large shape: its exact count decides the limit and the confirmation; count it off the client
                // thread and commit then, if this placement is still the one on screen.
                int started = generation;
                c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.counting"));
                c.regionWork().count(placement.region()).thenAccept(count -> {
                    if (generation == started && placement != null) {
                        commitCounted(c, permissions, OptionalLong.of(count), symmetry);
                    }
                });
                return;
            }
            commitCounted(c, permissions, cells, symmetry);
            return;
        }
        commitCounted(c, permissions, OptionalLong.empty(), symmetry);
    }

    /**
     * The rest of {@link #commit}: {@code cells} is the move's or stack's exact cell count, or empty when its bounds
     * alone are under every threshold (and for a paste).
     */
    private void commitCounted(ToolContext c, Permissions permissions, OptionalLong cells, Symmetry symmetry) {
        int copyCount = copyCount(symmetry);
        long blocks = saturatingTimes(switch (placement.mode()) {
            case PASTE -> placement.targetBox().volume();
            case MOVE -> cells.orElse(RegionWork.atMost(placement.region()));
            case STACK -> saturatingTimes(cells.orElse(RegionWork.atMost(placement.region())), placement.count());
        }, copyCount);
        if (blocks > opLimit(permissions)) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large",
                    SessionNotices.count(blocks), SessionNotices.count(permissions.limits().maxOpVolume())));
            return;
        }
        SettingsValues values = c.settings();
        boolean physics = values.get(settings.physics()) && permissions.has(Perm.PHYSICS);
        EntityFilter filter = values.get(settings.entities());
        OpSpec op = OpSymmetry.withSymmetry(placement.op(new PasteOptions(values.get(settings.includeAir()), physics,
                filter != EntityFilter.NONE, values.get(settings.into())), c.states().air(), filter), symmetry);
        Placement.Mode mode = placement.mode();
        // Where the selection goes after a move is worked out only once the move is sent (after any confirmation).
        Region source = placement.region();
        BlockPos targetMin = placement.targetMin();
        Transform transform = placement.transform();
        Runnable send = () -> {
            send(c, op, physics, mode);
            if (mode == Placement.Mode.MOVE) {
                selectMoved(c, source, targetMin, transform);
                done();
            }
        };
        if (blocks > SelectionActions.CONFIRM_VOLUME) {
            services.confirm(opNameKey(mode), blocks, copyCount, send);
        } else {
            send.run();
        }
    }

    private void send(ToolContext c, OpSpec op, boolean physics, Placement.Mode mode) {
        EditorSession session = c.session();
        int sentFor = generation;
        session.send(new ToolAction.RunOp(op, physics)).thenAccept(result -> {
            if (!(result instanceof ToolResult.Rejected rejected)) return;
            // A paste of an asset the server's shared asset cache let go of is refused as ASSET_NOT_LOADED. Previewing
            // it again loads it there again: done once per placement, and only while that placement is still the one
            // on screen; any other refusal is shown as it is.
            boolean reload = rejected.reason() == RejectReason.ASSET_NOT_LOADED && op instanceof OpSpec.Paste paste
                    && paste.src() instanceof SourceRef.Asset && sentFor == generation && !assetRetried;
            if (reload) {
                assetRetried = true;
                SourceRef source = ((OpSpec.Paste) op).src();
                session.clipboards().forgetPreview(source);
                session.requestPreview(source);
                c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.asset_reloading"));
                return;
            }
            c.notify(SessionNotices.rejection(rejected.reason(), SessionNotices.Subject.EDIT,
                    session.permissions().limits()));
        });
        switch (mode) {
            // Ready for another paste of the same source: the ghost follows the cursor again.
            case PASTE -> following = true;
            case MOVE -> {
            }
            case STACK -> done();
        }
    }

    /**
     * Selects where a move put the selection. The same cells moved cost nothing (a cell set keeps its outline and moves
     * its offset); a box or shape turns at once; a turned or mirrored cell set is mapped cell by cell off the client
     * thread and selected then, if the selection is still the one moved. A move out of range leaves the selection.
     */
    static void selectMoved(ToolContext c, Region source, BlockPos targetMin, Transform transform) {
        Selection before = c.selectionState().orElse(null);
        try {
            if (transform.isIdentity()) {
                boolean same = before != null && before.region() == source;
                c.setSelectionState((same ? before : Selection.of(source)).movedTo(targetMin));
            } else if (!(source instanceof Region.Cells)) {
                c.setSelectionRegion(SelectionModel.moved(source, targetMin, transform));
            } else {
                c.regionWork().supply(() -> SelectionModel.moved(source, targetMin, transform)).thenAccept(moved -> {
                    if (c.selectionState().orElse(null) == before) {
                        c.setSelectionRegion(moved);
                    }
                });
            }
        } catch (IllegalArgumentException | ArithmeticException outOfRange) {
            // The server refuses such a move anyway; the selection stays where it was.
        }
    }

    private static long opLimit(Permissions permissions) {
        return permissions.has(Perm.LIMIT_BYPASS) ? Long.MAX_VALUE : permissions.limits().maxOpVolume();
    }

    private static long saturatingTimes(long cells, long count) {
        return cells > Long.MAX_VALUE / Math.max(1, count) ? Long.MAX_VALUE : cells * count;
    }

    private static String opNameKey(Placement.Mode mode) {
        return switch (mode) {
            case PASTE -> "sculptory.op.paste";
            case MOVE -> "sculptory.op.move";
            case STACK -> "sculptory.op.stack";
        };
    }

    // ---- HUD ----

    @Override
    public List<KeyHint> hints(ToolContext c) {
        List<KeyHint> hints = new ArrayList<>();
        if (placement == null) {
            if (waitingFor != null) {
                hints.add(KeyHint.text("sculptory.hint.place.loading", percent()));
                hints.add(new KeyHint("Esc", "sculptory.hint.place.cancel"));
            } else {
                hints.add(KeyHint.text("sculptory.hint.place.empty"));
            }
            return hints;
        }
        Symmetry.Mode mode = symmetryMode(c);
        Optional<Symmetry> symmetry = symmetry(c);
        int copyCount = symmetry.map(this::copyCount).orElse(1);
        String count = sourceCells < 0 ? "…" : SessionNotices.count(saturatingTimes(placement.blocks(sourceCells), copyCount));
        String copiesText = Integer.toString(copyCount);
        switch (placement.mode()) {
            case PASTE -> hints.add(copyCount > 1 ? KeyHint.text("sculptory.hint.place.paste_copies", count, copiesText)
                    : KeyHint.text("sculptory.hint.place.paste", count));
            case MOVE -> hints.add(copyCount > 1 ? KeyHint.text("sculptory.hint.place.move_copies", count, copiesText)
                    : KeyHint.text("sculptory.hint.place.move", count));
            case STACK -> hints.add(copyCount > 1
                    ? KeyHint.text("sculptory.hint.place.stack_copies", Integer.toString(placement.count()), count, copiesText)
                    : KeyHint.text("sculptory.hint.place.stack", Integer.toString(placement.count()), count));
        }
        if (mode != Symmetry.Mode.OFF) {
            if (symmetry.isEmpty()) {
                hints.add(KeyHint.text("sculptory.hint.symmetry_centre_needed", services.keyLabel(KeyAction.SET_SYMMETRY_CENTRE)));
            } else if (copiesOutlineOnly) {
                hints.add(KeyHint.text("sculptory.hint.place.copies_outline_only"));
            }
            hints.add(new KeyHint(services.keyLabel(KeyAction.SET_SYMMETRY_CENTRE), "sculptory.hint.brush.symmetry_centre"));
        }
        if (previewTransfer != null && !previewTransfer.finished()) {
            hints.add(KeyHint.text("sculptory.hint.place.loading", percent()));
        }
        if (capture != null) {
            hints.add(KeyHint.text("sculptory.hint.place.reading",
                    Integer.toString(capture.sectionsRead() * 100 / Math.max(1, capture.sections()))));
        }
        if (outlineOnly) hints.add(KeyHint.text("sculptory.hint.place.outline_only"));
        String status = services.ghostStatus();
        if (!status.isEmpty()) hints.add(KeyHint.text("sculptory.hint.place.status", status));
        if (placement.mode() != Placement.Mode.STACK) {
            hints.add(new KeyHint(services.keyLabel(KeyAction.ROTATE_CW), "sculptory.hint.place.rotate"));
            hints.add(new KeyHint(services.keyLabel(KeyAction.FLIP_LEFT_RIGHT), "sculptory.hint.place.flip"));
        } else {
            hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.place.copies"));
        }
        hints.add(new KeyHint(services.keyLabel(KeyAction.FLIP_UPSIDE_DOWN), "sculptory.hint.place.upside_down"));
        hints.add(new KeyHint("LMB", following ? "sculptory.hint.place.drop" : "sculptory.hint.place.pick_up"));
        hints.add(new KeyHint(services.keyLabel(KeyAction.COMMIT), switch (placement.mode()) {
            case PASTE -> "sculptory.hint.place.commit";
            case MOVE -> "sculptory.hint.place.commit_move";
            case STACK -> "sculptory.hint.place.commit_stack";
        }));
        hints.add(new KeyHint("Esc", "sculptory.hint.place.cancel"));
        return hints;
    }

    private String percent() {
        Transfer<?> transfer = previewTransfer;
        return transfer == null ? "0" : Integer.toString((int) Math.round(transfer.progress() * 100));
    }
}
