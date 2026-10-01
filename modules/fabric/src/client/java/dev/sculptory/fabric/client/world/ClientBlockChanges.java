package dev.sculptory.fabric.client.world;

import dev.sculptory.core.Box;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;

/**
 * A global counter that changes whenever blocks in the client world may have changed, for caches such as the
 * brush cursor's {@code SurfaceSampler}: compare {@link #stamp()} with the value the cache was built at.
 *
 * <p>It is bumped by {@code ClientWorldMixin} for every block the client world changes (server block updates,
 * chunk delta updates, predicted edits and their rollbacks), and here for every chunk load (including a resent
 * chunk replacing one in place) and unload. The stamp is global: any change anywhere invalidates every cache.
 *
 * <p>{@link #watch} counts only the changes inside one box (a chunk load or unload counts when its columns overlap
 * the box), for a preview that goes out of date when the world near it changes.
 */
public final class ClientBlockChanges {
    private static final AtomicLong STAMP = new AtomicLong();
    private static final CopyOnWriteArrayList<Watch> WATCHES = new CopyOnWriteArrayList<>();

    private ClientBlockChanges() {}

    /** The changes seen inside one box since {@link #watch}; {@link #close} when done. */
    public static final class Watch implements AutoCloseable {
        private final Box box;
        private final AtomicLong changes = new AtomicLong();

        private Watch(Box box) {
            this.box = box;
        }

        public Box box() {
            return box;
        }

        /** How many changes have hit the box so far. */
        public long changes() {
            return changes.get();
        }

        @Override
        public void close() {
            WATCHES.remove(this);
        }

        void block(int x, int y, int z) {
            if (box.contains(x, y, z)) changes.incrementAndGet();
        }

        void chunk(int chunkX, int chunkZ) {
            int minX = chunkX << 4, minZ = chunkZ << 4;
            boolean overlaps = minX <= box.max().x() && minX + 15 >= box.min().x()
                    && minZ <= box.max().z() && minZ + 15 >= box.min().z();
            if (overlaps) changes.incrementAndGet();
        }
    }

    /** The current stamp; a different value means blocks may have changed since. */
    public static long stamp() {
        return STAMP.get();
    }

    /** Records a change somewhere. */
    public static void bump() {
        STAMP.incrementAndGet();
    }

    /** Records a change of the block at (x, y, z). */
    public static void bump(int x, int y, int z) {
        STAMP.incrementAndGet();
        for (Watch watch : WATCHES) watch.block(x, y, z);
    }

    /** Records a chunk load or unload. */
    public static void bumpChunk(int chunkX, int chunkZ) {
        STAMP.incrementAndGet();
        for (Watch watch : WATCHES) watch.chunk(chunkX, chunkZ);
    }

    /** Starts counting the changes inside {@code box}. */
    public static Watch watch(Box box) {
        Watch watch = new Watch(Objects.requireNonNull(box));
        WATCHES.add(watch);
        return watch;
    }

    /** Registers the chunk load and unload hooks. Called once from the client initializer. */
    public static void install() {
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> bumpChunk(chunk.getPos().x, chunk.getPos().z));
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> bumpChunk(chunk.getPos().x, chunk.getPos().z));
    }
}
