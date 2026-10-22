package dev.sculptory.mc.builder;

import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.mc.world.McInternals;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.GameMasterBlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;

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
    public record Decision(BlockItem item, BlockPlaceContext context, BlockState state) {
        public Decision {
            Objects.requireNonNull(item);
            Objects.requireNonNull(context);
            Objects.requireNonNull(state);
        }

        public BlockPos pos() {
            return context.getClickedPos();
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
    public static Outcome decide(Level world, Player player, InteractionHand hand, BlockHitResult hit, boolean replace,
                                 boolean force) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof BlockItem item)) return Outcome.refused(Refusal.NOT_A_BLOCK);
        if (!item.getBlock().isEnabled(world.enabledFeatures())) return Outcome.refused(Refusal.DISABLED_BLOCK);
        // Operator blocks (command, structure, jigsaw) stay with level-2 creative ops, Force place or not.
        if (item instanceof GameMasterBlockItem && !player.canUseGameMasterBlocks()) return Outcome.refused(Refusal.GAME_REFUSES);
        BlockPlaceContext context = replace
                ? new BuilderPlacementContext(world, player, hand, stack, hit)
                : new BlockPlaceContext(world, player, hand, stack, hit);
        if (!context.canPlace()) return Outcome.refused(Refusal.OCCUPIED);
        BlockPlaceContext placing = item.updatePlacementContext(context);
        if (placing == null) return Outcome.refused(Refusal.GAME_REFUSES);
        BlockState state = McInternals.get().placementState(item, placing);
        if (state == null && force) state = forcedState(item, placing);
        if (state == null) return Outcome.refused(Refusal.GAME_REFUSES);
        if (replace) state = keepOrientation(world.getBlockState(placing.getClickedPos()), state);
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
            Property<?> source = old.getBlock().getStateDefinition().getProperty(name);
            if (source == null) continue;
            result = withValueNamed(result, property, nameOf(old, source));
        }
        if (placed.getBlock() instanceof SlabBlock && old.getBlock() instanceof SlabBlock) {
            result = result.setValue(BlockStateProperties.SLAB_TYPE, old.getValue(BlockStateProperties.SLAB_TYPE));
        }
        return result;
    }

    private static <T extends Comparable<T>> String nameOf(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static <T extends Comparable<T>> BlockState withValueNamed(BlockState state, Property<T> property,
                                                                       String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
    }

    /**
     * The state Force place puts where the game refuses: a wall-and-floor item (a torch) on a block's side becomes its
     * wall block facing out of that side; otherwise the block's own placement state, without the item's checks, else its
     * default state.
     */
    public static BlockState forcedState(BlockItem item, BlockPlaceContext context) {
        if (item instanceof StandingAndWallBlockItem attachable && context.getClickedFace().getAxis().isHorizontal()) {
            BlockState wall = McInternals.get().wallBlock(attachable).defaultBlockState();
            if (wall.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return wall.setValue(BlockStateProperties.HORIZONTAL_FACING, context.getClickedFace());
        }
        BlockState state = null;
        try {
            state = item.getBlock().getStateForPlacement(context);
        } catch (RuntimeException ignored) {
            // A block whose placement logic cannot cope with the cell: its default state.
        }
        return state != null ? state : item.getBlock().defaultBlockState();
    }

    /**
     * Places {@code state} into {@code context}'s cell as {@code BlockItem.place} does once it has a state. With
     * {@code copy} (a mirrored copy) the item's post-placement step is only its block-entity data (a sign's editor opens
     * for the placed original only). Returns whether the item placed it.
     */
    public static boolean place(BlockItem item, BlockPlaceContext context, BlockState state, boolean copy) {
        McInternals steps = McInternals.get();
        if (!steps.placeBlock(item, context, state)) return false;
        BlockPos pos = context.getClickedPos();
        Level world = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        BlockState placed = world.getBlockState(pos);
        if (placed.is(state.getBlock())) {
            placed = steps.updateBlockStateFromTag(item, pos, world, stack, placed);
            if (copy) {
                BlockItem.updateCustomBlockEntityTag(world, player, pos, stack);
            } else {
                steps.updateCustomBlockEntityTag(item, pos, world, player, stack, placed);
            }
            steps.updateBlockEntityComponents(world, pos, stack);
            placed.getBlock().setPlacedBy(world, pos, placed, player, stack);
            if (player instanceof ServerPlayer serverPlayer) CriteriaTriggers.PLACED_BLOCK.trigger(serverPlayer, pos, stack);
        }
        SoundType sounds = placed.getSoundType();
        world.playSound(player, pos, steps.placeSound(item, placed), SoundSource.BLOCKS,
                (sounds.getVolume() + 1.0f) / 2.0f, sounds.getPitch() * 0.8f);
        world.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(player, placed));
        return true;
    }

    /** Whether a builder break takes the block at {@code pos}: not air or a bare fluid, and operator blocks only for ops. */
    public static boolean breakable(Level world, Player player, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) return false;
        return !(state.getBlock() instanceof GameMasterBlock) || player.canUseGameMasterBlocks();
    }

    /**
     * Breaks the block at {@code pos} as a creative player's break does. Returns whether it was removed. The caller
     * empties a container first (nothing may drop: its contents are in the history).
     */
    public static boolean breakBlock(Level world, Player player, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        BlockState broken = block.playerWillDestroy(world, pos, state, player);
        boolean removed = world.removeBlock(pos, false);
        if (removed) block.destroy(world, pos, broken);
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
        int handle = image.transform().applyToState(states, Block.getId(state));
        return handle < 0 ? state : Block.stateById(handle);
    }
}
