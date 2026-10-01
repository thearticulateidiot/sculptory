package dev.sculptory.core.schem;

import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtLimits;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.state.StateSpace;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Schematic files of every {@link SchematicFormat}: reading tells the format from the document itself
 * ({@link SchematicFormat#detect}), never from a file name, so a renamed file still reads as what it is; writing takes
 * the format the player chose.
 */
public final class SchematicFiles {
    private SchematicFiles() {}

    /** Reads a gzip-compressed (or uncompressed) schematic of any format. Does not close {@code in}. */
    public static Schematic read(InputStream in, StateSpace states, SchematicCodec.Limits limits, DataFixHook hook)
            throws IOException {
        Objects.requireNonNull(in);
        Objects.requireNonNull(limits);
        return decode(NbtIo.readAuto(in, limits.nbt()).value(), states, limits, hook);
    }

    /** Decodes a schematic document of any format. */
    public static Schematic decode(NbtCompound root, StateSpace states, SchematicCodec.Limits limits, DataFixHook hook)
            throws SchematicException {
        return switch (SchematicFormat.detect(Objects.requireNonNull(root))) {
            case SPONGE -> SchematicCodec.decode(root, states, limits, hook);
            case LITEMATIC -> LitematicCodec.decode(root, states, limits, hook);
            case STRUCTURE -> StructureCodec.decode(root, states, limits, hook);
        };
    }

    /** Writes a gzip-compressed schematic in {@code format}. Does not close {@code out}. */
    public static void write(SchematicFormat format, OutputStream out, Clipboard clipboard, SchematicMetadata metadata,
                             int dataVersion) throws IOException {
        NbtIo.writeGzip(out, "", encode(format, clipboard, metadata, dataVersion));
    }

    /** Encodes a schematic document in {@code format}. */
    public static NbtCompound encode(SchematicFormat format, Clipboard clipboard, SchematicMetadata metadata,
                                     int dataVersion) throws SchematicException {
        return switch (Objects.requireNonNull(format)) {
            case SPONGE -> SchematicCodec.encode(clipboard, metadata, dataVersion);
            case LITEMATIC -> LitematicCodec.encode(clipboard, metadata, dataVersion);
            case STRUCTURE -> StructureCodec.encode(clipboard, metadata, dataVersion);
        };
    }

    /**
     * What a library listing shows of a file without reading its blocks: its format, dimensions ({@code null} when
     * they cannot be read) and library tags.
     */
    public record Header(SchematicFormat format, int[] dims, List<String> tags) {
        public Header {
            Objects.requireNonNull(format);
            dims = dims == null ? null : dims.clone();
            tags = List.copyOf(tags);
        }

        @Override
        public int[] dims() {
            return dims == null ? null : dims.clone();
        }
    }

    /**
     * The header of a file's bytes, best effort (players write the library: untrusted NBT limits). Never throws: a file
     * that cannot be read has no dimensions and no tags.
     */
    public static Header header(byte[] bytes) {
        NbtCompound root;
        try {
            root = NbtIo.readAuto(new ByteArrayInputStream(bytes), NbtLimits.untrustedUpload()).value();
        } catch (IOException | RuntimeException e) {
            return new Header(SchematicFormat.SPONGE, null, List.of());
        }
        return header(root);
    }

    /** The header of a decoded document, best effort. */
    public static Header header(NbtCompound root) {
        SchematicFormat format = SchematicFormat.detect(root);
        try {
            return switch (format) {
                case SPONGE -> {
                    NbtCompound schem = root.getCompound("Schematic");
                    if (schem == null) schem = root;
                    int[] dims = dims(side(schem.get("Width")), side(schem.get("Height")), side(schem.get("Length")));
                    NbtCompound meta = schem.getCompound("Metadata");
                    yield new Header(format, dims, tags(SchematicCodec.ours(meta)));
                }
                case LITEMATIC -> {
                    NbtCompound meta = root.getCompound("Metadata");
                    NbtCompound size = meta == null ? null : meta.getCompound("EnclosingSize");
                    int[] dims = size == null ? regionDims(root.getCompound("Regions"))
                            : dims(value(size.get("x")), value(size.get("y")), value(size.get("z")));
                    yield new Header(format, dims, tags(SchematicCodec.ours(meta)));
                }
                case STRUCTURE -> {
                    NbtTag size = root.get("size");
                    int[] dims = null;
                    if (size instanceof NbtList list && list.size() == 3 && list.elementType() == NbtTag.INT) {
                        dims = dims(value(list.get(0)), value(list.get(1)), value(list.get(2)));
                    }
                    yield new Header(format, dims, tags(SchematicCodec.ours(root)));
                }
            };
        } catch (RuntimeException e) {
            return new Header(format, null, List.of());
        }
    }

    /** The enclosing box of Litematica regions, from their {@code Position} and {@code Size}. */
    private static int[] regionDims(NbtCompound regions) {
        if (regions == null || regions.isEmpty() || regions.size() > LitematicCodec.MAX_REGIONS) return null;
        long[] min = {Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE};
        long[] max = {Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE};
        for (Map.Entry<String, NbtTag> entry : regions.entries().entrySet()) {
            if (!(entry.getValue() instanceof NbtCompound region)) return null;
            NbtCompound position = region.getCompound("Position"), size = region.getCompound("Size");
            if (position == null || size == null) return null;
            String[] axes = {"x", "y", "z"};
            for (int axis = 0; axis < 3; axis++) {
                Integer p = position.getInt(axes[axis]), s = size.getInt(axes[axis]);
                if (p == null || s == null || s == 0) return null;
                long a = p, b = (long) p + s - Integer.signum(s);
                min[axis] = Math.min(min[axis], Math.min(a, b));
                max[axis] = Math.max(max[axis], Math.max(a, b));
            }
        }
        long w = max[0] - min[0] + 1, h = max[1] - min[1] + 1, l = max[2] - min[2] + 1;
        return w > Integer.MAX_VALUE || h > Integer.MAX_VALUE || l > Integer.MAX_VALUE ? null
                : dims((int) w, (int) h, (int) l);
    }

    private static int[] dims(int w, int h, int l) {
        return w < 1 || h < 1 || l < 1 ? null : new int[] {w, h, l};
    }

    private static List<String> tags(NbtCompound sculptory) {
        NbtList list = sculptory == null ? null : sculptory.getList("Tags");
        List<String> tags = list == null || list.size() > SchematicCodec.MAX_ASSET_TAGS ? null : list.strings();
        return tags == null ? List.of() : tags;
    }

    private static int side(NbtTag tag) {
        return switch (tag) {
            case NbtTag.NbtShort s -> s.value() & 0xFFFF;
            case NbtTag.NbtInt i -> i.value();
            case NbtTag.NbtByte b -> b.value() & 0xFF;
            case null, default -> -1;
        };
    }

    private static int value(NbtTag tag) {
        Integer value = NbtTag.intValue(tag);
        return value == null ? -1 : value;
    }
}
