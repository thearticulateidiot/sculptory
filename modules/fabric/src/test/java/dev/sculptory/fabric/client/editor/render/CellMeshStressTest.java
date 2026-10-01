package dev.sculptory.fabric.client.editor.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Sizes and build times of selection meshes in large and worst cases. Vertex bytes are what the renderer uploads ({@link CellMesh#QUAD_BYTES}, {@link CellMesh#EDGE_BYTES}). No
 * GL here: only the mesh is built, as the renderer does off the render thread.
 */
class CellMeshStressTest {
    private record Result(String name, long cells, CellMesh mesh, double millis) {
        @Override
        public String toString() {
            String note = mesh.tooDetailed() ? "  (too detailed: bounds only)" : mesh.edgesDropped() ? "  (faces only)" : "";
            return String.format(Locale.ROOT, "%-32s %,11d cells  %,8d quads  %,8d edges  %5.1f MB  %6.1f ms%s", name, cells,
                    mesh.quadCount(), mesh.edgeCount(), mesh.vertexBytes() / 1e6, millis, note);
        }
    }

    private static long[] sectionKeys(Box box) {
        LongArrayList keys = new LongArrayList();
        box.forEachSectionKey(keys::add);
        return keys.toLongArray();
    }

    private static Result run(String name, long cells, CellMesh.Rows rows, long[] keys) {
        CellMesh.build(rows, keys, CellMesh.Caps.DEFAULT, () -> false); // warm up
        long start = System.nanoTime();
        CellMesh mesh = CellMesh.build(rows, keys, CellMesh.Caps.DEFAULT, () -> false);
        double millis = (System.nanoTime() - start) / 1e6;
        Result result = new Result(name, cells, mesh, millis);
        System.out.println(result);
        assertTrue(millis < 5_000, "meshing took " + millis + " ms");
        assertTrue(mesh.vertexBytes() <= maxBytes());
        return result;
    }

    private static long maxBytes() {
        return (long) CellMesh.Caps.DEFAULT.maxQuads() * CellMesh.QUAD_BYTES
                + (long) CellMesh.Caps.DEFAULT.maxEdges() * CellMesh.EDGE_BYTES;
    }

    /** The ball inscribed in the box from 0 to 2r on each axis (cells within r + 0.5 of cell (r, r, r)), by rows. */
    private static CellMesh.SpanSource ball(int r) {
        double r2 = (r + 0.5) * (r + 0.5);
        return (y, z) -> {
            double rest = r2 - (double) (y - r) * (y - r) - (double) (z - r) * (z - r);
            if (rest < 0) return CellMesh.NO_SPAN;
            double half = Math.sqrt(rest);
            int min = (int) Math.ceil(r - half);
            int max = (int) Math.floor(r + half);
            return min > max ? CellMesh.NO_SPAN : CellMesh.span(min, max);
        };
    }

    @Test
    void largeSpheres() {
        for (int r : new int[] {32, 64, 128}) {
            Box bounds = new Box(BlockPos.ORIGIN, new BlockPos(2 * r, 2 * r, 2 * r));
            long cells = Math.round(4.0 / 3.0 * Math.PI * Math.pow(r + 0.5, 3));
            Result result = run("sphere r=" + r, cells, CellMesh.Rows.ofSpans(ball(r)), sectionKeys(bounds));
            if (r <= 64) {
                assertFalse(result.mesh().tooDetailed() || result.mesh().edgesDropped(), result.toString());
            }
        }
    }

    @Test
    void aMagicSelectedTerrainLayerAtTheDefaultLimit() {
        // A bumpy surface 3 blocks thick over 184 × 184 columns (about 100k blocks, the default magic select limit),
        // read cell by cell as a magic selection is.
        int size = 184;
        int[][] height = new int[size][size];
        Random random = new Random(7);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                height[x][z] = 64 + (int) Math.round(6 * Math.sin(x / 11.0) + 5 * Math.cos(z / 7.0) + random.nextInt(2));
            }
        }
        CellMesh.CellTest layer = (x, y, z) -> x >= 0 && z >= 0 && x < size && z < size
                && y <= height[x][z] && y > height[x][z] - 3;
        Box bounds = new Box(new BlockPos(0, 40, 0), new BlockPos(size - 1, 90, size - 1));
        Result result = run("bumpy terrain layer (magic)", 3L * size * size, CellMesh.Rows.of(layer), sectionKeys(bounds));
        assertFalse(result.mesh().tooDetailed(), result.toString());
        assertFalse(result.mesh().edgesDropped(), result.toString());
    }

    @Test
    void aWorstCaseCheckerboardStopsAtTheCaps() {
        // Every other cell of a 96³ box: nothing merges, six quads and twelve edges per cell.
        CellMesh.CellTest checker = (x, y, z) -> x >= 0 && y >= 0 && z >= 0 && x < 96 && y < 96 && z < 96
                && ((x + y + z) & 1) == 0;
        Box bounds = new Box(BlockPos.ORIGIN, new BlockPos(95, 95, 95));
        Result result = run("checkerboard 96^3 (worst case)", 96 * 96 * 48, CellMesh.Rows.of(checker), sectionKeys(bounds));
        assertTrue(result.mesh().tooDetailed());
        assertTrue(result.mesh().sections().isEmpty());
    }

    @Test
    void theCapsBoundTheVertexMemory() {
        System.out.printf(Locale.ROOT, "caps: at most %.1f MB of vertices%n", maxBytes() / 1e6);
        // An edge is 4 vertices of 20 bytes: BufferBuilder writes each line vertex twice in LINES mode.
        assertEquals(80, CellMesh.EDGE_BYTES);
        assertEquals(262_144L * 64 + 196_608L * 80, maxBytes());
        assertTrue(maxBytes() <= 33_000_000L);
    }
}
