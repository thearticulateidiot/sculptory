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

    /** A per-cell permit over layers {@code y0..y1} of chunk (0, 0), allowing every cell but {@code denied}. */
    private static ChunkPermit.Cells allBut(int y0, int y1, BlockPos... denied) {
        long[] bits = new long[(y1 - y0 + 1) * 4];
        java.util.Arrays.fill(bits, -1L);
        for (BlockPos cell : denied) {
            int bit = ((cell.z() & 15) << 4) | (cell.x() & 15);
            bits[ChunkPermit.Cells.word(y0, cell.y(), bit >>> 6)] &= ~(1L << bit);
        }
        return new ChunkPermit.Cells(y0, y1, bits);
    }

    @Test
    void aPerCellPermitIsJudgedOnEveryCellOfTheSource() {
        assertTrue(EngineEditService.allowsWholeRectangle(allBut(60, 70), 0, 0, BOX));
        assertFalse(EngineEditService.allowsWholeRectangle(allBut(60, 70, new BlockPos(8, 65, 9)), 0, 0, BOX),
                "an inner cell");
        assertFalse(EngineEditService.allowsWholeRectangle(allBut(60, 70, new BlockPos(11, 70, 15)), 0, 0, BOX),
                "the top corner cell");
        assertTrue(EngineEditService.allowsWholeRectangle(allBut(60, 70, new BlockPos(2, 65, 9)), 0, 0, BOX),
                "a cell outside the box");
        assertFalse(EngineEditService.allowsWholeRectangle(allBut(60, 65), 0, 0, BOX),
                "a permit not covering the box's heights denies the rest");
    }

    /**
     * A sphere's columns beside protection limited in height: only the heights the sphere holds in each column are
     * judged, so a denied cell above its cap in a column it uses does not refuse it; a denied cell of the sphere does.
     */
    @Test
    void aSelectionsColumnsAreJudgedAtTheHeightsItHolds() {
        dev.sculptory.core.region.Region sphere = new dev.sculptory.core.region.Region.Shape(
                new Box(new BlockPos(0, 60, 0), new BlockPos(10, 70, 10)), dev.sculptory.core.region.ShapeKind.ELLIPSOID,
                dev.sculptory.core.region.Facing.UP);
        long[] held = dev.sculptory.core.region.Regions.columns(sphere, 60, 70)
                .get(dev.sculptory.core.region.Regions.column(0, 0));
        // A column the sphere uses, and a height of it the sphere does not hold (above its cap there).
        BlockPos outside = null;
        BlockPos inside = null;
        for (int bit = 0; bit < 256 && (outside == null || inside == null); bit++) {
            if ((held[bit >>> 6] & (1L << bit)) == 0) continue;
            int x = bit & 15, z = bit >>> 4;
            if (inside == null) {
                for (int y = 60; y <= 70; y++) {
                    if (sphere.contains(x, y, z)) inside = new BlockPos(x, y, z);
                }
            }
            if (outside == null && !sphere.contains(x, 70, z)) outside = new BlockPos(x, 70, z);
        }
        assertTrue(outside != null && inside != null, "the sphere's columns");
        ChunkPermit.Cells aboveTheCap = allBut(60, 70, outside);
        assertTrue(EngineEditService.allowsColumns(aboveTheCap, 0, 0, held, 60, 70, sphere),
                "a denied cell the sphere does not hold");
        assertFalse(EngineEditService.allowsColumns(aboveTheCap, 0, 0, held, 60, 70, null),
                "without the selection, every height of its columns");
        assertFalse(EngineEditService.allowsColumns(allBut(60, 70, inside), 0, 0, held, 60, 70, sphere),
                "a denied cell of the sphere");
        assertTrue(EngineEditService.allowsColumns(ChunkPermit.ALLOW, 0, 0, held, 60, 70, sphere));
        assertFalse(EngineEditService.allowsColumns(ChunkPermit.DENY, 0, 0, held, 60, 70, sphere));
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
