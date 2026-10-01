package dev.sculptory.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class NamespacedIdTest {
    @Test
    void acceptsVanillaAndModdedIds() {
        assertEquals("minecraft:stone", new NamespacedId("minecraft:stone").value());
        assertEquals("farmersdelight:cooking_pot", new NamespacedId("farmersdelight:cooking_pot").value());
    }

    @Test
    void rejectsMalformedIds() {
        assertThrows(IllegalArgumentException.class, () -> new NamespacedId("Stone"));
        assertThrows(IllegalArgumentException.class, () -> new NamespacedId("minecraft:"));
        assertThrows(IllegalArgumentException.class, () -> new NamespacedId(""));
    }
}
