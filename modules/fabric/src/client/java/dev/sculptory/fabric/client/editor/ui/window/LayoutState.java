package dev.sculptory.fabric.client.editor.ui.window;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One window layout, in z-order from bottom to top: each window's place and size and whether it is open or collapsed
 * ({@link WindowManager#snapshot} and {@link WindowManager#restore(LayoutState)}). The editor saves one layout per UI
 * size ({@link SizedLayouts}); this is also the version-1 file it read before that, one layout for every size:
 *
 * <pre>{@code
 * {
 *   "version": 1,
 *   "windows": [
 *     {"id": "tool_settings", "open": true, "collapsed": false,
 *      "anchor": "TOP_RIGHT", "x": 6, "y": 24, "width": 180, "height": 220, "placed": true, "overReserved": false}
 *   ]
 * }
 * }</pre>
 *
 * {@code x}/{@code y} are distances from the anchor corner in scaled pixels. {@code placed} is false for a
 * window the user hasn't moved or resized since the last reset: it opens at its default place and the stored
 * position is only a fallback. {@code overReserved} says the user left it over the screen edges kept for the HUD.
 * Both are optional. An entry without them was written before they existed, when every window was saved whether the
 * user moved it or not: it is a placed window that keeps off the HUD, unless it is closed and sits exactly at an old
 * default place ({@link #fromJson(String, Predicate)}): then it opens at today's default place rather than at an old
 * one that may overlap today's windows.
 * Parsing rejects a document that isn't a version-1 layout; individual malformed window entries are skipped.
 */
public record LayoutState(List<WindowState> windows) {
    public static final int VERSION = 1;
    /** The largest offset or size a saved window may have, in UI units. */
    static final int MAX_SIZE = 16384;

    /** One window's saved state. */
    public record WindowState(String id, boolean open, boolean collapsed, Corner anchor, int offsetX, int offsetY,
            int width, int height, boolean placed, boolean overReserved) {
        public WindowState {
            Objects.requireNonNull(id);
            Objects.requireNonNull(anchor);
        }

        /** A window the user placed, kept off the HUD's edges. */
        public WindowState(String id, boolean open, boolean collapsed, Corner anchor, int offsetX, int offsetY,
                int width, int height) {
            this(id, open, collapsed, anchor, offsetX, offsetY, width, height, true, false);
        }

        /**
         * A window at its default place (not placed). Its stored place is a stand-in that is never used: restored, the
         * window takes its spec's anchor and size.
         */
        public static WindowState unplaced(String id, boolean open, boolean collapsed) {
            return new WindowState(id, open, collapsed, Corner.TOP_LEFT, 0, 0, 1, 1, false, false);
        }

        /** Where the user put the window, or empty when it sits at its default place. */
        public Optional<WindowPlacement> placement() {
            return placed ? Optional.of(new WindowPlacement(anchor, offsetX, offsetY, width, height, overReserved))
                    : Optional.empty();
        }

        /** This window, open and collapsed alike, at {@code placement}; unplaced (its stored place kept) when null. */
        public WindowState withPlacement(WindowPlacement placement) {
            if (placement == null) {
                return new WindowState(id, open, collapsed, anchor, offsetX, offsetY, width, height, false, false);
            }
            return new WindowState(id, open, collapsed, placement.anchor(), placement.offsetX(), placement.offsetY(),
                    placement.width(), placement.height(), true, placement.overReserved());
        }
    }

    public LayoutState {
        windows = List.copyOf(windows);
    }

    public Optional<WindowState> window(String id) {
        return windows.stream().filter(state -> state.id().equals(id)).findFirst();
    }

    /**
     * A version-1 layout document, every entry without "placed" read as placed.
     *
     * @throws IllegalArgumentException if the text isn't a version-1 layout document
     */
    public static LayoutState fromJson(String json) {
        return fromJson(json, state -> false);
    }

    /**
     * A version-1 layout document. An entry without "placed" (written before it existed) is placed unless it is closed
     * and {@code oldDefault} says it sits at a place an earlier version opened that window by default (its anchor,
     * offsets and size exactly): the user never moved it, so it opens at today's default place. The predicate sees
     * the entry as parsed, placed.
     *
     * @throws IllegalArgumentException if the text isn't a version-1 layout document
     */
    public static LayoutState fromJson(String json, Predicate<WindowState> oldDefault) {
        Objects.requireNonNull(oldDefault);
        JsonObject root = parseObject(json);
        Integer version = intValue(root.get("version"));
        if (version == null || version != VERSION) {
            throw new IllegalArgumentException("Unsupported editor layout version: " + root.get("version"));
        }
        JsonElement windowsElement = root.get("windows");
        if (windowsElement == null || !windowsElement.isJsonArray()) {
            throw new IllegalArgumentException("Editor layout has no windows array");
        }
        List<WindowState> windows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonElement element : windowsElement.getAsJsonArray()) {
            WindowState state = parseWindow(element, oldDefault);
            if (state != null && seen.add(state.id())) {
                windows.add(state);
            }
        }
        return new LayoutState(windows);
    }

    private static WindowState parseWindow(JsonElement element, Predicate<WindowState> oldDefault) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject entry = element.getAsJsonObject();
        String id = stringValue(entry.get("id"));
        Boolean open = booleanValue(entry.get("open"));
        Boolean collapsed = booleanValue(entry.get("collapsed"));
        boolean placedMissing = !entry.has("placed");
        Boolean placed = optionalBoolean(entry, "placed", true);
        Boolean overReserved = optionalBoolean(entry, "overReserved", false);
        Corner anchor = corner(entry.get("anchor"));
        Integer x = intValue(entry.get("x"));
        Integer y = intValue(entry.get("y"));
        Integer width = intValue(entry.get("width"));
        Integer height = intValue(entry.get("height"));
        if (id == null || id.isEmpty() || open == null || collapsed == null || placed == null || overReserved == null
                || !validPlace(anchor, x, y, width, height)) {
            return null;
        }
        WindowState state = new WindowState(id, open, collapsed, anchor, x, y, width, height, placed, overReserved);
        // Before "placed" existed every window was saved at its place, moved or not: an open one is kept where it is
        // seen; a closed one at an old default place was never moved and opens at today's.
        if (placedMissing && !open && oldDefault.test(state)) {
            return new WindowState(id, false, collapsed, anchor, x, y, width, height, false, overReserved);
        }
        return state;
    }

    // ---- JSON helpers, shared with SizedLayouts ----

    /**
     * The text as a JSON object.
     *
     * @throws IllegalArgumentException if it isn't one
     */
    static JsonObject parseObject(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException malformed) {
            throw new IllegalArgumentException("Editor layout is not valid JSON", malformed);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Editor layout must be a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    /** Whether these are a usable anchor, offsets and size: none missing, none out of range. */
    static boolean validPlace(Corner anchor, Integer x, Integer y, Integer width, Integer height) {
        return anchor != null && x != null && y != null && width != null && height != null
                && Math.abs(x) <= MAX_SIZE && Math.abs(y) <= MAX_SIZE
                && width >= 1 && width <= MAX_SIZE && height >= 1 && height <= MAX_SIZE;
    }

    /** The corner a JSON string names, or {@code null}. */
    static Corner corner(JsonElement element) {
        String name = stringValue(element);
        if (name == null) {
            return null;
        }
        try {
            return Corner.valueOf(name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    static String stringValue(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isString() ? primitive.getAsString() : null;
    }

    /** The field's value, {@code fallback} if it is absent, or {@code null} if it isn't a boolean. */
    static Boolean optionalBoolean(JsonObject entry, String name, boolean fallback) {
        return entry.has(name) ? booleanValue(entry.get(name)) : Boolean.valueOf(fallback);
    }

    static Boolean booleanValue(JsonElement element) {
        return element instanceof JsonPrimitive primitive && primitive.isBoolean() ? primitive.getAsBoolean() : null;
    }

    static Integer intValue(JsonElement element) {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            return null;
        }
        double value = primitive.getAsDouble();
        if (value != Math.rint(value) || Math.abs(value) > Integer.MAX_VALUE) {
            return null;
        }
        return (int) value;
    }
}
