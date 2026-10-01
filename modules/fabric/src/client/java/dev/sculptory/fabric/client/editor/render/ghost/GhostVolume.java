package dev.sculptory.fabric.client.editor.render.ghost;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.SplitMix64;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.function.IntPredicate;
import org.jetbrains.annotations.Nullable;

/**
 * What a ghost preview shows: a sparse set of {@link GhostSection}s of block-state handles in <em>local</em>
 * coordinates (a clipboard's own coordinates, for example). Where it appears in the world is the
 * {@link GhostPlacement}'s business, so moving or rotating a preview never touches the volume.
 *
 * <p>A volume is built at once from a {@link BlockBuffer} ({@link #of}) or grows one section at a time as clipboard
 * stream chunks arrive ({@link #put}). Each section carries a content hash; putting a section with the same content
 * again changes nothing, and the renderer re-meshes only sections whose hash changed.
 *
 * <p>The {@link #frame()} is the source box that {@code Transform}s rotate and mirror. It is the exact bounds of the
 * present cells unless set explicitly ({@link #setFrame}), which a streamed clipboard should do up front so the
 * preview does not shift while sections arrive.
 *
 * <p>Minecraft-free. Not thread-safe: change and read it on the render thread; mesher threads only see the
 * immutable sections.
 */
public final class GhostVolume {
    private final IntPredicate erases;
    private final Long2ObjectOpenHashMap<GhostSection> sections = new Long2ObjectOpenHashMap<>();
    private final Collection<GhostSection> sectionView = Collections.unmodifiableCollection(sections.values());
    private long version;
    private long contentHash;
    private long cellCount;
    private long blockCount;
    private long eraseCount;
    private @Nullable Box frame;
    private @Nullable Box bounds;
    private boolean boundsValid = true;

    /** @param erases which handles become air (cells drawn as red erase outlines instead of blocks) */
    public GhostVolume(IntPredicate erases) {
        this.erases = Objects.requireNonNull(erases, "erases");
    }

    /** A volume holding every non-empty section of {@code buffer}, in the buffer's coordinates. */
    public static GhostVolume of(BlockBuffer buffer, IntPredicate erases) {
        GhostVolume volume = new GhostVolume(erases);
        for (long key : buffer.sortedKeys()) {
            volume.put(key, buffer.section(key));
        }
        return volume;
    }

    /**
     * Adds or replaces the section at {@code key} ({@link BlockBuffer#key}). The cells are copied. An empty section
     * removes the key. Replacing a section with identical content keeps the stored one and changes nothing.
     *
     * @return the stored section, or null when the section was empty
     */
    public @Nullable GhostSection put(long key, SectionBuffer cells) {
        Objects.requireNonNull(cells, "cells");
        if (cells.isEmpty()) {
            remove(key);
            return null;
        }
        GhostSection old = sections.get(key);
        if (old != null && old.hash() == GhostSection.hashCells(cells)) {
            return old;
        }
        GhostSection section = new GhostSection(key, cells, erases);
        if (old != null) {
            forget(old);
        }
        sections.put(key, section);
        contentHash += entryHash(section);
        cellCount += section.cellCount();
        blockCount += section.blockCount();
        eraseCount += section.eraseCount();
        if (old != null) {
            // The new content may be smaller: recompute lazily.
            boundsValid = false;
        } else if (boundsValid) {
            bounds = bounds == null ? section.bounds() : union(bounds, section.bounds());
        }
        version++;
        return section;
    }

    /** {@link #put(long, SectionBuffer)} by section coordinates. */
    public @Nullable GhostSection put(int sectionX, int sectionY, int sectionZ, SectionBuffer cells) {
        return put(BlockBuffer.key(sectionX, sectionY, sectionZ), cells);
    }

    /** Removes a section. Returns whether it was there. */
    public boolean remove(long key) {
        GhostSection old = sections.remove(key);
        if (old == null) {
            return false;
        }
        forget(old);
        boundsValid = false;
        version++;
        return true;
    }

