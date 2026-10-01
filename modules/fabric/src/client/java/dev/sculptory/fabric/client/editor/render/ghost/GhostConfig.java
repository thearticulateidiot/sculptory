package dev.sculptory.fabric.client.editor.render.ghost;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Ghost preview limits, from the optional client file {@code config/sculptory/ghost.json} (read only, never
 * written). Missing keys keep their defaults; out-of-range values are clamped.
 *
 * <pre>{@code
 * { "maxMeshedBlocks": 262144, "maxVertexMegabytes": 128, "fullDetailDistance": 96,
 *   "maxUploadsPerFrame": 4, "uploadBudgetMillis": 2.0, "maxEraseOutlines": 2048 }
 * }</pre>
 *
 * @param maxMeshedBlocks block cells meshed at most, nearest sections first
 * @param maxVertexBytes vertex memory of all meshes at most
 * @param fullDetailDistance sections farther from the camera than this (in blocks) are drawn as boxes
 * @param maxUploadsPerFrame section meshes uploaded to the GPU per frame at most
 * @param uploadBudgetNanos time spent uploading per frame at most (checked after each upload)
 * @param maxEraseOutlines erase cells outlined one by one at most; the rest get one box per section
 */
public record GhostConfig(
        long maxMeshedBlocks,
        long maxVertexBytes,
        double fullDetailDistance,
        int maxUploadsPerFrame,
        long uploadBudgetNanos,
        int maxEraseOutlines) {
    public static final GhostConfig DEFAULTS = new GhostConfig(262_144, 128L << 20, 96.0, 4, 2_000_000L, 2048);

    private static final long MIB = 1L << 20;

    public GhostConfig {
        maxMeshedBlocks = clamp(maxMeshedBlocks, 0, 16L << 20);
        maxVertexBytes = clamp(maxVertexBytes, 0, 2048 * MIB);
        fullDetailDistance = Double.isNaN(fullDetailDistance) ? 96.0 : Math.max(16.0, Math.min(1024.0, fullDetailDistance));
        maxUploadsPerFrame = (int) clamp(maxUploadsPerFrame, 1, 64);
        uploadBudgetNanos = clamp(uploadBudgetNanos, 250_000L, 50_000_000L);
        maxEraseOutlines = (int) clamp(maxEraseOutlines, 0, 65_536);
    }

    /**
     * Reads the config file. A missing file gives the defaults; an unreadable or malformed one gives the defaults and
     * a message to {@code problems}.
     */
    public static GhostConfig load(Path file, Consumer<String> problems) {
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (NoSuchFileException missing) {
            return DEFAULTS;
        } catch (IOException | IllegalArgumentException failure) {
            problems.accept("Could not use " + file.getFileName() + " (" + failure.getMessage() + "); using the default ghost preview limits");
            return DEFAULTS;
        }
    }

    /**
     * Parses the JSON text of the config file.
     *
     * @throws IllegalArgumentException when the text is not a JSON object or a value is not a number
     */
    public static GhostConfig parse(String json) {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("expected a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException malformed) {
            throw new IllegalArgumentException("malformed JSON", malformed);
        }
        GhostConfig d = DEFAULTS;
        return new GhostConfig(
                (long) number(root, "maxMeshedBlocks", d.maxMeshedBlocks),
                (long) (number(root, "maxVertexMegabytes", (double) d.maxVertexBytes / MIB) * MIB),
                number(root, "fullDetailDistance", d.fullDetailDistance),
                (int) number(root, "maxUploadsPerFrame", d.maxUploadsPerFrame),
                (long) (number(root, "uploadBudgetMillis", d.uploadBudgetNanos / 1e6) * 1e6),
                (int) number(root, "maxEraseOutlines", d.maxEraseOutlines));
    }

    private static double number(JsonObject root, String key, double fallback) {
        JsonElement value = root.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        try {
            double number = value.getAsDouble();
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException(key + " must be a finite number");
            }
            return number;
        } catch (UnsupportedOperationException | IllegalStateException | NumberFormatException notNumber) {
            throw new IllegalArgumentException(key + " must be a number", notNumber);
        }
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
