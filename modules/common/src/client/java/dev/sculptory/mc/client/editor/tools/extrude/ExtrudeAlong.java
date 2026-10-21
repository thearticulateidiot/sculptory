package dev.sculptory.mc.client.editor.tools.extrude;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.generate.FaceCells;
import dev.sculptory.core.generate.PathExtrudeKernel;
import dev.sculptory.core.generate.TaperKernel;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.mc.client.editor.input.KeyAction;
import dev.sculptory.mc.client.editor.settings.SettingsValues;
import dev.sculptory.mc.client.editor.tool.EditorAction;
import dev.sculptory.mc.client.editor.tool.FrameInfo;
import dev.sculptory.mc.client.editor.tool.KeyHint;
import dev.sculptory.mc.client.editor.tool.PointerEvent;
import dev.sculptory.mc.client.editor.tool.ScrollEvent;
import dev.sculptory.mc.client.editor.tool.ToolContext;
import dev.sculptory.mc.client.editor.tool.WorldCursor;
import dev.sculptory.mc.client.editor.tool.WorldDraw;
import dev.sculptory.mc.client.editor.tools.generate.AirPoints;
import dev.sculptory.mc.client.editor.tools.generate.GenerateTool;
import dev.sculptory.mc.client.editor.tools.generate.LineDraft;
import dev.sculptory.mc.client.session.Notice;
import dev.sculptory.mc.client.session.SessionNotices;
import dev.sculptory.protocol.v2.OpLabel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The Extrude tool's Along a path and To a point directions ({@link ExtrudeSettings.Direction}): a click locks the
 * hovered face, then clicks on blocks shape what it becomes, previewed as a ghost and built with Enter as one generated
 * paste ({@link LineDraft}, as Generate's Line).
 *
 * <ul>
 *   <li><b>Along a path</b>: the clicked blocks are path points; the path starts at the face's centre and runs Straight,
 *   Curve or Hanging through them. The face is carried along it ({@link PathExtrudeKernel}); one undo step named
 *   "Extrude path".</li>
 *   <li><b>To a point</b>: the clicked block is the tip (a click elsewhere moves it); the face tapers to it
 *   ({@link TaperKernel}); one undo step named "Taper".</li>
 * </ul>
 * Once the face is locked, a click on open sky puts a point (or the tip) at the air marker ({@link AirPoints}; Alt+Scroll
 * sets how far ahead it floats). Points are edited as the Line's are (the shared point editor: gizmo, insert on the path,
 * E, Backspace); Esc deselects, then clears them, then lets go of the face.
 * Face blocks holding a block entity (chests, signs) can't travel in a generated paste: they are left out, and a toast
 * counts them when the face is locked. Client thread only.
 */
final class ExtrudeAlong {
    public static final String NEEDS_POINT = "sculptory.notice.extrude_needs_point";
    public static final String NEEDS_TIP = "sculptory.notice.extrude_needs_tip";
    public static final String TIP_BEHIND = "sculptory.notice.extrude_tip_behind";
    public static final String LEFT_OUT = "sculptory.notice.extrude_left_out";
    public static final String OP_PATH = "sculptory.op.extrude_path";
    public static final String SENT_PATH = "sculptory.notice.extrude_path_sent";
    public static final String OP_TAPER = "sculptory.op.extrude_taper";
    public static final String SENT_TAPER = "sculptory.notice.extrude_taper_sent";
    /** A generated paste writes every cell it holds, as Generate's Line does. */
    private static final PasteOptions OPTIONS = new PasteOptions(true, false, false);

    private final LineDraft path;
    private final LineDraft tip;
    private ExtrudeTool.Face locked;
    private FaceCells cells;
    /** The tip the last "behind the face" toast was for, so a preview made again does not repeat it. */
    private BlockPos toldBehind;

    /** Points (and the tip) in the air: one marker distance for both directions. */
    private final AirPoints air = new AirPoints();

