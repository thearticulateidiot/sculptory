package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.box;
import static dev.sculptory.core.edit.CopyTestSupport.grow;
import static dev.sculptory.core.edit.CopyTestSupport.runAndUndo;
import static dev.sculptory.core.edit.CopyTestSupport.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CopyTestSupport.Cell;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.state.OrientedStates;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pastes and moves with a free orientation ({@code PasteProgram} and {@code MoveProgram}'s resampled paths) against a
 * reference model built from {@link Orientation#inverseCell} and {@link OrientedStates}, with exact undo, and their
 * symmetric copies as exact images.
 */
class FreeCopyTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final Map<SourceRef, SourceBlocks> sources = new HashMap<>();
    private final CompileContext context = CopyTestSupport.context(states, sources);
    private final SourceRef ref = new SourceRef.Clipboard(UUID.randomUUID());
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");

    /** A clipboard of {@code size} with seeded directional blocks (stairs, logs, observers) and some holes. */
    private Clipboard clipboard(BlockPos size, long seed) {
        Random random = new Random(seed);
        int[] palette = {stone, states.state("minecraft:oak_log[axis=y]"), states.state("minecraft:oak_log[axis=x]"),
                states.state("minecraft:oak_stairs[facing=north]"), states.state("minecraft:oak_stairs[facing=east,half=top]"),
                states.state("testmod:widget[facing=up]"), states.state("minecraft:dirt")};
        Clipboard.Builder builder = Clipboard.builder(states, size).anchor(new BlockPos(size.x() / 2, 0, size.z() / 2));
        for (int x = 0; x < size.x(); x++) {
            for (int y = 0; y < size.y(); y++) {
                for (int z = 0; z < size.z(); z++) {
                    if (random.nextInt(6) == 0) continue;
                    builder.set(x, y, z, palette[random.nextInt(palette.length)]);
                }
            }
        }
        return builder.build();
    }

    /** The cells a paste of {@code clipboard} with {@code o} at {@code origin} writes, by the reference model. */
    private Map<BlockPos, Integer> expectedPaste(Clipboard clipboard, Orientation o, BlockPos origin) {
        BlockPos size = clipboard.size();
        Box target = PasteGeometry.pasteTarget(size, clipboard.anchor(), o, origin);
        OrientedStates oriented = new OrientedStates(states, o);
        Map<BlockPos, Integer> out = new HashMap<>();
        for (int x = 0; x < target.sizeX(); x++) {
            for (int y = 0; y < target.sizeY(); y++) {
                for (int z = 0; z < target.sizeZ(); z++) {
                    BlockPos from = o.inverseCell(x, y, z, size.x(), size.y(), size.z());
                    if (from == null) continue;
                    int state = clipboard.toSource().cells().get(from.x(), from.y(), from.z());
                    if (state < 0 || state == air) continue;
                    out.put(target.min().offset(x, y, z), oriented.map(state));
                }
            }
        }
        return out;
    }

    private EditProgram paste(Clipboard clipboard, BlockPos origin, Orientation o, Symmetry symmetry) {
        sources.put(ref, clipboard.toSource());
        return OpCompiler.compile(new OpSpec.Paste(ref, origin, o, PasteOptions.DEFAULT, symmetry), context);
    }

    @Test
    void aFreePasteWritesTheResampledCellsAndUndoesExactly() {
        Random random = new Random(41);
        for (int i = 0; i < 12; i++) {
            BlockPos size = new BlockPos(2 + random.nextInt(7), 1 + random.nextInt(6), 2 + random.nextInt(7));
            Clipboard clipboard = clipboard(size, i);
            Orientation o = Orientation.free(Transform.allWithFlips().get(random.nextInt(16)), random.nextInt(36_000),
                    random.nextInt(36_000), random.nextInt(36_000));
            BlockPos origin = new BlockPos(random.nextInt(40) - 20, 60, random.nextInt(40) - 20);
            FakeWorld world = new FakeWorld(states);
            Box area = grow(PasteGeometry.pasteTarget(size, clipboard.anchor(), o, origin), 2);
            world.fill(area, states.state("minecraft:dirt"));
            Map<BlockPos, Cell> before = snapshot(world, area);
            Map<BlockPos, Integer> expected = expectedPaste(clipboard, o, origin);
            runAndUndo(paste(clipboard, origin, o, Symmetry.NONE), world, area, o.toString());
            // runAndUndo leaves the paste redone: check it.
            for (Map.Entry<BlockPos, Cell> cell : before.entrySet()) {
                BlockPos p = cell.getKey();
                int want = expected.getOrDefault(p, cell.getValue().state());
                assertEquals(states.format(want), states.format(world.get(p.x(), p.y(), p.z())), o + " at " + p);
            }
        }
    }

    @Test
    void aQuarterPitchPasteTurnsLogsAndStairsExactly() {
        Clipboard column = Clipboard.builder(states, new BlockPos(1, 3, 1)).anchor(BlockPos.ORIGIN)
                .set(0, 0, 0, states.state("minecraft:oak_log[axis=y]"))
                .set(0, 1, 0, states.state("minecraft:oak_stairs[facing=north,half=bottom]"))
                .set(0, 2, 0, stone).build();
        Orientation o = Orientation.free(Transform.IDENTITY, 0, Orientation.QUARTER_TURN, 0);
        FakeWorld world = new FakeWorld(states);
        FakeExecutor.run(paste(column, new BlockPos(0, 64, 0), o, Symmetry.NONE), world);
        // Up goes south: the column lies along z, its bottom (the anchor) at the origin.
        assertEquals("minecraft:oak_log[axis=z]", states.format(world.get(0, 64, 0)));
        assertEquals(states.format(states.state("minecraft:oak_stairs[facing=north,half=top]")),
                states.format(world.get(0, 64, 1)));
        assertEquals(stone, world.get(0, 64, 2));
    }

    @Test
    void symmetricCopiesOfAFreePasteAreExactImagesWhateverTheSizes() {
        for (Symmetry symmetry : List.of(new Symmetry(Symmetry.Mode.MIRROR_X, 1, 0),
                new Symmetry(Symmetry.Mode.MIRROR_Z, 0, 0), new Symmetry(Symmetry.Mode.ROTATE_4, 2, 2),
                new Symmetry(Symmetry.Mode.MIRROR_XZ, 1, 3))) {
            for (BlockPos size : List.of(new BlockPos(4, 3, 6), new BlockPos(5, 2, 3))) {
                Clipboard clipboard = clipboard(size, size.x());
                Orientation o = Orientation.free(Transform.IDENTITY, 3_000, 1_500, 0);
                BlockPos origin = new BlockPos(20, 64, 30);
                FakeWorld world = new FakeWorld(states);
                FakeExecutor.run(paste(clipboard, origin, o, symmetry), world);
                Map<BlockPos, Integer> original = expectedPaste(clipboard, o, origin);
                assertTrue(original.size() > 5);
                for (Symmetry.Image image : symmetry.images()) {
                    if (image == Symmetry.Image.IDENTITY) continue;
                    for (Map.Entry<BlockPos, Integer> cell : original.entrySet()) {
                        BlockPos p = cell.getKey();
                        int x = (int) symmetry.cellX(image, p.x(), p.z()), z = (int) symmetry.cellZ(image, p.x(), p.z());
                        int want = image.transform().applyToState(states, cell.getValue());
                        // Overlapping copies: the original (first) wins where they meet.
                        if (original.containsKey(new BlockPos(x, p.y(), z))) continue;
                        assertEquals(states.format(want), states.format(world.get(x, p.y(), z)),
                                symmetry + " " + image + " " + size + " at " + p);
                    }
                }
            }
        }
    }

    @Test
    void aFreeMoveTurnsTheRegionInPlaceAndUndoesExactly() {
        Random random = new Random(7);
        for (int i = 0; i < 10; i++) {
            Box from = box(0, 64, 0, 2 + random.nextInt(6), 64 + random.nextInt(5), 2 + random.nextInt(6));
            Orientation o = Orientation.free(Transform.IDENTITY, random.nextInt(36_000), random.nextInt(36_000),
                    random.nextInt(36_000));
            BlockPos offset = new BlockPos(random.nextInt(9) - 4, random.nextInt(3), random.nextInt(9) - 4);
            FakeWorld world = new FakeWorld(states);
            Clipboard content = clipboard(new BlockPos(from.sizeX(), from.sizeY(), from.sizeZ()), i);
            for (int x = 0; x < from.sizeX(); x++) {
                for (int y = 0; y < from.sizeY(); y++) {
                    for (int z = 0; z < from.sizeZ(); z++) {
                        int state = content.toSource().cells().get(x, y, z);
                        world.set(x, 64 + y, z, state < 0 ? air : state);
                    }
                }
            }
            Orientation.Sampler sampler = o.sampler(from.sizeX(), from.sizeY(), from.sizeZ());
            BlockPos destMin = from.min().add(sampler.offset()).add(offset);
            Box area = grow(CopySupport.union(from, CopySupport.boxAt(destMin.x(), destMin.y(), destMin.z(),
                    sampler.size())), 1);
            Map<BlockPos, Cell> before = snapshot(world, area);
            // Reference: vacate the box, then write every result cell from its source cell.
            Map<BlockPos, Integer> expected = new HashMap<>();
            for (BlockPos p : before.keySet()) {
                expected.put(p, from.contains(p.x(), p.y(), p.z()) ? air : before.get(p).state());
            }
            OrientedStates oriented = new OrientedStates(states, o);
            int[] cell = new int[3];
            for (int x = 0; x < sampler.size().x(); x++) {
                for (int y = 0; y < sampler.size().y(); y++) {
                    for (int z = 0; z < sampler.size().z(); z++) {
                        if (!sampler.source(x, y, z, cell)) continue;
                        int state = before.get(from.min().offset(cell[0], cell[1], cell[2])).state();
                        expected.put(destMin.offset(x, y, z), oriented.map(state));
                    }
                }
            }
            OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(from), offset, o, new Pattern.Single(air),
                    EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING);
            runAndUndo(OpCompiler.compile(move, context), world, area, o.toString());
            for (Map.Entry<BlockPos, Integer> want : expected.entrySet()) {
                BlockPos p = want.getKey();
                assertEquals(states.format(want.getValue()), states.format(world.get(p.x(), p.y(), p.z())), o + " at " + p);
            }
        }
    }

    @Test
    void aFreeMoveOfAShapeMovesOnlyItsCellsAndItsRegionBecomesACellSet() {
        Region shape = new Region.Shape(box(0, 64, 0, 6, 70, 6), dev.sculptory.core.region.ShapeKind.ELLIPSOID,
                dev.sculptory.core.region.Facing.UP);
        Orientation o = Orientation.free(Transform.IDENTITY, 2_500, 0, 4_000);
        Box from = shape.bounds();
        Orientation.Sampler sampler = o.sampler(from.sizeX(), from.sizeY(), from.sizeZ());
        Region moved = Regions.moved(shape, from, from.min().add(sampler.offset()), o);
        assertInstanceOf(Region.Cells.class, moved);
        FakeWorld world = new FakeWorld(states);
        world.fill(grow(from, 3), states.state("minecraft:dirt"));
        world.fill(from, stone);
        OpSpec.Move move = new OpSpec.Move(shape, BlockPos.ORIGIN, o, new Pattern.Single(air), EntityFilter.NONE,
                Symmetry.NONE, PasteOptions.Into.EVERYTHING);
        runAndUndo(OpCompiler.compile(move, context), world, grow(from, 3), "shape");
        // Every moved cell holds stone; box cells outside the shape and outside the moved cells are untouched dirt.
        for (int x = from.min().x(); x <= from.max().x(); x++) {
            for (int y = from.min().y(); y <= from.max().y(); y++) {
                for (int z = from.min().z(); z <= from.max().z(); z++) {
                    if (moved.contains(x, y, z)) {
                        assertEquals(stone, world.get(x, y, z));
                    } else if (shape.contains(x, y, z)) {
                        assertEquals(air, world.get(x, y, z));
                    } else {
                        assertEquals(stone, world.get(x, y, z), "box cells outside the shape stay");
                    }
                }
            }
        }
    }

    @Test
    void symmetricCopiesOfAFreeMoveAreExactImages() {
        Symmetry symmetry = new Symmetry(Symmetry.Mode.MIRROR_X, 21, 0); // the plane x = 10.5
        Box from = box(0, 64, 0, 3, 66, 5); // even sizes
        FakeWorld world = new FakeWorld(states);
        Clipboard content = clipboard(new BlockPos(4, 3, 6), 99);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 6; z++) {
                    int state = content.toSource().cells().get(x, y, z);
                    state = state < 0 ? air : state;
                    world.set(x, 64 + y, z, state);
                    // The mirrored copy of the content across x = 10.5.
                    world.set(20 - x, 64 + y, z, new Transform(0, dev.sculptory.core.transform.Mirror.X)
                            .applyToState(states, state));
                }
            }
        }
        Orientation o = Orientation.free(Transform.IDENTITY, 3_500, 0, 2_000);
        OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(from), new BlockPos(1, 0, 0), o, new Pattern.Single(air),
                EntityFilter.NONE, symmetry, PasteOptions.Into.EVERYTHING);
        FakeExecutor.run(OpCompiler.compile(move, context), world);
        for (int x = -6; x <= 10; x++) {
            for (int y = 60; y <= 72; y++) {
                for (int z = -6; z <= 12; z++) {
                    int mirrored = new Transform(0, dev.sculptory.core.transform.Mirror.X)
                            .applyToState(states, world.get(x, y, z));
                    assertEquals(states.format(mirrored), states.format(world.get(20 - x, y, z)), "at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void theCopiesOfFreePastesAndMultiPastesNameTheComposedOrientation() {
        Orientation o = Orientation.free(Transform.IDENTITY, 3_000, 1_000, 500);
        Symmetry symmetry = new Symmetry(Symmetry.Mode.ROTATE_4, 0, 0);
        OpSpec.Paste paste = new OpSpec.Paste(ref, new BlockPos(3, 64, 1), o, PasteOptions.DEFAULT, symmetry);
        List<OpSymmetry.Copy> copies = OpSymmetry.copies(paste);
        assertEquals(4, copies.size());
        for (OpSymmetry.Copy copy : copies) {
            assertEquals(o.then(copy.image().transform()), ((OpSpec.Paste) copy.op()).orientation());
        }
        OpSpec.PasteMany many = new OpSpec.PasteMany(ref, List.of(new OpSpec.PasteMany.Placement(BlockPos.ORIGIN, o),
                new OpSpec.PasteMany.Placement(new BlockPos(9, 64, 9), Orientation.IDENTITY)), PasteOptions.DEFAULT,
                new Symmetry(Symmetry.Mode.MIRROR_X, 1, 0));
        OpSpec.PasteMany mirrored = (OpSpec.PasteMany) OpSymmetry.copies(many).get(1).op();
        Transform mirror = Symmetry.Image.MIRROR_X.transform();
        assertEquals(o.then(mirror), mirrored.placements().get(0).orientation());
        assertEquals(Orientation.of(mirror), mirrored.placements().get(1).orientation());
    }

    @Test
    void aMultiPastePlacementsCopyCompilesAsTheExactImage() {
        Clipboard clipboard = clipboard(new BlockPos(4, 3, 6), 7);
        sources.put(ref, clipboard.toSource());
        Symmetry symmetry = new Symmetry(Symmetry.Mode.MIRROR_X, 61, 0); // the plane x = 30.5
        OpSpec.PasteMany.Placement placement = new OpSpec.PasteMany.Placement(new BlockPos(10, 64, 4),
                Orientation.free(Transform.IDENTITY, 2_500, 1_000, 0));
        RegionProgram.Height height = new RegionProgram.Height(-64, 319);
        FakeWorld world = new FakeWorld(states);
        for (Symmetry.Image image : List.of(Symmetry.Image.IDENTITY, Symmetry.Image.MIRROR_X)) {
            FakeExecutor.run(PasteProgram.compilePlacement(ref, placement, PasteOptions.DEFAULT, symmetry, image,
                    clipboard.toSource(), states, height), world);
        }
        Map<BlockPos, Integer> original = expectedPaste(clipboard, placement.orientation(), placement.origin());
        assertTrue(original.size() > 10);
        Transform mirror = Symmetry.Image.MIRROR_X.transform();
        for (Map.Entry<BlockPos, Integer> cell : original.entrySet()) {
            BlockPos p = cell.getKey();
            assertEquals(states.format(cell.getValue()), states.format(world.get(p.x(), p.y(), p.z())));
            assertEquals(states.format(mirror.applyToState(states, cell.getValue())),
                    states.format(world.get(60 - p.x(), p.y(), p.z())), "the image of " + p);
        }
    }

    /** The landing cells by brute force: every cell of the turned box mapped back. */
    private static java.util.Set<BlockPos> bruteLanding(Region region, Box pivot, BlockPos destinationMin,
                                                        Orientation o, int minY, int maxY) {
        Orientation.Sampler sampler = o.sampler(pivot.sizeX(), pivot.sizeY(), pivot.sizeZ());
        java.util.Set<BlockPos> out = new java.util.HashSet<>();
        int[] cell = new int[3];
        for (int x = 0; x < sampler.size().x(); x++) {
            for (int y = 0; y < sampler.size().y(); y++) {
                for (int z = 0; z < sampler.size().z(); z++) {
                    if (!sampler.source(x, y, z, cell)) continue;
                    int sx = pivot.min().x() + cell[0], sy = pivot.min().y() + cell[1], sz = pivot.min().z() + cell[2];
                    if (sy < minY || sy > maxY || !region.contains(sx, sy, sz)) continue;
                    out.add(destinationMin.offset(x, y, z));
                }
            }
        }
        return out;
    }

    @Test
    void theLandingCellsFollowTheRegionsCellsNotTheTurnedBox() {
        Random random = new Random(19);
        for (int i = 0; i < 48; i++) {
            // Cell sets, shapes and boxes, some at negative coordinates, some reaching below the height limit.
            int x0 = i % 4 == 1 ? -57 : 0, z0 = i % 4 == 2 ? -33 : 3;
            dev.sculptory.core.region.CellSet.Builder cells = dev.sculptory.core.region.CellSet.builder();
            for (int k = 0; k < 40; k++) cells.add(x0 + random.nextInt(40), 58 + random.nextInt(12), z0 + random.nextInt(40));
            Region region = switch (i % 3) {
                case 0 -> new Region.Cells(cells.build());
                case 1 -> new Region.Shape(box(x0, 58, z0, x0 + 9 + i / 3, 66, z0 + 7),
                        dev.sculptory.core.region.ShapeKind.ELLIPSOID, dev.sculptory.core.region.Facing.UP);
                default -> new Region.Cuboid(box(x0, 60, z0, x0 + 5, 63, z0 + 4 + i % 5));
            };
            Box pivot = region.bounds();
            // Free angles after every mirror, quarter turn and flip; some quarter turns of pitch and roll.
            Orientation o = Orientation.free(Transform.allWithFlips().get(i % 16),
                    i % 7 == 0 ? Orientation.QUARTER_TURN : random.nextInt(36_000),
                    i % 5 == 0 ? Orientation.QUARTER_TURN : random.nextInt(36_000), random.nextInt(36_000));
            if (o.isExact()) continue;
            Orientation.Sampler sampler = o.sampler(pivot.sizeX(), pivot.sizeY(), pivot.sizeZ());
            BlockPos destination = new BlockPos(100, 70, -20);
            Region moved = Regions.moved(region, pivot, destination, sampler, 62, 400, Long.MAX_VALUE, Long.MAX_VALUE);
            java.util.Set<BlockPos> got = new java.util.HashSet<>();
            Box b = moved.bounds();
            for (int x = b.min().x(); x <= b.max().x(); x++) {
                for (int y = b.min().y(); y <= b.max().y(); y++) {
                    for (int z = b.min().z(); z <= b.max().z(); z++) if (moved.contains(x, y, z)) got.add(new BlockPos(x, y, z));
                }
            }
            assertEquals(bruteLanding(region, pivot, destination, o, 62, 400), got, o.toString());
        }
    }

    @Test
    void twoCellsFarApartCostTwoCellsWorthOfWorkOrAreRefused() {
        Region far = new Region.Cells(dev.sculptory.core.region.CellSet.builder().add(0, 64, 0).add(20_000, 70, 20_000)
                .build());
        Orientation o = Orientation.free(Transform.IDENTITY, 4_500, 0, 0);
        Box pivot = far.bounds();
        Orientation.Sampler sampler = o.sampler(pivot.sizeX(), pivot.sizeY(), pivot.sizeZ());
        Region landed = Regions.moved(far, pivot, BlockPos.ORIGIN, sampler, -64, 319, Long.MAX_VALUE, 2);
        assertTrue(landed.cellCount() <= 8, "two cells land on " + landed.cellCount());
        long started = System.nanoTime();
        OpSpec.Move move = new OpSpec.Move(far, new BlockPos(3, 0, 0), o, new Pattern.Single(air), EntityFilter.NONE,
                Symmetry.NONE, PasteOptions.Into.EVERYTHING);
        EditProgram program = OpCompiler.compile(move, context);
        assertTrue(program.estimatedCells() <= 8, "estimated " + program.estimatedCells());
        FakeWorld world = new FakeWorld(states);
        world.set(0, 64, 0, stone);
        world.set(20_000, 70, 20_000, stone);
        FakeExecutor.run(program, world);
        assertTrue(System.nanoTime() - started < 2_000_000_000L, "a sparse turn took " + (System.nanoTime() - started));
        assertEquals(air, world.get(0, 64, 0), "the first cell moved away");
        // Past the cells allowed: refused before anything is mapped.
        assertThrows(dev.sculptory.core.region.RegionTooLargeException.class,
                () -> Regions.moved(far, pivot, BlockPos.ORIGIN, sampler, -64, 319, Long.MAX_VALUE, 1));
        CompileContext tight = new CompileContext() {
            @Override
            public dev.sculptory.core.state.StateSpace states() {
                return states;
            }

            @Override
            public java.util.Optional<SourceBlocks> source(SourceRef ref) {
                return java.util.Optional.empty();
            }

            @Override
            public long maxCells() {
                return 100;
            }

            @Override
            public int bottomY() {
                return -64;
            }

            @Override
            public int topYExclusive() {
                return 320;
            }
        };
        OpSpec.Move dense = new OpSpec.Move(new Region.Cuboid(box(0, 64, 0, 9, 70, 9)), BlockPos.ORIGIN, o,
                new Pattern.Single(air), EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING);
        assertThrows(EditTooLargeException.class, () -> OpCompiler.compile(dense, tight));
    }

    @Test
    void aFreeMoveLeavingTheBuildHeightIsRefused() {
        OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(box(0, 310, 0, 20, 318, 3)), BlockPos.ORIGIN,
                Orientation.free(Transform.IDENTITY, 0, 0, Orientation.QUARTER_TURN), new Pattern.Single(air), EntityFilter.NONE,
                Symmetry.NONE, PasteOptions.Into.EVERYTHING);
        assertThrows(IllegalArgumentException.class, () -> OpCompiler.compile(move, context));
    }
}
