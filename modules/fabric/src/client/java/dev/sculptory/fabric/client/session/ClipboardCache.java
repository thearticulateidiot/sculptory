package dev.sculptory.fabric.client.session;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.server.net.PreviewPayload;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * M2. What the client knows about server-held clipboards and their previews.
 *
 * <p><b>The clipboard.</b> The server holds one clipboard per player: each copy, library load or upload
 * ({@code ClipboardReady}) replaces it and the old id dies. {@link #current()} is that clipboard, the one Ctrl+V
 * pastes.
 *
 * <p><b>Previews.</b> Decoded {@code bspv1} payloads, keyed by the clipboard content hash and found through the
 * source they were asked for ({@link #preview(SourceRef)}). They are kept most-recently-used first within a byte cap,
 * so a clipboard or asset previewed again is not downloaded again.
 *
 * <p>Render thread only.
 */
public interface ClipboardCache {
    /**
     * A clipboard: at least one cell on every side (the session drops anything else the server claims), and the
     * entities it holds.
     */
    record Entry(UUID clipboardId, BlockPos dims, BlockPos anchor, long cells, long bytes, int entities) {
        public Entry {
            Objects.requireNonNull(clipboardId);
            Objects.requireNonNull(dims);
            Objects.requireNonNull(anchor);
            if (dims.x() < 1 || dims.y() < 1 || dims.z() < 1) throw new IllegalArgumentException("Empty clipboard " + dims);
            if (entities < 0) throw new IllegalArgumentException("Negative entity count");
        }

        /** A clipboard without entities. */
        public Entry(UUID clipboardId, BlockPos dims, BlockPos anchor, long cells, long bytes) {
            this(clipboardId, dims, anchor, cells, bytes, 0);
        }
    }

    /**
     * A decoded preview: the source's blocks in its own local coordinates ({@code 0..dims-1}), air left out, as a
     * {@link GhostVolume} whose frame is the whole box, untransformed.
     *
     * @param key the clipboard content hash
     * @param anchor the local cell that lands on a paste's origin (may lie outside the box)
     * @param cells present cells of the source, air included
     * @param bytes estimated memory held by the volume
     * @param unknownCells cells whose block state this client does not know (not shown)
     * @param entities the source's entities (local coordinates and sizes), drawn as outline boxes
     */
    record Preview(String key, BlockPos dims, BlockPos anchor, long cells, GhostVolume volume, long bytes,
                   long unknownCells, List<PreviewPayload.Entity> entities) {
        public Preview {
            Objects.requireNonNull(key);
            Objects.requireNonNull(dims);
            Objects.requireNonNull(anchor);
            Objects.requireNonNull(volume);
            entities = List.copyOf(entities);
        }

        /** A preview without entities. */
        public Preview(String key, BlockPos dims, BlockPos anchor, long cells, GhostVolume volume, long bytes,
                       long unknownCells) {
            this(key, dims, anchor, cells, volume, bytes, unknownCells, List.of());
        }
    }

    /** The player's clipboard, the one Ctrl+V pastes. */
    Optional<Entry> current();

    Optional<Entry> get(UUID clipboardId);

    /** The known clipboards, newest first: at most the current one, since the server keeps one per player. */
    List<Entry> entries();

    /** The preview of a clipboard or asset, if it has been downloaded (and not evicted). Counts as a use. */
    Optional<Preview> preview(SourceRef source);

    /** A preview by its key (the clipboard content hash). Counts as a use. */
    Optional<Preview> preview(String key);

    /** Estimated memory held by the cached previews. */
    long previewBytes();

    /**
     * Forgets the current clipboard on this client (the Clipboard window's Clear): Ctrl+V has nothing to paste until
     * the next copy. The server still holds it until then.
     */
    default void forgetCurrent() {}

    /**
     * Stops finding a preview through {@code source}, so the next {@link EditorSession#requestPreview} asks the
     * server again: for an asset the server no longer holds (its asset cache is shared and bounded), asking again is
     * what lets it be pasted.
     */
    default void forgetPreview(SourceRef source) {}

    Subscription onChange(Runnable listener);
}