    ExtrudeAlong(Supplier<GenerateTool.Services> services) {
        this.path = new LineDraft(services, ExtrudeTool.FACE_COLOUR,
                new LineDraft.Rules(1, PathSpec.MAX_POINTS - 1, NEEDS_POINT)).withAirPoints(air);
        this.tip = new LineDraft(services, ExtrudeTool.FACE_COLOUR, new LineDraft.Rules(1, 1, NEEDS_TIP))
                .withAirPoints(air);
    }

    /** Points in the air (the marker and its distance). */
    AirPoints air() {
        return air;
    }

    /** The locked face, if any. */
    Optional<ExtrudeTool.Face> lockedFace() {
        return Optional.ofNullable(locked);
    }

    /** The path's points, or the tip, and their preview (tests). */
    LineDraft draft(ExtrudeSettings.Direction direction) {
        return direction == ExtrudeSettings.Direction.TO_POINT ? tip : path;
    }

    /** Locks {@code face}: the clicks from now on shape it. Its block-entity blocks are counted in a toast. */
    void lock(ToolContext c, ExtrudeTool.Face face) {
        Objects.requireNonNull(face);
        clearDrafts(c);
        locked = face;
        cells = face.cells();
        toldBehind = null;
        StateSpace states = GenerateTool.statesOrNull(c);
        long leftOut = states == null ? 0 : cells.without(states).leftOut();
        if (leftOut > 0) c.notify(Notice.of(Notice.Level.INFO, LEFT_OUT, SessionNotices.count(leftOut)));
    }

    /** Lets go of the face: its points and ghost go (a build on its way still goes out). */
    void unlock(ToolContext c) {
        clearDrafts(c);
        locked = null;
        cells = null;
    }

    private void clearDrafts(ToolContext c) {
        for (LineDraft draft : List.of(path, tip)) {
            draft.hide(c);
            draft.points().clear();
            draft.changed();
        }
    }

    /** A setting changed: the preview is made again. */
    void changed() {
        path.changed();
        tip.changed();
    }

    void deactivate(ToolContext c) {
        path.deactivate(c);
        tip.deactivate(c);
    }

    /**
     * Once per frame while locked: the point editor (the air marker, guide line, hovering) and the preview (no marker
     * while no face is locked: a click on the sky locks nothing); a build on its way goes out either way.
     */
    void frame(ToolContext c, ExtrudeSettings.Direction direction, FrameInfo f) {
        if (locked != null) {
            draft(direction).frame(c, f, host(direction), WorldCursor::pos);
        } else {
            air.hide();
        }
        path.pollBuild(c);
        tip.pollBuild(c);
    }

    /** A click while locked: a point (or the tip), or a point picked to drag. */
    boolean onPointer(ToolContext c, PointerEvent e, ExtrudeSettings.Direction direction) {
        if (locked == null) return false;
        return draft(direction).onPointer(c, e, WorldCursor::pos);
    }

    /** Alt+Scroll while locked: the air marker, or the point dragged freely, nearer or farther. */
    boolean onScroll(ToolContext c, ScrollEvent e, ExtrudeSettings.Direction direction) {
        if (locked == null) return false;
        return draft(direction).onScroll(c, e);
    }

    /** Esc, Delete/Backspace and Enter while locked; Esc with nothing left to clear lets go of the face. */
    boolean onAction(ToolContext c, EditorAction a, ExtrudeSettings.Direction direction) {
        if (locked == null) return false;
        if (draft(direction).onAction(c, a, host(direction))) return true;
        if (a == EditorAction.CANCEL) {
            unlock(c);
            return true;
        }
        // No point left to remove: Delete and Backspace stop here, so they never erase the selection behind the tool.
        return a == EditorAction.ERASE_SELECTION || a == EditorAction.REMOVE_NODE;
    }

    private LineDraft.Host host(ExtrudeSettings.Direction direction) {
        return direction == ExtrudeSettings.Direction.TO_POINT ? this::taperSetup : this::pathSetup;
    }

