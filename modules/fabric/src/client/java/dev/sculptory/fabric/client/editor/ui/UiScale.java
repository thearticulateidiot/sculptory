package dev.sculptory.fabric.client.editor.ui;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * The editor UI size: one setting that scales everything the editor draws as 2D UI (windows,
 * popups, tooltips, top bar, palette, HUD panels, toasts, the help sheet), independent of
 * Minecraft's GUI scale.
 *
 * <p>The UI lays out in <em>UI units</em> on a virtual screen of {@code screen / factor} and is
 * drawn with a matrix scale of {@link #factor()}; pointer positions are divided by the factor
 * before UI hit-testing. World picking, camera look and selection dragging keep using the
 * unscaled screen position. At 100% nothing is transformed, so the UI draws exactly as before.
 *
 * <p>Saved as {@code config/sculptory/editor-ui.json}:
 * <pre>{@code
 * {"version": 1, "uiSize": 80}
 * }</pre>
 *
 * <p>Client thread only; the listeners run on every change, in the order they were added.
 */
public final class UiScale {
    public static final int VERSION = 1;
    public static final int DEFAULT_PERCENT = 100;
    private static final List<Integer> STEPS = List.of(50, 60, 70, 75, 80, 90, 100, 110, 125, 150);

    private int percent = DEFAULT_PERCENT;
    private final List<IntConsumer> listeners = new ArrayList<>();

    /** The sizes the setting can take, smallest first. */
    public static List<Integer> steps() {
        return STEPS;
    }

    public int percent() {
        return percent;
    }

    /** The matrix scale: UI units to screen pixels. */
    public float factor() {
        return percent / 100.0F;
    }

    /** True at 100%, where no transform is applied. */
    public boolean isIdentity() {
        return percent == DEFAULT_PERCENT;
    }

    /**
     * Adds a listener, called with the new size after each change: the editor saves the size, and its windows take
     * that size's arrangement.
     */
    public void addListener(IntConsumer listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** Sets the size if it is one of {@link #steps()}. Returns true if it changed. */
    public boolean set(int percent) {
        if (!STEPS.contains(percent) || percent == this.percent) {
            return false;
        }
        this.percent = percent;
        for (IntConsumer listener : listeners) {
            listener.accept(percent);
        }
        return true;
    }

    /** The next size up ({@code direction > 0}) or down from the current one, stopping at the ends. */
    public int stepped(int direction) {
        int index = STEPS.indexOf(percent);
        int next = Math.max(0, Math.min(STEPS.size() - 1, index + Integer.signum(direction)));
        return STEPS.get(next);
    }

    // ---- Coordinate mapping ----

    /** A screen coordinate (GUI-scaled pixels) in UI units. */
    public double toUi(double screen) {
        return isIdentity() ? screen : screen * 100.0 / percent;
    }

    /** How many whole UI units fit in a screen length: the virtual screen's width or height. */
    public int uiLength(int screenLength) {
        return isIdentity() ? screenLength : (int) Math.floor(screenLength * 100.0 / percent);
    }

    /**
     * UI units spanning at least {@code units} screen pixels, and never fewer than {@code units}:
     * keeps grab zones (resize bands) usable when the UI is small.
     */
    public int reach(int units) {
        return isIdentity() ? units : Math.max(units, (int) Math.ceil(units * 100.0 / percent));
    }

    /** Runs {@code draw} with {@code g} scaled to UI units; at 100% it runs untransformed. */
    public void draw(UiGraphics g, Runnable draw) {
        if (isIdentity()) {
            draw.run();
            return;
        }
        g.pushScale(factor());
        try {
            draw.run();
        } finally {
            g.popScale();
        }
    }

    // ---- Persistence ----

    public static String toJson(int percent) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("uiSize", percent);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
    }

    /**
     * The size saved in {@link #toJson} text. A value between the smallest and largest step snaps
     * to the nearest step (the smaller one on a tie); anything malformed (bad JSON, no numeric
     * version, a missing, non-numeric or out-of-range value) gives {@link #DEFAULT_PERCENT}.
     *
     * @throws IllegalArgumentException if the document has another version (e.g. from a newer
     *     Sculptory), so the caller can leave the file alone instead of overwriting it
     */
    public static int fromJson(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException malformed) {
            return DEFAULT_PERCENT;
        }
        if (!parsed.isJsonObject()) {
            return DEFAULT_PERCENT;
        }
        JsonObject root = parsed.getAsJsonObject();
        if (!(root.get("version") instanceof JsonPrimitive version) || !version.isNumber()) {
            return DEFAULT_PERCENT;
        }
        if (version.getAsDouble() != VERSION) {
            throw new IllegalArgumentException("Unsupported editor UI settings version: " + version);
        }
        if (!(root.get("uiSize") instanceof JsonPrimitive size) || !size.isNumber()) {
            return DEFAULT_PERCENT;
        }
        return nearestStep(size.getAsDouble());
    }

    /** The step nearest {@code percent}, or {@link #DEFAULT_PERCENT} outside the steps' range. */
    static int nearestStep(double percent) {
        if (!(percent >= STEPS.get(0) && percent <= STEPS.get(STEPS.size() - 1))) {
            return DEFAULT_PERCENT;
        }
        int best = STEPS.get(0);
        for (int step : STEPS) {
            if (Math.abs(step - percent) < Math.abs(best - percent)) {
                best = step;
            }
        }
        return best;
    }
}
