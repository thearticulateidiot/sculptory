package dev.sculptory.neoforge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.server.engine.Perm;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * {@link PermissionMapping} with stand-ins for NeoForge's types: a player with an operator level, a node with its path
 * and default resolver, and a permissions handler that grants, denies or leaves a node unset (then it asks the node's
 * default resolver, as NeoForge's {@code DefaultPermissionHandler} always does and LuckPerms does for an unset node).
 * Not covered here: the adapter lines in {@code NeoForgeServerLoader} (the real {@code PermissionNode},
 * {@code PermissionGatherEvent.Nodes}, {@code PermissionAPI.getPermission}), which need a running server.
 */
class PermissionMappingTest {
    private static final String USE = Perm.USE.node();
    private static final String ADMIN = Perm.ADMIN.node();

    record Player(String name, int opLevel) {
    }

    record Node(String path, Predicate<Player> defaultResolver) {
    }

    private static final Player BUILDER = new Player("builder", 0);
    private static final Player OPERATOR = new Player("operator", 2);

    private final PermissionMapping<Player, Node> mapping =
            new PermissionMapping<>("sculptory", Node::new, (player, level) -> player.opLevel() >= level);

    /** A permissions handler: explicit answers by node path; anything else is unset and goes to the default. */
    private static BiFunction<Player, Node, Boolean> handler(Map<String, Boolean> answers) {
        return (player, node) -> {
            Boolean answer = answers.get(node.path());
            return answer != null ? answer : node.defaultResolver().test(player);
        };
    }

    @Test
    void oneNodePerPermUnderTheModId() {
        Set<String> paths = mapping.nodes().stream().map(Node::path).collect(Collectors.toSet());
        assertEquals(Perm.values().length, paths.size());
        for (Perm perm : Perm.values()) {
            assertTrue(paths.contains(perm.node().substring("sculptory.".length())), perm.node());
        }
    }

    @Test
    void grantedAndDeniedNodesOverrideTheOperatorLevel() {
        BiFunction<Player, Node, Boolean> handler = handler(Map.of("use", true, "admin", false));
        assertTrue(mapping.check(BUILDER, USE, 2, handler), "granted to a non-operator");
        assertFalse(mapping.check(OPERATOR, ADMIN, 2, handler), "denied to an operator");
    }

    @Test
    void anUnsetNodeFallsBackToTheLevelOfThisCheck() {
        BiFunction<Player, Node, Boolean> unset = handler(Map.of());
        assertFalse(mapping.check(BUILDER, USE, 2, unset));
        assertTrue(mapping.check(OPERATOR, USE, 2, unset));
        assertFalse(mapping.check(OPERATOR, USE, 3, unset), "the level comes with each check");
        assertTrue(mapping.check(BUILDER, USE, 0, unset));
    }

    @Test
    void anOfflinePlayerIsDeniedByTheDefault() {
        // The PermissionAPI passes no player for an offline check; there is no level to compare.
        assertFalse(mapping.check(OPERATOR, USE, 0, (player, node) -> node.defaultResolver().test(null)));
    }

    @Test
    void theDefaultOutsideACheckIsNo() {
        Node node = mapping.nodes().iterator().next();
        assertFalse(node.defaultResolver().test(OPERATOR));
        assertFalse(mapping.fallback(OPERATOR));
    }

    @Test
    void aNullOrNonBooleanAnswerIsNo() {
        assertFalse(mapping.check(OPERATOR, USE, 0, (player, node) -> null));
        // The PermissionAPI's answer is generic: a handler returning another type must not throw or count as yes.
        BiFunction<Player, Node, Object> wrongType = (player, node) -> "true";
        @SuppressWarnings({"unchecked", "rawtypes"})
        BiFunction<Player, Node, Boolean> polluted = (BiFunction) wrongType;
        assertFalse(mapping.check(OPERATOR, USE, 0, polluted));
    }

    @Test
    void checksFromOtherThreadsUseTheirOwnLevel() throws Exception {
        BiFunction<Player, Node, Boolean> unset = handler(Map.of());
        // Two threads in a check at the same time, with different levels: each default sees its own.
        CountDownLatch bothInside = new CountDownLatch(2);
        BiFunction<Player, Node, Boolean> waiting = (player, node) -> {
            bothInside.countDown();
            try {
                assertTrue(bothInside.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return unset.apply(player, node);
        };
        CompletableFuture<Boolean> low = CompletableFuture.supplyAsync(() -> mapping.check(OPERATOR, USE, 2, waiting));
        CompletableFuture<Boolean> high = CompletableFuture.supplyAsync(() -> mapping.check(OPERATOR, USE, 4, waiting));
        assertTrue(low.get(10, TimeUnit.SECONDS));
        assertFalse(high.get(10, TimeUnit.SECONDS));
    }

    @Test
    void aDefaultAskedFromAnotherThreadFailsClosed() throws Exception {
        // A permissions mod that resolves the default on its own thread: that thread has no check in progress.
        BiFunction<Player, Node, Boolean> offThread = (player, node) ->
                CompletableFuture.supplyAsync(() -> node.defaultResolver().test(player)).join();
        assertFalse(mapping.check(OPERATOR, USE, 0, offThread));
    }

    @Test
    void nestedChecksKeepTheirOwnLevels() {
        BiFunction<Player, Node, Boolean> unset = handler(Map.of());
        Map<String, Boolean> seen = new HashMap<>();
        BiFunction<Player, Node, Boolean> nesting = (player, node) -> {
            seen.put("outer before", node.defaultResolver().test(player));
            // A permissions mod (or a resolver) checking another Sculptory node with a higher level, mid-check.
            seen.put("inner", mapping.check(player, ADMIN, 4, unset));
            seen.put("outer after", node.defaultResolver().test(player));
            return node.defaultResolver().test(player);
        };
        assertTrue(mapping.check(OPERATOR, USE, 2, nesting));
        assertEquals(Map.of("outer before", true, "inner", false, "outer after", true), seen);
        assertFalse(mapping.fallback(OPERATOR), "the level is cleared after the outermost check");
    }

    @Test
    void anUnknownNodeThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> mapping.check(OPERATOR, "sculptory.no.such.node", 0, handler(Map.of())));
        assertTrue(e.getMessage().contains("sculptory.no.such.node"));
        assertThrows(IllegalArgumentException.class, () -> mapping.check(OPERATOR, "other.use", 0, handler(Map.of())));
    }

    @Test
    void aNodeThePermissionApiDoesNotKnowReachesTheCaller() {
        // PermissionAPI.getPermission throws UnregisteredPermissionException (an IllegalStateException) for a node
        // that was not gathered: the caller sees it, not a silent false, and the thread's level is restored.
        IllegalStateException unregistered = new IllegalStateException("unregistered node");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mapping.check(OPERATOR, USE, 0, (player, node) -> {
                    throw unregistered;
                }));
        assertSame(unregistered, thrown);
        assertFalse(mapping.fallback(OPERATOR));
    }

    @Test
    void aFailingHandlerReachesTheCallerAndRestoresTheOuterLevel() {
        BiFunction<Player, Node, Boolean> failing = (player, node) -> {
            throw new UnsupportedOperationException("permissions backend down");
        };
        Map<String, Boolean> seen = new HashMap<>();
        BiFunction<Player, Node, Boolean> outer = (player, node) -> {
            assertThrows(UnsupportedOperationException.class, () -> mapping.check(player, ADMIN, 4, failing));
            seen.put("outer after the failure", node.defaultResolver().test(player));
            return true;
        };
        assertTrue(mapping.check(OPERATOR, USE, 2, outer));
        assertEquals(Map.of("outer after the failure", true), seen);
        assertFalse(mapping.fallback(OPERATOR));
    }

    @Test
    void aNodeOutsideTheModIsRefusedAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new PermissionMapping<Player, Node>("other", Node::new, (player, level) -> true));
    }
}
