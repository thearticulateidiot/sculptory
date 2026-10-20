package dev.sculptory.fabric.client.editor.world;

import dev.sculptory.fabric.client.editor.tool.RayOverlay;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

/**
 * Casts cursor rays into the client world with {@code world.raycast(new RaycastContext(...))}.
 *
 * <p>Range is {@code min(512, (renderDistance + 1) * 16)} blocks. {@link Mode#TERRAIN} uses collision
 * shapes (grass and flowers are ignored), {@link Mode#BLOCK} uses outline shapes. Fluids are ignored
 * unless {@link Fluids#ANY} is chosen ("Aim at water and lava"): the ray then also stops where it enters
 * water or lava (see {@link #raycastEnteringFluids}). With the tool's {@link RayOverlay} (a Shape brush press) the ray
 * sees the world through an {@link OverlaidBlockView}.
 *
 * <p>Results are cached for the current frame, keyed by the ray (which is a function of cursor
 * position and camera pose), mode, fluid handling, range, world and entity, so the tool, the overlay
 * and the HUD readout can ask for the same pick without recasting. Render thread only.
 */
public final class WorldRaycaster {
    public static final double MAX_RANGE = 512.0;
    private static final int CACHE_SIZE = 4;
    /** How far before a fluid surface hit (in blocks, back along the ray) the ray must still be outside fluid. */
    private static final double SURFACE_EPSILON = 1.0e-4;

    /** Which block shapes stop the ray. */
    public enum Mode {
        /** Terrain tools: collision shapes, so grass, flowers and other plants are ignored. */
        TERRAIN,
        /** Block tools: outline shapes, so every targetable block is hit. */
        BLOCK;

        ClipContext.Block shapeType() {
            return this == TERRAIN ? ClipContext.Block.COLLIDER : ClipContext.Block.OUTLINE;
        }
    }

    /** Whether fluids stop the ray. */
    public enum Fluids {
        /** Rays pass through all fluids to the blocks beneath (default). */
        NONE,
        /**
         * Rays also stop where they enter a fluid: water or lava, source or flowing (the fluids vanilla's
         * {@code FluidHandling.ANY} stops at), at the fluid's surface. The fluid a ray starts in is passed through.
         * See {@link WorldRaycaster#raycastEnteringFluids}.
         */
        ANY;

        /** {@link #ANY} when the editor aims at water and lava, else {@link #NONE}. */
        public static Fluids of(boolean aimAtFluids) {
            return aimAtFluids ? ANY : NONE;
        }

        ClipContext.Fluid handling() {
            return this == NONE ? ClipContext.Fluid.NONE : ClipContext.Fluid.ANY;
        }
    }

    /**
     * The outcome of a pick.
     *
     * @param kind whether a block (or, with {@link Fluids#ANY}, a fluid surface), the fallback plane or nothing
     *     was hit
     * @param blockPos the hit block (for a fluid surface, the block holding the fluid); for {@link Kind#PLANE} the
     *     block whose face lies on the plane; for {@link Kind#MISS} the block at the end of the ray
     * @param face the face that was hit (for a miss, the face the ray would have entered)
     * @param pos the exact hit point (for a miss, the end of the ray)
     * @param distance distance from the ray origin to {@code pos}
     */
    public record Hit(Kind kind, BlockPos blockPos, Direction face, Vec3 pos, double distance) {
        public enum Kind { BLOCK, PLANE, MISS }

        /** True unless a real block was hit. */
        public boolean missed() {
            return kind != Kind.BLOCK;
        }
    }

