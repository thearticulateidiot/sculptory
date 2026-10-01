package dev.sculptory.fabric.client.editor.render;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.RegionTooLargeException;
import dev.sculptory.core.region.Regions;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the exact outline of the selection ({@link CellMesh}) up to date for a shape or a cell set: meshes a region
 * off the render thread when it changes, reuses the mesh with an offset when a shape only moved, and says what to
 * draw. A moved cell set keeps its region (the editor's selection holds the move as an offset, which the renderer adds),
 * so its mesh is never rebuilt for a move. A box selection has no mesh (the box is its outline). Pure: the vertex data
 * type {@code B} comes from a {@link Baker}, so the renderer's GL buffers stay out of here. Render thread only, apart
 * from the builds.
 *
 * <p>At most one build runs at a time. A region asked for while one runs cancels it (checked before anything else and
 * between sections) and is built next; regions asked for meanwhile replace each other, so a resize drag asking every
 * frame keeps one build going, for the latest region. While a new mesh is built the last one stays drawn, unless the
 * selection went away or became a box. A region too large to mesh in reasonable time ({@link #MAX_ROWS},
 * {@link #MAX_SECTIONS}) is reported too detailed without being enumerated.
 */
public final class SelectionMeshes<B> {
    /** Turns a mesh into vertex data (on the build thread) and frees vertex data that won't be shown. */
    public interface Baker<B> {
        /** Vertex data for {@code mesh}, relative to the cell {@code origin}. */
        B bake(CellMesh mesh, BlockPos origin);

        /** Frees vertex data that is not going to be installed. Any thread. */
        void discard(B built);
    }

    /** What the world shows of the selection. */
    public enum Status {
        /** No mesh wanted: nothing selected, or a box. */
        NONE,
        /** The mesh for the current region is being built (the last one, if any, is still drawn). */
        BUILDING,
        /** The exact outline is drawn. */
        READY,
        /** Too many faces: only the bounds are drawn. */
        TOO_DETAILED,
        /** Too many edges: the faces are drawn without them. */
        FACES_ONLY
    }

    private record Built<B>(CellMesh mesh, @Nullable B vertices) {}

    private record Build<B>(Region region, CompletableFuture<Built<B>> result, AtomicBoolean cancelled) {}

    /**
     * A shape with more rows than this is too detailed to outline: counted as the server and the counts do, along the
     * longest side of its box (the product of the two shorter sides), which is also how its sections are listed.
     */
    public static final long MAX_ROWS = 262_144;
    /** A region reaching more 16³ sections than this is too detailed to outline. */
    public static final long MAX_SECTIONS = 32_768;

    private final Baker<B> baker;
    private final Executor background;
    private final CellMesh.Caps caps;
    /** The region asked for last. */
    private @Nullable Region shown;
    /**
     * The region the last finished mesh was built for (also when it was too detailed to draw), and how far {@link
     * #shown} is moved from it.
     */
    private @Nullable Region meshed;
    private CellMesh meshedMesh = CellMesh.empty();
    /** Whether vertices for {@link #meshed} are installed. */
    private boolean drawable;
    private int dx;
    private int dy;
    private int dz;
    private @Nullable Build<B> pending;
    /** The region to build once the running build has stopped. */
    private @Nullable Region next;
    private Status status = Status.NONE;

    public SelectionMeshes(Baker<B> baker, Executor background, CellMesh.Caps caps) {
        this.baker = Objects.requireNonNull(baker);
        this.background = Objects.requireNonNull(background);
        this.caps = Objects.requireNonNull(caps);
    }

    /**
     * The selection to show this frame ({@code null} for none). Returns vertex data to install in place of what is
     * drawn when a build has finished; {@link #hasMesh()} says whether anything is to be drawn at all.
     */
    public Optional<B> update(@Nullable Region region) {
        if (region == null || region instanceof Region.Cuboid || region instanceof Region.Uploaded) {
            clear();
            return Optional.empty();
        }
        if (!same(region, shown)) {
            shown = region;
            int[] moved = meshed == null ? null : translation(meshed, region);
            if (moved != null) {
                dx = moved[0];
                dy = moved[1];
                dz = moved[2];
                next = null;
                if (pending != null) {
                    pending.cancelled().set(true);
                }
                status = meshStatus();
            } else if (pending != null && !pending.cancelled().get()
                    && (same(pending.region(), region) || translation(pending.region(), region) != null)) {
                next = null; // the running build serves this region too
            } else if (pending != null) {
                pending.cancelled().set(true);
                next = region;
                status = Status.BUILDING;
            } else {
                start(region);
            }
        }
        return collect();
    }

    /** Whether a mesh is installed (for the current region or, while a new one builds, the last one). */
    public boolean hasMesh() {
        return meshed != null && drawable;
    }

    public Status status() {
        return status;
    }

    /** How far the installed mesh is moved: {x, y, z} blocks from the region it was built for. */
    public int[] offset() {
        return new int[] {dx, dy, dz};
    }

    /** The mesh installed, for its counts. */
    public CellMesh mesh() {
        return meshedMesh;
    }

    /** Forgets everything: nothing is drawn until the next region. */
    public void clear() {
        cancelPending();
        next = null;
        shown = null;
        meshed = null;
        meshedMesh = CellMesh.empty();
        drawable = false;
        dx = dy = dz = 0;
        status = Status.NONE;
    }

    private void start(Region region) {
        AtomicBoolean cancelled = new AtomicBoolean();
        CompletableFuture<Built<B>> result;
        try {
            result = CompletableFuture.supplyAsync(() -> build(region, cancelled), background);
        } catch (RejectedExecutionException shutDown) {
            result = CompletableFuture.failedFuture(shutDown);
        }
        pending = new Build<>(region, result, cancelled);
        status = Status.BUILDING;
    }

    private Built<B> build(Region region, AtomicBoolean cancelled) {
        if (cancelled.get()) return null;
        if (tooLarge(region)) return new Built<>(CellMesh.tooLarge(), null);
        long[] sections;
        try {
            // A shape's sections along its longest side, as the server lists them; stops once past the cap.
            sections = region instanceof Region.Shape
                    ? Regions.sectionKeysBetween(region, Integer.MIN_VALUE, Integer.MAX_VALUE, MAX_SECTIONS)
                    : region.sectionKeys();
        } catch (RegionTooLargeException tooMany) {
            return new Built<>(CellMesh.tooLarge(), null);
        }
        if (sections.length > MAX_SECTIONS) return new Built<>(CellMesh.tooLarge(), null);
        CellMesh mesh = CellMesh.build(rows(region), sections, caps, cancelled::get);
        if (mesh == null) return null;
        B vertices = mesh.tooDetailed() || cancelled.get() ? null : baker.bake(mesh, region.bounds().min());
        return new Built<>(mesh, vertices);
    }

    /** Takes a finished build: installs it if it still fits what is shown; then starts the next one, if any. */
    private Optional<B> collect() {
        Build<B> build = pending;
        if (build == null || !build.result().isDone()) return Optional.empty();
        pending = null;
        Optional<B> installed = install(build);
        if (next != null) {
            Region region = next;
            next = null;
            start(region);
        }
        return installed;
    }

    private Optional<B> install(Build<B> build) {
        Built<B> built;
        try {
            built = build.result().getNow(null);
        } catch (RuntimeException failed) {
            status = hasMesh() ? meshStatus() : Status.TOO_DETAILED; // shown as its bounds
            return Optional.empty();
        }
        int[] moved = shown == null || built == null || build.cancelled().get() ? null
                : same(build.region(), shown) ? new int[3] : translation(build.region(), shown);
        if (built == null || moved == null) {
            if (built != null && built.vertices() != null) baker.discard(built.vertices());
            return Optional.empty();
        }
        meshed = build.region();
        meshedMesh = built.mesh();
        drawable = built.vertices() != null;
        dx = moved[0];
        dy = moved[1];
        dz = moved[2];
        status = meshStatus();
        return Optional.ofNullable(built.vertices());
    }

    private Status meshStatus() {
        if (meshedMesh.tooDetailed()) return Status.TOO_DETAILED;
        return meshedMesh.edgesDropped() ? Status.FACES_ONLY : Status.READY;
    }

    private void cancelPending() {
        Build<B> build = pending;
        pending = null;
        if (build == null) return;
        build.cancelled().set(true);
        build.result().thenAccept(built -> {
            if (built != null && built.vertices() != null) baker.discard(built.vertices());
        });
    }

    /** Whether two regions are the same: by identity for cell sets (value equality costs a pass over the cells). */
    static boolean same(@Nullable Region a, @Nullable Region b) {
        if (a == b) return true;
        if (a == null || b == null || a instanceof Region.Cells || b instanceof Region.Cells) return false;
        return a.equals(b);
    }

    /**
     * The move taking box or shape {@code from} onto {@code to}, if {@code to} is exactly {@code from} moved; else null.
     * Cheap: the kind, facing and size decide, no cells are counted. Cell sets are never compared (their moves come
     * as offsets).
     */
    static int[] translation(Region from, Region to) {
        if (from instanceof Region.Cells || from.getClass() != to.getClass()) return null;
        Box a = from.bounds();
        Box b = to.bounds();
        if (a.sizeX() != b.sizeX() || a.sizeY() != b.sizeY() || a.sizeZ() != b.sizeZ()) return null;
        int[] d = {b.min().x() - a.min().x(), b.min().y() - a.min().y(), b.min().z() - a.min().z()};
        return from.translate(d[0], d[1], d[2]).equals(to) ? d : null;
    }

    /** Whether a region is too large to mesh, judged from its box alone. */
    static boolean tooLarge(Region region) {
        Box box = region.bounds();
        long sections = (long) ((box.max().x() >> 4) - (box.min().x() >> 4) + 1)
                * ((box.max().y() >> 4) - (box.min().y() >> 4) + 1) * ((box.max().z() >> 4) - (box.min().z() >> 4) + 1);
        if (region instanceof Region.Shape shape) {
            return Regions.shapeRows(shape, Integer.MIN_VALUE, Integer.MAX_VALUE) > MAX_ROWS || sections > MAX_SECTIONS;
        }
        return false; // a cell set is judged by the sections it holds
    }

    /** The mesher's rows of a region: x intervals for a shape, cell tests for a cell set. */
    static CellMesh.Rows rows(Region region) {
        return switch (region) {
            case Region.Shape shape -> CellMesh.Rows.ofSpans((y, z) -> {
                long span = shape.rowSpan(y, z);
                return span == Region.Shape.EMPTY_ROW ? CellMesh.NO_SPAN
                        : CellMesh.span(Region.Shape.rowMin(span), Region.Shape.rowMax(span));
            });
            case Region.Cuboid cuboid -> {
                Box box = cuboid.box();
                yield CellMesh.Rows.ofSpans((y, z) -> box.contains(box.min().x(), y, z)
                        ? CellMesh.span(box.min().x(), box.max().x()) : CellMesh.NO_SPAN);
            }
            case Region.Cells cells -> CellMesh.Rows.of(cells::contains);
            case Region.Uploaded uploaded -> throw new IllegalArgumentException("An uploaded region has no cells here");
        };
    }
}
