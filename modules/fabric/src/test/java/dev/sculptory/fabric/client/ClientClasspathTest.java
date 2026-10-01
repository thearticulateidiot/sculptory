package dev.sculptory.fabric.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Guards the build wiring that lets JUnit see client-source-set classes. */
class ClientClasspathTest {
    @Test
    void clientClassesAreVisibleToTests() {
        assertEquals("SculptoryClientMod", SculptoryClientMod.class.getSimpleName());
    }
}
