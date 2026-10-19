package dev.sculptory.core.edit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BiomeSpec;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.HeightmapSpec;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.BiomeChange;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The core contracts of protocol 6 (the release after 0.3.0-alpha): the multi-paste, the biome fill, orientations on
 * Paste and Move, the paste options' biomes, the Heightmap and Biome brushes and the edit record's biome slot. What the
 * new ops and brushes do in full is tested next to their programs and kernels.
 */
class BatchSixContractsTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final Region BOX = new Region.Cuboid(Box.of(new BlockPos(0, 60, 0), new BlockPos(7, 63, 7)));
    private static final SourceRef CLIPBOARD = new SourceRef.Clipboard(new UUID(1, 2));
    private static final Pattern STONE = new Pattern.Single(STATES.state("minecraft:stone"));
    private static final Symmetry MIRROR = new Symmetry(Symmetry.Mode.MIRROR_X, 40, 0);

    private static final CompileContext CONTEXT = new CompileContext() {
        @Override
        public StateSpace states() {
            return STATES;
        }

        @Override
        public Optional<SourceBlocks> source(SourceRef ref) {
            return Optional.empty();
        }
    };

    private static OpSpec.PasteMany stamp(Symmetry symmetry) {
        return new OpSpec.PasteMany(CLIPBOARD, List.of(
                new OpSpec.PasteMany.Placement(new BlockPos(1, 64, 2), Orientation.IDENTITY),
                new OpSpec.PasteMany.Placement(new BlockPos(9, 65, -4), Orientation.of(new Transform(1, Mirror.X)))),
                PasteOptions.DEFAULT, symmetry);
    }

    @Test
    void pastesAndMovesCarryAnOrientationAndTheirTransformIsItsExactPart() {
        Transform turned = new Transform(2, Mirror.Z, true);
        OpSpec.Paste paste = new OpSpec.Paste(CLIPBOARD, BlockPos.ORIGIN, turned, PasteOptions.DEFAULT);
        assertEquals(Orientation.of(turned), paste.orientation());
        assertEquals(turned, paste.t());
        OpSpec.Move move = new OpSpec.Move(BOX, BlockPos.ORIGIN, turned, STONE, EntityFilter.NONE);
        assertEquals(turned, move.t());
        Orientation free = Orientation.free(Transform.IDENTITY, 3_000, 0, 0);
        OpSpec.Paste freePaste = new OpSpec.Paste(CLIPBOARD, BlockPos.ORIGIN, free, PasteOptions.DEFAULT);
        assertThrows(IllegalStateException.class, freePaste::t, "code that reads only the transform fails loudly");
        assertThrows(IllegalStateException.class, () -> new OpSpec.Move(BOX, BlockPos.ORIGIN, free, STONE,
                EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING).t());
        // Symmetry and regions keep the orientation.
        assertEquals(free, ((OpSpec.Paste) OpSymmetry.withSymmetry(freePaste, MIRROR)).orientation());
        OpSpec.Move freeMove = new OpSpec.Move(BOX, BlockPos.ORIGIN, free, STONE, EntityFilter.NONE, Symmetry.NONE,
                PasteOptions.Into.EVERYTHING);
        assertEquals(free, ((OpSpec.Move) OpRegions.withRegion(freeMove, BOX)).orientation());
        // A free move's target volume counts its turned bounds.
        assertTrue(OpCompiler.targetVolume(freeMove, null) >= BOX.cellCount());
    }

    /**
     * A move or stack carries biomes only when asked (the Place tool's Biomes box), and every copy of it (symmetric,
     * exact or free, a new region) asks the same.
     */
    @Test
    void movesAndStacksCarryBiomesOnlyWhenAskedAndEveryCopyAsksTheSame() {
        Orientation free = Orientation.free(Transform.IDENTITY, 3_000, 0, 0);
        Symmetry rotate = new Symmetry(Symmetry.Mode.ROTATE_4, 40, 40);
        Region other = new Region.Cuboid(Box.of(new BlockPos(2, 60, 2), new BlockPos(5, 61, 5)));
        assertFalse(new OpSpec.Move(BOX, BlockPos.ORIGIN, Transform.IDENTITY, STONE, EntityFilter.NONE).biomes());
        assertFalse(new OpSpec.Move(BOX, BlockPos.ORIGIN, free, STONE, EntityFilter.NONE, MIRROR,
                PasteOptions.Into.EVERYTHING).biomes());
        assertFalse(new OpSpec.Stack(BOX, 0, 4, 0, 2, EntityFilter.NONE, MIRROR, PasteOptions.Into.EVERYTHING, true)
                .biomes());
        for (boolean biomes : new boolean[] {false, true}) {
            for (Orientation orientation : List.of(Orientation.IDENTITY, Orientation.of(new Transform(1, Mirror.X)),
                    free)) {
                OpSpec.Move move = new OpSpec.Move(BOX, new BlockPos(30, 0, 0), orientation, STONE, EntityFilter.NONE,
                        Symmetry.NONE, PasteOptions.Into.EVERYTHING, biomes);
                for (Symmetry symmetry : List.of(MIRROR, rotate)) {
                    OpSpec symmetric = OpSymmetry.withSymmetry(move, symmetry);
                    assertEquals(biomes, ((OpSpec.Move) symmetric).biomes());
                    for (OpSymmetry.Copy copy : OpSymmetry.copies(symmetric)) {
                        assertEquals(biomes, ((OpSpec.Move) copy.op()).biomes(), orientation + " " + copy.image());
                    }
                }
                assertEquals(biomes, ((OpSpec.Move) OpRegions.withRegion(move, other)).biomes());
            }
            OpSpec.Stack stack = new OpSpec.Stack(BOX, 0, 4, 0, 2, EntityFilter.NONE, Symmetry.NONE,
                    PasteOptions.Into.AIR, true, biomes);
            for (Symmetry symmetry : List.of(MIRROR, rotate)) {
                OpSpec symmetric = OpSymmetry.withSymmetry(stack, symmetry);
                assertEquals(biomes, ((OpSpec.Stack) symmetric).biomes());
                for (OpSymmetry.Copy copy : OpSymmetry.copies(symmetric)) {
                    OpSpec.Stack copied = (OpSpec.Stack) copy.op();
                    assertEquals(biomes, copied.biomes(), copy.image().toString());
                    assertTrue(copied.upsideDown());
                }
            }
            assertEquals(biomes, ((OpSpec.Stack) OpRegions.withRegion(stack, other)).biomes());
        }
    }

    @Test
    void thePasteOptionsPasteNoBiomesUnlessAsked() {
        assertFalse(PasteOptions.DEFAULT.biomes());
        assertFalse(new PasteOptions(true, true, true, PasteOptions.Into.AIR).biomes());
        PasteOptions biomes = PasteOptions.DEFAULT.withBiomes(true);
        assertTrue(biomes.biomes());
        assertTrue(biomes.withInto(PasteOptions.Into.EXISTING).biomes(), "withInto keeps the biomes");
        assertNotEquals(PasteOptions.DEFAULT, biomes);
    }

    @Test
    void aMultiPasteTakesOneToTwoHundredFiftySixPlacements() {
        OpSpec.PasteMany stamp = stamp(Symmetry.NONE);
        assertThrows(IllegalArgumentException.class, () -> new OpSpec.PasteMany(CLIPBOARD, List.of(),
                PasteOptions.DEFAULT));
        assertThrows(NullPointerException.class, () -> new OpSpec.PasteMany.Placement(null, Orientation.IDENTITY));
        assertNull(OpRegions.region(stamp));
        assertThrows(IllegalArgumentException.class, () -> OpRegions.withRegion(stamp, BOX));
        assertEquals(2L * 4 * 5 * 6, OpCompiler.targetVolume(stamp, new BlockPos(4, 5, 6)));
        assertThrows(NullPointerException.class, () -> OpCompiler.targetVolume(stamp, null));
        // Each placement's copy is mirrored as a paste's would be.
        OpSpec.PasteMany mirrored = stamp(MIRROR);
        assertEquals(MIRROR, OpSymmetry.of(mirrored));
        List<OpSymmetry.Copy> copies = OpSymmetry.copies(mirrored);
        assertEquals(2, copies.size());
        OpSpec.PasteMany image = (OpSpec.PasteMany) copies.get(1).op();
        for (int i = 0; i < 2; i++) {
            OpSpec.PasteMany.Placement original = mirrored.placements().get(i);
            OpSpec.Paste asPaste = new OpSpec.Paste(CLIPBOARD, original.origin(), original.orientation(),
                    PasteOptions.DEFAULT, MIRROR);
            OpSpec.Paste pasteImage = (OpSpec.Paste) OpSymmetry.copies(asPaste).get(1).op();
            assertEquals(pasteImage.origin(), image.placements().get(i).origin());
            assertEquals(pasteImage.orientation(), image.placements().get(i).orientation());
        }
        // Compiled by PasteManyProgram (PasteManyTest): a source the context does not hold is refused.
        assertThrows(IllegalArgumentException.class, () -> OpCompiler.compile(stamp, CONTEXT));
    }

    @Test
    void aBiomeFillNamesAWellFormedBiomeAndCompilesToTheQuartsItTouches() {
        OpSpec.BiomeFill fill = new OpSpec.BiomeFill(BOX, "minecraft:cherry_grove", true);
        assertEquals(Symmetry.NONE, fill.symmetry());
        assertEquals(BOX, OpRegions.region(fill));
        assertEquals(BOX.bounds(), fill.box());
        assertThrows(IllegalArgumentException.class, () -> new OpSpec.BiomeFill(BOX, "cherry grove", true));
        assertThrows(NullPointerException.class, () -> new OpSpec.BiomeFill(BOX, null, true));
        assertEquals(BOX.cellCount(), OpCompiler.targetVolume(fill, null));
        OpSpec.BiomeFill mirrored = (OpSpec.BiomeFill) OpSymmetry.withSymmetry(fill, MIRROR);
        List<OpSymmetry.Copy> copies = OpSymmetry.copies(mirrored);
        assertEquals(2, copies.size());
        OpSpec.BiomeFill image = (OpSpec.BiomeFill) copies.get(1).op();
        assertEquals("minecraft:cherry_grove", image.biomeId());
        assertTrue(image.columns());
        assertEquals(MIRROR.imageBox(copies.get(1).image(), BOX.bounds()), image.box());
        assertThrows(IllegalArgumentException.class, () -> OpCompiler.compile(fill, CONTEXT),
                "a fill needs the world's build height");
        CompileContext world = new CompileContext() {
            @Override
            public StateSpace states() {
                return STATES;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef ref) {
                return Optional.empty();
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
        // x and z 0..7 touch two quart columns each way; full height is 96 quarts.
        BiomeFillProgram program = (BiomeFillProgram) OpCompiler.compile(fill, world);
        assertEquals(4 * 96, program.estimatedCells());
        assertEquals(0, program.sectionOrder().length, "no block is written");
        BiomeFillProgram threeD = (BiomeFillProgram) OpCompiler.compile(new OpSpec.BiomeFill(BOX, "minecraft:desert",
                false), world);
        assertEquals(4, threeD.estimatedCells(), "y 60..63 is one quart layer");
        assertEquals(8 * 96, ((BiomeFillProgram) OpCompiler.compile(mirrored, world)).estimatedCells(),
                "the mirrored copy's quarts too");
    }

    @Test
    void theHeightmapAndBiomeBrushesHaveTheirOwnSpecs() {
        assertFalse(BrushTool.HEIGHTMAP.terrain());
        assertFalse(BrushTool.BIOME.terrain());
        assertFalse(SculptMode.surfaceTool(BrushTool.HEIGHTMAP));
        byte[] heights = new byte[9];
        heights[4] = (byte) 255;
        HeightmapSpec image = new HeightmapSpec(3, heights, 12, false);
        heights[4] = 0;
        assertEquals(255, image.height(1, 1), "the spec copies the heights in");
        image.heights()[4] = 0;
        assertEquals(255, image.height(1, 1), "and out");
        assertEquals(image, new HeightmapSpec(3, image.heights(), 12, false));
        assertEquals(image.hashCode(), new HeightmapSpec(3, image.heights(), 12, false).hashCode());
        assertNotEquals(image, new HeightmapSpec(3, image.heights(), 12, true));
        assertThrows(IndexOutOfBoundsException.class, () -> image.height(3, 0));
        BrushSpec heightmap = new BrushSpec(BrushTool.HEIGHTMAP, 1, 1f, Falloff.SMOOTH, Shape.SQUARE, null,
                SurfaceMask.ANY, 0, 0, 1L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null, null, image, null);
        assertEquals(image, heightmap.withClip(Box.of(BlockPos.ORIGIN, BlockPos.ORIGIN)).heightmap(),
                "the with-methods keep the image");
        assertThrows(IllegalArgumentException.class, () -> heightmap.withSurface(null), "no Surface mode");
        BrushSpec biome = new BrushSpec(BrushTool.BIOME, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                0, 1L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null, null, null,
                new BiomeSpec("minecraft:desert", true));
        assertEquals(new BiomeSpec("minecraft:desert", true), biome.withSymmetry(MIRROR).biome());
        assertThrows(IllegalArgumentException.class, () -> new BiomeSpec("Desert", true));
    }

    @Test
    void anEditRecordHoldsBiomeChangesWithoutChangingItsOtherFields() {
        EditRecord cells = new EditRecord(new BlockBuffer(), new BlockBuffer());
        assertTrue(cells.biomes().isEmpty());
        assertTrue(cells.isEmpty());
        assertNull(cells.bounds());
        BiomeChange change = new BiomeChange(-1, 16, 3, "minecraft:plains", "minecraft:desert");
        assertEquals(-4, change.blockX());
        assertEquals(64, change.blockY());
        assertEquals(12, change.blockZ());
        EditRecord biomes = new EditRecord(new BlockBuffer(), new BlockBuffer(), List.of(), List.of(change));
        assertFalse(biomes.isEmpty());
        assertEquals(Box.of(new BlockPos(-4, 64, 12), new BlockPos(-1, 67, 15)), biomes.bounds());
        assertTrue(biomes.estimatedBytes() > cells.estimatedBytes());
        assertThrows(IllegalArgumentException.class, () -> new BiomeChange(0, 0, 0, "plains", "minecraft:desert"));
        assertThrows(UnsupportedOperationException.class, () -> biomes.biomes().add(change), "a copy");
    }
}
