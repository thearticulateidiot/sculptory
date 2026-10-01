package dev.sculptory.fabric.net;

import dev.sculptory.protocol.v2.ProtocolV2;
import io.netty.handler.codec.DecoderException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * One protocol v2 frame on {@code sculptory:c2s} or {@code sculptory:s2c}. The frame is the whole
 * payload (no second length prefix); the codecs refuse empty or over-cap payloads before allocating.
 * The array is not copied: frames are built once and never modified.
 */
public record Frame(CustomPayload.Id<Frame> id, byte[] bytes) implements CustomPayload {
    public static final CustomPayload.Id<Frame> C2S_ID = new CustomPayload.Id<>(Identifier.of("sculptory", "c2s"));
    public static final CustomPayload.Id<Frame> S2C_ID = new CustomPayload.Id<>(Identifier.of("sculptory", "s2c"));
    public static final PacketCodec<PacketByteBuf, Frame> C2S_CODEC = codec(C2S_ID, ProtocolV2.MAX_C2S_FRAME);
    public static final PacketCodec<PacketByteBuf, Frame> S2C_CODEC = codec(S2C_ID, ProtocolV2.MAX_S2C_FRAME);

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    public Frame {
        Objects.requireNonNull(id);
        Objects.requireNonNull(bytes);
        int max = maxBytes(id);
        if (bytes.length < 1 || bytes.length > max) {
            throw new IllegalArgumentException("Frame of " + bytes.length + " bytes (1-" + max + ")");
        }
    }

    public static Frame c2s(byte[] bytes) {
        return new Frame(C2S_ID, bytes);
    }

    public static Frame s2c(byte[] bytes) {
        return new Frame(S2C_ID, bytes);
    }

    @Override
    public CustomPayload.Id<Frame> getId() {
        return id;
    }

    /** Registers both payload types once; safe to call from the server and the client initializer. */
    public static void registerTypes() {
        if (REGISTERED.compareAndSet(false, true)) {
            PayloadTypeRegistry.playC2S().register(C2S_ID, C2S_CODEC);
            PayloadTypeRegistry.playS2C().register(S2C_ID, S2C_CODEC);
        }
    }

    private static int maxBytes(CustomPayload.Id<Frame> id) {
        if (id.equals(C2S_ID)) return ProtocolV2.MAX_C2S_FRAME;
        if (id.equals(S2C_ID)) return ProtocolV2.MAX_S2C_FRAME;
        throw new IllegalArgumentException("Not a Sculptory channel: " + id.id());
    }

    private static PacketCodec<PacketByteBuf, Frame> codec(CustomPayload.Id<Frame> id, int max) {
        return PacketCodec.of((frame, buf) -> buf.writeBytes(frame.bytes), buf -> {
            int length = buf.readableBytes();
            if (length < 1 || length > max) {
                throw new DecoderException("Sculptory frame of " + length + " bytes (cap " + max + ")");
            }
            byte[] bytes = new byte[length];
            buf.readBytes(bytes);
            return new Frame(id, bytes);
        });
    }
}
