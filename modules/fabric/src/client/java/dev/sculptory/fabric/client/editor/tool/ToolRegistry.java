package dev.sculptory.fabric.client.editor.tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The registered tools, their palette order (slots 1-15 are the first fifteen; slot 10 is on key 0, slot 11 on -,
 * slot 12 on =, slot 13 on [, slot 14 on ] and slot 15 on \) and which one is active.
 * At most one tool is active; switching deactivates the old tool before activating the new one.
 * Client thread only.
 */
public final class ToolRegistry {
    public static final int PALETTE_SLOTS = 15;

    private final Map<ToolId, Tool> tools = new LinkedHashMap<>();
    private final List<ToolId> order = new ArrayList<>();
    private Tool active;

    /** Adds a tool at the end of the palette. */
    public void register(Tool tool) {
        Objects.requireNonNull(tool);
        ToolId id = Objects.requireNonNull(tool.descriptor()).id();
        if (tools.containsKey(id)) throw new IllegalArgumentException("Tool already registered: " + id);
        tools.put(id, tool);
        order.add(id);
    }

    public Optional<Tool> get(ToolId id) {
        return Optional.ofNullable(tools.get(id));
    }

    /** All tools in palette order. */
    public List<Tool> paletteOrder() {
        return order.stream().map(tools::get).toList();
    }

    /** Reorders the palette; {@code ids} must list every registered tool exactly once. */
    public void setPaletteOrder(List<ToolId> ids) {
        List<ToolId> copy = List.copyOf(ids);
        if (copy.size() != tools.size() || !new HashSet<>(copy).equals(tools.keySet())) {
            throw new IllegalArgumentException("Palette order must list every registered tool once");
        }
        order.clear();
        order.addAll(copy);
    }

    /** The tool in palette slot {@code slot} (1-based, 1-15). */
    public Optional<Tool> slot(int slot) {
        if (slot < 1 || slot > PALETTE_SLOTS || slot > order.size()) return Optional.empty();
        return Optional.of(tools.get(order.get(slot - 1)));
    }

    public Optional<Tool> active() {
        return Optional.ofNullable(active);
    }

    public boolean isActive(ToolId id) {
        return active != null && active.descriptor().id().equals(id);
    }

    /**
     * Makes {@code id} the active tool, first deactivating the current one with
     * {@link DeactivateReason#SWITCHED_TOOL}. Activating the active tool again does nothing.
     *
     * @return true if the active tool changed
     */
    public boolean activate(ToolId id, ToolContext context) {
        Tool next = tools.get(id);
        if (next == null) throw new IllegalArgumentException("Unknown tool: " + id);
        if (next == active) return false;
        deactivate(context, DeactivateReason.SWITCHED_TOOL);
        next.activate(context);
        active = next;
        return true;
    }

    /** Deactivates the active tool, if any. */
    public void deactivate(ToolContext context, DeactivateReason reason) {
        Objects.requireNonNull(reason);
        Tool current = active;
        if (current == null) return;
        active = null;
        current.deactivate(context, reason);
    }
}
