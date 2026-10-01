package dev.sculptory.fabric.client.editor.render;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.region.Region;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.LongSupplier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * The exact outlines of a few shape regions for a tool's cursor (the Shape brush's shape and its copies), meshed and
 * drawn as the Select tool's shapes are ({@link SelectionMeshes}, {@link CellMeshBuffers}): off the render thread, on a
 * low-priority thread of their own, into vertex buffers. A region that only moved keeps its mesh and is drawn at an
 * offset, so following the cursor meshes nothing; a new size or facing is meshed once, and the last outline stays drawn
 * until it is ready. A slot asked for nothing (the cursor over the sky) keeps its mesh, undrawn, for
 * {@value #KEEP_SECONDS} s, so pointing back at the ground draws it again at once. Render thread only.
 */
public final class RegionOutlines implements AutoCloseable {
    /** Regions drawn at most: a shape and its three symmetric copies. */
    public static final int SLOTS = 4;
    /** How long a slot's mesh is kept while no region is asked of it. */
    public static final int KEEP_SECONDS = 5;
    private static final long KEEP_NANOS = KEEP_SECONDS * 1_000_000_000L;

    private final ExecutorService meshThread = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Sculptory brush outline");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final List<SelectionMeshes<CellMeshBuffers.Built>> meshes = new ArrayList<>(SLOTS);
    private final CellMeshBuffers[] buffers = new CellMeshBuffers[SLOTS];
    /** When each slot was last asked for a region. */
    private final long[] lastAsked = new long[SLOTS];
    private final LongSupplier clock;

    /** @param fillArgb the faces' tint, baked into the meshes */
    public RegionOutlines(int fillArgb) {
        this(fillArgb, System::nanoTime);
    }

    RegionOutlines(int fillArgb, LongSupplier clock) {
        this.clock = clock;
        SelectionMeshes.Baker<CellMeshBuffers.Built> baker = new SelectionMeshes.Baker<>() {
            @Override
            public CellMeshBuffers.Built bake(CellMesh mesh, BlockPos origin) {
                return CellMeshBuffers.build(mesh, origin.x(), origin.y(), origin.z(), fillArgb);
            }

            @Override
            public void discard(CellMeshBuffers.Built built) {
                built.close();
            }
        };
        for (int i = 0; i < SLOTS; i++) meshes.add(new SelectionMeshes<>(baker, meshThread, CellMesh.Caps.DEFAULT));
    }

    /**
     * Draws {@code regions} (shapes or cell sets; boxes have no mesh and draw nothing here): region {@code i} with edges
     * {@code edgeArgb[i]}, seen through terrain in {@code seeThroughArgb[i]}. Slots without a region this frame draw
     * nothing and keep their mesh for a while. Call once per frame with the frame's matrices.
     */
    public void draw(List<? extends Region> regions, int[] edgeArgb, int[] seeThroughArgb, Matrix4f view,
                     Matrix4f projection, double cameraX, double cameraY, double cameraZ) {
        long now = clock.getAsLong();
        for (int i = 0; i < SLOTS; i++) {
            @Nullable Region region = i < regions.size() ? regions.get(i) : null;
            SelectionMeshes<CellMeshBuffers.Built> slot = meshes.get(i);
            if (region == null) {
                if (now - lastAsked[i] > KEEP_NANOS && slot.hasMesh()) {
                    slot.clear();
                    if (buffers[i] != null) buffers[i].clear();
                }
                continue;
            }
            lastAsked[i] = now;
            Optional<CellMeshBuffers.Built> fresh = slot.update(region);
            if (fresh.isPresent()) {
                if (buffers[i] == null) buffers[i] = new CellMeshBuffers();
                buffers[i].upload(fresh.get());
            }
            if (!slot.hasMesh()) {
                if (buffers[i] != null) buffers[i].clear();
                continue;
            }
            int[] offset = slot.offset();
            buffers[i].draw(view, projection, cameraX, cameraY, cameraZ, offset[0], offset[1], offset[2], edgeArgb[i],
                    seeThroughArgb[i]);
        }
    }

    @Override
    public void close() {
        for (SelectionMeshes<CellMeshBuffers.Built> slot : meshes) slot.clear();
        meshThread.shutdownNow();
        for (int i = 0; i < SLOTS; i++) {
            if (buffers[i] != null) {
                buffers[i].close();
                buffers[i] = null;
            }
        }
    }
}
