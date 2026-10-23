package dev.sculptory.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class MinecraftVersionGuardTest {
    @Test
    void theJarKnowsTheVersionItWasBuiltFor() {
        assertEquals(System.getProperty("sculptory.minecraftVersion"), MinecraftVersionGuard.builtFor());
    }

    @Test
    void theVersionItWasBuiltForRuns() {
        assertEquals(Optional.empty(), MinecraftVersionGuard.refusal("1.21.1", "1.21.1"));
    }

    /** Paper loads an api-version 1.21 plugin on every 1.21.x; only 1.21.1 may run it. */
    @Test
    void anotherVersionIsRefusedNamingBoth() {
        for (String found : new String[] {"1.21", "1.21.2", "1.21.4", "1.21.11", "1.20.6"}) {
            String why = MinecraftVersionGuard.refusal("1.21.1", found).orElseThrow();
            assertTrue(why.contains("Minecraft 1.21.1 only") && why.contains("Minecraft " + found + ","), why);
        }
    }
}
