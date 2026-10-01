package dev.sculptory.fabric.client.editor.presets;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.fabric.client.editor.settings.Section;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.protocol.v2.Limits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Turns a preset's saved text into values for a tool's current schema, leniently, and says what it had to change:
 * <ul>
 *   <li>a setting the preset doesn't mention takes its default (it was added after the preset was saved);</li>
 *   <li>keys the schema doesn't have, and values that don't decode or can't be used, are <b>skipped</b> (the setting
 *       keeps its default);</li>
 *   <li>values outside the setting's bounds or the server's limits are <b>adjusted</b> to fit: numbers are clamped (a
 *       radius above the server's {@code maxBrushRadius} becomes that maximum), palettes shortened. A value that still
 *       doesn't validate is skipped;</li>
 *   <li>blocks this game doesn't have (a mod that isn't installed) are <b>unavailable</b> where they choose what is
 *       placed: a palette keeps its other blocks, and a single block or a palette left empty is skipped.</li>
 * </ul>
 * <b>Filters are never widened.</b> A section holding a block list or a range (a brush's Mask, the Scatter tool's
 * Filters) limits where an edit applies, and changing one part of it can widen it: dropping a block from a list, or
 * clamping a range to its full span (which means "no limit"), and under "Invert mask" even a shorter list. So such a
 * section is applied whole or not at all: when any of its values would be skipped or adjusted, the whole section is
 * <b>withheld</b> and keeps the tool's current values. Blocks this game doesn't have stay in a block list
 * (<b>unmatched</b>): they match nothing here, which with or without invert is what the preset means in a game
 * without them. Pure: the same input always gives the same result, which is what "(modified)" compares against.
 */
public final class PresetResolver {
    /** A value that had to change to fit, shown to the player as {@code label from → to}. */
    public record Adjusted(SettingDef<?> def, String from, String to) {
        public Adjusted {
            Objects.requireNonNull(def);
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
        }
    }

    /** A filter section left as it was, with the settings whose saved values didn't fit. */
    public record Withheld(Section section, List<SettingDef<?>> problems) {
        public Withheld {
            Objects.requireNonNull(section);
            problems = List.copyOf(problems);
        }
    }

    /**
     * What resolving gave: the values; the keys skipped; the values adjusted; the blocks left out (unavailable) and
     * the blocks kept in block lists although this game doesn't have them (unmatched), as block state text; and the
     * filter sections withheld. Each in schema or key order.
     */
    public record Result(SettingsValues values, List<String> skipped, List<Adjusted> adjusted, List<String> unavailable,
                         List<String> unmatched, List<Withheld> withheld) {
        public Result {
            Objects.requireNonNull(values);
            skipped = List.copyOf(skipped);
            adjusted = List.copyOf(adjusted);
            unavailable = List.copyOf(unavailable);
            unmatched = List.copyOf(unmatched);
            withheld = List.copyOf(withheld);
        }

        public boolean clean() {
            return skipped.isEmpty() && adjusted.isEmpty() && unavailable.isEmpty() && unmatched.isEmpty()
                    && withheld.isEmpty();
        }
    }

    /** What resolving notes on the way. */
    private record Notes(List<String> skipped, List<Adjusted> adjusted, List<String> unavailable,
                         List<String> unmatched) {
        Notes() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private PresetResolver() {}

    /**
     * @param current the tool's settings now: what a withheld filter section keeps
     * @param limits the server's limits, or null when unknown (only the settings' own bounds apply)
     * @param available whether a block exists in this game
     */
    public static Result resolve(SettingsValues current, Map<String, String> saved, Limits limits,
                                 Predicate<BlockDescriptor> available) {
        Objects.requireNonNull(saved);
        Objects.requireNonNull(available);
        SettingsSchema schema = current.schema();
        SettingsValues values = SettingsValues.defaults(schema);
        Notes notes = new Notes();
        List<Withheld> withheld = new ArrayList<>();
        for (Section section : schema.sections()) {
            if (!isFilter(section)) {
                for (SettingDef<?> def : section.settings()) {
                    values = resolveOne(def, saved.get(def.key()), values, limits, available, notes);
                }
                continue;
            }
            Notes sectionNotes = new Notes();
            SettingsValues sectionValues = values;
            for (SettingDef<?> def : section.settings()) {
                sectionValues = resolveOne(def, saved.get(def.key()), sectionValues, limits, available, sectionNotes);
            }
            List<SettingDef<?>> problems = new ArrayList<>();
            for (SettingDef<?> def : section.settings()) {
                boolean skipped = sectionNotes.skipped().contains(def.key());
                boolean adjusted = sectionNotes.adjusted().stream().anyMatch(change -> change.def().equals(def));
                if (skipped || adjusted) {
                    problems.add(def);
                }
            }
            if (problems.isEmpty()) {
                values = sectionValues;
                notes.unavailable().addAll(sectionNotes.unavailable());
                notes.unmatched().addAll(sectionNotes.unmatched());
            } else {
                for (SettingDef<?> def : section.settings()) {
                    values = keep(def, current, values);
                }
                withheld.add(new Withheld(section, problems));
            }
        }
        for (String key : saved.keySet()) {
            if (schema.def(key).isEmpty()) {
                notes.skipped().add(key);
            }
        }
        return new Result(values, notes.skipped(), notes.adjusted(), notes.unavailable(), notes.unmatched(), withheld);
    }

    /** Whether a section limits where an edit applies: it holds a block list or a range. */
    public static boolean isFilter(Section section) {
        return section.settings().stream()
                .anyMatch(def -> def instanceof SettingDef.BlockList || def instanceof SettingDef.IntRange);
    }

    private static <T> SettingsValues keep(SettingDef<T> def, SettingsValues current, SettingsValues values) {
        return values.with(def, current.get(def));
    }

    /** {@code values} with {@code def} set from its saved text; {@code text} null (not saved) keeps the default. */
    private static <T> SettingsValues resolveOne(SettingDef<T> def, String text, SettingsValues values, Limits limits,
                                                 Predicate<BlockDescriptor> available, Notes notes) {
        if (text == null) {
            return values;
        }
        T value;
        try {
            value = def.canonical(def.decode(text));
        } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
            notes.skipped().add(def.key());
            return values;
        }
        value = availableOnly(def, value, available, notes);
        if (value == null) {
            notes.skipped().add(def.key());
            return values;
        }
        T fitted = def.canonical(fit(def, value, limits));
        if (!def.validate(fitted, limits).isValid()) {
            // Even fitted it can't be used (a server cap below the setting's minimum, a palette emptied by missing
            // blocks): the default stays.
            notes.skipped().add(def.key());
            return values;
        }
        if (!fitted.equals(value)) {
            notes.adjusted().add(new Adjusted(def, describe(def, value), describe(def, fitted)));
        }
        return values.with(def, fitted);
    }

