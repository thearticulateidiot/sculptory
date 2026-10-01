package dev.sculptory.fabric.library;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import dev.sculptory.core.edit.MixLayout;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.schem.DataFixHook;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.protocol.v2.S2C;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The block palette file of the library (palettes; {@value LibraryPath#PALETTE_EXTENSION}): a small JSON document in
 * UTF-8, at most {@value #MAX_BYTES} bytes.
 *
 * <pre>
 * {
 *   "format": "sculptory:palette",
 *   "version": 1,
 *   "dataVersion": 3955,
 *   "entries": [
 *     {"state": "minecraft:moss_block", "weight": 4},
 *     {"state": "minecraft:sea_pickle[pickles=2,waterlogged=true]", "weight": 1}
 *   ],
 *   "pattern": {"kind": "patches", "patchSize": 6, "edge": 4, "steepnessEdge": 10, "seed": -8214629017740148339}
 * }
 * </pre>
 *
 * <ul>
 *   <li>{@code format} ({@value #FORMAT}) and {@code version} ({@value #VERSION}) are required; any other version is
 *       refused, so a newer build's file is never guessed at.</li>
 *   <li>{@code dataVersion} (optional): the game data version the states were saved in. States saved by an older
 *       game are upgraded with the game's data fixer when the palette is loaded.</li>
 *   <li>{@code entries}: 1 to {@value BlockPalette#MAX_ENTRIES} objects, each with a {@code state} (a string of 1 to
 *       {@value BlockPalette#MAX_STATE_BYTES} UTF-8 bytes) and a {@code weight} (a whole number,
 *       1-{@value BlockPalette#MAX_WEIGHT}).</li>
 *   <li>{@code pattern} (optional, written since mix patterns): how the tools
 *       lay the blocks out ({@link PalettePattern}); a file without one is Random. Its {@code kind} is required, the
 *       numbers take their defaults when missing, and an unknown kind or a number out of range is refused
 *       ({@link #pattern}). An older build ignores the member and loads the blocks as Random.</li>
 *   <li>Other members are ignored (a later version-1 writer may add some); a member name used twice is refused.</li>
 * </ul>
 *
 * Files are untrusted (anyone who may write the library writes them, and admins edit them by hand): reading is strict
 * JSON through a streaming reader (no object mapping, no recursion) of a bounded input, and anything else is a
 * {@link PaletteFormatException} saying what is wrong. A state the server doesn't know is not a format error: a load
 * leaves it out and counts it ({@link #resolve}), a save refuses it ({@link #canonical}).
 */
public final class PaletteFile {
    /** The largest palette file read or written. */
    public static final int MAX_BYTES = 64 << 10;
    public static final String FORMAT = "sculptory:palette";
    /** The format name of palette files saved before the rename: still read, never written. */
    public static final String LEGACY_FORMAT = "buildersuite:palette";
    public static final int VERSION = 1;
    /** Dropped states named in a load's answer. */
    public static final int SHOWN_DROPPED = S2C.PaletteData.MAX_SHOWN_DROPPED;

    private static final Pattern WHOLE_NUMBER = Pattern.compile("-?[0-9]{1,18}");
    /** A seed: any long, so up to 19 digits (checked for range when parsed). */
    private static final Pattern SEED = Pattern.compile("-?[0-9]{1,19}");
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private PaletteFile() {}

    /**
     * A file's content as written: the entries in order (their states unresolved, possibly repeated) and the pattern
     * (Random for a file without one).
     */
    public record Content(List<BlockPalette.Entry> entries, OptionalInt dataVersion, PalettePattern pattern) {
        public Content {
            entries = List.copyOf(entries);
            Objects.requireNonNull(dataVersion);
            Objects.requireNonNull(pattern);
        }

        /** A file's content without a pattern (Random). */
        public Content(List<BlockPalette.Entry> entries, OptionalInt dataVersion) {
            this(entries, dataVersion, PalettePattern.RANDOM);
        }
    }

    /** A palette read for a player: the entries this server knows, and what was left out. */
    public record Loaded(BlockPalette palette, int dropped, List<String> droppedStates) {
        public Loaded {
            Objects.requireNonNull(palette);
            droppedStates = List.copyOf(droppedStates);
        }
    }

    /** A palette file that can't be read; the message says why (it never names server paths). */
    public static final class PaletteFormatException extends Exception {
        public PaletteFormatException(String message) {
            super(message);
        }
    }

    // ================================================================== writing

    /** The file for {@code palette}, its states saved in the game data version {@code dataVersion}. */
    public static byte[] encode(BlockPalette palette, int dataVersion) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 + palette.size() * 96);
        try (Writer text = new OutputStreamWriter(bytes, StandardCharsets.UTF_8);
             JsonWriter out = new JsonWriter(text)) {
            out.setIndent("  ");
            out.beginObject();
            out.name("format").value(FORMAT);
            out.name("version").value(VERSION);
            out.name("dataVersion").value(dataVersion);
            out.name("entries").beginArray();
            for (BlockPalette.Entry entry : palette.entries()) {
                out.beginObject();
                out.name("state").value(entry.state());
                out.name("weight").value(entry.weight());
                out.endObject();
            }
            out.endArray();
            PalettePattern pattern = palette.pattern();
            out.name("pattern").beginObject();
            out.name("kind").value(pattern.kind().fileName());
            out.name("patchSize").value(pattern.patchSize());
            out.name("edge").value(pattern.edge());
            out.name("steepnessEdge").value(pattern.steepnessEdge());
            out.name("seed").value(pattern.seed());
            out.endObject();
            out.endObject();
        } catch (IOException e) {
            throw new IllegalStateException("Writing to memory failed", e);
        }
        bytes.write('\n');
        return bytes.toByteArray();
    }

    // ================================================================== reading

    /** Reads a palette file (untrusted); see the class comment for what is refused. */
    public static Content decode(byte[] bytes) throws PaletteFormatException {
        Objects.requireNonNull(bytes);
        if (bytes.length > MAX_BYTES) throw new PaletteFormatException(bytes.length + " bytes (at most " + MAX_BYTES + ")");
        String text = utf8(bytes);
        try {
            // First only the header, so a file of another version is refused as that, whatever its entries look like.
            readHeader(text);
            return readContent(text);
        } catch (IOException | IllegalStateException | NumberFormatException e) {
            // Malformed JSON, or a value of the wrong type the reader noticed first. Its message names a place in the
            // document, never a file.
            throw new PaletteFormatException("not valid JSON: " + clip(e.getMessage()));
        }
    }

    private static String utf8(byte[] bytes) throws PaletteFormatException {
        int start = bytes.length >= 3 && bytes[0] == BOM[0] && bytes[1] == BOM[1] && bytes[2] == BOM[2] ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.length - start))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new PaletteFormatException("not UTF-8 text");
        }
    }

    private static JsonReader reader(String text) {
        JsonReader in = new JsonReader(new StringReader(text));
        in.setLenient(false);
        return in;
    }

    private static void readHeader(String text) throws IOException, PaletteFormatException {
        JsonReader in = reader(text);
        if (in.peek() != JsonToken.BEGIN_OBJECT) throw new PaletteFormatException("not a palette file (no JSON object)");
        in.beginObject();
        String format = null;
        Long version = null;
        Set<String> seen = new HashSet<>();
        while (in.hasNext()) {
            String name = in.nextName();
            if (!seen.add(name)) throw new PaletteFormatException("\"" + clip(name) + "\" is given twice");
            switch (name) {
                case "format" -> format = string(in, "format");
                case "version" -> version = number(in, "version");
                default -> in.skipValue();
            }
        }
        in.endObject();
        if (in.peek() != JsonToken.END_DOCUMENT) throw new PaletteFormatException("text after the palette object");
        if (!FORMAT.equals(format) && !LEGACY_FORMAT.equals(format)) {
            throw new PaletteFormatException(format == null ? "not a palette file (no \"format\")"
                    : "not a palette file (format \"" + clip(format) + "\")");
        }
        if (version == null) throw new PaletteFormatException("no \"version\"");
        if (version != VERSION) {
            throw new PaletteFormatException("palette format version " + version + " (this server reads " + VERSION + ")");
        }
    }

    private static Content readContent(String text) throws IOException, PaletteFormatException {
        JsonReader in = reader(text);
        in.beginObject();
        Long dataVersion = null;
        List<BlockPalette.Entry> entries = null;
        PalettePattern pattern = PalettePattern.RANDOM;
        while (in.hasNext()) {
            switch (in.nextName()) {
                case "dataVersion" -> dataVersion = number(in, "dataVersion");
                case "entries" -> entries = entries(in);
                case "pattern" -> pattern = pattern(in);
                default -> in.skipValue();
            }
        }
        in.endObject();
        if (entries == null) throw new PaletteFormatException("no \"entries\"");
        if (dataVersion != null && (dataVersion < 0 || dataVersion > Integer.MAX_VALUE)) {
            throw new PaletteFormatException("dataVersion " + dataVersion + " is not a game data version");
        }
        return new Content(entries, dataVersion == null ? OptionalInt.empty() : OptionalInt.of(dataVersion.intValue()),
                pattern);
    }

    /**
     * The {@code pattern} object: {@code kind} ({@code random}, {@code patches},
     * {@code gradient} or {@code steepness}) is required; {@code patchSize} (1-32), {@code edge} (0-32),
     * {@code steepnessEdge} (0-45) and {@code seed} (any whole number within a long) are optional and take their
     * defaults when missing. An unknown kind, a value out of range or of the wrong type, and a member given twice are
     * refused; other members are ignored.
     */
    private static PalettePattern pattern(JsonReader in) throws IOException, PaletteFormatException {
        if (in.peek() != JsonToken.BEGIN_OBJECT) throw new PaletteFormatException("\"pattern\" is not an object");
        in.beginObject();
        String kind = null;
        PalettePattern defaults = PalettePattern.RANDOM;
        long patchSize = defaults.patchSize(), edge = defaults.edge(), steepnessEdge = defaults.steepnessEdge();
        long seed = defaults.seed();
        Set<String> seen = new HashSet<>();
        while (in.hasNext()) {
            String name = in.nextName();
            if (!seen.add(name)) throw new PaletteFormatException("pattern: \"" + clip(name) + "\" is given twice");
            switch (name) {
                case "kind" -> kind = string(in, "pattern kind");
                case "patchSize" -> patchSize = number(in, "pattern patchSize");
                case "edge" -> edge = number(in, "pattern edge");
                case "steepnessEdge" -> steepnessEdge = number(in, "pattern steepnessEdge");
                case "seed" -> seed = seed(in);
                default -> in.skipValue();
            }
        }
        in.endObject();
        if (kind == null) throw new PaletteFormatException("pattern has no \"kind\"");
        String name = kind;
        PalettePattern.Kind parsed = PalettePattern.Kind.fromFileName(kind)
                .orElseThrow(() -> new PaletteFormatException("unknown pattern \"" + clip(name) + "\""));
        checkRange("patchSize", patchSize, MixLayout.Patches.MIN_SIZE, MixLayout.Patches.MAX_SIZE);
        checkRange("edge", edge, MixLayout.Gradient.MIN_EDGE, MixLayout.Gradient.MAX_EDGE);
        checkRange("steepnessEdge", steepnessEdge, MixLayout.Steepness.MIN_EDGE, MixLayout.Steepness.MAX_EDGE);
        return new PalettePattern(parsed, (int) patchSize, (int) edge, (int) steepnessEdge, seed);
    }

    private static void checkRange(String what, long value, int min, int max) throws PaletteFormatException {
        if (value < min || value > max) {
            throw new PaletteFormatException("pattern " + what + " " + value + " is outside " + min + "-" + max);
        }
    }

    /** A pattern's seed: a whole number within a long. */
    private static long seed(JsonReader in) throws IOException, PaletteFormatException {
        if (in.peek() != JsonToken.NUMBER) throw new PaletteFormatException("pattern seed is not a number");
        String digits = in.nextString();
        if (!SEED.matcher(digits).matches()) {
            throw new PaletteFormatException("pattern seed " + clip(digits) + " is not a whole number in range");
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException outOfRange) {
            throw new PaletteFormatException("pattern seed " + clip(digits) + " is not a whole number in range");
        }
    }

    private static List<BlockPalette.Entry> entries(JsonReader in) throws IOException, PaletteFormatException {
        if (in.peek() != JsonToken.BEGIN_ARRAY) throw new PaletteFormatException("\"entries\" is not a list");
        in.beginArray();
        List<BlockPalette.Entry> entries = new ArrayList<>();
        while (in.hasNext()) {
            int index = entries.size() + 1;
            if (index > BlockPalette.MAX_ENTRIES) {
                throw new PaletteFormatException("more than " + BlockPalette.MAX_ENTRIES + " entries");
            }
            if (in.peek() != JsonToken.BEGIN_OBJECT) throw new PaletteFormatException("entry " + index + " is not an object");
            in.beginObject();
            String state = null;
            Long weight = null;
            Set<String> seen = new HashSet<>();
            while (in.hasNext()) {
                String name = in.nextName();
                if (!seen.add(name)) {
                    throw new PaletteFormatException("entry " + index + ": \"" + clip(name) + "\" is given twice");
                }
                switch (name) {
                    case "state" -> state = string(in, "entry " + index + " state");
                    case "weight" -> weight = number(in, "entry " + index + " weight");
                    default -> in.skipValue();
                }
            }
            in.endObject();
            if (state == null) throw new PaletteFormatException("entry " + index + " has no \"state\"");
            if (weight == null) throw new PaletteFormatException("entry " + index + " has no \"weight\"");
            if (weight < 1 || weight > BlockPalette.MAX_WEIGHT) {
                throw new PaletteFormatException("entry " + index + ": weight " + weight + " is outside 1-"
                        + BlockPalette.MAX_WEIGHT);
            }
            if (state.isBlank()) throw new PaletteFormatException("entry " + index + " has an empty state");
            if (state.getBytes(StandardCharsets.UTF_8).length > BlockPalette.MAX_STATE_BYTES) {
                throw new PaletteFormatException("entry " + index + ": a state over " + BlockPalette.MAX_STATE_BYTES
                        + " bytes");
            }
            entries.add(new BlockPalette.Entry(state, weight.intValue()));
        }
        in.endArray();
        if (entries.isEmpty()) throw new PaletteFormatException("no entries");
        return entries;
    }

    private static String string(JsonReader in, String what) throws IOException, PaletteFormatException {
        if (in.peek() != JsonToken.STRING) throw new PaletteFormatException(what + " is not text");
        return in.nextString();
    }

    /** A whole number, written as one ({@code 4}, never {@code 4.0} or {@code 4e0}); huge ones are out of range. */
    private static long number(JsonReader in, String what) throws IOException, PaletteFormatException {
        if (in.peek() != JsonToken.NUMBER) throw new PaletteFormatException(what + " is not a number");
        String digits = in.nextString();
        if (!WHOLE_NUMBER.matcher(digits).matches()) {
            throw new PaletteFormatException(what + " " + clip(digits) + " is not a whole number in range");
        }
        return Long.parseLong(digits);
    }

    // ================================================================== states

    /**
     * The palette a player saves, in this server's own state text ({@link StateSpace#format}, every property given):
     * every state must be one this server knows, and two entries must not name the same state.
     *
     * @throws PaletteFormatException naming the first state that is unknown, repeated or too long once written out
     */
    public static BlockPalette canonical(BlockPalette palette, StateSpace states) throws PaletteFormatException {
        Map<String, BlockPalette.Entry> byState = new LinkedHashMap<>();
        for (BlockPalette.Entry entry : palette.entries()) {
            int handle = parse(states, entry.state());
            if (handle < 0) throw new PaletteFormatException("unknown block state: " + clip(entry.state()));
            String text = states.format(handle);
            if (text.getBytes(StandardCharsets.UTF_8).length > BlockPalette.MAX_STATE_BYTES) {
                throw new PaletteFormatException("block state over " + BlockPalette.MAX_STATE_BYTES + " bytes: " + clip(text));
            }
            BlockPalette.Entry previous = byState.putIfAbsent(text, new BlockPalette.Entry(text, entry.weight()));
            if (previous != null) throw new PaletteFormatException("listed twice: " + clip(text));
        }
        return new BlockPalette(List.copyOf(byState.values()), palette.pattern());
    }

    /**
     * A file's palette as this server knows it: states saved by an older game are first upgraded with {@code fixes};
     * states this server doesn't know (or can't write out within {@value BlockPalette#MAX_STATE_BYTES} bytes) are left
     * out and counted, the first {@value #SHOWN_DROPPED} named; entries that name the same state are merged, their
     * weights added (at most {@value BlockPalette#MAX_WEIGHT}).
     *
     * @throws PaletteFormatException when nothing is left
     */
    public static Loaded resolve(Content content, StateSpace states, DataFixHook fixes) throws PaletteFormatException {
        int target = fixes.targetDataVersion();
        boolean upgrade = content.dataVersion().isPresent() && content.dataVersion().getAsInt() < target;
        Map<String, Integer> weights = new LinkedHashMap<>();
        int dropped = 0;
        List<String> droppedStates = new ArrayList<>();
        for (BlockPalette.Entry entry : content.entries()) {
            String text = entry.state();
            if (upgrade) {
                try {
                    text = fixes.fixBlockState(text, content.dataVersion().getAsInt());
                } catch (RuntimeException e) {
                    text = null; // the data fixer can't read it: not a state of this game
                }
            }
            int handle = text == null ? -1 : parse(states, text);
            String written = handle < 0 ? null : states.format(handle);
            if (written == null || written.getBytes(StandardCharsets.UTF_8).length > BlockPalette.MAX_STATE_BYTES) {
                dropped++;
                if (droppedStates.size() < SHOWN_DROPPED) droppedStates.add(entry.state());
                continue;
            }
            weights.merge(written, entry.weight(), (a, b) -> Math.min(BlockPalette.MAX_WEIGHT, a + b));
        }
        if (weights.isEmpty()) {
            throw new PaletteFormatException("none of its " + dropped + " block" + (dropped == 1 ? "" : "s")
                    + " exist on this server (" + clip(String.join(", ", droppedStates)) + ")");
        }
        List<BlockPalette.Entry> entries = new ArrayList<>(weights.size());
        weights.forEach((state, weight) -> entries.add(new BlockPalette.Entry(state, weight)));
        return new Loaded(new BlockPalette(entries, content.pattern()), dropped, droppedStates);
    }

    private static int parse(StateSpace states, String text) {
        try {
            return states.parse(text);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static String clip(String text) {
        String value = text == null ? "" : text;
        return value.length() <= 120 ? value : value.substring(0, 120) + "…";
    }

    /** For tests: reads {@code text} as a file would be. */
    static Content decode(String text) throws PaletteFormatException {
        return decode(text.getBytes(StandardCharsets.UTF_8));
    }
}
