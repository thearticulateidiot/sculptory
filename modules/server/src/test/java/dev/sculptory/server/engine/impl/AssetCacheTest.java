package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.server.library.LibraryPath;
import dev.sculptory.server.library.LibraryPathException;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** M4: loaded library assets follow renames and deletes, and a load answered after a change is not cached. */
class AssetCacheTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();

    private static LibraryPath file(String path) {
        try {
            return LibraryPath.file(path);
        } catch (LibraryPathException e) {
            throw new AssertionError(e);
        }
    }

    private static LibraryPath folder(String path) {
        try {
            return LibraryPath.folder(path);
        } catch (LibraryPathException e) {
            throw new AssertionError(e);
        }
    }

    private static AssetCache.Asset asset(String hash, String path) {
        Clipboard clipboard = Clipboard.builder(STATES, new BlockPos(1, 1, 1)).build();
        return new AssetCache.Asset(hash, file(path), clipboard);
    }

    private static Optional<String> pathOf(AssetCache cache, String hash) {
        return cache.get(hash).map(asset -> asset.path().toString());
    }

    @Test
    void removeIfDropsWhatItAcceptsAndCountsAsAChange() {
        AssetCache cache = new AssetCache();
        cache.put(asset("a", "trees/oak.schem"));
        cache.put(asset("b", "trees/birch.schem"));
        long seen = cache.version();
        cache.removeIf(asset -> asset.path().name().equals("oak.schem"));
        assertTrue(cache.get("a").isEmpty() && cache.get("b").isPresent());
        assertEquals(1, cache.size());
        cache.putIfUnchanged(asset("a", "trees/oak.schem"), seen);
        assertTrue(cache.get("a").isEmpty(), "a load admitted before the eviction is not cached");
    }

    @Test
    void renamesAndMovesGiveLoadedAssetsTheirNewPath() {
        AssetCache cache = new AssetCache();
        cache.put(asset("a", "trees/oak.schem"));
        cache.put(asset("b", "trees/big/elm.schem"));
        cache.put(asset("c", "rocks/stone.schem"));
        cache.moved(file("trees/oak.schem"), file("_players/00000000-0000-4000-8000-00000000000a/oak.schem"));
        assertEquals(Optional.of("_players/00000000-0000-4000-8000-00000000000a/oak.schem"), pathOf(cache, "a"),
                "access is now checked against the player folder");
        cache.moved(folder("trees"), folder("forest"));
        assertEquals(Optional.of("forest/big/elm.schem"), pathOf(cache, "b"));
        assertEquals(Optional.of("rocks/stone.schem"), pathOf(cache, "c"), "untouched");
    }

    @Test
    void deletesDropTheAssetUnderAnySpellingOfItsPath() {
        AssetCache cache = new AssetCache();
        cache.put(asset("a", "Trees/Oak.schem")); // loaded through another spelling (a file system ignoring case)
        cache.put(asset("b", "trees/sub/x.schem"));
        cache.put(asset("c", "treesx/y.schem"));
        cache.removed(file("trees/oak.schem"));
        assertTrue(cache.get("a").isEmpty());
        cache.removed(folder("TREES/SUB"));
        assertTrue(cache.get("b").isEmpty());
        assertEquals(Optional.of("treesx/y.schem"), pathOf(cache, "c"), "a folder is not a name prefix");
        // A move through another spelling drops what it cannot re-path for sure.
        cache.put(asset("d", "Rocks/Stone.schem"));
        cache.moved(file("rocks/stone.schem"), file("rocks/granite.schem"));
        assertTrue(cache.get("d").isEmpty());
        assertEquals(1, cache.size());
    }

    @Test
    void anAssetReadBeforeALibraryChangeIsNotCached() {
        AssetCache cache = new AssetCache();
        long seen = cache.version();
        cache.putIfUnchanged(asset("a", "trees/oak.schem"), seen);
        assertTrue(cache.get("a").isPresent(), "no change in between");

        long before = cache.version();
        cache.removed(file("trees/oak.schem")); // a delete answered while a load was reading the file
        cache.putIfUnchanged(asset("a", "trees/oak.schem"), before);
        assertTrue(cache.get("a").isEmpty(), "a deleted asset would stay pasteable by hash");

        before = cache.version();
        cache.moved(file("shared/x.schem"), file("_players/00000000-0000-4000-8000-00000000000a/x.schem"));
        cache.putIfUnchanged(asset("x", "shared/x.schem"), before);
        assertTrue(cache.get("x").isEmpty(), "a moved asset would stay readable under its old shared path");
    }
}
