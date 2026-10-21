package dev.sculptory.mc.client.editor.tools.generate;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.path.PathSample;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.mc.client.editor.input.KeyAction;
import dev.sculptory.mc.client.editor.render.OverlayColors;
import dev.sculptory.mc.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.mc.client.editor.render.ghost.GhostSection;
import dev.sculptory.mc.client.editor.render.ghost.GhostVolume;
import dev.sculptory.mc.client.editor.tool.EditorAction;
import dev.sculptory.mc.client.editor.tool.FrameInfo;
import dev.sculptory.mc.client.editor.tool.KeyHint;
import dev.sculptory.mc.client.editor.tool.PointerEvent;
import dev.sculptory.mc.client.editor.tool.ScrollEvent;
import dev.sculptory.mc.client.editor.tool.ToolContext;
import dev.sculptory.mc.client.editor.tool.WorldCursor;
import dev.sculptory.mc.client.editor.tool.WorldDraw;
import dev.sculptory.mc.client.editor.tools.place.GhostBaker;
import dev.sculptory.mc.client.editor.tools.place.Placement;
import dev.sculptory.mc.client.editor.tools.select.SelectionActions;
import dev.sculptory.mc.client.editor.tools.points.PointEditor;
import dev.sculptory.mc.client.editor.world.Ray;
import dev.sculptory.mc.client.editor.world.ScreenProjector;
import dev.sculptory.mc.client.session.ClipboardCache;
import dev.sculptory.mc.client.session.EditorSession;
import dev.sculptory.mc.client.session.LibraryFolder;
import dev.sculptory.mc.client.session.Notice;
import dev.sculptory.mc.client.session.Permissions;
import dev.sculptory.mc.client.session.Reply;
import dev.sculptory.mc.client.session.SessionNotices;
import dev.sculptory.mc.client.session.ToolAction;
import dev.sculptory.mc.client.session.ToolResult;
import dev.sculptory.mc.client.session.Transfer;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Generate's Stamp: the clipboard, or an asset picked in the Library, copied at each clicked block, every copy turned
 * along the path through the points; built as one {@link OpSpec.PasteMany} labelled "Stamp" (one job, one undo step,
 * block entities and entities kept: the server pastes its own copy of the source).
 *
 * <p><b>Points</b> are edited with the shared {@link PointEditor}, as the Line's: a click on a block adds one (each copy
 * stands on its block, its bottom middle on the block's top, as the Place tool puts a paste), a click on open sky adds
 * one at the air marker (the copy stands on the marker's cell, {@link AirPoints}), a click on a point selects it and its
 * gizmo moves it, a click on the drawn path inserts one, Delete or Backspace removes the selected (or last) one, Esc
 * deselects, then clears them. At most
 * {@value OpSpec.PasteMany#MAX_PLACEMENTS} copies.
 *
 * <p><b>Turning.</b> The first copy keeps the source as copied; each other copy is turned by the angle the path's
 * heading has turned there since the first point (a free yaw; the heading at a point runs from the point before it to
 * the one after), its blocks resampled as a free paste is. R and Shift+R turn every copy a further quarter.
 * {@link #orientation} is where a copy's turn is worked out.
 *
 * <p><b>Ghosts</b>: one per copy, of the source's preview, turned as a whole until its resampled bake (made off the
 * client thread per turn) is ready;
 * without a preview the copies' boxes are outlined. Client thread only.
 */
public final class StampDraft {
    public static final String NEEDS_POINT = "sculptory.notice.stamp_needs_point";
    public static final String NO_CLIPBOARD = "sculptory.notice.stamp_no_clipboard";
    public static final String NO_ASSET = "sculptory.notice.stamp_no_asset";
    public static final String TOO_MANY = "sculptory.notice.stamp_too_many";
    public static final String WAITING = "sculptory.notice.stamp_waiting";
    public static final String PREVIEW_FAILED = "sculptory.notice.stamp_preview_failed";
    public static final String RELOADING = "sculptory.notice.asset_reloading";
    public static final String SENT = "sculptory.notice.stamp_sent";
    public static final String OP = "sculptory.op.stamp";
    /** The most distinct turns baked at once; copies past them show the preview turned as a whole. */
    static final int MAX_BAKES = 64;
    public static final int OUTLINE_COLOUR = OverlayColors.scaleAlpha(GenerateTool.PATH_COLOUR, 0.6f);
    /** Blocks only where the source has them (its air leaves the world as it is); its entities come too. */
    static final PasteOptions OPTIONS = new PasteOptions(false, false, true);
    /** The path is drawn this far over the points' centres (on the blocks' tops, where the copies stand). */
    static final double DRAWN_LIFT = 0.5;

    /** The source copied, as the server knows it, and its size and anchor. */
    public record Source(SourceRef ref, String name, BlockPos dims, BlockPos anchor, long cells) {
        public Source {
            Objects.requireNonNull(ref);
            Objects.requireNonNull(name);
            Objects.requireNonNull(dims);
            Objects.requireNonNull(anchor);
        }
    }

    private final Supplier<GenerateTool.Services> services;
    private final PathModel points = new PathModel();
    /** The shared point editor of the copies' points. */
    private final PointEditor editor;
    /** Quarter turns R added to every copy. */
    private int turns;
    /** The Library asset picked for stamping (path, content hash), or null. */
    private String assetPath;
    private String assetHash;
    /** The source the preview is for, and its preview once here (null until then, or when it can't be had). */
    private SourceRef previewFor;
    private Transfer<ClipboardCache.Preview> previewTransfer;
    private GhostVolume volume;
    /** The preview's size, anchor and blocks (what an asset's copies are placed by). */
    private BlockPos previewDims;
    private BlockPos previewAnchor;
    private long previewCells;
    /**
     * Turned bakes of {@link #volume}, per turn, and the ones being baked: only for turns some copy has now (the others
     * are released each frame), at most {@value #MAX_BAKES} of them, and none started while a point is dragged.
     */
    private final Map<Orientation, GhostVolume> baked = new HashMap<>();
    private final Map<Orientation, CompletableFuture<GhostVolume>> baking = new HashMap<>();
    /** Bakes no longer wanted but still running: released when they finish. */
    private final List<CompletableFuture<GhostVolume>> orphans = new ArrayList<>();
    /** Turns whose bake failed: shown unbaked, not baked again. */
    private final Set<Orientation> bakeFailed = new HashSet<>();
    /** The source whose preview was refused: not asked for again until Enter (or another asset) says to. */
    private SourceRef previewFailed;
    /** A stamp of an asset the server had let go of was retried once (its preview loads it again there). */
    private boolean assetRetried;

    /** Points in the air: a click on open sky puts a copy at the marker ({@link AirPoints}). */
    private final AirPoints air;
    StampDraft(Supplier<GenerateTool.Services> services, AirPoints air) {
        this.services = Objects.requireNonNull(services);
        this.air = Objects.requireNonNull(air);
        PointEditor.View view = GenerateTool.pointView(services);
        this.editor = new PointEditor(points, air, view, new PointEditor.Host() {
            @Override
            public BlockPos pointOn(WorldCursor cursor) {
                return cursor.pos();
            }

            @Override
            public int maxPoints() {
                return OpSpec.PasteMany.MAX_PLACEMENTS;
            }

            @Override
            public String tooManyNotice(PathModel.Role role) {
                return TOO_MANY;
            }

            @Override
            public List<List<PathSample>> drawnSegments(List<BlockPos> nodes, boolean closed) {
                // The path is drawn over the blocks' tops, where the copies stand.
                List<List<PathSample>> segments = new ArrayList<>();
                for (List<PathSample> chord : PointEditor.chords(nodes, false)) {
                    List<PathSample> raised = new ArrayList<>(chord.size());
                    for (PathSample s : chord) raised.add(new PathSample(s.x(), s.y() + DRAWN_LIFT, s.z(), s.along()));
                    segments.add(raised);
                }
                return segments;
            }

            @Override
            public BlockPos insertPoint(List<BlockPos> nodes, int segment, double[] at) {
                // The drawn path runs over the blocks' tops: the point goes in the block under it.
                return new BlockPos((int) Math.floor(at[0]), (int) Math.floor(at[1] - DRAWN_LIFT), (int) Math.floor(at[2]));
            }

            @Override
            public void edited(ToolContext c, PointEditor.Edit edit) {
                // The ghosts follow the points each frame; nothing to make again here.
            }
        });
    }

    public PathModel points() {
        return points;
    }

    /** The point editor of the copies' points (for tests). */
    public PointEditor editor() {
        return editor;
    }
    /** Quarter turns R added to every copy. */
    public int turns() {
        return turns;
    }

    /** Stamps the Library asset at {@code path} (its content hash as the server indexed it) from now on. */
    public void useAsset(String path, String contentHash) {
        assetPath = Objects.requireNonNull(path);
        assetHash = Objects.requireNonNull(contentHash);
        previewFailed = null;
        assetRetried = false;
    }

    /** The asset picked in the Library, if any (its path). */
    public Optional<String> asset() {
        return Optional.ofNullable(assetPath);
    }

    /**
     * What the settings say to copy: the clipboard's current content, or the Library asset; empty (with the reason's
     * notice key in {@code why}, when given) when there is none.
     */
    public Optional<Source> source(ToolContext c, String[] why) {
        EditorSession session;
        try {
            session = c.session();
        } catch (IllegalStateException noSession) {
            return Optional.empty();
        }
        if (c.settings().get(GenerateSettings.STAMP_SOURCE) == GenerateSettings.StampSource.CLIPBOARD) {
            Optional<ClipboardCache.Entry> entry = session.clipboards().current();
            if (entry.isEmpty()) {
                if (why != null) why[0] = NO_CLIPBOARD;
                return Optional.empty();
            }
            ClipboardCache.Entry e = entry.get();
            return Optional.of(new Source(new SourceRef.Clipboard(e.clipboardId()), "clipboard", e.dims(), e.anchor(),
                    e.cells()));
        }
        Optional<SourceRef> asset = assetHash == null ? Optional.empty() : LibraryFolder.asset(assetHash);
        if (asset.isEmpty()) {
            if (why != null) why[0] = NO_ASSET;
            return Optional.empty();
        }
        // An asset's size and anchor come with its preview.
        if (asset.get().equals(previewFailed)) {
            if (why != null) why[0] = PREVIEW_FAILED;
            return Optional.empty();
        }
        if (volume == null || !asset.get().equals(previewFor) || previewDims == null) {
            if (why != null) why[0] = WAITING;
            return Optional.empty();
        }
        return Optional.of(new Source(asset.get(), assetPath, previewDims, previewAnchor, previewCells));
    }

    // ---- Input ----

    /** A pointer event for the point editor (a click adds a copy on the block); returns whether it was taken. */
    public boolean onPointer(ToolContext c, PointerEvent e) {
        return editor.onPointer(c, e);
    }

    /** Esc, Delete/Backspace, the insert key, R and Enter; returns whether the action was taken. */
    public boolean onAction(ToolContext c, EditorAction a) {
        if (editor.onAction(c, a)) return true;
        switch (a) {
            case CANCEL -> {
                if (points.isEmpty()) return false;
                editor.clear(c);
                return true;
            }
            case ROTATE_CW, ROTATE_CCW -> {
                turns = Math.floorMod(turns + (a == EditorAction.ROTATE_CW ? 1 : -1), 4);
                return true;
            }
            case COMMIT -> {
                build(c);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Alt+Scroll: the marker nearer or farther, or the point dragged freely. */
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        return editor.onScroll(c, e);
    }

    /** Whether a point is being dragged. */
    public boolean dragging() {
        return editor.dragging();
    }

    // ---- Copies ----

    /**
     * Copy {@code i}'s turn: R's quarter turns, then the angle the path's heading at its point has turned from the
     * heading at the first point (clockwise seen from above, to 1/100 degree), a free yaw. A turn of a whole quarter
     * stays exact.
     */
    Orientation orientation(int i) {
        List<BlockPos> nodes = points.nodes();
        double first = settledHeading(nodes, 0);
        double here = settledHeading(nodes, i);
        long yaw = Double.isNaN(first) || Double.isNaN(here) ? 0
                : Math.round((here - first) * (Orientation.FULL_TURN / 2) / Math.PI);
        return Orientation.free(Transform.rotation(turns), (int) Math.floorMod(yaw, (long) Orientation.FULL_TURN), 0, 0);
    }

    /**
     * The heading at point {@code i}, or where the path runs straight up or down there (points stacked in the air), the
     * nearest one before it that has one, else the nearest after; NaN when no point has one. A copy on a vertical stretch
     * keeps the turn of the path leading up to it.
     */
    private static double settledHeading(List<BlockPos> nodes, int i) {
        for (int j = i; j >= 0; j--) {
            double heading = heading(nodes, j);
            if (!Double.isNaN(heading)) return heading;
        }
        for (int j = i + 1; j < nodes.size(); j++) {
            double heading = heading(nodes, j);
            if (!Double.isNaN(heading)) return heading;
        }
        return Double.NaN;
    }

    /** The path's heading at point {@code i} (yaw, clockwise from east seen from above), or NaN without one. */
    private static double heading(List<BlockPos> nodes, int i) {
        BlockPos from = nodes.get(Math.max(0, i - 1)), to = nodes.get(Math.min(nodes.size() - 1, i + 1));
        int dx = to.x() - from.x(), dz = to.z() - from.z();
        if (dx == 0 && dz == 0) return Double.NaN;
        return StrictMath.atan2(dz, dx);
    }

    /** Copy {@code i} as the Place tool would place it: the source's bottom middle on the top of the clicked block. */
    private Placement placement(Source source, int i) {
        Placement placement = Placement.paste(source.ref(), source.dims(), source.anchor(), points.node(i).offset(0, 1, 0));
        placement.setOrientation(orientation(i));
        return placement;
    }

    /** The multi-paste of every copy, or empty without a source or points. */
    public Optional<OpSpec.PasteMany> op(ToolContext c) {
        Optional<Source> source = source(c, null);
        if (source.isEmpty() || points.isEmpty()) return Optional.empty();
        List<OpSpec.PasteMany.Placement> placements = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            Placement placement = placement(source.get(), i);
            placements.add(new OpSpec.PasteMany.Placement(placement.pasteOrigin(), orientation(i)));
        }
        return Optional.of(new OpSpec.PasteMany(source.get().ref(), placements, OPTIONS));
    }

    // ---- Frames and ghosts ----

    /**
     * Once per frame: the point editor (marker, guide line, hovering, a held point following the camera), then asks for
     * the source's preview when it changed, takes finished bakes, shows the ghosts.
     */
    public void frame(ToolContext c, FrameInfo f) {
        editor.frame(c, f);
        SourceRef wanted = wantedPreview(c);
        if (!Objects.equals(wanted, previewFor)) requestPreview(c, wanted);
        takeBakes();
        services.get().showGhosts(ghosts(c));
    }

    /** The source whose preview the ghosts need (the asset before its size is known), or null. */
    private SourceRef wantedPreview(ToolContext c) {
        Optional<Source> source = source(c, null);
        SourceRef wanted = source.isPresent() ? source.get().ref()
                : c.settings().get(GenerateSettings.STAMP_SOURCE) == GenerateSettings.StampSource.LIBRARY && assetHash != null
                ? LibraryFolder.asset(assetHash).orElse(null) : null;
        // A refused preview is not asked for every frame: Enter asks again.
        return wanted != null && wanted.equals(previewFailed) ? null : wanted;
    }

    private void requestPreview(ToolContext c, SourceRef ref) {
        dropPreview();
        previewFor = ref;
        if (ref == null) return;
        EditorSession session;
        try {
            session = c.session();
        } catch (IllegalStateException noSession) {
            return;
        }
        Transfer<ClipboardCache.Preview> transfer = session.requestPreview(ref);
        previewTransfer = transfer;
        transfer.result().thenAccept(reply -> {
            if (previewTransfer != transfer) return;
            if (!(reply instanceof Reply.Ok<ClipboardCache.Preview> ok)) {
                // The session said why. Nothing waits on it any more: Enter asks again.
                previewFailed = ref;
                previewFor = null;
                return;
            }
            volume = ok.value().volume();
            previewDims = ok.value().dims();
            previewAnchor = ok.value().anchor();
            previewCells = ok.value().cells();
        });
    }

    private void dropPreview() {
        if (previewTransfer != null && !previewTransfer.finished()) previewTransfer.cancel();
        previewTransfer = null;
        previewFor = null;
        volume = null;
        previewDims = null;
        previewAnchor = null;
        for (GhostVolume bake : baked.values()) services.get().releaseGhost(bake);
        baked.clear();
        orphans.addAll(baking.values()); // released when they finish
        baking.clear();
        bakeFailed.clear();
    }

    private void takeBakes() {
        for (Iterator<CompletableFuture<GhostVolume>> it = orphans.iterator(); it.hasNext(); ) {
            CompletableFuture<GhostVolume> bake = it.next();
            if (!bake.isDone()) continue;
            it.remove();
            GhostVolume late = GhostBaker.finished(bake);
            if (late != null) services.get().releaseGhost(late);
        }
        for (Orientation turn : List.copyOf(baking.keySet())) {
            CompletableFuture<GhostVolume> bake = baking.get(turn);
            if (!bake.isDone()) continue;
            baking.remove(turn);
            GhostVolume done = GhostBaker.finished(bake);
            if (done != null) {
                baked.put(turn, done);
            } else {
                bakeFailed.add(turn); // logged once by the baker; shown unbaked from now on
            }
        }
    }

    /** One ghost per copy: its turned bake once made, else the preview turned as a whole. */
    List<GhostPlacement> ghosts(ToolContext c) {
        Optional<Source> source = source(c, null);
        if (volume == null || source.isEmpty() || points.isEmpty()) return List.of();
        List<GhostPlacement> out = new ArrayList<>(points.size());
        Set<Orientation> used = new HashSet<>();
        for (int i = 0; i < points.size(); i++) {
            Placement placement = placement(source.get(), i);
            Orientation turn = placement.orientation();
            used.add(turn);
            BlockPos min = placement.targetMin();
            GhostVolume turned = turn.isIdentity() ? volume : baked.get(turn);
            if (turned == null) {
                // While a point is dragged the turns change every move: the copies show turned as a whole until it rests.
                if (!editor.dragging()) bake(c, turn);
                out.add(GhostPlacement.of(volume, min.x(), min.y(), min.z()).withOrientation(turn));
            } else {
                out.add(GhostPlacement.of(turned, min.x(), min.y(), min.z()));
            }
        }
        forgetUnused(used);
        return out;
    }

    /** Releases the bakes of turns no copy has any more; bakes still running for them are released when they finish. */
    private void forgetUnused(Set<Orientation> used) {
        for (Iterator<Map.Entry<Orientation, GhostVolume>> it = baked.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Orientation, GhostVolume> entry = it.next();
            if (used.contains(entry.getKey())) continue;
            services.get().releaseGhost(entry.getValue());
            it.remove();
        }
        for (Iterator<Map.Entry<Orientation, CompletableFuture<GhostVolume>>> it = baking.entrySet().iterator();
             it.hasNext(); ) {
            Map.Entry<Orientation, CompletableFuture<GhostVolume>> entry = it.next();
            if (used.contains(entry.getKey())) continue;
            orphans.add(entry.getValue());
            it.remove();
        }
        bakeFailed.retainAll(used);
    }

    /** Bakes held or running (tests). */
    int liveBakes() {
        return baked.size() + baking.size();
    }

    private void bake(ToolContext c, Orientation turn) {
        if (baking.containsKey(turn) || bakeFailed.contains(turn) || volume.frame() == null) return;
        if (baked.size() + baking.size() >= MAX_BAKES) return;
        StateSpace states = GenerateTool.statesOrNull(c);
        if (states == null) return;
        Box frame = volume.frame();
        List<GhostSection> sections = List.copyOf(volume.sections());
        baking.put(turn, CompletableFuture.supplyAsync(() -> GhostBaker.bake(frame, sections, turn, states, null),
                services.get().background()));
    }

    /** The kind or tool was put away: drags end, ghosts go, the points stay. */
    public void hide(ToolContext c) {
        editor.deactivate(c);
        dropPreview();
        takeBakes(); // bakes already finished are released now; the others when they finish
        services.get().showGhosts(List.of());
    }

    // ---- Building ----

    private void build(ToolContext c) {
        String[] why = {null};
        Optional<Source> source = source(c, why);
        if (source.isEmpty()) {
            if (PREVIEW_FAILED.equals(why[0])) {
                // Ask for the asset's preview again (it also loads the asset on the server).
                previewFailed = null;
                c.notify(Notice.of(Notice.Level.INFO, RELOADING));
                return;
            }
            if (why[0] != null) c.notify(Notice.of(Notice.Level.INFO, why[0]));
            return;
        }
        if (points.isEmpty()) {
            c.notify(Notice.of(Notice.Level.INFO, NEEDS_POINT));
            return;
        }
        EditorSession session;
        try {
            session = c.session();
        } catch (IllegalStateException noSession) {
            return;
        }
        Permissions permissions = session.permissions();
        Optional<Perm> missing = GenerateTool.missingNode(permissions);
        if (missing.isPresent()) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.needs_permission", missing.get().node()));
            return;
        }
        OpSpec.PasteMany op = op(c).orElseThrow();
        long blocks = source.get().cells() > Long.MAX_VALUE / points.size() ? Long.MAX_VALUE
                : source.get().cells() * points.size();
        Limits limits = permissions.limits();
        if (!permissions.has(Perm.LIMIT_BYPASS) && blocks > limits.maxOpVolume()) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large", SessionNotices.count(blocks),
                    SessionNotices.count(limits.maxOpVolume())));
            return;
        }
        int copies = op.placements().size();
        Runnable send = () -> session.send(new ToolAction.RunOp(op, false, OpLabel.STAMP)).thenAccept(result -> {
            switch (result) {
                case ToolResult.Accepted accepted -> {
                    assetRetried = false;
                    c.notify(Notice.of(Notice.Level.INFO, SENT, Integer.toString(copies), SessionNotices.count(blocks)));
                }
                case ToolResult.Rejected rejected -> {
                    // An asset the server's asset cache let go of: its preview loads it there again, once (as the
                    // Place tool does); press Enter once it is back.
                    if (rejected.reason() == RejectReason.ASSET_NOT_LOADED && op.src() instanceof SourceRef.Asset
                            && !assetRetried) {
                        assetRetried = true;
                        session.clipboards().forgetPreview(op.src());
                        requestPreview(c, op.src());
                        c.notify(Notice.of(Notice.Level.INFO, RELOADING));
                        return;
                    }
                    c.notify(SessionNotices.rejection(rejected.reason(), SessionNotices.Subject.EDIT,
                            session.permissions().limits()));
                }
                case ToolResult.Done ignored -> {
                }
            }
        });
        if (blocks > SelectionActions.CONFIRM_VOLUME) {
            services.get().confirm(OP, blocks, send);
        } else {
            send.run();
        }
    }

    // ---- Drawing and hints ----

    /** The points, the path through them and, without ghosts, each copy's box. */
    public void renderWorld(ToolContext c, WorldDraw d) {
        List<BlockPos> nodes = points.nodes();
        d.seeThrough(true);
        for (int i = 1; i < nodes.size(); i++) {
            BlockPos a = nodes.get(i - 1), b = nodes.get(i);
            d.line(a.x() + 0.5, a.y() + 1, a.z() + 0.5, b.x() + 0.5, b.y() + 1, b.z() + 0.5, GenerateTool.PATH_COLOUR);
        }
        d.seeThrough(false);
        editor.render(d, GenerateTool.NODE_COLOUR, GenerateTool.SELECTED_COLOUR);
        Optional<Source> source = source(c, null);
        if (volume != null || source.isEmpty()) return;
        for (int i = 0; i < nodes.size(); i++) d.boxOutline(placement(source.get(), i).targetBox(), OUTLINE_COLOUR);
    }

    public List<KeyHint> hints(ToolContext c, String click) {
        GenerateTool.Services s = services.get();
        List<KeyHint> hints = new ArrayList<>();
        String[] why = {null};
        Optional<Source> source = source(c, why);
        if (source.isEmpty()) {
            if (why[0] != null) hints.add(KeyHint.text(why[0]));
            return hints;
        }
        hints.add(KeyHint.text("sculptory.hint.stamp.source", source.get().name()));
        if (points.isEmpty()) {
            hints.add(new KeyHint(click, "sculptory.hint.stamp.add"));
            hints.add(air.hint());
            return hints;
        }
        if (editor.busy()) {
            hints.addAll(editor.hints(click, s::keyLabel));
            hints.add(air.hint());
            return hints;
        }
        hints.add(new KeyHint(click, "sculptory.hint.stamp.add_or_select"));
        hints.addAll(editor.hints(click, s::keyLabel));
        hints.add(air.hint());
        hints.add(new KeyHint(s.keyLabel(KeyAction.ERASE_SELECTION) + "/" + s.keyLabel(KeyAction.REMOVE_NODE),
                "sculptory.hint.line.remove_point"));
        hints.add(new KeyHint(s.keyLabel(KeyAction.ROTATE_CW), "sculptory.hint.stamp.turn"));
        long blocks = source.get().cells() > Long.MAX_VALUE / points.size() ? Long.MAX_VALUE
                : source.get().cells() * points.size();
        hints.add(new KeyHint(s.keyLabel(KeyAction.COMMIT), "sculptory.hint.stamp.build",
                List.of(Integer.toString(points.size()), SessionNotices.count(blocks))));
        hints.add(new KeyHint("Esc", "sculptory.hint.line.clear"));
        return hints;
    }
}
