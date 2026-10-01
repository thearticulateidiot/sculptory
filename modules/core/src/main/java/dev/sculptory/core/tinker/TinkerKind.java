package dev.sculptory.core.tinker;

import dev.sculptory.core.entity.EntityNbt;
import java.util.Optional;

/**
 * The entities Tinker changes, by their vanilla type ids. Every other entity is left
 * alone: Tinker does not open a panel for it and the server refuses edits to it.
 */
public enum TinkerKind {
    ARMOR_STAND("minecraft:armor_stand"),
    ITEM_FRAME("minecraft:item_frame"),
    GLOW_ITEM_FRAME("minecraft:glow_item_frame"),
    PAINTING("minecraft:painting"),
    BLOCK_DISPLAY("minecraft:block_display"),
    ITEM_DISPLAY("minecraft:item_display"),
    TEXT_DISPLAY("minecraft:text_display");

    private final String typeId;

    TinkerKind(String typeId) {
        this.typeId = typeId;
    }

    public String typeId() {
        return typeId;
    }

    /** The kind of entities of {@code typeId} (as the game loads it), if Tinker changes them. */
    public static Optional<TinkerKind> of(String typeId) {
        String loaded = EntityNbt.loadedId(typeId);
        for (TinkerKind kind : values()) {
            if (kind.typeId.equals(loaded)) return Optional.of(kind);
        }
        return Optional.empty();
    }

    /** An item frame or glow item frame. */
    public boolean itemFrame() {
        return this == ITEM_FRAME || this == GLOW_ITEM_FRAME;
    }

    /** Hung on a block: it moves by whole blocks only and faces the way its wall does. */
    public boolean hanging() {
        return itemFrame() || this == PAINTING;
    }

    /** A block, item or text display. */
    public boolean display() {
        return this == BLOCK_DISPLAY || this == ITEM_DISPLAY || this == TEXT_DISPLAY;
    }

    /** Whether it turns ({@link EntityEdit.Yaw}): armor stands and displays; hanging entities face their wall. */
    public boolean turns() {
        return this == ARMOR_STAND || display();
    }
}
