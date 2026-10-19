package dev.sculptory.fabric.client.editor.windows;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.editor.FlySpeed;
import dev.sculptory.fabric.client.editor.ui.UiOpacity;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.util.KeptJson;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Loads and saves {@code editor-ui.json}: the editor UI size (format in {@link UiScale}), whether the player has
 * dismissed the quick start card ({@code "quickStartSeen": true}, left out until then), the opacity settings of
 * View > Opacity… ({@link UiOpacity}; each left out while at its default) and the fly speed ({@link FlySpeed}, set
 * by the editor's scroll and {@code /flyspeed}; left out at 1×):
 * <pre>{@code
 * {"version": 1, "uiSize": 80, "quickStartSeen": true,
 *  "panelOpacity": 60, "fadeWhenNotHovered": true, "toolOutlineOpacity": 40, "flySpeed": 2.5}
 * }</pre>
 * A fly speed that isn't a number reads as 1×; one outside 0.25-8 is clamped into it ({@link FlySpeed#clamp}).
 * A missing, empty or malformed file gives 100%, an unseen card and everything opaque, and the next save replaces it; a
 * file that isn't a JSON object is copied aside as {@code editor-ui.json.bad} ({@link ConfigFile#keepAside}), reported
 * and replaced at once by a version-1 file, so it isn't copied again at every start (a malformed or missing flag reads
 * as unseen: the card shows once more). An opacity that isn't a number
 * reads as its default (100); a number outside its range (20–100 for the panels, 10–100 for the tool outlines) is
 * clamped into it, and rounded to a whole percent. The switch is on only for a JSON {@code true}. A JSON object without
 * a numeric version is malformed for the size (100%), but its flag, opacity and other fields are still read, as the
 * flag always was; it is copied aside the same way ({@link ConfigFile#copyAside}) and at once written again as
 * version 1. A file of another version (from a newer Sculptory) also gives 100% and the defaults, but is reported and
 * left unchanged; the card then counts as seen, since that can't be saved.
 *
 * <p>The opacity and fly speed fields are new in the same version 1: a build from before reads the size and the flag as
 * always (it ignores fields it doesn't know) and drops them when it saves. This build keeps the fields it doesn't know
 * (from a newer build of version 1) on a save, after its own.
 *
 * <p>Size, opacity and fly speed changes are saved once they settle: holding a UI-size key steps through sizes
 * quickly, a dragged slider changes the opacity at every step, and so does the scroll the fly speed; each step
 * shouldn't write the file. The editor calls
 * {@link #saveIfSettled} every tick and {@link #saveNow} when it closes. Dismissing the card is saved at once. Each
 * save writes every value. Client thread only.
 */
public final class UiSizeStore {
    /** How long the size and opacity must stay unchanged before they are written. */
    public static final long SETTLE_MS = 750;
    static final String QUICK_START_SEEN = "quickStartSeen";
    static final String PANEL_OPACITY = "panelOpacity";
    static final String FADE_WHEN_NOT_HOVERED = "fadeWhenNotHovered";
    static final String TOOL_OUTLINE_OPACITY = "toolOutlineOpacity";
    static final String FLY_SPEED = "flySpeed";
    /** The fields this build writes itself; any other field of a version-1 file is kept as it was. */
    private static final Set<String> KNOWN = Set.of("version", "uiSize", QUICK_START_SEEN, PANEL_OPACITY,
            FADE_WHEN_NOT_HOVERED, TOOL_OUTLINE_OPACITY, FLY_SPEED);

    private final ConfigFile file;
    /** Whether the size or the opacity changed since the last save. */
    private boolean pending;
    private long changedAtMs;
    /** The size as saved, or last changed: written with the flag. */
    private int percent = UiScale.DEFAULT_PERCENT;
    private boolean quickStartSeen;
    private UiOpacity.Values opacity = UiOpacity.Values.DEFAULT;
    private double flySpeed = FlySpeed.DEFAULT;
    /** The fields of the file read that this build doesn't know, written back after its own. */
    private JsonObject unknown = new JsonObject();

    public UiSizeStore(ConfigFile file) {
        this.file = Objects.requireNonNull(file);
    }

    /** The saved size in percent, or 100; also reads the quick start flag and the opacity ({@link #opacity}). */
    public int load() {
        Optional<String> text = file.read();
        pending = false;
        quickStartSeen = false;
        opacity = UiOpacity.Values.DEFAULT;
        flySpeed = FlySpeed.DEFAULT;
        unknown = new JsonObject();
        if (text.isEmpty() || text.get().isBlank()) {
            percent = UiScale.DEFAULT_PERCENT;
            return percent;
        }
        try {
            percent = UiScale.fromJson(text.get());
        } catch (IllegalArgumentException otherVersion) {
            file.keepAsIs(otherVersion.getMessage());
            quickStartSeen = true;
            percent = UiScale.DEFAULT_PERCENT;
            return percent;
        }
        JsonObject root = object(text.get());
        boolean keptAside = false;
        if (root == null) {
            keptAside = file.keepAside("not a JSON object; the editor UI starts at 100%, opaque").isPresent();
        } else if (!(root.get("version") instanceof JsonPrimitive version) || !version.isNumber()) {
            // Its size can't be read, and a save writes 100%: the file as it is is kept beside it.
            Optional<Path> aside = file.copyAside("no version; the UI size starts at 100%");
            aside.ifPresent(copy -> file.report("no version; the UI size starts at 100%, the other settings were"
                    + " read; the file was kept as " + copy.getFileName()));
            keptAside = aside.isPresent();
        }
        quickStartSeen = readSeen(root);
        opacity = readOpacity(root);
        flySpeed = readFlySpeed(root);
        unknown = unknownFields(root);
        if (keptAside) {
            // Replaced at once with what was read, so the next start doesn't copy it aside again.
            write();
        }
        return percent;
    }

    /** Remembers a new size to save once it has settled. */
    public void changed(int percent, long nowMs) {
        this.percent = percent;
        pending = true;
        changedAtMs = nowMs;
    }

    /** The opacity as read by {@link #load} (the defaults for a missing, malformed or newer file), or since changed. */
    public UiOpacity.Values opacity() {
        return opacity;
    }

    /** Remembers new opacity values to save once they have settled. */
    public void opacityChanged(UiOpacity.Values values, long nowMs) {
        opacity = Objects.requireNonNull(values);
        pending = true;
        changedAtMs = nowMs;
    }

    /** The fly speed as read by {@link #load} (1× for a missing, malformed or newer file), or since changed. */
    public double flySpeed() {
        return flySpeed;
    }

    /** Remembers a new fly speed to save once it has settled (scrolling steps through speeds quickly). */
    public void flySpeedChanged(double multiplier, long nowMs) {
        flySpeed = FlySpeed.clamp(multiplier);
        pending = true;
        changedAtMs = nowMs;
    }

    /** Saves remembered changes if nothing has changed for {@link #SETTLE_MS}. */
    public void saveIfSettled(long nowMs) {
        if (pending && nowMs - changedAtMs >= SETTLE_MS) {
            saveNow();
        }
    }

    /** Saves remembered changes now, if there are any. */
    public void saveNow() {
        if (pending) {
            pending = false;
            write();
        }
    }

    /** Whether the quick start card was dismissed (as read by {@link #load}, or since). */
    public boolean quickStartSeen() {
        return quickStartSeen;
    }

    /** The quick start card was dismissed: saved at once, with the size and opacity (still settling ones included). */
    public void markQuickStartSeen() {
        if (quickStartSeen) {
            return;
        }
        quickStartSeen = true;
        pending = false;
        write();
    }

    private void write() {
        file.write(toJson(percent, quickStartSeen, opacity, flySpeed, unknown));
    }

    /**
     * The file's text: {@link UiScale#toJson}, the flag once it is set, each opacity value that differs from its
     * default, the fly speed unless it is 1×, then {@code unknown}'s fields.
     */
    static String toJson(int percent, boolean quickStartSeen, UiOpacity.Values opacity, double flySpeed,
            JsonObject unknown) {
        JsonObject root = JsonParser.parseString(UiScale.toJson(percent)).getAsJsonObject();
        if (quickStartSeen) {
            root.addProperty(QUICK_START_SEEN, true);
        }
        UiOpacity.Values defaults = UiOpacity.Values.DEFAULT;
        if (opacity.panels() != defaults.panels()) {
            root.addProperty(PANEL_OPACITY, opacity.panels());
        }
        if (opacity.fadeUnlessHovered() != defaults.fadeUnlessHovered()) {
            root.addProperty(FADE_WHEN_NOT_HOVERED, opacity.fadeUnlessHovered());
        }
        if (opacity.toolOutlines() != defaults.toolOutlines()) {
            root.addProperty(TOOL_OUTLINE_OPACITY, opacity.toolOutlines());
        }
        if (flySpeed != FlySpeed.DEFAULT) {
            root.addProperty(FLY_SPEED, flySpeed);
        }
        for (Map.Entry<String, JsonElement> field : unknown.entrySet()) {
            if (!KNOWN.contains(field.getKey())) {
                root.add(field.getKey(), field.getValue());
            }
        }
        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
    }

    /** The file's JSON object, or null when it isn't one. */
    private static JsonObject object(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (JsonParseException | IllegalStateException malformed) {
            return null;
        }
    }

    /** The flag in a version-1 file: true only for a JSON {@code true}. */
    private static boolean readSeen(JsonObject root) {
        return root != null && root.get(QUICK_START_SEEN) instanceof JsonPrimitive seen && seen.isBoolean()
                && seen.getAsBoolean();
    }

    /** The opacity in a version-1 file ({@code null}: none): each missing or malformed value at its default. */
    static UiOpacity.Values readOpacity(JsonObject root) {
        UiOpacity.Values defaults = UiOpacity.Values.DEFAULT;
        if (root == null) {
            return defaults;
        }
        int panels = clampedPercent(root.get(PANEL_OPACITY), UiOpacity.PANELS_MIN, defaults.panels());
        boolean fade = root.get(FADE_WHEN_NOT_HOVERED) instanceof JsonPrimitive value && value.isBoolean()
                && value.getAsBoolean();
        int outlines = clampedPercent(root.get(TOOL_OUTLINE_OPACITY), UiOpacity.OUTLINES_MIN, defaults.toolOutlines());
        return new UiOpacity.Values(panels, fade, outlines);
    }

    /** The fly speed in a version-1 file ({@code null}: none): a number, clamped; 1× for anything else. */
    static double readFlySpeed(JsonObject root) {
        if (root == null || !(root.get(FLY_SPEED) instanceof JsonPrimitive value) || !value.isNumber()) {
            return FlySpeed.DEFAULT;
        }
        try {
            return FlySpeed.clamp(value.getAsDouble());
        } catch (NumberFormatException malformed) {
            return FlySpeed.DEFAULT;
        }
    }

    /** A percentage: a number clamped to {@code min}–100 and rounded, or {@code fallback} for anything else. */
    private static int clampedPercent(JsonElement element, int min, int fallback) {
        if (!(element instanceof JsonPrimitive value) || !value.isNumber()) {
            return fallback;
        }
        double number;
        try {
            number = value.getAsDouble();
        } catch (NumberFormatException malformed) {
            return fallback;
        }
        if (Double.isNaN(number)) {
            // A guard: lenient Gson reads a bare NaN as a string, so this isn't reached from a file today.
            return fallback;
        }
        return (int) Math.round(Math.max(min, Math.min(UiOpacity.MAX, number)));
    }

    /**
     * The fields of {@code root} this build doesn't write itself (none for a file that isn't a JSON object), without
     * those nested deeper than {@link KeptJson#MAX_DEPTH}. The parsed tree is this store's own, so nothing is copied.
     */
    private static JsonObject unknownFields(JsonObject root) {
        JsonObject fields = new JsonObject();
        if (root != null) {
            for (Map.Entry<String, JsonElement> field : root.entrySet()) {
                if (!KNOWN.contains(field.getKey()) && !KeptJson.deeperThan(field.getValue(), KeptJson.MAX_DEPTH)) {
                    fields.add(field.getKey(), field.getValue());
                }
            }
        }
        return fields;
    }
}
