package dev.sculptory.core.scatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.scatter.ScatterArea.Stamp;
import dev.sculptory.core.scatter.ScatterSettings.Density;
import dev.sculptory.core.scatter.ScatterSettings.Filters;
import dev.sculptory.core.scatter.ScatterSettings.Fit;
import dev.sculptory.core.scatter.ScatterSettings.Transforms;
import dev.sculptory.core.scatter.ScatterSettings.Variant;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScatterSettingsTest {
    private static final ScatterArea AREA = new ScatterArea.Stamps(List.of(Stamp.paint(0, 0, 4)));

    private static ScatterSettings settings(int spacing, List<Variant> variants) {
        return new ScatterSettings(AREA, new Density.Fraction(1), spacing, Filters.NONE, Fit.DEFAULT, variants,
                Transforms.NONE, 1);
    }

    @Test
    void validatesRanges() {
        assertThrows(IllegalArgumentException.class, () -> settings(-1, List.of(new Variant(0, 1))));
        assertThrows(IllegalArgumentException.class, () -> settings(65, List.of(new Variant(0, 1))));
        settings(64, List.of(new Variant(0, 1)));
        assertThrows(IllegalArgumentException.class, () -> settings(0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> settings(0, Collections.nCopies(65, new Variant(0, 1))));
        assertEquals(64 * 1000, settings(0, Collections.nCopies(64, new Variant(0, 1000))).totalWeight());

        assertThrows(IllegalArgumentException.class, () -> new Variant(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Variant(0, 1001));
        assertThrows(IllegalArgumentException.class, () -> new Variant(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Variant(0, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Variant(0, 1, 16));

        assertThrows(IllegalArgumentException.class, () -> new Density.Fraction(-0.01));
        assertThrows(IllegalArgumentException.class, () -> new Density.Fraction(1.01));
        assertThrows(IllegalArgumentException.class, () -> new Density.Fraction(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new Density.Count(0));
        assertThrows(IllegalArgumentException.class, () -> new Density.Count(ScatterSettings.MAX_PLACEMENTS + 1));
        assertEquals(7, new ScatterSettings(AREA, new Density.Count(7), 0, Filters.NONE, Fit.DEFAULT,
                List.of(new Variant(0, 1)), Transforms.NONE, 1).countLimit());

        assertEquals(new Fit(false, 0.5), Fit.DEFAULT);
        assertThrows(IllegalArgumentException.class, () -> new Fit(false, -0.01));
        assertThrows(IllegalArgumentException.class, () -> new Fit(false, 1.01));
        assertThrows(IllegalArgumentException.class, () -> new Fit(true, Double.NaN));
        assertEquals(new Fit(true, 0), Fit.DEFAULT.withAllowInFluid(true).withMinSupportFraction(0));

        assertThrows(IllegalArgumentException.class, () -> new Transforms(0, true));
        assertThrows(IllegalArgumentException.class, () -> Filters.NONE.withElevation(5, 4));
        assertThrows(IllegalArgumentException.class, () -> Filters.NONE.withSlope(-1, 4));
        assertThrows(IllegalArgumentException.class, () -> Filters.NONE.withSlope(3, 2));
    }

    @Test
    void validatesAreas() {
        assertThrows(IllegalArgumentException.class, () -> Stamp.paint(0, 0, 65));
        assertThrows(IllegalArgumentException.class, () -> Stamp.paint(0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> Stamp.paint(1 << 25, 0, 1));
        Stamp.paint(0, 0, 64);
        assertThrows(IllegalArgumentException.class, () -> new ScatterArea.Stamps(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ScatterArea.Stamps(List.of(Stamp.erase(0, 0, 3))));
        assertThrows(IllegalArgumentException.class,
                () -> new ScatterArea.Stamps(Collections.nCopies(ScatterArea.MAX_STAMPS + 1, Stamp.paint(0, 0, 1))));
        // Bounding rectangle: 1024 × 1024 is the most.
        new ScatterArea.Stamps(List.of(Stamp.paint(0, 0, 64), Stamp.paint(1023 - 128, 1023 - 128, 64)));
        assertThrows(IllegalArgumentException.class,
                () -> new ScatterArea.Stamps(List.of(Stamp.paint(0, 0, 64), Stamp.paint(1024 - 128, 1024 - 128, 64))));
        new ScatterArea.Region(Box.of(new BlockPos(0, 0, 0), new BlockPos(1023, 5, 1023)));
        new ScatterArea.Region(Box.of(new BlockPos(0, 0, 0), new BlockPos(2047, 5, 511)));
        assertThrows(IllegalArgumentException.class,
                () -> new ScatterArea.Region(Box.of(new BlockPos(0, 0, 0), new BlockPos(2048, 5, 511))));
        assertThrows(IllegalArgumentException.class,
                () -> new ScatterArea.Region(Box.of(new BlockPos(1 << 26, 0, 0), new BlockPos((1 << 26) + 5, 5, 5))));
    }

    @Test
    void transformListsAreOrderedAndRestrictedPerVariant() {
        assertEquals(List.of(Transform.IDENTITY), Transforms.NONE.list());
        assertEquals(8, Transforms.ALL.list().size());
        assertEquals(List.of(new Transform(1, Mirror.NONE), new Transform(3, Mirror.NONE),
                new Transform(1, Mirror.X), new Transform(3, Mirror.X)), new Transforms(0b1010, true).list());
        Transforms turns = new Transforms(0b0110, false);
        assertEquals(List.of(new Transform(2, Mirror.NONE)), turns.forVariant(new Variant(0, 1, 0b1100)));
        assertEquals(List.of(Transform.IDENTITY), turns.forVariant(new Variant(0, 1, 0b0001)), "the variant's own turns win");
        assertEquals(turns.list(), turns.forVariant(new Variant(0, 1)));
        assertEquals(0b1011, Variant.turnMask(List.of(0, 1, 3)));
        assertThrows(IllegalArgumentException.class, () -> Variant.turnMask(List.of(4)));
    }

    @Test
    void coveringMapsATransformListOntoTurnsAndMirror() {
        assertEquals(Transforms.NONE, Transforms.covering(List.of()));
        assertEquals(new Transforms(0b0101, false),
                Transforms.covering(List.of(Transform.IDENTITY, new Transform(2, Mirror.NONE))));
        // (1, Z) is (3, X).
        assertEquals(new Transforms(0b1001, true),
                Transforms.covering(List.of(Transform.IDENTITY, new Transform(1, Mirror.Z))));
        assertEquals(Transforms.ALL, Transforms.covering(Transform.all()));
    }

    @Test
    void surfaceMasksSplitIntoReportableFilters() {
        assertEquals(Filters.NONE, Filters.of(SurfaceMask.ANY));
        CellMask grass = new CellMask.Blocks(List.of(new NamespacedId("minecraft:grass_block")));
        CellMask dirt = new CellMask.Tag(new NamespacedId("minecraft:dirt"));
        SurfaceMask notHigh = new SurfaceMask.Not(new SurfaceMask.Elevation(100, 120));
        Filters split = Filters.of(new SurfaceMask.And(List.of(
                new SurfaceMask.Elevation(60, 150),
                new SurfaceMask.And(List.of(new SurfaceMask.Slope(1, 5), new SurfaceMask.SurfaceBlocks(grass))),
                new SurfaceMask.Slope(0, 3),
                new SurfaceMask.Elevation(70, 200),
                new SurfaceMask.SurfaceBlocks(dirt),
                notHigh)));
        assertEquals(70, split.minY());
        assertEquals(150, split.maxY());
        assertEquals(1, split.minSlope());
        assertEquals(3, split.maxSlope());
        assertEquals(new CellMask.And(List.of(grass, dirt)), split.substrate());
        assertEquals(notHigh, split.extra());
        assertTrue(split.needsSlope());

        // An empty intersection stays exact: the second range goes to extra.
        Filters disjoint = Filters.of(new SurfaceMask.And(List.of(new SurfaceMask.Elevation(0, 10),
                new SurfaceMask.Elevation(20, 30))));
        assertEquals(0, disjoint.minY());
        assertEquals(10, disjoint.maxY());
        assertEquals(new SurfaceMask.Elevation(20, 30), disjoint.extra());

        Filters top = Filters.of(notHigh);
        assertEquals(Filters.NONE.withExtra(notHigh), top);
        assertFalse(Filters.of(new SurfaceMask.Elevation(1, 2)).needsSlope());

        // Two maximally deep substrate masks cannot be and-ed into one cell mask: the second stays in extra.
        CellMask deep = grass;
        for (int i = 1; i < CellMask.MAX_DEPTH; i++) deep = new CellMask.Not(deep);
        CellMask deepDirt = dirt;
        for (int i = 1; i < CellMask.MAX_DEPTH; i++) deepDirt = new CellMask.Not(deepDirt);
        Filters kept = Filters.of(new SurfaceMask.And(List.of(new SurfaceMask.SurfaceBlocks(deep),
                new SurfaceMask.SurfaceBlocks(deepDirt))));
        assertEquals(deep, kept.substrate());
        assertEquals(new SurfaceMask.SurfaceBlocks(deepDirt), kept.extra());
    }

    @Test
    void variantListIsCopied() {
        List<Variant> variants = new ArrayList<>(List.of(new Variant(0, 1)));
        ScatterSettings s = settings(0, variants);
        variants.add(new Variant(1, 1));
        assertEquals(1, s.variants().size());
    }
}
