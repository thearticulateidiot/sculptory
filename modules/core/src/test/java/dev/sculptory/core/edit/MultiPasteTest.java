package dev.sculptory.core.edit;

import static dev.sculptory.core.edit.CopyTestSupport.box;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The multi-paste behind scatter commits: exact output, and compute work bounded by the placed cells. */
class MultiPasteTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");

    /** Two cells at opposite corners of an {@code n}³ box, anchored at the first. */
    private Clipboard corners(int n, int state) {
        return Clipboard.builder(states, new BlockPos(n, n, n)).set(0, 0, 0, state).set(n - 1, n - 1, n - 1, state).build();
    }

    private static CompileContext context(StateSpace states, MultiPaste plan) {
        UUID id = new UUID(1, 2);
        return new CompileContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef ref) {
                return Optional.empty();
            }

            @Override
            public Optional<MultiPaste> scatterPlan(UUID planId) {
                return planId.equals(id) ? Optional.of(plan) : Optional.empty();
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
    }

    private static MultiPasteProgram compile(StateSpace states, MultiPaste plan) {
        return (MultiPasteProgram) OpCompiler.compile(new OpSpec.ScatterCommit(new UUID(1, 2)), context(states, plan));
    }

    /**
     * A worst case: sparse two-corner assets, 131,072 placements. Visiting each placement's whole box would
     * cost ~4,096 visits per placed cell (over a billion here); visiting its parts costs at most 8.
     */
    @Test
    void sparseAssetsAtScaleStayBoundedByTheirCells() {
        List<SourceBlocks> sources = List.of(corners(40, stone).toSource(), corners(16, dirt).toSource());
        List<Transform> transforms = Transform.all();
        List<MultiPaste.Placement> placements = new ArrayList<>(131_072);
        for (int i = 0; i < 131_072; i++) {
            // 48 apart, so nothing overlaps; offset 8 so the 16³ boxes straddle section boundaries.
            BlockPos origin = new BlockPos((i % 512) * 48 + 8, 64, (i / 512) * 48 + 8);
            placements.add(new MultiPaste.Placement(origin, i & 1, transforms.get((i >> 1) % 8)));
        }
        long start = System.nanoTime();
        MultiPasteProgram program = compile(states, new MultiPaste(sources, placements));
        long compiled = System.nanoTime() - start;
        assertEquals(262_144, program.estimatedCells());
        assertTrue(program.visitBound() <= MultiPasteProgram.DENSE_RATIO * program.estimatedCells(),
                "visit bound " + program.visitBound());

        SectionBuffer before = SectionBuffer.uniform(air);
        long written = 0;
        long[] order = program.sectionOrder();
        for (long key : order) {
            SectionBuffer out = new SectionBuffer();
            program.compute(key, before, out, null);
            written += out.presentCount();
        }
        long elapsed = System.nanoTime() - start;
        assertEquals(262_144, written);
        System.out.printf("Sparse multi-paste, 131,072 placements x 2 cells: %d sections, visit bound %d, compile %.1f ms, "
                + "compile + compute %.1f ms%n", order.length, program.visitBound(), compiled / 1e6, elapsed / 1e6);
        assertTrue(elapsed < 10_000_000_000L, "took " + elapsed / 1_000_000 + " ms");
    }

    /** Dense and sparse parts, every transform, overlaps: the same as pasting each placement in turn. */
    @Test
    void matchesSequentialPastes() {
        Random random = new Random(7);
        int[] palette = {stone, dirt, states.state("minecraft:oak_log[axis=x]"),
                states.state("minecraft:oak_stairs[facing=east,shape=outer_left]"), states.state("testmod:widget[facing=north]")};
        // Sparse (~3% fill, spanning several source sections), a two-corner box, and a dense block with a chest.
        Clipboard.Builder sparse = Clipboard.builder(states, new BlockPos(20, 12, 34)).anchor(new BlockPos(3, 0, 5));
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 12; y++) {
                for (int z = 0; z < 34; z++) {
                    if (random.nextInt(32) == 0) sparse.set(x, y, z, palette[random.nextInt(palette.length)]);
                }
            }
        }
        sparse.set(0, 0, 0, stone);
        Clipboard.Builder dense = Clipboard.builder(states, new BlockPos(5, 3, 4)).anchor(new BlockPos(2, 0, 1));
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 4; z++) dense.set(x, y, z, palette[(x + 2 * y + 3 * z) % palette.length]);
            }
        }
        dense.set(2, 2, 2, states.state("minecraft:chest[facing=north]")).setTile(2, 2, 2, CopyTestSupport.chest("minecraft:apple", 3));
        List<Clipboard> clips = List.of(sparse.build(), corners(16, dirt), dense.build());

        List<MultiPaste.Placement> placements = new ArrayList<>();
        List<Transform> transforms = Transform.all();
        for (int i = 0; i < 60; i++) {
            BlockPos origin = new BlockPos(random.nextInt(60) - 30, 60 + random.nextInt(8), random.nextInt(60) - 30);
            placements.add(new MultiPaste.Placement(origin, random.nextInt(3), transforms.get(random.nextInt(8))));
        }
        List<SourceBlocks> sources = new ArrayList<>();
        for (Clipboard clip : clips) sources.add(clip.toSource());

        FakeWorld world = new FakeWorld(states);
        world.fill(box(-60, 55, -60, 60, 62, 60), stone);
        FakeWorld oracle = new FakeWorld(states);
        oracle.fill(box(-60, 55, -60, 60, 62, 60), stone);
        FakeExecutor.run(compile(states, new MultiPaste(sources, placements)), world);

        Map<SourceRef, SourceBlocks> refs = new HashMap<>();
        List<SourceRef> byIndex = new ArrayList<>();
        for (Clipboard clip : clips) {
            SourceRef ref = new SourceRef.Clipboard(UUID.randomUUID());
            refs.put(ref, clip.toSource());
            byIndex.add(ref);
        }
        CompileContext pastes = CopyTestSupport.context(states, refs);
        for (MultiPaste.Placement p : placements) {
            FakeExecutor.run(OpCompiler.compile(new OpSpec.Paste(byIndex.get(p.source()), p.origin(), p.transform(),
                    PasteOptions.DEFAULT), pastes), oracle);
        }
        // Cell by cell over everything the placements can reach (y 60-78, |x|, |z| < 30 + 34).
        long placed = 0;
        for (int x = -70; x <= 70; x++) {
            for (int y = 55; y <= 85; y++) {
                for (int z = -70; z <= 70; z++) {
                    assertEquals(oracle.get(x, y, z), world.get(x, y, z), "state at " + x + "," + y + "," + z);
                    assertEquals(oracle.tile(x, y, z), world.tile(x, y, z), "tile at " + x + "," + y + "," + z);
                    if (y > 62 && world.get(x, y, z) != air) placed++;
                }
            }
        }
        assertTrue(placed > 500, "placed " + placed);
    }
}
