package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.box;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The images of cells, boxes and regions under a symmetry, and the copies of every op, checked cell by cell against the 1/16-block point map and brute force.
 */
class OpSymmetryTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final Pattern stone = new Pattern.Single(states.state("minecraft:stone"));

    /** Centres of both parities on each axis, near the origin and far into negative coordinates. */
    private static final int[][] CENTRES = {{0, 0}, {1, 1}, {7, -3}, {-40, 12}, {-41, 13}, {-1_000_001, 999_999}, {2_048, 2_048}};

    private static List<Symmetry> symmetries() {
        List<Symmetry> all = new ArrayList<>();
        for (Symmetry.Mode mode : Symmetry.Mode.values()) {
            if (mode == Symmetry.Mode.OFF) continue;
            for (int[] centre : CENTRES) {
                if (mode == Symmetry.Mode.ROTATE_4 && ((centre[0] ^ centre[1]) & 1) != 0) continue;
                all.add(new Symmetry(mode, centre[0], centre[1]));
            }
        }
        return all;
    }

    private static Set<BlockPos> cells(Region region) {
        Set<BlockPos> cells = new HashSet<>();
        Box b = region.bounds();
        for (int x = b.min().x(); x <= b.max().x(); x++) {
            for (int y = b.min().y(); y <= b.max().y(); y++) {
                for (int z = b.min().z(); z <= b.max().z(); z++) {
                    if (region.contains(x, y, z)) cells.add(new BlockPos(x, y, z));
                }
            }
        }
        return cells;
    }

    private static Set<BlockPos> images(Symmetry symmetry, Symmetry.Image image, Set<BlockPos> cells) {
        Set<BlockPos> images = new HashSet<>();
        for (BlockPos cell : cells) {
            images.add(new BlockPos((int) symmetry.cellX(image, cell.x(), cell.z()), cell.y(),
                    (int) symmetry.cellZ(image, cell.x(), cell.z())));
        }
        return images;
    }

    // =================================================================== cells

    /**
     * A cell's image is where its centre point lands (the point map is exact: it lands on a cell centre), the mirrors
     * are {@code x2 - 1 - x}, the quarter turn is {@code ((x2 + z2) / 2 - z - 1, (z2 - x2) / 2 + x)}, every image is
     * undone by its inverse, and the columns of a Rotate 4 centre of mixed parity would not map onto columns.
     */
    @Test
    void cellImagesFollowThePointMapExactly() {
        Random random = new Random(7);
        for (Symmetry symmetry : symmetries()) {
            for (Symmetry.Image image : symmetry.images()) {
                for (int i = 0; i < 200; i++) {
                    int x = random.nextInt(200) - 100, z = random.nextInt(200) - 100;
                    if (i < 40) {
                        x = symmetry.x2() / 2 + random.nextInt(5) - 2; // around the centre and the planes
                        z = symmetry.z2() / 2 + random.nextInt(5) - 2;
                    }
                    long px = symmetry.imageX(image, 16L * x + 8, 16L * z + 8), pz = symmetry.imageZ(image, 16L * x + 8, 16L * z + 8);
                    String what = symmetry + " " + image + " of " + x + "," + z;
                    assertEquals(8, Math.floorMod(px, 16), what + ": the point lands on a cell centre");
                    assertEquals(8, Math.floorMod(pz, 16), what);
                    long ix = symmetry.cellX(image, x, z), iz = symmetry.cellZ(image, x, z);
                    assertEquals(Math.floorDiv(px, 16), ix, what);
                    assertEquals(Math.floorDiv(pz, 16), iz, what);
                    switch (image) {
                        case IDENTITY -> assertEquals(new BlockPos(x, 0, z), new BlockPos((int) ix, 0, (int) iz), what);
                        case MIRROR_X -> assertEquals(new BlockPos(symmetry.x2() - 1 - x, 0, z), new BlockPos((int) ix, 0, (int) iz), what);
                        case MIRROR_Z -> assertEquals(new BlockPos(x, 0, symmetry.z2() - 1 - z), new BlockPos((int) ix, 0, (int) iz), what);
                        case HALF_TURN -> assertEquals(new BlockPos(symmetry.x2() - 1 - x, 0, symmetry.z2() - 1 - z),
                                new BlockPos((int) ix, 0, (int) iz), what);
                        case QUARTER_CW -> assertEquals(new BlockPos((symmetry.x2() + symmetry.z2()) / 2 - z - 1, 0,
                                (symmetry.z2() - symmetry.x2()) / 2 + x), new BlockPos((int) ix, 0, (int) iz), what);
                        case QUARTER_CCW -> assertEquals(new BlockPos((symmetry.x2() - symmetry.z2()) / 2 + z, 0,
                                (symmetry.x2() + symmetry.z2()) / 2 - x - 1), new BlockPos((int) ix, 0, (int) iz), what);
                    }
                    assertEquals(x, symmetry.cellX(image.inverse(), (int) ix, (int) iz), what + ": inverse");
                    assertEquals(z, symmetry.cellZ(image.inverse(), (int) ix, (int) iz), what + ": inverse");
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new Symmetry(Symmetry.Mode.ROTATE_4, 3, 4), "the mod-2 rule");
        // With a mixed-parity centre a quarter turn moves cell centres onto cell edges: not a cell map.
        Symmetry mixed = new Symmetry(Symmetry.Mode.ROTATE_2, 3, 4);
        assertEquals(0, Math.floorMod(mixed.imageX(Symmetry.Image.QUARTER_CW, 8, 8), 16));
    }

    @Test
    void offsetsAndFacingsTurnAsDirections() {
        assertEquals(new BlockPos(-3, 5, 7), Symmetry.imageOffset(Symmetry.Image.MIRROR_X, new BlockPos(3, 5, 7)));
        assertEquals(new BlockPos(3, 5, -7), Symmetry.imageOffset(Symmetry.Image.MIRROR_Z, new BlockPos(3, 5, 7)));
        assertEquals(new BlockPos(-3, 5, -7), Symmetry.imageOffset(Symmetry.Image.HALF_TURN, new BlockPos(3, 5, 7)));
        assertEquals(new BlockPos(-7, 5, 3), Symmetry.imageOffset(Symmetry.Image.QUARTER_CW, new BlockPos(3, 5, 7)));
        assertEquals(new BlockPos(7, 5, -3), Symmetry.imageOffset(Symmetry.Image.QUARTER_CCW, new BlockPos(3, 5, 7)));
        assertEquals(Facing.WEST, Symmetry.imageFacing(Symmetry.Image.MIRROR_X, Facing.EAST));
        assertEquals(Facing.EAST, Symmetry.imageFacing(Symmetry.Image.MIRROR_Z, Facing.EAST));
        assertEquals(Facing.SOUTH, Symmetry.imageFacing(Symmetry.Image.QUARTER_CW, Facing.EAST));
        assertEquals(Facing.EAST, Symmetry.imageFacing(Symmetry.Image.QUARTER_CW, Facing.NORTH));
        assertEquals(Facing.UP, Symmetry.imageFacing(Symmetry.Image.HALF_TURN, Facing.UP));
        assertEquals(Facing.DOWN, Symmetry.imageFacing(Symmetry.Image.QUARTER_CCW, Facing.DOWN));
        assertEquals(new Transform(0, Mirror.X), Symmetry.Image.MIRROR_X.transform());
        assertEquals(new Transform(0, Mirror.Z), Symmetry.Image.MIRROR_Z.transform());
        assertEquals(Transform.rotation(1), Symmetry.Image.QUARTER_CW.transform());
        assertEquals(Transform.rotation(3), Symmetry.Image.QUARTER_CCW.transform());
        assertEquals(Transform.rotation(2), Symmetry.Image.HALF_TURN.transform());
    }

    // =================================================================== regions

    /**
     * The image of a box, of every shape kind and facing, and of a cell set is exactly the set of its cells' images,
     * across section borders and in negative coordinates.
     */
    @Test
    void regionImagesAreTheirCellsImages() {
        Random random = new Random(11);
        List<Region> regions = new ArrayList<>();
        for (Box b : List.of(box(-3, -2, -5, 4, 3, 6), box(10, 60, -20, 21, 66, -9), box(-17, 0, 13, -1, 1, 33))) {
            regions.add(new Region.Cuboid(b));
            for (ShapeKind kind : ShapeKind.values()) {
                for (Facing facing : Facing.values()) regions.add(new Region.Shape(b, kind, facing));
            }
        }
        CellSet.Builder builder = CellSet.builder();
        for (int i = 0; i < 400; i++) builder.add(random.nextInt(40) - 20, random.nextInt(9) - 4, random.nextInt(40) - 20);
        regions.add(new Region.Cells(builder.build()));
        for (Region region : regions) {
            Set<BlockPos> cells = cells(region);
            for (Symmetry symmetry : symmetries()) {
                for (Symmetry.Image image : symmetry.images()) {
                    Region imaged = Regions.image(region, symmetry, image);
                    String what = region + " under " + image + " about " + symmetry;
                    assertEquals(images(symmetry, image, cells), cells(imaged), what);
                    assertEquals(region.getClass(), imaged.getClass(), what + ": the kind is kept");
                    assertEquals(symmetry.imageBox(image, region.bounds()), imaged.bounds(), what + ": bounds");
                    if (region instanceof Region.Shape shape) {
                        assertEquals(shape.kind(), ((Region.Shape) imaged).kind(), what);
                    }
                }
                assertSame(region, Regions.image(region, symmetry, Symmetry.Image.IDENTITY));
            }
        }
    }

    @Test
    void anImageBeyondTheCoordinateRangeIsRefused() {
        Symmetry far = new Symmetry(Symmetry.Mode.MIRROR_X, -(1 << 26), 0);
        Box box = box(2_140_000_000, 0, 0, 2_147_000_000, 3, 3);
        assertThrows(IllegalArgumentException.class, () -> far.imageBox(Symmetry.Image.MIRROR_X, box));
        assertThrows(IllegalArgumentException.class, () -> OpSymmetry.copies(new OpSpec.Fill(new Region.Cuboid(box), stone,
                CellMask.ANY, far)));
        assertThrows(IllegalArgumentException.class, () -> OpSymmetry.copies(new OpSpec.Paste(
                new SourceRef.Clipboard(UUID.randomUUID()), new BlockPos(2_147_000_000, 0, 0), Transform.IDENTITY,
                PasteOptions.DEFAULT, far)));
    }

    // =================================================================== copies

    /** Copies come in image order, the original first, each without symmetry; without a mode there is one. */
    @Test
    void copiesFollowTheImageOrderWithoutSymmetryOfTheirOwn() {
        Region.Cuboid region = new Region.Cuboid(box(2, 0, 2, 5, 3, 6));
        OpSpec.Fill plain = new OpSpec.Fill(region, stone, CellMask.ANY);
        assertEquals(List.of(new OpSymmetry.Copy(plain, Symmetry.Image.IDENTITY)), OpSymmetry.copies(plain));
        assertEquals(1, OpSymmetry.copyCount(plain));
        assertEquals(Symmetry.NONE, OpSymmetry.of(plain));
        Symmetry symmetry = new Symmetry(Symmetry.Mode.ROTATE_4, 0, 0);
        OpSpec.Fill fill = (OpSpec.Fill) OpSymmetry.withSymmetry(plain, symmetry);
        assertEquals(symmetry, OpSymmetry.of(fill));
        List<OpSymmetry.Copy> copies = OpSymmetry.copies(fill);
        assertEquals(symmetry.images(), copies.stream().map(OpSymmetry.Copy::image).toList());
        assertEquals(plain, copies.get(0).op());
        for (OpSymmetry.Copy copy : copies) {
            assertEquals(Symmetry.NONE, OpSymmetry.of(copy.op()));
            assertEquals(Regions.image(region, symmetry, copy.image()), OpRegions.region(copy.op()));
        }
        assertEquals(4, OpSymmetry.copyCount(fill));
        assertEquals(Symmetry.NONE, OpSymmetry.of(new OpSpec.ScatterCommit(UUID.randomUUID())));
        assertThrows(IllegalArgumentException.class,
                () -> OpSymmetry.withSymmetry(new OpSpec.ScatterCommit(UUID.randomUUID()), symmetry));
    }

    /** A region symmetric about its own plane or centre gives fewer copies; a cell set is never compared. */
    @Test
    void copiesEqualToAnEarlierOneAreDropped() {
        // A box straddling the plane x = 5 (x2 = 10) symmetrically: its mirror is itself.
        Region.Cuboid straddling = new Region.Cuboid(box(2, 0, 0, 7, 2, 4));
        OpSpec.Erase mirrored = new OpSpec.Erase(straddling, CellMask.ANY, new Symmetry(Symmetry.Mode.MIRROR_X, 10, 0));
        assertEquals(List.of(Symmetry.Image.IDENTITY), OpSymmetry.images(mirrored));
        assertEquals(1, OpSymmetry.copyCount(mirrored));
        OpSpec.Erase both = new OpSpec.Erase(straddling, CellMask.ANY, new Symmetry(Symmetry.Mode.MIRROR_XZ, 10, 0));
        assertEquals(List.of(Symmetry.Image.IDENTITY, Symmetry.Image.MIRROR_Z), OpSymmetry.images(both));
        // A square centred on the centre under Rotate 4 is itself; a cone facing east turns though.
        Box square = box(-2, 0, -2, 2, 5, 2);
        assertEquals(1, OpSymmetry.copyCount(new OpSpec.Fill(new Region.Cuboid(square), stone, CellMask.ANY,
                new Symmetry(Symmetry.Mode.ROTATE_4, 1, 1))));
        assertEquals(4, OpSymmetry.copyCount(new OpSpec.Fill(new Region.Shape(square, ShapeKind.CONE, Facing.EAST), stone,
                CellMask.ANY, new Symmetry(Symmetry.Mode.ROTATE_4, 1, 1))));
        assertEquals(1, OpSymmetry.copyCount(new OpSpec.Fill(new Region.Shape(square, ShapeKind.ELLIPSOID, Facing.EAST),
                stone, CellMask.ANY, new Symmetry(Symmetry.Mode.ROTATE_4, 1, 1))), "a sphere's facing means nothing");
        // A cell set symmetric about the plane keeps both copies.
        CellSet.Builder builder = CellSet.builder();
        for (int x = 2; x <= 7; x++) builder.add(x, 0, 0);
        OpSpec.Erase set = new OpSpec.Erase(new Region.Cells(builder.build()), CellMask.ANY,
                new Symmetry(Symmetry.Mode.MIRROR_X, 10, 0));
        assertEquals(2, OpSymmetry.copyCount(set));
        assertEquals(OpRegions.region(set), OpRegions.region(OpSymmetry.copies(set).get(1).op()));
    }

    /** A paste's copies compose the image after the transform and map the anchor cell. */
    @Test
    void pasteCopiesComposeTheImageAfterTheTransform() {
        SourceRef source = new SourceRef.Clipboard(UUID.randomUUID());
        Symmetry symmetry = new Symmetry(Symmetry.Mode.MIRROR_XZ, 21, -8);
        Transform t = new Transform(1, Mirror.X, true);
        OpSpec.Paste paste = new OpSpec.Paste(source, new BlockPos(30, 64, -2), t, PasteOptions.DEFAULT, symmetry);
        List<OpSymmetry.Copy> copies = OpSymmetry.copies(paste);
        assertEquals(4, copies.size());
        for (OpSymmetry.Copy copy : copies) {
            OpSpec.Paste c = (OpSpec.Paste) copy.op();
            assertEquals(source, c.src());
            assertEquals(PasteOptions.DEFAULT, c.o());
            assertEquals(t.compose(copy.image().transform()), c.t(), copy.image().toString());
            assertTrue(c.t().upsideDown(), "every copy is upside down as the original");
            assertEquals(new BlockPos((int) symmetry.cellX(copy.image(), 30, -2), 64, (int) symmetry.cellZ(copy.image(), 30, -2)),
                    c.origin(), copy.image().toString());
        }
        assertEquals(new BlockPos(-10, 64, -2), ((OpSpec.Paste) copies.get(1).op()).origin(), "mirror x: 21 - 1 - 30");
        assertEquals(new BlockPos(30, 64, -7), ((OpSpec.Paste) copies.get(2).op()).origin(), "mirror z: -8 - 1 + 2");
        // A paste on the centre under a half turn is still another placement (the source is turned).
        OpSpec.Paste centred = new OpSpec.Paste(source, new BlockPos(0, 0, 0), Transform.IDENTITY, PasteOptions.DEFAULT,
                new Symmetry(Symmetry.Mode.ROTATE_2, 1, 1));
        assertEquals(2, OpSymmetry.copyCount(centred));
    }

    /**
     * A move's copy lands the image region on the image of the original's destination, cell for cell: the cells
     * {@link Regions#moved} gives for the copy are the images of the original's moved cells.
     */
    @Test
    void moveCopiesLandOnTheImageOfTheDestination() {
        List<Region> regions = List.of(new Region.Cuboid(box(3, 0, -4, 9, 2, 1)),
                new Region.Shape(box(3, 0, -4, 9, 2, 1), ShapeKind.CONE, Facing.WEST),
                new Region.Shape(box(3, 0, -4, 9, 2, 1), ShapeKind.PYRAMID, Facing.SOUTH));
        CellSet.Builder builder = CellSet.builder();
        Random random = new Random(3);
        for (int i = 0; i < 60; i++) builder.add(3 + random.nextInt(7), random.nextInt(3), -4 + random.nextInt(6));
        regions = new ArrayList<>(regions);
        regions.add(new Region.Cells(builder.build()));
        List<Transform> transforms = List.of(Transform.IDENTITY, Transform.rotation(1), new Transform(2, Mirror.X),
                new Transform(3, Mirror.Z), new Transform(0, Mirror.X), Transform.UPSIDE_DOWN,
                new Transform(1, Mirror.Z, true));
        for (Region region : regions) {
            for (Symmetry symmetry : symmetries()) {
                for (Transform t : transforms) {
                    for (BlockPos offset : List.of(new BlockPos(5, 1, -2), new BlockPos(-30, 0, 4))) {
                        OpSpec.Move move = new OpSpec.Move(region, offset, t, stone, EntityFilter.NONE, symmetry);
                        Box from = region.bounds();
                        Box destination = CopySupport.boxAt((long) from.min().x() + offset.x(), from.min().y(),
                                (long) from.min().z() + offset.z(), t.size(from.sizeX(), from.sizeY(), from.sizeZ()));
                        Set<BlockPos> movedCells = cells(Regions.moved(region, from, destination.min().offset(0, offset.y(), 0), t));
                        for (OpSymmetry.Copy copy : OpSymmetry.copies(move)) {
                            OpSpec.Move c = (OpSpec.Move) copy.op();
                            String what = region + " " + symmetry + " " + copy.image() + " " + t + " by " + offset;
                            assertEquals(cells(Regions.image(region, symmetry, copy.image())), cells(c.region()), what);
                            Box copyFrom = c.region().bounds();
                            Box copyDestination = CopySupport.boxAt((long) copyFrom.min().x() + c.offset().x(),
                                    copyFrom.min().y() + c.offset().y(), (long) copyFrom.min().z() + c.offset().z(),
                                    c.t().size(copyFrom.sizeX(), copyFrom.sizeY(), copyFrom.sizeZ()));
                            assertEquals(symmetry.imageBox(copy.image(), destination).offset(0, offset.y(), 0), copyDestination,
                                    what + ": destination");
                            assertEquals(images(symmetry, copy.image(), movedCells),
                                    cells(Regions.moved(c.region(), copyFrom, copyDestination.min(), c.t())), what + ": moved cells");
                            assertEquals(offset.y(), c.offset().y(), what);
                            assertEquals(stone, c.leave());
                        }
                    }
                }
            }
        }
    }

    @Test
    void stackCopiesStepAlongTheImageOfTheStep() {
        Region.Shape cone = new Region.Shape(box(0, 0, 0, 4, 6, 4), ShapeKind.CONE, Facing.NORTH);
        Symmetry symmetry = new Symmetry(Symmetry.Mode.ROTATE_4, 12, 12);
        OpSpec.Stack stack = new OpSpec.Stack(cone, 5, 1, -2, 3, EntityFilter.ALL, symmetry);
        List<OpSymmetry.Copy> copies = OpSymmetry.copies(stack);
        assertEquals(4, copies.size());
        OpSpec.Stack turned = (OpSpec.Stack) copies.get(1).op();
        assertEquals(Symmetry.Image.QUARTER_CW, copies.get(1).image());
        assertEquals(new Region.Shape(box(7, 0, 0, 11, 6, 4), ShapeKind.CONE, Facing.EAST), turned.region());
        assertEquals(2, turned.dx(), "(5, -2) turned clockwise is (2, 5)");
        assertEquals(1, turned.dy());
        assertEquals(5, turned.dz());
        assertEquals(3, turned.count());
        assertEquals(EntityFilter.ALL, turned.entities());
        OpSpec.Stack flipped = new OpSpec.Stack(cone, 5, 1, -2, 3, EntityFilter.ALL, symmetry, PasteOptions.Into.AIR, true);
        for (OpSymmetry.Copy copy : OpSymmetry.copies(flipped)) {
            assertTrue(((OpSpec.Stack) copy.op()).upsideDown(), "every copy is upside down as the original");
            assertEquals(PasteOptions.Into.AIR, ((OpSpec.Stack) copy.op()).into());
        }
        assertTrue(((OpSpec.Stack) OpSymmetry.withSymmetry(flipped, Symmetry.NONE)).upsideDown());
        for (OpSymmetry.Copy copy : copies) {
            Set<BlockPos> expected = new HashSet<>();
            for (BlockPos cell : images(symmetry, copy.image(), cells(cone))) expected.add(cell.offset(
                    ((OpSpec.Stack) copy.op()).dx(), 1, ((OpSpec.Stack) copy.op()).dz()));
            Set<BlockPos> original = new HashSet<>();
            for (BlockPos cell : cells(cone)) original.add(cell.offset(5, 1, -2));
            assertEquals(images(symmetry, copy.image(), original), expected, copy.image().toString());
        }
    }

    /** Volumes and source volumes count every copy; the region a copy is on is replaced with its symmetry kept. */
    @Test
    void volumesCountEveryCopy() {
        Region.Cuboid region = new Region.Cuboid(box(0, 0, 0, 9, 9, 9));
        Symmetry symmetry = new Symmetry(Symmetry.Mode.MIRROR_XZ, 41, 41);
        assertEquals(4_000, OpCompiler.targetVolume(new OpSpec.Fill(region, stone, CellMask.ANY, symmetry), null));
        assertEquals(1_000, OpCompiler.targetVolume(new OpSpec.Fill(region, stone, CellMask.ANY), null));
        OpSpec.Move move = new OpSpec.Move(region, new BlockPos(20, 0, 0), Transform.IDENTITY, stone, EntityFilter.NONE, symmetry);
        assertEquals(8_000, OpCompiler.targetVolume(move, null));
        assertEquals(4_000, OpCompiler.sourceVolume(move));
        OpSpec.Stack stack = new OpSpec.Stack(region, 0, 10, 0, 3, EntityFilter.NONE, new Symmetry(Symmetry.Mode.ROTATE_2, 41, 41));
        assertEquals(6_000, OpCompiler.targetVolume(stack, null));
        assertEquals(2_000, OpCompiler.sourceVolume(stack));
        OpSpec.Paste paste = new OpSpec.Paste(new SourceRef.Clipboard(UUID.randomUUID()), BlockPos.ORIGIN, Transform.IDENTITY,
                PasteOptions.DEFAULT, symmetry);
        assertEquals(4 * 60, OpCompiler.targetVolume(paste, new BlockPos(3, 4, 5)));
        // A straddling box's mirror is itself: one copy.
        OpSpec.Fill straddling = new OpSpec.Fill(new Region.Cuboid(box(16, 0, 0, 24, 0, 0)), stone, CellMask.ANY,
                new Symmetry(Symmetry.Mode.MIRROR_X, 41, 0));
        assertEquals(9, OpCompiler.targetVolume(straddling, null));
        assertTrue(OpCompiler.targetVolume(new OpSpec.Fill(new Region.Cuboid(box(0, 0, 0, 1_000_000_000, 2_000, 1_000_000_000)),
                stone, CellMask.ANY, symmetry), null) == Long.MAX_VALUE, "saturates");
        OpSpec replaced = OpRegions.withRegion(move, new Region.Cuboid(box(0, 0, 0, 1, 1, 1)));
        assertEquals(symmetry, OpSymmetry.of(replaced));
    }
}
