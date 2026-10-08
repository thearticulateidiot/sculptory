package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.testing.FakeStateSpace;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Line's square profile on sloped, diagonal and curved paths, pinned by a hash of their cells: the frame the square
 * is laid in comes from {@code PathFrame.square} since the path extrusion batch, and must lay exactly the cells the
 * Line laid before (the hash was taken from the Line as it was at protocol 6's contracts).
 */
class LineKernelGoldenTest {
    private final FakeStateSpace states = new FakeStateSpace();

    private static PathSpec path(PathKind kind, int... xyz) {
        List<BlockPos> points = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) points.add(new BlockPos(xyz[i], xyz[i + 1], xyz[i + 2]));
        return new PathSpec(points, kind);
    }

    @Test
    void squareLinesOnSlopesDiagonalsAndCurvesLayTheCellsTheyAlwaysDid() throws Exception {
        Map<String, PathSpec> paths = new HashMap<>();
        paths.put("slope", path(PathKind.STRAIGHT, 0, 64, 0, 20, 79, 0));
        paths.put("diagonal", path(PathKind.STRAIGHT, 0, 64, 0, 17, 64, 13));
        paths.put("climbing diagonal", path(PathKind.STRAIGHT, -5, 60, 3, 14, 75, -11, 20, 90, 6));
        paths.put("vertical", path(PathKind.STRAIGHT, 2, 60, 2, 2, 80, 2));
        paths.put("curve", path(PathKind.CURVE, 0, 64, 0, 10, 70, 5, 20, 64, -3, 30, 72, 8));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String name : List.of("slope", "diagonal", "climbing diagonal", "vertical", "curve")) {
            for (int thickness : new int[] {2, 3, 4, 5}) {
                GeneratedSource line = LineKernel.generate(new LineKernel.Spec(paths.get(name), thickness,
                        LineKernel.Profile.SQUARE, new Pattern.Single(states.state("minecraft:stone"))), states, 1 << 22);
                StringBuilder cells = new StringBuilder(name).append(thickness).append(':');
                line.forEach((x, y, z, state) -> cells.append(x).append(',').append(y).append(',').append(z).append(';'));
                digest.update(cells.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        assertEquals(GOLDEN, hex.toString());
    }

    private static final String GOLDEN = "adc87d85aaa2b7e7aa42f5373b6901511a4a44a6b1993ed6a7e96a7f2fbd11cb";
}
