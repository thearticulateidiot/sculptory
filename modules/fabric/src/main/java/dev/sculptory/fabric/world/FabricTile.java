package dev.sculptory.fabric.world;

import dev.sculptory.core.buffer.BlockEntityData;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.Identifier;

/**
 * Block-entity content: the {@code createNbtWithId} compound (with {@code id}, without x/y/z). The compound is
 * owned and never mutated; {@link #nbtBytes()} is encoded lazily with {@link NbtIo}, only when a caller needs bytes
 * for the wire or a file.
 *
 * <p><b>Origin.</b> A tile made by {@link #capture} was read from this server's world ({@link #serverCaptured()});
 * restoring it (undo/redo, move, stack) cannot introduce content that did not already exist, so
 * {@link BlockWriter} restores it as-is even for players without operator rights. Every other tile ({@link #of},
 * {@link #from} of foreign data, core {@code NbtBytes}) is untrusted and subject to operator-NBT stripping. The
 * origin survives only while the same object is kept: re-encoding a captured tile (e.g. into {@code NbtBytes})
 * makes it untrusted, which fails safe (stripping).
 */
public final class FabricTile implements BlockEntityData {
    /** Cap for decoding foreign NBT bytes (schematics, wire) into a compound. */
    public static final long MAX_DECODE_BYTES = 8L << 20;

    private final String typeId;
    private final NbtCompound nbt;
    private final boolean serverCaptured;
    private volatile byte[] bytes;

    private FabricTile(String typeId, NbtCompound nbt, boolean serverCaptured) {
        this.typeId = Objects.requireNonNull(typeId);
        this.nbt = Objects.requireNonNull(nbt);
        this.serverCaptured = serverCaptured;
        if (typeId.isEmpty()) throw new IllegalArgumentException("Empty block-entity type id");
    }

    /** Captures a live block entity; the result is {@link #serverCaptured() server-captured}. */
    public static FabricTile capture(BlockEntity entity, RegistryWrapper.WrapperLookup registries) {
        Identifier type = BlockEntityType.getId(entity.getType());
        NbtCompound nbt = entity.createNbtWithId(registries);
        String typeId = type != null ? type.toString() : nbt.getString("id");
        return new FabricTile(typeId, nbt, true);
    }

    /** Wraps a compound (copied) as untrusted content. Its {@code id} is set to {@code typeId} if missing. */
    public static FabricTile of(String typeId, NbtCompound nbt) {
        NbtCompound copy = nbt.copy();
        if (!copy.contains("id")) copy.putString("id", typeId);
        return new FabricTile(typeId, copy, false);
    }

    /**
     * Content captured from this server's world that was saved in its undo history journal (in the world's own save
     * folder, as trusted as its chunks) and read back after a restart: {@link #serverCaptured() server-captured}, as it
     * was when it was recorded, so undo restores it exactly as before the restart.
     *
     * @throws IOException if the bytes are not a valid NBT compound within {@code maxBytes}
     */
    public static FabricTile restoreCaptured(String typeId, byte[] nbtBytes, long maxBytes) throws IOException {
        NbtCompound nbt;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(nbtBytes))) {
            nbt = NbtIo.readCompound(in, NbtSizeTracker.of(maxBytes));
        } catch (RuntimeException e) {
            throw new IOException("Invalid block-entity NBT", e);
        }
        if (!nbt.contains("id")) nbt.putString("id", typeId);
        return new FabricTile(typeId, nbt, true);
    }

    /**
     * Any {@link BlockEntityData} as a FabricTile, decoding foreign bytes (untrusted). A FabricTile is returned
     * as-is, keeping its origin.
     *
     * @throws IOException if the bytes are not a valid NBT compound within {@link #MAX_DECODE_BYTES}
     */
    public static FabricTile from(BlockEntityData data) throws IOException {
        if (data instanceof FabricTile tile) return tile;
        return of(data.typeId(), decode(data.nbtBytes()));
    }

    /** True when this content was captured from the server's own world by {@link #capture}. */
    public boolean serverCaptured() {
        return serverCaptured;
    }

    /** True when {@code data} is a {@link #serverCaptured() server-captured} FabricTile. */
    public static boolean isServerCaptured(BlockEntityData data) {
        return data instanceof FabricTile tile && tile.serverCaptured;
    }

    /** A fresh, mutable copy of the compound (with {@code id}). */
    public NbtCompound copyNbt() {
        return nbt.copy();
    }

    /** The compound itself, for reading only (comparisons that must not copy it); never change it. */
    NbtCompound compound() {
        return nbt;
    }

    @Override
    public String typeId() {
        return typeId;
    }

    @Override
    public byte[] nbtBytes() {
        return encoded().clone();
    }

    @Override
    public int estimatedBytes() {
        byte[] encoded = bytes;
        int payload = encoded != null ? encoded.length : nbt.getSizeInBytes();
        return 64 + 2 * typeId.length() + payload;
    }

    @Override
    public boolean sameContent(BlockEntityData o) {
        if (o == null || !typeId.equals(o.typeId())) return false;
        if (o instanceof FabricTile other) return nbt.equals(other.nbt);
        try {
            NbtCompound decoded = decode(o.nbtBytes());
            if (!decoded.contains("id")) decoded.putString("id", typeId);
            return nbt.equals(decoded);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FabricTile other && typeId.equals(other.typeId) && nbt.equals(other.nbt);
    }

    @Override
    public int hashCode() {
        return 31 * typeId.hashCode() + nbt.hashCode();
    }

    @Override
    public String toString() {
        return "FabricTile[" + typeId + (serverCaptured ? ", captured, " : ", ") + nbt + "]";
    }

    private byte[] encoded() {
        byte[] encoded = bytes;
        if (encoded == null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            try (DataOutputStream data = new DataOutputStream(out)) {
                NbtIo.writeCompound(nbt, data);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            encoded = out.toByteArray();
            bytes = encoded;
        }
        return encoded;
    }

    private static NbtCompound decode(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return NbtIo.readCompound(in, NbtSizeTracker.of(MAX_DECODE_BYTES));
        } catch (RuntimeException e) {
            throw new IOException("Invalid block-entity NBT", e);
        }
    }
}
