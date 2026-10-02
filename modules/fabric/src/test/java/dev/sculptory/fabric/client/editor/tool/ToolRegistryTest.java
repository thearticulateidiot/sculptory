package dev.sculptory.fabric.client.editor.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {
    private final List<String> events = new ArrayList<>();

    private final class RecordingTool implements Tool {
        private final ToolDescriptor descriptor;

        RecordingTool(ToolId id) {
            descriptor = new ToolDescriptor(id, "tool." + id, "icon/" + id, Perm.USE);
        }

        @Override
        public ToolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public SettingsSchema schema() {
            return SettingsSchema.EMPTY;
        }

        @Override
        public RaycastMode raycastMode(SettingsValues s) {
            return RaycastMode.NONE;
        }

        @Override
        public void activate(ToolContext c) {
            events.add("activate " + descriptor.id());
        }

        @Override
        public void deactivate(ToolContext c, DeactivateReason r) {
            events.add("deactivate " + descriptor.id() + " " + r);
        }
    }

    private ToolRegistry registry(ToolId... ids) {
        ToolRegistry registry = new ToolRegistry();
        for (ToolId id : ids) registry.register(new RecordingTool(id));
        return registry;
    }

    @Test
    void registrationAndPaletteOrder() {
        ToolRegistry registry = registry(ToolId.SELECT, ToolId.RAISE, ToolId.LOWER);
        assertEquals(List.of(ToolId.SELECT, ToolId.RAISE, ToolId.LOWER),
                registry.paletteOrder().stream().map(t -> t.descriptor().id()).toList());
        assertThrows(IllegalArgumentException.class, () -> registry.register(new RecordingTool(ToolId.RAISE)));
        assertEquals(ToolId.RAISE, registry.slot(2).orElseThrow().descriptor().id());
        assertTrue(registry.slot(0).isEmpty());
        assertTrue(registry.slot(4).isEmpty());
        assertTrue(registry.get(ToolId.SCATTER).isEmpty());

        registry.setPaletteOrder(List.of(ToolId.LOWER, ToolId.SELECT, ToolId.RAISE));
        assertEquals(ToolId.LOWER, registry.slot(1).orElseThrow().descriptor().id());
        assertThrows(IllegalArgumentException.class, () -> registry.setPaletteOrder(List.of(ToolId.LOWER, ToolId.SELECT)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.setPaletteOrder(List.of(ToolId.LOWER, ToolId.LOWER, ToolId.RAISE)));
        assertThrows(UnsupportedOperationException.class, () -> registry.paletteOrder().clear());
    }

    @Test
    void onlyFifteenSlots() {
        ToolRegistry registry = new ToolRegistry();
        for (int i = 0; i < 16; i++) registry.register(new RecordingTool(new ToolId("tool" + i)));
        assertEquals("tool8", registry.slot(9).orElseThrow().descriptor().id().value());
        assertEquals("tool9", registry.slot(10).orElseThrow().descriptor().id().value(), "slot 10, on key 0");
        assertEquals("tool10", registry.slot(11).orElseThrow().descriptor().id().value(), "slot 11, on key -");
        assertEquals("tool11", registry.slot(12).orElseThrow().descriptor().id().value(), "slot 12, on key =");
        assertEquals("tool12", registry.slot(13).orElseThrow().descriptor().id().value(), "slot 13, on key [");
        assertEquals("tool13", registry.slot(14).orElseThrow().descriptor().id().value(), "slot 14, on key ]");
        assertEquals("tool14", registry.slot(15).orElseThrow().descriptor().id().value(), "slot 15, on key \\");
        assertTrue(registry.slot(16).isEmpty());
        assertEquals(16, registry.paletteOrder().size());
    }

    @Test
    void activationLifecycle() {
        ToolRegistry registry = registry(ToolId.SELECT, ToolId.RAISE);
        assertTrue(registry.active().isEmpty());
        assertTrue(registry.activate(ToolId.SELECT, null));
        assertTrue(registry.isActive(ToolId.SELECT));
        assertFalse(registry.activate(ToolId.SELECT, null), "re-activating is a no-op");
        assertTrue(registry.activate(ToolId.RAISE, null));
        assertSame(registry.get(ToolId.RAISE).orElseThrow(), registry.active().orElseThrow());
        registry.deactivate(null, DeactivateReason.EDITOR_CLOSED);
        registry.deactivate(null, DeactivateReason.EDITOR_CLOSED);
        assertTrue(registry.active().isEmpty());
        assertEquals(List.of(
                "activate select",
                "deactivate select SWITCHED_TOOL",
                "activate raise",
                "deactivate raise EDITOR_CLOSED"), events);
        assertThrows(IllegalArgumentException.class, () -> registry.activate(ToolId.SCATTER, null));
    }

    @Test
    void worldCursorAdjacent() {
        WorldCursor hit = new WorldCursor(new dev.sculptory.core.BlockPos(1, 2, 3), WorldCursor.Face.UP, 1.5, 3, 3.5, false);
        assertEquals(new dev.sculptory.core.BlockPos(1, 3, 3), hit.adjacent());
        assertThrows(IllegalStateException.class, () -> WorldCursor.miss(0, 0, 0).adjacent());
        assertThrows(NullPointerException.class, () -> new WorldCursor(null, null, 0, 0, 0, false));
    }
}
