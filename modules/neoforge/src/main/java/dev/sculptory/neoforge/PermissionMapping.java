package dev.sculptory.neoforge;

import dev.sculptory.server.engine.Perm;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * How {@code ServerLoader.hasPermission} maps onto NeoForge's {@code PermissionAPI}, kept free of NeoForge's and
 * Minecraft's types so it is unit-tested ({@code PermissionMappingTest}); {@link NeoForgeServerLoader} plugs in
 * {@code ServerPlayer}, {@code PermissionNode} and {@code PermissionAPI.getPermission}.
 *
 * <p>The PermissionAPI wants every node registered up front, each with a default resolver that answers when no
 * permissions mod decides. Sculptory's checks pass the operator level to fall back to with each check (as
 * fabric-permissions-api's {@code Permissions.check(player, node, level)} takes it), so the one node per {@link Perm}
 * cannot carry it: {@link #check} sets it for the calling thread around the query, and the node's default resolver
 * ({@link #fallback}) reads it. A permissions mod calls the default resolver on the same call, on the same thread, when
 * it has no answer; if it called it from another thread, that thread has no level set and the answer is no (fail
 * closed). Nested checks (a permissions mod or a resolver checking another Sculptory node) keep their own levels.
 *
 * @param <P> the player ({@code ServerPlayer})
 * @param <N> the permission node ({@code PermissionNode<Boolean>})
 */
final class PermissionMapping<P, N> {
    /** Makes the PermissionAPI node for one Sculptory node: its path under the mod id, and its default resolver. */
    interface NodeFactory<P, N> {
        N create(String path, Predicate<P> defaultResolver);
    }

    private final String prefix;
    private final BiPredicate<P, Integer> hasOpLevel;
    /** The fallback operator level of the check in progress on this thread; unset outside {@link #check}. */
    private final ThreadLocal<Integer> fallbackOpLevel = new ThreadLocal<>();
    /** One node per {@link Perm}, by Sculptory's node name ({@code sculptory.<path>}). */
    private final Map<String, N> nodes = new LinkedHashMap<>();

    /**
     * @param modId      the mod id every node name starts with (followed by a dot)
     * @param hasOpLevel whether a player has at least the given operator level
     */
    PermissionMapping(String modId, NodeFactory<P, N> factory, BiPredicate<P, Integer> hasOpLevel) {
        this.prefix = Objects.requireNonNull(modId) + ".";
        this.hasOpLevel = Objects.requireNonNull(hasOpLevel);
        for (Perm perm : Perm.values()) {
            String name = perm.node();
            if (!name.startsWith(prefix)) throw new IllegalStateException("permission node outside the mod: " + name);
            nodes.put(name, factory.create(name.substring(prefix.length()), this::fallback));
        }
    }

    /** Every node, for {@code PermissionGatherEvent.Nodes}. */
    Collection<N> nodes() {
        return List.copyOf(nodes.values());
    }

    /**
     * Asks {@code query} (the PermissionAPI) for {@code node} with {@code fallbackOpLevel} as the default's level.
     * Anything the query throws reaches the caller (the contract: the caller decides what a failure means).
     *
     * @throws IllegalArgumentException for a node name that is not one of Sculptory's {@link Perm} nodes
     */
    boolean check(P player, String node, int fallbackOpLevel, BiFunction<P, N, Boolean> query) {
        N permission = nodes.get(node);
        if (permission == null) {
            throw new IllegalArgumentException("not a registered Sculptory permission node: " + node);
        }
        Integer outer = this.fallbackOpLevel.get();
        this.fallbackOpLevel.set(fallbackOpLevel);
        try {
            return Boolean.TRUE.equals(query.apply(player, permission));
        } finally {
            if (outer == null) {
                this.fallbackOpLevel.remove();
            } else {
                this.fallbackOpLevel.set(outer);
            }
        }
    }

    /**
     * Every node's default resolver: the player has the level of the check in progress on this thread. No for an
     * offline player (the PermissionAPI passes null; there is no level to compare) and outside a check.
     */
    boolean fallback(P player) {
        Integer level = fallbackOpLevel.get();
        return player != null && level != null && hasOpLevel.test(player, level);
    }
}
