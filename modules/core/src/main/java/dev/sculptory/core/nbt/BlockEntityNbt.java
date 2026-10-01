package dev.sculptory.core.nbt;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * Converts between block-entity compounds and {@link NbtBytes}.
 *
 * <p>The {@code NbtBytes} form is the uncompressed named root compound (name {@code ""}) that Minecraft's
 * {@code NbtIo.writeCompound} writes for {@code BlockEntity.createNbtWithId}: it carries {@code id} (first) and
 * never {@code x}/{@code y}/{@code z}. The Fabric side decodes it with {@code NbtIo.readCompound}.
 */
public final class BlockEntityNbt {
    private BlockEntityNbt() {}

    /**
     * The canonical block-entity compound: {@code id} set to {@code typeId} and placed first, {@code x}/{@code y}/
     * {@code z} removed, every other entry kept in order.
     */
    public static NbtCompound normalize(String typeId, NbtCompound data) {
        Objects.requireNonNull(typeId);
        NbtCompound.Builder out = NbtCompound.builder().putString("id", typeId);
        data.entries().forEach((key, value) -> {
            if (!key.equals("id") && !key.equals("x") && !key.equals("y") && !key.equals("z")) out.put(key, value);
        });
        return out.build();
    }

    /**
     * {@link #normalize Normalizes} {@code data} and encodes it.
     *
     * @throws IllegalArgumentException if {@code typeId} is empty or a string is too long to encode
     */
    public static NbtBytes toNbtBytes(String typeId, NbtCompound data) {
        return new NbtBytes(typeId, NbtIo.toBytes(normalize(typeId, data)));
    }

    /** Decodes a tile's NBT with {@link NbtLimits#BLOCK_ENTITY}; the result has {@code id} if the bytes did. */
    public static NbtCompound decode(BlockEntityData tile) throws IOException {
        return decode(tile, NbtLimits.BLOCK_ENTITY);
    }

    public static NbtCompound decode(BlockEntityData tile, NbtLimits limits) throws IOException {
        Objects.requireNonNull(tile);
        return NbtIo.fromBytes(tile.nbtBytes(), limits);
    }

    /**
     * The tile's NBT with keys in sorted order and {@code id} set to its type (x/y/z dropped), so equal content
     * always gives equal bytes; the raw bytes if they cannot be decoded.
     */
    public static byte[] canonicalBytes(BlockEntityData tile) {
        byte[] canonical = tryCanonicalBytes(tile);
        return canonical != null ? canonical : tile.nbtBytes();
    }

    /** {@link #canonicalBytes} when the NBT decodes, else {@code null}. */
    public static byte[] tryCanonicalBytes(BlockEntityData tile) {
        byte[] raw = tile.nbtBytes();
        NbtCompound decoded;
        try {
            decoded = NbtIo.fromBytes(raw, NbtLimits.BLOCK_ENTITY);
        } catch (IOException undecodable) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length + 16);
        try {
            NbtIo.writeCanonical(out, normalize(tile.typeId(), decoded));
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return out.toByteArray();
    }
}
