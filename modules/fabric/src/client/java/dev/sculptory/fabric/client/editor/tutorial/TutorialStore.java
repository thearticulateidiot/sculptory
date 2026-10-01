package dev.sculptory.fabric.client.editor.tutorial;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.sculptory.fabric.client.editor.ConfigFile;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Keeps the tutorial progress in {@code config/sculptory/editor-tutorial.json}:
 * <pre>{@code
 * {"version": 1, "completed": ["getting_around", "menus"], "current": {"lesson": "select", "step": 2}}
 * }</pre>
 * ({@code current} is left out when no lesson is in progress). A missing or malformed file gives no progress, and the
 * next save replaces it; entries that aren't lesson ids and steps are skipped. A file of another version (from a newer
 * Sculptory) is reported and left unchanged, and progress then lasts for the session only. Nothing here throws:
 * a file that can't be read or written is reported through the {@link ConfigFile}, and the tutorial goes on.
 */
public final class TutorialStore {
    public static final String FILE_NAME = "editor-tutorial.json";
    public static final int VERSION = 1;

    /** Where progress is saved, or null to keep it for this session only. */
    private final ConfigFile file;

    private TutorialStore(ConfigFile file) {
        this.file = file;
    }

    public static TutorialStore of(ConfigFile file) {
        return new TutorialStore(Objects.requireNonNull(file));
    }

    /** Progress kept for this session only (tests, or an editor without its config directory). */
    public static TutorialStore inMemory() {
        return new TutorialStore(null);
    }

    /** Reads the saved progress (none when there is no usable file). */
    public TutorialProgress load() {
        if (file == null) {
            return TutorialProgress.NONE;
        }
        Optional<String> text = file.read();
        if (text.isEmpty()) {
            return TutorialProgress.NONE;
        }
        try {
            return fromJson(text.get());
        } catch (OtherVersionException newer) {
            file.keepAsIs(newer.getMessage());
            return TutorialProgress.NONE;
        } catch (IllegalArgumentException malformed) {
            // Written by the game, not by hand: the next save replaces it.
            return TutorialProgress.NONE;
        }
    }

    /** Saves {@code progress}; returns false when the file could not be written (the problem is reported). */
    public boolean save(TutorialProgress progress) {
        Objects.requireNonNull(progress);
        return file == null || file.write(toJson(progress));
    }

    // ---- File format ----

    /** A file written by another version of Sculptory. */
    static final class OtherVersionException extends IllegalArgumentException {
        OtherVersionException(String message) {
            super(message);
        }
    }

    static String toJson(TutorialProgress progress) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray completed = new JsonArray();
        new TreeSet<>(progress.completed()).forEach(completed::add);
        root.add("completed", completed);
        progress.current().ifPresent(current -> {
            JsonObject object = new JsonObject();
            object.addProperty("lesson", current.lesson());
            object.addProperty("step", current.step());
            root.add("current", object);
        });
        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
    }

    /**
     * The progress in {@link #toJson} text.
     *
     * @throws OtherVersionException for a file of another version
     * @throws IllegalArgumentException for text that isn't such a file
     */
    static TutorialProgress fromJson(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException malformed) {
            throw new IllegalArgumentException("Not JSON", malformed);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Not a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        if (!(root.get("version") instanceof JsonPrimitive version) || !version.isNumber()) {
            throw new IllegalArgumentException("No version");
        }
        if (version.getAsDouble() != VERSION) {
            throw new OtherVersionException("version " + version.getAsString() + ", this Sculptory reads " + VERSION);
        }
        Set<String> completed = new TreeSet<>();
        if (root.get("completed") instanceof JsonArray list) {
            for (JsonElement entry : list) {
                if (entry instanceof JsonPrimitive id && id.isString() && isLessonId(id.getAsString())) {
                    completed.add(id.getAsString());
                }
            }
        }
        Optional<TutorialProgress.Current> current = Optional.empty();
        if (root.get("current") instanceof JsonObject object
                && object.get("lesson") instanceof JsonPrimitive lesson && lesson.isString()
                && isLessonId(lesson.getAsString())
                && object.get("step") instanceof JsonPrimitive step && step.isNumber()) {
            double value = step.getAsDouble();
            if (value >= 0 && value <= Integer.MAX_VALUE && value == Math.floor(value)) {
                current = Optional.of(new TutorialProgress.Current(lesson.getAsString(), (int) value));
            }
        }
        return new TutorialProgress(completed, current);
    }

    /** Lesson ids are lower-case words joined by underscores. */
    public static boolean isLessonId(String text) {
        return text.matches("[a-z][a-z0-9_]{0,63}");
    }
}
