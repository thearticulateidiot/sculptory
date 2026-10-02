package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CellPredicate;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import org.junit.jupiter.api.Test;

/**
 * What a save or export may write ({@code ServerClipboards.requireStorableBox}): a box under the player's clipboard
 * volume (or {@code limit.bypass}) and never over {@code MAX_STORED_BOX}; a structure file, which lists every present
 * block in memory before writing, never over {@code MAX_STRUCTURE_CELLS} present blocks, bypass or not.
 */
class StorableBoxTest {
    private final FakeStateSpace states = new FakeStateSpace();

    /**
     * A clipboard of a box {@code sizeX} × 1 × {@code sizeZ} copied without its air (a masked copy), so its present
     * cells are the stone it is filled with when {@code filled}, else none.
     */
    private Clipboard clipboard(int sizeX, int sizeZ, boolean filled) {
        FakeWorld world = new FakeWorld(states);
        Box box = new Box(BlockPos.ORIGIN, new BlockPos(sizeX - 1, 0, sizeZ - 1));
        if (filled) world.fill(box, states.state("minecraft:stone"));
        CellPredicate notAir = (x, y, z, before) -> before != states.air();
        return Clipboard.copyOf(world, box, BlockPos.ORIGIN, notAir, "test");
    }

    @Test
    void aStructureFileIsRefusedOverItsPresentCellCap() {
        Clipboard overCap = clipboard(1025, 1024, true);
        assertEquals(ServerClipboards.MAX_STRUCTURE_CELLS + 1024, overCap.cellCount());
        EditRejected refused = assertThrows(EditRejected.class, () -> ServerClipboards.requireStorableBox(overCap,
                SchematicFormat.STRUCTURE, Long.MAX_VALUE, true), "bypass does not lift the structure cap");
        assertEquals(RejectReason.TOO_LARGE, refused.reason());
        assertTrue(refused.getMessage().contains(".schem or .litematic"), refused.getMessage());
        // The same clipboard saves as a Sponge or Litematica schematic (its box is under the caps).
        for (SchematicFormat other : new SchematicFormat[] {SchematicFormat.SPONGE, SchematicFormat.LITEMATIC}) {
            assertDoesNotThrow(() -> ServerClipboards.requireStorableBox(overCap, other, Long.MAX_VALUE, false));
        }
    }

    @Test
    void aStructureFileAtTheCapOrWithAnEmptyBoxIsFine() {
        Clipboard atCap = clipboard(1024, 1024, true);
        assertEquals(ServerClipboards.MAX_STRUCTURE_CELLS, atCap.cellCount());
        assertDoesNotThrow(() -> ServerClipboards.requireStorableBox(atCap, SchematicFormat.STRUCTURE, Long.MAX_VALUE,
                false));
        // A structure file lists present cells, so a large but empty box is not over the cap: the box caps still apply.
        Clipboard emptyBox = clipboard(2048, 1024, false);
        assertDoesNotThrow(() -> ServerClipboards.requireStorableBox(emptyBox, SchematicFormat.STRUCTURE, Long.MAX_VALUE,
                false));
        EditRejected overVolume = assertThrows(EditRejected.class, () -> ServerClipboards.requireStorableBox(emptyBox,
                SchematicFormat.STRUCTURE, 1000, false), "the player's clipboard volume still caps the box");
        assertEquals(RejectReason.TOO_LARGE, overVolume.reason());
        assertDoesNotThrow(() -> ServerClipboards.requireStorableBox(emptyBox, SchematicFormat.STRUCTURE, 1000, true),
                "which limit.bypass lifts");
    }
}
