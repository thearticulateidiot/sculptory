package dev.sculptory.mc.client.editor.render.ghost;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.mc.Sculptory;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * Meshes ghost sections on at most {@value #WORKERS} background threads, the way vanilla's section builder does:
 * {@code BlockRenderDispatcher.renderBatched} for model blocks and {@code renderLiquid} for fluids (waterlogged blocks
 * included), all into one {@code BufferBuilder} in the terrain vertex format
 * ({@code POSITION_COLOR_TEXTURE_LIGHT_NORMAL}, quads). Vertices are relative to the section's minimum corner, as
 * vanilla's section meshes are ({@code renderLiquid} writes {@code pos & 15} itself).
 *
 * <p>Blocks drawn by block-entity renderers only ({@code ENTITYBLOCK_ANIMATED}: chests, beds, banners, skulls...)
 * have no model and do not appear. Random model variants use the local position, so they may differ from the final
 * placement.
 *
 * <p>A block whose model throws abandons its section (the vertex buffer may hold half a quad): the section gets an
 * empty mesh and one log line, and is not retried until its content changes.
 *
 * <p>Signatures checked with javap against Minecraft 1.21.1 (Mojang names): {@code renderBatched(BlockState, BlockPos,
 * BlockAndTintGetter, PoseStack, VertexConsumer, boolean, RandomSource)}, {@code renderLiquid(BlockPos,
 * BlockAndTintGetter, VertexConsumer, BlockState, FluidState)}, {@code new BufferBuilder(ByteBufferBuilder,
 * VertexFormat.Mode, VertexFormat)}, {@code BufferBuilder.build()}.
 */
final class GhostMesher implements AutoCloseable {
    static final int WORKERS = 2;
    /**
     * The vertex format ghost meshes are built in and drawn with. Iris widens new block-format buffers to its own
     * terrain format while a shader pack is on (on any thread); {@link GhostMeshCache} drops such meshes.
     */
    static final VertexFormat FORMAT = DefaultVertexFormat.BLOCK;
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
            ClientLevel world,
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
    record Result(Job job, @Nullable MeshData built, @Nullable ByteBufferBuilder allocator, long vertexBytes, boolean failed)
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
        ByteBufferBuilder allocator = new ByteBufferBuilder(initialBytes(section.blockCount()));
        try {
            MeshData built = mesh(job, section, allocator);
            if (built == null) {
                allocator.close();
                return new Result(job, null, null, 0, false);
            }
            MeshData.DrawState drawn = built.drawState();
            long bytes = (long) drawn.vertexCount() * drawn.format().getVertexSize();
            return new Result(job, built, allocator, bytes, false);
        } catch (Throwable failure) {
            allocator.close();
            if (WARNED.compareAndSet(false, true)) {
                Sculptory.LOG.warn("Sculptory: could not mesh a ghost preview section {} (further failures are not logged)",
                        section, failure);
            }
            return new Result(job, null, null, 0, true);
        }
    }

    private static @Nullable MeshData mesh(Job job, GhostSection section, ByteBufferBuilder allocator) {
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        GhostBlockView view = new GhostBlockView(job.neighbourhood(), section.sectionX(), section.sectionY(), section.sectionZ(),
                job.world(), job.lightMode(), job.mapping());
        BufferBuilder builder = new BufferBuilder(allocator, VertexFormat.Mode.QUADS, FORMAT);
        PoseStack matrices = new PoseStack();
        RandomSource random = new SingleThreadedRandomSource(0L);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
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
                blocks.renderLiquid(pos, view, builder, state, fluid);
            }
            if (state.getRenderShape() == RenderShape.MODEL) {
                matrices.pushPose();
                matrices.translate((float) x, (float) y, (float) z);
                blocks.renderBatched(state, pos, view, matrices, builder, true, random);
                matrices.popPose();
            }
        }
        return builder.build();
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
