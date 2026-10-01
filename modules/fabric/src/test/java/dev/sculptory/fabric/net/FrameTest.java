package dev.sculptory.fabric.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.protocol.v2.ProtocolV2;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

class FrameTest {
    @Test
    void channelsAndPayloadCodec() {
        assertEquals("sculptory:c2s", Frame.C2S_ID.id().toString());
        assertEquals("sculptory:s2c", Frame.S2C_ID.id().toString());
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        Frame.C2S_CODEC.encode(buf, Frame.c2s(new byte[] {1, 2, 3}));
        Frame decoded = Frame.C2S_CODEC.decode(buf);
        assertEquals(Frame.C2S_ID, decoded.getId());
        assertArrayEquals(new byte[] {1, 2, 3}, decoded.bytes());
    }

    @Test
    void codecsRefuseEmptyAndOverCapPayloads() {
        PacketByteBuf big = new PacketByteBuf(Unpooled.wrappedBuffer(new byte[ProtocolV2.MAX_C2S_FRAME + 1]));
        assertThrows(DecoderException.class, () -> Frame.C2S_CODEC.decode(big));
        assertThrows(DecoderException.class, () -> Frame.S2C_CODEC.decode(new PacketByteBuf(Unpooled.buffer())));
        PacketByteBuf s2c = new PacketByteBuf(Unpooled.wrappedBuffer(new byte[ProtocolV2.MAX_C2S_FRAME + 1]));
        assertEquals(ProtocolV2.MAX_C2S_FRAME + 1, Frame.S2C_CODEC.decode(s2c).bytes().length, "S2C has the larger cap");
        assertThrows(IllegalArgumentException.class, () -> Frame.c2s(new byte[ProtocolV2.MAX_C2S_FRAME + 1]));
        assertThrows(IllegalArgumentException.class, () -> Frame.s2c(new byte[0]));
    }
}
