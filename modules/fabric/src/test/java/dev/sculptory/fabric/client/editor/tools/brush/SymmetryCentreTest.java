package dev.sculptory.fabric.client.editor.tools.brush;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SymmetryCentreTest {
    @Test
    void aSelectionsCentreIsABlockCentreAlongOddSidesAndAnEdgeAlongEvenOnes() {
        assertArrayEquals(new int[] {1, 1}, SymmetryCentre.centreOf(Box.of(new BlockPos(0, 0, 0))), "one block: its centre");
        assertArrayEquals(new int[] {-2, 2}, SymmetryCentre.centreOf(Box.of(new BlockPos(-6, 5, -3), new BlockPos(3, 9, 4))));
        assertArrayEquals(new int[] {21, -7}, SymmetryCentre.centreOf(Box.of(new BlockPos(8, 0, -6), new BlockPos(12, 0, -2))));
    }

    @Test
    void theSetCentreWinsOverTheSelectionUntilCleared() {
        SymmetryCentre centre = new SymmetryCentre();
        Optional<Box> selection = Optional.of(Box.of(new BlockPos(0, 0, 0), new BlockPos(9, 0, 9)));
        assertTrue(centre.resolve(Optional.empty()).isEmpty());
        assertArrayEquals(new int[] {10, 10}, centre.resolve(selection).orElseThrow());
        centre.set(-5, 7);
        assertArrayEquals(new int[] {-5, 7}, centre.resolve(selection).orElseThrow());
        assertArrayEquals(new int[] {-5, 7}, centre.resolve(Optional.empty()).orElseThrow());
        centre.clear();
        assertFalse(centre.isSet());
        assertArrayEquals(new int[] {10, 10}, centre.resolve(selection).orElseThrow());
    }

    @Test
    void rotate4MovesAMixedCentreOntoBlockCentres() {
        assertArrayEquals(new int[] {-3, 1}, SymmetryCentre.fitted(Symmetry.Mode.ROTATE_4, -2, 1));
        assertArrayEquals(new int[] {5, 3}, SymmetryCentre.fitted(Symmetry.Mode.ROTATE_4, 5, 4));
        assertArrayEquals(new int[] {6, 4}, SymmetryCentre.fitted(Symmetry.Mode.ROTATE_4, 6, 4), "both edges stay");
        assertArrayEquals(new int[] {5, 7}, SymmetryCentre.fitted(Symmetry.Mode.ROTATE_4, 5, 7), "both centres stay");
        for (Symmetry.Mode mode : Symmetry.Mode.values()) {
            if (mode == Symmetry.Mode.ROTATE_4) continue;
            assertArrayEquals(new int[] {-2, 1}, SymmetryCentre.fitted(mode, -2, 1), mode + " keeps any centre");
        }
    }

    @Test
    void halfBlockCoordinatesReadAsPlayersWriteThem() {
        assertEquals("12", SymmetryCentre.format(24));
        assertEquals("12.5", SymmetryCentre.format(25));
        assertEquals("-3.5", SymmetryCentre.format(-7));
        assertEquals("-0.5", SymmetryCentre.format(-1));
        assertEquals("0", SymmetryCentre.format(0));
        assertEquals("-12", SymmetryCentre.format(-24));
    }

    /** For a Select operation or a placement: none with the mode off, the fitted mode around the set centre, else empty. */
    @Test
    void anOperationNeedsTheSetCentre() {
        SymmetryCentre centre = new SymmetryCentre();
        assertEquals(Optional.of(Symmetry.NONE), centre.forOp(Symmetry.Mode.OFF));
        assertTrue(centre.forOp(Symmetry.Mode.MIRROR_X).isEmpty(), "no centre: refused, never the selection's");
        centre.set(-2, 1);
        assertEquals(Optional.of(new Symmetry(Symmetry.Mode.MIRROR_X, -2, 1)), centre.forOp(Symmetry.Mode.MIRROR_X));
        assertEquals(Optional.of(new Symmetry(Symmetry.Mode.ROTATE_4, -3, 1)), centre.forOp(Symmetry.Mode.ROTATE_4), "fitted");
        assertEquals(Optional.of(Symmetry.NONE), centre.forOp(Symmetry.Mode.OFF));
        assertEquals(new Symmetry(Symmetry.Mode.ROTATE_2, 5, 4), SymmetryCentre.around(Symmetry.Mode.ROTATE_2, 5, 4));
        assertEquals(Symmetry.NONE, SymmetryCentre.around(Symmetry.Mode.MIRROR_X, Integer.MAX_VALUE, 0), "beyond the range");
    }
}
