package dev.sculptory.fabric.client.editor.render.ghost;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.world.ClientWorld;
import org.jetbrains.annotations.Nullable;

/**
 * The GPU meshes of one {@link GhostVolume}'s sections. The renderer asks for meshes nearest first
 * ({@link #request}); {@link GhostMesher} builds them on worker threads; {@link #drain} uploads finished ones into
 * {@code VertexBuffer}s within a per-frame budget.
 *
 * <p>A mesh is valid for one section content hash and one light key (0 for full-bright meshes, else the placement's
 * {@link GhostMapping#lightKey()}). A changed hash rebuilds the section; a changed light key only once the placement
 * has kept it for {@value #LIGHT_SETTLE_NANOS} ns, so a world-lit preview being dragged is not rebuilt every frame.
 * Until the new mesh arrives the old one is still drawn.
 *
 * <p>{@link #invalidate()} (resource reload, disconnect) frees every mesh and discards jobs in flight;
 * {@link #close()} also stops accepting results. Render thread only, except the result sink.
 */
final class GhostMeshCache implements AutoCloseable {
    /** Jobs submitted and not yet drained, per cache: bounds the native memory waiting for upload. */
    static final int MAX_IN_FLIGHT = 4;
    static final long LIGHT_SETTLE_NANOS = 250_000_000L;
    /** How long a section whose mesh came in another vertex format waits before it is meshed again. */
    static final long WRONG_FORMAT_RETRY_NANOS = 1_000_000_000L;

    private final GhostVolume volume;
    private final GhostMesher mesher;
    private final Long2ObjectLinkedOpenHashMap<Entry> entries = new Long2ObjectLinkedOpenHashMap<>();
    private final Queue<GhostMesher.Result> finished = new ConcurrentLinkedQueue<>();
    private final Object sinkLock = new Object();
    private long syncedVersion = -1;
    private long generation;
    private int inFlight;
    private long vertexBytes;
    private long lastLightKey;
    private long lightKeySince;
    private long lastUsedNanos;
    private boolean closed;

    /** One section's mesh state. */
    static final class Entry {
        GhostSection section;
        @Nullable VertexBuffer buffer;
        /** A mesh (possibly without vertices) exists for {@link #meshHash}/{@link #meshLightKey}. */
        boolean meshed;
        long meshHash;
        long meshLightKey;
        long bytes;
        /**
         * Vertex bytes of the last mesh built from content {@link #measuredHash}, kept after the mesh is freed, or -1.
         * The planner uses it instead of its per-block estimate, so a section whose real mesh did not fit is not
         * meshed again and again (freed, estimated smaller, meshed, found too big, freed...).
         */
        long measuredBytes = -1;
        long measuredHash;
        /** Its last mesh came in another vertex format: no mesh is requested before {@link #retryAfterNanos}. */
        boolean wrongFormat;
        long retryAfterNanos;
        boolean pending;
        /**
         * The last renderer frame in which some placement of this volume planned a full mesh for the section. Several
         * placements may share one volume (stack copies, scatter placements) at different distances: a mesh is freed
         * only once no placement has wanted it for a whole frame.
         */
        long wantedFrame = -2; // never (frames count from 1)

        Entry(GhostSection section) {
            this.section = section;
        }

        /** The mesh reflects the section's current content (maybe with outdated light). */
        boolean current() {
            return meshed && meshHash == section.hash();
        }

        /**
         * The section's vertex bytes when known: of its current mesh, else of the last mesh built from its current
         * content (since freed); otherwise -1.
         */
        long knownBytes() {
            if (current()) return bytes;
            return measuredBytes >= 0 && measuredHash == section.hash() ? measuredBytes : -1;
        }
    }

    GhostMeshCache(GhostVolume volume, GhostMesher mesher) {
        this.volume = Objects.requireNonNull(volume, "volume");
        this.mesher = Objects.requireNonNull(mesher, "mesher");
    }

    GhostVolume volume() {
        return volume;
    }

    void touch(long nowNanos) {
        lastUsedNanos = nowNanos;
    }

    long lastUsedNanos() {
        return lastUsedNanos;
    }

    long vertexBytes() {
        return vertexBytes;
    }

    /** Brings the entries in line with the volume: new, replaced and removed sections. */
    void sync() {
        if (syncedVersion == volume.version()) {
            return;
        }
        syncedVersion = volume.version();
        entries.values().removeIf(entry -> {
            GhostSection now = volume.section(entry.section.key());
            if (now == null) {
                free(entry);
                return true;
            }
            entry.section = now;
            return false;
        });
        for (GhostSection section : volume.sections()) {
            if (!entries.containsKey(section.key())) {
                entries.put(section.key(), new Entry(section));
            }
        }
    }

    /** The entries, in a stable order. */
    List<Entry> entries() {
        return new ArrayList<>(entries.values());
    }

    /** Tells the cache which light key the placement wants this frame (for the settle delay). */
    void observeLightKey(long lightKey, long nowNanos) {
        if (lightKey != lastLightKey) {
            lastLightKey = lightKey;
            lightKeySince = nowNanos;
        }
    }

