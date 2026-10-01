package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProtocolV2Test {
    @Test
    void frameCapsRespectVanillaLimits() {
        assertTrue(ProtocolV2.MAX_C2S_FRAME < 32_767);
        assertTrue(ProtocolV2.MAX_S2C_FRAME < 1_048_576);
    }
}
