package dev.sculptory.fabric.client.session;

import java.util.Objects;

/**
 * Where the server saved an asset ({@code AssetSaved}). {@code path} may differ from the requested one: players
 * without {@code library.write} save under {@code _players/<uuid>/}. {@code contentHash} is the file's SHA-256.
 */
public record SavedAsset(String path, String contentHash) {
    public SavedAsset {
        Objects.requireNonNull(path);
        Objects.requireNonNull(contentHash);
    }
}
