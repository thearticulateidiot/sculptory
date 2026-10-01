package dev.sculptory.fabric.client.session;

import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.protocol.v2.S2C;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One library folder as the server listed it ({@code LibraryListing}): at most 512 entries, folders, {@code .schem}
 * files and {@code .palette.json} palettes (each entry's {@code kind} says which), with paths relative to the library
 * root. {@code folder} is {@code ""} for the root. The
 * session keeps only valid entries (see {@link ClipboardTransfers#validEntry}). {@code writable} (M4): the server lets
 * this player create folders here and rename, move and delete the entries (it checks each request again).
 */
public record LibraryFolder(String folder, List<S2C.LibraryListing.Entry> entries, boolean writable) {
    public LibraryFolder {
        Objects.requireNonNull(folder);
        entries = List.copyOf(entries);
    }

    /** The asset a content hash names, or empty when it is not a lowercase SHA-256 (never throws). */
    public static Optional<SourceRef> asset(String contentHash) {
        return contentHash != null && ClipboardTransfers.SHA256.matcher(contentHash).matches()
                ? Optional.of(new SourceRef.Asset(contentHash))
                : Optional.empty();
    }
}
