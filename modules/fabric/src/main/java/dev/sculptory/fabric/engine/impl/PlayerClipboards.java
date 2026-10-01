package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.clipboard.Clipboard;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One clipboard per player, in memory only; a new one replaces the old (whose id then stops resolving), and it is
 * dropped when the player leaves. Server thread only.
 *
 * <p>Trust travels with the tiles, not the clipboard: a copy of world blocks holds server-captured
 * {@code FabricTile}s (operator NBT kept when pasted), while clipboards read from files hold plain
 * {@code NbtBytes} (operator NBT stripped for players without the right).
 */
public final class PlayerClipboards {
    /** A held clipboard and the id the client refers to it by. */
    public record Held(UUID id, Clipboard clipboard) {
        public Held {
            Objects.requireNonNull(id);
            Objects.requireNonNull(clipboard);
        }
    }

    private final Map<UUID, Held> byPlayer = new HashMap<>();

    /** The player's clipboard. */
    public Optional<Held> get(UUID player) {
        return Optional.ofNullable(byPlayer.get(player));
    }

    /** The player's clipboard if its id is {@code clipboardId}. */
    public Optional<Held> find(UUID player, UUID clipboardId) {
        Held held = byPlayer.get(player);
        return held != null && held.id().equals(clipboardId) ? Optional.of(held) : Optional.empty();
    }

    /** Makes {@code clipboard} the player's clipboard under a fresh id. */
    public Held install(UUID player, Clipboard clipboard) {
        Held held = new Held(UUID.randomUUID(), clipboard);
        byPlayer.put(player, held);
        return held;
    }

    public void remove(UUID player) {
        byPlayer.remove(player);
    }

    public void clear() {
        byPlayer.clear();
    }

    public int size() {
        return byPlayer.size();
    }
}