    /** Removes every section. The explicit frame, if any, stays. */
    public void clear() {
        if (sections.isEmpty()) {
            return;
        }
        sections.clear();
        contentHash = 0;
        cellCount = 0;
        blockCount = 0;
        eraseCount = 0;
        bounds = null;
        boundsValid = true;
        version++;
    }

    private void forget(GhostSection section) {
        contentHash -= entryHash(section);
        cellCount -= section.cellCount();
        blockCount -= section.blockCount();
        eraseCount -= section.eraseCount();
    }

    /** Order-independent: the volume hash is the (wrapping) sum of these. */
    private static long entryHash(GhostSection section) {
        return SplitMix64.mix(section.hash() ^ SplitMix64.mix(section.key()));
    }

    /** The section at {@code key}, or null. */
    public @Nullable GhostSection section(long key) {
        return sections.get(key);
    }

    /** The section at section coordinates, or null (also outside the packable key range). */
    public @Nullable GhostSection section(int sectionX, int sectionY, int sectionZ) {
        if (sectionX < BlockBuffer.MIN_SECTION_XZ || sectionX > BlockBuffer.MAX_SECTION_XZ
                || sectionZ < BlockBuffer.MIN_SECTION_XZ || sectionZ > BlockBuffer.MAX_SECTION_XZ
                || sectionY < BlockBuffer.MIN_SECTION_Y || sectionY > BlockBuffer.MAX_SECTION_Y) {
            return null;
        }
        return sections.get(BlockBuffer.key(sectionX, sectionY, sectionZ));
    }

    /** The handle of a local cell, or -1 if it is absent. */
    public int handle(int x, int y, int z) {
        GhostSection section = section(x >> 4, y >> 4, z >> 4);
        return section == null ? -1 : section.handle(SectionBuffer.index(x & 15, y & 15, z & 15));
    }

    /** Unmodifiable live view of the sections; iteration order is unspecified. */
    public Collection<GhostSection> sections() {
        return sectionView;
    }

    /** Section keys in ascending order. */
    public long[] sortedKeys() {
        long[] keys = sections.keySet().toLongArray();
        Arrays.sort(keys);
        return keys;
    }

    public int sectionCount() {
        return sections.size();
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    /** Present cells (blocks and erase cells). */
    public long cellCount() {
        return cellCount;
    }

    /** Present cells that hold a block. */
    public long blockCount() {
        return blockCount;
    }

    /** Present cells that become air. */
    public long eraseCount() {
        return eraseCount;
    }

    /**
     * An order-independent hash of every section's key and content: equal for volumes with the same cells, however
     * they were built. 0 when empty.
     */
    public long contentHash() {
        return contentHash;
    }

    /** Increases on every content change; the renderer re-syncs when it moves. */
    public long version() {
        return version;
    }

    /** Exact bounds of the present cells, or null when empty. */
    public @Nullable Box bounds() {
        if (!boundsValid) {
            Box computed = null;
            for (GhostSection section : sections.values()) {
                computed = computed == null ? section.bounds() : union(computed, section.bounds());
            }
            bounds = computed;
            boundsValid = true;
        }
        return bounds;
    }

    /** The source box transforms apply to: the explicit frame if set, else {@link #bounds()} (null when empty). */
    public @Nullable Box frame() {
        return frame != null ? frame : bounds();
    }

    /** Fixes the frame (e.g. a clipboard's full box while it streams in); null returns to the exact bounds. */
    public void setFrame(@Nullable Box frame) {
        this.frame = frame;
    }

    private static Box union(Box a, @Nullable Box b) {
        if (b == null) {
            return a;
        }
        return new Box(
                new BlockPos(Math.min(a.min().x(), b.min().x()), Math.min(a.min().y(), b.min().y()), Math.min(a.min().z(), b.min().z())),
                new BlockPos(Math.max(a.max().x(), b.max().x()), Math.max(a.max().y(), b.max().y()), Math.max(a.max().z(), b.max().z())));
    }

    @Override
    public String toString() {
        return "GhostVolume[sections=" + sections.size() + ", blocks=" + blockCount + ", erase=" + eraseCount + "]";
    }
}
