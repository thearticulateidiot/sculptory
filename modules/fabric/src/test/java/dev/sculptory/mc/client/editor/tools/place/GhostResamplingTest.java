package dev.sculptory.mc.client.editor.tools.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CompileContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteGeometry;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceBlocks;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeExecutor;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Resampling;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.mc.client.editor.render.ghost.GhostVolume;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The paste and move ghost of a free turn shows what the server writes, for every method with and without smooth
 * slopes: {@link GhostBaker#bake(Box, java.util.Collection, Orientation, StateSpace, GhostBaker.FlipTally)} against the
 * engine's own programs run on a fake world.
 */
class GhostResamplingTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int air = states.air();

    /** A small house: stone walls, a planks roof, a door and a bed. */
    private FakeWorld build(Box box) {
        FakeWorld world = new FakeWorld(states);
        int stone = states.state("minecraft:stone");
        int planks = states.state("minecraft:oak_planks");
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    boolean wall = x == box.min().x() || x == box.max().x() || z == box.min().z() || z == box.max().z();
                    world.set(x, y, z, y == box.max().y() ? planks : wall ? stone : air);
                }
            }
        }
        BlockPos m = box.min();
        world.set(m.x() + 2, m.y() + 1, m.z(), states.state("minecraft:oak_door[facing=north,half=lower]"));
        world.set(m.x() + 2, m.y() + 2, m.z(), states.state("minecraft:oak_door[facing=north,half=upper]"));
        world.set(m.x() + 1, m.y(), m.z() + 2, states.state("minecraft:red_bed[facing=south,part=foot]"));
        world.set(m.x() + 1, m.y(), m.z() + 3, states.state("minecraft:red_bed[facing=south,part=head]"));
        return world;
    }

    private CompileContext context(SourceRef ref, SourceBlocks source) {
        return new CompileContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef r) {
                return r.equals(ref) ? Optional.of(source) : Optional.empty();
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

    private static List<Orientation> orientations() {
        return List.of(Orientation.free(Transform.IDENTITY, 3_000, 2_000, 0),
                Orientation.free(new Transform(1, dev.sculptory.core.transform.Mirror.X), 4_500, 0, 2_500));
    }

    @Test
    void aFreeMoveGhostIsWhatTheMoveWrites() {
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(6, 68, 5));
        for (Orientation turn : orientations()) {
            for (Resampling method : Resampling.values()) {
                for (boolean slopes : new boolean[] {false, true}) {
                    Orientation o = turn.withResampling(method, slopes);
                    FakeWorld world = build(box);
                    GhostVolume volume = GhostBaker.capture(world, box);
                    GhostVolume ghost = GhostBaker.bake(volume.frame(), volume.sections(), o, states,
                            new GhostBaker.FlipTally(), GhostBaker.CellFilter.ALL);
                    OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(box), BlockPos.ORIGIN, o,
                            new Pattern.Single(air), EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING);
                    EditProgram program = OpCompiler.compile(move, context(new SourceRef.Clipboard(new UUID(0, 0)),
                            null));
                    FakeExecutor.run(program, world);
                    BlockPos at = box.min().add(o.bounds(box.sizeX(), box.sizeY(), box.sizeZ()).offset());
                    compare(ghost, world, at, o.toString());
                }
            }
        }
    }

    @Test
    void aFreePasteGhostIsWhatThePasteWrites() {
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(6, 68, 5));
        BlockPos size = new BlockPos(box.sizeX(), box.sizeY(), box.sizeZ());
        for (Orientation turn : orientations()) {
            for (Resampling method : Resampling.values()) {
                for (boolean slopes : new boolean[] {false, true}) {
                    Orientation o = turn.withResampling(method, slopes);
                    FakeWorld source = build(box);
                    Clipboard.Builder builder = Clipboard.builder(states, size).anchor(new BlockPos(3, 0, 2));
                    for (int x = 0; x < size.x(); x++) {
                        for (int y = 0; y < size.y(); y++) {
                            for (int z = 0; z < size.z(); z++) {
                                int s = source.get(box.min().x() + x, box.min().y() + y, box.min().z() + z);
                                builder.set(x, y, z, s);
                            }
                        }
                    }
                    Clipboard clipboard = builder.build();
                    GhostVolume volume = GhostBaker.capture(source, box);
                    GhostVolume ghost = GhostBaker.bake(volume.frame(), volume.sections(), o, states,
                            new GhostBaker.FlipTally());
                    BlockPos origin = new BlockPos(40, 80, -20);
                    SourceRef ref = new SourceRef.Clipboard(new UUID(1, 1));
                    FakeWorld world = new FakeWorld(states);
                    FakeExecutor.run(OpCompiler.compile(new OpSpec.Paste(ref, origin, o, PasteOptions.DEFAULT),
                            context(ref, clipboard.toSource())), world);
                    Box target = PasteGeometry.pasteTarget(size, clipboard.anchor(), o, origin);
                    compare(ghost, world, target.min(), o.toString());
                }
            }
        }
    }

    /** Every block the ghost shows is in the world at its place, and the world holds no other block there. */
    private void compare(GhostVolume ghost, FakeWorld world, BlockPos at, String what) {
        Box frame = ghost.frame();
        long shown = 0, written = 0;
        for (int x = 0; x <= frame.max().x(); x++) {
            for (int y = 0; y <= frame.max().y(); y++) {
                for (int z = 0; z <= frame.max().z(); z++) {
                    int h = ghost.handle(x, y, z);
                    int got = world.get(at.x() + x, at.y() + y, at.z() + z);
                    if (h >= 0 && h != air) {
                        shown++;
                        assertEquals(states.format(h), states.format(got), what + " at " + x + "," + y + "," + z);
                    }
                    if (got >= 0 && got != air) written++;
                }
            }
        }
        assertTrue(shown > 20, what + ": " + shown);
        assertEquals(shown, written, what + ": the world holds exactly the ghost's blocks");
    }
}
