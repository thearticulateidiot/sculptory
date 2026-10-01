package dev.sculptory.core.schem;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.clipboard.Clipboard;
import java.util.Objects;

/**
 * A decoded schematic file.
 *
 * @param format the file's format
 * @param formatVersion the format's own version: Sponge {@code Version} (1, 2 or 3), Litematica {@code Version}
 *     (2-6); 0 for a structure file, which has none
 * @param dataVersion the file's {@code DataVersion} (Litematica {@code MinecraftDataVersion}; 1631 for files
 *     without one, as for Sponge version 1)
 * @param offset Sponge: the file's {@code Offset} as stored (for version 3 the clipboard anchor is {@code -offset};
 *     for versions 1 and 2 {@code Offset} is WorldEdit's absolute minimum corner and the anchor is
 *     {@code -Metadata.WEOffset{X,Y,Z}}, or (0, 0, 0) without it). Litematica: the regions' minimum corner relative to
 *     the schematic origin ({@code -anchor}). Structure: {@code -anchor}
 * @param metadata the parsed metadata
 * @param clipboard the content: local coordinates, tiles as {@code NbtBytes}; dense (every cell present) except for a
 *     structure's cells the file leaves out and the cells between a Litematica file's regions
 * @param report what could not be imported
 */
public record Schematic(SchematicFormat format, int formatVersion, int dataVersion, BlockPos offset,
                        SchematicMetadata metadata, Clipboard clipboard, SchematicReport report) {
    public Schematic {
        Objects.requireNonNull(format);
        Objects.requireNonNull(offset);
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(clipboard);
        Objects.requireNonNull(report);
    }

    /** Width, height and length. */
    public BlockPos dims() {
        return clipboard.size();
    }

    /** A copy of the cells in local coordinates, with their tiles. */
    public BlockBuffer blocks() {
        return clipboard.copyBlocks();
    }
}
