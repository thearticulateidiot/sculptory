package dev.sculptory.fabric.client.editor.tools.scatter;

import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The Scatter tool's variant mix: up to {@value #MAX_VARIANTS} sources (clipboards, library assets and single blocks
 * by their exact state), each with a display name and a weight of 1 to {@value #MAX_WEIGHT}. A source appears once
 * (a block: once per exact state). The server holds one clipboard per player, so at most one clipboard variant can be
 * live: adding a clipboard replaces any other clipboard variant (whose id is dead by then). Pure;
 * client thread only.
 */
public final class ScatterMix {
    public static final int MAX_VARIANTS = ScatterSettings.MAX_VARIANTS;
    public static final int MAX_WEIGHT = ScatterSettings.MAX_WEIGHT;
    public static final int DEFAULT_WEIGHT = 10;

    /** One variant: a clipboard, a library asset or a block, shown as {@code name}. */
    public record Variant(ScatterSource source, String name, int weight) {
        public Variant {
            Objects.requireNonNull(source);
            Objects.requireNonNull(name);
            if (weight < 1 || weight > MAX_WEIGHT) throw new IllegalArgumentException("Weight must be 1-" + MAX_WEIGHT);
        }

        public Variant withWeight(int newWeight) {
            return new Variant(source, name, newWeight);
        }
    }

    /** What adding did. */
    public enum AddResult {
        ADDED,
        /** Added, replacing an older clipboard variant. */
        REPLACED_CLIPBOARD,
        /** The source is in the mix already. */
        DUPLICATE,
        /** {@value #MAX_VARIANTS} variants already. */
        FULL
    }

    private final List<Variant> variants = new ArrayList<>();
    private int version;
    private int generation;

    public AddResult add(SourceRef source, String name) {
        return add(new ScatterSource.Held(source), name);
    }

    public AddResult add(ScatterSource source, String name) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(name);
        for (Variant variant : variants) {
            if (variant.source().equals(source)) return AddResult.DUPLICATE;
        }
        boolean replaced = isClipboard(source) && variants.removeIf(variant -> isClipboard(variant.source()));
        if (!replaced && variants.size() >= MAX_VARIANTS) return AddResult.FULL;
        variants.add(new Variant(source, name.isBlank() ? "?" : name, DEFAULT_WEIGHT));
        version++;
        return replaced ? AddResult.REPLACED_CLIPBOARD : AddResult.ADDED;
    }

    /** Whether {@code source} is a clipboard (the server keeps one per player). */
    public static boolean isClipboard(ScatterSource source) {
        return source instanceof ScatterSource.Held held && held.ref() instanceof SourceRef.Clipboard;
    }

    /** Whether {@code source} is a library asset. */
    public static boolean isAsset(ScatterSource source) {
        return source instanceof ScatterSource.Held held && held.ref() instanceof SourceRef.Asset;
    }

    public void remove(int index) {
        Objects.checkIndex(index, variants.size());
        variants.remove(index);
        version++;
    }

    /** Sets a weight, clamped to 1-{@value #MAX_WEIGHT}; returns whether it changed. */
    public boolean setWeight(int index, int weight) {
        Objects.checkIndex(index, variants.size());
        int clamped = Math.max(1, Math.min(MAX_WEIGHT, weight));
        Variant variant = variants.get(index);
        if (variant.weight() == clamped) return false;
        variants.set(index, variant.withWeight(clamped));
        version++;
        return true;
    }

    /**
     * Replaces every variant (a preset was applied). The sources must be distinct, at most one a clipboard, and there
     * may be at most {@value #MAX_VARIANTS}.
     */
    public void replaceAll(List<Variant> next) {
        List<Variant> copy = List.copyOf(next);
        if (copy.size() > MAX_VARIANTS) throw new IllegalArgumentException("More than " + MAX_VARIANTS + " variants");
        if (copy.stream().map(Variant::source).distinct().count() != copy.size()) {
            throw new IllegalArgumentException("A source appears twice");
        }
        if (copy.stream().filter(variant -> isClipboard(variant.source())).count() > 1) {
            throw new IllegalArgumentException("More than one clipboard variant");
        }
        variants.clear();
        variants.addAll(copy);
        version++;
        generation++;
    }

    public List<Variant> variants() {
        return List.copyOf(variants);
    }

    public int size() {
        return variants.size();
    }

    public boolean isEmpty() {
        return variants.isEmpty();
    }

    /** Bumped on every change. */
    public int version() {
        return version;
    }

    /** Bumped when the whole mix is replaced ({@link #replaceAll}): views of the weights show them again. */
    public int generation() {
        return generation;
    }
}
