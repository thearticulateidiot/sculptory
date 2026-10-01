package dev.sculptory.fabric.client.editor.tool;

import dev.sculptory.core.BlockPos;
import java.util.Objects;

/**
 * Where the cursor ray met the world. On a miss, {@code pos} and {@code face} are {@code null} and the hit
 * vector is the end of the ray.
 */
public record WorldCursor(BlockPos pos, Face face, double hitX, double hitY, double hitZ, boolean missed) {
    public enum Face {
        DOWN(0, -1, 0),
        UP(0, 1, 0),
        NORTH(0, 0, -1),
        SOUTH(0, 0, 1),
        WEST(-1, 0, 0),
        EAST(1, 0, 0);

        private final int dx, dy, dz;

        Face(int dx, int dy, int dz) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
        }

        public int dx() {
            return dx;
        }

        public int dy() {
            return dy;
        }

        public int dz() {
            return dz;
        }
    }

    public WorldCursor {
        if (!missed) {
            Objects.requireNonNull(pos);
            Objects.requireNonNull(face);
        }
    }

    public static WorldCursor miss(double endX, double endY, double endZ) {
        return new WorldCursor(null, null, endX, endY, endZ, true);
    }

    /** The cell against the hit face, where a placed block would go. Only for hits. */
    public BlockPos adjacent() {
        if (missed) throw new IllegalStateException("The ray missed");
        return pos.offset(face.dx(), face.dy(), face.dz());
    }
}
