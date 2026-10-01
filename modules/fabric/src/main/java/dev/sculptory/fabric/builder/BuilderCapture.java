package dev.sculptory.fabric.builder;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.fabric.world.FabricTile;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.Objects;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One builder-mode action on one world: while it is open, every change the
 * opening thread makes to that world through {@code World.setBlockState} (all of vanilla's block changes go through it:
 * the placement, a door's upper half, a fence joining its neighbour, water left by a broken waterlogged block) is
 * noted with the cell's state and block entity from before its first change, so {@link #finish} can give each changed
 * cell's first and last state: an exact record for the history, whatever vanilla did.
 *
 * <p>With {@code keepShape} it also makes those changes physics-free: {@code BuilderCaptureMixin} turns every such
 * {@code setBlockState} into a {@code FORCE_STATE} write without neighbour notifications, so nothing around a placed or
 * broken block reacts (fences don't join, plants float). The server pairs it with an {@code EditScope}, which cancels
 * the blocks' own add and replace callbacks (sand stays put, nothing drops).
 *
 * <p>One capture at a time per side: the server thread's for a server world and the client thread's (its prediction)
 * for the client world, both in one JVM in singleplayer. Captures do not nest. The hooks cost two volatile reads per
 * block change while none is open.
 */
public final class BuilderCapture implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    private static volatile BuilderCapture serverSide;
    private static volatile BuilderCapture clientSide;

    /** A cell as it was before the action first changed it. */
    private record Before(int state, BlockEntityData tile) {}

    /** Where {@link #finish} sends each changed cell. */
    @FunctionalInterface
    public interface Sink {
        void record(int x, int y, int z, int before, BlockEntityData beforeTile, int after, BlockEntityData afterTile);
    }

    private final World world;
    private final Thread thread;
    private final boolean keepShape;
    private final boolean recording;
    private final Long2ObjectLinkedOpenHashMap<Before> touched = new Long2ObjectLinkedOpenHashMap<>();
    private boolean captureFailureLogged;
    private boolean closed;

    private BuilderCapture(World world, boolean keepShape, boolean recording) {
        this.world = world;
        this.thread = Thread.currentThread();
        this.keepShape = keepShape;
        this.recording = recording;
    }

    /**
     * Opens a capture of {@code world} on the current thread.
     *
     * @param keepShape make the action's block changes physics-free
     * @param recording note the changed cells for {@link #finish} (the server); the client's prediction only needs
     *     {@code keepShape}
     * @throws IllegalStateException if a capture of that side is already open
     */
    public static BuilderCapture open(World world, boolean keepShape, boolean recording) {
        Objects.requireNonNull(world);
        BuilderCapture capture = new BuilderCapture(world, keepShape, recording);
        synchronized (BuilderCapture.class) {
            if (world.isClient) {
                if (clientSide != null) throw new IllegalStateException("A client builder capture is already open");
                clientSide = capture;
            } else {
                if (serverSide != null) throw new IllegalStateException("A server builder capture is already open");
                serverSide = capture;
            }
        }
        return capture;
    }

    public World world() {
        return world;
    }

    public boolean keepShape() {
        return keepShape;
    }

    /**
     * Notes the cell as it is now, before a change the hook does not see (clearing a container before it is broken, so
     * the record keeps its contents). A cell already noted keeps its first state.
     */
    public void touch(BlockPos pos) {
        if (!recording || closed) return;
        long key = pos.asLong();
        if (touched.containsKey(key)) return;
        BlockState old = world.getBlockState(pos);
        touched.put(key, new Before(Block.getRawIdFromState(old), old.hasBlockEntity() ? capture(pos) : null));
    }

    /** Cells noted so far (changed or not). */
    public int touchedCells() {
        return touched.size();
    }

    /**
     * Closes the capture (if still open) and gives {@code sink} every noted cell whose state or block entity differs from
     * before the action, in the order they were first changed. Returns how many it gave.
     */
    public int finish(Sink sink) {
        close();
        int changed = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (Long2ObjectMap.Entry<Before> entry : touched.long2ObjectEntrySet()) {
            pos.set(entry.getLongKey());
            Before before = entry.getValue();
            BlockState now = world.getBlockState(pos);
            int after = Block.getRawIdFromState(now);
            BlockEntityData tile = now.hasBlockEntity() ? capture(pos) : null;
            if (after == before.state() && sameTile(before.tile(), tile)) continue;
            sink.record(pos.getX(), pos.getY(), pos.getZ(), before.state(), before.tile(), after, tile);
            changed++;
        }
        return changed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        synchronized (BuilderCapture.class) {
            if (serverSide == this) serverSide = null;
            if (clientSide == this) clientSide = null;
        }
    }

    // ============================================================================================ hooks (mixin)

    /** {@code World.setBlockState}, before it changes anything: notes the cell's first state. */
    public static void beforeSetBlockState(World world, BlockPos pos, BlockState state) {
        BuilderCapture capture = active(world);
        if (capture == null || !capture.recording) return;
        long key = pos.asLong();
        if (capture.touched.containsKey(key)) return;
        BlockState old = world.getBlockState(pos);
        if (old == state) return;
        capture.touched.put(key, new Before(Block.getRawIdFromState(old), old.hasBlockEntity() ? capture.capture(pos) : null));
    }

    /**
     * The flags of a {@code World.setBlockState} made inside a keep-shape capture: {@code FORCE_STATE} and no neighbour
     * notification (listeners, and so clients, still hear of the change). Other calls keep theirs.
     */
    public static int flags(World world, int flags) {
        BuilderCapture capture = active(world);
        if (capture == null || !capture.keepShape) return flags;
        return (flags | Block.FORCE_STATE) & ~Block.NOTIFY_NEIGHBORS;
    }

    /** The update depth of such a call: 0, so no shape update ever runs from it. */
    public static int depth(World world, int maxUpdateDepth) {
        BuilderCapture capture = active(world);
        return capture == null || !capture.keepShape ? maxUpdateDepth : 0;
    }

    private static BuilderCapture active(World world) {
        BuilderCapture server = serverSide;
        BuilderCapture client = clientSide;
        if (server == null && client == null) return null;
        BuilderCapture capture = world.isClient ? client : server;
        if (capture == null || capture.world != world || Thread.currentThread() != capture.thread) return null;
        return capture;
    }

    /** The block entity at {@code pos}, captured; a block entity that fails to save is logged once and taken as none. */
    private BlockEntityData capture(BlockPos pos) {
        try {
            BlockEntity entity = world.getWorldChunk(pos).getBlockEntity(pos, WorldChunk.CreationType.CHECK);
            return entity == null || entity.isRemoved() ? null : FabricTile.capture(entity, world.getRegistryManager());
        } catch (RuntimeException e) {
            if (!captureFailureLogged) {
                captureFailureLogged = true;
                LOG.warn("Sculptory: a block entity at {} could not be saved for the history; it is taken as none",
                        pos.toShortString(), e);
            }
            return null;
        }
    }

    private static boolean sameTile(BlockEntityData a, BlockEntityData b) {
        if (a == null || b == null) return a == b;
        return a.sameContent(b);
    }
}
