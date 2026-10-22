package dev.sculptory.mc.client.editor.tools.generate;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.HeightImage;
import dev.sculptory.core.brush.SurfaceScan;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.generate.FaceCells;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.GeneratedTooLargeException;
import dev.sculptory.core.generate.GroundFill;
import dev.sculptory.core.generate.GroundMap;
import dev.sculptory.core.generate.HeightmapAreaKernel;
import dev.sculptory.core.generate.LineKernel;
import dev.sculptory.core.generate.RoadKernel;
import dev.sculptory.core.generate.RoofKernel;
import dev.sculptory.core.generate.RoofStates;
import dev.sculptory.core.generate.SlopeSteps;
import dev.sculptory.core.generate.StairShapes;
import dev.sculptory.core.generate.SurfaceReader;
import dev.sculptory.core.generate.SweepKernel;
import dev.sculptory.core.generate.UnknownMaterialException;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSample;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StairSlabVariants;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.mc.client.editor.Translator;
import dev.sculptory.mc.client.editor.heightmap.HeightmapLibrary;
import dev.sculptory.mc.client.editor.heightmap.HeightmapRef;
import dev.sculptory.mc.client.editor.input.KeyAction;
import dev.sculptory.mc.client.editor.render.OverlayColors;
import dev.sculptory.mc.client.editor.render.ghost.GhostMasking;
import dev.sculptory.mc.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.mc.client.editor.render.ghost.GhostVolume;
import dev.sculptory.mc.client.editor.settings.SettingDef;
import dev.sculptory.mc.client.editor.settings.SettingsSchema;
import dev.sculptory.mc.client.editor.settings.SettingsValues;
import dev.sculptory.mc.client.editor.tool.DeactivateReason;
import dev.sculptory.mc.client.editor.tool.EditorAction;
import dev.sculptory.mc.client.editor.tool.FrameInfo;
import dev.sculptory.mc.client.editor.tool.HudDraw;
import dev.sculptory.mc.client.editor.tool.KeyHint;
import dev.sculptory.mc.client.editor.tool.Modifiers;
import dev.sculptory.mc.client.editor.tool.PointerEvent;
import dev.sculptory.mc.client.editor.tool.RaycastMode;
import dev.sculptory.mc.client.editor.tool.ScrollEvent;
import dev.sculptory.mc.client.editor.tool.Tool;
import dev.sculptory.mc.client.editor.tool.ToolContext;
import dev.sculptory.mc.client.editor.tool.ToolDescriptor;
import dev.sculptory.mc.client.editor.tool.ToolId;
import dev.sculptory.mc.client.editor.tool.WorldCursor;
import dev.sculptory.mc.client.editor.tool.WorldDraw;
import dev.sculptory.mc.client.editor.tools.place.GhostBaker;
import dev.sculptory.mc.client.editor.tools.place.PlaceTool;
import dev.sculptory.mc.client.editor.tools.points.PointEditor;
import dev.sculptory.mc.client.editor.world.Ray;
import dev.sculptory.mc.client.editor.world.ScreenProjector;
import dev.sculptory.mc.client.session.EditorSession;
import dev.sculptory.mc.client.session.Notice;
import dev.sculptory.mc.client.session.Permissions;
import dev.sculptory.mc.client.session.SessionNotices;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.server.engine.Perm;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Generate tool (palette slot 11, key -): roads along a
 * path of clicked nodes, roofs over the selection and lines through clicked points, generated on this client by the
 * {@code core.generate} kernels, previewed as ghosts and built through one sparse clipboard upload followed by an
 * ordinary paste (one job, one undo step; {@link GeneratedCommit}).
 *
 * <p><b>Path.</b> The nodes are edited with the shared {@link PointEditor}, as every path tool's points are: a click on the
 * ground adds a node at the hit block, a click on a node (or its column) selects it, its gizmo's arrows, squares and
 * centre move it (never onto terrain), a click on the drawn centre line inserts one, E adds one after the selected node,
 * Delete or Backspace removes the selected node (or the last one). Esc drops a node being inserted, puts a dragged node
 * back or deselects, then stops a preview being made, then clears the path. From two nodes on the road's ghost follows
 * the nodes and every setting; Enter builds it. The nodes stay, so the road can be adjusted and built again.
 * Ctrl+Scroll changes the width (Shift ×4).
 *
 * <p><b>Points in the air</b> ({@link AirPoints}, one marker distance for the Road, Line, Sweep and Stamp): over open
 * sky a click puts a node or point at the marker, Alt+Scroll moves the marker (or a point dragged freely) nearer
 * or farther. A Road node left in the air once placed or let go ({@link #inTheAir}: nothing solid in its block or the
 * one under it; not one only passing the sky in a drag) turns the road's Height to Straight, with a toast: Follow
 * terrain would lay it on the ground below; Esc clearing the road puts the Height back. The road is then a deck through
 * the air, its fill below only where something lies under it within its depth ({@link RoadKernel#deckGround});
 * columns in chunks not loaded are a bare deck, counted in a toast.
 *
 * <p><b>Roof.</b> The selection's bounds are the footprint: its bottom layer is the eaves level, its height is
 * ignored. The ghost follows the selection and every setting; Enter builds it.
 *
 * <p><b>Line</b> ({@link LineDraft}). Clicked blocks are its points,
 * edited as the road's nodes are; the path runs Straight, Curve or Hanging through them, 1-16 blocks
 * thick, round or square, of a block or a mix ({@link LineKernel}). Ctrl+Scroll changes the thickness; Enter builds it
 * (the paste is named "Line").
 *
 * <p><b>Sweep</b> (a second {@link LineDraft}). The selection's blocks, read from the client world, are the profile,
 * swept along the clicked points ({@link SweepKernel}); Enter builds it (named "Sweep").
 *
 * <p><b>Stamp</b> ({@link StampDraft}). The clipboard, or the asset picked with the Library's Stamp button, is copied
 * on each clicked block, turned along the path; Enter sends one multi-paste (named "Stamp"), not a generated upload.
 *
 * <p><b>Heightmap</b> ({@link HeightmapAreaKernel}). A picture from the {@link HeightmapLibrary} is stretched over the
 * selection's footprint, turned in quarter turns and inverted if set; each column's surface goes to Min Y plus the
 * picture's value times (Max Y - Min Y), cleared above and filled with its own layers below. Min Y and Max Y take the
 * selection's bottom and top whenever the selection changes, and can then be set. The columns are read on the client
 * thread when the preview starts; the kernel runs off it. Enter builds it as one undo step named "Heightmap"
 * ({@link OpLabel#HEIGHTMAP}).
 *
 * <p><b>Smart Surface</b> ({@link SmartSurfaceDraft}). Points on blocks make a closed outline and inside points (the
 * shared point editor); a terrain-like sheet fills the outline through every point, as a top layer over a body, a set
 * thickness or down to the ground; Enter builds it as one undo step named "Smart surface"
 * ({@link OpLabel#SMART_SURFACE}).
 *
 * <p><b>Previews</b> run on the background executor: the Path generator first snapshots the ground of the footprint's
 * columns from the client world on the client thread ({@link GroundMap}), the kernel and the ghost bake then run off
 * it; a result of an older change is dropped. A source over {@link #MAX_GHOST_CELLS} shows its bounds as an outline;
 * unloaded columns are outlined per chunk and refuse the build. Client thread only.
 */
public final class GenerateTool implements Tool {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    public static final String CLICK = "LMB";
    public static final int PATH_COLOUR = 0xFFFFC857;
    public static final int NODE_COLOUR = 0xFFFFE8A8;
    public static final int SELECTED_COLOUR = 0xFF7FD4FF;
    public static final int UNLOADED_COLOUR = 0xFFFF4A4A;
    /** Sources over this many cells are outlined instead of drawn as ghosts (the Place tool's capture cap). */
    public static final long MAX_GHOST_CELLS = PlaceTool.DEFAULT_MAX_CAPTURE_CELLS;
    /** The roof ghost's outline (and a road's bounds when it has no footprint): the path colour, a little dimmer. */
    public static final int OUTLINE_COLOUR = OverlayColors.scaleAlpha(PATH_COLOUR, 0.8);
    /** The most cells a preview holds for a player with {@code limit.bypass} (memory, not policy). */
    static final long BYPASS_PREVIEW_CELLS = 1L << 24;
    /** The seed of a weighted material mix: fixed, so rebuilding the same road gives the same mix. */
    static final long MIX_SEED = 0x5EEDL;

    public static final String NEEDS_NODES = "sculptory.notice.generate_needs_nodes";
    public static final String NEEDS_SELECTION = "sculptory.notice.generate_needs_selection";
    public static final String UNLOADED = "sculptory.notice.generate_unloaded";
    public static final String NOTHING = "sculptory.notice.generate_nothing";
    /** Hint while a node building needs is missing (the node as its argument). */
    public static final String NEEDS_PERMISSION_HINT = "sculptory.hint.generate.needs_permission";
    public static final String BLOCK_ENTITY = "sculptory.notice.generate_block_entity";
    public static final String SENT_PATH = "sculptory.notice.generate_path_sent";
    /** A Road node went into the air: the road's height turned to Straight. */
    public static final String ROAD_STRAIGHT = "sculptory.notice.generate_road_straight";
    /** A Straight road's columns in chunks not loaded: built without their fill below ([count]). */
    public static final String ROAD_BARE = "sculptory.notice.generate_road_bare";
    /** Use Stairs and Slabs: the block ([name]) has neither a stair nor a slab, so its steps stay full blocks. */
    public static final String NO_STEPS = "sculptory.notice.generate_no_steps";
    public static final String SENT_ROOF = "sculptory.notice.generate_roof_sent";
    public static final String OP_PATH = "sculptory.op.generate_path";
    public static final String OP_ROOF = "sculptory.op.generate_roof";
    public static final String OP_LINE = "sculptory.op.generate_line";
    public static final String SENT_LINE = "sculptory.notice.generate_line_sent";
    public static final String OP_SWEEP = "sculptory.op.generate_sweep";
    public static final String SENT_SWEEP = "sculptory.notice.generate_sweep_sent";
    public static final String SWEEP_NEEDS_SELECTION = "sculptory.notice.sweep_needs_selection";
    public static final String SWEEP_TOO_LARGE = "sculptory.notice.sweep_too_large";
    public static final String SWEEP_UNLOADED = "sculptory.notice.sweep_unloaded";
    public static final String SWEEP_EMPTY = "sculptory.notice.sweep_empty";
    public static final String SWEEP_LEFT_OUT = "sculptory.notice.sweep_left_out";
    /** The largest selection box a Sweep profile is read from. */
    static final long MAX_PROFILE_BOX = 262_144;
    public static final String OP_HEIGHTMAP = "sculptory.op.generate_heightmap";
    public static final String SENT_HEIGHTMAP = "sculptory.notice.generate_heightmap_sent";
    /** The Heightmap import left block entities as they are ([count]). */
    public static final String KEPT_BLOCK_ENTITIES = "sculptory.notice.generate_heightmap_kept";
    /** The most footprint columns a Heightmap import reads (a 1,024 × 1,024 selection). */
    public static final long MAX_HEIGHTMAP_COLUMNS = 1L << 20;
    /** The most cells a Heightmap import keeps of its columns (64 MB): past it the preview says it is too large. */
    public static final long MAX_HEIGHTMAP_CELLS = 16L << 20;

    /** What the tool needs from the client. */
    public interface Services {
        /** Shows these ghost previews from now on (an empty list shows none). */
        void showGhosts(List<GhostPlacement> placements);

        /** Frees a volume's meshes now (a volume no longer shown). */
        void releaseGhost(GhostVolume volume);

        /** Where kernels, ghost bakes and payload encoding run, off the client thread. */
        Executor background();

        String keyLabel(KeyAction action);

        /** Asks the player to confirm a large build; {@code onConfirm} runs only if they do. */
        void confirm(String opNameKey, long blocks, Runnable onConfirm);

        /** The pictures the Heightmap import picks from: the built-ins alone by default; the game's adds the folder. */
        default HeightmapLibrary heightmaps() {
            return HeightmapLibrary.builtInOnly();
        }

        /**
         * Changes whenever client blocks may have changed (the Heightmap import reads its columns again then); 0, never,
         * by default.
         */
        default long changeStamp() {
            return 0;
        }

        /**
         * The cursor ray of the event being handled (the crosshair ray while looking), for points in the air
         * ({@link AirPoints}); none by default, so a click on open sky places nothing.
         */
        default Optional<Ray> cursorRay() {
            return Optional.empty();
        }

        /** World to screen, for the point editor's gizmo and drawn path; none by default. */
        default Optional<ScreenProjector> projector() {
            return Optional.empty();
        }

        /** The camera's position (the point gizmo's size); none by default. */
        default Optional<double[]> eye() {
            return Optional.empty();
        }

        /** The editor's active block (the top bar's): Smart Surface's top and body unless set to a mix; stone by default. */
        default BlockDescriptor activeBlock() {
            return BlockDescriptor.of(new NamespacedId("minecraft:stone"));
        }

        /** Display text for a translation key (the air marker's distance label); the key itself by default. */
        default String translate(String key, Object... args) {
            return Translator.KEYS.translate(key, args);
        }

        /** Services without Minecraft: no ghosts, default key names, work on the caller's thread, confirms at once. */
        static Services headless() {
            return new Services() {
                @Override
                public void showGhosts(List<GhostPlacement> placements) {}

                @Override
                public void releaseGhost(GhostVolume volume) {}

                @Override
                public Executor background() {
                    return Runnable::run;
                }

                @Override
                public String keyLabel(KeyAction action) {
                    return dev.sculptory.mc.client.editor.input.EditorKeymap.defaults().display(action);
                }

                @Override
                public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
                    onConfirm.run();
                }
            };
        }
    }

    /**
     * A preview made: the cells, the road's unloaded columns, the ghost (null when over the cap or empty), the road's
     * centre line, ground (Follow terrain's; null for a Straight road, whose centre line runs at its nodes' heights) and
     * footprint (null for a roof, or a road over {@link RoadFootprint#MAX_COLUMNS} columns), or {@code tooLarge} when the
     * kernel passed the cap; the block-entity cells a Heightmap import leaves as they are; and the columns of a Straight
     * road with fill below in chunks this game has not loaded, built as a bare deck.
     */
    public record Built(GeneratedSource source, List<RoadKernel.Column> unloaded, GhostVolume ghost, RoadKernel.Path path,
                        GroundMap ground, RoadFootprint footprint, boolean tooLarge, long cap, long keptBlockEntities,
                        int bareColumns) {
        public Built {
            Objects.requireNonNull(source);
            unloaded = List.copyOf(unloaded);
        }

        /** A preview that left no block entity out (every kind but the Heightmap import). */
        public Built(GeneratedSource source, List<RoadKernel.Column> unloaded, GhostVolume ghost, RoadKernel.Path path,
                     GroundMap ground, RoadFootprint footprint, boolean tooLarge, long cap) {
            this(source, unloaded, ghost, path, ground, footprint, tooLarge, cap, 0, 0);
        }

        /** A preview with no bare road columns. */
        public Built(GeneratedSource source, List<RoadKernel.Column> unloaded, GhostVolume ghost, RoadKernel.Path path,
                     GroundMap ground, RoadFootprint footprint, boolean tooLarge, long cap, long keptBlockEntities) {
            this(source, unloaded, ghost, path, ground, footprint, tooLarge, cap, keptBlockEntities, 0);
        }

        static Built tooLarge(RoadKernel.Path path, GroundMap ground, long cap) {
            return new Built(GeneratedSource.empty(), List.of(), null, path, ground, null, true, cap);
        }

        public long cells() {
            return source.cells();
        }

        /** Whether it can be built as it is. */
        public boolean buildable() {
            return !tooLarge && unloaded.isEmpty() && !source.isEmpty();
        }
    }

    private final Services services;
    /**
     * Greyed out in the palette without {@code region}: a generated road or roof may hold any block state, as a Fill
     * (a {@code region} operation) may place it. Building also needs {@code clipboard}, for the upload and its paste
     * ({@link #missingNode}).
     */
    private final ToolDescriptor descriptor = new ToolDescriptor(ToolId.GENERATE, "sculptory.tool.generate",
            "minecraft:oak_stairs", Perm.REGION);
    private final PathModel path = new PathModel();
    private ToolContext context;
    private WorldCursor cursor = WorldCursor.miss(0, 0, 0);
    /** The shared point editor of the road's nodes. */
    private final PointEditor roadEditor;
    /** The Height before a node in the air turned it Straight (Esc clearing the road puts it back), or null. */
    private RoadKernel.HeightMode heightBeforeAir;
    /** The preview needs making again (a node, selection or setting changed). */
    private boolean dirty = true;
    /** Bumped on every change, so a result of an older change is dropped. */
    private int generation;
    private CompletableFuture<Built> running;
    private int runningGeneration;
    /** The latest preview, or null. */
    private Built shown;
    private GhostVolume ghost;
    /** The selection bounds the roof preview was made for. */
    private Box roofFootprint;
    /** The selection bounds the sweep's preview was made for. */
    private Box sweepProfile;
    /** The selection the Heightmap import's Min Y and Max Y were last set from. */
    private Box rangeFrom;
    /** The Heightmap import's last capture of the footprint's columns, and what it was made for ({@link #capture}). */
    private HeightmapAreaKernel.Column[] captured;
    private Box capturedBox;
    private int capturedHi;
    private int capturedMinY;
    private long capturedStamp;
    /** Enter came while the preview was being made: build as soon as it lands. */
    private boolean buildWhenReady;
    /** Builds the road's and the roof's sources. */
    private final GeneratedCommit commit;
    /** The Line's points, preview and build. */
    private final LineDraft line;
    /** The Sweep's points, preview and build (the profile is the selection). */
    private final LineDraft sweep;
    /** The Stamp's points, ghosts and build. */
    private final StampDraft stamp;
    /** Smart Surface's points, preview and build. */
    private final SmartSurfaceDraft smart;
    /** Points in the air for the Road, Line, Sweep and Stamp: one marker distance for them all. */
    private final AirPoints air = new AirPoints();
    /** The mouse at the last frame (scaled pixels), for the marker's distance label. */
    private double mouseX, mouseY;
    /** The count of profile blocks left out the last toast told, so making the preview again does not repeat it. */
    private long toldLeftOut = -1;
    /** The family resolver Use Stairs and Slabs reads (per state space), and the block the last "no stairs" toast named. */
    private StairSlabVariants variants;
    private String toldNoSteps;
    /** The bare road columns (unloaded, no fill) the last toast told of, so a preview made again does not repeat it. */
    private int toldBare;
    /** Where the shown ghost is placed: the source's corner, or the masked cells'. */
    private BlockPos ghostOrigin;
    /** The kind the last frame ran with: a change drops the other kind's preview. */
    private GenerateSettings.Kind lastKind;

    public GenerateTool(Services services) {
        this.services = Objects.requireNonNull(services);
        this.commit = new GeneratedCommit(services);
        this.line = new LineDraft(() -> services, PATH_COLOUR).withAirPoints(air);
        this.sweep = new LineDraft(() -> services, PATH_COLOUR).withAirPoints(air);
        this.stamp = new StampDraft(() -> services, air);
        this.smart = new SmartSurfaceDraft(services, air);
        PointEditor.View view = GenerateTool.pointView(() -> services);
        this.roadEditor = new PointEditor(path, air, view, new PointEditor.Host() {
            @Override
            public BlockPos pointOn(WorldCursor at) {
                return at.pos();
            }

            @Override
            public List<List<PathSample>> drawnSegments(List<BlockPos> nodes, boolean closed) {
                return roadSegments(nodes);
            }

            @Override
            public BlockPos insertPoint(List<BlockPos> nodes, int segment, double[] at) {
                // The centre line is drawn on the ground: the node takes the height of the nodes either side instead.
                return levelledBetween(nodes.get(segment), nodes.get(segment + 1), at);
            }

            @Override
            public void edited(ToolContext c, PointEditor.Edit edit) {
                if (!edit.changesPoints()) return;
                changed();
                // A node left in the air (nothing solid in its block or the one under it) once it is placed or let go.
                boolean placed = edit.kind() == PointEditor.EditKind.ADD || edit.kind() == PointEditor.EditKind.INSERT
                        || edit.kind() == PointEditor.EditKind.MOVE;
                if (placed && edit.finished() && inTheAir(c, edit.after())) straightForTheAir(c);
            }
        });
    }

    /** The point editor's aim from {@code services} (read each time: a host may get the game's after it is built). */
    public static PointEditor.View pointView(Supplier<Services> services) {
        return new PointEditor.View() {
            @Override
            public Optional<Ray> cursorRay() {
                return services.get().cursorRay();
            }

            @Override
            public Optional<ScreenProjector> projector() {
                return services.get().projector();
            }

            @Override
            public Optional<double[]> eye() {
                return services.get().eye();
            }
        };
    }

    /** The road's drawn centre line split at its nodes (the shown preview's, when made for these nodes), else chords. */
    private List<List<PathSample>> roadSegments(List<BlockPos> nodes) {
        Built built = shown;
        if (built == null || built.path() == null || !built.path().nodes().equals(nodes)) {
            return PointEditor.chords(nodes, false);
        }
        RoadKernel.Path line = built.path();
        List<List<PathSample>> segments = new ArrayList<>();
        for (int s = 0; s + 1 < nodes.size(); s++) {
            List<PathSample> samples = new ArrayList<>();
            for (int v = line.nodeVertex(s); v <= line.nodeVertex(s + 1); v++) {
                samples.add(new PathSample(line.x(v), centreLineY(built, v), line.z(v), line.along(v)));
            }
            if (samples.size() == 1) samples.add(samples.get(0));
            segments.add(samples);
        }
        return segments;
    }

    /** The Sweep's points and preview (for tests and the HUD). */
    public LineDraft sweep() {
        return sweep;
    }

    /** The Stamp's points (for tests and the HUD). */
    public StampDraft stamp() {
        return stamp;
    }

    /** Smart Surface's points and preview (for tests and the HUD). */
    public SmartSurfaceDraft smartSurface() {
        return smart;
    }

    /** The Library's Stamp: stamps the asset at {@code path} from now on (when the Stamp source is the Library). */
    public void useStampAsset(String path, String contentHash) {
        stamp.useAsset(path, contentHash);
    }

    /** The path's nodes (for tests and the HUD). */
    public PathModel path() {
        return path;
    }

    /** The Line's points and preview (for tests and the HUD). */
    public LineDraft line() {
        return line;
    }

    /** The point editor of a kind that has points (Path's road nodes, Line, Sweep, Stamp, Smart Surface), if it has. */
    public Optional<PointEditor> editor(GenerateSettings.Kind kind) {
        return Optional.ofNullable(switch (kind) {
            case PATH -> roadEditor;
            case LINE -> line.editor();
            case SWEEP -> sweep.editor();
            case STAMP -> stamp.editor();
            case SMART_SURFACE -> smart.editor();
            case ROOF, HEIGHTMAP -> null;
        });
    }

    /** The latest preview, if any. */
    public Optional<Built> preview() {
        return Optional.ofNullable(shown);
    }

    /** Whether a preview is being made. */
    public boolean previewRunning() {
        return running != null;
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public SettingsSchema schema() {
        return GenerateSettings.SCHEMA;
    }

    @Override
    public RaycastMode raycastMode(SettingsValues s) {
        return RaycastMode.TERRAIN;
    }

    @Override
    public void activate(ToolContext c) {
        context = c;
        dirty = true;
        if (c.settings().get(GenerateSettings.KIND) == GenerateSettings.Kind.HEIGHTMAP) refreshPictures(c);
        SettingsValues values = c.settings();
        if (!values.get(GenerateSettings.HEIGHTMAP_MIN_Y).equals(GenerateSettings.HEIGHTMAP_MIN_Y.defaultValue())
                || !values.get(GenerateSettings.HEIGHTMAP_MAX_Y).equals(GenerateSettings.HEIGHTMAP_MAX_Y.defaultValue())) {
            // Min Y and Max Y were set (by hand or a preset): they stay until the selection changes.
            rangeFrom = c.selection().orElse(null);
        }
    }

    @Override
    public void deactivate(ToolContext c, DeactivateReason r) {
        roadEditor.deactivate(c);
        dropPreview();
        commit.cancel();
        line.deactivate(c);
        sweep.deactivate(c);
        stamp.hide(c);
        smart.deactivate(c);
        context = null;
    }

    // ---- Input ----

    @Override
    public boolean onPointer(ToolContext c, PointerEvent e) {
        cursor = e.cursor();
        GenerateSettings.Kind kind = c.settings().get(GenerateSettings.KIND);
        if (kind == GenerateSettings.Kind.LINE) return line.onPointer(c, e, WorldCursor::pos);
        if (kind == GenerateSettings.Kind.SWEEP) return sweep.onPointer(c, e, WorldCursor::pos);
        if (kind == GenerateSettings.Kind.STAMP) return stamp.onPointer(c, e);
        if (kind == GenerateSettings.Kind.SMART_SURFACE) return smart.onPointer(c, e);
        if (kind != GenerateSettings.Kind.PATH) return false;
        return roadEditor.onPointer(c, e);
    }

    /**
     * Whether a node at {@code node} is in the air: nothing solid in its block or the one under it (both open,
     * {@link SurfaceScan#open}: air, plants, fluid). A node on the ground is the ground block itself; one a block or so
     * over it still has the ground under it. False without a world, or in a chunk not loaded.
     */
    static boolean inTheAir(ToolContext c, BlockPos node) {
        WorldReader world = worldOrNull(c);
        if (node == null || world == null || !world.isLoaded(node.x() >> 4, node.z() >> 4)) return false;
        for (int y = node.y(); y >= node.y() - 1; y--) {
            if (y < world.bottomY() || y >= world.topYExclusive()) continue;
            if (!SurfaceScan.open(world.states().flags(world.get(node.x(), y, node.z())))) return false;
        }
        return true;
    }

    /**
     * The cell under drawn point {@code at} of the stretch from {@code from} to {@code to}, at the height of those two
     * nodes there (by how far along their level span {@code at} lies), not at the drawn line's.
     */
    static BlockPos levelledBetween(BlockPos from, BlockPos to, double[] at) {
        double ax = from.x() + 0.5, az = from.z() + 0.5, dx = to.x() - from.x(), dz = to.z() - from.z();
        double span = dx * dx + dz * dz;
        double t = span > 0 ? Math.max(0, Math.min(1, ((at[0] - ax) * dx + (at[2] - az) * dz) / span)) : 0;
        int y = (int) Math.floor(from.y() + t * (to.y() - from.y()) + 0.5);
        return new BlockPos((int) Math.floor(at[0]), y, (int) Math.floor(at[2]));
    }

    /**
     * A Road node went into the air: Follow terrain would lay the road on the ground under it, so the road's height turns
     * to Straight (the nodes' heights), with a toast saying so.
     */
    private void straightForTheAir(ToolContext c) {
        SettingsValues values = c.settings();
        if (values.get(GenerateSettings.HEIGHT) != RoadKernel.HeightMode.FOLLOW_TERRAIN) return;
        heightBeforeAir = RoadKernel.HeightMode.FOLLOW_TERRAIN;
        c.updateSettings(values.with(GenerateSettings.HEIGHT, RoadKernel.HeightMode.STRAIGHT));
        c.notify(Notice.of(Notice.Level.INFO, ROAD_STRAIGHT));
    }

    /**
     * Esc cleared the road: a Height turned Straight for its nodes in the air goes back to what it was (when it is still
     * Straight).
     */
    private void restoreHeight(ToolContext c) {
        RoadKernel.HeightMode before = heightBeforeAir;
        heightBeforeAir = null;
        SettingsValues values = c.settings();
        if (before == null || values.get(GenerateSettings.HEIGHT) != RoadKernel.HeightMode.STRAIGHT) return;
        c.updateSettings(values.with(GenerateSettings.HEIGHT, before));
    }

    /** Whether {@code kind} makes path points (and so takes points in the air). */
    static boolean takesPoints(GenerateSettings.Kind kind) {
        return kind == GenerateSettings.Kind.PATH || kind == GenerateSettings.Kind.LINE
                || kind == GenerateSettings.Kind.SWEEP || kind == GenerateSettings.Kind.STAMP;
    }

    /** Points in the air: the marker's distance (for tests). */
    public AirPoints air() {
        return air;
    }

    @Override
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        GenerateSettings.Kind kind = c.settings().get(GenerateSettings.KIND);
        if (kind == GenerateSettings.Kind.SMART_SURFACE) return smart.onScroll(c, e);
        if (takesPoints(kind) && Modifiers.alt(e.modifiers()) && !Modifiers.control(e.modifiers())) {
            // Alt+Scroll: the air marker (or the point dragged freely) nearer or farther.
            return switch (kind) {
                case LINE -> line.onScroll(c, e);
                case SWEEP -> sweep.onScroll(c, e);
                case STAMP -> stamp.onScroll(c, e);
                default -> roadEditor.onScroll(c, e);
            };
        }
        if (e.amount() == 0 || (kind != GenerateSettings.Kind.PATH && kind != GenerateSettings.Kind.LINE)) return false;
        if (!Modifiers.control(e.modifiers())) return false;
        int step = (e.amount() > 0 ? 1 : -1) * (Modifiers.shift(e.modifiers()) ? 4 : 1);
        SettingsValues values = c.settings();
        if (kind == GenerateSettings.Kind.LINE) {
            int thickness = Math.max(LineKernel.MIN_THICKNESS, Math.min(LineKernel.MAX_THICKNESS,
                    values.get(GenerateSettings.LINE_THICKNESS) + step));
            c.updateSettings(values.with(GenerateSettings.LINE_THICKNESS, thickness));
            return true;
        }
        int width = Math.max(RoadKernel.MIN_WIDTH, Math.min(RoadKernel.MAX_WIDTH, values.get(GenerateSettings.WIDTH) + step));
        c.updateSettings(values.with(GenerateSettings.WIDTH, width));
        return true;
    }

    @Override
    public boolean onAction(ToolContext c, EditorAction a) {
        GenerateSettings.Kind current = c.settings().get(GenerateSettings.KIND);
        if (current == GenerateSettings.Kind.LINE) return line.onAction(c, a, this::lineSetup);
        // Sweep and Stamp take Delete and Backspace even with no point left: they would erase the selection, which is
        // the Sweep's profile.
        boolean removal = a == EditorAction.ERASE_SELECTION || a == EditorAction.REMOVE_NODE;
        if (current == GenerateSettings.Kind.SWEEP) return sweep.onAction(c, a, this::sweepSetup) || removal;
        if (current == GenerateSettings.Kind.STAMP) return stamp.onAction(c, a) || removal;
        if (current == GenerateSettings.Kind.SMART_SURFACE) return smart.onAction(c, a);
        boolean pathMode = c.settings().get(GenerateSettings.KIND) == GenerateSettings.Kind.PATH;
        if (pathMode && roadEditor.onAction(c, a)) return true;
        switch (a) {
            case CANCEL -> {
                if (running != null) {
                    generation++;
                    running = null;
                    dirty = false;
                    buildWhenReady = false;
                    return true;
                }
                if (pathMode && !path.isEmpty()) {
                    roadEditor.clear(c);
                    restoreHeight(c);
                    return true;
                }
                return false;
            }
            case ERASE_SELECTION, REMOVE_NODE -> {
                // With nodes the point editor took it; with none, Delete erases the selection as everywhere.
                return false;
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

    @Override
    public void onSettingsChanged(ToolContext c, SettingsValues before, SettingsValues after) {
        if (!before.get(GenerateSettings.HEIGHTMAP_MIN_Y).equals(after.get(GenerateSettings.HEIGHTMAP_MIN_Y))
                || !before.get(GenerateSettings.HEIGHTMAP_MAX_Y).equals(after.get(GenerateSettings.HEIGHTMAP_MAX_Y))) {
            // Set here (typed, or a preset loaded): kept until the selection changes.
            rangeFrom = c.selection().orElse(null);
        }
        if (!before.get(GenerateSettings.STAIRS).equals(after.get(GenerateSettings.STAIRS))) {
            StateSpace states = statesOrNull(c);
            if (states != null) {
                RoofMaterials.Derived derived = RoofMaterials.derive(states, after.get(GenerateSettings.STAIRS));
                SettingsValues filled = after;
                if (derived.slab().isPresent()) filled = filled.with(GenerateSettings.SLAB, derived.slab().get());
                if (derived.full().isPresent()) filled = filled.with(GenerateSettings.FULL, derived.full().get());
                if (!filled.equals(after)) {
                    c.updateSettings(filled);
                    return; // the update comes back through here
                }
            }
        }
        changed();
        line.changed();
        sweep.changed();
        smart.changed();
    }

    @Override
    public void frame(ToolContext c, FrameInfo f) {
        cursor = f.cursor();
        GenerateSettings.Kind kind = c.settings().get(GenerateSettings.KIND);
        mouseX = f.mouseX();
        mouseY = f.mouseY();
        if (!takesPoints(kind)) air.hide();
        if (kind != lastKind) {
            // The Line, Sweep and Stamp show their own ghosts: the kind left hides its own (the road's or roof's comes
            // back when its kind does).
            if (lastKind != null) {
                switch (lastKind) {
                    case LINE -> line.hide(c);
                    case SWEEP -> sweep.hide(c);
                    case STAMP -> stamp.hide(c);
                    case SMART_SURFACE -> smart.hide(c);
                    case PATH -> {
                        roadEditor.deactivate(c);
                        dropPreview();
                    }
                    default -> dropPreview();
                }
            }
            if (kind == GenerateSettings.Kind.HEIGHTMAP && lastKind != null) refreshPictures(c);
            dirty = true;
            lastKind = kind;
        }
        line.pollBuild(c);
        sweep.pollBuild(c);
        smart.pollBuild(c);
        switch (kind) {
            case LINE -> {
                line.frame(c, f, this::lineSetup, WorldCursor::pos);
                commit.poll(c);
                return;
            }
            case SWEEP -> {
                // The profile is the selection: a selection changed makes the preview again.
                Box profile = c.selection().orElse(null);
                if (!Objects.equals(profile, sweepProfile)) {
                    sweepProfile = profile;
                    sweep.changed();
                }
                sweep.frame(c, f, this::sweepSetup, WorldCursor::pos);
                commit.poll(c);
                return;
            }
            case STAMP -> {
                stamp.frame(c, f);
                commit.poll(c);
                return;
            }
            case SMART_SURFACE -> {
                smart.frame(c, f);
                commit.poll(c);
                return;
            }
            case PATH -> roadEditor.frame(c, f);
            default -> {
            }
        }
        if (kind == GenerateSettings.Kind.ROOF || kind == GenerateSettings.Kind.HEIGHTMAP) {
            Box footprint = c.selection().orElse(null);
            if (!Objects.equals(footprint, roofFootprint)) {
                roofFootprint = footprint;
                changed();
            }
            if (kind == GenerateSettings.Kind.HEIGHTMAP && footprint != null && !footprint.equals(rangeFrom)) {
                // A new selection: Min Y and Max Y start at its bottom and top.
                rangeFrom = footprint;
                SettingsValues values = c.settings();
                SettingsValues ranged = values.with(GenerateSettings.HEIGHTMAP_MIN_Y, clampY(footprint.min().y()))
                        .with(GenerateSettings.HEIGHTMAP_MAX_Y, clampY(footprint.max().y()));
                if (!ranged.equals(values)) c.updateSettings(ranged);
            }
        }
        pollPreview();
        if (dirty && running == null) {
            start(c);
            pollPreview(); // an executor that runs at once (tests, tiny work) has the result already
        }
        commit.poll(c);
    }

    // ---- Previews ----

    private void changed() {
        dirty = true;
        generation++;
    }

    /** Takes a finished preview, if it is still the one wanted. */
    private void pollPreview() {
        if (running == null || !running.isDone()) return;
        CompletableFuture<Built> done = running;
        running = null;
        Built built;
        try {
            built = done.getNow(null);
        } catch (CompletionException | CancellationException e) {
            LOG.warn("Sculptory: generating a preview failed", e.getCause() != null ? e.getCause() : e);
            built = null;
        }
        if (built == null || runningGeneration != generation) {
            if (built != null && built.ghost() != null) services.releaseGhost(built.ghost());
            return;
        }
        show(built);
    }

    private void show(Built built) {
        built = masked(built);
        if (ghost != null && ghost != built.ghost()) services.releaseGhost(ghost);
        ghost = built.ghost();
        shown = built;
        if (built.bareColumns() != toldBare) {
            toldBare = built.bareColumns();
            if (toldBare > 0 && context != null) {
                context.notify(Notice.of(Notice.Level.INFO, ROAD_BARE, Integer.toString(toldBare)));
            }
        }
        if (buildWhenReady && context != null) {
            buildWhenReady = false;
            build(context);
        }
        if (ghost != null) {
            BlockPos min = ghostOrigin;
            services.showGhosts(List.of(GhostPlacement.of(ghost, min.x(), min.y(), min.z())));
        } else {
            services.showGhosts(List.of());
        }
    }

    /**
     * {@code built} with its ghost showing only what the global mask lets through (the mask is judged here, on the
     * client thread): the same preview while the mask leaves every cell in, else one whose ghost is baked again.
     */
    private Built masked(Built built) {
        ghostOrigin = built.source().isEmpty() ? null : built.source().bounds().min();
        if (built.ghost() == null || context == null) return built;
        WorldReader world = worldOrNull(context);
        StateSpace states = statesOrNull(context);
        if (world == null || states == null) return built;
        GeneratedSource visible = GhostMasking.filter(built.source(), world);
        if (visible == built.source()) return built;
        services.releaseGhost(built.ghost());
        GhostVolume baked = visible.isEmpty() ? null : GhostBaker.fromSparse(visible, states);
        ghostOrigin = visible.isEmpty() ? null : visible.bounds().min();
        return new Built(built.source(), built.unloaded(), baked, built.path(), built.ground(), built.footprint(),
                built.tooLarge(), built.cap(), built.keptBlockEntities(), built.bareColumns());
    }

    private void dropPreview() {
        generation++;
        running = null;
        buildWhenReady = false;
        if (ghost != null) services.releaseGhost(ghost);
        ghost = null;
        shown = null;
        services.showGhosts(List.of());
    }

    /** Starts making the preview for the settings, nodes and selection as they are now. */
    private void start(ToolContext c) {
        dirty = false;
        Supplier<Built> task = task(c);
        if (task == null) {
            if (shown != null) dropPreview();
            return;
        }
        runningGeneration = generation;
        running = CompletableFuture.supplyAsync(task, services.background());
    }

    /** The work of a preview, or null when there is nothing to preview (after telling the player why, if a setting). */
    private Supplier<Built> task(ToolContext c) {
        SettingsValues values = c.settings();
        StateSpace states = statesOrNull(c);
        WorldReader world = worldOrNull(c);
        if (states == null || world == null) return null;
        long cap = bypass(c) ? BYPASS_PREVIEW_CELLS : limits(c).maxClipboardVolume();
        int bottom = world.bottomY(), top = world.topYExclusive();
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.PATH) {
            if (path.size() < 2) return null;
            RoadKernel.Spec spec = roadSpec(c, values, states);
            if (spec == null) return null;
            RoadKernel.Path laid = RoadKernel.path(spec);
            boolean follow = spec.heightMode() == RoadKernel.HeightMode.FOLLOW_TERRAIN;
            GroundMap ground;
            if (follow) {
                ground = new GroundMap();
                SurfaceReader surface = SurfaceReader.of(world);
                ground.capture(surface, laid.columnKeys());
                ground.capture(surface, laid.sampleColumnKeys());
            } else {
                // Straight: the fill below reaches down only where it meets something under the deck, read here.
                ground = RoadKernel.deckGround(spec, laid, world);
            }
            // The centre line is drawn on the ground Follow terrain read; a Straight road's at its nodes' heights.
            GroundMap drawn = follow ? ground : null;
            // Use Stairs and Slabs: the road's steps turned along its own direction.
            SlopeSteps.Variants steps = values.get(GenerateSettings.PATH_STAIRS_AND_SLABS)
                    ? steps(c, states, spec.material(), spec.border()) : null;
            return () -> {
                try {
                    RoadKernel.Result result = RoadKernel.generate(spec, laid, ground, bottom, top, cap);
                    GeneratedSource source = steps == null ? result.source()
                            : SlopeSteps.apply(result.source(), SlopeSteps.byColumn(laid.climbAxes()), steps, states.air())
                                    .source();
                    return new Built(source, result.unloaded(), ghostOf(source, states), laid, drawn,
                            RoadFootprint.of(source, states.air()), false, cap, 0, result.bareColumns());
                } catch (GeneratedTooLargeException e) {
                    return Built.tooLarge(laid, drawn, cap);
                }
            };
        }
        Box footprint = c.selection().orElse(null);
        if (footprint == null) return null;
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.HEIGHTMAP) {
            return heightmapTask(c, values, footprint, world, cap);
        }
        RoofKernel.Spec spec = roofSpec(values, footprint, states.air());
        RoofStates roof;
        try {
            roof = RoofStates.resolve(states, GenerateSettings.materials(values));
        } catch (UnknownMaterialException e) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", e.state().format()));
            return null;
        }
        if (!placeable(c, roof.full(), states) || !placeable(c, roof.slab(), states)
                || !placeable(c, roof.stair(dev.sculptory.core.region.Facing.NORTH, StairShapes.Shape.STRAIGHT), states)) {
            return null;
        }
        return () -> {
            try {
                GeneratedSource source = RoofKernel.generate(spec, roof, bottom, top, cap);
                return new Built(source, List.of(), ghostOf(source, states), null, null, null, false, cap);
            } catch (GeneratedTooLargeException e) {
                return Built.tooLarge(null, null, cap);
            }
        };
    }

    /**
     * The Heightmap import's preview work: the footprint's columns are read here, on the client thread, and the picture
     * stretched and the kernel run off it. {@code null} (after a toast) when the picture is missing; too large past
     * {@link #MAX_HEIGHTMAP_COLUMNS} columns.
     */
    private Supplier<Built> heightmapTask(ToolContext c, SettingsValues values, Box footprint, WorldReader world, long cap) {
        HeightmapRef ref = values.get(GenerateSettings.HEIGHTMAP_IMAGE);
        HeightImage image = services.heightmaps().image(ref).orElse(null);
        if (image == null) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.heightmap_missing", ref.fileLabel()));
            return null;
        }
        long columns = (long) footprint.sizeX() * footprint.sizeZ();
        if (columns > MAX_HEIGHTMAP_COLUMNS) {
            long tooMany = Math.min(cap, columns);
            return () -> Built.tooLarge(null, null, tooMany);
        }
        int minY = Math.min(values.get(GenerateSettings.HEIGHTMAP_MIN_Y), values.get(GenerateSettings.HEIGHTMAP_MAX_Y));
        int maxY = Math.max(values.get(GenerateSettings.HEIGHTMAP_MIN_Y), values.get(GenerateSettings.HEIGHTMAP_MAX_Y));
        int turns = values.get(GenerateSettings.HEIGHTMAP_TURN).quarterTurns();
        boolean invert = values.get(GenerateSettings.HEIGHTMAP_INVERT);
        int bottom = world.bottomY(), top = world.topYExclusive();
        StateSpace states = world.states();
        HeightmapAreaKernel.Column[] read = capture(world, footprint, minY, maxY);
        if (read == null) {
            long tooMany = Math.min(cap, MAX_HEIGHTMAP_CELLS + 1);
            return () -> Built.tooLarge(null, null, tooMany);
        }
        int x0 = footprint.min().x(), z0 = footprint.min().z(), width = footprint.sizeX();
        return () -> {
            byte[] heights = image.stretch(width, footprint.sizeZ(), turns, invert);
            HeightmapAreaKernel.Spec spec = new HeightmapAreaKernel.Spec(footprint, minY, maxY, heights);
            try {
                HeightmapAreaKernel.Result result = HeightmapAreaKernel.generate(spec,
                        (x, z) -> read[(z - z0) * width + (x - x0)], states, bottom, top, cap);
                List<RoadKernel.Column> unloaded = new ArrayList<>();
                for (long[] column : result.unloaded()) {
                    unloaded.add(new RoadKernel.Column((int) column[0], (int) column[1], 0, 0, false));
                }
                return new Built(result.source(), unloaded, ghostOf(result.source(), states), null, null, null, false, cap,
                        result.keptBlockEntities());
            } catch (GeneratedTooLargeException e) {
                return Built.tooLarge(null, null, cap);
            }
        };
    }

    /**
     * The footprint's columns for an import from {@code minY} to {@code maxY}, read on this thread: the last capture
     * when it still serves (the same footprint and top of the range, no block changed since, made for this Min Y or a lower one), else a
     * new one. {@code null} when the columns would hold more than {@link #MAX_HEIGHTMAP_CELLS} cells.
     */
    private HeightmapAreaKernel.Column[] capture(WorldReader world, Box footprint, int minY, int maxY) {
        int[] range = HeightmapAreaKernel.range(footprint, minY, maxY, world.bottomY(), world.topYExclusive());
        long stamp = services.changeStamp();
        if (captured != null && footprint.equals(capturedBox) && range[1] == capturedHi && minY >= capturedMinY
                && stamp == capturedStamp) {
            return captured;
        }
        captured = null;
        int width = footprint.sizeX(), x0 = footprint.min().x(), z0 = footprint.min().z();
        HeightmapAreaKernel.Column[] read = new HeightmapAreaKernel.Column[width * footprint.sizeZ()];
        long cells = 0;
        for (int z = 0; z < footprint.sizeZ(); z++) {
            for (int x = 0; x < width; x++) {
                HeightmapAreaKernel.Column column = HeightmapAreaKernel.capture(world, x0 + x, z0 + z, range[0], range[1],
                        minY);
                read[z * width + x] = column;
                if (column != null) cells += column.cells().length;
                if (cells > MAX_HEIGHTMAP_CELLS) return null;
            }
        }
        captured = read;
        capturedBox = footprint;
        capturedHi = range[1];
        capturedMinY = minY;
        capturedStamp = stamp;
        return read;
    }

    /** Looks at the heightmaps folder again; each file that can't be used is named once, in a one-line toast. */
    private void refreshPictures(ToolContext c) {
        for (HeightmapLibrary.BadFile bad : services.heightmaps().refresh()) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.heightmap_bad_file." + bad.reason().key(),
                    bad.name()));
        }
    }

    private static int clampY(int y) {
        return Math.max(GenerateSettings.MIN_HEIGHTMAP_Y, Math.min(GenerateSettings.MAX_HEIGHTMAP_Y, y));
    }

    private static GhostVolume ghostOf(GeneratedSource source, StateSpace states) {
        if (source.isEmpty() || source.cells() > MAX_GHOST_CELLS) return null;
        return GhostBaker.fromSparse(source, states);
    }

    /** The road spec of the settings, or null (after a toast) when a block can't be used. */
    private RoadKernel.Spec roadSpec(ToolContext c, SettingsValues values, StateSpace states) {
        Pattern material;
        if (values.get(GenerateSettings.MATERIAL) == GenerateSettings.Material.PALETTE) {
            material = mix(c, values.get(GenerateSettings.PALETTE), states);
        } else {
            material = single(c, values.get(GenerateSettings.BLOCK), states);
        }
        if (material == null) return null;
        Pattern border = null;
        if (values.get(GenerateSettings.BORDER)) {
            border = single(c, values.get(GenerateSettings.BORDER_BLOCK), states);
            if (border == null) return null;
        }
        return new RoadKernel.Spec(path.nodes(), values.get(GenerateSettings.WIDTH), material, border,
                values.get(GenerateSettings.HEIGHT), values.get(GenerateSettings.LEVEL_ACROSS),
                values.get(GenerateSettings.FILL_BELOW), values.get(GenerateSettings.CLEAR_ABOVE), states.air());
    }

    static RoofKernel.Spec roofSpec(SettingsValues values, Box footprint, int air) {
        return new RoofKernel.Spec(footprint, values.get(GenerateSettings.STYLE), values.get(GenerateSettings.RIDGE),
                values.get(GenerateSettings.LOW_SIDE), values.get(GenerateSettings.PITCH), values.get(GenerateSettings.OVERHANG),
                values.get(GenerateSettings.THICKNESS), values.get(GenerateSettings.GABLE_WALLS),
                values.get(GenerateSettings.INSIDE), air);
    }

    static Pattern single(ToolContext c, BlockDescriptor block, StateSpace states) {
        int state = states.resolve(block);
        if (state < 0) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", block.format()));
            return null;
        }
        if (!placeable(c, state, states)) return null;
        return new Pattern.Single(state);
    }

    /** Whether a state can be generated: the server refuses block-entity states, so say so before uploading. */
    private static boolean placeable(ToolContext c, int state, StateSpace states) {
        if (!StateFlags.has(states.flags(state), StateFlags.HAS_BLOCK_ENTITY)) return true;
        c.notify(Notice.of(Notice.Level.WARNING, BLOCK_ENTITY, states.describe(state).format()));
        return false;
    }

    static Pattern mix(ToolContext c, List<SettingDef.WeightedBlock> entries, StateSpace states) {
        if (entries.isEmpty()) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.palette_empty"));
            return null;
        }
        int[] handles = new int[entries.size()];
        int[] weights = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            int state = states.resolve(entries.get(i).block());
            if (state < 0) {
                c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", entries.get(i).block().format()));
                return null;
            }
            if (!placeable(c, state, states)) return null;
            handles[i] = state;
            weights[i] = entries.get(i).weight();
        }
        return new Pattern.Weighted(handles, weights, MIX_SEED);
    }

    /**
     * The Line of {@code points} with the settings as they are now, or null (after a toast) when its block can't be
     * used. It is clipped to the build height; nothing of the world is read.
     */
    private LineDraft.Setup lineSetup(ToolContext c, List<BlockPos> points) {
        SettingsValues values = c.settings();
        StateSpace states = statesOrNull(c);
        if (states == null) return null;
        Pattern material = values.get(GenerateSettings.LINE_MATERIAL) == GenerateSettings.Material.PALETTE
                ? mix(c, values.get(GenerateSettings.LINE_PALETTE), states)
                : single(c, values.get(GenerateSettings.LINE_BLOCK), states);
        if (material == null) return null;
        PathKind kind = values.get(GenerateSettings.LINE_PATH);
        PathSpec path = new PathSpec(points, kind, kind == PathKind.HANGING ? values.get(GenerateSettings.LINE_SAG) : 0);
        LineKernel.Spec spec = new LineKernel.Spec(path, values.get(GenerateSettings.LINE_THICKNESS),
                values.get(GenerateSettings.LINE_PROFILE), material);
        WorldReader world = worldOrNull(c);
        int bottom = world == null ? Integer.MIN_VALUE : world.bottomY();
        int top = world == null ? Integer.MAX_VALUE : world.topYExclusive();
        // Extend to Ground: the fill under the line is of the line's own material.
        GroundFill.Fill toGround = values.get(GenerateSettings.LINE_EXTEND_TO_GROUND)
                ? GroundFill.Fill.of((x, y, z, column) -> material.apply(states, x, y, z, -1)) : null;
        // Use Stairs and Slabs: the step cells of the line (and its fill) turned, along the line's own climb.
        LineDraft.Finisher finish = null;
        if (values.get(GenerateSettings.LINE_STAIRS_AND_SLABS)) {
            SlopeSteps.Variants steps = steps(c, states, material);
            finish = source -> {
                List<dev.sculptory.core.path.PathSample> samples;
                try {
                    samples = dev.sculptory.core.path.PathSampler.sample(path, 0.5);
                } catch (IllegalArgumentException tooLong) {
                    return source;
                }
                return SlopeSteps.apply(source, SlopeSteps.along(samples), steps, states.air()).source();
            };
        }
        return new LineDraft.Setup(path, cap -> LineKernel.generate(spec, states, cap, bottom, top), OpLabel.LINE, OP_LINE,
                SENT_LINE, new PasteOptions(true, false, false), toGround, finish);
    }

    /**
     * The Sweep of {@code points}: the selection's blocks, read from the client world here (on the client thread, as
     * the road's ground is) so the kernel runs off it, swept with the settings as they are now. Null after a toast when
     * there is no selection, it reaches chunks the game has not loaded, or it holds too many blocks; its blocks with
     * a block entity are left out with a toast.
     */
    private LineDraft.Setup sweepSetup(ToolContext c, List<BlockPos> points) {
        StateSpace states = statesOrNull(c);
        WorldReader world = worldOrNull(c);
        if (states == null || world == null) return null;
        Optional<Region> selection = c.selectionState().map(dev.sculptory.mc.client.editor.tool.Selection::region);
        if (selection.isEmpty()) {
            c.notify(Notice.of(Notice.Level.INFO, SWEEP_NEEDS_SELECTION));
            return null;
        }
        SweepKernel.Profile profile = profile(c, selection.get(), world, states);
        if (profile == null) return null;
        long leftOut = 0;
        for (int state : profile.states()) if (FaceCells.blockEntity(states, state)) leftOut++;
        if (leftOut > 0 && leftOut != toldLeftOut) {
            c.notify(Notice.of(Notice.Level.INFO, SWEEP_LEFT_OUT, SessionNotices.count(leftOut)));
        }
        toldLeftOut = leftOut;
        SettingsValues values = c.settings();
        PathKind kind = values.get(GenerateSettings.SWEEP_PATH);
        SweepKernel.Spec spec = new SweepKernel.Spec(profile, points, kind,
                kind == PathKind.HANGING ? values.get(GenerateSettings.SWEEP_SAG) : 0,
                values.get(GenerateSettings.SWEEP_FOLLOW_SLOPE));
        int bottom = world.bottomY(), top = world.topYExclusive();
        // Extend to Ground: each column's fill is its lowest full block (never stairs, chains, lanterns, fences or doors
        // stacked down), else the profile's commonest full block; a profile without one fills nothing.
        GroundFill.Fill toGround = null;
        if (values.get(GenerateSettings.SWEEP_EXTEND_TO_GROUND)) {
            IntPredicate full = state -> fullBlock(states, state);
            int commonest = commonest(profile.states(), full);
            toGround = new GroundFill.Fill(full, (x, y, z, column) -> column >= 0 ? column : commonest);
        }
        return new LineDraft.Setup(spec.path(), cap -> SweepKernel.generate(spec, states, cap, bottom, top).source(),
                OpLabel.SWEEP, OP_SWEEP, SENT_SWEEP, new PasteOptions(true, false, false), toGround, null);
    }

    /**
     * The stairs and slabs the step cells take ({@link StairSlabVariants}, one per state space, safe off the client
     * thread), after a toast naming a block of {@code materials} that has neither, so its steps stay full blocks (told
     * once per block until another one is the reason).
     */
    private SlopeSteps.Variants steps(ToolContext c, StateSpace states, Pattern... materials) {
        if (variants == null || variants.states() != states) variants = new StairSlabVariants(states);
        StairSlabVariants found = variants;
        for (Pattern material : materials) {
            if (material == null) continue;
            int[] handles = material instanceof Pattern.Single single ? new int[] {single.state()}
                    : material instanceof Pattern.Weighted weighted ? weighted.states()
                    : material instanceof Pattern.Arranged arranged ? arranged.mix().states() : new int[0];
            for (int state : handles) {
                if (found.stairs(state) >= 0 || found.slab(state) >= 0) continue;
                String name = states.describe(state).format();
                if (!name.equals(toldNoSteps)) c.notify(Notice.of(Notice.Level.INFO, NO_STEPS, name));
                toldNoSteps = name;
                break;
            }
        }
        return new SlopeSteps.Variants() {
            @Override
            public int stairs(int state, String facing, String half) {
                return found.stairs(state, facing, half, "straight");
            }

            @Override
            public int slab(int state, String type) {
                return found.slab(state, type);
            }
        };
    }

    /** A full block that can stand under a sweep: terrain-solid, without contents, not waterlogged. */
    static boolean fullBlock(StateSpace states, int state) {
        int flags = states.flags(state);
        return StateFlags.has(flags, StateFlags.TERRAIN_SOLID) && !StateFlags.has(flags, StateFlags.HAS_BLOCK_ENTITY)
                && !StateFlags.has(flags, StateFlags.WATERLOGGED);
    }

    /** The state {@code states} holds most often of those {@code wanted} accepts (the lowest on a tie), or -1. */
    static int commonest(int[] states, IntPredicate wanted) {
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap counts = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        int best = -1, bestCount = 0;
        for (int state : states) {
            if (!wanted.test(state)) continue;
            int count = counts.addTo(state, 1) + 1;
            if (count > bestCount || (count == bestCount && state < best)) {
                best = state;
                bestCount = count;
            }
        }
        return best;
    }

    /** The selection's blocks (air left out), or null after a toast when they can't be had. */
    private static SweepKernel.Profile profile(ToolContext c, Region region, WorldReader world, StateSpace states) {
        Box bounds = region.bounds();
        if (bounds.volume() > MAX_PROFILE_BOX) {
            c.notify(Notice.of(Notice.Level.WARNING, SWEEP_TOO_LARGE, SessionNotices.count(SweepKernel.MAX_PROFILE_CELLS)));
            return null;
        }
        it.unimi.dsi.fastutil.ints.IntArrayList xs = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.ints.IntArrayList ys = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.ints.IntArrayList zs = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.ints.IntArrayList found = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (int x = bounds.min().x(); x <= bounds.max().x(); x++) {
            for (int z = bounds.min().z(); z <= bounds.max().z(); z++) {
                if (!world.isLoaded(x >> 4, z >> 4)) {
                    c.notify(Notice.of(Notice.Level.WARNING, SWEEP_UNLOADED));
                    return null;
                }
                for (int y = Math.max(bounds.min().y(), world.bottomY()); y <= bounds.max().y()
                        && y < world.topYExclusive(); y++) {
                    if (!region.contains(x, y, z)) continue;
                    int state = world.get(x, y, z);
                    if (StateFlags.has(states.flags(state), StateFlags.AIR)) continue;
                    if (found.size() >= SweepKernel.MAX_PROFILE_CELLS) {
                        c.notify(Notice.of(Notice.Level.WARNING, SWEEP_TOO_LARGE,
                                SessionNotices.count(SweepKernel.MAX_PROFILE_CELLS)));
                        return null;
                    }
                    xs.add(x);
                    ys.add(y);
                    zs.add(z);
                    found.add(state);
                }
            }
        }
        if (found.isEmpty()) {
            c.notify(Notice.of(Notice.Level.INFO, SWEEP_EMPTY));
            return null;
        }
        return new SweepKernel.Profile(xs.toIntArray(), ys.toIntArray(), zs.toIntArray(), found.toIntArray());
    }

    // ---- Building ----

    /** Enter: builds what the preview shows (making the preview first when it is not current). */
    private void build(ToolContext c) {
        SettingsValues values = c.settings();
        GenerateSettings.Kind kind = values.get(GenerateSettings.KIND);
        if (kind == GenerateSettings.Kind.PATH && path.size() < 2) {
            c.notify(Notice.of(Notice.Level.INFO, NEEDS_NODES));
            return;
        }
        if ((kind == GenerateSettings.Kind.ROOF || kind == GenerateSettings.Kind.HEIGHTMAP) && c.selection().isEmpty()) {
            c.notify(Notice.of(Notice.Level.INFO, NEEDS_SELECTION));
            return;
        }
        EditorSession session;
        try {
            session = c.session();
        } catch (IllegalStateException noSession) {
            return;
        }
        Optional<Perm> missing = missingNode(session.permissions());
        if (missing.isPresent()) {
            // The server would refuse the upload (it needs both); say which node before anything is made or sent.
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.needs_permission", missing.get().node()));
            return;
        }
        if (dirty || running != null || shown == null) {
            // The preview is not current: build once it lands (pollPreview), without holding the client thread.
            if (dirty || running == null) start(c);
            if (running == null) return;
            pollPreview();
            if (running != null) {
                buildWhenReady = true;
                return;
            }
            if (shown == null) return;
        }
        Built built = shown;
        Limits limits = session.permissions().limits();
        if (built.tooLarge()) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large", "> " + SessionNotices.count(built.cap()),
                    SessionNotices.count(limits.maxClipboardVolume())));
            return;
        }
        if (!built.unloaded().isEmpty()) {
            c.notify(Notice.of(Notice.Level.WARNING, UNLOADED, Integer.toString(built.unloaded().size())));
            return;
        }
        if (built.cells() == 0) {
            c.notify(Notice.of(Notice.Level.INFO, NOTHING));
            return;
        }
        PasteOptions options = new PasteOptions(true, false, false);
        GeneratedCommit.Request request = switch (kind) {
            case PATH -> new GeneratedCommit.Request(built.source(), OpLabel.ROAD, OP_PATH, SENT_PATH, options);
            case HEIGHTMAP -> new GeneratedCommit.Request(built.source(), OpLabel.HEIGHTMAP, OP_HEIGHTMAP, SENT_HEIGHTMAP,
                    options);
            default -> new GeneratedCommit.Request(built.source(), OpLabel.ROOF, OP_ROOF, SENT_ROOF, options);
        };
        commit.send(c, request);
        if (built.keptBlockEntities() > 0) {
            // Chests, signs and the like stay as they are: the import says how many.
            c.notify(Notice.of(Notice.Level.INFO, KEPT_BLOCK_ENTITIES, SessionNotices.count(built.keptBlockEntities())));
        }
    }

    // ---- Drawing and hints ----

    @Override
    public void renderWorld(ToolContext c, WorldDraw d) {
        SettingsValues values = c.settings();
        switch (values.get(GenerateSettings.KIND)) {
            case LINE -> {
                line.renderWorld(d, false);
                return;
            }
            case SWEEP -> {
                sweep.renderWorld(d, false);
                return;
            }
            case STAMP -> {
                stamp.renderWorld(c, d);
                return;
            }
            case SMART_SURFACE -> {
                smart.renderWorld(c, d);
                return;
            }
            default -> {
            }
        }
        Built built = shown;
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.PATH) {
            roadEditor.render(d, NODE_COLOUR, SELECTED_COLOUR);
            if (built != null && built.path() != null) {
                // The road replaces the ground, so its ghost is mostly inside the terrain: the footprint shows where it
                // goes; the centre line stays on top of it.
                if (built.footprint() != null) built.footprint().draw(d, PATH_COLOUR);
                drawCentreLine(d, built);
                drawUnloaded(d, built);
            }
        }
        if (built == null || built.source().isEmpty()) return;
        boolean roof = built.path() == null;
        if (roof || built.ghost() == null || built.footprint() == null) {
            // A roof stands in the air, where a ghost alone is hard to read against the terrain behind it: its bounds
            // are outlined (faintly through terrain, fully in view). A road over the ghost or footprint cap gets the same.
            outline(d, built.source().bounds(), OUTLINE_COLOUR);
        }
    }

    /** A box's edges twice: faintly through the terrain, then depth-tested, as the cell outlines are drawn. */
    private static void outline(WorldDraw d, Box box, int argb) {
        d.seeThrough(true);
        d.boxOutline(box, OverlayColors.scaleAlpha(argb, 0.35));
        d.seeThrough(false);
        d.boxOutline(box, argb);
    }

    /** The centre line on the terrain: each vertex at its column's ground (the last preview's), else the nodes' height. */
    private static void drawCentreLine(WorldDraw d, Built built) {
        RoadKernel.Path line = built.path();
        double lastX = 0, lastY = 0, lastZ = 0;
        for (int i = 0; i < line.vertexCount(); i++) {
            double x = line.x(i), z = line.z(i);
            double y = centreLineY(built, i);
            if (i > 0) d.line(lastX, lastY, lastZ, x, y, z, PATH_COLOUR);
            lastX = x;
            lastY = y;
            lastZ = z;
        }
    }

    /** Where the centre line is drawn at vertex {@code i}: on its column's ground (the last preview's), else the nodes' height. */
    private static double centreLineY(Built built, int i) {
        RoadKernel.Path line = built.path();
        int ground = built.ground() == null ? SurfaceReader.UNLOADED
                : built.ground().ground((int) Math.floor(line.x(i)), (int) Math.floor(line.z(i)));
        return (SurfaceReader.known(ground) ? ground : line.nodeHeightAt(line.along(i))) + 1.05;
    }

    /** One outline per chunk holding unloaded footprint columns, at the nodes' height there. */
    private static void drawUnloaded(WorldDraw d, Built built) {
        if (built.unloaded().isEmpty()) return;
        Long2ObjectOpenHashMap<int[]> chunks = new Long2ObjectOpenHashMap<>();
        for (RoadKernel.Column column : built.unloaded()) {
            long key = GroundMap.column(column.x() >> 4, column.z() >> 4);
            int y = RoadKernel.deckHeight(built.path(), column);
            int[] box = chunks.get(key);
            if (box == null) {
                chunks.put(key, new int[] {column.x(), y, column.z(), column.x(), y, column.z()});
            } else {
                box[0] = Math.min(box[0], column.x());
                box[1] = Math.min(box[1], y);
                box[2] = Math.min(box[2], column.z());
                box[3] = Math.max(box[3], column.x());
                box[4] = Math.max(box[4], y);
                box[5] = Math.max(box[5], column.z());
            }
        }
        d.seeThrough(true);
        for (int[] box : chunks.values()) {
            d.boxOutline(new Box(new BlockPos(box[0], box[1], box[2]), new BlockPos(box[3], box[4], box[5])), UNLOADED_COLOUR);
        }
        d.seeThrough(false);
    }

    /**
     * The first node building needs that the player lacks: {@code region} (a generated clipboard may hold any block
     * state, as a Fill may place it) and {@code clipboard} (the upload and its paste); the server checks both again.
     */
    public static Optional<Perm> missingNode(Permissions permissions) {
        for (Perm node : List.of(Perm.REGION, Perm.CLIPBOARD)) {
            if (!permissions.has(node)) return Optional.of(node);
        }
        return Optional.empty();
    }

    /** Over open sky, the air marker's (or the dragged point's) distance beside the cursor. */
    @Override
    public void renderHud(ToolContext c, HudDraw d) {
        GenerateSettings.Kind kind = c.settings().get(GenerateSettings.KIND);
        if (!(takesPoints(kind) || kind == GenerateSettings.Kind.SMART_SURFACE) || !air.labelShown(cursor)) return;
        AirPoints.renderLabel(d, mouseX, mouseY, services.translate(AirPoints.LABEL, air.shownDistance()));
    }

    @Override
    public List<KeyHint> hints(ToolContext c) {
        List<KeyHint> hints = new ArrayList<>();
        try {
            missingNode(c.session().permissions())
                    .ifPresent(node -> hints.add(KeyHint.text(NEEDS_PERMISSION_HINT, node.node())));
        } catch (IllegalStateException noSession) {
            // No session: nothing can be built anyway, and the palette says why.
        }
        SettingsValues values = c.settings();
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.LINE) {
            hints.addAll(line.hints(CLICK, "sculptory.hint.line.build"));
            hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.line.thickness"));
            return hints;
        }
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.SWEEP) {
            if (c.selection().isEmpty()) {
                hints.add(KeyHint.text("sculptory.hint.sweep.needs_selection"));
                return hints;
            }
            hints.add(KeyHint.text("sculptory.hint.sweep.profile"));
            hints.addAll(sweep.hints(CLICK, "sculptory.hint.sweep.build"));
            return hints;
        }
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.STAMP) {
            hints.addAll(stamp.hints(c, CLICK));
            return hints;
        }
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.SMART_SURFACE) {
            hints.addAll(smart.hints(c, CLICK));
            return hints;
        }
        Built built = shown;
        String enter = services.keyLabel(KeyAction.COMMIT);
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.HEIGHTMAP) {
            if (c.selection().isEmpty()) {
                hints.add(KeyHint.text("sculptory.hint.generate.heightmap_needs_selection"));
                return hints;
            }
            if (built != null && built.tooLarge()) {
                hints.add(KeyHint.text("sculptory.hint.generate.too_large", SessionNotices.count(built.cap())));
            } else if (built != null && !built.unloaded().isEmpty()) {
                hints.add(KeyHint.text("sculptory.hint.generate.unloaded", Integer.toString(built.unloaded().size())));
            } else if (built != null && !built.source().isEmpty()) {
                hints.add(new KeyHint(enter, "sculptory.hint.generate.build_heightmap",
                        List.of(SessionNotices.count(built.cells()))));
            }
            return hints;
        }
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.ROOF) {
            if (c.selection().isEmpty()) {
                hints.add(KeyHint.text("sculptory.hint.generate.roof_needs_selection"));
                return hints;
            }
            hints.add(KeyHint.text("sculptory.hint.generate.roof_eaves"));
            if (built != null && built.tooLarge()) {
                hints.add(KeyHint.text("sculptory.hint.generate.too_large", SessionNotices.count(built.cap())));
            } else if (built != null && !built.source().isEmpty()) {
                hints.add(new KeyHint(enter, "sculptory.hint.generate.build_roof", List.of(SessionNotices.count(built.cells()))));
            }
            return hints;
        }
        if (path.isEmpty()) {
            hints.add(new KeyHint(CLICK, "sculptory.hint.generate.add_node"));
            hints.add(air.hint());
            hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.generate.width"));
            return hints;
        }
        if (roadEditor.busy()) {
            hints.addAll(roadEditor.hints(CLICK, services::keyLabel));
            hints.add(air.hint());
            return hints;
        }
        hints.add(new KeyHint(CLICK, "sculptory.hint.generate.add_or_select"));
        hints.addAll(roadEditor.hints(CLICK, services::keyLabel));
        hints.add(air.hint());
        hints.add(new KeyHint(services.keyLabel(KeyAction.ERASE_SELECTION) + "/" + services.keyLabel(KeyAction.REMOVE_NODE),
                "sculptory.hint.generate.remove_node"));
        if (built != null && built.tooLarge()) {
            hints.add(KeyHint.text("sculptory.hint.generate.too_large", SessionNotices.count(built.cap())));
        } else if (built != null && !built.unloaded().isEmpty()) {
            hints.add(KeyHint.text("sculptory.hint.generate.unloaded", Integer.toString(built.unloaded().size())));
        } else if (built != null && !built.source().isEmpty()) {
            hints.add(new KeyHint(enter, "sculptory.hint.generate.build_path", List.of(SessionNotices.count(built.cells()))));
        }
        hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.generate.width"));
        hints.add(new KeyHint("Esc", "sculptory.hint.generate.clear"));
        return hints;
    }

    // ---- Helpers ----

    public static StateSpace statesOrNull(ToolContext c) {
        try {
            return c.states();
        } catch (IllegalStateException noSession) {
            return null;
        }
    }

    public static WorldReader worldOrNull(ToolContext c) {
        try {
            return c.world();
        } catch (IllegalStateException noSession) {
            return null;
        }
    }

    static Limits limits(ToolContext c) {
        try {
            return c.session().permissions().limits();
        } catch (IllegalStateException noSession) {
            return Limits.DEFAULTS;
        }
    }

    static boolean bypass(ToolContext c) {
        try {
            return c.session().permissions().has(Perm.LIMIT_BYPASS);
        } catch (IllegalStateException noSession) {
            return false;
        }
    }
}
