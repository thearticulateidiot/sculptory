package dev.sculptory.fabric.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

/** The physics-suppression guard must never leak to another thread (client and integrated server share a JVM). */
class EditScopeTest {
    @Test
    void nestsOnTheOwnerThreadAndIsInvisibleElsewhere() throws Exception {
        assertFalse(EditScope.isSuppressing());
        try (EditScope outer = EditScope.suppressPhysics()) {
            assertTrue(EditScope.isSuppressing());
            try (EditScope inner = EditScope.suppressPhysics()) {
                assertEquals(2, EditScope.depth());
            }
            assertTrue(EditScope.isSuppressing(), "closing the inner scope ended the outer one");
            assertFalse(CompletableFuture.supplyAsync(EditScope::isSuppressing).get(), "other thread sees suppression");
            ExecutionException refused = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> CompletableFuture.runAsync(EditScope::suppressPhysics).get());
            assertInstanceOf(IllegalStateException.class, refused.getCause());
        }
        assertFalse(EditScope.isSuppressing());
        assertEquals(0, EditScope.depth());
        // Released: another thread may now take it.
        CompletableFuture.runAsync(() -> {
            try (EditScope scope = EditScope.suppressPhysics()) {
                if (!EditScope.isSuppressing()) throw new AssertionError();
            }
        }).get();
        assertFalse(EditScope.isSuppressing());
    }
}