    /** The face carried along the clicked points, with the settings as they are now. */
    private LineDraft.Setup pathSetup(ToolContext c, List<BlockPos> points) {
        StateSpace states = GenerateTool.statesOrNull(c);
        if (states == null || cells == null) return null;
        SettingsValues values = c.settings();
        PathKind kind = values.get(ExtrudeSettings.PATH);
        PathExtrudeKernel.Spec spec = new PathExtrudeKernel.Spec(cells, points, kind,
                kind == PathKind.HANGING ? values.get(ExtrudeSettings.SAG) : 0, values.get(ExtrudeSettings.FOLLOW_SLOPE));
        int[] height = height(c);
        return new LineDraft.Setup(spec.path(),
                cap -> PathExtrudeKernel.generate(spec, states, cap, height[0], height[1]).source(),
                OpLabel.PATH_EXTRUDE, OP_PATH, SENT_PATH, OPTIONS);
    }

    /** The face tapered to the clicked tip; refused with a toast when the tip is not in front of the face. */
    private LineDraft.Setup taperSetup(ToolContext c, List<BlockPos> points) {
        StateSpace states = GenerateTool.statesOrNull(c);
        if (states == null || cells == null) return null;
        BlockPos at = points.get(0);
        TaperKernel.Spec spec = new TaperKernel.Spec(cells, at, c.settings().get(ExtrudeSettings.TAPER));
        if (spec.layers() < 1) {
            if (!at.equals(toldBehind)) c.notify(Notice.of(Notice.Level.INFO, TIP_BEHIND));
            toldBehind = at;
            return null;
        }
        int[] height = height(c);
        return new LineDraft.Setup(new PathSpec(List.of(cells.centre(), at), PathKind.STRAIGHT),
                cap -> TaperKernel.generate(spec, states, cap, height[0], height[1]).source(),
                OpLabel.TAPER, OP_TAPER, SENT_TAPER, OPTIONS);
    }

    private static int[] height(ToolContext c) {
        WorldReader world = GenerateTool.worldOrNull(c);
        return world == null ? new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}
                : new int[] {world.bottomY(), world.topYExclusive()};
    }

    // ---- Drawing and hints ----

    void renderWorld(WorldDraw d, ExtrudeSettings.Direction direction) {
        if (locked == null) return;
        d.shapeOutlines(List.of(locked.region()), ExtrudeTool.FACE_COLOUR, ExtrudeTool.FACE_COLOUR);
        draft(direction).renderWorld(d, false);
    }

    /** The hints while a face is locked (the tool gives the others). */
    List<KeyHint> hints(ExtrudeSettings.Direction direction, Supplier<GenerateTool.Services> services) {
        List<KeyHint> hints = new ArrayList<>();
        String click = ExtrudeTool.CLICK;
        if (direction == ExtrudeSettings.Direction.ALONG_PATH) {
            hints.add(KeyHint.text("sculptory.hint.extrude.locked", SessionNotices.count(locked.count())));
            hints.addAll(path.hints(click, "sculptory.hint.extrude.build_path"));
            if (path.points().isEmpty()) hints.add(new KeyHint("Esc", "sculptory.hint.extrude.unlock"));
            return hints;
        } else {
            LineDraft.Built built = tip.preview().orElse(null);
            hints.add(new KeyHint(click, tip.points().isEmpty() ? "sculptory.hint.extrude.click_tip"
                    : "sculptory.hint.extrude.move_tip"));
            hints.add(air.hint());
            if (built != null && built.tooLarge()) {
                hints.add(KeyHint.text("sculptory.hint.generate.too_large", SessionNotices.count(built.cap())));
            } else if (built != null && !built.source().isEmpty()) {
                hints.add(new KeyHint(services.get().keyLabel(KeyAction.COMMIT), "sculptory.hint.extrude.build_taper",
                        List.of(SessionNotices.count(built.cells()))));
            }
        }
        hints.add(new KeyHint("Esc", tip.points().isEmpty() ? "sculptory.hint.extrude.unlock" : "sculptory.hint.extrude.clear_tip"));
        return hints;
    }
}
