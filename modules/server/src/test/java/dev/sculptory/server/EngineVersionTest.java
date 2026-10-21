package dev.sculptory.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EngineVersionTest {
    /** processResources expanded the placeholder: the id is the engine version with a "+" build part. */
    @Test
    void engineBuildIdIsWrittenIntoTheModule() {
        String id = EngineVersion.get();
        assertNotEquals(EngineVersion.UNKNOWN, id);
        assertFalse(id.contains("${"), id);
        assertTrue(id.matches("[0-9A-Za-z.+_-]+"), id);
    }
}
