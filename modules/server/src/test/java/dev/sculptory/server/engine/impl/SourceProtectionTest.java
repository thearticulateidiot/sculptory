package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.library.Library;
import dev.sculptory.server.library.LibraryPath;
import dev.sculptory.server.library.LibraryPathException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The source-read protection rule on one chunk's permit, and where saves land. */
class SourceProtectionTest {
    /** Columns x 4..11, z 0..15 of chunk (0, 0). */
    private static final Box BOX = new Box(new BlockPos(4, 60, 0), new BlockPos(11, 70, 15));

    private static ChunkPermit.Columns deny(int x, int z) {
        long[] bits = {-1L, -1L, -1L, -1L};
        int bit = ((z & 15) << 4) | (x & 15);
        bits[bit >>> 6] &= ~(1L << bit);
        return new ChunkPermit.Columns(bits);
    }

    @Test
    void anyProtectedColumnOfTheSourceRefusesTheRead() {
        assertTrue(EngineEditService.allowsWholeRectangle(ChunkPermit.ALLOW, 0, 0, BOX));
        assertFalse(EngineEditService.allowsWholeRectangle(ChunkPermit.DENY, 0, 0, BOX));
        assertFalse(EngineEditService.allowsWholeRectangle(null, 0, 0, BOX));
        assertTrue(EngineEditService.allowsWholeRectangle(new ChunkPermit.Columns(new long[] {-1L, -1L, -1L, -1L}), 0, 0, BOX));
        assertFalse(EngineEditService.allowsWholeRectangle(deny(4, 0), 0, 0, BOX), "a corner column");
        assertFalse(EngineEditService.allowsWholeRectangle(deny(8, 9), 0, 0, BOX), "an inner column");
        assertTrue(EngineEditService.allowsWholeRectangle(deny(2, 9), 0, 0, BOX), "a column outside the box");
        assertTrue(EngineEditService.allowsWholeRectangle(deny(12, 0), 0, 0, BOX), "a column outside the box");
    }

    @Test
    void savesWithoutLibraryWriteLandInThePlayersFolder() throws LibraryPathException {
        UUID player = UUID.fromString("00000000-0000-4000-8000-000000000001");
        LibraryPath shared = LibraryPath.file("trees/oak.schem");
        assertEquals(shared, ServerClipboards.saveTarget(shared, new Library.Viewer(player, true, false)));
        assertEquals(shared, ServerClipboards.saveTarget(shared, new Library.Viewer(player, false, true)));
        assertEquals("_players/" + player + "/trees/oak.schem",
                ServerClipboards.saveTarget(shared, new Library.Viewer(player, false, false)).toString());
        LibraryPath own = LibraryPath.file("_players/" + player + "/a.schem");
        assertEquals(own, ServerClipboards.saveTarget(own, new Library.Viewer(player, false, false)));
        LibraryPath other = LibraryPath.file("_players/" + UUID.randomUUID() + "/a.schem");
        LibraryPath target = ServerClipboards.saveTarget(other, new Library.Viewer(player, false, false));
        assertFalse(Library.mayWrite(target, new Library.Viewer(player, false, false)), "another player's folder");
    }
}
