package dev.sculptory.server.engine.impl;

import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.schem.AssetInfo;
import dev.sculptory.server.library.LibraryPath;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Library assets loaded for previews and pastes ({@code SourceRef.Asset}), keyed by the SHA-256 of the file bytes,
 * least recently used first out, bounded by entries and estimated bytes. Content comes from files, so its tiles
 * are untrusted. Each entry remembers its library path, so access can be checked for every player who uses it.
 * Server thread only.
 */
public final class AssetCache {
    public static final int MAX_ENTRIES = 64;
    public static final long MAX_BYTES = 256L << 20;

    /**
     * A parsed asset.
     *
     * @param info the file's Sculptory library metadata (scatter rotations, weight), or {@code null} when it has
     *     none
     */
    public record Asset(String hash, LibraryPath path, Clipboard clipboard, AssetInfo info) {
        public Asset {
            Objects.requireNonNull(hash);
            Objects.requireNonNull(path);
            Objects.requireNonNull(clipboard);
        }

        /** An asset without library metadata. */
        public Asset(String hash, LibraryPath path, Clipboard clipboard) {
            this(hash, path, clipboard, null);
        }
    }

    private final LinkedHashMap<String, Asset> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final int maxEntries;
    private final long maxBytes;
    private long bytes;
    private long version;

    public AssetCache() {
        this(MAX_ENTRIES, MAX_BYTES);
    }

    public AssetCache(int maxEntries, long maxBytes) {
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
    }

    public Optional<Asset> get(String hash) {
        return Optional.ofNullable(entries.get(hash));
    }

    /** Adds (or replaces) an asset; one larger than the whole budget is not kept. */
    public void put(Asset asset) {
        Asset old = entries.remove(asset.hash());
        if (old != null) bytes -= old.clipboard().estimatedBytes();
        long size = asset.clipboard().estimatedBytes();
        if (size > maxBytes) return;
        entries.put(asset.hash(), asset);
        bytes += size;
        Iterator<Map.Entry<String, Asset>> oldest = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || bytes > maxBytes) && oldest.hasNext()) {
            Asset evicted = oldest.next().getValue();
            if (evicted == asset) break;
            bytes -= evicted.clipboard().estimatedBytes();
            oldest.remove();
        }
    }

    /**
     * How many library changes were applied to this cache ({@link #moved}, {@link #removed}). A load or preview reads
     * this when it is admitted and caches its asset with {@link #putIfUnchanged}: an asset read before a change and
     * answered after it may carry a stale path, or a deleted file.
     */
    public long version() {
        return version;
    }

    /** {@link #put}, unless a library change came in since {@code seen} ({@link #version()} at admission). */
    public void putIfUnchanged(Asset asset, long seen) {
        if (seen == version) put(asset);
    }

    /**
     * Library management moved {@code from} (a file, or a folder and everything in it) to {@code to}: the assets loaded
     * from there keep their content and hash and take the new path, which is what access is checked against. Assets
     * loaded through another spelling of the same path (a file system that ignores case) are dropped instead: they are
     * found again under the new name the next time their preview is asked for.
     */
    public void moved(LibraryPath from, LibraryPath to) {
        version++;
        Iterator<Map.Entry<String, Asset>> all = entries.entrySet().iterator();
        while (all.hasNext()) {
            Map.Entry<String, Asset> entry = all.next();
            Asset asset = entry.getValue();
            LibraryPath moved = movedPath(asset.path(), from, to);
            if (moved != null) {
                entry.setValue(new Asset(asset.hash(), moved, asset.clipboard(), asset.info()));
            } else if (inIgnoringCase(asset.path(), from)) {
                bytes -= asset.clipboard().estimatedBytes();
                all.remove();
            }
        }
    }

    /**
     * Library management deleted {@code path}: the assets loaded from it (under any spelling of it, as on a file system
     * that ignores case) are dropped, so it can no longer be pasted or scattered by hash (a copy elsewhere in the
     * library is found again the next time its preview is asked for).
     */
    public void removed(LibraryPath path) {
        version++;
        Iterator<Map.Entry<String, Asset>> all = entries.entrySet().iterator();
        while (all.hasNext()) {
            Asset asset = all.next().getValue();
            if (inIgnoringCase(asset.path(), path)) {
                bytes -= asset.clipboard().estimatedBytes();
                all.remove();
            }
        }
    }

    /** {@code path} after {@code from} became {@code to}, or {@code null} when it is not {@code from} or inside it. */
    static LibraryPath movedPath(LibraryPath path, LibraryPath from, LibraryPath to) {
        if (from.isFile()) return path.equals(from) ? to : null;
        List<String> segments = path.segments();
        List<String> prefix = from.segments();
        if (segments.size() <= prefix.size() || !segments.subList(0, prefix.size()).equals(prefix)) return null;
        LibraryPath moved = to;
        for (int i = prefix.size(); i < segments.size(); i++) {
            moved = moved.child(segments.get(i), i == segments.size() - 1 && path.isFile());
        }
        return moved;
    }

    /** Whether {@code path} is {@code changed} (a file) or inside it (a folder), ignoring case. */
    public static boolean inIgnoringCase(LibraryPath path, LibraryPath changed) {
        String text = path.toString().toLowerCase(Locale.ROOT);
        String prefix = changed.toString().toLowerCase(Locale.ROOT);
        return changed.isFile() ? text.equals(prefix) : text.startsWith(prefix + "/");
    }

    /**
     * Drops the assets {@code drop} accepts (per-asset access: a restricted file about to be renamed, moved or
     * deleted is evicted at admission, before its grant moves, so no path check runs against a stale name). Counts
     * as a library change ({@link #version()}).
     */
    public void removeIf(java.util.function.Predicate<Asset> drop) {
        version++;
        Iterator<Map.Entry<String, Asset>> all = entries.entrySet().iterator();
        while (all.hasNext()) {
            Asset asset = all.next().getValue();
            if (drop.test(asset)) {
                bytes -= asset.clipboard().estimatedBytes();
                all.remove();
            }
        }
    }

    public void clear() {
        entries.clear();
        bytes = 0;
    }

    public int size() {
        return entries.size();
    }
}
