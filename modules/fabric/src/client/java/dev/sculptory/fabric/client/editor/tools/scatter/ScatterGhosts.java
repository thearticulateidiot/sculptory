package dev.sculptory.fabric.client.editor.tools.scatter;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostSection;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.tools.place.GhostBaker;
import dev.sculptory.fabric.client.session.ClipboardCache;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * The ghost geometry of a scatter plan: where each placement's footprint lies, and the {@link GhostPlacement} that
 * draws it. Every placement of a variant shares that variant's preview {@link GhostVolume} (a taller column plant's,
 * its column's preview for that height), so the renderer meshes each variant once and draws each placement with its
 * own model matrix.
 *
 * <p>A transformed placement first draws the untransformed volume under a turned model matrix (geometry only). Its
 * (volume, transform) pair is then baked once off the render thread ({@link GhostBaker#bake}: states turned and
 * mirrored too, so stairs and logs face the right way) and shared by every placement with that pair. Baked volumes are
 * kept while their source volume is among the plan's variants, so a re-rolled plan reuses them (and their meshes);
 * the others are released. At most {@value #MAX_BAKES} are kept: past that the least recently drawn one not drawn now
 * is released to make room, and when every kept bake is drawn now no new one starts (its placements stay turned by the
 * model matrix). Volumes over {@value #MAX_BAKE_BLOCKS} blocks are not baked.
 *
 * <p>Placement geometry follows the paste rule: the variant's transformed anchor lands on the placement's anchor, so
 * the transformed box starts at {@code anchor - T(previewAnchor)}. A placement whose variant has no preview has no
 * footprint; it is drawn as a dot at its anchor. Client thread only (bakes run on the given executor).
 */
final class ScatterGhosts {
    static final long MAX_BAKE_BLOCKS = 262_144;
    static final int MAX_BAKES_IN_FLIGHT = 2;
    /** Baked volumes kept (and so meshed) at most, plus the ones in flight. */
    static final int MAX_BAKES = 32;

    /** A (source volume, transform) pair; the volume by identity. */
    private record Key(GhostVolume source, Transform transform) {}

    private record Bake(Key key, CompletableFuture<GhostVolume> result) {}

    /** The preview of a taller column plant's column: variant {@code variant}, {@code height} cells; or {@code null}. */
    @FunctionalInterface
    interface ColumnPreviews {
        ColumnPreviews NONE = (variant, height) -> null;

        ClipboardCache.Preview of(int variant, int height);
    }

    private final Executor background;
    private final Consumer<GhostVolume> release;
    private List<ScatterPlan.Placement> placements = List.of();
    /** Per placement, the preview it draws (its variant's, or its column's), or {@code null}. */
    private ClipboardCache.Preview[] shown = new ClipboardCache.Preview[0];
    /** The volumes of {@link #shown}, by identity. */
    private Set<GhostVolume> volumes = Set.of();
    /** Per placement: the transformed box's minimum corner and size (size 0 without a preview). */
    private int[] origins = new int[0];
    private int[] sizes = new int[0];
    private double[] centres = new double[0];
    private int[] draws = new int[0];
    /** Baked volumes, least recently drawn first. */
    private final LinkedHashMap<Key, GhostVolume> baked = new LinkedHashMap<>(16, 0.75f, true);
    private final List<Bake> baking = new ArrayList<>();
    /** Pairs whose bake failed: not retried until the plan changes. */
    private final Set<Key> failed = new HashSet<>();

    ScatterGhosts(Executor background, Consumer<GhostVolume> release) {
        this.background = Objects.requireNonNull(background);
        this.release = Objects.requireNonNull(release);
    }

    /** Shows a plan whose placements are one block tall or have no column previews. */
    void set(List<ScatterPlan.Placement> plan, List<ClipboardCache.Preview> variantPreviews) {
        set(plan, variantPreviews, ColumnPreviews.NONE);
    }

    /**
     * Shows a plan. {@code previews} is index-aligned with the plan's variants; an entry may be {@code null} (no
     * preview: dots only). A taller column plant's placement draws its column's preview from {@code columns}.
     */
    void set(List<ScatterPlan.Placement> plan, List<ClipboardCache.Preview> variantPreviews, ColumnPreviews columns) {
        // Built aside and swapped in at the end, so a failure leaves the ghosts shown before.
        List<ScatterPlan.Placement> placements = List.copyOf(plan);
        List<ClipboardCache.Preview> previews = Collections.unmodifiableList(new ArrayList<>(variantPreviews));
        int n = placements.size();
        ClipboardCache.Preview[] shown = new ClipboardCache.Preview[n];
        Set<GhostVolume> volumes = Collections.newSetFromMap(new IdentityHashMap<>());
        int[] origins = new int[3 * n];
        int[] sizes = new int[3 * n];
        double[] centres = new double[3 * n];
        int[] draws = new int[n];
        for (int i = 0; i < n; i++) {
            ScatterPlan.Placement placement = placements.get(i);
            BlockPos anchor = placement.anchor();
            int variant = placement.variant();
            ClipboardCache.Preview preview = variant < 0 || variant >= previews.size() ? null
                    : placement.height() == 1 ? previews.get(variant) : columns.of(variant, placement.height());
            shown[i] = preview;
            if (preview != null) volumes.add(preview.volume());
            if (preview == null) {
                origins[3 * i] = anchor.x();
                origins[3 * i + 1] = anchor.y();
                origins[3 * i + 2] = anchor.z();
                centres[3 * i] = anchor.x() + 0.5;
                centres[3 * i + 1] = anchor.y() + 0.5;
                centres[3 * i + 2] = anchor.z() + 0.5;
                draws[i] = -1;
                continue;
            }
            Transform t = placement.transform();
            BlockPos dims = preview.dims();
            BlockPos local = preview.anchor();
            int ox = anchor.x() - t.mapX(local.x(), local.z(), dims.x(), dims.z());
            int oy = anchor.y() - local.y();
            int oz = anchor.z() - t.mapZ(local.x(), local.z(), dims.x(), dims.z());
            int sx = t.sizeX(dims.x(), dims.z()), sy = dims.y(), sz = t.sizeZ(dims.x(), dims.z());
            origins[3 * i] = ox;
            origins[3 * i + 1] = oy;
            origins[3 * i + 2] = oz;
            sizes[3 * i] = sx;
            sizes[3 * i + 1] = sy;
            sizes[3 * i + 2] = sz;
            centres[3 * i] = ox + sx / 2.0;
            centres[3 * i + 1] = oy + sy / 2.0;
            centres[3 * i + 2] = oz + sz / 2.0;
            draws[i] = Math.max(1, preview.volume().sectionCount());
        }
        this.placements = placements;
        this.shown = shown;
        this.volumes = volumes;
        this.origins = origins;
        this.sizes = sizes;
        this.centres = centres;
        this.draws = draws;
        releaseUnused();
    }

    /** Forgets the plan and frees every baked volume. */
    void clear() {
        placements = List.of();
        shown = new ClipboardCache.Preview[0];
        volumes = Set.of();
        origins = new int[0];
        sizes = new int[0];
        centres = new double[0];
        draws = new int[0];
        releaseUnused();
    }

    int size() {
        return placements.size();
    }

    List<ScatterPlan.Placement> placements() {
        return placements;
    }

    /** x, y, z of each placement's centre (for {@link ScatterLod#plan}). */
    double[] centres() {
        return centres;
    }

    /** Each placement's section draws as a ghost, or -1 without a preview. */
    int[] draws() {
        return draws;
    }

    /** The placement's anchor cell. */
    BlockPos anchor(int index) {
        return placements.get(index).anchor();
    }

    /** The world cells the placement's transformed box covers, or {@code null} without a preview. */
    Box footprint(int index) {
        if (sizes[3 * index] == 0) return null;
        int x = origins[3 * index], y = origins[3 * index + 1], z = origins[3 * index + 2];
        return new Box(new BlockPos(x, y, z),
                new BlockPos(x + sizes[3 * index] - 1, y + sizes[3 * index + 1] - 1, z + sizes[3 * index + 2] - 1));
    }

    /**
     * The ghost of placement {@code index}: baked if its bake is ready, else turned by the model matrix only;
     * {@code null} without a preview or when its bake failed ({@link #bakeFailed}).
     */
    GhostPlacement ghost(int index) {
        ClipboardCache.Preview preview = shown[index];
        if (preview == null) return null;
        Transform t = placements.get(index).transform();
        int x = origins[3 * index], y = origins[3 * index + 1], z = origins[3 * index + 2];
        if (t.isIdentity()) return GhostPlacement.of(preview.volume(), x, y, z);
        Key key = new Key(preview.volume(), t);
        if (failed.contains(key)) return null; // its bake failed: an outline instead
        GhostVolume turned = baked.get(key); // marks it drawn
        return turned != null
                ? GhostPlacement.of(turned, x, y, z)
                : GhostPlacement.of(preview.volume(), x, y, z).withTransform(t);
    }

    /** Whether the bake placement {@code index} needs failed: it is drawn as its footprint outline instead. */
    boolean bakeFailed(int index) {
        Key key = key(index);
        return key != null && failed.contains(key);
    }

    /** Whether the ghost of {@code index} shows its final states (untransformed, or baked). */
    boolean bakedFor(int index) {
        ClipboardCache.Preview preview = shown[index];
        Transform t = placements.get(index).transform();
        return preview != null && (t.isIdentity() || baked.containsKey(new Key(preview.volume(), t)));
    }

    /**
     * Collects finished bakes and starts the bakes the given ghosts (the ones drawn now) still need: at most
     * {@value #MAX_BAKES_IN_FLIGHT} at a time, and at most {@value #MAX_BAKES} kept, making room by releasing the least
     * recently drawn bake that none of these ghosts draws. A bake that failed is dropped (its placements are drawn as
     * outlines) and not retried until the plan changes.
     */
    void updateBakes(Iterable<Integer> ghostIndices, StateSpace states) {
        Set<Key> drawn = new HashSet<>();
        for (int index : ghostIndices) {
            Key key = key(index);
            if (key != null && drawn.add(key)) baked.get(key); // marks it drawn
        }
        Iterator<Bake> running = baking.iterator();
        while (running.hasNext()) {
            Bake bake = running.next();
            if (!bake.result().isDone()) continue;
            running.remove();
            GhostVolume result = GhostBaker.finished(bake.result()); // null when it failed: never rethrown here
            if (result == null) {
                failed.add(bake.key());
            } else if (inUse(bake.key().source())) {
                baked.put(bake.key(), result);
            } else {
                release.accept(result);
            }
        }
        while (baked.size() > MAX_BAKES && evictOne(drawn)) {
            // Released the least recently drawn bake that is not drawn now.
        }
        if (states == null) return;
        for (int index : ghostIndices) {
            if (baking.size() >= MAX_BAKES_IN_FLIGHT) return;
            Key key = key(index);
            if (key == null || baked.containsKey(key) || failed.contains(key) || isBaking(key)) continue;
            GhostVolume source = key.source();
            Box frame = source.frame();
            if (frame == null || source.blockCount() > MAX_BAKE_BLOCKS) continue;
            if (baked.size() + baking.size() >= MAX_BAKES && !evictOne(drawn)) return;
            List<GhostSection> sections = List.copyOf(source.sections());
            Transform t = key.transform();
            CompletableFuture<GhostVolume> result =
                    CompletableFuture.supplyAsync(() -> GhostBaker.bake(frame, sections, t, states), background);
            baking.add(new Bake(key, result));
        }
    }

    /** Bakes started and not yet collected. */
    int bakesInFlight() {
        return baking.size();
    }

    /** Baked volumes kept. */
    int bakeCount() {
        return baked.size();
    }

    /** The (volume, transform) pair placement {@code index} would bake, or {@code null} (no preview, identity). */
    private Key key(int index) {
        ClipboardCache.Preview preview = shown[index];
        Transform t = placements.get(index).transform();
        return preview == null || t.isIdentity() ? null : new Key(preview.volume(), t);
    }

    /** Releases the least recently drawn bake not in {@code drawn}; false when every kept bake is drawn. */
    private boolean evictOne(Set<Key> drawn) {
        Iterator<Map.Entry<Key, GhostVolume>> entries = baked.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Key, GhostVolume> entry = entries.next();
            if (drawn.contains(entry.getKey())) continue;
            release.accept(entry.getValue());
            entries.remove();
            return true;
        }
        return false;
    }

    private boolean isBaking(Key key) {
        for (Bake bake : baking) {
            if (bake.key().equals(key)) return true;
        }
        return false;
    }

    private boolean inUse(GhostVolume volume) {
        return volumes.contains(volume);
    }

    /** Releases the baked volumes whose source is no longer among the variants; forgets failed bakes. */
    private void releaseUnused() {
        Iterator<Map.Entry<Key, GhostVolume>> entries = baked.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Key, GhostVolume> entry = entries.next();
            if (inUse(entry.getKey().source())) continue;
            release.accept(entry.getValue());
            entries.remove();
        }
        failed.clear();
    }

    /** The source volumes with baked versions (for tests). */
    Set<GhostVolume> bakedSources() {
        Set<GhostVolume> sources = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Key key : baked.keySet()) sources.add(key.source());
        return Collections.unmodifiableSet(sources);
    }
}
