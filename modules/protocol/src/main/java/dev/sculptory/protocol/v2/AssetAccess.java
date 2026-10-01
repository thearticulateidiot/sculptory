package dev.sculptory.protocol.v2;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who may load a library entry (per-asset access): {@link Mode#EVERYONE}
 * who may use the library (the default), or {@link Mode#LISTED} the {@code players} named, at most
 * {@value #MAX_PLAYERS}, each once. A {@link Grantee} sent by the client may have no UUID yet (a name the player
 * typed): the server resolves it or refuses the change. Names are at most {@value #MAX_NAME_BYTES} UTF-8 bytes and
 * never empty.
 */
public record AssetAccess(Mode mode, List<Grantee> players) {
    public static final int MAX_PLAYERS = 256;
    public static final int MAX_NAME_BYTES = 64;
    /** Everyone who may use the library. */
    public static final AssetAccess EVERYONE = new AssetAccess(Mode.EVERYONE, List.of());

    /** Append-only: the wire carries the ordinal. */
    public enum Mode {
        EVERYONE,
        LISTED
    }

    /** One granted player: {@code uuid} may be {@code null} only in a request, for a name the server is to resolve. */
    public record Grantee(UUID uuid, String name) {
        public Grantee {
            Objects.requireNonNull(name);
            if (name.isEmpty() || name.getBytes(StandardCharsets.UTF_8).length > MAX_NAME_BYTES) {
                throw new IllegalArgumentException("A player name is 1-" + MAX_NAME_BYTES + " bytes");
            }
        }
    }

    public AssetAccess {
        Objects.requireNonNull(mode);
        players = List.copyOf(players);
        if (mode == Mode.EVERYONE && !players.isEmpty()) {
            throw new IllegalArgumentException("Access for everyone names no players");
        }
        if (mode == Mode.LISTED && players.isEmpty()) {
            throw new IllegalArgumentException("A listed access names at least one player");
        }
        if (players.size() > MAX_PLAYERS) {
            throw new IllegalArgumentException("At most " + MAX_PLAYERS + " players");
        }
        Set<UUID> seen = new HashSet<>();
        for (Grantee grantee : players) {
            if (grantee.uuid() != null && !seen.add(grantee.uuid())) {
                throw new IllegalArgumentException("A player is listed once: " + grantee.name());
            }
        }
    }

    /** Access for exactly these players. */
    public static AssetAccess listed(List<Grantee> players) {
        return new AssetAccess(Mode.LISTED, players);
    }

    public boolean restricted() {
        return mode == Mode.LISTED;
    }

    /** Whether every grantee has a UUID (a request may name players still to resolve). */
    public boolean resolved() {
        for (Grantee grantee : players) {
            if (grantee.uuid() == null) return false;
        }
        return true;
    }

    /** Whether {@code player} is named ({@code false} for {@link Mode#EVERYONE}: that is not a grant). */
    public boolean names(UUID player) {
        for (Grantee grantee : players) {
            if (player.equals(grantee.uuid())) return true;
        }
        return false;
    }
}
