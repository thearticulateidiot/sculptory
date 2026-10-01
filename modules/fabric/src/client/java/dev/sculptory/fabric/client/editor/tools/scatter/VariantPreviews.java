package dev.sculptory.fabric.client.editor.tools.scatter;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.BlockVariants;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.tools.place.GhostBaker;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.client.session.Transfer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The previews of the mix's variants. Downloading a library asset's preview is also what makes the server hold that
 * asset, which a scatter preview needs; the player's clipboard needs no preview there, but its ghost does. A block
 * variant's preview is built here from its state (one cell, or two for a double-tall block; a column plant's taller
 * columns on demand, {@link #columnPreview}), and so is where it goes ({@link #medium}): nothing is downloaded.
 * The decoded previews are held here (not only in the session's bounded cache), so a mix of up to 64 variants keeps
 * its ghosts. Client thread only.
 */
final class VariantPreviews {
    /** Where a variant's preview stands. */
    enum Status {
        LOADING,
        READY,
        FAILED
    }

    private static final class Entry {
        Transfer<ClipboardCache.Preview> transfer;
        ClipboardCache.Preview preview;
        boolean failed;
        /** Bumped per request, so an answer to an abandoned request is ignored. */
        int generation;
        /** A block variant's medium, once built. */
        BlockVariants.Medium medium;
        /** A tree or feature: ready at once, without a preview (its cells come with the plan). */
        boolean feature;
        /** A column plant's column previews, by height. */
        final Map<Integer, ClipboardCache.Preview> columns = new HashMap<>();
    }

    private final Map<ScatterSource, Entry> entries = new HashMap<>();
    private int version;
    /** The state space the block previews were built from. */
    private StateSpace states;

    /**
     * Asks for the previews of variants not seen yet, builds those of blocks (once {@code states} is known), and
     * forgets variants that left the mix.
     */
    void sync(EditorSession session, StateSpace states, List<ScatterMix.Variant> mix) {
        Set<ScatterSource> wanted = new HashSet<>();
        for (ScatterMix.Variant variant : mix) wanted.add(variant.source());
        entries.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) return false;
            cancel(entry.getValue());
            return true;
        });
        for (ScatterSource source : wanted) {
            Entry entry = entries.get(source);
            if (entry == null) {
                entry = new Entry();
                entries.put(source, entry);
                start(session, states, source, entry);
            } else if (source instanceof ScatterSource.Block block && entry.preview == null && !entry.failed) {
                build(states, block, entry); // the state space was not known yet
            }
        }
    }

    private void start(EditorSession session, StateSpace states, ScatterSource source, Entry entry) {
        switch (source) {
            case ScatterSource.Held held -> request(session, held, entry);
            case ScatterSource.Block block -> build(states, block, entry);
            // A tree or feature grows on the server: nothing to download or build, it goes on land.
            case ScatterSource.Feature feature -> {
                entry.feature = true;
                entry.medium = BlockVariants.Medium.LAND;
                version++;
            }
        }
    }

    private void request(EditorSession session, ScatterSource.Held source, Entry entry) {
        entries.put(source, entry);
        entry.failed = false;
        int generation = ++entry.generation;
        Transfer<ClipboardCache.Preview> transfer = session.requestPreview(source.ref());
        entry.transfer = transfer;
        transfer.result().thenAccept(reply -> {
            if (entries.get(source) != entry || entry.generation != generation) return;
            entry.transfer = null;
            if (reply instanceof Reply.Ok<ClipboardCache.Preview> ok) {
                entry.preview = ok.value();
                entry.failed = false;
            } else if (!(reply instanceof Reply.Failed<ClipboardCache.Preview> failed
                    && failed.failure() == Reply.Failure.CANCELLED)) {
                entry.failed = true;
            }
            version++;
        });
    }

    /** A block variant's preview, from this client's blocks; FAILED when they do not know the state. */
    private void build(StateSpace states, ScatterSource.Block block, Entry entry) {
        if (states == null) return;
        this.states = states;
        entry.generation++;
        entry.columns.clear();
        try {
            entry.preview = blockPreview(states, block);
            entry.medium = BlockVariants.medium(states, BlockVariants.resolve(states, block.state()));
            entry.failed = false;
        } catch (IllegalArgumentException unknownHere) {
            entry.failed = true;
        }
        version++;
    }

    /**
     * Where a block variant's placements go (under water, on the water surface, on land), once its preview is built;
     * empty for clipboards and assets (they go on land) and for blocks this client does not know.
     */
    Optional<BlockVariants.Medium> medium(ScatterSource source) {
        Entry entry = entries.get(Objects.requireNonNull(source));
        return entry == null || entry.failed ? Optional.empty() : Optional.ofNullable(entry.medium);
    }

    /**
     * The ghost geometry of a column plant's column of {@code height} cells ({@link BlockVariants#column}), built on
     * first use; empty for anything else, or before the block's preview is built.
     */
    Optional<ClipboardCache.Preview> columnPreview(ScatterSource source, int height) {
        if (!(source instanceof ScatterSource.Block block) || states == null) return Optional.empty();
        Entry entry = entries.get(source);
        if (entry == null || entry.failed || entry.preview == null) return Optional.empty();
        ClipboardCache.Preview preview = entry.columns.get(height);
        if (preview == null) {
            try {
                int state = BlockVariants.resolve(states, block.state());
                if (!BlockVariants.isColumn(states, state)) return Optional.empty();
                preview = preview(states, "block:" + block.state() + "#" + height, BlockVariants.column(states, state, height));
            } catch (IllegalArgumentException unknownHere) {
                return Optional.empty();
            }
            entry.columns.put(height, preview);
        }
        return Optional.of(preview);
    }

    /** The ghost geometry of a block variant: its footprint ({@link BlockVariants#clipboard}), anchored on the block. */
    static ClipboardCache.Preview blockPreview(StateSpace states, ScatterSource.Block block) {
        Clipboard clipboard = BlockVariants.clipboard(states, BlockVariants.resolve(states, block.state()));
        return preview(states, "block:" + block.state(), clipboard);
    }

    /** A one-column clipboard's ghost geometry, anchored on its bottom cell. */
    private static ClipboardCache.Preview preview(StateSpace states, String name, Clipboard clipboard) {
        BlockPos size = clipboard.size();
        BlockBuffer cells = new BlockBuffer();
        for (int y = 0; y < size.y(); y++) cells.set(0, y, 0, clipboard.get(0, y, 0));
        GhostVolume volume = GhostVolume.of(cells, GhostBaker.air(states));
        volume.setFrame(new Box(BlockPos.ORIGIN, size.offset(-1, -1, -1)));
        return new ClipboardCache.Preview(name, size, clipboard.anchor(), size.y(), volume, 0, 0);
    }

    /**
     * Asks again for every library asset's preview (the server let go of one: its asset cache is shared and bounded).
     * The previews already decoded are kept for the ghosts meanwhile.
     */
    void reloadAssets(EditorSession session) {
        for (Map.Entry<ScatterSource, Entry> entry : Map.copyOf(entries).entrySet()) {
            if (!ScatterMix.isAsset(entry.getKey())) continue;
            ScatterSource.Held held = (ScatterSource.Held) entry.getKey();
            cancel(entry.getValue());
            session.clipboards().forgetPreview(held.ref());
            request(session, held, entry.getValue());
        }
    }

    /** Asks again for (or builds again) the previews that failed; returns how many. */
    int retryFailed(EditorSession session, StateSpace states) {
        int retried = 0;
        for (Map.Entry<ScatterSource, Entry> entry : Map.copyOf(entries).entrySet()) {
            if (!entry.getValue().failed) continue;
            switch (entry.getKey()) {
                case ScatterSource.Held held -> {
                    session.clipboards().forgetPreview(held.ref());
                    request(session, held, entry.getValue());
                }
                case ScatterSource.Block block -> build(states, block, entry.getValue());
                case ScatterSource.Feature feature -> { }
            }
            retried++;
        }
        return retried;
    }

    /** LOADING while a download is in progress (also a reload: the server may not hold the asset yet). */
    Status status(ScatterSource source) {
        Entry entry = entries.get(Objects.requireNonNull(source));
        if (entry == null || entry.transfer != null) return Status.LOADING;
        if (entry.feature) return Status.READY;
        if (entry.failed) return Status.FAILED;
        return entry.preview != null ? Status.READY : Status.LOADING;
    }

    Optional<ClipboardCache.Preview> preview(ScatterSource source) {
        Entry entry = entries.get(Objects.requireNonNull(source));
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.preview);
    }

    /** Library assets whose preview is still downloading (a scatter preview waits for them). */
    int loadingAssets() {
        int loading = 0;
        for (Map.Entry<ScatterSource, Entry> entry : entries.entrySet()) {
            if (ScatterMix.isAsset(entry.getKey()) && entry.getValue().transfer != null) loading++;
        }
        return loading;
    }

    /** The overall download progress of the loading previews, 0..1. */
    double progress() {
        long done = 0, total = 0;
        for (Entry entry : entries.values()) {
            if (entry.transfer == null) continue;
            done += entry.transfer.doneBytes();
            total += entry.transfer.totalBytes();
        }
        return total <= 0 ? 0 : Math.min(1, done / (double) total);
    }

    /** Bumped whenever a preview arrives or fails. */
    int version() {
        return version;
    }

    /** Forgets everything; downloads in progress are left to finish (another tool may share them) and ignored. */
    void clear() {
        entries.values().forEach(VariantPreviews::cancel);
        entries.clear();
        version++;
    }

    /** Stops listening to the entry's download (the session shares one download per source, so it is not cancelled). */
    private static void cancel(Entry entry) {
        entry.generation++;
        entry.transfer = null;
    }

    /** The paste source of a held variant (for the session's own lookups), or {@code null} for a block. */
    static SourceRef refOf(ScatterSource source) {
        return source instanceof ScatterSource.Held held ? held.ref() : null;
    }
}
