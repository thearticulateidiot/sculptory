package dev.sculptory.fabric.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sculptory.server.engine.impl.EngineEditService;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

/** The shared engine's copies of game constants are the game's. */
class PlatformConstantsTest {
    @Test
    void theHorizontalWorldLimitIsTheGames() {
        assertEquals(World.HORIZONTAL_LIMIT, EngineEditService.HORIZONTAL_LIMIT);
    }
}
