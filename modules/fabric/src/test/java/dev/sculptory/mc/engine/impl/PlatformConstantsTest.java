package dev.sculptory.mc.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sculptory.server.engine.impl.EngineEditService;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/** The shared engine's copies of game constants are the game's. */
class PlatformConstantsTest {
    @Test
    void theHorizontalWorldLimitIsTheGames() {
        assertEquals(Level.MAX_LEVEL_SIZE, EngineEditService.HORIZONTAL_LIMIT);
    }
}
