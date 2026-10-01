package dev.sculptory.fabric.world;

/**
 * Suppresses block physics ({@code AbstractBlockState.onBlockAdded}/{@code onStateReplaced}, cancelled by
 * {@code AbstractBlockStateMixin}) for writes made on the owning thread while a scope is open.
 *
 * <p>The guard is a static owner thread plus a depth counter rather than a flag: the client and the integrated
 * server share one JVM, and the client thread must never see suppression opened by the server thread. Scopes
 * nest on the owning thread; opening one on another thread while it is held is a programming error.
 *
 * <pre>{@code
 * try (EditScope scope = EditScope.suppressPhysics()) {
 *     world.setBlockState(pos, state, Block.FORCE_STATE, 0);
 * }
 * }</pre>
 */
public final class EditScope implements AutoCloseable {
    private static final EditScope INSTANCE = new EditScope();

    /** Read lock-free by {@link #isSuppressing()}; written under the class lock. */
    private static volatile Thread owner;
    /** Guarded by the class lock. */
    private static int depth;

    private EditScope() {}

    /** Opens (or nests) a suppression scope on the current thread. */
    public static synchronized EditScope suppressPhysics() {
        Thread current = Thread.currentThread();
        Thread held = owner;
        if (held != null && held != current) {
            throw new IllegalStateException("EditScope is held by thread " + held.getName());
        }
        owner = current;
        depth++;
        return INSTANCE;
    }

    /** True when the current thread holds an open scope. Called by the mixin on every block add/replace. */
    public static boolean isSuppressing() {
        return owner == Thread.currentThread();
    }

    /** Current nesting depth for the calling thread (0 when it holds no scope). */
    public static synchronized int depth() {
        return isSuppressing() ? depth : 0;
    }

    @Override
    public void close() {
        synchronized (EditScope.class) {
            if (owner != Thread.currentThread()) {
                throw new IllegalStateException("EditScope closed on a non-owner thread");
            }
            if (--depth == 0) owner = null;
        }
    }
}
