package dev.sculptory.core.schem;

import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtList;
import java.util.Locale;
import java.util.Objects;

/**
 * The schematic file formats Sculptory reads and writes, each with its file extension. Wire order (the export
 * request's format byte): append only.
 */
public enum SchematicFormat {
    /** Sponge schematic, versions 1-3 read, 3 written ({@link SchematicCodec}; WorldEdit's). */
    SPONGE(".schem"),
    /** Litematica schematic ({@link LitematicCodec}). */
    LITEMATIC(".litematic"),
    /** Vanilla structure-block file ({@link StructureCodec}). */
    STRUCTURE(".nbt");

    private final String extension;

    SchematicFormat(String extension) {
        this.extension = extension;
    }

    /** The lower-case file extension, with its dot. */
    public String extension() {
        return extension;
    }

    /** The format a file name's extension names (ignoring case), or {@code null}. */
    public static SchematicFormat ofFileName(String name) {
        String lower = Objects.requireNonNull(name).toLowerCase(Locale.ROOT);
        for (SchematicFormat format : values()) {
            if (lower.endsWith(format.extension) && lower.length() > format.extension.length()) return format;
        }
        return null;
    }

    /**
     * The format a decoded document is in, by its fields (a file's extension is never trusted): a Sponge version 3
     * wrapper ({@code Schematic}) is Sponge, a {@code Regions} entry Litematica, a {@code size} list a structure;
     * anything else is read as a Sponge version 1 or 2 schematic (whose reader says what is missing when it is not
     * one).
     */
    public static SchematicFormat detect(NbtCompound root) {
        if (root.contains("Schematic")) return SPONGE;
        if (root.contains("Regions")) return LITEMATIC;
        if (root.get("size") instanceof NbtList) return STRUCTURE;
        return SPONGE;
    }
}
