package dev.sculptory.protocol.v2;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Named optional features, negotiated in {@code Hello}/{@code Welcome}. At most {@value #MAX_FEATURES}. */
public record Features(SortedSet<String> names) {
    public static final int MAX_FEATURES = 64;
    public static final String STROKES = "strokes";
    public static final String REGION_OPS = "region_ops";
    public static final String HISTORY = "history";
    public static final String CLIPBOARD = "clipboard";
    public static final String LIBRARY = "library";
    public static final String SCHEMATICS = "schematics";
    public static final String SCATTER = "scatter";
    /**
     * Builder mode (protocol 5): {@code BuilderPowers}, {@code BuilderPlace}, {@code BuilderBreak} and
     * {@code BuilderDragEnd}. A client offers it; the server lists it when it carries them out.
     */
    public static final String BUILDER = "builder";
    /**
     * The server turns modded blocks that do not turn themselves by their facing, axis or rotation
     * (config {@code transform.moddedFacingFallback}). Offered only while it is on; a client
     * that sees it negotiated turns its ghost previews the same way. A name only: no message changes with it.
     */
    public static final String MODDED_FACING_FALLBACK = "modded_facing_fallback";
    /**
     * Tinker (protocol 5): the server answers {@code TinkerBlock} and
     * {@code TinkerEntity}. A client uses the tool only when it was negotiated.
     */
    public static final String TINKER = "tinker";
    /**
     * The global mask (protocol 5): the server answers {@code SetEditMask} and applies
     * the mask to every edit. A client with its mask on refuses to send edits to a server without it.
     */
    public static final String EDIT_MASK = "edit_mask";
    /**
     * Jump and Through (protocol 5): the server answers
     * {@code Navigate}.
     */
    public static final String NAVIGATE = "navigate";

    private static final Pattern NAME = Pattern.compile("[a-z0-9_.:-]{1,64}");

    public static final Features NONE = new Features(new TreeSet<>());

    public Features {
        Objects.requireNonNull(names);
        if (names.size() > MAX_FEATURES) throw new IllegalArgumentException("Too many features");
        TreeSet<String> copy = new TreeSet<>();
        for (String name : names) {
            if (name == null || !NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid feature name: " + name);
            }
            copy.add(name);
        }
        names = Collections.unmodifiableSortedSet(copy);
    }

    public static Features of(String... names) {
        return new Features(new TreeSet<>(List.of(names)));
    }

    public boolean has(String name) {
        return names.contains(name);
    }

    /** These features and {@code more}. */
    public Features with(String... more) {
        TreeSet<String> all = new TreeSet<>(names);
        all.addAll(List.of(more));
        return new Features(all);
    }

    /** The features both sides support. */
    public Features intersect(Features other) {
        TreeSet<String> common = new TreeSet<>(names);
        common.retainAll(other.names);
        return new Features(common);
    }
}