    /** Whether the entry should be (re)built for this light key now. */
    boolean wantsMesh(Entry entry, long lightKey, long nowNanos) {
        if (entry.pending || (entry.wrongFormat && nowNanos - entry.retryAfterNanos < 0)) {
            return false;
        }
        if (!entry.current()) {
            return true;
        }
        return entry.meshLightKey != lightKey && nowNanos - lightKeySince >= LIGHT_SETTLE_NANOS;
    }

    /**
     * Submits a mesh job for the entry unless too many are in flight.
     *
     * @return whether a job was submitted
     */
    boolean request(Entry entry, long lightKey, ClientWorld world, GhostPlacement.LightMode lightMode, GhostMapping mapping) {
        if (closed || entry.pending || inFlight >= MAX_IN_FLIGHT) {
            return false;
        }
        GhostSection section = entry.section;
        GhostSection[] neighbourhood = new GhostSection[27];
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    neighbourhood[GhostBlockView.neighbourIndex(dx, dy, dz)] =
                            volume.section(section.sectionX() + dx, section.sectionY() + dy, section.sectionZ() + dz);
                }
            }
        }
        GhostMesher.Job job = new GhostMesher.Job(
                generation, section.key(), section.hash(), lightKey, neighbourhood, world, lightMode, mapping);
        entry.pending = true;
        inFlight++;
        mesher.submit(job, this::accept);
        return true;
    }

    /** Result sink; runs on a mesher thread. */
    private void accept(GhostMesher.Result result) {
        synchronized (sinkLock) {
            if (!closed) {
                finished.add(result);
                return;
            }
        }
        result.close();
    }

    /**
     * Uploads finished meshes, oldest first, until {@code maxUploads} vertex buffers were uploaded or
     * {@code budgetNanos} were spent. Stale results (older generation, changed content, removed section) are freed
     * without upload and do not count.
     *
     * @return the number of vertex buffers uploaded
     */
    int drain(int maxUploads, long budgetNanos) {
        RenderSystem.assertOnRenderThread();
        long start = System.nanoTime();
        int uploads = 0;
        while (uploads < maxUploads && System.nanoTime() - start < budgetNanos) {
            GhostMesher.Result result = finished.poll();
            if (result == null) {
                break;
            }
            if (result.job().generation() == generation) {
                inFlight--;
            }
            if (install(result)) {
                uploads++;
            }
        }
        return uploads;
    }

    /** Installs one result; returns whether it uploaded vertices. */
    private boolean install(GhostMesher.Result result) {
        GhostMesher.Job job = result.job();
        Entry entry = job.generation() == generation ? entries.get(job.key()) : null;
        if (entry == null) {
            result.close();
            return false;
        }
        entry.pending = false;
        if (entry.section.hash() != job.hash()) {
            result.close();
            return false;
        }
        if (result.built() != null && result.built().getDrawParameters().format() != GhostMesher.FORMAT) {
            // Built in another vertex format (Iris widens block-format buffers while a shader pack is on): drawn with
            // the ghost programs its vertices would be misread. Dropped without a measurement; the section is meshed
            // again when wanted, at most once a second (with a shader pack on, sections are boxes and none is wanted,
            // but the pack check and Iris's own may disagree for a moment).
            entry.wrongFormat = true;
            entry.retryAfterNanos = System.nanoTime() + WRONG_FORMAT_RETRY_NANOS;
            result.close();
            return false;
        }
        entry.wrongFormat = false;
        entry.meshed = true;
        entry.meshHash = job.hash();
        entry.meshLightKey = job.lightKey();
        entry.measuredHash = job.hash();
        entry.measuredBytes = result.vertexBytes();
        vertexBytes -= entry.bytes;
        entry.bytes = 0;
        if (result.built() == null) {
            // No vertices (or a failed block): keep an empty mesh so the section is not retried until it changes.
            closeBuffer(entry);
            result.close();
            return false;
        }
        if (entry.buffer == null || entry.buffer.isClosed()) {
            entry.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        try {
            entry.buffer.bind();
            entry.buffer.upload(result.built()); // consumes (closes) the built buffer
            VertexBuffer.unbind();
            entry.bytes = result.vertexBytes();
            vertexBytes += entry.bytes;
        } finally {
            result.close();
        }
        return true;
    }

    /** Frees one entry's mesh (it will be rebuilt if requested again). */
    void free(Entry entry) {
        closeBuffer(entry);
        vertexBytes -= entry.bytes;
        entry.bytes = 0;
        entry.meshed = false;
    }

    private static void closeBuffer(Entry entry) {
        if (entry.buffer != null) {
            entry.buffer.close();
            entry.buffer = null;
        }
    }

    /** Frees every mesh and discards the jobs in flight (their results are freed when they arrive). */
    void invalidate() {
        generation++;
        for (Entry entry : entries.values()) {
            free(entry);
            entry.pending = false;
            entry.measuredBytes = -1; // models may differ after a resource reload
            entry.wrongFormat = false;
        }
        vertexBytes = 0;
        discardFinished();
    }

    private void discardFinished() {
        GhostMesher.Result result;
        while ((result = finished.poll()) != null) {
            result.close();
        }
        // Jobs of older generations still running are no longer counted; drain frees their results on arrival.
        inFlight = 0;
    }

    @Override
    public void close() {
        synchronized (sinkLock) {
            closed = true;
        }
        invalidate();
        entries.clear();
    }
}
