package dev.sculptory.mc.client.editor.render;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.region.Region;
import dev.sculptory.mc.client.editor.world.Aabb;
import dev.sculptory.mc.client.editor.world.BoxFace;
import dev.sculptory.mc.client.editor.world.CameraSnapshot;
import dev.sculptory.mc.client.editor.world.GizmoPick;
import dev.sculptory.mc.loader.WorldFrame;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.Nullable;

/**
 * Facade for editor world overlays. Tools and the editor set what should be visible (selection box,
 * brush cursor, gizmo) through setters; {@link #render} draws it.
 *
 * <p>A shape or cell-set selection also gets its exact outline: {@link SelectionMeshes} meshes it off the render
 * thread (when it changes; a moved selection reuses its mesh) and {@link CellMeshBuffers} draws it.
 *
 * <p>This class registers no listeners. The integrator calls {@link #render} from
 * the loader's world-render-last hook every frame (also when nothing is set: it captures the
 * {@link CameraSnapshot} that cursor picking uses until the next frame).
 *
 * <p>Render thread only. Owns native buffers, created on first draw: {@link #close} on shutdown.
 */
public final class OverlayRenderer implements AutoCloseable {
    private LineBatch lines;
    private QuadBatch quads;
    private long frame;
    private @Nullable CameraSnapshot camera;

