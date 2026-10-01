package dev.sculptory.fabric.client.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The library changes a session has seen (M4), oldest first: the answers to this player's own changes and the ones the
 * server pushed. A Library window remembers the {@link #version()} it has followed and asks for what came
 * {@link #since} then, to list its folder again when it changed (or to follow its folder when that was renamed).
 * Keeps the last {@value #MAX_KEPT}; a window further behind is told to list again. Render thread only.
 */
public final class LibraryChanges {
    public static final int MAX_KEPT = 64;
    /** A log nothing is ever added to, for sessions without a library. */
    public static final LibraryChanges NONE = new LibraryChanges(true);

    private final ArrayDeque<LibraryChange> recent = new ArrayDeque<>();
    private final boolean frozen;
    private long version;

    public LibraryChanges() {
        this(false);
    }

    private LibraryChanges(boolean frozen) {
        this.frozen = frozen;
    }

    /** How many changes were ever added. */
    public long version() {
        return version;
    }

    /**
     * The changes added after {@code version} (one this log returned before), oldest first; empty when some of them
     * are no longer kept, so the caller should simply list again.
     */
    public Optional<List<LibraryChange>> since(long version) {
        long missed = this.version - version;
        if (missed <= 0) return Optional.of(List.of());
        if (missed > recent.size()) return Optional.empty();
        List<LibraryChange> all = new ArrayList<>(recent);
        return Optional.of(List.copyOf(all.subList(all.size() - (int) missed, all.size())));
    }

    public void add(LibraryChange change) {
        Objects.requireNonNull(change);
        if (frozen) return;
        recent.addLast(change);
        while (recent.size() > MAX_KEPT) recent.removeFirst();
        version++;
    }
}
