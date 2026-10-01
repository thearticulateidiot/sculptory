package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.ShapeStamp;
import dev.sculptory.core.brush.ShapeSweep;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.brush.BrushPredictor;
import dev.sculptory.fabric.client.editor.brush.StrokeController;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.RayOverlay;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolDescriptor;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.generate.GenerateTool;
import dev.sculptory.fabric.client.editor.tools.generate.LineDraft;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.FabricStrokeHandle;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.StrokeHandle;
import dev.sculptory.fabric.client.session.StrokeParams;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.protocol.v2.ProtocolV2;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The Shape brush (palette slot 10, key 0; the {@code brush} permission): a click places a sphere, cylinder, cone or
 * cube of the active block (or a weighted mix) at the cursor, and a drag paints shapes along the drag. One press is
 * one server stroke of {@link BrushTool#SHAPE} (one undo step, "Shape · N blocks"), predicted at once like the terrain
 * brushes: the same kernel runs on the client ({@link BrushPredictor}) and the server, so the server writes exactly
 * what the player saw. A shape of more than {@link TerrainBrushTool#MAX_PREDICTED_CELLS} cells (copies counted) runs on
 * the server only; its outline is drawn faded.
 *
 * <p><b>Where.</b> Each dab is the centre of the shape's box ({@link ShapeAnchor}): centred on the block under the
 * cursor, or resting on the face the cursor is on. "Clicked face" takes the facing from the face the press begins on,
 * for the whole press. Along a drag the shapes are laid every quarter of the shape's shortest side
 * ({@link ShapeAnchor#spacing}), each on the grid its box needs.
 *
 * <p><b>Click or drag.</b> A press places its first shape where it began, and paints along the cursor only once the
 * cursor has moved: its ray turned more than {@value #DRAG_START_DEGREES}° from the press's, or the camera moved more
 * than {@value #DRAG_START_BLOCKS} blocks. So a click held still, or with a shaking hand, places one shape however long
 * it is held. For the whole press the cursor ray sees the world as it was before the press wrote ({@link #rayOverlay},
 * {@link PressSnapshot}, taken just before each dab goes out): the shapes the press placed, copies included, predicted
 * or written by the server, never become the surface its next shape goes on.
 *
 * <p><b>Keys.</b> Ctrl+Scroll changes the radius (Shift ×4); Alt+Scroll the height of a cylinder, cone or cube (which
 * turns "Height = diameter" off); middle-click picks the active block, or with "Blocks: Mix" adds the block to the mix;
 * M sets the symmetry centre the brushes share; Esc stops the stroke. A settings change mid-press restarts the server
 * stroke with the new settings, as for the terrain brushes. With "Blocks: Mix" the mix goes out as its Pattern lays it
 * out ({@link ShapeSettings#PATTERN}); with the Gradient, Alt+drag draws the
 * line ({@link GradientDrag}) instead of placing, and a press without a line is refused with a toast.
 *
 * <p><b>Cursor.</b> The exact outline of the shape a click would place, and with symmetry each copy's (mirrored or
 * turned, at the height of the dab: the Shape brush's copies do not follow the terrain) with the centre and planes,
 * drawn as the Select tool's shapes are ({@link WorldDraw#shapeOutlines}: meshed off the render thread once per size and
 * facing, then only moved); a cube is its box. "Only inside selection" and symmetry work as for the terrain brushes
 * ({@link BrushPress}).
 *
 * <p><b>Line</b> ("Draw: Line"). Clicks add points instead ({@link LineDraft}:
 * select, drag, Backspace, Esc as Generate's Line); the current shape, its size, hollow and mode are swept along the
 * Straight, Curve or Hanging path through them ({@code ShapeSweep}), previewed as a ghost, and Enter builds the sweep as
 * one generated paste ("Shape line"): Place into everything, Air only into air, Paint over blocks, Carve as air. It
 * needs {@code region} and {@code clipboard} as Generate does; symmetry is not used; "Only inside selection" keeps the
 * cells inside the selection box.
 *
 * <p><b>Pacing.</b> A dab costs its work units ({@link ShapeStamp#units}: its placements, one unit per
 * {@value ShapeStamp#WORK_UNIT_CELLS} cells each, at most the whole window) in the dab rate and the in-flight window, and
 * stays in the window until the server reports it written ({@code StrokeStatus.appliedIndex}), across presses: a new
 * press's dabs wait for the last press's large shapes. So large shapes go out as fast as the server writes them, and a
 * fast drag waits instead of being refused. Shapes the server is still writing and the player could not see predicted
 * are drawn as faded boxes until they are written. Client thread only.
 */
public final class ShapeBrushTool implements Tool {
    /** The Shape brush's colour (ARGB). */
    public static final int COLOR = TerrainBrushTool.color(BrushTool.SHAPE);
    public static final String CLICK = "LMB";
    /**
     * How far the cursor ray must turn from the press's (degrees) before a press paints along the drag: about 6 pixels
     * of a 1600-pixel-high window at the default field of view, more than a hand shakes on a click.
     */
    public static final double DRAG_START_DEGREES = 0.25;
    /** How far the camera must move during a press (blocks) before it paints along: flying with the button held. */
    public static final double DRAG_START_BLOCKS = 0.25;
    private static final double DRAG_START_COS = Math.cos(Math.toRadians(DRAG_START_DEGREES));
    /** Line mode: the confirmation's name, the notice once sent, the build hint. */
    public static final String OP_LINE = "sculptory.op.shape_line";
    public static final String SENT_LINE = "sculptory.notice.shape_line_sent";
    public static final String BUILD_LINE_HINT = "sculptory.hint.shape_line.build";
    /** Line mode: the seed of a mix, fixed so the preview and each build of the same line agree. */
    static final long LINE_SEED = 0x5EEDL;

    /**
     * A material fixed by another tool built on this brush (the Fluid tool's ball): the pattern a stroke begun in
     * {@code c} places, or {@code null} (after telling the player) when there is none.
     */
    @FunctionalInterface
    public interface MaterialSource {
        Pattern material(ToolContext c);
    }

    private final ToolDescriptor descriptor;
    private final BrushServices services;
    private final StrokeController controller;
    private final BrushPress press;
    /** The Gradient pattern's line (shared through the symmetry centre) and its Alt+drag. */
    private final GradientLine gradientLine;
    private final GradientDrag gradient;
    private final Supplier<BlockDescriptor> activeBlock;
    /** The fixed material of a preset, or {@code null} for the settings' blocks (the active block or the mix). */
    private final MaterialSource fixedMaterial;
    /** Line mode ("Draw: Line"): the points, the preview and the build, with Generate's services once the editor sets them. */
    private final LineDraft line;
    private GenerateTool.Services lineServices = GenerateTool.Services.headless();
    /** Whether the last frame was in line mode (leaving it drops the line's ghost; the points stay). */
    private boolean wasLine;
    /** The selection box the line was last clipped to ("Only inside selection"), or null. */
    private Box lineClip;
    /** The last shape counted ({@link #cells}): its count does not depend on the facing. */
    private CountKey counted;
    private long countedCells;

    private ToolContext context;
    private WorldCursor cursor = WorldCursor.miss(0, 0, 0);
    /** The block the cursor last hit. */
    private BlockPos aimPos;

    // The current press.
    /** The facing of the press: the fixed one, or the face the press began on; {@code null} until the cursor hits. */
    private Facing pressFacing;
    /** The world as it was before the press wrote: what the press's cursor ray sees. */
    private final PressSnapshot before = new PressSnapshot();
    /** The cursor ray when the press began, or {@code null} without one. */
    private Ray pressRay;
    /** The press's first hit, where it aims until the cursor moves; {@code null} until the cursor hits. */
    private WorldCursor anchor;
    /** Whether the cursor has moved since the press began: the press paints along the drag. */
    private boolean dragging;
    /** Whether the controller has the anchor's point: until the cursor moves, it keeps it. */
    private boolean anchorAimed;
    private long seed;
    private BrushSpec strokeSpec;
    private BrushPredictor predictor;
    /** What one dab of the stroke in progress costs in the dab rate and the in-flight window. */
    private int strokeUnits = 1;
    /** Strokes begun whose dabs the server may still be writing, oldest first (the current one last). */
    private final List<Sent> sent = new ArrayList<>();

    /** A stroke begun: its dabs' cost in the window, and whether the player saw them predicted. */
    private record Sent(FabricStrokeHandle handle, int units, boolean predicted) {}

    /**
     * @param centre the symmetry centre, shared with the terrain brushes
     * @param activeBlock the editor's active block (the top bar's), placed unless the blocks are set to the mix
     */
    public ShapeBrushTool(BrushServices services, SymmetryCentre centre, Supplier<BlockDescriptor> activeBlock) {
        this(services, centre, activeBlock, new ToolDescriptor(ToolId.SHAPE, "sculptory.tool.shape",
                TerrainBrushTool.icon(BrushTool.SHAPE), Perm.BRUSH), null);
    }

    /**
     * The stroke machinery for a tool built on the Shape brush: strokes begin under {@code descriptor}'s id and place {@code material}'s pattern instead of the
     * settings' blocks; the shape, radius, anchor, mode and symmetry still come from the {@link ShapeSettings} the
     * owning tool's context answers with. Middle-click is left to the editor.
     */
    public ShapeBrushTool(BrushServices services, SymmetryCentre centre, ToolDescriptor descriptor, MaterialSource material) {
        this(services, centre, () -> null, descriptor, Objects.requireNonNull(material));
    }

    private ShapeBrushTool(BrushServices services, SymmetryCentre centre, Supplier<BlockDescriptor> activeBlock,
                           ToolDescriptor descriptor, MaterialSource fixedMaterial) {
        this.services = Objects.requireNonNull(services);
        this.press = new BrushPress(Objects.requireNonNull(centre), services);
        this.gradientLine = centre.gradientLine();
        this.gradient = new GradientDrag(gradientLine);
        this.activeBlock = Objects.requireNonNull(activeBlock);
        this.descriptor = Objects.requireNonNull(descriptor);
        this.fixedMaterial = fixedMaterial;
        this.controller = new StrokeController(new Host(), false);
        this.line = new LineDraft(() -> lineServices, COLOR);
    }

    /** The stroke logic (for tests and diagnostics). */
    public StrokeController controller() {
        return controller;
    }

    /** The spec of the server stroke in progress, or {@code null}. */
    public BrushSpec strokeSpec() {
        return strokeSpec;
    }

    /** The prediction of the server stroke in progress, or {@code null} when it is not predicted. */
    public BrushPredictor predictor() {
        return predictor;
    }

    /** Line mode's points and preview (for tests and the HUD). */
    public LineDraft line() {
        return line;
    }

    /** The ghosts, background work and confirmations Line mode builds with (the editor gives Generate's). */
    public void setLineServices(GenerateTool.Services services) {
        this.lineServices = Objects.requireNonNull(services);
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public SettingsSchema schema() {
        return ShapeSettings.SCHEMA;
    }

    @Override
    public RaycastMode raycastMode(SettingsValues s) {
        return RaycastMode.TERRAIN;
    }

    /** While pressing: the world as it was before the press wrote, so the cursor looks through the press's shapes. */
    @Override
    public RayOverlay rayOverlay() {
        return controller.pressed() && !before.isEmpty() ? before : null;
    }

    @Override
    public void activate(ToolContext c) {
        context = c;
    }

    @Override
    public void deactivate(ToolContext c, DeactivateReason r) {
        if (controller.pressed()) {
            endPress(c, r == DeactivateReason.SWITCHED_TOOL
                    ? StrokeController.EndReason.TOOL_SWITCHED
                    : StrokeController.EndReason.EDITOR_EXITED);
        }
        gradient.cancel(c);
        line.deactivate(c);
        c.setPointerCapture(false);
        context = null;
    }

    @Override
    public boolean onPointer(ToolContext c, PointerEvent e) {
        if (lineMode(c.settings()) && !controller.pressed()) {
            cursor = e.cursor();
            return line.onPointer(c, e, hit -> linePoint(c, hit));
        }
        switch (e.kind()) {
            case PRESS -> {
                if (e.button() != PointerEvent.LEFT) {
                    return false;
                }
                aim(c, e.cursor());
                // A mix with a Gradient: Alt+drag draws the line instead of placing shapes.
                if (drawsGradientLine(c.settings()) && gradient.press(c, e)) {
                    return true;
                }
                beginPress(c);
                return true;
            }
            case DRAG -> {
                if (gradient.active()) {
                    gradient.drag(e);
                    aim(c, e.cursor());
                    return true;
                }
                if (e.button() != PointerEvent.LEFT || !controller.pressed()) {
                    return false;
                }
                aim(c, e.cursor());
                return true;
            }
            case RELEASE -> {
                if (gradient.active() && e.button() == PointerEvent.LEFT) {
                    gradient.release(c, e);
                    aim(c, e.cursor());
                    return true;
                }
                if (e.button() != PointerEvent.LEFT || !controller.pressed()) {
                    return false;
                }
                aim(c, e.cursor());
                endPress(c, StrokeController.EndReason.RELEASED);
                return true;
            }
            case MOVE -> {
                aim(c, e.cursor());
                return false;
            }
        }
        return false;
    }

    @Override
    public void frame(ToolContext c, FrameInfo f) {
        boolean lineNow = lineMode(c.settings());
        if (wasLine && !lineNow) line.hide(c);
        wasLine = lineNow;
        if (lineNow && !controller.pressed()) {
            cursor = f.cursor();
            // "Only inside selection" follows the selection box as it changes.
            Box clip = c.settings().get(ShapeSettings.INSIDE_SELECTION) ? c.selection().orElse(null) : null;
            if (!Objects.equals(clip, lineClip)) {
                lineClip = clip;
                line.changed();
            }
            line.frame(c, this::lineSetup);
            return;
        }
        line.pollBuild(c);
        aim(c, f.cursor());
        if (controller.pressed()) {
            if (!services.windowFocused()) {
                endPress(c, StrokeController.EndReason.FOCUS_LOST);
            } else {
                controller.update(f.nanoTime());
            }
        }
    }

    @Override
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        if (e.amount() == 0) {
            return false;
        }
        KeyAction action = services.scrollAction(e.modifiers()).orElse(null);
        int direction = e.amount() > 0 ? 1 : -1;
        SettingsValues values = c.settings();
        Limits limits = limits(c);
        if (action == KeyAction.TOOL_SIZE) {
            int step = direction * (Modifiers.shift(e.modifiers()) ? 4 : 1);
            int max = Math.max(BrushSpec.MIN_RADIUS, Math.min(BrushSpec.MAX_RADIUS, limits.maxBrushRadius()));
            int radius = Math.max(BrushSpec.MIN_RADIUS, Math.min(max, values.get(ShapeSettings.RADIUS) + step));
            c.updateSettings(values.with(ShapeSettings.RADIUS, radius));
            return true;
        }
        if (action == KeyAction.TOOL_STRENGTH && ShapeSettings.usesHeight(values)) {
            // The height, from the one the shape has now (the diameter while "Height = diameter" is on).
            int height = Math.max(ShapeSpec.MIN_HEIGHT, Math.min(ShapeSettings.maxHeight(limits),
                    ShapeSettings.height(values, limits) + direction));
            c.updateSettings(values.with(ShapeSettings.HEIGHT_AUTO, false).with(ShapeSettings.HEIGHT, height));
            return true;
        }
        return false;
    }

    @Override
    public boolean onAction(ToolContext c, EditorAction a) {
        if (lineMode(c.settings()) && !controller.pressed()) {
            switch (a) {
                case CANCEL, ERASE_SELECTION, REMOVE_NODE, COMMIT -> {
                    return line.onAction(c, a, this::lineSetup);
                }
                default -> {
                    // The eyedropper and the symmetry centre work as when placing shapes.
                }
            }
        }
        switch (a) {
            case CANCEL -> {
                if (gradient.cancel(c)) {
                    return true;
                }
                if (!controller.pressed()) {
                    return false;
                }
                endPress(c, StrokeController.EndReason.CANCELLED);
                return true;
            }
            case EYEDROPPER -> {
                // With the active block (or a fixed material), the editor's own eyedropper sets it (with its toast).
                if (fixedMaterial != null || !usesMix(c.settings())) {
                    return false;
                }
                pickIntoMix(c);
                return true;
            }
            case SET_SYMMETRY_CENTRE -> {
                press.setCentre(c, cursor, c.settings().get(ShapeSettings.SYMMETRY) != Symmetry.Mode.OFF);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public void onSettingsChanged(ToolContext c, SettingsValues before, SettingsValues after) {
        line.changed();
        if (controller.pressed()) {
            ShapeSpec shape = shapeOf(after, limits(c), facingFor(after, cursor.missed() ? null : cursor.face()));
            int radius = ShapeSettings.radius(after, limits(c));
            controller.setRadius(reach(shape, radius));
            controller.setSpacing(ShapeAnchor.spacing(shape, radius));
            controller.setDabCost(units(shape, radius, after.get(ShapeSettings.SYMMETRY)));
            controller.requestRestart();
        }
    }

    @Override
    public void renderWorld(ToolContext c, WorldDraw d) {
        if (strokeSpec != null && strokeSpec.clip() != null) {
            d.boxOutline(strokeSpec.clip(), COLOR);
        }
        drawUnwritten(d);
        SettingsValues values = c.settings();
        if (lineMode(values) && !controller.pressed()) {
            renderLine(c, d, values);
            return;
        }
        if (drawsGradientLine(values)) {
            gradient.render(d, COLOR);
        }
        Limits limits = limits(c);
        Symmetry symmetry = press.shown(c, values.get(ShapeSettings.SYMMETRY), controller.pressed(), aimPos);
        int radius = ShapeSettings.radius(values, limits);
        if (!symmetry.isOff()) {
            BrushOutlines.symmetry(d, symmetry, cursor.missed() ? (aimPos == null ? 0 : aimPos.y() + 1) : cursor.hitY(),
                    radius, COLOR);
        }
        if (cursor.missed()) {
            return;
        }
        Facing facing = facingFor(values, cursor.face());
        ShapeSpec shape = shapeOf(values, limits, facing);
        double[] point = ShapeAnchor.point(shape, radius, facing, values.get(ShapeSettings.ANCHOR), cursor.pos(),
                cursor.face());
        List<ShapeStamp.Placement> placements;
        try {
            placements = ShapeStamp.placements(shape, radius, symmetry,
                    Dab.of(0, point[0], point[1], point[2], Dab.FULL_PRESSURE));
        } catch (IllegalArgumentException outOfRange) {
            return;
        }
        boolean serverOnly = cells(shape, radius) * symmetry.mode().copies() > TerrainBrushTool.MAX_PREDICTED_CELLS;
        int own = serverOnly ? OverlayColors.scaleAlpha(COLOR, 0.45) : COLOR;
        int copies = OverlayColors.scaleAlpha(own, 0.7);
        if (shape.kind() == ShapeSpec.Kind.CUBE) {
            d.seeThrough(true);
            for (int i = 0; i < placements.size(); i++) d.boxOutline(placements.get(i).box(), i == 0 ? own : copies);
            d.seeThrough(false);
            return;
        }
        List<Region> regions = new ArrayList<>(placements.size());
        for (ShapeStamp.Placement placement : placements) regions.add(placement.region());
        d.shapeOutlines(regions, own, copies);
    }

    /**
     * Line mode: the points, the path and the ghost ({@link LineDraft}; a carve's bounds, since air shows no ghost), and
     * the outline of the shape a click would sweep from there.
     */
    private void renderLine(ToolContext c, WorldDraw d, SettingsValues values) {
        line.renderWorld(d, values.get(ShapeSettings.MODE) == ShapeSpec.Mode.CARVE);
        if (cursor.missed()) {
            return;
        }
        Limits limits = limits(c);
        Facing facing = lineFacing(values, cursor.face());
        ShapeSpec shape = shapeOf(values, limits, facing);
        int radius = ShapeSettings.radius(values, limits);
        BlockPos point = linePoint(c, cursor);
        ShapeStamp.Placement placement = new ShapeStamp.Placement(ShapeStamp.box(shape, radius, facing,
                16L * point.x() + 8, 16L * point.y() + 8, 16L * point.z() + 8), shape.kind(), facing);
        int faded = OverlayColors.scaleAlpha(COLOR, 0.6);
        if (shape.kind() == ShapeSpec.Kind.CUBE) {
            d.seeThrough(true);
            d.boxOutline(placement.box(), faded);
            d.seeThrough(false);
            return;
        }
        d.shapeOutlines(List.of(placement.region()), faded, faded);
    }

    @Override
    public List<KeyHint> hints(ToolContext c) {
        List<KeyHint> hints = new ArrayList<>();
        SettingsValues values = c.settings();
        if (lineMode(values) && !controller.pressed()) {
            try {
                GenerateTool.missingNode(c.session().permissions())
                        .ifPresent(node -> hints.add(KeyHint.text(GenerateTool.NEEDS_PERMISSION_HINT, node.node())));
            } catch (IllegalStateException noSession) {
                // No session: nothing can be built anyway.
            }
            hints.addAll(line.hints(CLICK, BUILD_LINE_HINT));
            hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.brush.radius"));
            return hints;
        }
        if (!controller.pressed() && values.get(ShapeSettings.INSIDE_SELECTION) && c.selection().isEmpty()) {
            hints.add(KeyHint.text(TerrainBrushTool.NEEDS_SELECTION));
        }
        if (controller.pressed()) {
            hints.add(new KeyHint("Esc", "sculptory.hint.cancel_drag"));
        } else {
            hints.add(new KeyHint(CLICK, values.get(ShapeSettings.MODE) == ShapeSpec.Mode.CARVE
                    ? "sculptory.hint.shape.carve" : "sculptory.hint.shape.place"));
            if (values.get(ShapeSettings.MODE) != ShapeSpec.Mode.CARVE) {
                hints.add(new KeyHint(services.keyLabel(KeyAction.EYEDROPPER), usesMix(values)
                        ? "sculptory.hint.brush.add_block" : "sculptory.hint.shape.pick_block"));
            }
            if (drawsGradientLine(values)) {
                hints.add(new KeyHint(TerrainBrushTool.GRADIENT_DRAG, "sculptory.hint.pattern.draw_line"));
            }
        }
        hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.brush.radius"));
        if (ShapeSettings.usesHeight(values)) {
            hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_STRENGTH), "sculptory.hint.shape.height"));
        }
        if (!controller.pressed() && values.get(ShapeSettings.SYMMETRY) != Symmetry.Mode.OFF) {
            hints.add(new KeyHint(services.keyLabel(KeyAction.SET_SYMMETRY_CENTRE), "sculptory.hint.brush.symmetry_centre"));
        }
        return hints;
    }

    // ---- Presses ----

    private void beginPress(ToolContext c) {
        SettingsValues values = c.settings();
        if (drawsGradientLine(values) && !gradientLine.isSet()) {
            // A Gradient runs along its line: none drawn yet, so nothing to place.
            c.notify(Notice.of(Notice.Level.WARNING, GradientDrag.NO_LINE));
            return;
        }
        press.begin(c.selection());
        if (values.get(ShapeSettings.INSIDE_SELECTION) && press.clip(c) == null) {
            press.end();
            return;
        }
        seed = services.nextSeed();
        strokeSpec = null;
        predictor = null;
        pressFacing = cursor.missed() ? null : ShapeAnchor.facing(values.get(ShapeSettings.FACING), cursor.face());
        before.clear();
        pressRay = services.cursorRay().orElse(null);
        anchor = cursor.missed() ? null : cursor;
        dragging = false;
        anchorAimed = false;
        Limits limits = limits(c);
        int radius = ShapeSettings.radius(values, limits);
        ShapeSpec shape = shapeOf(values, limits, pressFacing == null ? Facing.UP : pressFacing);
        controller.setSnap(null);
        controller.press(reach(shape, radius), limits.maxDabRate(), units(shape, radius, values.get(ShapeSettings.SYMMETRY)));
        controller.setSpacing(ShapeAnchor.spacing(shape, radius));
        c.setPointerCapture(true);
    }

    private void endPress(ToolContext c, StrokeController.EndReason reason) {
        controller.end(reason);
        c.setPointerCapture(false);
        strokeSpec = null;
        predictor = null;
        pressFacing = null;
        before.clear();
        pressRay = null;
        anchor = null;
        dragging = false;
        anchorAimed = false;
        press.end();
    }

    /** Follows the cursor: the point a dab would take there goes to the stroke controller. */
    private void aim(ToolContext c, WorldCursor next) {
        cursor = next;
        WorldCursor target = controller.pressed() ? pressTarget(next) : next;
        boolean holding = controller.pressed() && !dragging && target == anchor;
        if (holding && anchorAimed && controller.stroke() != null) {
            // Held still after the first shape went out: the press keeps its point, whatever the settings (a radius)
            // do meanwhile. Before it goes out (waiting for earlier shapes to be written), the point follows them.
            return;
        }
        anchorAimed = holding;
        if (target.missed()) {
            controller.clearAim();
            return;
        }
        aimPos = target.pos();
        SettingsValues values = c.settings();
        if (controller.pressed() && pressFacing == null) {
            pressFacing = ShapeAnchor.facing(values.get(ShapeSettings.FACING), target.face());
        }
        Limits limits = limits(c);
        Facing facing = facingFor(values, target.face());
        int radius = ShapeSettings.radius(values, limits);
        double[] point = ShapeAnchor.point(shapeOf(values, limits, facing), radius, facing,
                values.get(ShapeSettings.ANCHOR), target.pos(), target.face());
        controller.aim(point[0], point[1], point[2]);
    }

    /**
     * Where a press aims: its first hit until the cursor moves ({@link #moved}), so a click held still, or with a
     * shaking hand, places one shape; the cursor from then on. The cursor sees the world as it was before the press
     * wrote ({@link #rayOverlay}), so neither is ever on a shape of the press.
     */
    private WorldCursor pressTarget(WorldCursor next) {
        if (!dragging && moved(next)) {
            dragging = true;
        }
        if (dragging) {
            return next;
        }
        if (anchor == null && !next.missed()) {
            anchor = next;
        }
        return anchor == null ? next : anchor;
    }

    /**
     * Whether the cursor has moved since the press began: its ray turned more than {@value #DRAG_START_DEGREES}° or
     * moved more than {@value #DRAG_START_BLOCKS} blocks. A pick without a ray (no camera for a moment) is no move.
     * Without a ray when the press began (tools built without the game) any hit other than the press's first is a move.
     */
    private boolean moved(WorldCursor next) {
        Ray now = services.cursorRay().orElse(null);
        if (pressRay == null) {
            return anchor != null && !anchor.equals(next);
        }
        if (now == null) {
            return false;
        }
        double turned = pressRay.dirX() * now.dirX() + pressRay.dirY() * now.dirY() + pressRay.dirZ() * now.dirZ();
        double dx = now.originX() - pressRay.originX();
        double dy = now.originY() - pressRay.originY();
        double dz = now.originZ() - pressRay.originZ();
        return turned < DRAG_START_COS || dx * dx + dy * dy + dz * dz > DRAG_START_BLOCKS * DRAG_START_BLOCKS;
    }

    /**
     * {@code dab} is about to go out: the sections its shape and copies can write (inside the clip box of "Only inside
     * selection") are kept as they are now for the press's cursor ray ({@link PressSnapshot}), before anything of it is
     * predicted or written.
     */
    private void keepBefore(Dab dab) {
        ToolContext c = context;
        WorldReader world = c == null ? null : BrushPress.worldOrNull(c);
        if (world == null || strokeSpec == null) {
            return;
        }
        List<ShapeStamp.Placement> placements;
        try {
            placements = ShapeStamp.placements(strokeSpec, dab);
        } catch (IllegalArgumentException outOfRange) {
            // A copy beyond the world: the server refuses the dab.
            return;
        }
        Box clip = strokeSpec.clip();
        for (ShapeStamp.Placement placement : placements) {
            Box box = placement.box();
            if (clip != null) {
                if (!box.intersects(clip)) {
                    continue;
                }
                box = new Box(
                        new BlockPos(Math.max(box.min().x(), clip.min().x()), Math.max(box.min().y(), clip.min().y()),
                                Math.max(box.min().z(), clip.min().z())),
                        new BlockPos(Math.min(box.max().x(), clip.max().x()), Math.min(box.max().y(), clip.max().y()),
                                Math.min(box.max().z(), clip.max().z())));
            }
            before.cover(world, box);
        }
    }

    /** The facing now: the press's while pressing, else the setting's (the face under the cursor for "Clicked face"). */
    private Facing facingFor(SettingsValues values, WorldCursor.Face face) {
        if (controller.pressed() && pressFacing != null) {
            return pressFacing;
        }
        ShapeSettings.FacingChoice choice = values.get(ShapeSettings.FACING);
        if (choice.facing() != null) {
            return choice.facing();
        }
        return face == null ? Facing.UP : ShapeAnchor.facing(choice, face);
    }

    /** Begins a server stroke with the settings as they are now (the controller calls this). */
    private StrokeHandle beginServerStroke() {
        ToolContext c = context;
        if (c == null) {
            return null;
        }
        BrushSpec spec = buildSpec(c);
        if (spec == null) {
            return null;
        }
        EditorSession session;
        try {
            session = c.session();
        } catch (IllegalStateException noSession) {
            return null;
        }
        int cap = TerrainBrushTool.MAX_PREDICTED_CELLS;
        boolean predict = BrushPredictor.estimatedCells(spec) <= cap;
        StrokeHandle handle = session.beginStroke(descriptor.id(), spec, new StrokeParams(predict, cap));
        if (!handle.active()) {
            return null;
        }
        strokeSpec = spec;
        predictor = null;
        strokeUnits = units(spec.shapeSpec(), spec.radius(), spec.symmetry().mode());
        if (handle instanceof FabricStrokeHandle fabric) sent.add(new Sent(fabric, strokeUnits, predict));
        // Every dab of this stroke lands on the grid its shape's box needs.
        controller.setSnap(ShapeAnchor.snap(spec.shapeSpec(), spec.radius(), spec.shapeSpec().facing()));
        if (predict && handle instanceof FabricStrokeHandle fabric) {
            BrushPredictor.Target target = services.predictionTarget();
            WorldReader world = BrushPress.worldOrNull(c);
            if (target != null && world != null) {
                BrushPredictor next = new BrushPredictor(spec, world, target);
                fabric.setBatchHook((stroke, batch) -> next.predict(batch));
                predictor = next;
            }
        }
        return handle;
    }

    /**
     * The spec the settings give now, with this press's facing, seed, clip box and symmetry; {@code null} (after telling
     * the player) when the blocks can't be used, "Only inside selection" has no box, or the spec's block states don't fit
     * one message to the server ({@link TerrainBrushTool#fitsOneFrame}).
     */
    private BrushSpec buildSpec(ToolContext c) {
        SettingsValues values = c.settings();
        StateSpace states;
        try {
            states = c.states();
        } catch (IllegalStateException noSession) {
            return null;
        }
        Limits limits = limits(c);
        ShapeSpec shape = shapeOf(values, limits, pressFacing != null ? pressFacing : facingFor(values, null));
        Pattern material = null;
        if (shape.usesMaterial()) {
            material = material(c, values, states, seed);
            if (material == null) {
                return null;
            }
        }
        Box clip = null;
        if (values.get(ShapeSettings.INSIDE_SELECTION)) {
            clip = press.clip(c);
            if (clip == null) {
                return null;
            }
        }
        BrushSpec spec = BrushSpec.shape(ShapeSettings.radius(values, limits), shape, material, seed, clip,
                press.symmetry(c, values.get(ShapeSettings.SYMMETRY), aimPos));
        if (!TerrainBrushTool.fitsOneFrame(spec, states)) {
            c.notify(Notice.of(Notice.Level.WARNING, TerrainBrushTool.SPEC_TOO_LARGE, Integer.toString(ProtocolV2.MAX_C2S_FRAME)));
            return null;
        }
        return spec;
    }

    /**
     * The blocks a shape of the settings places: the fixed material, the mix as its pattern lays it out (with
     * {@code seed}), or the active block; {@code null} (after telling the player) when they can't be used.
     */
    private Pattern material(ToolContext c, SettingsValues values, StateSpace states, long seed) {
        if (fixedMaterial != null) {
            return fixedMaterial.material(c);
        }
        if (usesMix(values)) {
            Pattern.Weighted mix = BrushPress.palettePattern(c, values.get(ShapeSettings.PALETTE), seed);
            return mix == null ? null : ShapeSettings.PATTERN.pattern(c, values, mix, gradientLine);
        }
        BlockDescriptor block = activeBlock.get();
        int state = states.resolve(block);
        if (state < 0) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", block.format()));
            return null;
        }
        return new Pattern.Single(state);
    }

    // ---- Line mode ----

    /** Whether clicks add points to a line (never for a tool with a fixed material, such as the Fluid tool's ball). */
    private boolean lineMode(SettingsValues values) {
        return fixedMaterial == null && ShapeSettings.drawsLine(values);
    }

    /** The facing of the line's shape: the setting's, or for "Clicked face" the face its first point was clicked on. */
    private Facing lineFacing(SettingsValues values, WorldCursor.Face clicked) {
        ShapeSettings.FacingChoice choice = values.get(ShapeSettings.FACING);
        WorldCursor.Face face = line.points().isEmpty() || line.firstFace() == null ? clicked : line.firstFace();
        return choice.facing() != null ? choice.facing() : ShapeAnchor.facing(choice, face == null ? WorldCursor.Face.UP : face);
    }

    /** The point a click on {@code hit} adds: the block the shape's centre lands in there, as a dab would place it. */
    private BlockPos linePoint(ToolContext c, WorldCursor hit) {
        SettingsValues values = c.settings();
        Limits limits = limits(c);
        Facing facing = lineFacing(values, hit.face());
        double[] point = ShapeAnchor.point(shapeOf(values, limits, facing), ShapeSettings.radius(values, limits), facing,
                values.get(ShapeSettings.ANCHOR), hit.pos(), hit.face());
        return new BlockPos((int) Math.floor(point[0]), (int) Math.floor(point[1]), (int) Math.floor(point[2]));
    }

    /**
     * The shape swept along {@code points} with the settings as they are now: its mode picks the paste's Into (Carve
     * pastes air); "Only inside selection" keeps the cells inside the selection box; symmetry is not used. Null (after a
     * toast) when the blocks can't be used or there is no selection to stay inside.
     */
    private LineDraft.Setup lineSetup(ToolContext c, List<BlockPos> points) {
        SettingsValues values = c.settings();
        StateSpace states;
        try {
            states = c.states();
        } catch (IllegalStateException noSession) {
            return null;
        }
        Limits limits = limits(c);
        ShapeSpec shape = shapeOf(values, limits, lineFacing(values, null));
        int radius = ShapeSettings.radius(values, limits);
        Pattern material = null;
        if (shape.usesMaterial()) {
            material = material(c, values, states, LINE_SEED);
            if (material == null || !placeable(c, material, states)) {
                return null;
            }
        }
        Box clip = null;
        if (values.get(ShapeSettings.INSIDE_SELECTION)) {
            clip = c.selection().orElse(null);
            if (clip == null) {
                c.notify(Notice.of(Notice.Level.INFO, TerrainBrushTool.NEEDS_SELECTION));
                return null;
            }
        }
        PathKind kind = values.get(ShapeSettings.LINE_PATH);
        PathSpec path = new PathSpec(points, kind, kind == PathKind.HANGING ? values.get(ShapeSettings.LINE_SAG) : 0);
        WorldReader world = BrushPress.worldOrNull(c);
        int bottom = world == null ? Integer.MIN_VALUE : world.bottomY();
        int top = world == null ? Integer.MAX_VALUE : world.topYExclusive();
        Pattern blocks = material;
        Box inside = clip;
        LineDraft.Generator generator = cap -> {
            GeneratedSource swept = ShapeSweep.generate(path, radius, shape, blocks, states, cap, bottom, top);
            return inside == null ? swept : within(swept, inside);
        };
        return new LineDraft.Setup(path, generator, OpLabel.SHAPE_LINE, OP_LINE, SENT_LINE, lineOptions(shape.mode()));
    }

    /** The paste of a shape line in {@code mode}: Place everything, Air only into air, Paint over blocks, Carve air. */
    static PasteOptions lineOptions(ShapeSpec.Mode mode) {
        PasteOptions options = new PasteOptions(true, false, false);
        return switch (mode) {
            case PLACE, CARVE -> options;
            case PLACE_IN_AIR -> options.withInto(PasteOptions.Into.AIR);
            case PAINT -> options.withInto(PasteOptions.Into.EXISTING);
        };
    }

    /** The cells of {@code source} inside {@code box}. */
    private static GeneratedSource within(GeneratedSource source, Box box) {
        GeneratedSource.Builder kept = GeneratedSource.builder(source.cells());
        source.forEach((x, y, z, state) -> {
            if (box.contains(x, y, z)) kept.set(x, y, z, state);
        });
        return kept.build();
    }

    /** Whether the pattern's blocks can be generated: the server refuses block-entity states in a generated clipboard. */
    private static boolean placeable(ToolContext c, Pattern pattern, StateSpace states) {
        int[] handles = switch (pattern) {
            case Pattern.Single single -> new int[] {single.state()};
            case Pattern.Weighted weighted -> weighted.states();
            case Pattern.Arranged arranged -> arranged.mix().states();
            default -> new int[0];
        };
        for (int state : handles) {
            if (StateFlags.has(states.flags(state), StateFlags.HAS_BLOCK_ENTITY)) {
                c.notify(Notice.of(Notice.Level.WARNING, GenerateTool.BLOCK_ENTITY, states.describe(state).format()));
                return false;
            }
        }
        return true;
    }

    /** Middle-click with "Blocks: Mix": the block under the cursor joins the mix. */
    private void pickIntoMix(ToolContext c) {
        if (cursor.missed()) {
            return;
        }
        BlockPos pos = cursor.pos();
        WorldReader world = BrushPress.worldOrNull(c);
        if (world == null || !world.isLoaded(pos.x() >> 4, pos.z() >> 4)) {
            return;
        }
        int state = world.get(pos.x(), pos.y(), pos.z());
        if (state == c.states().air()) {
            return;
        }
        BrushPress.addToMix(c, ShapeSettings.PALETTE, c.states().describe(state));
    }

    // ---- Helpers ----

    private static boolean usesMix(SettingsValues values) {
        return values.get(ShapeSettings.BLOCKS) == ShapeSettings.Blocks.PALETTE;
    }

    /**
     * Whether this tool takes Alt+drag for the Gradient pattern's line and draws it: its own blocks (not a fixed
     * material), set to the mix with the Gradient pattern, placing (not carving).
     */
    private boolean drawsGradientLine(SettingsValues values) {
        return fixedMaterial == null && usesMix(values) && values.get(ShapeSettings.MODE) != ShapeSpec.Mode.CARVE
                && ShapeSettings.PATTERN.gradient(values);
    }

    private static ShapeSpec shapeOf(SettingsValues values, Limits limits, Facing facing) {
        return ShapeSettings.shape(values, limits, facing);
    }

    /** How far a shape reaches from its dab: the stroke controller's radius (its jump distance). */
    private static int reach(ShapeSpec shape, int radius) {
        return Math.max(radius, shape.axialSize(radius) / 2);
    }

    /** The cells one dab of this shape writes, without copies (cached for the last shape asked, whatever its facing). */
    private long cells(ShapeSpec shape, int radius) {
        CountKey key = new CountKey(shape.kind(), shape.axialSize(radius), shape.hollow(), radius);
        if (!key.equals(counted)) {
            countedCells = ShapeStamp.cellCount(shape, radius);
            counted = key;
        }
        return countedCells;
    }

    private record CountKey(ShapeSpec.Kind kind, int axial, int hollow, int radius) {}

    /**
     * What one dab costs in the dab rate and the in-flight window: its copies' work units, as the server counts them
     * ({@link ShapeStamp#units}), at most the window.
     */
    private int units(ShapeSpec shape, int radius, Symmetry.Mode symmetry) {
        return Math.min(StrokeController.MAX_IN_FLIGHT, ShapeStamp.units(symmetry.copies(), cells(shape, radius)));
    }

    /**
     * The work units of dabs sent that the server has not reported written, over the strokes begun (forgetting strokes
     * with none left that have ended).
     */
    private int unwritten() {
        int units = 0;
        for (int i = sent.size() - 1; i >= 0; i--) {
            Sent s = sent.get(i);
            int left = s.handle().unapplied();
            if (left == 0 && s.handle().ended()) {
                sent.remove(i);
                continue;
            }
            units += left * s.units();
        }
        return units;
    }

    /**
     * Faded boxes over the shapes the server is still writing that the player did not see predicted (the others are
     * already on screen), so a large shape shows where it will appear until it does.
     */
    private void drawUnwritten(WorldDraw d) {
        int faded = OverlayColors.scaleAlpha(COLOR, 0.3);
        for (Sent s : sent) {
            if (s.predicted()) continue;
            for (Dab dab : s.handle().unappliedDabs()) {
                try {
                    for (ShapeStamp.Placement placement : ShapeStamp.placements(s.handle().spec(), dab)) {
                        d.boxOutline(placement.box(), faded);
                    }
                } catch (IllegalArgumentException outOfRange) {
                    // A copy beyond the world: the server refused it.
                }
            }
        }
    }

    private static Limits limits(ToolContext c) {
        try {
            return c.session().permissions().limits();
        } catch (IllegalStateException noSession) {
            return Limits.DEFAULTS;
        }
    }

    /** The controller's view of this tool. */
    private final class Host implements StrokeController.Host {
        @Override
        public StrokeHandle begin() {
            return beginServerStroke();
        }

        /**
         * Dabs queued, and dabs of this press or earlier ones the server has not reported written yet, each counted as
         * its work units.
         */
        @Override
        public int inFlight(StrokeHandle stroke) {
            int units = stroke instanceof FabricStrokeHandle fabric ? fabric.queued() * strokeUnits : 0;
            return units + unwritten();
        }

        /** Earlier presses' dabs the server has not written: a new press's first shape waits for them. */
        @Override
        public int inFlightBeforeStroke() {
            return unwritten();
        }

        @Override
        public void beforeDab(Dab dab) {
            keepBefore(dab);
        }
    }
}
