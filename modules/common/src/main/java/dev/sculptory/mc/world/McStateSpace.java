package dev.sculptory.mc.world;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.state.ModdedFacingFallback;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.state.StateTips;
import dev.sculptory.core.state.VerticalFlip;
import dev.sculptory.core.transform.CubeTurn;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.protocol.v2.Features;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.PlaceOnWaterBlockItem;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CoralBlock;
import net.minecraft.world.level.block.CoralFanBlock;
import net.minecraft.world.level.block.CoralPlantBlock;
import net.minecraft.world.level.block.CoralWallFanBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.GrowingPlantBodyBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The vanilla block-state space: handle {@code h} is {@code Block.getRawIdFromState}, so handles are only valid
 * inside this JVM's registry (rebuild after registry sync; never send handles over the wire).
 *
 * <p>Flags are computed eagerly from {@link Block#BLOCK_STATE_REGISTRY} when the space is built:
 * <ul>
 *   <li>{@code TERRAIN_SOLID}: not replaceable, not leaves, and a collision shape that is a full cube or covers at
 *       least the lower 14/16 of the cell (mud, soul sand, dirt path, farmland). Logs count.</li>
 *   <li>{@code VEGETATION}: leaves, plants (including replaceable ones), flowers, saplings, vines, kelp,
 *       sugar cane, cactus and bamboo.</li>
 *   <li>{@code OPERATOR_NBT}: the block entity's {@code copyItemDataRequiresOperator()}, or an operator block
 *       (command/structure/jigsaw).</li>
 *   <li>{@code NO_COLLISION}: an empty collision shape in an empty world (air, fluids, grass, flowers, torches).</li>
 * </ul>
 * <p>The same pass records the {@linkplain #isSignBlockEntity sign block-entity types}: those of blocks extending
 * vanilla's {@link SignBlock} whose block entity is a {@link SignBlockEntity}, vanilla's sign and hanging sign
 * and modded ones built on them (Farmer's Delight canvas signs). The file sanitizer keeps their text.
 *
 * <p>{@link #rotate}/{@link #mirror} are vanilla's {@code BlockState.rotate}/{@code mirror}, except for modded blocks
 * that do not turn themselves: the {@link ModdedFacingFallback}, whose tables are built
 * with the space, turns those by their facing, axis or rotation while it is switched on. The server switches it with
 * {@code transform.moddedFacingFallback}; the client builds the same tables and follows the server
 * ({@link #followingServer}), so ghost previews turn exactly as the paste will.
 *
 * <p>Tag-based parts of the classification use the tags bound when the space is built; {@link #inTag} is always
 * live. Thread-safe: all state is immutable except benign lazily filled caches.
 */
public final class McStateSpace implements StateSpace {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    private static final VoxelShape LOWER_FOURTEEN_SIXTEENTHS = Shapes.box(0, 0, 0, 1, 0.875, 1);
    /** A pitcher crop stands two cells tall from this age on (vanilla's {@code PitcherCropBlock.isDoubleTallAtAge}). */
    private static final int PITCHER_TWO_TALL_AGE = 3;
    private static final Rotation[] ROTATIONS = {
        Rotation.NONE, Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90
    };

    private final BlockState[] states;
    private final int[] flags;
    private final int[] fluidSources;
    private final NamespacedId[] blockIds;
    private final int air;
    /** Lazily filled; racing writers store equal values. */
    private final BlockDescriptor[] descriptors;
    private final String[] formats;
    private final Set<String> signTypes;
    /** The modded facing fallback: its tables, switched on or off for this view. */
    private final ModdedFacingFallback fallback;
    /** Per state, a column plant's top and below-top states ({@link #columnPart}), -1 for anything else. */
    private final int[] columnTops, columnBodies;
    /** The upside-down flip's tables ({@link #flip}), built with the space. */
    private final VerticalFlip verticalFlip;
    /** The tip tables ({@link #tip}), built with the space; modded states tip only while the fallback is on. */
    private final StateTips tips;

    private McStateSpace(BlockState[] states, int[] flags, int[] fluidSources, NamespacedId[] blockIds, int air,
                             Set<String> signTypes, int[] columnTops, int[] columnBodies) {
        this.states = states;
        this.flags = flags;
        this.fluidSources = fluidSources;
        this.blockIds = blockIds;
        this.air = air;
        this.descriptors = new BlockDescriptor[states.length];
        this.formats = new String[states.length];
        this.signTypes = Set.copyOf(signTypes);
        this.fallback = ModdedFacingFallback.NONE;
        this.columnTops = columnTops;
        this.columnBodies = columnBodies;
        this.verticalFlip = VerticalFlip.NONE;
        this.tips = StateTips.NONE;
    }

    /** {@code from} with other fallback, flip and tip tables: the same handles, flags and caches. */
    private McStateSpace(McStateSpace from, ModdedFacingFallback fallback, VerticalFlip verticalFlip,
                             StateTips tips) {
        this.states = from.states;
        this.flags = from.flags;
        this.fluidSources = from.fluidSources;
        this.blockIds = from.blockIds;
        this.air = from.air;
        this.descriptors = from.descriptors;
        this.formats = from.formats;
        this.signTypes = from.signTypes;
        this.columnTops = from.columnTops;
        this.columnBodies = from.columnBodies;
        this.fallback = Objects.requireNonNull(fallback);
        this.verticalFlip = Objects.requireNonNull(verticalFlip);
        this.tips = Objects.requireNonNull(tips).moddedEnabled(fallback.enabled());
    }

    /**
     * Builds the space from the current {@link Block#BLOCK_STATE_REGISTRY} with the modded facing fallback switched on (the
     * config default). Call after registries (and tags) are ready.
     */
    public static McStateSpace build() {
        return build(true);
    }

    /**
     * Builds the space from the current {@link Block#BLOCK_STATE_REGISTRY}, with the modded facing fallback switched on or off.
     * Its tables are built either way, so {@link #withModdedFacingFallback} can switch it later.
     */
    public static McStateSpace build(boolean moddedFacingFallback) {
        int size = Block.BLOCK_STATE_REGISTRY.size();
        BlockState[] states = new BlockState[size];
        int[] flags = new int[size];
        int[] fluidSources = new int[size];
        NamespacedId[] blockIds = new NamespacedId[size];
        Map<Block, NamespacedId> ids = new IdentityHashMap<>();
        Map<Block, Boolean> operatorOnly = new IdentityHashMap<>();
        Set<String> signTypes = new HashSet<>();
        for (int h = 0; h < size; h++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(h);
            fluidSources[h] = -1;
            if (state == null) continue;
            states[h] = state;
            Block block = state.getBlock();
            blockIds[h] = ids.computeIfAbsent(block, McStateSpace::blockId);
            try {
                flags[h] = computeFlags(state, operatorOnly, signTypes);
                fluidSources[h] = computeFluidSource(state);
            } catch (RuntimeException e) {
                LOG.warn("Could not classify block state {}; treating it as having no flags", state, e);
            }
        }
        int air = Block.getId(Blocks.AIR.defaultBlockState());
        int[] columnTops = new int[size], columnBodies = new int[size];
        columns(states, columnTops, columnBodies);
        // Vanilla's own turns, for the fallback to look at.
        McStateSpace own = new McStateSpace(states, flags, fluidSources, blockIds, air, signTypes, columnTops,
                columnBodies);
        ModdedFacingFallback fallback;
        try {
            fallback = ModdedFacingFallback.build(own, h -> mayTurn(states[h]), own.propertyAccess())
                    .enabled(moddedFacingFallback);
        } catch (RuntimeException e) {
            // Off, whatever the config says: the server then does not offer it, so clients preview as it pastes.
            LOG.warn("Could not build the modded facing fallback; modded blocks turn only as they turn themselves", e);
            fallback = ModdedFacingFallback.NONE;
        }
        VerticalFlip flip;
        try {
            flip = VerticalFlip.build(own, own.propertyAccess(), new FlipHints(states));
        } catch (RuntimeException e) {
            LOG.warn("Could not build the upside-down flip; blocks keep their states when flipped", e);
            flip = VerticalFlip.NONE;
        }
        StateTips tips;
        try {
            tips = StateTips.build(own, own.propertyAccess(), new FlipHints(states), new TipShapes(states));
        } catch (RuntimeException e) {
            LOG.warn("Could not build the tip tables; blocks keep their states when a free turn tips them", e);
            tips = StateTips.NONE;
        }
        return new McStateSpace(own, fallback, flip, tips);
    }

    /**
     * This space with the modded facing fallback switched on or off: the same handles and tables ({@code this} when it
     * already is). Each call makes a new view, so callers that compare spaces by identity keep the one they got.
     */
    public McStateSpace withModdedFacingFallback(boolean on) {
        return on == fallback.enabled() ? this : new McStateSpace(this, fallback.enabled(on), verticalFlip, tips);
    }

    /**
     * The view the client uses for a server that answered {@code Hello} with {@code features}: the fallback on
     * exactly when the server applies it ({@link Features#MODDED_FACING_FALLBACK}; a server without it, including an
     * older build, turns modded blocks only as they turn themselves).
     */
    public McStateSpace followingServer(Features features) {
        return withModdedFacingFallback(features.has(Features.MODDED_FACING_FALLBACK));
    }

    /** Whether {@link #rotate}/{@link #mirror} apply the modded facing fallback. */
    public boolean moddedFacingFallback() {
        return fallback.enabled();
    }

    /** The fallback's tables and counts (switched as this view is). */
    public ModdedFacingFallback moddedFacingFallbackTables() {
        return fallback;
    }

    /**
     * Fills in the column plants ({@link #columnPart}): sugar cane, cactus and bamboo repeat their own state; an
     * upward-growing two-part plant (kelp, twisting vines: an {@link GrowingPlantHeadBlock} top over its
     * {@link GrowingPlantBodyBlock}) has the stem's state on top and the plant block's default below. The stem of a plant
     * block is its pick stack's block (vanilla picks the stem's item); a plant whose pick stack cannot be told is
     * left out.
     */
    private static void columns(BlockState[] states, int[] tops, int[] bodies) {
        Arrays.fill(tops, -1);
        Arrays.fill(bodies, -1);
        Map<Block, Block> plantOfStem = new IdentityHashMap<>();
        Map<Block, Block> stemOfPlant = new IdentityHashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof GrowingPlantBodyBlock plant) || plant.growthDirection != Direction.UP) continue;
            try {
                Block stem = Block.byItem(plant.getCloneItemStack(null, BlockPos.ZERO, plant.defaultBlockState())
                        .getItem());
                if (stem instanceof GrowingPlantHeadBlock && ((GrowingPlantHeadBlock) stem).growthDirection == Direction.UP) {
                    plantOfStem.put(stem, plant);
                    stemOfPlant.put(plant, stem);
                }
            } catch (RuntimeException e) {
                LOG.debug("Could not tell the stem of {}; it is not a scatter column plant", block, e);
            }
        }
        for (int h = 0; h < states.length; h++) {
            BlockState state = states[h];
            if (state == null) continue;
            Block block = state.getBlock();
            if (block instanceof SugarCaneBlock || block instanceof CactusBlock || block instanceof BambooStalkBlock) {
                tops[h] = h;
                bodies[h] = h;
            } else if (plantOfStem.containsKey(block)) {
                tops[h] = h;
                bodies[h] = Block.getId(plantOfStem.get(block).defaultBlockState());
            } else if (stemOfPlant.containsKey(block)) {
                tops[h] = Block.getId(stemOfPlant.get(block).defaultBlockState());
                bodies[h] = h;
            }
        }
    }

    @Override
    public int size() {
        return states.length;
    }

    @Override
    public int air() {
        return air;
    }

    @Override
    public int flags(int h) {
        check(h);
        return flags[h];
    }

    /** The vanilla state of handle {@code h}. */
    public BlockState state(int h) {
        check(h);
        return states[h];
    }

    /** The handle of a vanilla state; -1 if it is not in {@link Block#BLOCK_STATE_REGISTRY}. */
    public int handle(BlockState state) {
        int h = Block.getId(Objects.requireNonNull(state));
        return h >= 0 && h < states.length && states[h] == state ? h : -1;
    }

    @Override
    public String format(int h) {
        check(h);
        String text = formats[h];
        if (text == null) {
            text = describe(h).format();
            formats[h] = text;
        }
        return text;
    }

    @Override
    public int parse(String spec) {
        if (spec == null) return -1;
        BlockDescriptor descriptor;
        try {
            descriptor = BlockDescriptor.parse(spec);
        } catch (IllegalArgumentException malformed) {
            return -1;
        }
        return resolve(descriptor);
    }

    @Override
    public BlockDescriptor describe(int h) {
        check(h);
        BlockDescriptor descriptor = descriptors[h];
        if (descriptor != null) return descriptor;
        BlockState state = states[h];
        TreeMap<String, String> properties = new TreeMap<>();
        for (Property<?> property : state.getProperties()) properties.put(property.getName(), valueName(state, property));
        try {
            descriptor = BlockDescriptor.of(blockIds[h], properties);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Block state has no text form: " + state, e);
        }
        descriptors[h] = descriptor;
        return descriptor;
    }

    @Override
    public int resolve(BlockDescriptor d) {
        if (d == null) return -1;
        ResourceLocation id = ResourceLocation.tryParse(d.block().value());
        if (id == null) return -1;
        Optional<Block> found = BuiltInRegistries.BLOCK.getOptional(id);
        if (found.isEmpty()) return -1;
        Block block = found.get();
        StateDefinition<Block, BlockState> manager = block.getStateDefinition();
        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> entry : d.properties().entrySet()) {
            Property<?> property = manager.getProperty(entry.getKey());
            if (property == null) return -1;
            state = withValue(state, property, entry.getValue());
            if (state == null) return -1;
        }
        return handle(state);
    }

    @Override
    public NamespacedId blockId(int h) {
        check(h);
        return blockIds[h];
    }

    @Override
    public boolean inTag(int h, NamespacedId tag) {
        check(h);
        ResourceLocation id = ResourceLocation.tryParse(Objects.requireNonNull(tag).value());
        return id != null && states[h].is(TagKey.create(Registries.BLOCK, id));
    }

    @Override
    public int rotate(int h, int clockwiseQuarterTurns) {
        check(h);
        int turns = Math.floorMod(clockwiseQuarterTurns, 4);
        if (turns == 0) return h;
        int turned = fallback.rotate(h, turns);
        if (turned >= 0) return turned;
        return handle(states[h].rotate(ROTATIONS[turns]));
    }

    @Override
    public int mirror(int h, Mirror m) {
        check(h);
        int mirrored = fallback.mirror(h, m);
        if (mirrored >= 0) return mirrored;
        return switch (Objects.requireNonNull(m)) {
            case NONE -> h;
            case X -> handle(states[h].mirror(net.minecraft.world.level.block.Mirror.FRONT_BACK));
            case Z -> handle(states[h].mirror(net.minecraft.world.level.block.Mirror.LEFT_RIGHT));
        };
    }

    /** The {@link VerticalFlip} tables, built with the space: vanilla and modded blocks by their properties. */
    @Override
    public int flip(int h) {
        check(h);
        return verticalFlip.flip(h);
    }

    @Override
    public int flipKind(int h) {
        check(h);
        return verticalFlip.kind(h);
    }

    /** The upside-down flip's tables and counts. */
    public VerticalFlip verticalFlipTables() {
        return verticalFlip;
    }

    /** The {@link StateTips} tables: vanilla and (with the fallback on) modded blocks by their properties. */
    @Override
    public int tip(int h, CubeTurn.Tip tip) {
        check(h);
        return tips.tip(h, tip);
    }

    @Override
    public int tipKind(int h, CubeTurn.Tip tip) {
        check(h);
        return tips.kind(h, tip);
    }

    /** The tip tables and counts (modded states switched as this view is). */
    public StateTips tipTables() {
        return tips;
    }

    @Override
    public int withWaterlogged(int h, boolean on) {
        check(h);
        BlockState state = states[h];
        if (!state.hasProperty(BlockStateProperties.WATERLOGGED)) return h;
        return handle(state.setValue(BlockStateProperties.WATERLOGGED, on));
    }

    @Override
    public int fluidSource(int h) {
        check(h);
        return fluidSources[h];
    }

    /** Sugar cane, cactus, bamboo, and upward-growing two-part plants (kelp, twisting vines). */
    @Override
    public int columnPart(int h, boolean top) {
        check(h);
        return top ? columnTops[h] : columnBodies[h];
    }

    /**
     * True for the block-entity types of sign blocks: a block extending vanilla's {@link SignBlock} whose
     * block entity is a {@link SignBlockEntity} (or subclass), so its NBT is sign text. That is
     * {@code minecraft:sign} and {@code minecraft:hanging_sign}, and modded signs built on them.
     */
    @Override
    public boolean isSignBlockEntity(String typeId) {
        return signTypes.contains(typeId);
    }

    /** Straight on the vanilla state: the block's property by name, and {@code with}. */
    @Override
    public int withProperty(int h, String name, String value) {
        if (name == null || value == null) return -1;
        return new StateProperties().with(h, name, value);
    }

    /** The property's values in its own order ({@code Property.getValues}), modded properties included. */
    @Override
    public List<String> propertyValues(int h, String name) {
        BlockState state = state(h);
        Property<?> property = name == null ? null : state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) return List.of();
        return valueNames(property);
    }

    private static <T extends Comparable<T>> List<String> valueNames(Property<T> property) {
        List<String> names = new ArrayList<>();
        for (T value : property.getPossibleValues()) names.add(property.getName(value));
        return List.copyOf(names);
    }

    private void check(int h) {
        if (h < 0 || h >= states.length || states[h] == null) {
            throw new IndexOutOfBoundsException("No block state handle " + h + " (size " + states.length + ")");
        }
    }

    private static NamespacedId blockId(Block block) {
        return new NamespacedId(BuiltInRegistries.BLOCK.getKey(block).toString());
    }

    /** The fallback's and the flip's property access on this space (package-private for McStateSpaceTest). */
    VerticalFlip.StateView propertyAccess() {
        return new StateProperties();
    }

    /**
     * The fallback's property access straight on the vanilla states (a lookup and {@code with}, no text forms): the
     * same answers as {@link #describe}/{@link #resolve} give.
     */
    private final class StateProperties implements VerticalFlip.StateView {
        @Override
        public Collection<String> names(int h) {
            Collection<Property<?>> properties = state(h).getProperties();
            List<String> names = new ArrayList<>(properties.size());
            for (Property<?> property : properties) names.add(property.getName());
            return names;
        }

        @Override
        public String value(int h, String name) {
            BlockState state = state(h);
            Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
            return property == null ? null : valueName(state, property);
        }

        @Override
        public int with(int h, String name, String value) {
            BlockState state = state(h);
            Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
            if (property == null) return -1;
            BlockState changed = withValue(state, property, value);
            return changed == null ? -1 : handle(changed);
        }
    }

    /**
     * What the upside-down flip needs from Minecraft ({@link VerticalFlip.Hints}): plants (a {@link BushBlock} or
     * {@link GrowingPlantBlock}, big dripleaf) and torches ({@link BaseTorchBlock}, standing or on a wall) are
     * upright by nature; a state whose outline is not its own mirror image upside down is lopsided (a bed, a carpet, a
     * rail, a chest, a hopper, an anvil, a vine on a ceiling). Walls are taken as the same either way up (their low sides
     * are two pixels shorter than their posts). The outline is asked at the origin of an empty world; one that throws
     * counts as the same either way up.
     */
    private static final class FlipHints implements VerticalFlip.Hints {
        private final BlockState[] states;
        /** Per outline shape instance, whether it is lopsided (the tables are built on one thread). */
        private final Map<VoxelShape, Boolean> lopsided = new IdentityHashMap<>();

        FlipHints(BlockState[] states) {
            this.states = states;
        }

        @Override
        public boolean upright(int h) {
            Block block = states[h].getBlock();
            return block instanceof BushBlock || block instanceof GrowingPlantBlock
                    || block instanceof BaseTorchBlock || block instanceof BigDripleafBlock
                    || block instanceof BigDripleafStemBlock;
        }

        @Override
        public boolean lopsided(int h) {
            if (states[h].getBlock() instanceof WallBlock) return false;
            try {
                VoxelShape shape = states[h].getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (shape.isEmpty()) return false;
                // States share shape instances (waterlogged or powered variants, a block's precomputed shapes).
                return lopsided.computeIfAbsent(shape, FlipHints::notItsOwnMirrorImage);
            } catch (RuntimeException e) {
                return false;
            }
        }

        private static boolean notItsOwnMirrorImage(VoxelShape shape) {
            VoxelShape flipped = Shapes.empty();
            for (AABB box : shape.toAabbs()) {
                flipped = Shapes.or(flipped,
                        Shapes.box(box.minX, 1.0 - box.maxY, box.minZ, box.maxX, 1.0 - box.minY, box.maxZ));
            }
            return Shapes.joinIsNotEmpty(shape, flipped, BooleanOp.NOT_SAME);
        }
    }

    /**
     * Whether a state's outline is its own when tipped ({@link StateTips.Shapes}): its boxes turned by the tip about the
     * cell's centre, joined, against the outline itself, exactly ({@code VoxelShapes.matchesAnywhere}, as
     * {@link FlipHints} compares a flip), so thin parts (panes, bars, candles) count. Empty and full cells always are.
     * Per outline shape instance and tip, cached (the tables are built on one thread); one that throws counts as the
     * same.
     */
    private static final class TipShapes implements StateTips.Shapes {
        private final BlockState[] states;
        private final Map<VoxelShape, Boolean> pitch = new IdentityHashMap<>();
        private final Map<VoxelShape, Boolean> roll = new IdentityHashMap<>();

        TipShapes(BlockState[] states) {
            this.states = states;
        }

        @Override
        public boolean keepsShape(int h, CubeTurn.Tip tip) {
            if (tip == CubeTurn.Tip.NONE) return true;
            try {
                VoxelShape shape = states[h].getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (shape.isEmpty() || shape == Shapes.block()) return true;
                return (tip == CubeTurn.Tip.PITCH ? pitch : roll).computeIfAbsent(shape, s -> sameTipped(s, tip));
            } catch (RuntimeException e) {
                return true;
            }
        }

        private static boolean sameTipped(VoxelShape shape, CubeTurn.Tip tip) {
            int[] m = tip.matrix();
            VoxelShape[] turned = {Shapes.empty()};
            shape.forAllBoxes((x0, y0, z0, x1, y1, z1) -> {
                double[] a = turn(m, x0 - 0.5, y0 - 0.5, z0 - 0.5), b = turn(m, x1 - 0.5, y1 - 0.5, z1 - 0.5);
                turned[0] = Shapes.or(turned[0], Shapes.box(Math.min(a[0], b[0]) + 0.5,
                        Math.min(a[1], b[1]) + 0.5, Math.min(a[2], b[2]) + 0.5, Math.max(a[0], b[0]) + 0.5,
                        Math.max(a[1], b[1]) + 0.5, Math.max(a[2], b[2]) + 0.5));
            });
            return !Shapes.joinIsNotEmpty(shape, turned[0], BooleanOp.NOT_SAME);
        }

        private static double[] turn(int[] m, double x, double y, double z) {
            return new double[] {m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z,
                    m[6] * x + m[7] * y + m[8] * z};
        }
    }

    /**
     * The fallback's cheap filter: a registered state with a property it may turn. The fallback itself skips vanilla
     * blocks and the states that turn themselves.
     */
    private static boolean mayTurn(BlockState state) {
        if (state == null) return false;
        for (Property<?> property : state.getProperties()) {
            switch (property.getName()) {
                case ModdedFacingFallback.FACING, ModdedFacingFallback.AXIS, ModdedFacingFallback.HORIZONTAL_AXIS,
                        ModdedFacingFallback.ROTATION -> {
                    return true;
                }
                default -> { }
            }
        }
        return false;
    }

    private static int computeFlags(BlockState state, Map<Block, Boolean> operatorOnly, Set<String> signTypes) {
        Block block = state.getBlock();
        int result = 0;
        boolean isAir = state.isAir();
        if (isAir) result |= StateFlags.AIR;
        boolean fluidBlock = block instanceof LiquidBlock;
        if (fluidBlock) result |= StateFlags.FLUID_BLOCK;
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            result |= StateFlags.WATERLOGGABLE;
            if (state.getValue(BlockStateProperties.WATERLOGGED)) result |= StateFlags.WATERLOGGED;
        }
        if (state.hasBlockEntity()) {
            result |= StateFlags.HAS_BLOCK_ENTITY;
            if (operatorOnly.computeIfAbsent(block, b -> requiresOperator(state, signTypes))) {
                result |= StateFlags.OPERATOR_NBT;
            }
        }
        boolean replaceable = state.canBeReplaced();
        if (replaceable) result |= StateFlags.REPLACEABLE;
        if (block instanceof FallingBlock) result |= StateFlags.FALLING;
        boolean leaves = block instanceof LeavesBlock || state.is(BlockTags.LEAVES);
        if (!replaceable && !leaves && coversCell(state)) result |= StateFlags.TERRAIN_SOLID;
        boolean vegetation = !isAir && !fluidBlock && isVegetation(state, block, leaves, replaceable);
        if (vegetation) result |= StateFlags.VEGETATION;
        if (standsTwoTall(state, block)) {
            result |= state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? StateFlags.LOWER_HALF
                    : StateFlags.UPPER_HALF;
        }
        FluidState fluid = state.getFluidState();
        boolean water = !fluid.isEmpty() && Fluids.WATER.isSame(fluid.getType());
        if (water) result |= StateFlags.WATER;
        if (isAquatic(state, block, water, fluidBlock, vegetation)) result |= StateFlags.AQUATIC;
        if (!isAir && block.asItem() instanceof PlaceOnWaterBlockItem) result |= StateFlags.ON_WATER;
        if (noCollision(state)) result |= StateFlags.NO_COLLISION;
        return result;
    }

    /**
     * A block that lives only under water: live coral (plants, fans, wall fans and coral blocks, which die out of
     * water), or a plant that holds water without a {@code waterlogged} property (seagrass, tall seagrass, kelp and
     * kelp_plant; a bubble column is not a plant).
     */
    private static boolean isAquatic(BlockState state, Block block, boolean water, boolean fluidBlock,
                                     boolean vegetation) {
        if (block instanceof CoralPlantBlock || block instanceof CoralFanBlock || block instanceof CoralWallFanBlock
                || block instanceof CoralBlock) {
            return true;
        }
        return water && !fluidBlock && vegetation && !state.hasProperty(BlockStateProperties.WATERLOGGED);
    }

    /**
     * A vanilla block made of a lower and an upper half: the two-block plants ({@link DoublePlantBlock}: tall grass, large
     * ferns, the tall flowers, pitcher plants, small dripleaf, tall seagrass; a pitcher crop only once it has grown two
     * tall) and doors. A {@code half} property alone (a modded vertical slab) is not enough.
     */
    private static boolean standsTwoTall(BlockState state, Block block) {
        if (!state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return false;
        if (block instanceof PitcherCropBlock) return state.getValue(PitcherCropBlock.AGE) >= PITCHER_TWO_TALL_AGE;
        return block instanceof DoublePlantBlock || block instanceof DoorBlock;
    }

    /**
     * No collision shape in an empty world (air, fluids, grass, flowers, torches, signs): a player stands in and
     * walks through it. A shape that depends on the entity (powder snow, scaffolding) counts as it is without one.
     */
    private static boolean noCollision(BlockState state) {
        try {
            return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A full collision cube, or a collision shape covering at least the lower 14/16 of the cell. */
    private static boolean coversCell(BlockState state) {
        try {
            VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (shape.isEmpty()) return false;
            if (Block.isShapeFullBlock(shape)) return true;
            return !Shapes.joinIsNotEmpty(LOWER_FOURTEEN_SIXTEENTHS, shape, BooleanOp.ONLY_FIRST);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isVegetation(BlockState state, Block block, boolean leaves, boolean replaceable) {
        return leaves
                || block instanceof BushBlock
                || block instanceof GrowingPlantBlock
                || block instanceof VineBlock
                || block instanceof SugarCaneBlock
                || block instanceof CactusBlock
                || block instanceof BambooStalkBlock
                || block instanceof BambooSaplingBlock
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SAPLINGS)
                || (replaceable && state.is(BlockTags.REPLACEABLE_BY_TREES));
    }

    /**
     * Better Replace's Keep shape: the block entity of a
     * {@code from} cell fits {@code to} when the type {@code from}'s block makes supports {@code to}'s state (every
     * wood's sign shares one type, every colour's shulker box and banner another).
     */
    @Override
    public boolean keepsBlockEntity(int from, int to) {
        BlockState source = state(from), target = state(to);
        if (!source.hasBlockEntity() || !target.hasBlockEntity()) return false;
        if (source.getBlock() == target.getBlock()) return true;
        Optional<BlockEntityType<?>> type = BLOCK_ENTITY_TYPES.computeIfAbsent(source.getBlock(),
                block -> Optional.ofNullable(blockEntityType(source)));
        return type.isPresent() && type.get().isValid(target);
    }

    /** Block-entity types by block, found once each ({@link #keepsBlockEntity}). */
    private static final java.util.concurrent.ConcurrentHashMap<Block, Optional<BlockEntityType<?>>> BLOCK_ENTITY_TYPES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static BlockEntityType<?> blockEntityType(BlockState state) {
        if (!(state.getBlock() instanceof EntityBlock provider)) return null;
        try {
            BlockEntity entity = provider.newBlockEntity(BlockPos.ZERO, state);
            return entity == null ? null : entity.getType();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Whether the block's NBT needs an operator; adds its block-entity type to {@code signTypes} if it is a sign. */
    private static boolean requiresOperator(BlockState state, Set<String> signTypes) {
        Block block = state.getBlock();
        if (block instanceof GameMasterBlock) return true;
        if (!(block instanceof EntityBlock provider)) return false;
        try {
            BlockEntity entity = provider.newBlockEntity(BlockPos.ZERO, state);
            if (block instanceof SignBlock && entity instanceof SignBlockEntity) {
                ResourceLocation type = BlockEntityType.getKey(entity.getType());
                if (type != null) signTypes.add(type.toString());
            }
            return entity != null && entity.onlyOpCanSetNbt();
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Could not create a block entity for {}; treating its NBT as operator-only", state, e);
            return true;
        }
    }

    private static int computeFluidSource(BlockState state) {
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) return -1;
        Fluid type = fluid.getType();
        Fluid still = type instanceof FlowingFluid flowable ? flowable.getSource() : type;
        BlockState source = still.defaultFluidState().createLegacyBlock();
        if (source.isAir()) return -1;
        return Block.getId(source);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** The state with {@code property} set to {@code value}, or {@code null} if the value is unknown or not canonical. */
    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        if (parsed.isEmpty() || !property.getName(parsed.get()).equals(value)) return null;
        return state.setValue(property, parsed.get());
    }
}
