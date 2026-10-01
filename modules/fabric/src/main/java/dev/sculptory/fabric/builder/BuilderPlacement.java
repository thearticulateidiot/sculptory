package dev.sculptory.fabric.builder;

import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.mixin.BlockItemInvoker;
import dev.sculptory.fabric.mixin.VerticallyAttachableBlockItemAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.advancement.criterion.Criteria;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.OperatorBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.OperatorOnlyBlockItem;
import net.minecraft.item.VerticallyAttachableBlockItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;

/**
 * How builder mode places and breaks one block, the same on the server (the
 * write, inside a {@link BuilderCapture}) and on the client (its prediction): vanilla's own steps, so doors get their
 * upper half, beds their head, signs their editor and named blocks their name, with the powers' changes.
 *
 * <ul>
 *   <li><b>State.</b> The held item's placement state, as vanilla computes it from the player's look and the hit
 *       ({@link #decide}). <b>Replace</b> places into the clicked cell itself and keeps the old block's facing, half and
 *       the like where the new block has them ({@link #keepOrientation}). <b>Force place</b> takes the block's own
 *       placement state, or its default state, where the game refuses (no support, an entity in the way;
 *       {@link #forcedState}); a wall-and-floor item (a torch) on a block's side becomes its wall block facing out.</li>
 *   <li><b>Placing</b> ({@link #place}) follows {@code BlockItem.place(ItemPlacementContext)} after its state is
 *       chosen: the item's own {@code place}, block-entity data and components from the item, {@code onPlaced}, the
 *       placed-block advancement, the sound and the game event. Creative players keep their items.</li>
 *   <li><b>Breaking</b> ({@link #breakBlock}) follows a creative player's break: {@code onBreak} (the other half of a
 *       door, bed or tall plant goes too), then {@code removeBlock} (a waterlogged block leaves its water) and
 *       {@code onBroken}. Nothing drops: the server empties containers first.</li>
 * </ul>
 */
public final class BuilderPlacement {
    /** Properties Replace keeps from the block it replaces, where the new block has them with that value. */
    static final Set<String> ORIENTATION = Set.of("facing", "axis", "half", "rotation", "face", "hinge", "attachment",
            "orientation", "shape", "vertical_direction", "hanging");

    private BuilderPlacement() {}

    /** Why a placement is refused. */
    public enum Refusal {
        /** The hand holds no block. */
        NOT_A_BLOCK,
        /** The block is not enabled in this world (an experimental feature). */
        DISABLED_BLOCK,
        /** The cell holds a block the new one may not replace. */
        OCCUPIED,
        /** The game refuses the block there (no support, an entity in the way): Force place overrides it. */
        GAME_REFUSES
    }

    /** A placement: the item, its context (the cell is {@code context.getBlockPos()}) and the state chosen. */
    public record Decision(BlockItem item, ItemPlacementContext context, BlockState state) {
        public Decision {
            Objects.requireNonNull(item);
            Objects.requireNonNull(context);
            Objects.requireNonNull(state);
        }

        public BlockPos pos() {
            return context.getBlockPos();
        }
    }

    /** A decision, or why there is none. */
    public record Outcome(Decision decision, Refusal refusal) {
        static Outcome refused(Refusal refusal) {
            return new Outcome(null, refusal);
        }
    }

    /**
     * What a right-click of {@code hand}'s block at {@code hit} places: into the cell next to the hit face, or into the
     * hit cell when it is replaceable (air aimed at by Place in air is), or with {@code replace} into the hit cell
     * whatever holds it.
     */
    public static Outcome decide(World world, PlayerEntity player, Hand hand, BlockHitResult hit, boolean replace,
                                 boolean force) {
        ItemStack stack = player.getStackInHand(hand);
        if (!(stack.getItem() instanceof BlockItem item)) return Outcome.refused(Refusal.NOT_A_BLOCK);
        if (!item.getBlock().isEnabled(world.getEnabledFeatures())) return Outcome.refused(Refusal.DISABLED_BLOCK);
        // Operator blocks (command, structure, jigsaw) stay with level-2 creative ops, Force place or not.
        if (item instanceof OperatorOnlyBlockItem && !player.isCreativeLevelTwoOp()) return Outcome.refused(Refusal.GAME_REFUSES);
        ItemPlacementContext context = replace
                ? new BuilderPlacementContext(world, player, hand, stack, hit)
                : new ItemPlacementContext(world, player, hand, stack, hit);
        if (!context.canPlace()) return Outcome.refused(Refusal.OCCUPIED);
        ItemPlacementContext placing = item.getPlacementContext(context);
        if (placing == null) return Outcome.refused(Refusal.GAME_REFUSES);
        BlockState state = ((BlockItemInvoker) item).sculptory$getPlacementState(placing);
        if (state == null && force) state = forcedState(item, placing);
        if (state == null) return Outcome.refused(Refusal.GAME_REFUSES);
        if (replace) state = keepOrientation(world.getBlockState(placing.getBlockPos()), state);
        return new Outcome(new Decision(item, placing, state), null);
    }

    /**
     * {@code placed} with the orientation properties ({@link #ORIENTATION}) of {@code old} that it has with a valid value
     * of the same name; a slab keeps the old slab's type (top, bottom, double) only from a slab.
     */
    public static BlockState keepOrientation(BlockState old, BlockState placed) {
        BlockState result = placed;
        for (Property<?> property : placed.getProperties()) {
            String name = property.getName();
            if (!ORIENTATION.contains(name)) continue;
            Property<?> source = old.getBlock().getStateManager().getProperty(name);
            if (source == null) continue;
            result = withValueNamed(result, property, nameOf(old, source));
        }
        if (placed.getBlock() instanceof SlabBlock && old.getBlock() instanceof SlabBlock) {
            result = result.with(Properties.SLAB_TYPE, old.get(Properties.SLAB_TYPE));
        }
        return result;
    }

