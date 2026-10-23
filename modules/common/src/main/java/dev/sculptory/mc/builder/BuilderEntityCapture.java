package dev.sculptory.mc.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * One builder-mode use of an item that puts an entity into the world (an item frame, a painting, an armor stand): while
 * it is open, every entity the opening thread adds to that world is noted ({@code BuilderEntityCaptureMixin}, at
 * {@code ServerLevel.addFreshEntity}, which every such item goes through), so the history can record what the game's
 * own use of the item spawned. Server thread only; one capture at a time; captures do not nest. The hook costs one
 * volatile read per entity added while none is open.
 */
public final class BuilderEntityCapture implements AutoCloseable {
    private static volatile BuilderEntityCapture open;

    private final ServerLevel world;
    private final Thread thread;
    private final List<Entity> added = new ArrayList<>();

    private BuilderEntityCapture(ServerLevel world) {
        this.world = world;
        this.thread = Thread.currentThread();
    }

    /** Opens a capture of {@code world} on the current thread, or returns {@code null} when one is already open. */
    public static BuilderEntityCapture open(ServerLevel world) {
        Objects.requireNonNull(world);
        synchronized (BuilderEntityCapture.class) {
            if (open != null) return null;
            BuilderEntityCapture capture = new BuilderEntityCapture(world);
            open = capture;
            return capture;
        }
    }

    /** The hook: {@code entity} was added to {@code world}. */
    public static void added(ServerLevel world, Entity entity) {
        BuilderEntityCapture capture = open;
        if (capture == null || capture.world != world || capture.thread != Thread.currentThread()) return;
        capture.added.add(entity);
    }

    /** The entities added while the capture was open, in order. */
    public List<Entity> entities() {
        return List.copyOf(added);
    }

    @Override
    public void close() {
        synchronized (BuilderEntityCapture.class) {
            if (open == this) open = null;
        }
    }
}
