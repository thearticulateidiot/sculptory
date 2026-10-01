package dev.sculptory.fabric.client.editor.render.ghost;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.editor.render.LineBatch;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.render.OverlayOpacity;
import dev.sculptory.fabric.client.editor.world.Aabb;
import dev.sculptory.fabric.config.FolderMigration;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * Draws ghost-block previews: semi-transparent block meshes, outlines for simplified sections, and red outlines for
 * cells that will become air.
 *
 * <p>Per {@link #render} call (one per placement per frame, from {@code WorldRenderEvents.LAST}):
 * <ol>
 *   <li>{@link GhostBudget} picks, nearest first, the sections that get full meshes within the caps of
 *       {@link GhostConfig}; the rest (and everything while a shader pack is active) draw as their occupied bounding
 *       boxes, and {@link #status()} says so for the HUD. The vertex-memory cap is one budget for the whole renderer
 *       ({@link #vertexBytes()}): meshes other volumes hold are taken off it, so many volumes (a scatter's baked
 *       variants) together stay within it.</li>
 *   <li>Missing or outdated meshes are requested from the worker threads; finished ones are uploaded within the
 *       per-frame budget (shared by every placement; see {@link #beginFrame()}). A section still waiting for its
 *       first mesh draws as a box.</li>
 *   <li>Meshes draw in two passes (see {@link GhostLayers}): depth only, then colour at the placement's alpha with a
 *       {@code LEQUAL} depth test, so only the front-most ghost surfaces show and nothing needs sorting. Both passes
 *       use a small polygon offset so ghost faces win against coplanar world faces. Moving or rotating a placement
 *       only changes each section's model matrix.</li>
 * </ol>
 * The terrain shaders compute fog from the untransformed vertex position, so ghost meshes are not fogged.
 *
 * <p>Meshes are cached per {@link GhostVolume} (by identity). A volume not drawn for {@value #EVICT_AFTER_SECONDS}
 * seconds is freed; {@link #release} frees one at once. Render thread only. Owns native memory, GPU buffers and two
 * worker threads: {@link #close()} when done ({@link #shared()} does that on client shutdown).
 */
public final class GhostRenderer implements AutoCloseable {
    static final int EVICT_AFTER_SECONDS = 10;
    private static final long EVICT_AFTER_NANOS = EVICT_AFTER_SECONDS * 1_000_000_000L;
    private static final int BOX_COLOUR = OverlayColors.argb(200, 225, 235, 255);
    private static final int ERASE_COLOUR = OverlayColors.argb(230, 255, 60, 60);
    private static final double OUTLINE_INFLATE = 0.004;
    private static final float OFFSET_FACTOR = -1f;
    private static final float DEPTH_OFFSET_UNITS = -10f;
    /** One unit closer than the depth pass, so the colour pass passes LEQUAL even across the two programs. */
    private static final float COLOUR_OFFSET_UNITS = -11f;

    private static @Nullable GhostRenderer shared;

    private final GhostConfig config;
    private final GhostMesher mesher = new GhostMesher();
    private final Map<GhostVolume, GhostMeshCache> caches = new IdentityHashMap<>();
    private @Nullable LineBatch lines;
    private int uploadsLeft;
    private long uploadNanosLeft;
    /** Counts {@link #beginFrame} calls; see {@code GhostMeshCache.Entry.wantedFrame}. */
    private long frame;
    private GhostStatus status = GhostStatus.EMPTY;
    private boolean closed;

    public GhostRenderer(GhostConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        beginFrame();
    }

    /**
     * The client's renderer, created on first use with {@code config/sculptory/ghost.json}. Also registers its
     * lifecycle: per-frame upload budget ({@code WorldRenderEvents.START}), meshes dropped on resource or video
     * reloads ({@code InvalidateRenderStateCallback}), everything freed on disconnect and on client shutdown.
     * Call on the client thread.
     */
    public static GhostRenderer shared() {
        if (shared == null) {
            Path file = FolderMigration.configDir().resolve("ghost.json");
            GhostRenderer renderer = new GhostRenderer(
                    GhostConfig.load(file, problem -> SculptoryMod.LOG.warn("Sculptory: {}", problem)));
            WorldRenderEvents.START.register(context -> renderer.beginFrame());
            InvalidateRenderStateCallback.EVENT.register(renderer::invalidate);
            // DISCONNECT can fire on the network thread.
            ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(renderer::clear));
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> renderer.close());
            shared = renderer;
        }
        return shared;
    }

    public GhostConfig config() {
        return config;
    }

    /** The status of the last {@link #render} call. */
    public GhostStatus status() {
        return status;
    }

    /**
     * Starts a frame: resets the upload budget and frees the meshes of volumes not drawn for a while. The shared
     * renderer calls this from {@code WorldRenderEvents.START}; other instances must call it once per frame.
     */
    public void beginFrame() {
        frame++;
        uploadsLeft = config.maxUploadsPerFrame();
        uploadNanosLeft = config.uploadBudgetNanos();
        long now = System.nanoTime();
        Iterator<GhostMeshCache> iterator = caches.values().iterator();
        while (iterator.hasNext()) {
            GhostMeshCache cache = iterator.next();
            if (now - cache.lastUsedNanos() > EVICT_AFTER_NANOS) {
                cache.close();
                iterator.remove();
            }
        }
    }

    /**
     * Draws one placement. Call from {@code WorldRenderEvents.LAST} on the render thread.
     *
     * @return what was drawn, also kept as {@link #status()}
     */
    public GhostStatus render(WorldRenderContext context, GhostPlacement placement) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(placement, "placement");
        ClientWorld world = context.world();
        GhostMapping mapping = placement.mapping();
        if (closed || world == null || mapping == null) {
            status = GhostStatus.EMPTY;
            return status;
        }
        long now = System.nanoTime();
        GhostMeshCache cache = caches.computeIfAbsent(placement.volume(), volume -> new GhostMeshCache(volume, mesher));
        cache.touch(now);
        cache.sync();
        upload(cache);

        Vec3d camera = context.camera().getPos();
        List<GhostMeshCache.Entry> entries = cache.entries();
        List<GhostBudget.Candidate> candidates = new ArrayList<>(entries.size());
        Aabb[] placed = new Aabb[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            GhostMeshCache.Entry entry = entries.get(i);
            GhostSection section = entry.section;
            // Stored sections are never empty, so they have bounds.
            placed[i] = mapping.worldBox(Objects.requireNonNull(section.bounds()));
            double distance = GhostBudget.distance(placed[i], camera.x, camera.y, camera.z);
            // Known sizes, including that of a mesh since freed, so a section too big for the budget stays boxed.
            long bytes = entry.knownBytes();
            candidates.add(new GhostBudget.Candidate(section.blockCount(), section.eraseCount(), distance, bytes, entry.meshed));
        }
        // One vertex-memory budget for the whole renderer: the other volumes' meshes are taken off it first, but a
        // volume always keeps room for the meshes it already has (no trading meshes between volumes).
        long elsewhere = GhostBudget.bytesElsewhere(vertexBytes(), cache.vertexBytes(), config.maxVertexBytes());
        GhostBudget.Plan plan = GhostBudget.plan(candidates, config, ShaderPacks.inUse(), elsewhere);
        status = plan.status();

        requestMeshes(cache, entries, plan, placement, mapping, world, now, frame);
        draw(context, placement, mapping, entries, plan, placed, camera);
        return status;
    }

    private void upload(GhostMeshCache cache) {
        if (uploadsLeft <= 0 || uploadNanosLeft <= 0) {
            return;
        }
        long start = System.nanoTime();
        uploadsLeft -= cache.drain(uploadsLeft, uploadNanosLeft);
        uploadNanosLeft -= System.nanoTime() - start;
    }

    /**
     * Frees meshes of simplified sections, then asks for missing or outdated ones, nearest first. Placements sharing a
     * volume share its meshes, so a section one placement simplifies (it is far) keeps its mesh while another placement
     * (near) wanted it this frame or the last; otherwise the two would free and rebuild it every frame.
     */
    private static void requestMeshes(
            GhostMeshCache cache,
            List<GhostMeshCache.Entry> entries,
            GhostBudget.Plan plan,
            GhostPlacement placement,
            GhostMapping mapping,
            ClientWorld world,
            long now,
            long frame) {
        long lightKey = placement.lightMode() == GhostPlacement.LightMode.WORLD ? mapping.lightKey() : 0L;
        cache.observeLightKey(lightKey, now);
        for (int i = 0; i < entries.size(); i++) {
            GhostMeshCache.Entry entry = entries.get(i);
            if (plan.draw(i) == GhostBudget.Draw.MESH) {
                entry.wantedFrame = frame;
            } else if (entry.meshed && frame - entry.wantedFrame > 1) {
                cache.free(entry);
            }
        }
        for (int index : plan.nearestFirst()) {
            GhostMeshCache.Entry entry = entries.get(index);
            if (plan.draw(index) != GhostBudget.Draw.MESH || !cache.wantsMesh(entry, lightKey, now)) {
                continue;
            }
            if (!cache.request(entry, lightKey, world, placement.lightMode(), mapping)) {
                return; // enough jobs in flight
            }
        }
    }

    private void draw(
            WorldRenderContext context,
            GhostPlacement placement,
            GhostMapping mapping,
            List<GhostMeshCache.Entry> entries,
            GhostBudget.Plan plan,
            Aabb[] placed,
            Vec3d camera) {
        Frustum frustum = context.frustum();
        Matrix4f view = context.positionMatrix();
        List<MeshDraw> meshes = new ArrayList<>();
        LineBatch outlines = lines();
        outlines.begin(camera.x, camera.y, camera.z);
        try {
            for (int i = 0; i < entries.size(); i++) {
                GhostMeshCache.Entry entry = entries.get(i);
                GhostSection section = entry.section;
                Aabb box = placed[i];
                if (frustum != null && !frustum.isVisible(new Box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()))) {
                    continue;
                }
                GhostBudget.Draw draw = plan.draw(i);
                if (draw == GhostBudget.Draw.MESH && entry.meshed) {
                    if (entry.buffer != null) {
                        Matrix4f model = mapping.sectionModel(
                                section.sectionX(), section.sectionY(), section.sectionZ(), camera.x, camera.y, camera.z);
                        meshes.add(new MeshDraw(entry.buffer, new Matrix4f(view).mul(model)));
                    }
                } else if (draw != GhostBudget.Draw.NONE && section.blockBounds() != null) {
                    // Simplified, or still waiting for its first mesh.
                    outlines.box(mapping.worldBox(section.blockBounds()), OUTLINE_INFLATE, BOX_COLOUR, false);
                }
                outlineErasures(outlines, mapping, section, plan.outlineEraseCells(i));
            }
            if (placement.alpha() > 0f) {
                // The Tool outlines opacity (View > Opacity…) fades the ghosts too.
                drawMeshes(context.projectionMatrix(), meshes, OverlayOpacity.alpha(placement.alpha()));
            }
        } finally {
            outlines.draw();
        }
    }

    private static void outlineErasures(LineBatch outlines, GhostMapping mapping, GhostSection section, boolean cellByCell) {
        if (section.eraseCount() == 0) {
            return;
        }
        if (!cellByCell) {
            outlines.box(mapping.worldBox(Objects.requireNonNull(section.eraseBounds())), OUTLINE_INFLATE, ERASE_COLOUR, false);
            return;
        }
        int baseX = section.sectionX() << 4;
        int baseY = section.sectionY() << 4;
        int baseZ = section.sectionZ() << 4;
        for (short index : section.eraseCells()) {
            int x = baseX + (index & 15);
            int y = baseY + ((index >>> 8) & 15);
            int z = baseZ + ((index >>> 4) & 15);
            outlines.box(mapping.worldCell(x, y, z), OUTLINE_INFLATE, ERASE_COLOUR, false);
        }
    }

    private static void drawMeshes(Matrix4f projection, List<MeshDraw> meshes, float alpha) {
        if (meshes.isEmpty()) {
            return;
        }
        RenderSystem.enablePolygonOffset();
        try {
            RenderSystem.polygonOffset(OFFSET_FACTOR, DEPTH_OFFSET_UNITS);
            pass(GhostLayers.DEPTH, projection, meshes);
            RenderSystem.polygonOffset(OFFSET_FACTOR, COLOUR_OFFSET_UNITS);
            RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
            pass(GhostLayers.COLOUR, projection, meshes);
        } finally {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.polygonOffset(0f, 0f);
            RenderSystem.disablePolygonOffset();
        }
    }

    private static void pass(RenderLayer layer, Matrix4f projection, List<MeshDraw> meshes) {
        layer.startDrawing();
        try {
            ShaderProgram shader = RenderSystem.getShader();
            if (shader == null) {
                return;
            }
            // Vertices are section-relative and the model-view matrix places them; no chunk offset.
            if (shader.chunkOffset != null) {
                shader.chunkOffset.set(0f, 0f, 0f);
            }
            for (MeshDraw mesh : meshes) {
                mesh.buffer().bind();
                mesh.buffer().draw(mesh.modelView(), projection, shader);
            }
            VertexBuffer.unbind();
        } finally {
            layer.endDrawing();
        }
    }

    private LineBatch lines() {
        if (lines == null) {
            lines = new LineBatch();
        }
        return lines;
    }

    /** Frees the meshes of one volume now (e.g. when a tool drops its preview). */
    public void release(GhostVolume volume) {
        GhostMeshCache cache = caches.remove(volume);
        if (cache != null) {
            cache.close();
        }
    }

    /** Frees every mesh; they are rebuilt on the next draw. For resource and video reloads. */
    public void invalidate() {
        for (GhostMeshCache cache : caches.values()) {
            cache.invalidate();
        }
    }

    /** Frees every mesh and forgets every volume. For disconnects. */
    public void clear() {
        for (GhostMeshCache cache : caches.values()) {
            cache.close();
        }
        caches.clear();
        status = GhostStatus.EMPTY;
    }

    /** Vertex memory of all meshes, in bytes. */
    public long vertexBytes() {
        long bytes = 0;
        for (GhostMeshCache cache : caches.values()) {
            bytes += cache.vertexBytes();
        }
        return bytes;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        clear();
        mesher.close();
        if (lines != null) {
            lines.close();
            lines = null;
        }
    }

    private record MeshDraw(VertexBuffer buffer, Matrix4f modelView) {}
}
