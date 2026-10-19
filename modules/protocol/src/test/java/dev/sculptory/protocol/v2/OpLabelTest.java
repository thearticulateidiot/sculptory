package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpLabelTest {
    private static final Region BOX = new Region.Cuboid(Box.of(new BlockPos(0, 60, 0), new BlockPos(3, 62, 3)));
    private static final Pattern STONE = new Pattern.Single(1);

    private static final List<OpSpec> OPS = List.of(
            new OpSpec.Fill(BOX, STONE, CellMask.ANY),
            new OpSpec.Replace(BOX, CellMask.ANY, STONE),
            new OpSpec.Erase(BOX, CellMask.ANY),
            new OpSpec.Hollow(BOX, 1, STONE),
            new OpSpec.Walls(BOX, 1, STONE),
            new OpSpec.Paste(new SourceRef.Clipboard(new UUID(1, 2)), BlockPos.ORIGIN, Transform.IDENTITY, PasteOptions.DEFAULT),
            new OpSpec.Move(BOX, new BlockPos(1, 0, 0), Transform.IDENTITY, STONE, EntityFilter.NONE),
            new OpSpec.Stack(BOX, 1, 0, 0, 2, EntityFilter.NONE),
            new OpSpec.ScatterCommit(new UUID(3, 4)),
            new OpSpec.Overlay(BOX, STONE, 1),
            new OpSpec.Naturalize(BOX, STONE, 1, STONE, 3, STONE),
            new OpSpec.UpdateBlocks(BOX),
            new OpSpec.PasteMany(new SourceRef.Clipboard(new UUID(1, 2)), List.of(new OpSpec.PasteMany.Placement(
                    BlockPos.ORIGIN, Orientation.IDENTITY)), PasteOptions.DEFAULT),
            new OpSpec.BiomeFill(BOX, "minecraft:plains", true));

    /** Each label fits exactly the op kind its tool sends; NONE fits every op. */
    @Test
    void eachLabelFitsItsToolsOpKind() {
        for (OpSpec op : OPS) assertTrue(OpLabel.NONE.fits(op), op.toString());
        assertEquals(List.of(OpSpec.Paste.class), fitting(OpLabel.ROAD));
        assertEquals(List.of(OpSpec.Paste.class), fitting(OpLabel.ROOF));
        assertEquals(List.of(OpSpec.Stack.class), fitting(OpLabel.EXTRUDE));
        assertEquals(List.of(OpSpec.Erase.class), fitting(OpLabel.CARVE));
        assertEquals(List.of(OpSpec.Move.class), fitting(OpLabel.SMEAR));
        assertEquals(List.of(OpSpec.Fill.class), fitting(OpLabel.FLOOD));
        assertEquals(List.of(OpSpec.Fill.class), fitting(OpLabel.DRAIN));
        assertEquals(List.of(OpSpec.Paste.class), fitting(OpLabel.LINE));
        assertEquals(List.of(OpSpec.Paste.class), fitting(OpLabel.SHAPE_LINE));
        assertEquals(List.of(OpSpec.Paste.class), fitting(OpLabel.PATH_EXTRUDE));
        assertEquals(List.of(OpSpec.Paste.class), fitting(OpLabel.TAPER));
        assertEquals(List.of(OpSpec.Paste.class), fitting(OpLabel.SWEEP));
        assertEquals(List.of(OpSpec.Paste.class), fitting(OpLabel.HEIGHTMAP));
        assertEquals(List.of(OpSpec.PasteMany.class), fitting(OpLabel.STAMP));
        assertEquals(List.of(OpSpec.Move.class), fitting(OpLabel.ROTATE));
        assertEquals(List.of(OpSpec.BiomeFill.class), fitting(OpLabel.BIOME_FILL));
        assertEquals(List.of(OpSpec.Paste.class), fitting(OpLabel.SMART_SURFACE));
    }

    @Test
    void theTextIsTheToolsNameAndNoneHasNone() {
        assertNull(OpLabel.NONE.text());
        assertEquals("Road", OpLabel.ROAD.text());
        assertEquals("Roof", OpLabel.ROOF.text());
        assertEquals("Extrude", OpLabel.EXTRUDE.text());
        assertEquals("Carve", OpLabel.CARVE.text());
        assertEquals("Smear", OpLabel.SMEAR.text());
        assertEquals("Flood", OpLabel.FLOOD.text());
        assertEquals("Drain", OpLabel.DRAIN.text());
        assertEquals("Line", OpLabel.LINE.text());
        assertEquals("Shape line", OpLabel.SHAPE_LINE.text());
        assertEquals("Extrude path", OpLabel.PATH_EXTRUDE.text());
        assertEquals("Taper", OpLabel.TAPER.text());
        assertEquals("Sweep", OpLabel.SWEEP.text());
        assertEquals("Heightmap", OpLabel.HEIGHTMAP.text());
        assertEquals("Stamp", OpLabel.STAMP.text());
        assertEquals("Rotate", OpLabel.ROTATE.text());
        assertEquals("Biome fill", OpLabel.BIOME_FILL.text());
        assertEquals("Smart surface", OpLabel.SMART_SURFACE.text());
        for (OpLabel label : OpLabel.values()) {
            if (label != OpLabel.NONE) assertFalse(label.text().isBlank(), label.name());
        }
    }

    private static List<Class<?>> fitting(OpLabel label) {
        return OPS.stream().filter(label::fits).<Class<?>>map(OpSpec::getClass).toList();
    }
}
