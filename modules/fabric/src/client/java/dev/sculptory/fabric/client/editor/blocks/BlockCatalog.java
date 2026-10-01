package dev.sculptory.fabric.client.editor.blocks;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import java.util.List;
import java.util.Objects;
import net.minecraft.item.ItemStack;

/**
 * The blocks the player can choose from, with names and item icons. The game implementation reads
 * {@code Registries.BLOCK}; tests use a small fake (icons may be null there).
 */
public interface BlockCatalog {
    /** One pickable block (its default state) and its display name. */
    record Entry(BlockDescriptor block, String name) {
        public Entry {
            Objects.requireNonNull(block);
            Objects.requireNonNull(name);
        }
    }

    /** A catalog with no blocks; names are the block ids and there are no icons. */
    BlockCatalog EMPTY = new BlockCatalog() {
        @Override
        public List<Entry> entries() {
            return List.of();
        }

        @Override
        public ItemStack icon(BlockDescriptor block) {
            return null;
        }

        @Override
        public String name(BlockDescriptor block) {
            return block.block().value();
        }
    };

    /** Every block, sorted by name. */
    List<Entry> entries();

    /** The block's item icon, or null/empty when it has none. */
    ItemStack icon(BlockDescriptor block);

    /** The display name ("Oak Planks"). */
    String name(BlockDescriptor block);

    /** One block tag the picker offers for masks ({@code #minecraft:logs}) and how many blocks it holds. */
    record Tag(NamespacedId id, int blocks) {
        public Tag {
            Objects.requireNonNull(id);
        }
    }

    /** Every block tag, sorted by id. None by default. */
    default List<Tag> tags() {
        return List.of();
    }
}
