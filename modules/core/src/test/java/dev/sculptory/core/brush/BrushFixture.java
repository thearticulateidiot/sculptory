package dev.sculptory.core.brush;

import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.IntBinaryOperator;

/** Terrain, dabs and heightfield readouts for brush tests. */
final class BrushFixture {
    static final int FLOOR = 50;
    static final int EXTENT = 40;

    final FakeStateSpace states = new FakeStateSpace();
    final int air = states.air();
    final int stone = states.state("minecraft:stone");
    final int dirt = states.state("minecraft:dirt");
    final int grass = states.state("minecraft:grass_block");
    final int sand = states.state("minecraft:sand");
    final int water = states.state("minecraft:water");
    final int shortGrass = states.state("minecraft:short_grass");
    final int stairs = states.state("minecraft:oak_stairs");

    record Write(int x, int y, int z, int state) {}

    /** Stone from {@value #FLOOR} with a grass top at {@code height(x, z)}, for |x|, |z| <= {@value #EXTENT}. */
    FakeWorld terrain(IntBinaryOperator height) {
        FakeWorld world = new FakeWorld(states);
        for (int x = -EXTENT; x <= EXTENT; x++) {
            for (int z = -EXTENT; z <= EXTENT; z++) {
                int top = height.applyAsInt(x, z);
                for (int y = FLOOR; y <= top; y++) world.set(x, y, z, y < top ? stone : grass);
            }
        }
        return world;
    }

    FakeWorld flat(int surfaceY) {
        return terrain((x, z) -> surfaceY);
    }

    /** Rebuilds one column: stone up to {@code top - 1}, grass at {@code top}, air above up to y 100. */
    void column(FakeWorld world, int x, int z, int top) {
        for (int y = FLOOR; y <= 100; y++) world.set(x, y, z, y < top ? stone : y == top ? grass : air);
    }

    static BrushSpec spec(BrushTool tool, int radius, float strength, Falloff falloff, Shape shape) {
        return new BrushSpec(tool, radius, strength, falloff, shape, null, SurfaceMask.ANY, 0, 0, 1L);
    }

    static BrushSpec paint(BrushTool tool, int radius, float strength, Pattern material, int depth, SurfaceMask mask) {
        return new BrushSpec(tool, radius, strength, Falloff.CONSTANT, Shape.CIRCLE, material, mask, depth, 0, 1L);
    }

    static BrushSpec masked(BrushTool tool, int radius, SurfaceMask mask) {
        return new BrushSpec(tool, radius, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, mask, 0, 0, 1L);
    }

    static BrushSpec flatten(int radius, float strength, Shape shape, int flattenY) {
        return new BrushSpec(BrushTool.FLATTEN, radius, strength, Falloff.CONSTANT, shape, null, SurfaceMask.ANY, 0, flattenY, 1L);
    }

    /** A full-pressure dab centred on block column (x, z), at height y. */
    static Dab at(int index, int x, int y, int z) {
        return new Dab(index, x * 16 + 8, y * 16, z * 16 + 8, Dab.FULL_PRESSURE);
    }

    /** Applies one dab, writing through to the world, and returns the cells written in sink order. */
    static List<Write> dab(BrushSpec spec, StrokeState stroke, FakeWorld world, Dab dab) {
        List<Write> writes = new ArrayList<>();
        BrushKernels.forTool(spec.tool()).apply(spec, dab, stroke, world, (x, y, z, h) -> {
            writes.add(new Write(x, y, z, h));
            world.set(x, y, z, h);
        });
        return writes;
    }

    /** The topmost terrain-solid y of a column (searching y 100 down to the floor), or {@code FLOOR - 1}. */
    int surface(FakeWorld world, int x, int z) {
        for (int y = 100; y >= FLOOR; y--) {
            if (StateFlags.has(states.flags(world.get(x, y, z)), StateFlags.TERRAIN_SOLID)) return y;
        }
        return FLOOR - 1;
    }

    /** Surface heights minus {@code base} for |x - cx|, |z - cz| <= r: one line per z, x ascending. */
    String heights(FakeWorld world, int cx, int cz, int r, int base) {
        StringJoiner lines = new StringJoiner("\n");
        for (int z = cz - r; z <= cz + r; z++) {
            StringJoiner line = new StringJoiner(" ");
            for (int x = cx - r; x <= cx + r; x++) line.add(Integer.toString(surface(world, x, z) - base));
            lines.add(line.toString());
        }
        return lines.toString();
    }

    static String grid(String... rows) {
        return String.join("\n", rows);
    }
}
