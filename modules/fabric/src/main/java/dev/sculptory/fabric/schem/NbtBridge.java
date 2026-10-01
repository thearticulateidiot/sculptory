package dev.sculptory.fabric.schem;

import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtLimits;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtSizeTracker;

/**
 * Converts between the core NBT model and Minecraft's through bytes: both sides read and write the same binary
 * form, a named root compound (name {@code ""}) as written by Minecraft's {@code NbtIo.writeCompound} (checked with
 * javap: {@code readCompound(DataInput, NbtSizeTracker)} skips the root name).
 */
public final class NbtBridge {
    private NbtBridge() {}

    /**
     * A Minecraft compound with the same content.
     *
     * @throws IOException if the content is over {@code maxBytes} for Minecraft's size tracker
     */
    public static NbtCompound toMinecraft(dev.sculptory.core.nbt.NbtCompound core, long maxBytes) throws IOException {
        byte[] bytes = NbtIo.toBytes(core);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return net.minecraft.nbt.NbtIo.readCompound(in, NbtSizeTracker.of(maxBytes));
        } catch (RuntimeException e) {
            // Minecraft wraps read failures (including the size tracker's) in a CrashException.
            throw new IOException("NBT could not be converted: " + e.getMessage(), e);
        }
    }

    /**
     * A core compound with the same content.
     *
     * @throws IOException if the content breaks {@code limits}
     */
    public static dev.sculptory.core.nbt.NbtCompound toCore(NbtCompound minecraft, NbtLimits limits) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            net.minecraft.nbt.NbtIo.writeCompound(minecraft, out);
        }
        return NbtIo.fromBytes(bytes.toByteArray(), limits);
    }
}
