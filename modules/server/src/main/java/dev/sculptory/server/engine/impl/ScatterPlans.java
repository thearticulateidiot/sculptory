package dev.sculptory.server.engine.impl;

import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.server.library.LibraryPath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The players' scatter plans (M3), in memory only: <b>one live plan per player</b>, pinned to the world it was
 * planned in and valid for {@link #TTL_NANOS}. A new preview drops the old plan (whose id then stops resolving); a
 * commit consumes it; it is dropped when it expires, when the player leaves or changes world.
 *
 * <p><b>Memory.</b> A plan keeps its sources (the clipboards it was planned with, usually shared with the player's
 * clipboard and the asset cache) so a commit writes exactly what was previewed, plus about 96 bytes per placement.
 * With one plan per player, what a player can retain is bounded by the preview caps: at most
 * {@code ScatterPlanner.MAX_SOURCE_CELLS} (2,097,152) source cells and {@code ScatterSettings.MAX_PLACEMENTS}
 * placements (about 12 MB), plus the cells its trees and features grew, at most {@code scatter.maxFeatureCells}
 * (about 16 bytes each, 16 MB at the default). {@link #retainedCells()} reports the total. Server thread only.
 */
public final class ScatterPlans {
    /** How long a plan may be committed after it was made. */
    public static final long TTL_NANOS = 10L * 60 * 1_000_000_000L;

    /**
     * A held plan, the id the client refers to it by, its world and when it was made ({@code System.nanoTime} scale).
     *
     * @param libraryPaths per plan source, its library path when it is a library asset (read access is checked
     *     again at commit), or {@code null} for the player's clipboard or a block
     * @param blockVariants whether some source is a plain block (the player's {@code brush} or {@code region} is
     *     checked again at commit)
     */
    public record Held(UUID id, UUID owner, String worldId, ScatterPlan plan, long createdNanos,
                       List<LibraryPath> libraryPaths, boolean blockVariants) {
        public Held {
            Objects.requireNonNull(id);
            Objects.requireNonNull(owner);
            Objects.requireNonNull(worldId);
            Objects.requireNonNull(plan);
            libraryPaths = Collections.unmodifiableList(new ArrayList<>(libraryPaths));
            if (libraryPaths.size() != plan.sources().size()) throw new IllegalArgumentException("One path per source");
        }

        /** Source cells the plan keeps alive. */
        public long retainedCells() {
            long cells = plan.grownCells();
            for (Clipboard source : plan.sources()) cells += source.cellCount();
            return cells;
        }

        boolean expired(long now) {
            return now - createdNanos >= TTL_NANOS;
        }
    }

    private final Map<UUID, Held> byPlayer = new HashMap<>();

    /** Makes {@code plan} the player's plan under a fresh id, replacing any other. */
    public Held put(UUID owner, String worldId, ScatterPlan plan, long now, List<LibraryPath> libraryPaths,
                    boolean blockVariants) {
        Held held = new Held(UUID.randomUUID(), owner, worldId, plan, now, libraryPaths, blockVariants);
        byPlayer.put(owner, held);
        return held;
    }

    /** A plan whose sources are all clipboards. */
    public Held put(UUID owner, String worldId, ScatterPlan plan, long now) {
        return put(owner, worldId, plan, now, Collections.nCopies(plan.sources().size(), null), false);
    }

    /** Source cells the live plans keep alive, over all players. */
    public long retainedCells() {
        long cells = 0;
        for (Held held : byPlayer.values()) cells += held.retainedCells();
        return cells;
    }

    /**
     * The player's plan {@code planId} if it is still valid in {@code worldId}. An expired plan, or one made in
     * another world, is dropped.
     */
    public Optional<Held> find(UUID owner, UUID planId, String worldId, long now) {
        Held held = byPlayer.get(owner);
        if (held == null || !held.id().equals(planId)) return Optional.empty();
        if (held.expired(now) || !held.worldId().equals(worldId)) {
            byPlayer.remove(owner);
            return Optional.empty();
        }
        return Optional.of(held);
    }

    /** The player's plan, valid or not. */
    public Optional<Held> get(UUID owner) {
        return Optional.ofNullable(byPlayer.get(owner));
    }

    /** Drops the player's plan if it is {@code planId} (a commit consumed it). */
    public void consume(UUID owner, UUID planId) {
        Held held = byPlayer.get(owner);
        if (held != null && held.id().equals(planId)) byPlayer.remove(owner);
    }

    public void remove(UUID owner) {
        byPlayer.remove(owner);
    }

    /**
     * Drops the player's plan when one of its sources is the library file {@code path} (per-asset access: the player
     * lost the right to read it, so the plan ends as after a deletion).
     *
     * @return whether a plan was dropped
     */
    public boolean dropIfUses(UUID owner, LibraryPath path) {
        Held held = byPlayer.get(owner);
        if (held == null || !held.libraryPaths().contains(path)) return false;
        byPlayer.remove(owner);
        return true;
    }

    /** The players whose held plan has the library file {@code path} among its sources. */
    public List<UUID> ownersUsing(LibraryPath path) {
        List<UUID> owners = new ArrayList<>();
        for (Held held : byPlayer.values()) {
            if (held.libraryPaths().contains(path)) owners.add(held.owner());
        }
        return owners;
    }

    /**
     * Drops every plan (whoever holds it) with a library source {@code drop} accepts.
     *
     * @return how many were dropped
     */
    public int dropUsing(java.util.function.Predicate<LibraryPath> drop) {
        List<UUID> gone = new ArrayList<>();
        for (Held held : byPlayer.values()) {
            for (LibraryPath path : held.libraryPaths()) {
                if (path != null && drop.test(path)) {
                    gone.add(held.owner());
                    break;
                }
            }
        }
        gone.forEach(byPlayer::remove);
        return gone.size();
    }

    /**
     * Library management moved {@code from} (a file, or a folder and everything in it) to {@code to}: the plans keep
     * their sources and take the new paths, which is what a commit (and a revocation) checks against; a plan whose
     * source was spelt otherwise (a file system that ignores case) is dropped, like the cached asset.
     */
    public void moved(LibraryPath from, LibraryPath to) {
        for (Map.Entry<UUID, Held> entry : new ArrayList<>(byPlayer.entrySet())) {
            Held held = entry.getValue();
            List<LibraryPath> paths = new ArrayList<>(held.libraryPaths());
            boolean changed = false;
            for (int i = 0; i < paths.size(); i++) {
                LibraryPath path = paths.get(i);
                if (path == null) continue;
                LibraryPath moved = AssetCache.movedPath(path, from, to);
                if (moved != null) {
                    paths.set(i, moved);
                    changed = true;
                } else if (AssetCache.inIgnoringCase(path, from)) {
                    byPlayer.remove(entry.getKey());
                    changed = false;
                    break;
                }
            }
            if (changed) {
                byPlayer.put(entry.getKey(), new Held(held.id(), held.owner(), held.worldId(), held.plan(),
                        held.createdNanos(), paths, held.blockVariants()));
            }
        }
    }

    /**
     * Library management deleted {@code path} (a file, or a folder): the plans using it (under any spelling) are
     * dropped, so what is no longer in the library is not placed from a plan either.
     */
    public void removed(LibraryPath path) {
        dropUsing(source -> AssetCache.inIgnoringCase(source, path));
    }

    /**
     * Drops expired plans, and plans whose player is gone or now in another world ({@code currentWorld} gives the
     * player's world id, or {@code null} when offline).
     *
     * @return how many were dropped
     */
    public int sweep(long now, Function<UUID, String> currentWorld) {
        List<UUID> drop = new ArrayList<>();
        for (Held held : byPlayer.values()) {
            String world = currentWorld.apply(held.owner());
            if (held.expired(now) || world == null || !world.equals(held.worldId())) drop.add(held.owner());
        }
        drop.forEach(byPlayer::remove);
        return drop.size();
    }

    public void clear() {
        byPlayer.clear();
    }

    public int size() {
        return byPlayer.size();
    }
}
