package dev.sculptory.core.schem;

import java.util.List;

/**
 * The metadata fields Sculptory reads and writes (Sponge {@code Metadata}; Litematica {@code Metadata}; a
 * structure file's {@code Sculptory} compound).
 *
 * @param name {@code Name}, or {@code null}
 * @param author {@code Author}, or {@code null}
 * @param dateMillis {@code Date} (Litematica: {@code TimeCreated}; epoch milliseconds), or {@code null}
 * @param requiredMods {@code RequiredMods}; on write, merged with the non-{@code minecraft} namespaces in use
 * @param sculptory {@code Sculptory} library metadata, or {@code null}
 * @param description Litematica's {@code Description}, or {@code null} (Sponge files have none)
 */
public record SchematicMetadata(String name, String author, Long dateMillis, List<String> requiredMods,
                                AssetInfo sculptory, String description) {
    public static final SchematicMetadata EMPTY = new SchematicMetadata(null, null, null, List.of(), null, null);

    public SchematicMetadata {
        requiredMods = List.copyOf(requiredMods);
    }

    /** Metadata without a description. */
    public SchematicMetadata(String name, String author, Long dateMillis, List<String> requiredMods,
                             AssetInfo sculptory) {
        this(name, author, dateMillis, requiredMods, sculptory, null);
    }
}
