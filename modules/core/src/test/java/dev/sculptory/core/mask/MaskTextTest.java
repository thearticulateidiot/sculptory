package dev.sculptory.core.mask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The saved text form of a mask ({@link MaskText}): every rule round-trips, bad text is refused. */
class MaskTextTest {
    private static final Box BOX = Box.of(new BlockPos(-5, -64, 3), new BlockPos(10, 70, 12));

    @Test
    void everyRuleRoundTrips() {
        BlockSet set = BlockSet.parse("minecraft:stone;#minecraft:logs;minecraft:oak_stairs[facing=east,half=top]");
        EditMask mask = new EditMask(List.of(
                MaskEntry.of(new MaskRule.Is(set)),
                new MaskEntry(new MaskRule.OnTopOf(BlockSet.parse("minecraft:grass_block")), true),
                MaskEntry.of(new MaskRule.Under(BlockSet.parse("#minecraft:leaves"))),
                MaskEntry.of(new MaskRule.NextTo(BlockSet.parse("minecraft:water"))),
                new MaskEntry(new MaskRule.Exposed(), true),
                MaskEntry.of(new MaskRule.NotAir()),
                MaskEntry.of(new MaskRule.Solid()),
                MaskEntry.of(new MaskRule.Height(-64, 319)),
                MaskEntry.of(new MaskRule.Slope(0, 16)),
                MaskEntry.of(new MaskRule.Inside(new Region.Cuboid(BOX))),
                new MaskEntry(new MaskRule.Inside(new Region.Shape(BOX, ShapeKind.CONE, Facing.NORTH)), true),
                MaskEntry.of(new MaskRule.Chance(37, -1234567890123L))), true);
        String text = MaskText.encode(mask);
        assertEquals(mask, MaskText.decode(text));
        assertEquals(text, MaskText.encode(MaskText.decode(text)));
        assertEquals(EditMask.NONE, MaskText.decode(MaskText.encode(EditMask.NONE)));
        assertEquals("v1", MaskText.encode(EditMask.NONE));
        assertEquals("v1 | is minecraft:stone | !height 0 63", MaskText.encode(new EditMask(List.of(
                MaskEntry.of(new MaskRule.Is(BlockSet.parse("minecraft:stone"))),
                new MaskEntry(new MaskRule.Height(0, 63), true)), false)));
    }

    @Test
    void malformedTextIsRefused() {
        for (String bad : List.of("", "v2", "v1 |", "v1 | is", "v1 | frob", "v1 | height 1", "v1 | height 5 4",
                "v1 | slope 0 17", "v1 | chance 0 1", "v1 | chance 100 1", "v1 | inside box 1 2 3", "v1 | inside blob",
                "v1 | inside shape round up 0 0 0 1 1 1", "v1 | is minecraft:stone extra", "v1 | touches_air now",
                "v1 invert x", "v1 | height a b")) {
            assertThrows(IllegalArgumentException.class, () -> MaskText.decode(bad), bad);
        }
        StringBuilder seventeen = new StringBuilder("v1");
        for (int i = 0; i < 17; i++) seventeen.append(" | solid");
        assertThrows(IllegalArgumentException.class, () -> MaskText.decode(seventeen.toString()));
    }

    @Test
    void aCellSetHasNoTextForm() {
        CellSet cells = CellSet.of(new Region.Cuboid(Box.of(new BlockPos(1, 2, 3))), 10);
        assertThrows(IllegalArgumentException.class, () -> MaskText.encode(new EditMask(List.of(
                MaskEntry.of(new MaskRule.Inside(new Region.Cells(cells)))), false)));
    }

    @Test
    void anExactStateKeepsItsProperties() {
        BlockSet set = BlockSet.of(new BlockSet.State(BlockDescriptor.parse("minecraft:oak_log[axis=x]")));
        EditMask mask = new EditMask(List.of(MaskEntry.of(new MaskRule.Is(set))), false);
        assertEquals(mask, MaskText.decode(MaskText.encode(mask)));
    }
}