    /**
     * The value without the blocks this game doesn't have where they would be placed; null when a single block is
     * missing. Block lists keep them (noted as unmatched).
     */
    @SuppressWarnings("unchecked")
    private static <T> T availableOnly(SettingDef<T> def, T value, Predicate<BlockDescriptor> available, Notes notes) {
        return switch (def) {
            case SettingDef.Block block -> {
                BlockDescriptor descriptor = (BlockDescriptor) value;
                if (available.test(descriptor)) {
                    yield value;
                }
                notes.unavailable().add(descriptor.format());
                yield null;
            }
            case SettingDef.BlockList list -> {
                for (BlockDescriptor descriptor : (List<BlockDescriptor>) value) {
                    if (!available.test(descriptor)) {
                        notes.unmatched().add(descriptor.format());
                    }
                }
                yield value;
            }
            case SettingDef.WeightedBlocks weighted -> {
                List<SettingDef.WeightedBlock> kept = new ArrayList<>();
                for (SettingDef.WeightedBlock entry : (List<SettingDef.WeightedBlock>) value) {
                    if (available.test(entry.block())) {
                        kept.add(entry);
                    } else {
                        notes.unavailable().add(entry.block().format());
                    }
                }
                yield (T) List.copyOf(kept);
            }
            // Asset references are checked when their previews are asked for; the rest name no blocks.
            case SettingDef.AssetMix mix -> value;
            case SettingDef.Int number -> value;
            case SettingDef.Decimal number -> value;
            case SettingDef.Bool flag -> value;
            case SettingDef.Enum<?> choice -> value;
            case SettingDef.IntRange range -> value;
            case SettingDef.Seed seed -> value;
            // The blocks a mask names match nothing when they are missing; they are kept as they are.
            case SettingDef.MaskRules rules -> value;
        };
    }

    /** The value moved inside the setting's bounds and the server's limits. */
    private static Object fit(SettingDef<?> def, Object value, Limits limits) {
        return switch (def) {
            case SettingDef.Int number -> {
                int max = number.max();
                if (number.serverMax() != null && limits != null) {
                    max = Math.max(number.min(), Math.min(max, number.serverMax().applyAsInt(limits)));
                }
                yield Math.max(number.min(), Math.min(max, (Integer) value));
            }
            case SettingDef.Decimal number -> Math.max(number.min(), Math.min(number.max(), (Double) value));
            case SettingDef.IntRange range -> {
                SettingDef.IntSpan span = (SettingDef.IntSpan) value;
                int low = Math.max(range.min(), Math.min(range.max(), span.min()));
                int high = Math.max(range.min(), Math.min(range.max(), span.max()));
                yield new SettingDef.IntSpan(Math.min(low, high), high);
            }
            case SettingDef.BlockList list -> shorten((List<?>) value, list.maxEntries());
            case SettingDef.WeightedBlocks weighted -> shorten((List<?>) value, weighted.maxEntries());
            case SettingDef.AssetMix mix -> shorten((List<?>) value, mix.maxEntries());
            case SettingDef.Bool flag -> value;
            case SettingDef.Enum<?> choice -> value;
            case SettingDef.Block block -> value;
            case SettingDef.Seed seed -> value;
            case SettingDef.MaskRules rules -> value;
        };
    }

    private static List<?> shorten(List<?> list, int max) {
        return list.size() > max ? List.copyOf(list.subList(0, max)) : list;
    }

    /** A value as the player reads it in a toast: lists by their length, the rest by their preset text. */
    private static <T> String describe(SettingDef<T> def, T value) {
        return value instanceof List<?> list ? Integer.toString(list.size()) : def.encode(value);
    }
}
