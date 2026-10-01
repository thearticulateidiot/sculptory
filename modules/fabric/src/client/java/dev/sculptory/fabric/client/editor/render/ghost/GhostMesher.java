package dev.sculptory.fabric.client.editor.render.ghost;

import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.fabric.SculptoryMod;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.LocalRandom;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

/**
 * Meshes ghost sections on at most {@value #WORKERS} background threads, the way vanilla's section builder does:
 * {@code BlockRenderManager.renderBlock} for model blocks and {@code renderFluid} for fluids (waterlogged blocks
 * included), all into one {@code BufferBuilder} in the terrain vertex format
 * ({@code POSITION_COLOR_TEXTURE_LIGHT_NORMAL}, quads). Vertices are relative to the section's minimum corner, as
 * vanilla's section meshes are ({@code renderFluid} writes {@code pos & 15} itself).
 *
 * <p>Blocks drawn by block-entity renderers only ({@code ENTITYBLOCK_ANIMATED}: chests, beds, banners, skulls...)
 * have no model and do not appear. Random model variants use the local position, so they may differ from the final
 * placement.
 *
 * <p>A block whose model throws abandons its section (the vertex buffer may hold half a quad): the section gets an
 * empty mesh and one log line, and is not retried until its content changes.
 *
 * <p>Signatures checked with javap against yarn 1.21.1+build.3: {@code renderBlock(BlockState, BlockPos,
 * BlockRenderView, MatrixStack, VertexConsumer, boolean, Random)}, {@code renderFluid(BlockPos, BlockRenderView,
 * VertexConsumer, BlockState, FluidState)}, {@code new BufferBuilder(BufferAllocator, DrawMode, VertexFormat)},
 * {@code BufferBuilder.endNullable()}.
 */
final class GhostMesher implements AutoCloseable {
    static final int WORKERS = 2;
    /**
     * The vertex format ghost meshes are built in and drawn with. Iris widens new block-format buffers to its own
     * terrain format while a shader pack is on (on any thread); {@link GhostMeshCache} drops such meshes.
     */
    static final VertexFormat FORMAT = VertexFormats.POSITION_COLOR_TEXTURE_LIGHT_NORMAL;
    private static final int MIN_BUFFER_BYTES = 16 * 1024;
    private static final int MAX_INITIAL_BUFFER_BYTES = 2 * 1024 * 1024;
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private final ThreadPoolExecutor pool;

    GhostMesher() {
        AtomicInteger threads = new AtomicInteger();
        pool = new ThreadPoolExecutor(WORKERS, WORKERS, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "Sculptory ghost mesher " + threads.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        pool.allowCoreThreadTimeOut(true);
    }

    /**
     * One section to mesh.
     *
     * @param generation the cache generation at submission; results of older generations are discarded
     * @param hash the section's content hash
     * @param lightKey 0 for full-bright, else the placement's {@link GhostMapping#lightKey()}
     * @param neighbourhood the 3×3×3 sections around it (see {@link GhostBlockView#neighbourIndex})
     */
    record Job(
            long generation,
            long key,
            long hash,
            long lightKey,
            GhostSection[] neighbourhood,
            ClientWorld world,
            GhostPlacement.LightMode lightMode,
            GhostMapping mapping) {
        GhostSection section() {
            return neighbourhood[GhostBlockView.neighbourIndex(0, 0, 0)];
        }
    }

    /**
     * A finished job. Owns native memory until {@link #close()}d (or until the vertex buffer upload consumed
     * {@code built}, after which only the allocator is left to close).
     *
     * @param built the vertices, or null when the section produced none (or failed)
     * @param vertexBytes size of the vertex data
     * @param failed a block threw while meshing
     */
    record Result(Job job, @Nullable BuiltBuffer built, @Nullable BufferAllocator allocator, long vertexBytes, boolean failed)
            implements AutoCloseable {
        /** Frees the vertex data and the allocator; safe on any thread, and twice. */
        @Override
        public void close() {
            if (built != null) {
                built.close();
            }
            if (allocator != null) {
                allocator.close();
            }
        }
    }

    /** Meshes {@code job} on a worker and hands the result to {@code sink} (on that worker). */
    void submit(Job job, Consumer<Result> sink) {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(sink, "sink");
        try {
            pool.execute(() -> sink.accept(build(job)));
        } catch (RejectedExecutionException shutDown) {
            sink.accept(new Result(job, null, null, 0, true));
        }
    }

    static Result build(Job job) {
        GhostSection section = job.section();
        BufferAllocator allocator = new BufferAllocator(initialBytes(section.blockCount()));
        try {
            BuiltBuffer built = mesh(job, section, allocator);
            if (built == null) {
                allocator.close();
                return new Result(job, null, null, 0, false);
            }
            BuiltBuffer.DrawParameters drawn = built.getDrawParameters();
            long bytes = (long) drawn.vertexCount() * drawn.format().getVertexSizeByte();
            return new Result(job, built, allocator, bytes, false);
        } catch (Throwable failure) {
            allocator.close();
            if (WARNED.compareAndSet(false, true)) {
                SculptoryMod.LOG.warn("Sculptory: could not mesh a ghost preview section {} (further failures are not logged)",
                        section, failure);
            }
            return new Result(job, null, null, 0, true);
        }
    }

    private static @Nullable BuiltBuffer mesh(Job job, GhostSection section, BufferAllocator allocator) {
        BlockRenderManager blocks = MinecraftClient.getInstance().getBlockRenderManager();
        GhostBlockView view = new GhostBlockView(job.neighbourhood(), section.sectionX(), section.sectionY(), section.sectionZ(),
                job.world(), job.lightMode(), job.mapping());
        BufferBuilder builder = new BufferBuilder(allocator, VertexFormat.DrawMode.QUADS, FORMAT);
        MatrixStack matrices = new MatrixStack();
        Random random = new LocalRandom(0L);
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int baseX = section.sectionX() << 4;
        int baseY = section.sectionY() << 4;
        int baseZ = section.sectionZ() << 4;
        for (int index = 0; index < SectionBuffer.SIZE; index++) {
            int handle = section.handle(index);
            if (handle < 0) {
                continue;
            }
            BlockState state = GhostStates.state(handle);
            if (state.isAir()) {
                continue;
            }
            int x = SectionBuffer.localX(index);
            int y = SectionBuffer.localY(index);
            int z = SectionBuffer.localZ(index);
            pos.set(baseX + x, baseY + y, baseZ + z);
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                blocks.renderFluid(pos, view, builder, state, fluid);
            }
            if (state.getRenderType() == BlockRenderType.MODEL) {
                matrices.push();
                matrices.translate((float) x, (float) y, (float) z);
                blocks.renderBlock(state, pos, view, matrices, builder, true, random);
                matrices.pop();
            }
        }
        return builder.endNullable();
    }

    /** A first guess at the buffer size; the allocator grows as needed. */
    private static int initialBytes(int blocks) {
        long guess = blocks * GhostBudget.ESTIMATED_BYTES_PER_BLOCK;
        return (int) Math.max(MIN_BUFFER_BYTES, Math.min(MAX_INITIAL_BUFFER_BYTES, guess));
    }

    /** Stops the workers; jobs still queued are dropped (their sinks are not called). */
    @Override
    public void close() {
        pool.shutdownNow();
    }
}