    private record Key(BlockGetter world, @Nullable Entity entity, Ray ray, Mode mode, Fluids fluids, double maxDistance) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && key.world == world
                    && key.entity == entity
                    && key.ray.equals(ray)
                    && key.mode == mode
                    && key.fluids == fluids
                    && Double.compare(key.maxDistance, maxDistance) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(System.identityHashCode(world), System.identityHashCode(entity), ray, mode, fluids, maxDistance);
        }
    }

    private final Key[] cachedKeys = new Key[CACHE_SIZE];
    private final Hit[] cachedHits = new Hit[CACHE_SIZE];
    private long cacheFrame = Long.MIN_VALUE;
    private int nextSlot;
    /** The world with the last overlay picked through, or null. */
    private OverlaidBlockView overlaid;

    /** Picking range for a render distance in chunks: {@code min(512, (chunks + 1) * 16)}. */
    public static double maxDistance(int renderDistanceChunks) {
        return Math.min(MAX_RANGE, (Math.max(renderDistanceChunks, 0) + 1) * 16.0);
    }

    /**
     * Picks in the client world with the camera entity's shape context and the clamped view distance, seeing the cells
     * of {@code overlay} (or none, with {@code null}) as it says ({@link OverlaidBlockView}). Returns a miss when no
     * world is loaded.
     */
    public Hit pick(CameraSnapshot camera, Ray ray, Mode mode, Fluids fluids, @Nullable RayOverlay overlay) {
        Minecraft client = Minecraft.getInstance();
        double range = maxDistance(client.options.getEffectiveRenderDistance());
        if (client.level == null) {
            return miss(ray, range);
        }
        Entity entity = client.getCameraEntity() != null ? client.getCameraEntity() : client.player;
        return raycast(view(client.level, overlay), entity, ray, mode, fluids, range, camera.frame());
    }

    /**
     * {@code world} as {@code overlay} shows it: the same view while both stay the same (one press), so picks of one
     * frame share the cache.
     */
    private BlockGetter view(BlockGetter world, @Nullable RayOverlay overlay) {
        if (overlay == null) {
            overlaid = null;
            return world;
        }
        if (overlaid == null || !overlaid.shows(world, overlay)) {
            overlaid = new OverlaidBlockView(world, overlay);
        }
        return overlaid;
    }

    /**
     * Casts {@code ray} up to {@code maxDistance} blocks.
     *
     * @param entity supplies the collision context; null uses an entity-less context
     * @param frame the current frame counter; the cache is cleared when it changes
     */
    public Hit raycast(
            BlockGetter world, @Nullable Entity entity, Ray ray, Mode mode, Fluids fluids, double maxDistance, long frame) {
        if (frame != cacheFrame) {
            invalidate();
            cacheFrame = frame;
        }
        Key key = new Key(world, entity, ray, mode, fluids, maxDistance);
        for (int i = 0; i < CACHE_SIZE; i++) {
            if (key.equals(cachedKeys[i])) {
                return cachedHits[i];
            }
        }
        Hit hit = cast(world, entity, ray, mode, fluids, maxDistance);
        cachedKeys[nextSlot] = key;
        cachedHits[nextSlot] = hit;
        nextSlot = (nextSlot + 1) % CACHE_SIZE;
        return hit;
    }

    /** Drops cached picks, e.g. after the client applied a block change mid-frame. */
    public void invalidate() {
        Arrays.fill(cachedKeys, null);
        Arrays.fill(cachedHits, null);
        nextSlot = 0;
    }

    private static Hit cast(BlockGetter world, @Nullable Entity entity, Ray ray, Mode mode, Fluids fluids, double maxDistance) {
        Vec3 start = new Vec3(ray.originX(), ray.originY(), ray.originZ());
        Vec3 end = new Vec3(ray.pointX(maxDistance), ray.pointY(maxDistance), ray.pointZ(maxDistance));
        ClipContext context = entity != null
                ? new ClipContext(start, end, mode.shapeType(), fluids.handling(), entity)
                : new ClipContext(start, end, mode.shapeType(), fluids.handling(), CollisionContext.empty());
        BlockHitResult result = fluids == Fluids.NONE ? world.clip(context) : raycastEnteringFluids(world, context);
        if (result.getType() == HitResult.Type.MISS) {
            return new Hit(Hit.Kind.MISS, result.getBlockPos(), result.getDirection(), result.getLocation(), maxDistance);
        }
        return new Hit(Hit.Kind.BLOCK, result.getBlockPos(), result.getDirection(), result.getLocation(), start.distanceTo(result.getLocation()));
    }

    /**
     * {@link BlockGetter#clip(ClipContext)} that also stops where the ray enters a fluid from outside (the
     * context's own fluid handling is not used). Per block, as in vanilla, the nearer of the block shape hit and the
     * fluid surface hit wins, and block shapes are hit exactly as vanilla hits them.
     *
     * <p>Vanilla's {@code FluidHandling.ANY} differs in three ways this avoids: it stops in the fluid the ray starts
     * in (a camera under water would pick its own block, or a point up to half a block ahead of it), at the face
     * between two fluid blocks, and at a fluid shape {@code FlowableFluid} caches per fluid state (so a source block
     * reads as 8/9 or a whole block high depending on which block asked first). Here the ray passes through the
     * fluid around the camera until it leaves it, and stops at the next surface it enters, at that block's height.
     */
    static BlockHitResult raycastEnteringFluids(BlockGetter world, ClipContext context) {
        Vec3 start = context.getFrom();
        Vec3 end = context.getTo();
        return BlockGetter.traverseBlocks(start, end, context, (ctx, pos) -> {
            BlockState state = world.getBlockState(pos);
            BlockHitResult block = world.clipWithInteractionOverride(start, end, pos, ctx.getBlockShape(state, world, pos), state);
            BlockHitResult fluid = fluidEntry(world, pos, start, end);
            double blockDistance = block == null ? Double.MAX_VALUE : start.distanceToSqr(block.getLocation());
            double fluidDistance = fluid == null ? Double.MAX_VALUE : start.distanceToSqr(fluid.getLocation());
            return blockDistance <= fluidDistance ? block : fluid;
        }, ctx -> {
            Vec3 back = ctx.getFrom().subtract(ctx.getTo());
            return BlockHitResult.miss(ctx.getTo(), Direction.getNearest(back.x, back.y, back.z),
                    BlockPos.containing(ctx.getTo()));
        });
    }

    /**
     * Where the ray enters the fluid in {@code pos} from outside any fluid, or null. The fluid fills its block up to
     * {@code FluidState.getHeight(world, pos)}: 8/9 for a source, less for flowing fluid, the whole block under more
     * of the same fluid. Just before its surface the ray must be outside every fluid, so a face between two fluid
     * blocks is no surface, and neither is the fluid the ray starts in (the ray only leaves it).
     */
    @Nullable
    private static BlockHitResult fluidEntry(BlockGetter world, BlockPos pos, Vec3 start, Vec3 end) {
        FluidState fluid = world.getFluidState(pos);
        if (fluid.isEmpty()) {
            return null;
        }
        // Box.raycast finds only faces the ray enters from outside the box, never a start inside it.
        AABB body = new AABB(0, 0, 0, 1, fluid.getHeight(world, pos), 1);
        BlockHitResult hit = AABB.clip(List.of(body), start, end, pos.immutable());
        if (hit == null) {
            return null;
        }
        Vec3 before = hit.getLocation().subtract(end.subtract(start).normalize().scale(SURFACE_EPSILON));
        return inFluid(world, before) ? null : hit;
    }

    /** Whether a point lies in the fluid of its block, below that fluid's surface. */
    static boolean inFluid(BlockGetter world, Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        FluidState fluid = world.getFluidState(pos);
        return !fluid.isEmpty() && point.y - pos.getY() < fluid.getHeight(world, pos);
    }

    /**
     * Plane fallback for a miss: when {@code hit} missed, intersects the ray with the horizontal plane
     * {@code y = planeY} (e.g. the last hit's height). Returns {@code hit} unchanged when it hit a block
     * or when the plane is parallel, behind the origin or beyond {@code maxDistance}.
     */
    public static Hit withPlaneFallback(Hit hit, Ray ray, double planeY, double maxDistance) {
        if (!hit.missed()) {
            return hit;
        }
        return planeHit(ray, planeY, maxDistance).orElse(hit);
    }

    /**
     * Intersects the ray with the plane {@code y = planeY}. Coming from above, the result is the top
     * face of the block just below the plane; from below, the bottom face of the block above it.
     */
    public static Optional<Hit> planeHit(Ray ray, double planeY, double maxDistance) {
        double t = ray.distanceToHorizontalPlane(planeY);
        if (Double.isNaN(t) || t > maxDistance) {
            return Optional.empty();
        }
        double x = ray.pointX(t);
        double z = ray.pointZ(t);
        boolean fromAbove = ray.dirY() < 0;
        int blockY = fromAbove ? (int) Math.ceil(planeY) - 1 : (int) Math.floor(planeY);
        BlockPos pos = new BlockPos((int) Math.floor(x), blockY, (int) Math.floor(z));
        return Optional.of(new Hit(Hit.Kind.PLANE, pos, fromAbove ? Direction.UP : Direction.DOWN, new Vec3(x, planeY, z), t));
    }

    private static Hit miss(Ray ray, double maxDistance) {
        Vec3 end = new Vec3(ray.pointX(maxDistance), ray.pointY(maxDistance), ray.pointZ(maxDistance));
        Direction entered = Direction.getNearest(-ray.dirX(), -ray.dirY(), -ray.dirZ());
        return new Hit(Hit.Kind.MISS, BlockPos.containing(end), entered, end, maxDistance);
    }
}
