package dev.sculptory.mc.client.editor.ui.window;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.mc.client.editor.ui.window.LayoutState.WindowState;
import dev.sculptory.mc.client.util.KeptJson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * The editor's window layouts, one per UI size, as saved in {@code config/sculptory/editor-layout.json}:
 *
 * <pre>{@code
 * {
 *   "version": 2,
 *   "windows": [
 *     {"id": "selection", "open": true, "collapsed": false},
 *     {"id": "history", "open": true, "collapsed": false},
 *     {"id": "tool_settings", "open": true, "collapsed": false}
 *   ],
 *   "uiSizes": {
 *     "50": [
 *       {"id": "history", "anchor": "BOTTOM_LEFT", "x": 4, "y": 4, "width": 160, "height": 162, "overReserved": false}
 *     ]
 *   }
 * }
 * }</pre>
 *
 * {@code windows} is shared by every UI size: which windows are open and which collapsed, bottom to top.
 * {@code uiSizes} holds each size's arrangement, by UI size in percent: the windows the user moved or resized at that
 * size ({@link WindowPlacement}: the corner a window is anchored to, {@code x}/{@code y} in from it, its expanded
 * size, in UI units; {@code overReserved}, optional, that it was left over the screen edges kept for the HUD). A
 * window not listed for a size opens at its default place there, and a size not listed shows the default layout.
 *
 * <p>What this version doesn't use is kept as it is and written back ({@link Kept}), so a player who tries another
 * build and comes back loses nothing: a size it doesn't offer, a window it doesn't have, fields it doesn't know (at the
 * top, in a window's entry, in a placement), and entries it can't read (a window or placement entry that isn't one, the
 * second of two with the same id, a size whose value isn't a list). A window's and a placement's unknown fields stay
 * with that window's entry while it has one; a size whose arrangement is reset ({@link #without}) loses its own.
 *
 * <p>A version-1 file ({@link LayoutState#fromJson(String, Predicate)}: one layout for every size) is read as the
 * arrangement of the UI size it was used at ({@link #fromJson}). Immutable.
 */
public record SizedLayouts(List<Shown> windows, Map<String, Map<String, WindowPlacement>> uiSizes, Kept kept) {
    public static final int VERSION = 2;
    /** No saved layout: the default layout at every size, the windows open as they are by default. */
    public static final SizedLayouts EMPTY = new SizedLayouts(List.of(), Map.of());

    private static final Set<String> ROOT_FIELDS = Set.of("version", "windows", "uiSizes");
    private static final Set<String> SHOWN_FIELDS = Set.of("id", "open", "collapsed");
    private static final Set<String> PLACEMENT_FIELDS = Set.of("id", "anchor", "x", "y", "width", "height",
            "overReserved");

    /** Whether a window is open, and collapsed, at every UI size. */
    public record Shown(String id, boolean open, boolean collapsed) {
        public Shown {
            Objects.requireNonNull(id);
        }
    }

    /**
     * What a layout file held that this version doesn't use, as JSON text, written back as it was.
     *
     * @param root                 the fields other than {@code version}, {@code windows} and {@code uiSizes}
     * @param windowFields         per window id, its entry's fields other than {@code id}, {@code open}, {@code collapsed}
     * @param unreadableWindows    the window entries that couldn't be read, in order
     * @param placementFields      per UI size key and window id, the placement's unknown fields
     * @param unreadablePlacements per UI size key, the placement entries that couldn't be read, in order
     * @param unreadableSizes      per UI size key, a value that isn't a list
     */
    public record Kept(SortedMap<String, String> root, Map<String, SortedMap<String, String>> windowFields,
                       List<String> unreadableWindows, Map<String, Map<String, SortedMap<String, String>>> placementFields,
                       Map<String, List<String>> unreadablePlacements, Map<String, String> unreadableSizes) {
        public static final Kept NONE = new Kept(new TreeMap<>(), Map.of(), List.of(), Map.of(), Map.of(), Map.of());

        public Kept {
            root = Collections.unmodifiableSortedMap(new TreeMap<>(root));
            windowFields = fieldsCopy(windowFields);
            unreadableWindows = List.copyOf(unreadableWindows);
            Map<String, Map<String, SortedMap<String, String>>> placements = new LinkedHashMap<>();
            placementFields.forEach((size, fields) -> {
                Map<String, SortedMap<String, String>> copy = fieldsCopy(fields);
                if (!copy.isEmpty()) placements.put(size, copy);
            });
            placementFields = Collections.unmodifiableMap(placements);
            Map<String, List<String>> unreadable = new LinkedHashMap<>();
            unreadablePlacements.forEach((size, entries) -> {
                if (!entries.isEmpty()) unreadable.put(size, List.copyOf(entries));
            });
            unreadablePlacements = Collections.unmodifiableMap(unreadable);
            unreadableSizes = Collections.unmodifiableMap(new LinkedHashMap<>(unreadableSizes));
        }

        private static Map<String, SortedMap<String, String>> fieldsCopy(Map<String, SortedMap<String, String>> fields) {
            Map<String, SortedMap<String, String>> copy = new LinkedHashMap<>();
            fields.forEach((id, values) -> {
                if (!values.isEmpty()) copy.put(id, Collections.unmodifiableSortedMap(new TreeMap<>(values)));
            });
            return Collections.unmodifiableMap(copy);
        }

        /** This, with UI size {@code key}'s placement fields only for the windows in {@code placed}. */
        Kept placedAt(String key, Set<String> placed) {
            Map<String, SortedMap<String, String>> fields = new LinkedHashMap<>(placementFields.getOrDefault(key,
                    Map.of()));
            fields.keySet().retainAll(placed);
            Map<String, Map<String, SortedMap<String, String>>> placements = new LinkedHashMap<>(placementFields);
            placements.put(key, fields);
            return new Kept(root, windowFields, unreadableWindows, placements, unreadablePlacements, unreadableSizes);
        }

        /** This without anything kept of UI size {@code key}. */
        Kept without(String key) {
            Map<String, Map<String, SortedMap<String, String>>> placements = new LinkedHashMap<>(placementFields);
            placements.remove(key);
            Map<String, List<String>> unreadable = new LinkedHashMap<>(unreadablePlacements);
            unreadable.remove(key);
            Map<String, String> sizes = new LinkedHashMap<>(unreadableSizes);
            sizes.remove(key);
            return new Kept(root, windowFields, unreadableWindows, placements, unreadable, sizes);
        }
    }

    public SizedLayouts {
        windows = List.copyOf(windows);
        Map<String, Map<String, WindowPlacement>> sizes = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, WindowPlacement>> size : uiSizes.entrySet()) {
            Map<String, WindowPlacement> arrangement = new LinkedHashMap<>();
            size.getValue().forEach((id, placement) -> arrangement.put(Objects.requireNonNull(id),
                    Objects.requireNonNull(placement)));
            sizes.put(Objects.requireNonNull(size.getKey()), Collections.unmodifiableMap(arrangement));
        }
        uiSizes = Collections.unmodifiableMap(sizes);
        Objects.requireNonNull(kept);
    }

    /** Layouts with nothing kept from a file. */
    public SizedLayouts(List<Shown> windows, Map<String, Map<String, WindowPlacement>> uiSizes) {
        this(windows, uiSizes, Kept.NONE);
    }

    /** The key of UI size {@code percent} in {@link #uiSizes}: {@code "50"}. */
    public static String key(int percent) {
        return Integer.toString(percent);
    }

    /** UI size {@code percent}'s arrangement: the windows placed at that size, by id; empty for a size never arranged. */
    public Map<String, WindowPlacement> arrangement(int percent) {
        return uiSizes.getOrDefault(key(percent), Map.of());
    }

    /**
     * The layout at UI size {@code percent}, bottom to top: each of {@link #windows} open and collapsed as saved, at its
     * place at that size, or at its default place ({@link WindowState#unplaced}) where it has none there.
     */
    public LayoutState at(int percent) {
        Map<String, WindowPlacement> arrangement = arrangement(percent);
        List<WindowState> states = new ArrayList<>();
        for (Shown shown : windows) {
            states.add(WindowState.unplaced(shown.id(), shown.open(), shown.collapsed())
                    .withPlacement(arrangement.get(shown.id())));
        }
        return new LayoutState(states);
    }

    /**
     * These layouts with {@code live} (a window manager's layout at UI size {@code percent}) as that size's
     * arrangement, and as the windows' open and collapsed flags and order. A window {@code live} doesn't have keeps
     * what these layouts say of it: its flags (after those of {@code live}) and its place at every size. Other sizes'
     * arrangements are unchanged. A size left with no placed window is dropped: it shows the default layout. What was
     * kept from the file stays, but a placement's unknown fields only while its window is still placed at that size.
     */
    public SizedLayouts capture(int percent, LayoutState live) {
        Set<String> known = new HashSet<>();
        List<Shown> shown = new ArrayList<>();
        Map<String, WindowPlacement> arrangement = new LinkedHashMap<>();
        for (WindowState state : live.windows()) {
            if (known.add(state.id())) {
                shown.add(new Shown(state.id(), state.open(), state.collapsed()));
                state.placement().ifPresent(placement -> arrangement.put(state.id(), placement));
            }
        }
        for (Shown kept : windows) {
            if (!known.contains(kept.id())) {
                shown.add(kept);
            }
        }
        arrangement(percent).forEach((id, placement) -> {
            if (!known.contains(id)) {
                arrangement.put(id, placement);
            }
        });
        Map<String, Map<String, WindowPlacement>> sizes = new LinkedHashMap<>(uiSizes);
        if (arrangement.isEmpty()) {
            sizes.remove(key(percent));
        } else {
            sizes.put(key(percent), arrangement);
        }
        return new SizedLayouts(shown, sizes, kept.placedAt(key(percent), arrangement.keySet()));
    }

    /** These layouts without UI size {@code percent}'s arrangement: that size shows the default layout. */
    public SizedLayouts without(int percent) {
        Map<String, Map<String, WindowPlacement>> sizes = new LinkedHashMap<>(uiSizes);
        sizes.remove(key(percent));
        return new SizedLayouts(windows, sizes, kept.without(key(percent)));
    }

    /** These layouts with window {@code id}'s place at every UI size but {@code percent} changed by {@code change}. */
    public SizedLayouts withPlacementsOf(String id, int percent, UnaryOperator<WindowPlacement> change) {
        Map<String, Map<String, WindowPlacement>> sizes = new LinkedHashMap<>();
        uiSizes.forEach((size, arrangement) -> {
            if (size.equals(key(percent)) || !arrangement.containsKey(id)) {
                sizes.put(size, arrangement);
            } else {
                Map<String, WindowPlacement> changed = new LinkedHashMap<>(arrangement);
                changed.put(id, change.apply(arrangement.get(id)));
                sizes.put(size, changed);
            }
        });
        return new SizedLayouts(windows, sizes, kept);
    }

    /** A single layout ({@link LayoutState}, as a version-1 file holds) as the arrangement of UI size {@code percent}. */
    public static SizedLayouts of(LayoutState layout, int percent) {
        return EMPTY.capture(percent, layout);
    }

    // ---- File format ----

    /**
     * The file's text: sizes in increasing order (sizes that aren't a number after them, as they were), each entry's
     * kept fields after its own, entries that couldn't be read after the others, the kept top-level fields last.
     */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray shownArray = new JsonArray();
        for (Shown shown : windows) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", shown.id());
            entry.addProperty("open", shown.open());
            entry.addProperty("collapsed", shown.collapsed());
            addKept(entry, kept.windowFields().getOrDefault(shown.id(), new TreeMap<>()));
            shownArray.add(entry);
        }
        kept.unreadableWindows().forEach(json -> shownArray.add(JsonParser.parseString(json)));
        root.add("windows", shownArray);
        JsonObject sizes = new JsonObject();
        Set<String> sizeKeys = new LinkedHashSet<>(uiSizes.keySet());
        sizeKeys.addAll(kept.unreadablePlacements().keySet());
        List<String> keys = new ArrayList<>(sizeKeys);
        keys.sort(Comparator.comparing((String key) -> number(key) == null)
                .thenComparingInt(key -> number(key) == null ? 0 : number(key)));
        for (String key : keys) {
            JsonArray arrangement = new JsonArray();
            Map<String, SortedMap<String, String>> fields = kept.placementFields().getOrDefault(key, Map.of());
            uiSizes.getOrDefault(key, Map.of()).forEach((id, placement) -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("id", id);
                entry.addProperty("anchor", placement.anchor().name());
                entry.addProperty("x", placement.offsetX());
                entry.addProperty("y", placement.offsetY());
                entry.addProperty("width", placement.width());
                entry.addProperty("height", placement.height());
                entry.addProperty("overReserved", placement.overReserved());
                addKept(entry, fields.getOrDefault(id, new TreeMap<>()));
                arrangement.add(entry);
            });
            kept.unreadablePlacements().getOrDefault(key, List.of())
                    .forEach(json -> arrangement.add(JsonParser.parseString(json)));
            sizes.add(key, arrangement);
        }
        kept.unreadableSizes().forEach((key, json) -> {
            if (!sizes.has(key)) sizes.add(key, JsonParser.parseString(json));
        });
        root.add("uiSizes", sizes);
        addKept(root, kept.root());
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    private static void addKept(JsonObject object, Map<String, String> fields) {
        fields.forEach((key, json) -> {
            if (!object.has(key)) object.add(key, JsonParser.parseString(json));
        });
    }

    private static Integer number(String key) {
        try {
            return Integer.valueOf(key);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /**
     * A layout file's text. Version 2 as {@link #toJson} writes it; version 1 (one layout for every size, read by
     * {@link LayoutState#fromJson(String, Predicate)} with {@code oldDefault}) as the arrangement of UI size
     * {@code percent}, the size it was used at, its windows' flags and order as the shared ones.
     *
     * @throws IllegalArgumentException for text that isn't a layout file of version 1 or 2: not a JSON object, no
     *     whole-number version, another version (a newer Sculptory's), no windows list, sizes that aren't an object
     */
    public static SizedLayouts fromJson(String json, int percent, Predicate<WindowState> oldDefault) {
        JsonObject root = LayoutState.parseObject(json);
        Integer version = LayoutState.intValue(root.get("version"));
        if (version == null) {
            throw new IllegalArgumentException("Editor layout has no version");
        }
        if (version == LayoutState.VERSION) {
            return of(LayoutState.fromJson(json, oldDefault), percent);
        }
        if (version != VERSION) {
            throw new IllegalArgumentException("version " + version + "; this Sculptory reads editor layouts of"
                    + " version " + LayoutState.VERSION + " and " + VERSION);
        }
        if (!(root.get("windows") instanceof JsonArray shownArray)) {
            throw new IllegalArgumentException("Editor layout has no windows list");
        }
        JsonElement sizesElement = root.get("uiSizes");
        if (sizesElement != null && !sizesElement.isJsonObject()) {
            throw new IllegalArgumentException("Editor layout's uiSizes is not an object");
        }
        List<Shown> windows = new ArrayList<>();
        Map<String, SortedMap<String, String>> windowFields = new LinkedHashMap<>();
        List<String> unreadableWindows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement element : shownArray) {
            Shown shown = parseShown(element);
            if (shown != null && seen.add(shown.id())) {
                windows.add(shown);
                windowFields.put(shown.id(), other(element.getAsJsonObject(), SHOWN_FIELDS));
            } else {
                keep(unreadableWindows, element);
            }
        }
        Map<String, Map<String, WindowPlacement>> sizes = new LinkedHashMap<>();
        Map<String, Map<String, SortedMap<String, String>>> placementFields = new LinkedHashMap<>();
        Map<String, List<String>> unreadablePlacements = new LinkedHashMap<>();
        Map<String, String> unreadableSizes = new LinkedHashMap<>();
        if (sizesElement != null) {
            for (Map.Entry<String, JsonElement> size : sizesElement.getAsJsonObject().entrySet()) {
                if (!(size.getValue() instanceof JsonArray entries)) {
                    String text = KeptJson.text(size.getValue());
                    if (text != null) unreadableSizes.put(size.getKey(), text);
                    continue;
                }
                Map<String, WindowPlacement> arrangement = new LinkedHashMap<>();
                Map<String, SortedMap<String, String>> fields = new LinkedHashMap<>();
                List<String> unreadable = new ArrayList<>();
                for (JsonElement element : entries) {
                    if (!parsePlacement(element, arrangement, fields)) {
                        keep(unreadable, element);
                    }
                }
                sizes.put(size.getKey(), arrangement);
                placementFields.put(size.getKey(), fields);
                unreadablePlacements.put(size.getKey(), unreadable);
            }
        }
        return new SizedLayouts(windows, sizes, new Kept(other(root, ROOT_FIELDS), windowFields, unreadableWindows,
                placementFields, unreadablePlacements, unreadableSizes));
    }

    private static Shown parseShown(JsonElement element) {
        if (!(element instanceof JsonObject entry)) {
            return null;
        }
        String id = LayoutState.stringValue(entry.get("id"));
        Boolean open = LayoutState.booleanValue(entry.get("open"));
        Boolean collapsed = LayoutState.booleanValue(entry.get("collapsed"));
        return id == null || id.isEmpty() || open == null || collapsed == null ? null : new Shown(id, open, collapsed);
    }

    /**
     * Adds the entry's placement to {@code arrangement} and its unknown fields to {@code fields}; false when it can't be
     * read or its window is there already.
     */
    private static boolean parsePlacement(JsonElement element, Map<String, WindowPlacement> arrangement,
            Map<String, SortedMap<String, String>> fields) {
        if (!(element instanceof JsonObject entry)) {
            return false;
        }
        String id = LayoutState.stringValue(entry.get("id"));
        Corner anchor = LayoutState.corner(entry.get("anchor"));
        Integer x = LayoutState.intValue(entry.get("x"));
        Integer y = LayoutState.intValue(entry.get("y"));
        Integer width = LayoutState.intValue(entry.get("width"));
        Integer height = LayoutState.intValue(entry.get("height"));
        Boolean overReserved = LayoutState.optionalBoolean(entry, "overReserved", false);
        if (id == null || id.isEmpty() || overReserved == null || !LayoutState.validPlace(anchor, x, y, width, height)
                || arrangement.containsKey(id)) {
            return false;
        }
        arrangement.put(id, new WindowPlacement(anchor, x, y, width, height, overReserved));
        fields.put(id, other(entry, PLACEMENT_FIELDS));
        return true;
    }

    /** The object's fields other than {@code known}, as JSON text (those nested too deep to write back left out). */
    private static SortedMap<String, String> other(JsonObject object, Set<String> known) {
        SortedMap<String, String> other = new TreeMap<>();
        KeptJson.eachField(object.entrySet(), (key, json) -> {
            if (!known.contains(key)) other.put(key, json);
        });
        return other;
    }

    /** Adds {@code element} as JSON text to {@code into}, unless it nests too deep to write back. */
    private static void keep(List<String> into, JsonElement element) {
        String text = KeptJson.text(element);
        if (text != null) into.add(text);
    }
}
