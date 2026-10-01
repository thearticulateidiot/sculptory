package dev.sculptory.fabric.client.editor.tools.place;

import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.render.ghost.GhostMapping;
import dev.sculptory.fabric.client.editor.render.ghost.GhostMasking;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps the Place tool's ghosts in step with its "Paste into" setting: each
 * placement shown is cut down to the cells the server would write ({@link GhostBaker.Filter}), one placement at a
 * time, a few sections per frame, so a ghost that keeps moving shows whole until it rests. A filtered volume is
 * judged once and kept while its placement (the same volume, at the same place, with the same transform and
 * setting) is still shown, and released when it is not: world changes under a resting placement (the player's own
 * paste, another player's edit, a chunk arriving after it was judged unloaded) are not seen until the placement
 * moves, turns or the setting changes. A volume without a frame yet (a streamed clipboard before its size is known,
 * so no {@link GhostMapping}) is shown whole. Client thread only.
 */
final class GhostFilter {
    /** Volume sections judged per frame (up to 4096 cells each). */
    static final int SECTIONS_PER_FRAME = 4;

    /**
     * What a filtered volume depends on: the volume (by identity, at its version), where it lands, the setting and the
     * global mask ({@link GhostMasking#revision}).
     */
    private record Key(GhostVolume volume, long version, GhostMapping mapping, PasteOptions.Into into, long mask) {}

    private final Consumer<GhostVolume> release;
    private final Map<Key, GhostVolume> ready = new HashMap<>();
    private @Nullable Key working;
    private @Nullable GhostBaker.Filter filter;

    /** @param release frees a filtered volume's meshes once it is no longer shown */
    GhostFilter(Consumer<GhostVolume> release) {
        this.release = Objects.requireNonNull(release);
    }

    /**
     * The placements to show this frame: each with its filtered volume once that is ready, the placement itself
     * meanwhile (and always with {@code EVERYTHING} while the global mask is off, which drops every filtered volume).
     * The global mask cuts the ghosts as the Into setting does.
     */
    List<GhostPlacement> apply(WorldReader world, List<GhostPlacement> placements, PasteOptions.Into into) {
        BoundMask mask = GhostMasking.current(world.states());
        long maskRevision = GhostMasking.revision();
        if ((into == PasteOptions.Into.EVERYTHING && mask.acceptsAll()) || placements.isEmpty()) {
            clear();
            return placements;
        }
        List<Key> keys = new ArrayList<>(placements.size());
        Set<Key> wanted = new HashSet<>();
        for (GhostPlacement placement : placements) {
            GhostMapping mapping = placement.mapping();
            Key key = mapping == null ? null
                    : new Key(placement.volume(), placement.volume().version(), mapping, into, maskRevision);
            keys.add(key);
            if (key != null) wanted.add(key);
        }
        ready.entrySet().removeIf(entry -> {
            if (wanted.contains(entry.getKey())) return false;
            releaseOwn(entry.getKey(), entry.getValue());
            return true;
        });
        if (working != null && !wanted.contains(working)) {
            working = null;
            filter = null;
        }
        if (working == null) {
            for (Key key : keys) {
                if (key != null && !ready.containsKey(key)) {
                    working = key;
                    filter = new GhostBaker.Filter(world, key.volume(), key.mapping(), into, mask);
                    break;
                }
            }
        }
        if (filter != null && filter.step(SECTIONS_PER_FRAME)) {
            ready.put(working, filter.build());
            working = null;
            filter = null;
        }
        List<GhostPlacement> shown = new ArrayList<>(placements.size());
        for (int i = 0; i < placements.size(); i++) {
            GhostPlacement placement = placements.get(i);
            Key key = keys.get(i);
            GhostVolume filtered = key == null ? null : ready.get(key);
            shown.add(filtered == null || filtered == placement.volume() ? placement
                    : new GhostPlacement(filtered, placement.originX(), placement.originY(), placement.originZ(),
                            placement.transform(), placement.alpha(), placement.lightMode()));
        }
        return shown;
    }

    /** Whether a placement is still being judged (its ghost shows whole meanwhile). */
    boolean pending() {
        return working != null;
    }

    /** Drops every filtered volume (releasing their meshes) and the work in progress. */
    void clear() {
        ready.forEach(this::releaseOwn);
        ready.clear();
        working = null;
        filter = null;
    }

    /** Releases a filtered volume unless it is the source itself (nothing was dropped from it). */
    private void releaseOwn(Key key, GhostVolume filtered) {
        if (filtered != key.volume()) release.accept(filtered);
    }
}
