package dev.sculptory.core.mask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.brush.WeatherSpec;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.CellPredicate;
import dev.sculptory.core.edit.CompileContext;
import dev.sculptory.core.edit.ComputeContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpRegions;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.OpSymmetry;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceBlocks;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.scatter.FeatureCatalog;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Transform;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The core contracts of the WorldEdit-inspired batch:
 * what they already do before their streams land, and the stubs' refusals.
 */
class BatchContractsTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final Region BOX = new Region.Cuboid(Box.of(new BlockPos(0, 60, 0), new BlockPos(7, 63, 7)));
    private static final Pattern STONE = new Pattern.Single(STATES.state("minecraft:stone"));

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

    // ---------------------------------------------------------------- masks

    @Test
    void aBlockSetMatchesItsBlocksTagsAndExactStatesAndUnknownStatesNothing() {
        int stairs = STATES.state("minecraft:oak_stairs[facing=east,half=top]");
        int otherStairs = STATES.state("minecraft:oak_stairs[facing=west,half=top]");
        BlockSet set = new BlockSet(List.of(new BlockSet.Block(new NamespacedId("minecraft:stone")),
                new BlockSet.Tag(new NamespacedId("minecraft:logs")),
                new BlockSet.State(BlockDescriptor.parse("minecraft:oak_stairs[facing=east,half=top]")),
                new BlockSet.State(BlockDescriptor.parse("modded:gone[facing=up]"))));
        CellPredicate matches = set.toCellMask(STATES).bind(STATES);
        assertTrue(matches.test(0, 0, 0, STATES.state("minecraft:stone")));
        assertTrue(matches.test(0, 0, 0, STATES.state("minecraft:oak_log[axis=x]")));
        assertTrue(matches.test(0, 0, 0, stairs));
        assertFalse(matches.test(0, 0, 0, otherStairs));
        assertFalse(matches.test(0, 0, 0, STATES.air()));
        CellMask nothing = BlockSet.of(new BlockSet.State(BlockDescriptor.parse("modded:gone[facing=up]"))).toCellMask(STATES);
        for (int h = 0; h < STATES.size(); h++) assertFalse(nothing.bind(STATES).test(0, 0, 0, h));
    }

    @Test
    void theOffMaskIsNoneAndPassesEverythingThrough() {
        assertTrue(EditMask.NONE.isOff());
        assertFalse(new EditMask(List.of(), true).isOff());
        EditMask mask = new EditMask(List.of(MaskEntry.of(new MaskRule.NotAir())), false);
        BoundMask bound = mask.bind(STATES);
        assertFalse(bound.acceptsAll());
        assertEquals(0, bound.reach());
        BoundMask off = EditMask.NONE.bind(STATES);
        assertTrue(off.acceptsAll());
        EditProgram program = OpCompiler.compile(new OpSpec.Fill(BOX, STONE, CellMask.ANY), CONTEXT);
        assertSame(program, MaskedProgram.wrap(program, off));
        assertSame(BrushKernels.forTool(BrushTool.RAISE), MaskedKernel.wrap(BrushKernels.forTool(BrushTool.RAISE), off));
        assertThrows(IllegalArgumentException.class, () -> new EditMask(java.util.Collections.nCopies(17,
                MaskEntry.of(new MaskRule.Solid())), false));
        assertThrows(IllegalArgumentException.class, () -> new MaskRule.Chance(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new MaskRule.Chance(100, 1));
        assertEquals(mask, MaskText.decode(MaskText.encode(mask)));
    }

    @Test
    void moveIsMaskedAtItsSourceAndEveryOtherOpWhereItLands() {
        assertEquals(MaskSide.SOURCE, MaskedProgram.sideOf(new OpSpec.Move(BOX, new BlockPos(1, 0, 0), Transform.IDENTITY,
                STONE, EntityFilter.NONE)));
        for (OpSpec op : List.of(new OpSpec.Fill(BOX, STONE, CellMask.ANY), new OpSpec.Stack(BOX, 1, 0, 0, 2,
                EntityFilter.NONE), new OpSpec.Overlay(BOX, STONE, 1), new OpSpec.UpdateBlocks(BOX))) {
            assertEquals(MaskSide.DESTINATION, MaskedProgram.sideOf(op), op.getClass().getSimpleName());
        }
        assertSame(BoundMask.ALL, CONTEXT.sourceMask());
        assertNull(CONTEXT.neighbourShapes());
    }

    @Test
    void theSnapshotReaderReadsSnapshotsFirstAndTheLiveWorldElsewhere() {
        FakeWorld live = new FakeWorld(STATES);
        int stone = STATES.state("minecraft:stone"), dirt = STATES.state("minecraft:dirt");
        live.set(1, 2, 3, stone);
        live.set(20, 2, 3, stone);
        SectionBuffer snapshot = new SectionBuffer();
        snapshot.set(SectionBuffer.index(1, 2, 3), dirt);
        long key = BlockBuffer.key(0, 0, 0);
        ComputeContext ctx = new ComputeContext() {
            @Override
            public StateSpace states() {
                return STATES;
            }

            @Override
            public long seed() {
                return 0;
            }

            @Override
            public SectionBuffer source(long k) {
                return k == key ? snapshot : null;
            }
        };
        SnapshotReader reader = new SnapshotReader(ctx, live);
        assertEquals(dirt, reader.get(1, 2, 3), "the snapshot, not the live world");
        assertEquals(STATES.air(), reader.get(2, 2, 3), "a snapshot's absent cell is air");
        assertEquals(stone, reader.get(20, 2, 3), "outside the snapshots: the live world");
        assertEquals(STATES.air(), new SnapshotReader(ctx, null).get(20, 2, 3), "no live world: air");
    }

    // ---------------------------------------------------------------- Better Replace and the selection ops

    @Test
    void keepShapeKeepsTheSharedPropertiesOfTheCell() {
        int oak = STATES.state("minecraft:oak_stairs[facing=east,half=top,shape=outer_left]");
        int brick = STATES.state("minecraft:stone_brick_stairs");
        int kept = new Pattern.KeepShape(new Pattern.Single(brick)).apply(STATES, 0, 0, 0, oak);
        assertEquals(STATES.state("minecraft:stone_brick_stairs[facing=east,half=top,shape=outer_left]"), kept);
        int stone = STATES.state("minecraft:stone");
        assertEquals(stone, new Pattern.KeepShape(STONE).apply(STATES, 0, 0, 0, oak), "no shared properties");
        assertEquals(STATES.state("minecraft:oak_log[axis=x]"),
                STATES.withSharedProperties(STATES.state("minecraft:oak_log[axis=y]"), STATES.state("minecraft:oak_log[axis=x]")));
        // replace-ops: the remap works (ReplaceOpsProgramTest).
        Pattern remap = new Pattern.Remap(List.of(new Pattern.BlockSwap(new NamespacedId("minecraft:oak_log"),
                new NamespacedId("minecraft:stone"))), true);
        assertEquals(oak, remap.apply(STATES, 0, 0, 0, oak), "an unmapped block is unchanged");
    }

    @Test
    void theLayerOpsHaveRegionsSymmetryAndCopies() {
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 20, 0);
        Region other = new Region.Cuboid(Box.of(new BlockPos(1, 60, 1), new BlockPos(2, 61, 2)));
        List<OpSpec> ops = List.of(new OpSpec.Overlay(BOX, STONE, 3, mirror),
                new OpSpec.Naturalize(BOX, STONE, 1, STONE, 3, STONE, mirror), new OpSpec.UpdateBlocks(BOX, mirror));
        for (OpSpec op : ops) {
            String name = op.getClass().getSimpleName();
            assertEquals(BOX, OpRegions.region(op), name);
            assertEquals(other, OpRegions.region(OpRegions.withRegion(op, other)), name);
            assertEquals(mirror, OpSymmetry.of(op), name);
            assertEquals(Symmetry.NONE, OpSymmetry.of(OpSymmetry.withSymmetry(op, Symmetry.NONE)), name);
            assertEquals(2, OpSymmetry.copies(op).size(), name);
            // replace-ops: an overlay's layer may reach 3 cells over each column of the box (ReplaceOpsProgramTest).
            long layer = op instanceof OpSpec.Overlay ? 8L * 8 * 3 : 0;
            assertEquals(2L * (8 * 4 * 8 + layer), OpCompiler.targetVolume(op, null), name);
        }
        assertFalse(OpCompiler.compile(new OpSpec.Fill(BOX, STONE, CellMask.ANY), CONTEXT).relightsAfter());
    }

    // ---------------------------------------------------------------- Weather, lines, scatter features, navigation

    @Test
    void theWeatherBrushHasItsOwnKernel() {
        assertFalse(BrushTool.WEATHER.terrain());
        assertFalse(BrushTool.TERRAIN.contains(BrushTool.WEATHER));
        BrushSpec spec = new BrushSpec(BrushTool.WEATHER, 3, 1f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                1L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null, new WeatherSpec(WeatherSpec.Mode.FILL_IN));
        // The weather stream replaced the contract stub: an empty world changes nothing (WeatherKernelTest has the rest).
        BrushKernels.forTool(BrushTool.WEATHER).applyStep(spec, List.of(new Dab(0, 0, 64 * 16, 0, 255)), new StrokeState(),
                new FakeWorld(STATES), (x, y, z, s) -> {
                    throw new AssertionError("wrote " + x + " " + y + " " + z);
                });
        assertThrows(IllegalArgumentException.class, () -> BrushKernels.forTool(BrushTool.SMOOTH).applyStep(spec,
                List.of(new Dab(0, 0, 64 * 16, 0, 255)), new StrokeState(), new FakeWorld(STATES), (x, y, z, s) -> {}));
    }

    @Test
    void pathsAndTheFeatureCatalogKeepTheirLimits() {
        List<BlockPos> two = List.of(BlockPos.ORIGIN, new BlockPos(10, 5, 0));
        assertEquals(0, new PathSpec(two, PathKind.CURVE).sag());
        assertEquals(12.5, new PathSpec(two, PathKind.HANGING, 12.5).sag());
        assertThrows(IllegalArgumentException.class, () -> new PathSpec(List.of(BlockPos.ORIGIN), PathKind.STRAIGHT));
        assertThrows(IllegalArgumentException.class, () -> new PathSpec(two, PathKind.STRAIGHT, 1));
        assertThrows(IllegalArgumentException.class, () -> new PathSpec(two, PathKind.HANGING, PathSpec.MAX_SAG + 1));
        Set<String> ids = new HashSet<>();
        for (FeatureCatalog.FeatureDef def : FeatureCatalog.ALL) {
            assertTrue(ids.add(def.id()), "listed twice: " + def.id());
            assertTrue(def.id().startsWith("minecraft:"), def.id());
            assertEquals(Optional.of(def), FeatureCatalog.find(def.id()));
        }
        assertTrue(FeatureCatalog.find("minecraft:not_a_tree").isEmpty());
        assertTrue(StateFlags.has(STATES.flags(STATES.air()), StateFlags.NO_COLLISION));
        assertFalse(StateFlags.has(STATES.flags(STATES.state("minecraft:stone")), StateFlags.NO_COLLISION));
    }
}
