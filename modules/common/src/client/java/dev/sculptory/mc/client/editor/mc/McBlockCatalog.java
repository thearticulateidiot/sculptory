package dev.sculptory.mc.client.editor.mc;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.mc.client.editor.blocks.BlockCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The block catalog from {@code Registries.BLOCK}, modded blocks included, with each block's item
 * as its icon. Also converts world block states to {@link BlockDescriptor}s for the eyedropper.
 * The list is built on first use (registries are frozen by then). Client thread only.
 */
public final class McBlockCatalog implements BlockCatalog {
    private List<Entry> entries;
    private List<Tag> tags;
    private final Map<BlockDescriptor, ItemStack> icons = new HashMap<>();

    @Override
    public List<Entry> entries() {
        if (entries == null) {
            List<Entry> built = new ArrayList<>();
            for (Block block : BuiltInRegistries.BLOCK) {
                ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                try {
                    built.add(new Entry(BlockDescriptor.of(new NamespacedId(id.toString())), block.getName().getString()));
                } catch (IllegalArgumentException unusualId) {
                    // An id our platform-neutral type can't hold; not offered in the picker.
                }
            }
            built.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(entry -> entry.block().block().value()));
            entries = List.copyOf(built);
        }
        return entries;
    }

    /** The block tags of {@code Registries.BLOCK} (as the client knows them from the server), with their sizes. */
    @Override
    public List<Tag> tags() {
        if (tags == null) {
            List<Tag> built = new ArrayList<>();
            BuiltInRegistries.BLOCK.getTags().forEach(pair -> {
                try {
                    int size = 0;
                    for (var ignored : pair.getSecond()) size++;
                    built.add(new Tag(new NamespacedId(pair.getFirst().location().toString()), size));
                } catch (IllegalArgumentException unusualId) {
                    // Not offered.
                }
            });
            built.sort(Comparator.comparing(tag -> tag.id().value()));
            if (built.isEmpty()) return List.of();
            tags = List.copyOf(built);
        }
        return tags;
    }

    @Override
    public ItemStack icon(BlockDescriptor block) {
        return icons.computeIfAbsent(BlockDescriptor.of(block.block()), key -> block(key).map(found -> {
            Item item = found.asItem();
            return new ItemStack(item);
        }).orElse(ItemStack.EMPTY));
    }

    @Override
    public String name(BlockDescriptor block) {
        return block(block).map(found -> found.getName().getString()).orElse(block.block().value());
    }

    /** The registered block for a descriptor, if the id is known. */
    public static Optional<Block> block(BlockDescriptor descriptor) {
        ResourceLocation id = ResourceLocation.tryParse(descriptor.block().value());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return Optional.empty();
        }
        return Optional.of(BuiltInRegistries.BLOCK.get(id));
    }

    /** The exact state as a descriptor (block id plus every property value). */
    public static Optional<BlockDescriptor> describe(BlockState state) {
        try {
            Map<String, String> properties = new HashMap<>();
            for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
                properties.put(entry.getKey().getName(), valueName(entry.getKey(), entry.getValue()));
            }
            NamespacedId id = new NamespacedId(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
            return Optional.of(BlockDescriptor.of(id, properties));
        } catch (IllegalArgumentException unusual) {
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String valueName(Property<T> property, Comparable<?> value) {
        return property.getName((T) value);
    }
}
