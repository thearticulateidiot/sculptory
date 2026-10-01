package dev.sculptory.fabric.client.editor.brush;

import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.world.TerrainProbe;
import java.util.Objects;

/**
 * {@link TerrainProbe} over a core {@link WorldReader}, with the brush kernel's notion of ground: a
 * terrain-solid state without a block entity. The brush cursor therefore hugs the same surface the brush
 * will sculpt. Cells in unloaded chunks are not ground. Equal to another probe over the same reader
 * instance, so the surface sampler's cache holds while the reader does. Not thread-safe.
 */
public final class ReaderTerrainProbe implements TerrainProbe {
    private final WorldReader world;
    private int loadedCx = Integer.MIN_VALUE;
    private int loadedCz = Integer.MIN_VALUE;
    private boolean loaded;

    public ReaderTerrainProbe(WorldReader world) {
        this.world = Objects.requireNonNull(world);
    }

    @Override
    public boolean isTerrainSolid(int x, int y, int z) {
        if (y < world.bottomY() || y >= world.topYExclusive() || !loaded(x >> 4, z >> 4)) {
            return false;
        }
        int flags = world.states().flags(world.get(x, y, z));
        return StateFlags.has(flags, StateFlags.TERRAIN_SOLID) && !StateFlags.has(flags, StateFlags.HAS_BLOCK_ENTITY);
    }

    @Override
    public int bottomY() {
        return world.bottomY();
    }

    @Override
    public int topY() {
        return world.topYExclusive();
    }

    private boolean loaded(int cx, int cz) {
        if (cx != loadedCx || cz != loadedCz) {
            loadedCx = cx;
            loadedCz = cz;
            loaded = world.isLoaded(cx, cz);
        }
        return loaded;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ReaderTerrainProbe probe && probe.world == world;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(world);
    }
}
