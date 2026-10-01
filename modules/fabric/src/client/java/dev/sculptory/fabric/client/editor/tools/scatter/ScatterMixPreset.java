package dev.sculptory.fabric.client.editor.tools.scatter;

import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.FeatureCatalog;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.fabric.client.editor.presets.PresetExtra;
import dev.sculptory.fabric.client.session.LibraryFolder;
import dev.sculptory.fabric.client.session.Notice;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The Scatter tool's variant mix in its presets (extra {@value #KEY}): the variants, in order, as entries joined by
 * {@code ;}. Each entry starts with its kind and a colon, so new kinds can be added without touching the others:
 * <ul>
 *   <li>{@code asset:<weight>:<sha256>:<path>}: a library asset (library paths never contain {@code ;} or
 *       {@code :}).</li>
 *   <li>{@code block:<weight>:<state>}: one block state (for example {@code minecraft:pink_petals[facing=east]});
 *       state text never contains {@code ;}. On applying it is fitted to this game
 *       ({@link ScatterTool#blockVariantText}): a block this game doesn't have, or can't scatter, is skipped like any
 *       unusable entry.</li>
 *   <li>{@code feature:<weight>:<id>}: a vanilla tree or feature by its configured feature id (for example
 *       {@code minecraft:fancy_oak}); an id this build's {@code FeatureCatalog} does not list is skipped.</li>
 * </ul>
 * A new kind is one more {@code case} in {@link #read} and in {@link #capture}; its text after the kind must not
 * contain {@code ;}. A build that doesn't know a kind skips those entries (reported to the player) and keeps the rest.
 * A clipboard variant lasts only as long as the server holds that clipboard, so it is left out, with a note. Applying
 * replaces the whole mix; entries that don't read (an unknown kind, a bad weight or hash, a repeat, more than
 * {@value ScatterMix#MAX_VARIANTS}) are reported as skipped. Whether an asset is still in the library is only known
 * once its preview is asked for (the server says): the session reports the refusal and the variant's row in the panel
 * says it failed.
 */
public final class ScatterMixPreset implements PresetExtra {
    public static final String KEY = "mix";
    public static final String ASSET = "asset";
    public static final String BLOCK = "block";
    public static final String FEATURE = "feature";

    private final ScatterTool tool;
    private String parsedText;
    private Parsed parsed;

    /** A mix read from preset text, and the entries that couldn't be used. */
    record Parsed(List<ScatterMix.Variant> variants, List<String> unusable) {}

    public ScatterMixPreset(ScatterTool tool) {
        this.tool = Objects.requireNonNull(tool);
    }

    @Override
    public Capture capture() {
        List<String> entries = new ArrayList<>();
        int clipboards = 0;
        for (ScatterMix.Variant variant : tool.mix().variants()) {
            switch (variant.source()) {
                case ScatterSource.Held(SourceRef.Asset asset) -> entries.add(String.join(":", ASSET,
                        Integer.toString(variant.weight()), asset.contentHash(), safeName(variant.name())));
                case ScatterSource.Held(SourceRef.Clipboard clipboard) -> clipboards++;
                case ScatterSource.Block block -> entries.add(String.join(":", BLOCK,
                        Integer.toString(variant.weight()), block.state()));
                case ScatterSource.Feature feature -> entries.add(String.join(":", FEATURE,
                        Integer.toString(variant.weight()), feature.id()));
            }
        }
        Notice note = clipboards == 0 ? null : Notice.of(Notice.Level.INFO,
                "sculptory.preset.notice.clipboard_left_out", Integer.toString(clipboards));
        return new Capture(String.join(";", entries), note);
    }

    @Override
    public Applied apply(String text) {
        Parsed mix = parse(text);
        tool.replaceMix(mix.variants());
        return new Applied(mix.unusable(), List.of());
    }

    @Override
    public boolean matches(String text) {
        List<ScatterMix.Variant> wanted = parse(text).variants();
        List<ScatterMix.Variant> current = tool.mix().variants();
        if (wanted.size() != current.size()) {
            return false;
        }
        for (int i = 0; i < wanted.size(); i++) {
            ScatterMix.Variant a = wanted.get(i);
            ScatterMix.Variant b = current.get(i);
            if (!a.source().equals(b.source()) || a.weight() != b.weight()) {
                return false;
            }
        }
        return true;
    }

    /** The last text parsed is kept: "(modified)" asks every frame. */
    private Parsed parse(String text) {
        if (!text.equals(parsedText)) {
            parsed = read(text);
            parsedText = text;
        }
        return parsed;
    }

    Parsed read(String text) {
        List<ScatterMix.Variant> variants = new ArrayList<>();
        List<String> unusable = new ArrayList<>();
        Set<ScatterSource> seen = new HashSet<>();
        if (text.isEmpty()) {
            return new Parsed(List.of(), List.of());
        }
        for (String entry : text.split(";", -1)) {
            int colon = entry.indexOf(':');
            String kind = colon < 0 ? "" : entry.substring(0, colon);
            String rest = colon < 0 ? "" : entry.substring(colon + 1);
            Optional<ScatterMix.Variant> variant = switch (kind) {
                case ASSET -> asset(rest);
                case BLOCK -> block(rest);
                case FEATURE -> feature(rest);
                default -> Optional.empty();
            };
            boolean usable = variant.isPresent() && variants.size() < ScatterMix.MAX_VARIANTS
                    && seen.add(variant.get().source());
            if (usable) {
                variants.add(variant.get());
            } else {
                unusable.add(label(entry));
            }
        }
        return new Parsed(List.copyOf(variants), List.copyOf(unusable));
    }

    /** {@code <weight>:<sha256>:<path>}. */
    private static Optional<ScatterMix.Variant> asset(String text) {
        String[] parts = text.split(":", 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        Optional<SourceRef> source = LibraryFolder.asset(parts[1]);
        int weight = weight(parts[0]);
        if (source.isEmpty() || weight < 1) {
            return Optional.empty();
        }
        return Optional.of(new ScatterMix.Variant(new ScatterSource.Held(source.get()),
                parts[2].isBlank() ? "?" : parts[2], weight));
    }

    /** {@code <weight>:<state>}, fitted to this game; empty when the block can't be scattered here. */
    private Optional<ScatterMix.Variant> block(String text) {
        String[] parts = text.split(":", 2);
        int weight = parts.length == 2 ? weight(parts[0]) : -1;
        if (weight < 1) {
            return Optional.empty();
        }
        return tool.blockVariantText(parts[1]).map(state -> new ScatterMix.Variant(new ScatterSource.Block(state),
                tool.blockVariantName(state), weight));
    }

    /** {@code <weight>:<id>}, a tree or feature of {@code FeatureCatalog}; empty for any other id. */
    private Optional<ScatterMix.Variant> feature(String text) {
        String[] parts = text.split(":", 2);
        int weight = parts.length == 2 ? weight(parts[0]) : -1;
        if (weight < 1 || FeatureCatalog.find(parts[1]).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ScatterMix.Variant(new ScatterSource.Feature(parts[1]), tool.featureName(parts[1]),
                weight));
    }

    /** What the player is told about an entry that couldn't be used: its last part (a path), or the entry. */
    private static String label(String entry) {
        String last = entry.substring(entry.lastIndexOf(':') + 1);
        return !last.isBlank() ? last : entry.isEmpty() ? "?" : entry;
    }

    private static int weight(String text) {
        try {
            int weight = Integer.parseInt(text.trim());
            return weight >= 1 && weight <= ScatterMix.MAX_WEIGHT ? weight : -1;
        } catch (NumberFormatException malformed) {
            return -1;
        }
    }

    /** A display name that can't break the entry format. */
    private static String safeName(String name) {
        StringBuilder safe = new StringBuilder(name.length());
        name.codePoints().forEach(c -> {
            boolean breaksFormat = c == ';' || c == ':' || Character.isISOControl(c);
            safe.appendCodePoint(breaksFormat ? '_' : c);
        });
        return safe.toString();
    }
}
