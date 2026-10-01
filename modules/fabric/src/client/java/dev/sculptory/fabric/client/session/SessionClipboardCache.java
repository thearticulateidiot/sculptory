package dev.sculptory.fabric.client.session;

import dev.sculptory.core.edit.SourceRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@link ClipboardCache} both sessions use: the current clipboard from {@code ClipboardReady}, and the decoded
 * previews in least-recently-used order within {@link #maxBytes()} and {@link #maxEntries()}. The preview just added
 * is never evicted, even when it alone is over the cap. Render thread only.
 */
public final class SessionClipboardCache implements ClipboardCache {
    /** Default memory cap for decoded previews. */
    public static final long DEFAULT_MAX_BYTES = 256L << 20;
    /** Default number of previews kept. */
    public static final int DEFAULT_MAX_ENTRIES = 32;

    private final Listeners<Runnable> listeners = new Listeners<>();
    private final long maxBytes;
    private final int maxEntries;
    private Entry current;
    /** Previews by key, least recently used first. */
    private final LinkedHashMap<String, Preview> previews = new LinkedHashMap<>(16, 0.75f, true);
    /** {@link #alias} of a source to the key of its preview. */
    private final Map<String, String> aliases = new HashMap<>();
    private long bytes;

    public SessionClipboardCache() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_ENTRIES);
    }

    public SessionClipboardCache(long maxBytes, int maxEntries) {
        if (maxBytes < 1 || maxEntries < 1) throw new IllegalArgumentException("Cache caps must be positive");
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
    }

    public long maxBytes() {
        return maxBytes;
    }

    public int maxEntries() {
        return maxEntries;
    }

    // ---- The clipboard ----

    /** A new server clipboard ({@code ClipboardReady}): it replaces the previous one, whose id is now dead. */
    public void setCurrent(Entry entry) {
        Objects.requireNonNull(entry);
        if (current != null && !current.clipboardId().equals(entry.clipboardId())) {
            aliases.remove(alias(new SourceRef.Clipboard(current.clipboardId())));
        }
        current = entry;
        Listeners.run(listeners);
    }

    /** Forgets the current clipboard (the Clipboard window's Clear); the server still holds it. */
    @Override
    public void forgetCurrent() {
        if (current == null) return;
        aliases.remove(alias(new SourceRef.Clipboard(current.clipboardId())));
        current = null;
        Listeners.run(listeners);
    }

    /** Forgets everything: the clipboard and every preview (disconnect). */
    public void clear() {
        boolean changed = current != null || !previews.isEmpty();
        current = null;
        previews.clear();
        aliases.clear();
        bytes = 0;
        if (changed) Listeners.run(listeners);
    }

    @Override
    public Optional<Entry> current() {
        return Optional.ofNullable(current);
    }

    @Override
    public Optional<Entry> get(UUID clipboardId) {
        return current != null && current.clipboardId().equals(clipboardId) ? Optional.of(current) : Optional.empty();
    }

    @Override
    public List<Entry> entries() {
        return current == null ? List.of() : List.of(current);
    }

    // ---- Previews ----

    /**
     * Adds (or replaces) a preview under its key, reachable from each of {@code sources}, then evicts the least
     * recently used previews until the caps hold again.
     */
    public void putPreview(Preview preview, List<SourceRef> sources) {
        Objects.requireNonNull(preview);
        Preview old = previews.remove(preview.key());
        if (old != null) bytes -= old.bytes();
        previews.put(preview.key(), preview);
        bytes += preview.bytes();
        for (SourceRef source : sources) aliases.put(alias(source), preview.key());
        evict(preview.key());
        Listeners.run(listeners);
    }

    private void evict(String keep) {
        Iterator<Map.Entry<String, Preview>> oldest = previews.entrySet().iterator();
        while ((bytes > maxBytes || previews.size() > maxEntries) && oldest.hasNext()) {
            Map.Entry<String, Preview> entry = oldest.next();
            if (entry.getKey().equals(keep)) continue;
            bytes -= entry.getValue().bytes();
            oldest.remove();
        }
        aliases.values().removeIf(key -> !previews.containsKey(key));
    }

    @Override
    public void forgetPreview(SourceRef source) {
        aliases.remove(alias(Objects.requireNonNull(source)));
    }

    @Override
    public Optional<Preview> preview(SourceRef source) {
        String key = aliases.get(alias(Objects.requireNonNull(source)));
        return key == null ? Optional.empty() : preview(key);
    }

    @Override
    public Optional<Preview> preview(String key) {
        return Optional.ofNullable(previews.get(Objects.requireNonNull(key)));
    }

    /** The keys of the cached previews, least recently used first (for tests). */
    public List<String> previewKeys() {
        return new ArrayList<>(previews.keySet());
    }

    @Override
    public long previewBytes() {
        return bytes;
    }

    @Override
    public Subscription onChange(Runnable listener) {
        return listeners.add(listener);
    }

    private static String alias(SourceRef source) {
        return switch (source) {
            case SourceRef.Clipboard clipboard -> "clipboard:" + clipboard.id();
            case SourceRef.Asset asset -> "asset:" + asset.contentHash();
        };
    }
}