    private static <T extends Comparable<T>> String nameOf(BlockState state, Property<T> property) {
        return property.name(state.get(property));
    }

    private static <T extends Comparable<T>> BlockState withValueNamed(BlockState state, Property<T> property,
                                                                       String value) {
        return property.parse(value).map(parsed -> state.with(property, parsed)).orElse(state);
    }

    /**
     * The state Force place puts where the game refuses: a wall-and-floor item (a torch) on a block's side becomes its
     * wall block facing out of that side; otherwise the block's own placement state, without the item's checks, else its
     * default state.
     */
    public static BlockState forcedState(BlockItem item, ItemPlacementContext context) {
        if (item instanceof VerticallyAttachableBlockItem attachable && context.getSide().getAxis().isHorizontal()) {
            BlockState wall = ((VerticallyAttachableBlockItemAccessor) attachable).sculptory$wallBlock().getDefaultState();
            if (wall.contains(Properties.HORIZONTAL_FACING)) return wall.with(Properties.HORIZONTAL_FACING, context.getSide());
        }
        BlockState state = null;
        try {
            state = item.getBlock().getPlacementState(context);
        } catch (RuntimeException ignored) {
            // A block whose placement logic cannot cope with the cell: its default state.
        }
        return state != null ? state : item.getBlock().getDefaultState();
    }

    /**
     * Places {@code state} into {@code context}'s cell as {@code BlockItem.place} does once it has a state. With
     * {@code copy} (a mirrored copy) the item's post-placement step is only its block-entity data (a sign's editor opens
     * for the placed original only). Returns whether the item placed it.
     */
    public static boolean place(BlockItem item, ItemPlacementContext context, BlockState state, boolean copy) {
        BlockItemInvoker invoker = (BlockItemInvoker) item;
        if (!invoker.sculptory$place(context, state)) return false;
        BlockPos pos = context.getBlockPos();
        World world = context.getWorld();
        PlayerEntity player = context.getPlayer();
        ItemStack stack = context.getStack();
        BlockState placed = world.getBlockState(pos);
        if (placed.isOf(state.getBlock())) {
            placed = invoker.sculptory$placeFromNbt(pos, world, stack, placed);
            if (copy) {
                BlockItem.writeNbtToBlockEntity(world, player, pos, stack);
            } else {
                invoker.sculptory$postPlacement(pos, world, player, stack, placed);
            }
            BlockItemInvoker.sculptory$copyComponentsToBlockEntity(world, pos, stack);
            placed.getBlock().onPlaced(world, pos, placed, player, stack);
            if (player instanceof ServerPlayerEntity serverPlayer) Criteria.PLACED_BLOCK.trigger(serverPlayer, pos, stack);
        }
        BlockSoundGroup sounds = placed.getSoundGroup();
        world.playSound(player, pos, invoker.sculptory$getPlaceSound(placed), SoundCategory.BLOCKS,
                (sounds.getVolume() + 1.0f) / 2.0f, sounds.getPitch() * 0.8f);
        world.emitGameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Emitter.of(player, placed));
        return true;
    }

    /** Whether a builder break takes the block at {@code pos}: not air or a bare fluid, and operator blocks only for ops. */
    public static boolean breakable(World world, PlayerEntity player, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof FluidBlock) return false;
        return !(state.getBlock() instanceof OperatorBlock) || player.isCreativeLevelTwoOp();
    }

    /**
     * Breaks the block at {@code pos} as a creative player's break does. Returns whether it was removed. The caller
     * empties a container first (nothing may drop: its contents are in the history).
     */
    public static boolean breakBlock(World world, PlayerEntity player, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        BlockState broken = block.onBreak(world, pos, state, player);
        boolean removed = world.removeBlock(pos, false);
        if (removed) block.onBroken(world, pos, broken);
        return removed;
    }

    /** A cell a symmetric action reaches, and the image that maps the original onto it. */
    public record Copy(BlockPos pos, Symmetry.Image image) {}

    /**
     * {@code cell} and its images under {@code symmetry}, the original first, each cell once (a cell on a mirror plane
     * is its own image), leaving out images beyond the int range.
     */
    public static List<Copy> copies(Symmetry symmetry, BlockPos cell) {
        List<Copy> copies = new ArrayList<>(Symmetry.MAX_COPIES);
        for (Symmetry.Image image : symmetry.images()) {
            long x = symmetry.cellX(image, cell.getX(), cell.getZ());
            long z = symmetry.cellZ(image, cell.getX(), cell.getZ());
            if (x != (int) x || z != (int) z) continue;
            BlockPos pos = new BlockPos((int) x, cell.getY(), (int) z);
            boolean seen = false;
            for (Copy copy : copies) seen |= copy.pos().equals(pos);
            if (!seen) copies.add(new Copy(pos, image));
        }
        return copies;
    }

    /** {@code state} turned and mirrored by {@code image}, as the editor's symmetric copies are. */
    public static BlockState imageState(StateSpace states, Symmetry.Image image, BlockState state) {
        if (image == Symmetry.Image.IDENTITY) return state;
        int handle = image.transform().applyToState(states, Block.getRawIdFromState(state));
        return handle < 0 ? state : Block.getStateFromRawId(handle);
    }
}
