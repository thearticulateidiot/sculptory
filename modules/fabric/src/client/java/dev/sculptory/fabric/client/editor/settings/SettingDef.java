package dev.sculptory.fabric.client.editor.settings;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskText;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.protocol.v2.Limits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * A declarative tool setting. The settings window is generated from these; values live in
 * {@link SettingsValues}. {@link #validate} is pure. {@link #encode}/{@link #decode} give the preset text form.
 *
 * @param <T> the immutable value type
 */
public sealed interface SettingDef<T> permits SettingDef.Int, SettingDef.Decimal, SettingDef.Bool, SettingDef.Enum,
        SettingDef.Block, SettingDef.BlockList, SettingDef.WeightedBlocks, SettingDef.IntRange, SettingDef.AssetMix,
        SettingDef.Seed, SettingDef.MaskRules {

    Predicate<SettingsValues> ALWAYS = values -> true;

    /** Unique within a schema: {@code [a-z0-9_.]{1,64}}. */
    String key();

    /** Translation key of the label. */
    String labelKey();

    T defaultValue();

    /** Whether the setting is shown, given the other values. */
    Predicate<SettingsValues> visibleWhen();

    /** Checks the def's own bounds and, when {@code serverLimits} is non-null, the server's. */
    Validation validate(T value, Limits serverLimits);

    /** An immutable copy of {@code value}; throws {@link IllegalArgumentException} for null or a wrong type. */
    T canonical(Object value);

    String encode(T value);

    /** Parses {@link #encode} text; throws {@link IllegalArgumentException} if malformed. */
    T decode(String text);

    /** One weighted block of a palette. */
    record WeightedBlock(BlockDescriptor block, int weight) {
        public WeightedBlock {
            Objects.requireNonNull(block);
            if (weight < 1 || weight > Pattern.Weighted.MAX_WEIGHT) {
                throw new IllegalArgumentException("Weight must be 1-" + Pattern.Weighted.MAX_WEIGHT);
            }
        }
    }

    /** An inclusive integer range value. */
    record IntSpan(int min, int max) {
        public IntSpan {
            if (min > max) throw new IllegalArgumentException("Inverted range");
        }
    }

    /** One weighted asset of a scatter mix. {@code asset} is a library path or content hash. */
    record AssetWeight(String asset, int weight) {
        public AssetWeight {
            Objects.requireNonNull(asset);
            if (!asset.matches("[^;\\s]{1,256}")) throw new IllegalArgumentException("Invalid asset reference");
            if (weight < 1 || weight > Pattern.Weighted.MAX_WEIGHT) {
                throw new IllegalArgumentException("Weight must be 1-" + Pattern.Weighted.MAX_WEIGHT);
            }
        }
    }

    /**
     * An integer in {@code [min, max]}. {@code serverMax}, if non-null, reads a further cap from the server
     * limits (e.g. {@code Limits::maxBrushRadius}).
     */
    record Int(String key, String labelKey, Integer defaultValue, int min, int max, ToIntFunction<Limits> serverMax,
               Predicate<SettingsValues> visibleWhen) implements SettingDef<Integer> {
        public Int {
            checkCommon(key, labelKey, defaultValue, visibleWhen);
            if (min > max || defaultValue < min || defaultValue > max) throw new IllegalArgumentException("Int bounds");
        }

        public Int(String key, String labelKey, int defaultValue, int min, int max) {
            this(key, labelKey, defaultValue, min, max, null, ALWAYS);
        }

        @Override
        public Validation validate(Integer value, Limits serverLimits) {
            if (value < min || value > max) return Validation.error("sculptory.setting.out_of_range", min, max);
            if (serverMax != null && serverLimits != null) {
                int cap = serverMax.applyAsInt(serverLimits);
                if (value > cap) return Validation.error("sculptory.setting.server_limit", cap);
            }
            return Validation.ok();
        }

        @Override
        public Integer canonical(Object value) {
            return cast(Integer.class, value);
        }

        @Override
        public String encode(Integer value) {
            return Integer.toString(value);
        }

        @Override
        public Integer decode(String text) {
            return Integer.parseInt(text);
        }
    }

    /** A finite double in {@code [min, max]}; {@code step} is the slider increment. */
    record Decimal(String key, String labelKey, Double defaultValue, double min, double max, double step,
                   Predicate<SettingsValues> visibleWhen) implements SettingDef<Double> {
        public Decimal {
            checkCommon(key, labelKey, defaultValue, visibleWhen);
            if (!(min <= max) || !(defaultValue >= min && defaultValue <= max) || !(step > 0)) {
                throw new IllegalArgumentException("Decimal bounds");
            }
        }

        public Decimal(String key, String labelKey, double defaultValue, double min, double max, double step) {
            this(key, labelKey, defaultValue, min, max, step, ALWAYS);
        }

        @Override
        public Validation validate(Double value, Limits serverLimits) {
            if (!(value >= min && value <= max)) return Validation.error("sculptory.setting.out_of_range", min, max);
            return Validation.ok();
        }

        @Override
        public Double canonical(Object value) {
            return cast(Double.class, value);
        }

        @Override
        public String encode(Double value) {
            return Double.toString(value);
        }

        @Override
        public Double decode(String text) {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Not finite: " + text);
            return value;
        }
    }

    record Bool(String key, String labelKey, Boolean defaultValue, Predicate<SettingsValues> visibleWhen)
            implements SettingDef<Boolean> {
        public Bool {
            checkCommon(key, labelKey, defaultValue, visibleWhen);
        }

        public Bool(String key, String labelKey, boolean defaultValue) {
            this(key, labelKey, defaultValue, ALWAYS);
        }

        @Override
        public Validation validate(Boolean value, Limits serverLimits) {
            return Validation.ok();
        }

        @Override
        public Boolean canonical(Object value) {
            return cast(Boolean.class, value);
        }

        @Override
        public String encode(Boolean value) {
            return Boolean.toString(value);
        }

        @Override
        public Boolean decode(String text) {
            if (text.equals("true")) return true;
            if (text.equals("false")) return false;
            throw new IllegalArgumentException("Not a boolean: " + text);
        }
    }

    /**
     * One constant of an enum, shown as a dropdown. Options in {@code unavailable} are shown greyed out, with the
     * translation key there as the reason in their tooltip, and can't be chosen (a value holding one doesn't validate).
     */
    record Enum<E extends java.lang.Enum<E>>(String key, String labelKey, Class<E> type, E defaultValue,
                                             Predicate<SettingsValues> visibleWhen, Map<E, String> unavailable)
            implements SettingDef<E> {
        public Enum {
            checkCommon(key, labelKey, defaultValue, visibleWhen);
            Objects.requireNonNull(type);
            unavailable = Map.copyOf(unavailable);
            if (unavailable.containsKey(defaultValue)) throw new IllegalArgumentException("The default is unavailable");
        }

        public Enum(String key, String labelKey, Class<E> type, E defaultValue, Predicate<SettingsValues> visibleWhen) {
            this(key, labelKey, type, defaultValue, visibleWhen, Map.of());
        }

        public Enum(String key, String labelKey, Class<E> type, E defaultValue) {
            this(key, labelKey, type, defaultValue, ALWAYS);
        }

        /** Whether {@code option} can be chosen. */
        public boolean available(E option) {
            return !unavailable.containsKey(option);
        }

        @Override
        public Validation validate(E value, Limits serverLimits) {
            String reason = unavailable.get(value);
            return reason == null ? Validation.ok() : Validation.error(reason);
        }

        @Override
        public E canonical(Object value) {
            return cast(type, value);
        }

        @Override
        public String encode(E value) {
            return value.name();
        }

        @Override
        public E decode(String text) {
            return java.lang.Enum.valueOf(type, text);
        }
    }

    /** One exact block state. */
    record Block(String key, String labelKey, BlockDescriptor defaultValue, Predicate<SettingsValues> visibleWhen)
            implements SettingDef<BlockDescriptor> {
        public Block {
            checkCommon(key, labelKey, defaultValue, visibleWhen);
        }

        public Block(String key, String labelKey, BlockDescriptor defaultValue) {
            this(key, labelKey, defaultValue, ALWAYS);
        }

        @Override
        public Validation validate(BlockDescriptor value, Limits serverLimits) {
            return Validation.ok();
        }

        @Override
        public BlockDescriptor canonical(Object value) {
            return cast(BlockDescriptor.class, value);
        }

        @Override
        public String encode(BlockDescriptor value) {
            return value.format();
        }

        @Override
        public BlockDescriptor decode(String text) {
            return BlockDescriptor.parse(text);
        }
    }

    /** Up to {@code maxEntries} block states (may be empty). */
    record BlockList(String key, String labelKey, List<BlockDescriptor> defaultValue, int maxEntries,
                     Predicate<SettingsValues> visibleWhen) implements SettingDef<List<BlockDescriptor>> {
        public BlockList {
            checkCommon(key, labelKey, defaultValue, visibleWhen);
            defaultValue = List.copyOf(defaultValue);
            if (maxEntries < 1 || defaultValue.size() > maxEntries) throw new IllegalArgumentException("BlockList bounds");
        }

        public BlockList(String key, String labelKey, List<BlockDescriptor> defaultValue, int maxEntries) {
            this(key, labelKey, defaultValue, maxEntries, ALWAYS);
        }

        @Override
        public Validation validate(List<BlockDescriptor> value, Limits serverLimits) {
            if (value.size() > maxEntries) return Validation.error("sculptory.setting.too_many", maxEntries);
            return Validation.ok();
        }

        @Override
        public List<BlockDescriptor> canonical(Object value) {
            return castList(BlockDescriptor.class, value);
        }

        @Override
        public String encode(List<BlockDescriptor> value) {
            return joinEntries(value, BlockDescriptor::format);
        }

        @Override
        public List<BlockDescriptor> decode(String text) {
            return splitEntries(text, BlockDescriptor::parse);
        }
    }

    /** A weighted block palette of 1 to {@code maxEntries} entries. */
    record WeightedBlocks(String key, String labelKey, List<WeightedBlock> defaultValue, int maxEntries,
                          Predicate<SettingsValues> visibleWhen) implements SettingDef<List<WeightedBlock>> {
        public WeightedBlocks {
            checkCommon(key, labelKey, defaultValue, visibleWhen);
            defaultValue = List.copyOf(defaultValue);
            if (maxEntries < 1 || defaultValue.isEmpty() || defaultValue.size() > maxEntries) {
                throw new IllegalArgumentException("WeightedBlocks bounds");
            }
        }

        public WeightedBlocks(String key, String labelKey, List<WeightedBlock> defaultValue, int maxEntries) {
            this(key, labelKey, defaultValue, maxEntries, ALWAYS);
        }

        @Override
        public Validation validate(List<WeightedBlock> value, Limits serverLimits) {
            if (value.isEmpty()) return Validation.error("sculptory.setting.empty");
            if (value.size() > maxEntries) return Validation.error("sculptory.setting.too_many", maxEntries);
            return Validation.ok();
        }

        @Override
        public List<WeightedBlock> canonical(Object value) {
            return castList(WeightedBlock.class, value);
        }

        @Override
        public String encode(List<WeightedBlock> value) {
            return joinEntries(value, entry -> entry.weight() + ":" + entry.block().format());
        }

        @Override
        public List<WeightedBlock> decode(String text) {
            return splitEntries(text, entry -> {
                int colon = weightSeparator(entry);
                return new WeightedBlock(BlockDescriptor.parse(entry.substring(colon + 1)),
                        Integer.parseInt(entry.substring(0, colon)));
            });
        }
    }

    /** A range within {@code [min, max]}. */
    record IntRange(String key, String labelKey, IntSpan defaultValue, int min, int max,
                    Predicate<SettingsValues> visibleWhen) implements SettingDef<IntSpan> {
        public IntRange {
            checkCommon(key, labelKey, defaultValue, visibleWhen);
            if (min > max || defaultValue.min() < min || defaultValue.max() > max) {
                throw new IllegalArgumentException("IntRange bounds");
            }
        }

        public IntRange(String key, String labelKey, IntSpan defaultValue, int min, int max) {
            this(key, labelKey, defaultValue, min, max, ALWAYS);
        }

        @Override
        public Validation validate(IntSpan value, Limits serverLimits) {
            if (value.min() < min || value.max() > max) return Validation.error("sculptory.setting.out_of_range", min, max);
            return Validation.ok();
        }

        @Override
        public IntSpan canonical(Object value) {
            return cast(IntSpan.class, value);
        }

        @Override
        public String encode(IntSpan value) {
            return value.min() + ".." + value.max();
        }

        @Override
        public IntSpan decode(String text) {
            int dots = text.indexOf("..", 1);
            if (dots < 0) throw new IllegalArgumentException("Not a range: " + text);
            return new IntSpan(Integer.parseInt(text.substring(0, dots)), Integer.parseInt(text.substring(dots + 2)));
        }
    }

    /** M3. A weighted asset mix of up to {@code maxEntries} entries. */
    record AssetMix(String key, String labelKey, List<AssetWeight> defaultValue, int maxEntries,
                    Predicate<SettingsValues> visibleWhen) implements SettingDef<List<AssetWeight>> {
        public AssetMix {
            checkCommon(key, labelKey, defaultValue, visibleWhen);
            defaultValue = List.copyOf(defaultValue);
            if (maxEntries < 1 || defaultValue.size() > maxEntries) throw new IllegalArgumentException("AssetMix bounds");
        }

        public AssetMix(String key, String labelKey, List<AssetWeight> defaultValue, int maxEntries) {
            this(key, labelKey, defaultValue, maxEntries, ALWAYS);
        }

        /** Empty is only a warning: the default is empty because library contents are not known in advance. */
        @Override
        public Validation validate(List<AssetWeight> value, Limits serverLimits) {
            if (value.size() > maxEntries) return Validation.error("sculptory.setting.too_many", maxEntries);
            if (value.isEmpty()) return Validation.warning("sculptory.setting.empty");
            return Validation.ok();
        }

        @Override
        public List<AssetWeight> canonical(Object value) {
            return castList(AssetWeight.class, value);
        }

        @Override
        public String encode(List<AssetWeight> value) {
            return joinEntries(value, entry -> entry.weight() + ":" + entry.asset());
        }

        @Override
        public List<AssetWeight> decode(String text) {
            return splitEntries(text, entry -> {
                int colon = weightSeparator(entry);
                return new AssetWeight(entry.substring(colon + 1), Integer.parseInt(entry.substring(0, colon)));
            });
        }
    }

    /** A random seed. */
    record Seed(String key, String labelKey, Long defaultValue, Predicate<SettingsValues> visibleWhen)
            implements SettingDef<Long> {
        public Seed {
            checkCommon(key, labelKey, defaultValue, visibleWhen);
        }

        public Seed(String key, String labelKey, long defaultValue) {
            this(key, labelKey, defaultValue, ALWAYS);
        }

        @Override
        public Validation validate(Long value, Limits serverLimits) {
            return Validation.ok();
        }

        @Override
        public Long canonical(Object value) {
            return cast(Long.class, value);
        }

        @Override
        public String encode(Long value) {
            return Long.toString(value);
        }

        @Override
        public Long decode(String text) {
            return Long.parseLong(text);
        }
    }


    /**
     * A mask as a rule list (a brush's own Mask section). The value may be unset (the
     * default): then {@code fallback} gives the mask the other settings describe (the legacy mask keys of a preset saved
     * before rules), which is also what the form shows. Its text is {@code MaskText}, or "" while unset.
     */
    record MaskRules(String key, String labelKey, BiFunction<SettingsValues, StateSpace, EditMask> fallback,
                     Predicate<SettingsValues> visibleWhen) implements SettingDef<MaskValue> {
        public MaskRules {
            checkCommon(key, labelKey, MaskValue.UNSET, visibleWhen);
            Objects.requireNonNull(fallback);
        }

        @Override
        public MaskValue defaultValue() {
            return MaskValue.UNSET;
        }

        /**
         * The mask the value stands for: its own once set, else {@link #fallback}'s, which reads block states through
         * {@code states} ({@code null} when none is at hand).
         */
        public EditMask shown(SettingsValues values, StateSpace states) {
            MaskValue value = values.get(this);
            return value.set() ? value.mask() : fallback.apply(values, states);
        }

        @Override
        public Validation validate(MaskValue value, Limits serverLimits) {
            return Validation.ok();
        }

        @Override
        public MaskValue canonical(Object value) {
            return cast(MaskValue.class, value);
        }

        @Override
        public String encode(MaskValue value) {
            return value.set() ? MaskText.encode(value.mask()) : "";
        }

        @Override
        public MaskValue decode(String text) {
            return text.isEmpty() ? MaskValue.UNSET : new MaskValue(MaskText.decode(text), true);
        }
    }

    /** A {@link MaskRules} value: a mask, or unset ({@link #UNSET}: the fallback decides). */
    record MaskValue(EditMask mask, boolean set) {
        public static final MaskValue UNSET = new MaskValue(EditMask.NONE, false);

        public MaskValue {
            Objects.requireNonNull(mask);
            if (!set && !mask.isOff()) throw new IllegalArgumentException("An unset mask value holds no rules");
        }

        /** A mask set by the player. */
        public static MaskValue of(EditMask mask) {
            return new MaskValue(mask, true);
        }
    }

    private static void checkCommon(String key, String labelKey, Object defaultValue, Predicate<SettingsValues> visibleWhen) {
        Objects.requireNonNull(key);
        if (!key.matches("[a-z0-9_.]{1,64}")) throw new IllegalArgumentException("Invalid setting key: " + key);
        Objects.requireNonNull(labelKey);
        Objects.requireNonNull(defaultValue);
        Objects.requireNonNull(visibleWhen);
    }

    private static <V> V cast(Class<V> type, Object value) {
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("Expected " + type.getSimpleName() + " but got " + value);
        }
        return type.cast(value);
    }

    private static <V> List<V> castList(Class<V> elementType, Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected a list but got " + value);
        List<V> copy = new ArrayList<>(list.size());
        for (Object element : list) copy.add(cast(elementType, element));
        return List.copyOf(copy);
    }

    /** Entries joined with ';', which block-state text and asset references never contain. */
    private static <V> String joinEntries(List<V> entries, Function<V, String> format) {
        return String.join(";", entries.stream().map(format).toList());
    }

    private static <V> List<V> splitEntries(String text, Function<String, V> parse) {
        if (text.isEmpty()) return List.of();
        List<V> entries = new ArrayList<>();
        for (String entry : text.split(";", -1)) entries.add(parse.apply(entry));
        return List.copyOf(entries);
    }

    private static int weightSeparator(String entry) {
        int colon = entry.indexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("Expected weight:value but got " + entry);
        return colon;
    }
}
