package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BiomeSpec;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.HeightImage;
import dev.sculptory.core.brush.HeightmapSpec;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceNormal;
import dev.sculptory.core.brush.SurfacePlane;
import dev.sculptory.core.brush.SurfaceScan;
import dev.sculptory.core.brush.SymmetricStep;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.brush.WeatherSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.brush.BrushPredictor;
import dev.sculptory.fabric.client.editor.brush.ReaderTerrainProbe;
import dev.sculptory.fabric.client.editor.brush.StrokeController;
import dev.sculptory.fabric.client.editor.heightmap.HeightmapLibrary;
import dev.sculptory.fabric.client.editor.heightmap.HeightmapRef;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.render.BrushCursor;
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
import dev.sculptory.fabric.client.editor.world.SurfaceSampler;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.FabricStrokeHandle;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.client.session.StrokeHandle;
import dev.sculptory.fabric.client.session.StrokeParams;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.ProtocolV2;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The terrain brushes (palette slots 2-7, 15 and 16), one parameterized tool per {@link BrushTool}: Raise, Lower,
 * Smooth, Flatten, Paint, Palette Paint, Weather (its Weather setting picks Erode, Fill in, Roughen or Melt:
 * {@code WeatherKernel}) and Heightmap (below).
 *
 * <p>Left-drag paints a stroke. {@link StrokeController} turns the cursor path into dabs for a server
 * stroke; on the Fabric session a {@link BrushPredictor} applies each batch to the client world just before
 * it is sent, so the terrain changes at once and the server's acknowledgement reconciles it. Brushes whose
 * dabs are estimated to write more than {@link #MAX_PREDICTED_CELLS} cells run on the server only, shown by
 * a dashed cursor.
 *
 * <p>Keys: Alt while stroking inverts Raise and Lower; Ctrl+Scroll changes the radius (Shift ×4) and
 * Alt+Scroll the strength, also mid-stroke (the server stroke restarts with the new settings); middle-click
 * picks Paint's material or adds a block to Palette Paint's mix; Esc cancels the stroke. Flatten's target
 * height is the surface the stroke started on. Client thread only.
 *
 * <p><b>Pattern</b> (Palette Paint): the mix goes out as its Pattern setting
 * lays it out ({@link MixPatternSettings}). With the Gradient, Alt+drag draws the line it runs along
 * ({@link GradientDrag}, the {@link GradientLine} the brushes share) instead of painting, the line shows as an arrow,
 * and a press without a line is refused with a toast.
 *
 * <p><b>Mode</b> (Raise, Lower, Smooth, Flatten and Weather). "Surface (any direction)", the default,
 * sends a {@link SculptMode#SURFACE} spec: the brush works on whatever it is aimed at, inside the ball of its radius
 * ({@code SurfaceKernel}). Its cursor is a ring across the way the surface faces there ({@link #facingAt}, the kernel's
 * {@link SurfaceNormal} estimate, the hit face when that is zero), with a tick showing which way Raise pushes or Lower
 * pulls. Flatten's plane ({@link SurfacePlane}) is fixed at the press's first dab, through the hit point and facing
 * that way, and shown while pressing. "Terrain (from above)" is the column brush as before.
 *
 * <p>With "Only inside selection" on, the selection box as it is when the press begins (clamped to the build
 * height) becomes every server stroke's {@link BrushSpec#clip()} for that press, restarts included: selecting
 * something else mid-press changes nothing until the next press. The box is outlined in the brush colour while
 * painting. Without a selection the press is refused with a toast, and the hint line says why.
 *
 * <p><b>Symmetry.</b> With a symmetry mode set, every server stroke's spec carries a {@link Symmetry} around the
 * {@link SymmetryCentre} the brushes share: the one set with {@link KeyAction#SET_SYMMETRY_CENTRE} (the cursor's
 * block centre, or with Shift the nearest block corner; the same key on the same point again clears it), otherwise the
 * selection's centre when the press began. With neither, the press sets the centre at the block it begins on (a toast
 * says so). The centre is fixed for the whole press, restarts included; Rotate 4 moves a centre that mixes a block
 * centre and a block edge onto block centres ({@link SymmetryCentre#fitted}). The server replicates the dabs itself and
 * stands each copy on the ground where it lands ({@link SymmetricStep}); the prediction runs the same kernel, so it
 * replicates and locates them the same way. Pacing and the server-only estimate count every copy: a dab costs its
 * mode's copies in the dab rate and in the in-flight window. While the brush has symmetry on, the centre line, the
 * mirror planes and the copies' rings (on the ground each would stand on, grey where one would find none) are drawn in
 * the world.
 *
 * <p><b>Heightmap</b> ({@link BrushTool#HEIGHTMAP}, slot 16). It stamps a picture from the {@link HeightmapLibrary} (the
 * built-ins and the builder's folder, picked in Tool Settings): the picture is fitted to the brush's square
 * ({@code 2 × radius + 1} columns) and turned by the Angle setting ({@link HeightImage#stamp}) here on the client, and
 * sent as the stroke's {@link HeightmapSpec} with the Height and Raise or Lower. A click stamps once; a drag lays a ridge
 * ({@code HeightmapKernel}). As the Shape brush's, a press stamps at its first hit until the cursor has moved
 * ({@link PressAim}), and its cursor ray sees the ground as it was before the press stamped ({@link #rayOverlay},
 * {@link PressSnapshot}, taken just before each dab goes out): so a click held still stamps once however its hill rises
 * (or its pit sinks) under the cursor, predicted or written by the server, and a drag lays its ridge along the ground
 * the cursor points at rather than climbing its own hills. The other brushes aim at the cursor as it is: the sculpting
 * ones repeat while held (flow), and Paint, Palette Paint and Biome change no height the cursor meets. R and Shift+R
 * turn the stamp a quarter turn either way; Ctrl+Scroll sizes it, up to radius 128 (a 257-wide stamp; every other brush stops at 32), within the server's {@code maxHeightmapRadius}, and the Height
 * within its {@code maxHeightmapHeight}. The cursor outlines the stamp's square on the ground and tints it by the
 * picture's value: each column up to radius 32, and past it each cell of a few columns by the picture stamped a pixel
 * a cell (at most 65 × 65 quads), with a tick pointing to the picture's top edge. A stamp over the prediction cap is
 * left to the server (at radius 128 every one is). A Heightmap dab stays in flight until the server reports it
 * written (not merely admitted), weighing its copies times its stamp's columns in radius-32 stamps
 * ({@link HeightmapSpec#weight}), as the server's queue counts it; so a drag or quick clicks at radius 128 send the
 * next stamp only once the last is written, the path coalescing to the cursor meanwhile, and a new press waits for
 * earlier presses' stamps. A picture too detailed to send at its size is refused with a toast. A missing
 * picture (a preset naming a file this player lacks) refuses the press with a toast; files in the folder that can't be
 * read are skipped with a one-line toast.
 */
public final class TerrainBrushTool implements Tool {
    /** Dabs estimated to write more cells than this are not predicted. */
    public static final int MAX_PREDICTED_CELLS = StrokeParams.DEFAULT.maxPredictedCells();
    public static final String LEFT_DRAG = "LMB drag";
    /** The hint for drawing the Gradient pattern's line. */
    public static final String GRADIENT_DRAG = "Alt+drag";
    /** Toast and hint when "Only inside selection" is on but there is no selection. */
    public static final String NEEDS_SELECTION = "sculptory.notice.brush_needs_selection";
    /** Toast when "Only inside selection" is on but the selection lies outside the build height. */
    public static final String SELECTION_OUTSIDE_WORLD = "sculptory.notice.brush_selection_outside_world";
    /** Toast when the brush's block states are too long to send in one message ({@link #fitsOneFrame}). */
    public static final String SPEC_TOO_LARGE = "sculptory.notice.brush_blocks_too_long";
    /**
     * Toast when a Heightmap stamp's picture is too detailed to shrink into one message at its size (past radius 32 the
     * picture travels deflated; noise doesn't shrink).
     */
    public static final String PICTURE_TOO_DETAILED = "sculptory.notice.heightmap_too_detailed";
    /** A copy's outline where it would find no ground (and change nothing). */
    static final int NO_GROUND_COLOR = 0x99A0A0A0;
    /** Vanilla's {@code World.HORIZONTAL_LIMIT}: no block exists beyond it. */
    private static final int WORLD_LIMIT = 30_000_000;
    /** Toasts of the symmetry centre key and of a press that sets the centre. */
    public static final String SYMMETRY_CENTRE_SET = "sculptory.notice.symmetry_centre_set";
    public static final String SYMMETRY_CENTRE_SET_OFF = "sculptory.notice.symmetry_centre_set_off";
    public static final String SYMMETRY_CENTRE_CLEARED = "sculptory.notice.symmetry_centre_cleared";
    public static final String SYMMETRY_CENTRE_AIM = "sculptory.notice.symmetry_centre_aim";
    public static final String SYMMETRY_CENTRE_STARTED = "sculptory.notice.symmetry_centre_started";

    private final BrushTool kind;
    private final ToolDescriptor descriptor;
    private final BrushSettings settings;
    private final BrushServices services;
    private final StrokeController controller;
    private final SurfaceSampler sampler = new SurfaceSampler();
    private final SymmetryCentre centre;
    /** Palette Paint's Alt+drag for the Gradient pattern's line (shared through the symmetry centre). */
    private final GradientDrag gradient;
    /** The press's selection and symmetry centre. */
    private final BrushPress press;

    private ToolContext context;
    private WorldCursor cursor = WorldCursor.miss(0, 0, 0);
    private int aimBlockY;
    /** The block the cursor last hit. */
    private BlockPos aimPos;
    /** The cursor's last hit (Surface Flatten's plane is taken there, as flattenY is from {@link #aimBlockY}). */
    private WorldCursor lastHit;

    // What the cursor showed last frame, for renderWorld.
    private SurfaceSampler.Samples samples;
    private int cursorRadius;
    private Shape cursorShape = Shape.CIRCLE;
    private int cursorColor;
    private boolean cursorServerOnly;
    /** The Surface mode's cursor: the way the surface faces at the cursor, or {@code null} in the Terrain mode. */
    private Facing cursorFacing;
    private double cursorFalloffStart;
    /** The brush the cursor shows (Alt swaps Raise and Lower). */
    private BrushTool cursorTool;
    /** {@link #cachedFacing}'s last answer and what it was for: the hit's block, the radius and the world's change stamp. */
    private Facing facingCached;
    private final int[] facingBlock = new int[3];
    private WorldCursor.Face facingFace;
    /** {@link #copyFacing}'s answers and what each was for, per copy. */
    private final Facing[] copyFacings = new Facing[Symmetry.MAX_COPIES];
    private final long[][] copyFacingKeys = new long[Symmetry.MAX_COPIES][];
    private int facingRadius;
    private long facingStamp;

    // The Heightmap brush's stamp, kept while its picture, size and angle stay the same.
    private HeightmapLibrary.Entry stampEntry;
    private int stampSide;
    private int stampAngle;
    private byte[] stamp;
    /** A wide stamp's preview, one pixel a cell ({@link #previewStamp}), and what it was made for. */
    private byte[] previewCells;
    /** Heightmap strokes begun whose stamps the server may still be writing, oldest first (the current one last). */
    private final List<FabricStrokeHandle> sentStamps = new ArrayList<>();
    private HeightmapLibrary.Entry previewEntry;
    private int previewAcross;
    private int previewAngle;

    // The current press.
    private boolean invert;
    private Integer flattenY;
    /** Surface-mode Flatten's plane, fixed at the press's first dab ({@link #planeAt}). */
    private SurfacePlane surfacePlane;
    private long seed;
    private BrushSpec strokeSpec;
    private BrushPredictor predictor;
    private boolean warnedMaskState;
    /** The press said its picture is missing (once a press, restarts included). */
    private boolean warnedPicture;
    /** The Heightmap brush: the world as it was before the press stamped, what the press's cursor ray sees. */
    private final PressSnapshot before = new PressSnapshot();
    /** The Heightmap brush: where the press aims, its first hit until the cursor moves. */
    private final PressAim pressAim;

    /** A brush with a symmetry centre of its own (tests and tool sets without the others). */
    public TerrainBrushTool(BrushTool kind, BrushServices services) {
        this(kind, services, new SymmetryCentre());
    }

    /** @param centre the symmetry centre, shared by the brushes of one editor */
    public TerrainBrushTool(BrushTool kind, BrushServices services, SymmetryCentre centre) {
        this.kind = Objects.requireNonNull(kind);
        this.services = Objects.requireNonNull(services);
        if (kind == BrushTool.SHAPE) throw new IllegalArgumentException("The Shape brush has its own tool");
        this.centre = Objects.requireNonNull(centre);
        this.press = new BrushPress(centre, services);
        this.pressAim = new PressAim(services);
        this.gradient = new GradientDrag(centre.gradientLine());
        this.settings = BrushSettings.forTool(kind);
        // The Biome brush needs the biome node instead of the brush node (protocol 6).
        this.descriptor = new ToolDescriptor(toolId(kind), "sculptory.tool." + name(kind), icon(kind),
                kind == BrushTool.BIOME ? Perm.BIOME : Perm.BRUSH);
        this.controller = new StrokeController(new Host(), flows(kind));
    }

    // ---- Kinds ----

    public static ToolId toolId(BrushTool kind) {
        return switch (kind) {
            case RAISE -> ToolId.RAISE;
            case LOWER -> ToolId.LOWER;
            case SMOOTH -> ToolId.SMOOTH;
            case FLATTEN -> ToolId.FLATTEN;
            case PAINT -> ToolId.PAINT;
            case PALETTE -> ToolId.PALETTE;
            case SHAPE -> ToolId.SHAPE;
            case WEATHER -> ToolId.WEATHER;
            case HEIGHTMAP -> ToolId.HEIGHTMAP;
            case BIOME -> ToolId.BIOME;
        };
    }

    /** The cursor colour of each brush (ARGB). */
    public static int color(BrushTool kind) {
        return switch (kind) {
            case RAISE -> 0xFF6BD16B;
            case LOWER -> 0xFFE5655D;
            case SMOOTH -> 0xFF5DB3E9;
            case FLATTEN -> 0xFFE8C547;
            case PAINT -> 0xFFD77BE0;
            case PALETTE -> 0xFFA58BF2;
            case SHAPE -> 0xFF4FD6C4;
            case WEATHER -> 0xFF9FB4C7;
            case HEIGHTMAP -> 0xFFC9A26B;
            case BIOME -> 0xFF7BC96B;
        };
    }

    /**
     * Where the cursor's inner ring sits, as a fraction of the radius: roughly where each falloff curve
     * starts to drop (a display approximation of the kernel's curves).
     */
    public static double falloffStart(Falloff falloff) {
        return switch (falloff) {
            case CONSTANT -> 1.0;
            case LINEAR -> 0.0;
            case SMOOTH -> 0.25;
            case SPHERE -> 0.5;
        };
    }

    /**
     * Whether this brush takes Alt+drag for the Gradient pattern's line and draws it: Palette Paint with its Pattern set
     * to Gradient.
     */
    private boolean drawsGradientLine(SettingsValues values) {
        return kind == BrushTool.PALETTE && settings.mixPattern.gradient(values);
    }

    /** The sculpting brushes (Raise, Lower, Smooth, Flatten and Weather) repeat dabs while the cursor is held still. */
    public static boolean flows(BrushTool kind) {
        return kind == BrushTool.RAISE || kind == BrushTool.LOWER || kind == BrushTool.SMOOTH
                || kind == BrushTool.FLATTEN || kind == BrushTool.WEATHER;
    }

    private static String name(BrushTool kind) {
        return kind.name().toLowerCase(Locale.ROOT);
    }

    /** The Heightmap tool's palette icon (protocol 6; its placeholder uses it too). */
    public static final String HEIGHTMAP_ICON = "minecraft:filled_map";
    /** The Biome tool's palette icon (protocol 6; its placeholder uses it too). */
    public static final String BIOME_ICON = "minecraft:globe_banner_pattern";

    /** The palette icon of each brush (an item id). */
    static String icon(BrushTool kind) {
        return switch (kind) {
            case RAISE -> "minecraft:grass_block";
            case LOWER -> "minecraft:iron_shovel";
            case SMOOTH -> "minecraft:brush";
            case FLATTEN -> "minecraft:smooth_stone_slab";
            case PAINT -> "minecraft:red_dye";
            case PALETTE -> "minecraft:painting";
            case SHAPE -> "minecraft:heart_of_the_sea";
            case WEATHER -> "minecraft:wind_charge";
            case HEIGHTMAP -> HEIGHTMAP_ICON;
            case BIOME -> BIOME_ICON;
        };
    }

    // ---- Tool ----

    public BrushTool kind() {
        return kind;
    }

    public BrushSettings settings() {
        return settings;
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

    /** The symmetry centre this brush uses. */
    public SymmetryCentre symmetryCentre() {
        return centre;
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public SettingsSchema schema() {
        return settings.schema();
    }

    @Override
    public RaycastMode raycastMode(SettingsValues s) {
        return RaycastMode.TERRAIN;
    }

    /** The Heightmap brush while pressing: the world as it was before the press stamped, so the cursor looks through it. */
    @Override
    public RayOverlay rayOverlay() {
        return controller.pressed() && !before.isEmpty() ? before : null;
    }

    @Override
    public void activate(ToolContext c) {
        context = c;
        if (kind == BrushTool.HEIGHTMAP) {
            refreshPictures(c);
        }
    }

    @Override
    public void deactivate(ToolContext c, DeactivateReason r) {
        if (controller.pressed()) {
            endPress(c, r == DeactivateReason.SWITCHED_TOOL
                    ? StrokeController.EndReason.TOOL_SWITCHED
                    : StrokeController.EndReason.EDITOR_EXITED);
        }
        gradient.cancel(c);
        c.setPointerCapture(false);
        services.clearCursor();
        samples = null;
        context = null;
    }

    @Override
    public boolean onPointer(ToolContext c, PointerEvent e) {
        switch (e.kind()) {
            case PRESS -> {
                if (e.button() != PointerEvent.LEFT) {
                    return false;
                }
                aim(e.cursor());
                // Palette Paint with a Gradient: Alt+drag draws the line instead of painting.
                if (drawsGradientLine(c.settings()) && gradient.press(c, e)) {
                    return true;
                }
                beginPress(c, Modifiers.alt(e.modifiers()));
                return true;
            }
            case DRAG -> {
                if (gradient.active()) {
                    gradient.drag(e);
                    aim(e.cursor());
                    return true;
                }
                if (e.button() != PointerEvent.LEFT || !controller.pressed()) {
                    return false;
                }
                aim(e.cursor());
                return true;
            }
            case RELEASE -> {
                if (gradient.active() && e.button() == PointerEvent.LEFT) {
                    gradient.release(c, e);
                    aim(e.cursor());
                    return true;
                }
                if (e.button() != PointerEvent.LEFT || !controller.pressed()) {
                    return false;
                }
                aim(e.cursor());
                endPress(c, StrokeController.EndReason.RELEASED);
                return true;
            }
            case MOVE -> {
                aim(e.cursor());
                return false;
            }
        }
        return false;
    }

    @Override
    public void frame(ToolContext c, FrameInfo f) {
        aim(f.cursor());
        if (controller.pressed()) {
            if (!services.windowFocused()) {
                endPress(c, StrokeController.EndReason.FOCUS_LOST);
            } else {
                if (invertible()) {
                    boolean alt = Modifiers.alt(c.modifiers());
                    if (alt != invert) {
                        invert = alt;
                        controller.requestRestart();
                    }
                }
                controller.update(f.nanoTime());
            }
        }
        updateCursor(c);
    }

    @Override
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        if (e.amount() == 0) {
            return false;
        }
        KeyAction action = services.scrollAction(e.modifiers()).orElse(null);
        int direction = e.amount() > 0 ? 1 : -1;
        SettingsValues values = c.settings();
        if (action == KeyAction.TOOL_SIZE) {
            int step = direction * (Modifiers.shift(e.modifiers()) ? 4 : 1);
            int radius = clamp(values.get(settings.radius) + step, 1, maxRadius(limits(c)));
            c.updateSettings(values.with(settings.radius, radius));
            return true;
        }
        if (action == KeyAction.TOOL_STRENGTH && kind != BrushTool.HEIGHTMAP) {
            double steps = Math.round(values.get(settings.strength) / BrushSettings.STRENGTH_STEP) + direction;
            double strength = Math.max(0, Math.min(1, steps * BrushSettings.STRENGTH_STEP));
            c.updateSettings(values.with(settings.strength, Math.round(strength * 100) / 100.0));
            return true;
        }
        return false;
    }

    @Override
    public boolean onAction(ToolContext c, EditorAction a) {
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
                if (kind != BrushTool.PAINT && kind != BrushTool.PALETTE) {
                    return false;
                }
                pickMaterial(c);
                return true;
            }
            case SET_SYMMETRY_CENTRE -> {
                setSymmetryCentre(c);
                return true;
            }
            case ROTATE_CW, ROTATE_CCW -> {
                if (kind != BrushTool.HEIGHTMAP) {
                    return false;
                }
                // A quarter turn of the stamp, clockwise seen from above (Shift: back); mid-press the stroke restarts.
                SettingsValues values = c.settings();
                int turn = a == EditorAction.ROTATE_CW ? 90 : 270;
                c.updateSettings(values.with(settings.heightmapAngle, (values.get(settings.heightmapAngle) + turn) % 360));
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public void onSettingsChanged(ToolContext c, SettingsValues before, SettingsValues after) {
        if (controller.pressed()) {
            controller.setRadius(effectiveRadius(after, limits(c)));
            controller.setDabCost(dabCost(after, limits(c)));
            controller.requestRestart();
        }
    }

    @Override
    public void renderWorld(ToolContext c, WorldDraw d) {
        if (strokeSpec != null && strokeSpec.clip() != null) {
            d.boxOutline(strokeSpec.clip(), color(strokeSpec.tool()));
        }
        if (drawsGradientLine(c.settings())) {
            gradient.render(d, color(kind));
        }
        Symmetry symmetry = shownSymmetry(c);
        if (!symmetry.isOff()) {
            BrushOutlines.symmetry(d, symmetry, cursor.missed() ? aimBlockY + 1 : cursor.hitY(),
                    effectiveRadius(c.settings(), limits(c)), cursorColor == 0 ? color(kind) : cursorColor);
            if (!cursor.missed()) {
                copyOutlines(c, d, symmetry, c.settings().get(settings.shape), effectiveRadius(c.settings(), limits(c)));
            }
        }
        if (cursorFacing != null && !cursor.missed()) {
            // Surface mode: the ring across the way the surface faces, a tick showing which way Raise pushes or Lower
            // pulls, and Flatten's plane while pressing.
            BrushOutlines.surfaceRing(d, cursor.hitX(), cursor.hitY(), cursor.hitZ(), cursorFacing, cursorRadius,
                    cursorShape, cursorFalloffStart, cursorColor, cursorServerOnly);
            if (cursorTool == BrushTool.RAISE || cursorTool == BrushTool.LOWER) {
                BrushOutlines.directionTick(d, cursor.hitX(), cursor.hitY(), cursor.hitZ(),
                        cursorTool == BrushTool.RAISE ? cursorFacing : cursorFacing.opposite(), cursorRadius, cursorColor);
            }
            if (kind == BrushTool.FLATTEN && controller.pressed() && surfacePlane != null) {
                BrushOutlines.surfacePlane(d, surfacePlane, cursor.hitX(), cursor.hitY(), cursor.hitZ(), cursorRadius,
                        cursorShape, cursorColor);
            }
            return;
        }
        if (samples == null || cursor.missed()) {
            return;
        }
        if (cursorShape == Shape.SQUARE) {
            BrushOutlines.square(d, samples, cursorRadius, cursorColor, cursorServerOnly);
        }
        if (kind == BrushTool.HEIGHTMAP) {
            stampPreview(c, d);
        }
        if (kind == BrushTool.FLATTEN && controller.pressed() && flattenY != null) {
            BrushOutlines.plane(d, samples.centerX(), flattenY, samples.centerZ(), cursorRadius, cursorShape, cursorColor);
        }
    }

    @Override
    public List<KeyHint> hints(ToolContext c) {
        List<KeyHint> hints = new ArrayList<>();
        String name = name(kind);
        if (!controller.pressed() && c.settings().get(settings.insideSelection) && c.selection().isEmpty()) {
            hints.add(KeyHint.text(NEEDS_SELECTION));
        }
        if (controller.pressed()) {
            hints.add(new KeyHint("Esc", "sculptory.hint.cancel_drag"));
        } else {
            hints.add(new KeyHint(LEFT_DRAG, "sculptory.hint.brush." + name));
        }
        if (invertible()) {
            hints.add(new KeyHint("Alt", "sculptory.hint.brush.invert_" + name));
        }
        if (!controller.pressed() && kind == BrushTool.PAINT) {
            hints.add(new KeyHint(services.keyLabel(KeyAction.EYEDROPPER), "sculptory.hint.brush.pick_material"));
        }
        if (!controller.pressed() && kind == BrushTool.PALETTE) {
            hints.add(new KeyHint(services.keyLabel(KeyAction.EYEDROPPER), "sculptory.hint.brush.add_block"));
        }
        if (!controller.pressed() && drawsGradientLine(c.settings())) {
            hints.add(new KeyHint(GRADIENT_DRAG, "sculptory.hint.pattern.draw_line"));
        }
        if (kind == BrushTool.HEIGHTMAP) {
            hints.add(new KeyHint(services.keyLabel(KeyAction.ROTATE_CW) + "/" + services.keyLabel(KeyAction.ROTATE_CCW),
                    "sculptory.hint.brush.heightmap_turn"));
        }
        hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.brush.radius"));
        if (kind != BrushTool.HEIGHTMAP) {
            hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_STRENGTH), "sculptory.hint.brush.strength"));
        }
        if (!controller.pressed() && c.settings().get(settings.symmetry) != Symmetry.Mode.OFF) {
            hints.add(new KeyHint(services.keyLabel(KeyAction.SET_SYMMETRY_CENTRE), "sculptory.hint.brush.symmetry_centre"));
        }
        return hints;
    }

    // ---- Presses ----

    private void beginPress(ToolContext c, boolean alt) {
        if (drawsGradientLine(c.settings()) && !symmetryCentre().gradientLine().isSet()) {
            // A Gradient runs along its line: none drawn yet, so nothing to paint.
            c.notify(Notice.of(Notice.Level.WARNING, GradientDrag.NO_LINE));
            return;
        }
        press.begin(c.selection());
        if (c.settings().get(settings.insideSelection) && press.clip(c) == null) {
            press.end();
            return;
        }
        invert = alt && invertible();
        flattenY = null;
        surfacePlane = null;
        seed = services.nextSeed();
        strokeSpec = null;
        predictor = null;
        warnedMaskState = false;
        warnedPicture = false;
        if (kind == BrushTool.HEIGHTMAP) {
            refreshPictures(c);
        }
        before.clear();
        pressAim.begin(cursor);
        Limits limits = limits(c);
        controller.press(effectiveRadius(c.settings(), limits), limits.maxDabRate(), dabCost(c.settings(), limits));
        c.setPointerCapture(true);
    }

    private void endPress(ToolContext c, StrokeController.EndReason reason) {
        controller.end(reason);
        c.setPointerCapture(false);
        strokeSpec = null;
        predictor = null;
        flattenY = null;
        surfacePlane = null;
        invert = false;
        before.clear();
        pressAim.end();
        press.end();
    }

    /**
     * Follows the cursor: its hit goes to the stroke controller. A Heightmap press aims at its first hit until the
     * cursor moves ({@link PressAim}): its stamp raises or cuts the ground under a cursor held still.
     */
    private void aim(WorldCursor next) {
        cursor = next;
        WorldCursor target = kind == BrushTool.HEIGHTMAP && controller.pressed() ? pressAim.target(next) : next;
        if (target.missed()) {
            controller.clearAim();
        } else {
            controller.aim(target.hitX(), target.hitY(), target.hitZ());
            aimBlockY = target.pos().y();
            aimPos = target.pos();
            lastHit = target;
        }
    }

    /**
     * {@code dab} of a Heightmap stroke is about to go out: the sections its stamp and copies can write are kept as they
     * are now for the press's cursor ray ({@link PressSnapshot#coverHeightmap}), before anything of it is predicted or
     * written.
     */
    private void keepBefore(Dab dab) {
        ToolContext c = context;
        WorldReader world = c == null ? null : worldOrNull(c);
        if (kind != BrushTool.HEIGHTMAP || world == null || strokeSpec == null) {
            return;
        }
        before.coverHeightmap(world, strokeSpec, dab);
    }

    /** Begins a server stroke with the settings as they are now (the controller calls this). */
    private StrokeHandle beginServerStroke() {
        ToolContext c = context;
        if (c == null) {
            return null;
        }
        if (kind == BrushTool.FLATTEN && flattenY == null) {
            flattenY = aimBlockY;
        }
        if (kind == BrushTool.FLATTEN && surfacePlane == null && surface(c.settings()) && lastHit != null) {
            surfacePlane = planeAt(worldOrNull(c), lastHit, effectiveRadius(c.settings(), limits(c)));
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
        // Biomes are not predicted: the client sees them when the server's biome packets come (protocol 6).
        boolean predict = kind != BrushTool.BIOME && BrushPredictor.estimatedCells(spec) <= MAX_PREDICTED_CELLS;
        StrokeHandle handle = session.beginStroke(descriptor.id(), spec, new StrokeParams(predict, MAX_PREDICTED_CELLS));
        if (!handle.active()) {
            return null;
        }
        strokeSpec = spec;
        if (kind == BrushTool.HEIGHTMAP && handle instanceof FabricStrokeHandle fabric) {
            sentStamps.add(fabric);
        }
        predictor = null;
        if (predict && handle instanceof FabricStrokeHandle fabric) {
            BrushPredictor.Target target = services.predictionTarget();
            WorldReader world = worldOrNull(c);
            if (target != null && world != null) {
                BrushPredictor next = new BrushPredictor(spec, world, target);
                fabric.setBatchHook((stroke, batch) -> next.predict(batch));
                predictor = next;
            }
        }
        return handle;
    }

    /**
     * The spec the settings give now, with this press's invert, Flatten height, seed and clip box; {@code null}
     * (after telling the player) when the material can't be used, "Only inside selection" has no box, or the spec's
     * block states don't fit one message to the server ({@link #fitsOneFrame}).
     */
    private BrushSpec buildSpec(ToolContext c) {
        SettingsValues values = c.settings();
        StateSpace states;
        try {
            states = c.states();
        } catch (IllegalStateException noSession) {
            return null;
        }
        int radius = effectiveRadius(values, limits(c));
        float strength = (float) Math.max(0, Math.min(1, values.get(settings.strength)));
        Pattern material = null;
        if (kind == BrushTool.PAINT) {
            BlockDescriptor block = values.get(settings.material);
            int state = states.resolve(block);
            if (state < 0) {
                c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", block.format()));
                return null;
            }
            material = new Pattern.Single(state);
        } else if (kind == BrushTool.PALETTE) {
            Pattern.Weighted mix = BrushPress.palettePattern(c, values.get(settings.palette), seed);
            material = mix == null ? null : settings.mixPattern.pattern(c, values, mix, centre.gradientLine());
            if (material == null) {
                return null;
            }
        }
        Box clip = null;
        if (values.get(settings.insideSelection)) {
            clip = press.clip(c);
            if (clip == null) {
                return null;
            }
        }
        if (!warnedMaskState) {
            settings.unknownMaskState(values, states).ifPresent(unknown ->
                    c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", unknown.format())));
            warnedMaskState = true;
        }
        if (kind == BrushTool.HEIGHTMAP) {
            HeightmapSpec image = heightmapSpec(c, values, radius);
            if (image == null) {
                return null;
            }
            BrushSpec spec = new BrushSpec(BrushTool.HEIGHTMAP, radius, 1f, Falloff.CONSTANT, Shape.SQUARE, null,
                    settings.mask(values, states), 0, 0, seed, clip, press.symmetry(c, values.get(settings.symmetry), aimPos),
                    null, SculptMode.TERRAIN, null, null, image, null);
            if (!fitsOneFrame(spec, states)) {
                // Past radius 32 the picture travels deflated: a picture too noisy to shrink is what doesn't fit.
                c.notify(image.side() > 2 * BrushSpec.MAX_RADIUS + 1
                        ? Notice.of(Notice.Level.WARNING, PICTURE_TOO_DETAILED)
                        : Notice.of(Notice.Level.WARNING, SPEC_TOO_LARGE, Integer.toString(ProtocolV2.MAX_C2S_FRAME)));
                return null;
            }
            // The server refuses a stamp past its limits (its blocks above all): say which, with the numbers, before
            // sending anything.
            Notice over = SessionNotices.heightmapLimit(spec, limits(c), bypass(c));
            if (over != null) {
                c.notify(over);
                return null;
            }
            return spec;
        }
        int depth = settings.depth == null ? 0 : values.get(settings.depth);
        int targetY = kind == BrushTool.FLATTEN && flattenY != null ? flattenY : 0;
        BrushSpec spec = new BrushSpec(effectiveTool(invert), radius, strength, values.get(settings.falloff),
                values.get(settings.shape), material, settings.mask(values, states), depth, targetY, seed, clip,
                press.symmetry(c, values.get(settings.symmetry), aimPos), null, SculptMode.TERRAIN, null,
                settings.weatherMode == null ? null : new WeatherSpec(values.get(settings.weatherMode)), null,
                settings.biome == null ? null
                        : new BiomeSpec(values.get(settings.biome), !values.get(settings.biomeThreeD)));
        if (surface(values)) {
            if (kind == BrushTool.FLATTEN && surfacePlane == null) {
                return null;
            }
            spec = spec.withSurface(kind == BrushTool.FLATTEN ? surfacePlane : null);
        }
        if (!fitsOneFrame(spec, states)) {
            c.notify(Notice.of(Notice.Level.WARNING, SPEC_TOO_LARGE, Integer.toString(ProtocolV2.MAX_C2S_FRAME)));
            return null;
        }
        return spec;
    }

    /**
     * Whether a stroke of {@code spec} can begin: its {@code StrokeBegin} fits one client frame
     * ({@link ProtocolV2#MAX_C2S_FRAME} bytes). The mix and the mask's exact states travel as state text, so 64 blocks
     * of vanilla states always fit (the longest take under 200 bytes, a palette's at most 256), but modded states with
     * many long properties may not. Such a press is refused here, with a toast, instead of failing to send; the server
     * never sees an oversized frame. The check encodes with the longest stroke id (5 bytes), so every real id fits.
     */
    static boolean fitsOneFrame(BrushSpec spec, StateSpace states) {
        try {
            Codec.encodeC2S(new C2S.StrokeBegin(Integer.MAX_VALUE, spec), states);
            return true;
        } catch (ProtocolException e) {
            // Anything else is the session's to report when it sends.
            return e.reason() != ProtocolException.Reason.TOO_LARGE;
        } catch (RuntimeException e) {
            // No state space yet, or a handle outside it: the session refuses the stroke and says so, as for any send.
            return true;
        }
    }

    // ---- Symmetry ----

    /** How many copies a dab of these settings makes at most: its cost in the dab rate and the in-flight window. */
    private int copies(SettingsValues values) {
        return values.get(settings.symmetry).copies();
    }

    /**
     * The symmetry the world overlay shows: the press's while pressing; otherwise around the set centre or the
     * selection's, or around the cursor's block (where a press would put it). Off when the brush has none.
     */
    private Symmetry shownSymmetry(ToolContext c) {
        return press.shown(c, c.settings().get(settings.symmetry), controller.pressed(), aimPos);
    }

    /** The symmetry centre key ({@link BrushPress#setCentre}). */
    private void setSymmetryCentre(ToolContext c) {
        press.setCentre(c, cursor, c.settings().get(settings.symmetry) != Symmetry.Mode.OFF);
    }

    /**
     * The copies' outlines for a press at the cursor (the overlay's ring marks the cursor itself): each on the ground
     * where it lands, found as a stroke finds it ({@link SymmetricStep#ground}, from the cursor's height); a copy that
     * would find no ground, and so change nothing, in grey at the cursor's height. They are the copies of the dab a
     * press would lay here, at the hit point in 1/16 block as {@link StrokeController} places it.
     */
    private void copyOutlines(ToolContext c, WorldDraw d, Symmetry symmetry, Shape shape, int radius) {
        Dab dab;
        List<Dab> copies;
        try {
            dab = Dab.of(0, cursor.hitX(), cursor.hitY(), cursor.hitZ(), Dab.FULL_PRESSURE);
            copies = symmetry.copies(dab);
        } catch (IllegalArgumentException outOfRange) {
            return;
        }
        WorldReader world = worldOrNull(c);
        int color = OverlayColors.scaleAlpha(cursorColor == 0 ? color(kind) : cursorColor, 0.7);
        boolean surface = surface(c.settings());
        for (int i = 1; i < copies.size(); i++) {
            Dab copy = copies.get(i);
            if (surface && world != null && SymmetricStep.surfaceAt(world, copy.blockX(), copy.blockY(), copy.blockZ())) {
                // A Surface copy with a surface at its own point stays there (SymmetricStep.locate).
                BrushOutlines.surfaceRing(d, copy.x16() / 16.0, copy.y16() / 16.0, copy.z16() / 16.0,
                        copyFacing(world, i, copy, radius), radius, shape, Double.NaN, color, false);
                continue;
            }
            int ground = world == null ? SurfaceScan.NONE : SymmetricStep.ground(world, copy.blockX(), copy.blockZ(),
                    dab.blockY());
            boolean found = ground != SurfaceScan.NONE;
            double y = (found ? ground + 1.0 : cursor.hitY()) + BrushOutlines.LIFT;
            BrushOutlines.footprint(d, copy.x16() / 16.0, y, copy.z16() / 16.0, radius, shape,
                    found ? color : NO_GROUND_COLOR);
        }
    }

    /**
     * {@code box} limited to the world: y within {@code [bottomY, topYExclusive)}, x and z within the vanilla world
     * limit (±30,000,000); {@code null} when nothing is left. Nothing outside the world can be written anyway, so
     * the clamp changes no stroke's result, and the server accepts the box.
     */
    static Box clampToWorld(Box box, int bottomY, int topYExclusive) {
        int y0 = Math.max(box.min().y(), bottomY);
        int y1 = Math.min(box.max().y(), topYExclusive - 1);
        int x0 = Math.max(box.min().x(), -WORLD_LIMIT);
        int x1 = Math.min(box.max().x(), WORLD_LIMIT);
        int z0 = Math.max(box.min().z(), -WORLD_LIMIT);
        int z1 = Math.min(box.max().z(), WORLD_LIMIT);
        if (y0 > y1 || x0 > x1 || z0 > z1) {
            return null;
        }
        return new Box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    /** Middle-click: the block under the cursor becomes Paint's material, or joins Palette Paint's mix. */
    private void pickMaterial(ToolContext c) {
        if (cursor.missed()) {
            return;
        }
        BlockPos pos = cursor.pos();
        WorldReader world = worldOrNull(c);
        if (world == null || !world.isLoaded(pos.x() >> 4, pos.z() >> 4)) {
            return;
        }
        int state = world.get(pos.x(), pos.y(), pos.z());
        if (state == c.states().air()) {
            return;
        }
        BlockDescriptor block = c.states().describe(state);
        SettingsValues values = c.settings();
        if (kind == BrushTool.PAINT) {
            c.updateSettings(values.with(settings.material, block));
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.paint_material", block.format()));
            return;
        }
        BrushPress.addToMix(c, settings.palette, block);
    }

    // ---- Cursor ----

    private void updateCursor(ToolContext c) {
        WorldReader world = cursor.missed() ? null : worldOrNull(c);
        if (world == null) {
            samples = null;
            cursorFacing = null;
            services.clearCursor();
            return;
        }
        SettingsValues values = c.settings();
        int radius = effectiveRadius(values, limits(c));
        Shape shape = values.get(settings.shape);
        int depth = settings.depth == null ? 0 : values.get(settings.depth);
        BrushTool shown = controller.pressed() ? effectiveTool(invert) : effectiveTool(invertible() && Modifiers.alt(c.modifiers()));
        boolean surface = surface(values);
        SculptMode mode = surface ? SculptMode.SURFACE : SculptMode.TERRAIN;
        long cells = kind == BrushTool.HEIGHTMAP
                ? BrushPredictor.estimatedHeightmapCells(radius, effectiveHeight(values, limits(c)))
                : BrushPredictor.estimatedCells(shown, radius, shape, depth, mode);
        // The Biome brush writes no block: nothing to predict (the server's cursor shows it).
        boolean serverOnly = kind == BrushTool.BIOME || cells * copies(values) > MAX_PREDICTED_CELLS;
        cursorRadius = radius;
        cursorShape = shape;
        cursorColor = color(shown);
        cursorServerOnly = serverOnly;
        cursorTool = shown;
        if (surface) {
            // The ring lies across the way the surface faces here (drawn in renderWorld), not over the columns' tops.
            samples = null;
            cursorFacing = cachedFacing(world, radius);
            cursorFalloffStart = falloffStart(values.get(settings.falloff));
            services.clearCursor();
            return;
        }
        cursorFacing = null;
        BlockPos pos = cursor.pos();
        if (kind == BrushTool.HEIGHTMAP) {
            // The whole square, up to 257 wide: one column sampled per cell of a few columns once past radius 32.
            samples = sampler.sampleSquare(new ReaderTerrainProbe(world), pos.x(), pos.y(), pos.z(), radius,
                    services.changeStamp());
        } else {
            int sampleRadius = shape == Shape.SQUARE ? (int) Math.ceil(radius * Math.sqrt(2)) : radius;
            samples = sampler.sample(new ReaderTerrainProbe(world), pos.x(), pos.y(), pos.z(), sampleRadius,
                    services.changeStamp());
        }
        if (shape == Shape.CIRCLE) {
            services.showCursor(new BrushCursor(samples, falloffStart(values.get(settings.falloff)), cursorColor,
                    serverOnly, true));
        } else {
            // The overlay's cursor is round; the square outline is drawn in renderWorld.
            services.clearCursor();
        }
    }

    // ---- Surface mode ----

    /** Whether these settings sculpt in the Surface mode (Raise, Lower, Smooth, Flatten and Weather; the default). */
    private boolean surface(SettingsValues values) {
        return settings.mode != null && values.get(settings.mode) == SculptMode.SURFACE;
    }

    /**
     * The way the surface faces at a cursor hit, as the kernel estimates it for a dab there ({@link SurfaceNormal}, around
     * the block holding the hit point, over the brush's estimate radius), snapped to one of six directions; the hit face's
     * own direction when the estimate is zero (nothing solid around, or perfectly balanced), up without a world.
     */
    static Facing facingAt(WorldReader world, WorldCursor hit, int radius) {
        return facingAt(world, hit, radius, false);
    }

    /**
     * {@link #facingAt}, where a zero estimate gives up when {@code upWhenUnknown} (Raise and Lower, as the kernel
     * does) and the hit face otherwise (Flatten's plane, the ring of Smooth).
     */
    static Facing facingAt(WorldReader world, WorldCursor hit, int radius, boolean upWhenUnknown) {
        Dab point;
        try {
            point = Dab.of(0, hit.hitX(), hit.hitY(), hit.hitZ(), Dab.FULL_PRESSURE);
        } catch (IllegalArgumentException outOfRange) {
            return Facing.UP;
        }
        return facingAt(world, point, upWhenUnknown || hit.face() == null ? Facing.UP : facing(hit.face()), radius);
    }

    /** {@link #facingAt} for a dab's point, with {@code fallback} when the estimate is zero. */
    static Facing facingAt(WorldReader world, Dab point, Facing fallback, int radius) {
        long[] n = world == null ? new long[3]
                : SurfaceNormal.estimate(world, point.blockX(), point.blockY(), point.blockZ(),
                        SurfaceNormal.radiusFor(radius));
        if (n[0] == 0 && n[1] == 0 && n[2] == 0) {
            return fallback;
        }
        return SurfaceNormal.facing(n);
    }

    /**
     * {@link #facingAt} for copy {@code i}'s outline (up where the estimate is zero, as the kernel does for Raise and
     * Lower), worked out again only when its block, the radius or the world changed.
     */
    private Facing copyFacing(WorldReader world, int i, Dab copy, int radius) {
        long[] key = {copy.blockX(), copy.blockY(), copy.blockZ(), radius, services.changeStamp()};
        if (copyFacings[i] == null || !java.util.Arrays.equals(key, copyFacingKeys[i])) {
            copyFacings[i] = facingAt(world, copy, Facing.UP, radius);
            copyFacingKeys[i] = key;
        }
        return copyFacings[i];
    }

    /** {@link #facingAt} for the cursor, worked out again only when its block, the radius or the world changed. */
    private Facing cachedFacing(WorldReader world, int radius) {
        int bx = (int) Math.floor(cursor.hitX() * 16) >> 4;
        int by = (int) Math.floor(cursor.hitY() * 16) >> 4;
        int bz = (int) Math.floor(cursor.hitZ() * 16) >> 4;
        long stamp = services.changeStamp();
        if (facingCached == null || bx != facingBlock[0] || by != facingBlock[1] || bz != facingBlock[2]
                || cursor.face() != facingFace || radius != facingRadius || stamp != facingStamp) {
            facingCached = facingAt(world, cursor, radius, invertible());
            facingBlock[0] = bx;
            facingBlock[1] = by;
            facingBlock[2] = bz;
            facingFace = cursor.face();
            facingRadius = radius;
            facingStamp = stamp;
        }
        return facingCached;
    }

    /**
     * Surface-mode Flatten's plane for a press at {@code hit}: through the hit point, facing the way the surface faces
     * there ({@link #facingAt}); {@code null} when the hit lies beyond the plane's range.
     */
    static SurfacePlane planeAt(WorldReader world, WorldCursor hit, int radius) {
        try {
            Dab point = Dab.of(0, hit.hitX(), hit.hitY(), hit.hitZ(), Dab.FULL_PRESSURE);
            return SurfacePlane.through(facingAt(world, hit, radius), point.x16(), point.y16(), point.z16());
        } catch (IllegalArgumentException outOfRange) {
            return null;
        }
    }

    private static Facing facing(WorldCursor.Face face) {
        return switch (face) {
            case UP -> Facing.UP;
            case DOWN -> Facing.DOWN;
            case NORTH -> Facing.NORTH;
            case SOUTH -> Facing.SOUTH;
            case WEST -> Facing.WEST;
            case EAST -> Facing.EAST;
        };
    }

    /** Surface-mode Flatten's plane for the current press, or {@code null} (tests). */
    public SurfacePlane surfacePlane() {
        return surfacePlane;
    }

    // ---- Heightmap ----

    /** Looks at the heightmaps folder again; each file that can't be used is named once, in a one-line toast. */
    private void refreshPictures(ToolContext c) {
        for (HeightmapLibrary.BadFile bad : services.heightmaps().refresh()) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.heightmap_bad_file." + bad.reason().key(),
                    bad.name()));
        }
    }

    /**
     * The stroke's stamp: the chosen picture fitted to {@code 2 × radius + 1} columns and turned by the Angle setting, with
     * the Height and Mode; {@code null} (after a toast, once a press) when the picture is missing.
     */
    private HeightmapSpec heightmapSpec(ToolContext c, SettingsValues values, int radius) {
        byte[] heights = stamp(values, radius);
        if (heights == null) {
            if (!warnedPicture) {
                HeightmapRef ref = values.get(settings.heightmapImage);
                c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.heightmap_missing", ref.fileLabel()));
                warnedPicture = true;
            }
            return null;
        }
        return new HeightmapSpec(2 * radius + 1, heights, effectiveHeight(values, limits(c)),
                values.get(settings.heightmapMode) == BrushSettings.StampMode.LOWER);
    }

    /**
     * The stamp of the chosen picture for a brush of {@code radius}, turned by the Angle setting ({@link HeightImage#stamp}),
     * or {@code null} when the picture is missing. Worked out again only when the picture, size or angle changes.
     */
    public byte[] stamp(SettingsValues values, int radius) {
        HeightmapLibrary.Entry entry = services.heightmaps().entry(values.get(settings.heightmapImage)).orElse(null);
        if (entry == null) {
            return null;
        }
        int side = 2 * radius + 1;
        int angle = values.get(settings.heightmapAngle);
        if (entry != stampEntry || side != stampSide || angle != stampAngle) {
            stamp = entry.image().stamp(side, angle);
            stampEntry = entry;
            stampSide = side;
            stampAngle = angle;
        }
        return stamp;
    }

    /**
     * The Heightmap cursor's picture of the stamp: the square tinted by the picture's value, on the ground the cursor
     * sees, and a tick from the centre toward the picture's top edge (which R turns). Up to radius 32 each column is
     * tinted by the stamp's pixel; past it each cell of a few columns (the samples' step) by a stamp of the picture one
     * pixel a cell (at most 65 × 65), so resizing a wide stamp never resamples the whole picture at full size each frame
     * (from a 1,024-pixel picture that takes tens of milliseconds); the full stamp is made when a press sends it.
     */
    private void stampPreview(ToolContext c, WorldDraw d) {
        int radius = cursorRadius;
        if (samples == null || samples.radius() != radius) {
            return;
        }
        int side = 2 * radius + 1;
        int step = samples.step();
        int across = (side + step - 1) / step;
        byte[] cells = step == 1 ? stamp(c.settings(), radius) : previewStamp(c.settings(), across);
        if (cells == null) {
            return;
        }
        int x0 = samples.centerX() - radius, z0 = samples.centerZ() - radius;
        int rgb = cursorColor & 0xFFFFFF;
        for (int cv = 0; cv < across; cv++) {
            for (int cu = 0; cu < across; cu++) {
                int value = cells[cv * across + cu] & 0xFF;
                if (value == 0) {
                    continue;
                }
                int u = cu * step, v = cv * step;
                int ground = samples.heightAtWorld(x0 + u, z0 + v);
                if (ground == SurfaceSampler.NONE) {
                    continue;
                }
                int alpha = 0x18 + value * 0x90 / 255;
                d.surfaceQuad(x0 + u, z0 + v, x0 + Math.min(side, u + step), z0 + Math.min(side, v + step),
                        ground + 1 + BrushOutlines.LIFT, alpha << 24 | rgb);
            }
        }
        double angle = Math.toRadians(c.settings().get(settings.heightmapAngle));
        double cx = samples.centerX() + 0.5, cz = samples.centerZ() + 0.5;
        double y = samples.centerY() + 1 + 2 * BrushOutlines.LIFT;
        // The picture's top edge is north (-z) unturned; a clockwise turn takes it toward east.
        d.line(cx, y, cz, cx + Math.sin(angle) * radius, y, cz - Math.cos(angle) * radius, cursorColor);
    }

    /**
     * The chosen picture stamped {@code across} pixels wide, turned by the Angle setting, for the preview of a wide stamp;
     * {@code null} when the picture is missing. Kept while the picture, width and angle stay the same.
     */
    private byte[] previewStamp(SettingsValues values, int across) {
        HeightmapLibrary.Entry entry = services.heightmaps().entry(values.get(settings.heightmapImage)).orElse(null);
        if (entry == null) {
            return null;
        }
        int angle = values.get(settings.heightmapAngle);
        if (entry != previewEntry || across != previewAcross || angle != previewAngle) {
            previewCells = entry.image().stamp(across, angle);
            previewEntry = entry;
            previewAcross = across;
            previewAngle = angle;
        }
        return previewCells;
    }

    // ---- Helpers ----

    private boolean invertible() {
        return kind == BrushTool.RAISE || kind == BrushTool.LOWER;
    }

    private BrushTool effectiveTool(boolean inverted) {
        if (!inverted) {
            return kind;
        }
        return switch (kind) {
            case RAISE -> BrushTool.LOWER;
            case LOWER -> BrushTool.RAISE;
            default -> kind;
        };
    }

    private int effectiveRadius(SettingsValues values, Limits limits) {
        return clamp(values.get(settings.radius), BrushSpec.MIN_RADIUS, maxRadius(limits));
    }

    /** This brush's largest radius on this server: 32 (or {@code maxBrushRadius}); the Heightmap brush's 128. */
    private int maxRadius(Limits limits) {
        return limits.maxRadius(kind);
    }

    /**
     * What one dab costs in the dab rate and the in-flight window: its copies, times its stamp's weight for the Heightmap
     * brush ({@link HeightmapSpec#weight}: 16 at radius 128), as the server counts it. With Heightmap dabs counted in
     * flight until written ({@link Host#inFlight}), the window of 16 never holds more than the server's queue of 32 takes,
     * and a dab heavier than the window goes out alone once nothing is unwritten.
     */
    private int dabCost(SettingsValues values, Limits limits) {
        int copies = copies(values);
        if (kind != BrushTool.HEIGHTMAP) {
            return copies;
        }
        return copies * HeightmapSpec.weight(2 * effectiveRadius(values, limits) + 1);
    }

    /** The Height of a Heightmap stamp on this server: the setting, at most {@code maxHeightmapHeight}. */
    private int effectiveHeight(SettingsValues values, Limits limits) {
        return clamp(values.get(settings.heightmapHeight), 1, Math.min(HeightmapSpec.MAX_HEIGHT_BLOCKS,
                limits.maxHeightmapHeight()));
    }

    private static Limits limits(ToolContext c) {
        try {
            return c.session().permissions().limits();
        } catch (IllegalStateException noSession) {
            return Limits.DEFAULTS;
        }
    }

    /** Whether the player holds {@code limit.bypass} on this server (the stamp's limits don't apply to them). */
    private static boolean bypass(ToolContext c) {
        try {
            return c.session().permissions().has(Perm.LIMIT_BYPASS);
        } catch (IllegalStateException noSession) {
            return false;
        }
    }

    private static WorldReader worldOrNull(ToolContext c) {
        try {
            return c.world();
        } catch (IllegalStateException noSession) {
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** The controller's view of this tool. */
    private final class Host implements StrokeController.Host {
        @Override
        public StrokeHandle begin() {
            return beginServerStroke();
        }

        /**
         * Dabs in flight, each counted as its spec's mode's copies, plus the units of every stroke of the session the
         * server has not written ({@link EditorSession#unwrittenUnits}: earlier Heightmap stamps and Shape shapes, whatever
         * this brush is), as the server's queue holds them all for the player. A Heightmap stroke counts its queued dabs
         * and its sent ones until written, each its copies times its stamp's weight; the other brushes count dabs queued or
         * not acknowledged.
         */
        @Override
        public int inFlight(StrokeHandle stroke) {
            if (!(stroke instanceof FabricStrokeHandle fabric)) {
                return 0;
            }
            BrushSpec spec = fabric.spec();
            if (spec.heightmap() != null) {
                return fabric.queued() * stampUnits(spec) + unwrittenBefore();
            }
            return (fabric.unacknowledged() + fabric.queued()) * spec.symmetry().mode().copies() + unwrittenBefore();
        }

        /** Unwritten stamps and shapes of this session: a new press of any brush waits for room after them. */
        @Override
        public int inFlightBeforeStroke() {
            return unwrittenBefore();
        }

        @Override
        public void beforeDab(Dab dab) {
            keepBefore(dab);
        }
    }

    /** The session's unwritten units ({@link EditorSession#unwrittenUnits}), and at least this tool's own stamps'. */
    private int unwrittenBefore() {
        int session = 0;
        if (context != null) {
            try {
                session = context.session().unwrittenUnits();
            } catch (IllegalStateException noSession) {
                session = 0;
            }
        }
        return Math.max(session, kind == BrushTool.HEIGHTMAP ? unwrittenStamps() : 0);
    }

    /** What one dab of a Heightmap stroke of {@code spec} weighs in flight: its copies times its stamp's weight. */
    static int stampUnits(BrushSpec spec) {
        return spec.symmetry().mode().copies() * spec.heightmap().dabWeight();
    }

    /**
     * The units of Heightmap dabs sent that the server has not reported written, over the strokes begun (forgetting
     * strokes with none left that have ended).
     */
    private int unwrittenStamps() {
        int units = 0;
        for (int i = sentStamps.size() - 1; i >= 0; i--) {
            FabricStrokeHandle handle = sentStamps.get(i);
            int left = handle.unapplied();
            if (left == 0 && !handle.active()) {
                // Ended or refused: it sends nothing more.
                sentStamps.remove(i);
                continue;
            }
            units += left * stampUnits(handle.spec());
        }
        return units;
    }
}