    private @Nullable Aabb selection;
    private @Nullable BoxFace selectionHovered;
    private SelectionBoxRenderer.Style selectionStyle = SelectionBoxRenderer.Style.DEFAULT;
    private @Nullable Region selectionRegion;
    /** How far the selection has moved from {@link #selectionRegion} without it being rebuilt (see {@code Selection}). */
    private int[] selectionOffset = new int[3];
    /**
     * Its own thread: meshing a large selection takes a while, and vanilla's worker pool (which also meshes chunks)
     * must not wait for it.
     */
    private final ExecutorService meshThread = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Sculptory selection outline");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final SelectionMeshes<CellMeshBuffers.Built> meshes = new SelectionMeshes<>(new MeshBaker(), meshThread,
            CellMesh.Caps.DEFAULT);
    private @Nullable CellMeshBuffers meshBuffers;

    private @Nullable BrushCursor brushCursor;
    /** Biome view's tint (protocol 6), or null while it is off. */
    private @Nullable dev.sculptory.mc.client.editor.biomes.BiomeView.Tint biomeTint;

    private boolean gizmoVisible;
    private double gizmoX;
    private double gizmoY;
    private double gizmoZ;
    private @Nullable GizmoPick.Handle gizmoHovered;

    /** Captures this frame's camera and draws the current overlays. Call from {@code ClientLoader.onWorldRenderLast}. */
    public void render(WorldFrame context) {
        CameraSnapshot snapshot = CameraSnapshot.capture(context, ++frame);
        camera = snapshot;
        updateMesh();
        if (selection == null && brushCursor == null && !gizmoVisible && biomeTint == null) {
            return;
        }
        if (lines == null) {
            lines = new LineBatch();
            quads = new QuadBatch();
        }
        lines.begin(snapshot.cameraX(), snapshot.cameraY(), snapshot.cameraZ());
        quads.begin(snapshot.cameraX(), snapshot.cameraY(), snapshot.cameraZ());
        boolean exact = selectionRegion != null && !(selectionRegion instanceof Region.Cuboid);
        try {
            if (biomeTint != null) biomeTint(quads, biomeTint);
            if (brushCursor != null) {
                BrushCursorRenderer.render(lines, quads, brushCursor);
            }
            if (selection != null) {
                SelectionBoxRenderer.render(lines, quads, selection, selectionHovered,
                        exact ? SelectionBoxRenderer.Style.BOUNDS : selectionStyle,
                        !(selectionRegion instanceof Region.Cells));
            }
            if (gizmoVisible) {
                GizmoRenderer.render(lines, gizmoX, gizmoY, gizmoZ, gizmoHovered);
            }
        } finally {
            quads.draw();
            lines.draw();
        }
        if (selection != null && exact && meshes.hasMesh() && meshBuffers != null) {
            int[] offset = meshes.offset();
            meshBuffers.draw(context.positionMatrix(), context.projectionMatrix(), snapshot.cameraX(), snapshot.cameraY(),
                    snapshot.cameraZ(), offset[0] + selectionOffset[0], offset[1] + selectionOffset[1],
                    offset[2] + selectionOffset[2], SelectionBoxRenderer.CELL_EDGE,
                    SelectionBoxRenderer.CELL_EDGE_SEE_THROUGH);
        }
    }

    /** Biome view's alpha over the ground (0..255). */
    static final int BIOME_TINT_ALPHA = 0x70;

    /** Each tinted column's top face in its biome's colour, just above the block. */
    private static void biomeTint(QuadBatch quads, dev.sculptory.mc.client.editor.biomes.BiomeView.Tint tint) {
        for (int i = 0; i < tint.size(); i++) {
            int x = tint.xs()[i], z = tint.zs()[i];
            quads.horizontal(x, z, x + 1.0, z + 1.0, tint.ys()[i] + 1.0 + BrushCursorRenderer.TINT_LIFT,
                    OverlayColors.withAlpha(tint.colors()[i], BIOME_TINT_ALPHA), false);
        }
    }

    /** Shows Biome view's tint ({@code null}: none; protocol 6). */
    public void setBiomeTint(@Nullable dev.sculptory.mc.client.editor.biomes.BiomeView.Tint tint) {
        this.biomeTint = tint;
    }

    /** Takes a finished selection mesh, or frees the drawn one when the selection no longer has one. */
    private void updateMesh() {
        Optional<CellMeshBuffers.Built> fresh = meshes.update(selection == null ? null : selectionRegion);
        if (fresh.isPresent()) {
            if (meshBuffers == null) {
                meshBuffers = new CellMeshBuffers();
            }
            meshBuffers.upload(fresh.get());
        }
        if (!meshes.hasMesh() && meshBuffers != null) {
            meshBuffers.clear();
        }
    }

    /** The camera of the last rendered frame, for cursor rays and screen-space picking. */
    public Optional<CameraSnapshot> camera() {
        return Optional.ofNullable(camera);
    }

    /** Shows a selection box; {@code hovered} tints that face's handle (null for none). */
    public void setSelection(Aabb box, @Nullable BoxFace hovered) {
        selection = Objects.requireNonNull(box, "box");
        selectionHovered = hovered;
    }

    /**
     * The selection's region, for its exact outline, and how far the selection has moved from it (a moved cell set keeps
     * its region and its outline; the move is only an offset). A shape or cell set is drawn cell by cell inside its
     * (dimmer) bounds, a cell set without handles; a box ({@code Cuboid}) or {@code null} draws only the box.
     */
    public void setSelectionRegion(@Nullable Region region, int dx, int dy, int dz) {
        selectionRegion = region;
        selectionOffset = new int[] {dx, dy, dz};
    }

    /** Why the selection is drawn simplified (a hint translation key), if it is. */
    public Optional<String> selectionNote() {
        if (selection == null || selectionRegion == null || selectionRegion instanceof Region.Cuboid) {
            return Optional.empty();
        }
        return switch (meshes.status()) {
            case BUILDING -> meshes.hasMesh() ? Optional.empty() : Optional.of("sculptory.hint.select.outlining");
            case TOO_DETAILED -> Optional.of("sculptory.hint.select.too_detailed");
            case FACES_ONLY -> Optional.of("sculptory.hint.select.faces_only");
            case NONE, READY -> Optional.empty();
        };
    }

    public void setSelectionStyle(SelectionBoxRenderer.Style style) {
        selectionStyle = Objects.requireNonNull(style, "style");
    }

    public void clearSelection() {
        selection = null;
        selectionHovered = null;
        selectionRegion = null;
        selectionOffset = new int[3];
    }

    public void setBrushCursor(BrushCursor cursor) {
        brushCursor = Objects.requireNonNull(cursor, "cursor");
    }

    public void clearBrushCursor() {
        brushCursor = null;
    }

    /** Shows the gizmo at a world position; {@code hovered} highlights that handle (null for none). */
    public void setGizmo(double x, double y, double z, @Nullable GizmoPick.Handle hovered) {
        gizmoVisible = true;
        gizmoX = x;
        gizmoY = y;
        gizmoZ = z;
        gizmoHovered = hovered;
    }

    public void clearGizmo() {
        gizmoVisible = false;
        gizmoHovered = null;
    }

    /** Hides every overlay. */
    public void clearAll() {
        clearSelection();
        clearBrushCursor();
        clearGizmo();
        biomeTint = null;
    }

    @Override
    public void close() {
        if (lines != null) {
            lines.close();
            quads.close();
            lines = null;
            quads = null;
        }
        meshes.clear();
        meshThread.shutdownNow();
        if (meshBuffers != null) {
            meshBuffers.close();
            meshBuffers = null;
        }
    }

    /** Selection meshes become vertex data on the build thread; unused data is freed. */
    private static final class MeshBaker implements SelectionMeshes.Baker<CellMeshBuffers.Built> {
        @Override
        public CellMeshBuffers.Built bake(CellMesh mesh, BlockPos origin) {
            return CellMeshBuffers.build(mesh, origin.x(), origin.y(), origin.z(), SelectionBoxRenderer.CELL_FILL);
        }

        @Override
        public void discard(CellMeshBuffers.Built built) {
            built.close();
        }
    }
}
