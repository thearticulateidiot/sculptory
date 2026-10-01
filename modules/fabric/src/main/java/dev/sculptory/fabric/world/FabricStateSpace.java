package dev.sculptory.fabric.world;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.state.ModdedFacingFallback;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.state.VerticalFlip;
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
import net.minecraft.block.AbstractPlantBlock;
import net.minecraft.block.AbstractPlantPartBlock;
import net.minecraft.block.AbstractPlantStemBlock;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.AbstractTorchBlock;
import net.minecraft.block.BambooBlock;
import net.minecraft.block.BambooShootBlock;
import net.minecraft.block.BigDripleafBlock;
import net.minecraft.block.BigDripleafStemBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CactusBlock;
import net.minecraft.block.CoralBlock;
import net.minecraft.block.CoralBlockBlock;
import net.minecraft.block.CoralFanBlock;
import net.minecraft.block.CoralWallFanBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.OperatorBlock;
import net.minecraft.block.PitcherCropBlock;
import net.minecraft.block.PlantBlock;
import net.minecraft.block.SugarCaneBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.VineBlock;
import net.minecraft.block.WallBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.PlaceableOnWaterItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.EmptyBlockView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The vanilla block-state space: handle {@code h} is {@code Block.getRawIdFromState}, so handles are only valid
 * inside this JVM's registry (rebuild after registry sync; never send handles over the wire).
 *
 * <p>Flags are computed eagerly from {@link Block#STATE_IDS} when the space is built:
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
 * vanilla's {@link AbstractSignBlock} whose block entity is a {@link SignBlockEntity}, vanilla's sign and hanging sign
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
public final class FabricStateSpace implements StateSpace {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    private static final VoxelShape LOWER_FOURTEEN_SIXTEENTHS = VoxelShapes.cuboid(0, 0, 0, 1, 0.875, 1);
    /** A pitcher crop stands two cells tall from this age on (vanilla's {@code PitcherCropBlock.isDoubleTallAtAge}). */
    private static final int PITCHER_TWO_TALL_AGE = 3;
    private static final BlockRotation[] ROTATIONS = {
        BlockRotation.NONE, BlockRotation.CLOCKWISE_90, BlockRotation.CLOCKWISE_180, BlockRotation.COUNTERCLOCKWISE_90
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

    private FabricStateSpace(BlockState[] states, int[] flags, int[] fluidSources, NamespacedId[] blockIds, int air,
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
    }

    /** {@code from} with another fallback and flip tables: the same handles, flags and caches. */
    private FabricStateSpace(FabricStateSpace from, ModdedFacingFallback fallback, VerticalFlip verticalFlip) {
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
    }

    /**
     * Builds the space from the current {@link Block#STATE_IDS} with the modded facing fallback switched on (the
     * config default). Call after registries (and tags) are ready.
     */
    public static FabricStateSpace build() {
        return build(true);
    }

    /**
     * Builds the space from the current {@link Block#STATE_IDS}, with the modded facing fallback switched on or off.
     * Its tables are built either way, so {@link #withModdedFacingFallback} can switch it later.
     */
    public static FabricStateSpace build(boolean moddedFacingFallback) {
        int size = Block.STATE_IDS.size();
        BlockState[] states = new BlockState[size];
        int[] flags = new int[size];
        int[] fluidSources = new int[size];
        NamespacedId[] blockIds = new NamespacedId[size];
        Map<Block, NamespacedId> ids = new IdentityHashMap<>();
        Map<Block, Boolean> operatorOnly = new IdentityHashMap<>();
        Set<String> signTypes = new HashSet<>();
        for (int h = 0; h < size; h++) {
            BlockState state = Block.STATE_IDS.get(h);
            fluidSources[h] = -1;
            if (state == null) continue;
            states[h] = state;
            Block block = state.getBlock();
            blockIds[h] = ids.computeIfAbsent(block, FabricStateSpace::blockId);
            try {
                flags[h] = computeFlags(state, operatorOnly, signTypes);
                fluidSources[h] = computeFluidSource(state);
            } catch (RuntimeException e) {
                LOG.warn("Could not classify block state {}; treating it as having no flags", state, e);
            }
        }
        int air = Block.getRawIdFromState(Blocks.AIR.getDefaultState());
        int[] columnTops = new int[size], columnBodies = new int[size];
        columns(states, columnTops, columnBodies);
        // Vanilla's own turns, for the fallback to look at.
        FabricStateSpace own = new FabricStateSpace(states, flags, fluidSources, blockIds, air, signTypes, columnTops,
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
        return new FabricStateSpace(own, fallback, flip);
    }

    /**
     * This space with the modded facing fallback switched on or off: the same handles and tables ({@code this} when it
     * already is). Each call makes a new view, so callers that compare spaces by identity keep the one they got.
     */
    public FabricStateSpace withModdedFacingFallback(boolean on) {
        return on == fallback.enabled() ? this : new FabricStateSpace(this, fallback.enabled(on), verticalFlip);
    }

    /**
     * The view the client uses for a server that answered {@code Hello} with {@code features}: the fallback on
     * exactly when the server applies it ({@link Features#MODDED_FACING_FALLBACK}; a server without it, including an
     * older build, turns modded blocks only as they turn themselves).
     */
    public FabricStateSpace followingServer(Features features) {
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
     * upward-growing two-part plant (kelp, twisting vines: an {@link AbstractPlantStemBlock} top over its
     * {@link AbstractPlantBlock}) has the stem's state on top and the plant block's default below. The stem of a plant
     * block is its pick stack's block (vanilla picks the stem's item); a plant whose pick stack cannot be told is
     * left out.
     */
    private static void columns(BlockState[] states, int[] tops, int[] bodies) {
        Arrays.fill(tops, -1);
        Arrays.fill(bodies, -1);
        Map<Block, Block> plantOfStem = new IdentityHashMap<>();
        Map<Block, Block> stemOfPlant = new IdentityHashMap<>();
        for (Block block : Registries.BLOCK) {
            if (!(block instanceof AbstractPlantBlock plant) || plant.growthDirection != Direction.UP) continue;
            try {
                Block stem = Block.getBlockFromItem(plant.getPickStack(null, BlockPos.ORIGIN, plant.getDefaultState())
                        .getItem());
                if (stem instanceof AbstractPlantStemBlock && ((AbstractPlantStemBlock) stem).growthDirection == Direction.UP) {
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
            if (block instanceof SugarCaneBlock || block instanceof CactusBlock || block instanceof BambooBlock) {
                tops[h] = h;
                bodies[h] = h;
            } else if (plantOfStem.containsKey(block)) {
                tops[h] = h;
                bodies[h] = Block.getRawIdFromState(plantOfStem.get(block).getDefaultState());
            } else if (stemOfPlant.containsKey(block)) {
                tops[h] = Block.getRawIdFromState(stemOfPlant.get(block).getDefaultState());
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

    /** The handle of a vanilla state; -1 if it is not in {@link Block#STATE_IDS}. */
    public int handle(BlockState state) {
        int h = Block.getRawIdFromState(Objects.requireNonNull(state));
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
        Identifier id = Identifier.tryParse(d.block().value());
        if (id == null) return -1;
        Optional<Block> found = Registries.BLOCK.getOrEmpty(id);
        if (found.isEmpty()) return -1;
        Block block = found.get();
        StateManager<Block, BlockState> manager = block.getStateManager();
        BlockState state = block.getDefaultState();
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
        Identifier id = Identifier.tryParse(Objects.requireNonNull(tag).value());
        return id != null && states[h].isIn(TagKey.of(RegistryKeys.BLOCK, id));
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
            case X -> handle(states[h].mirror(BlockMirror.FRONT_BACK));
            case Z -> handle(states[h].mirror(BlockMirror.LEFT_RIGHT));
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

    @Override
    public int withWaterlogged(int h, boolean on) {
        check(h);
        BlockState state = states[h];
        if (!state.contains(Properties.WATERLOGGED)) return h;
        return handle(state.with(Properties.WATERLOGGED, on));
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
     * True for the block-entity types of sign blocks: a block extending vanilla's {@link AbstractSignBlock} whose
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
        Property<?> property = name == null ? null : state.getBlock().getStateManager().getProperty(name);
        if (property == null) return List.of();
        return valueNames(property);
    }

    private static <T extends Comparable<T>> List<String> valueNames(Property<T> property) {
        List<String> names = new ArrayList<>();
        for (T value : property.getValues()) names.add(property.name(value));
        return List.copyOf(names);
    }

    private void check(int h) {
        if (h < 0 || h >= states.length || states[h] == null) {
            throw new IndexOutOfBoundsException("No block state handle " + h + " (size " + states.length + ")");
        }
    }

    private static NamespacedId blockId(Block block) {
        return new NamespacedId(Registries.BLOCK.getId(block).toString());
    }

    /** The fallback's and the flip's property access on this space (package-private for FabricStateSpaceTest). */
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
            Property<?> property = state.getBlock().getStateManager().getProperty(name);
            return property == null ? null : valueName(state, property);
        }

        @Override
        public int with(int h, String name, String value) {
            BlockState state = state(h);
            Property<?> property = state.getBlock().getStateManager().getProperty(name);
            if (property == null) return -1;
            BlockState changed = withValue(state, property, value);
            return changed == null ? -1 : handle(changed);
        }
    }

    /**
     * What the upside-down flip needs from Minecraft ({@link VerticalFlip.Hints}): plants (a {@link PlantBlock} or
     * {@link AbstractPlantPartBlock}, big dripleaf) and torches ({@link AbstractTorchBlock}, standing or on a wall) are
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
            return block instanceof PlantBlock || block instanceof AbstractPlantPartBlock
                    || block instanceof AbstractTorchBlock || block instanceof BigDripleafBlock
                    || block instanceof BigDripleafStemBlock;
        }

        @Override
        public boolean lopsided(int h) {
            if (states[h].getBlock() instanceof WallBlock) return false;
            try {
                VoxelShape shape = states[h].getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
                if (shape.isEmpty()) return false;
                // States share shape instances (waterlogged or powered variants, a block's precomputed shapes).
                return lopsided.computeIfAbsent(shape, FlipHints::notItsOwnMirrorImage);
            } catch (RuntimeException e) {
                return false;
            }
        }

        private static boolean notItsOwnMirrorImage(VoxelShape shape) {
            VoxelShape flipped = VoxelShapes.empty();
            for (Box box : shape.getBoundingBoxes()) {
                flipped = VoxelShapes.union(flipped,
                        VoxelShapes.cuboid(box.minX, 1.0 - box.maxY, box.minZ, box.maxX, 1.0 - box.minY, box.maxZ));
            }
            return VoxelShapes.matchesAnywhere(shape, flipped, BooleanBiFunction.NOT_SAME);
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
        boolean fluidBlock = block instanceof FluidBlock;
        if (fluidBlock) result |= StateFlags.FLUID_BLOCK;
        if (state.contains(Properties.WATERLOGGED)) {
            result |= StateFlags.WATERLOGGABLE;
            if (state.get(Properties.WATERLOGGED)) result |= StateFlags.WATERLOGGED;
        }
        if (state.hasBlockEntity()) {
            result |= StateFlags.HAS_BLOCK_ENTITY;
            if (operatorOnly.computeIfAbsent(block, b -> requiresOperator(state, signTypes))) {
                result |= StateFlags.OPERATOR_NBT;
            }
        }
        boolean replaceable = state.isReplaceable();
        if (replaceable) result |= StateFlags.REPLACEABLE;
        if (block instanceof FallingBlock) result |= StateFlags.FALLING;
        boolean leaves = block instanceof LeavesBlock || state.isIn(BlockTags.LEAVES);
        if (!replaceable && !leaves && coversCell(state)) result |= StateFlags.TERRAIN_SOLID;
        boolean vegetation = !isAir && !fluidBlock && isVegetation(state, block, leaves, replaceable);
        if (vegetation) result |= StateFlags.VEGETATION;
        if (standsTwoTall(state, block)) {
            result |= state.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? StateFlags.LOWER_HALF
                    : StateFlags.UPPER_HALF;
        }
        FluidState fluid = state.getFluidState();
        boolean water = !fluid.isEmpty() && Fluids.WATER.matchesType(fluid.getFluid());
        if (water) result |= StateFlags.WATER;
        if (isAquatic(state, block, water, fluidBlock, vegetation)) result |= StateFlags.AQUATIC;
        if (!isAir && block.asItem() instanceof PlaceableOnWaterItem) result |= StateFlags.ON_WATER;
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
        if (block instanceof CoralBlock || block instanceof CoralFanBlock || block instanceof CoralWallFanBlock
                || block instanceof CoralBlockBlock) {
            return true;
        }
        return water && !fluidBlock && vegetation && !state.contains(Properties.WATERLOGGED);
    }

    /**
     * A vanilla block made of a lower and an upper half: the two-block plants ({@link TallPlantBlock}: tall grass, large
     * ferns, the tall flowers, pitcher plants, small dripleaf, tall seagrass; a pitcher crop only once it has grown two
     * tall) and doors. A {@code half} property alone (a modded vertical slab) is not enough.
     */
    private static boolean standsTwoTall(BlockState state, Block block) {
        if (!state.contains(Properties.DOUBLE_BLOCK_HALF)) return false;
        if (block instanceof PitcherCropBlock) return state.get(PitcherCropBlock.AGE) >= PITCHER_TWO_TALL_AGE;
        return block instanceof TallPlantBlock || block instanceof DoorBlock;
    }

    /**
     * No collision shape in an empty world (air, fluids, grass, flowers, torches, signs): a player stands in and
     * walks through it. A shape that depends on the entity (powder snow, scaffolding) counts as it is without one.
     */
    private static boolean noCollision(BlockState state) {
        try {
            return state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** A full collision cube, or a collision shape covering at least the lower 14/16 of the cell. */
    private static boolean coversCell(BlockState state) {
        try {
            VoxelShape shape = state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
            if (shape.isEmpty()) return false;
            if (Block.isShapeFullCube(shape)) return true;
            return !VoxelShapes.matchesAnywhere(LOWER_FOURTEEN_SIXTEENTHS, shape, BooleanBiFunction.ONLY_FIRST);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isVegetation(BlockState state, Block block, boolean leaves, boolean replaceable) {
        return leaves
                || block instanceof PlantBlock
                || block instanceof AbstractPlantPartBlock
                || block instanceof VineBlock
                || block instanceof SugarCaneBlock
                || block instanceof CactusBlock
                || block instanceof BambooBlock
                || block instanceof BambooShootBlock
                || state.isIn(BlockTags.FLOWERS)
                || state.isIn(BlockTags.SAPLINGS)
                || (replaceable && state.isIn(BlockTags.REPLACEABLE_BY_TREES));
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
        return type.isPresent() && type.get().supports(target);
    }

    /** Block-entity types by block, found once each ({@link #keepsBlockEntity}). */
    private static final java.util.concurrent.ConcurrentHashMap<Block, Optional<BlockEntityType<?>>> BLOCK_ENTITY_TYPES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static BlockEntityType<?> blockEntityType(BlockState state) {
        if (!(state.getBlock() instanceof BlockEntityProvider provider)) return null;
        try {
            BlockEntity entity = provider.createBlockEntity(BlockPos.ORIGIN, state);
            return entity == null ? null : entity.getType();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Whether the block's NBT needs an operator; adds its block-entity type to {@code signTypes} if it is a sign. */
    private static boolean requiresOperator(BlockState state, Set<String> signTypes) {
        Block block = state.getBlock();
        if (block instanceof OperatorBlock) return true;
        if (!(block instanceof BlockEntityProvider provider)) return false;
        try {
            BlockEntity entity = provider.createBlockEntity(BlockPos.ORIGIN, state);
            if (block instanceof AbstractSignBlock && entity instanceof SignBlockEntity) {
                Identifier type = BlockEntityType.getId(entity.getType());
                if (type != null) signTypes.add(type.toString());
            }
            return entity != null && entity.copyItemDataRequiresOperator();
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Could not create a block entity for {}; treating its NBT as operator-only", state, e);
            return true;
        }
    }

    private static int computeFluidSource(BlockState state) {
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) return -1;
        Fluid type = fluid.getFluid();
        Fluid still = type instanceof FlowableFluid flowable ? flowable.getStill() : type;
        BlockState source = still.getDefaultState().getBlockState();
        if (source.isAir()) return -1;
        return Block.getRawIdFromState(source);
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.name(state.get(property));
    }

    /** The state with {@code property} set to {@code value}, or {@code null} if the value is unknown or not canonical. */
    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.parse(value);
        if (parsed.isEmpty() || !property.name(parsed.get()).equals(value)) return null;
        return state.with(property, parsed.get());
    }
}
