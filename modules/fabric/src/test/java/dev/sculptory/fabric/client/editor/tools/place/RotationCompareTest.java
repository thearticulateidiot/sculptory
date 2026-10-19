package dev.sculptory.fabric.client.editor.tools.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteGeometry;
import dev.sculptory.core.generate.RotationShowcase;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Resampling;
import dev.sculptory.fabric.client.session.ClipboardCache;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The rotation comparison's stamp: a grid of copies, each way at each angle, centred in its own cell. */
class RotationCompareTest {
    @Test
    void theStampLaysOutEveryWayAtEveryAngleCentredInItsCell() {
        BlockPos dims = new BlockPos(RotationShowcase.SIZE_X, RotationShowcase.SIZE_Y, RotationShowcase.SIZE_Z);
        ClipboardCache.Entry clipboard = new ClipboardCache.Entry(UUID.randomUUID(), dims, BlockPos.ORIGIN, 351, 1000, 0);
        BlockPos corner = new BlockPos(100, 64, -40);
        for (boolean slopes : new boolean[] {false, true}) {
            OpSpec.PasteMany stamp = RotationCompare.stamp(clipboard, corner, slopes);
            int columns = RotationShowcase.WAYS.size() + 1;
            assertEquals(RotationShowcase.ANGLES.size() * columns, stamp.placements().size());
            for (int i = 0; i < stamp.placements().size(); i++) {
                OpSpec.PasteMany.Placement p = stamp.placements().get(i);
                int row = i / columns, column = i % columns;
                Orientation o = p.orientation();
                if (column == 0) {
                    assertTrue(o.isIdentity());
                } else {
                    assertEquals(RotationCompare.WAYS.get(column - 1), o.resampling());
                    assertEquals(slopes, o.smoothSlopes());
                    int[] a = RotationShowcase.ANGLES.get(row);
                    assertEquals(a[0], o.yaw());
                    assertEquals(a[1], o.pitch());
                    assertEquals(a[2], o.roll());
                }
                // The turned box sits in its grid cell: its centre within a block of the unturned copy's.
                Box target = PasteGeometry.pasteTarget(dims, BlockPos.ORIGIN, o, p.origin());
                BlockPos cell = corner.add(RotationShowcase.placement(column, row));
                double cx = (target.min().x() + target.max().x() + 1) / 2.0, cz = (target.min().z() + target.max().z() + 1) / 2.0;
                assertTrue(Math.abs(cx - (cell.x() + dims.x() / 2.0)) <= 1.5, o + " x " + cx);
                assertTrue(Math.abs(cz - (cell.z() + dims.z() / 2.0)) <= 1.5, o + " z " + cz);
                // Neighbouring copies never touch: each stays within half the gap around its unturned place.
                int margin = (RotationShowcase.SPACING - dims.x()) / 2;
                assertTrue(target.min().x() >= cell.x() - margin && target.max().x() < cell.x() + dims.x() + margin
                        && target.min().z() >= cell.z() - margin && target.max().z() < cell.z() + dims.z() + margin,
                        o + " " + target);
            }
        }
    }
}
