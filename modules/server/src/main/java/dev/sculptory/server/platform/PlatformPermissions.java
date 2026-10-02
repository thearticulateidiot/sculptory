package dev.sculptory.server.platform;

import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.PermissionService;

/**
 * The platform's permission nodes and chunk protection as the engine asks them ({@link Platform#permissions}). A
 * permissions backend that fails (throws) grants nothing new ({@link #has} is {@code false}) but is no definite no
 * either ({@link #denied} is {@code false} too), so re-checks of work already admitted leave that work alone.
 *
 * @param <P> the platform's player type
 * @param <W> the platform's world type
 */
public interface PlatformPermissions<P, W> extends PermissionService<P, W> {
    /** Whether the player definitely lacks the node: {@code false} when it is granted or the backend failed. */
    boolean denied(P p, Perm node);

    /** Whether operator-only block-entity and entity data is kept for this player. */
    boolean mayWriteOperatorNbt(P p);

    /** Whether the player definitely may not keep operator-only data: {@code false} when the backend failed. */
    boolean operatorNbtDenied(P p);
}
