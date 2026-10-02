package dev.sculptory.server.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.schem.DataFixHook;
import dev.sculptory.core.testing.FakeStateSpace;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The palette file format: round trips, what a hostile or hand-edited file can't do, and state resolution. */
class PaletteFileTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final DataFixHook NO_FIXES = DataFixHook.identity(3955);
    private static final BlockPalette MIX = new BlockPalette(List.of(
            new BlockPalette.Entry("minecraft:grass_block[snowy=false]", 4),
            new BlockPalette.Entry("minecraft:sea_pickle[pickles=2,waterlogged=true]", 1),
            new BlockPalette.Entry("minecraft:stone", 1000)));

    private static String problem(String text) {
        return assertThrows(PaletteFile.PaletteFormatException.class, () -> PaletteFile.decode(text)).getMessage();
    }

    private static String file(String entries) {
        return "{\"format\": \"sculptory:palette\", \"version\": 1, \"entries\": [" + entries + "]}";
    }

    private static PaletteFile.Content content(BlockPalette.Entry... entries) {
        return new PaletteFile.Content(List.of(entries), OptionalInt.empty());
    }

    // ---------------------------------------------------------------- format

    @Test
    void aPaletteRoundTripsThroughItsFileExactly() throws Exception {
        byte[] bytes = PaletteFile.encode(MIX, 3955);
        PaletteFile.Content read = PaletteFile.decode(bytes);
        assertEquals(MIX.entries(), read.entries());
        assertEquals(OptionalInt.of(3955), read.dataVersion());
        // Readable by hand and by any JSON tool.
        JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("sculptory:palette", json.get("format").getAsString());
        assertEquals(1, json.get("version").getAsInt());
        assertEquals(3, json.getAsJsonArray("entries").size());
        assertEquals("minecraft:sea_pickle[pickles=2,waterlogged=true]",
                json.getAsJsonArray("entries").get(1).getAsJsonObject().get("state").getAsString(),
                "the exact state, waterlogged included");
        // Text that needs escaping survives.
        BlockPalette odd = BlockPalette.of("modded:\"q\\é ", 2);
        assertEquals(odd.entries(), PaletteFile.decode(PaletteFile.encode(odd, 1)).entries());
    }

    @Test
    void theFullestPaletteStaysFarUnderTheFileCap() throws Exception {
        List<BlockPalette.Entry> entries = new ArrayList<>();
        for (int i = 0; i < BlockPalette.MAX_ENTRIES; i++) {
            String suffix = Integer.toString(100 + i);
            entries.add(new BlockPalette.Entry("m:" + "\"".repeat((BlockPalette.MAX_STATE_BYTES - 2 - suffix.length()))
                    + suffix, BlockPalette.MAX_WEIGHT));
        }
        byte[] bytes = PaletteFile.encode(new BlockPalette(entries), Integer.MAX_VALUE);
        assertTrue(bytes.length < PaletteFile.MAX_BYTES, bytes.length + " bytes");
        assertEquals(entries, PaletteFile.decode(bytes).entries());
    }

    @Test
    void handEditedExtrasAreReadLeniently() throws Exception {
        String text = "﻿{\n  \"entries\": [{\"weight\": 3, \"note\": {\"deep\": [[[1]]]}, \"state\": \"minecraft:stone\"},"
                + " {\"state\": \"minecraft:stone\", \"weight\": 2}],\n  \"version\": 1, \"comment\": \"mine\","
                + " \"format\": \"sculptory:palette\"\n}\n";
        PaletteFile.Content read = PaletteFile.decode(text);
        assertEquals(List.of(new BlockPalette.Entry("minecraft:stone", 3), new BlockPalette.Entry("minecraft:stone", 2)),
                read.entries(), "members in any order, unknown ones skipped, a byte order mark, repeats kept for now");
        assertEquals(OptionalInt.empty(), read.dataVersion());
    }

    @Test
    void aPaletteSavedBeforeTheRenameIsStillRead() throws Exception {
        String old = "{\"format\": \"buildersuite:palette\", \"version\": 1,"
                + " \"entries\": [{\"state\": \"minecraft:stone\", \"weight\": 2}]}";
        assertEquals(List.of(new BlockPalette.Entry("minecraft:stone", 2)), PaletteFile.decode(old).entries());
        JsonObject rewritten = JsonParser.parseString(new String(PaletteFile.encode(MIX, 3955), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals("sculptory:palette", rewritten.get("format").getAsString(), "saved under the new name only");
    }

    @Test
    void wrongFilesAreRefusedSayingWhy() {
        assertTrue(problem("").contains("not"), "empty");
        assertTrue(problem("[1, 2]").contains("no JSON object"));
        assertTrue(problem("not json").contains("not"));
        assertTrue(problem("{\"version\": 1, \"entries\": []}").contains("no \"format\""));
        assertTrue(problem("{\"format\": \"worldedit:palette\", \"version\": 1}").contains("format \"worldedit:palette\""));
        assertTrue(problem("{\"format\": \"sculptory:palette\", \"entries\": []}").contains("no \"version\""));
        assertTrue(problem("{\"format\": \"sculptory:palette\", \"version\": 2, \"entries\": {\"new\": true}}")
                .contains("version 2"), "a newer format is refused as such, whatever its entries look like");
        assertTrue(problem("{\"format\": \"sculptory:palette\", \"version\": \"1\"}").contains("not a number"));
        assertTrue(problem("{\"format\": \"sculptory:palette\", \"version\": 1.0}").contains("whole number"));
        assertTrue(problem("{\"format\": \"sculptory:palette\", \"version\": 1}").contains("no \"entries\""));
        assertTrue(problem(file("")).contains("no entries"));
        String after = problem(file("") + " {}");
        assertTrue(after.contains("text after") || after.contains("JSON"), after);
        assertTrue(problem("{\"format\": \"sculptory:palette\", \"format\": \"x\", \"version\": 1, \"entries\": []}")
                .contains("twice"));
        assertTrue(problem("{\"format\": \"sculptory:palette\", \"version\": 1, \"entries\": \"stone\"}")
                .contains("not a list"));
        assertTrue(problem("{\"format\": \"sculptory:palette\", \"version\": 1, \"dataVersion\": -5, \"entries\": ["
                + "{\"state\": \"minecraft:stone\", \"weight\": 1}]}").contains("dataVersion"));
        assertTrue(problem("{'format': 'sculptory:palette'}").contains("JSON"), "no lenient JSON");
        assertTrue(problem(file("{\"state\": \"minecraft:stone\", \"weight\": 1},")).contains("JSON"), "trailing comma");
    }

    /** A file with a pattern member: {@code "pattern": {…}} after the entries. */
    private static String patterned(String pattern) {
        return "{\"format\": \"sculptory:palette\", \"version\": 1, \"entries\": [{\"state\": \"minecraft:stone\","
                + " \"weight\": 1}], \"pattern\": " + pattern + "}";
    }

    /**
     * Mix patterns: a palette's pattern and order round-trip through its file
     * for every kind and at the value limits; a file without one (every file written before) loads as Random; missing
     * numbers take their defaults.
     */
    @Test
    void thePatternRoundTripsAndOldFilesLoadAsRandom() throws Exception {
        for (PalettePattern.Kind kind : PalettePattern.Kind.values()) {
            for (PalettePattern pattern : List.of(new PalettePattern(kind, 1, 0, 0, Long.MIN_VALUE),
                    new PalettePattern(kind, 32, 32, 45, Long.MAX_VALUE), new PalettePattern(kind, 9, 3, 12, 0))) {
                PaletteFile.Content read = PaletteFile.decode(PaletteFile.encode(MIX.withPattern(pattern), 3955));
                assertEquals(pattern, read.pattern());
                assertEquals(MIX.entries(), read.entries(), "the order is kept");
                assertEquals(MIX.withPattern(pattern), PaletteFile.resolve(read, STATES, NO_FIXES).palette());
                assertEquals(MIX.withPattern(pattern), PaletteFile.canonical(MIX.withPattern(pattern), STATES));
            }
        }
        JsonObject json = JsonParser.parseString(new String(PaletteFile.encode(MIX.withPattern(
                new PalettePattern(PalettePattern.Kind.GRADIENT, 5, 7, 20, -3)), 3955), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("pattern");
        assertEquals("gradient", json.get("kind").getAsString());
        assertEquals(5, json.get("patchSize").getAsInt());
        assertEquals(7, json.get("edge").getAsInt());
        assertEquals(20, json.get("steepnessEdge").getAsInt());
        assertEquals(-3, json.get("seed").getAsLong());
        assertEquals(1, JsonParser.parseString(new String(PaletteFile.encode(MIX, 1), StandardCharsets.UTF_8))
                .getAsJsonObject().get("version").getAsInt(), "still version 1: older builds ignore the member");
        // Every file written before patterns existed: Random, its order kept.
        PaletteFile.Content old = PaletteFile.decode(file("{\"state\": \"minecraft:sand\", \"weight\": 2},"
                + " {\"state\": \"minecraft:stone\", \"weight\": 1}"));
        assertEquals(PalettePattern.RANDOM, old.pattern());
        assertEquals(PalettePattern.RANDOM, PaletteFile.resolve(old, STATES, NO_FIXES).palette().pattern());
        assertEquals("minecraft:sand", PaletteFile.resolve(old, STATES, NO_FIXES).palette().entries().get(0).state());
        // Missing numbers take their defaults; unknown members are ignored.
        assertEquals(new PalettePattern(PalettePattern.Kind.PATCHES, 6, 4, 10, 0),
                PaletteFile.decode(patterned("{\"kind\": \"patches\", \"note\": [1]}")).pattern());
        assertEquals(new PalettePattern(PalettePattern.Kind.STEEPNESS, 6, 4, 45, 9223372036854775807L),
                PaletteFile.decode(patterned("{\"seed\": 9223372036854775807, \"steepnessEdge\": 45,"
                        + " \"kind\": \"steepness\"}")).pattern());
    }

    /** A pattern that can't be read is refused saying why: an unknown kind, no kind, values out of range or not numbers. */
    @Test
    void badPatternsAreRefusedSayingWhy() {
        assertTrue(problem(patterned("\"patches\"")).contains("\"pattern\" is not an object"));
        assertTrue(problem(patterned("{\"kind\": \"stripes\"}")).contains("unknown pattern \"stripes\""));
        assertTrue(problem(patterned("{\"kind\": \"Patches\"}")).contains("unknown pattern"), "names are lower-case");
        assertTrue(problem(patterned("{\"patchSize\": 4}")).contains("no \"kind\""));
        assertTrue(problem(patterned("{\"kind\": 2}")).contains("not text"));
        assertTrue(problem(patterned("{\"kind\": \"patches\", \"patchSize\": 0}")).contains("patchSize 0 is outside 1-32"));
        assertTrue(problem(patterned("{\"kind\": \"patches\", \"patchSize\": 33}")).contains("outside 1-32"));
        assertTrue(problem(patterned("{\"kind\": \"gradient\", \"edge\": -1}")).contains("edge -1 is outside 0-32"));
        assertTrue(problem(patterned("{\"kind\": \"gradient\", \"edge\": 33}")).contains("outside 0-32"));
        assertTrue(problem(patterned("{\"kind\": \"steepness\", \"steepnessEdge\": 46}")).contains("outside 0-45"));
        assertTrue(problem(patterned("{\"kind\": \"patches\", \"patchSize\": 4.5}")).contains("whole number"));
        assertTrue(problem(patterned("{\"kind\": \"patches\", \"patchSize\": \"4\"}")).contains("not a number"));
        assertTrue(problem(patterned("{\"kind\": \"patches\", \"seed\": 9223372036854775808}")).contains("seed"));
        assertTrue(problem(patterned("{\"kind\": \"patches\", \"seed\": \"7\"}")).contains("seed is not a number"));
        assertTrue(problem(patterned("{\"kind\": \"patches\", \"kind\": \"random\"}")).contains("twice"));
    }

    @Test
    void badEntriesAreRefusedSayingWhich() {
        assertTrue(problem(file("\"minecraft:stone\"")).contains("entry 1 is not an object"));
        assertTrue(problem(file("{\"weight\": 1}")).contains("entry 1 has no \"state\""));
        assertTrue(problem(file("{\"state\": \"minecraft:stone\"}")).contains("entry 1 has no \"weight\""));
        assertTrue(problem(file("{\"state\": \"minecraft:stone\", \"weight\": 0}")).contains("outside 1-1000"));
        assertTrue(problem(file("{\"state\": \"minecraft:stone\", \"weight\": 1001}")).contains("outside 1-1000"));
        assertTrue(problem(file("{\"state\": \"minecraft:stone\", \"weight\": 99999999999999999999999}"))
                .contains("whole number"), "a huge weight");
        assertTrue(problem(file("{\"state\": \"minecraft:stone\", \"weight\": 2.5}")).contains("whole number"));
        assertTrue(problem(file("{\"state\": \"minecraft:stone\", \"weight\": 1e2}")).contains("whole number"));
        assertTrue(problem(file("{\"state\": 5, \"weight\": 1}")).contains("not text"));
        assertTrue(problem(file("{\"state\": \"  \", \"weight\": 1}")).contains("empty state"));
        assertTrue(problem(file("{\"state\": \"m:" + "x".repeat(BlockPalette.MAX_STATE_BYTES) + "\", \"weight\": 1}"))
                .contains("over 256 bytes"));
        assertTrue(problem(file("{\"state\": \"a\", \"state\": \"b\", \"weight\": 1}")).contains("twice"));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i <= BlockPalette.MAX_ENTRIES; i++) {
            if (i > 0) many.append(',');
            many.append("{\"state\": \"m:b").append(i).append("\", \"weight\": 1}");
        }
        assertTrue(problem(file(many.toString())).contains("more than 64 entries"));
    }

    @Test
    void hostileFilesCostLittleAndFailCleanly() {
        byte[] huge = new byte[PaletteFile.MAX_BYTES + 1];
        java.util.Arrays.fill(huge, (byte) ' ');
        assertTrue(assertThrows(PaletteFile.PaletteFormatException.class, () -> PaletteFile.decode(huge)).getMessage()
                .contains("at most"));
        byte[] notUtf8 = file("{\"state\": \"minecraft:stone\", \"weight\": 1}").getBytes(StandardCharsets.UTF_8);
        notUtf8[notUtf8.length / 2] = (byte) 0xC3;
        notUtf8[notUtf8.length / 2 + 1] = (byte) 0x28;
        assertTrue(assertThrows(PaletteFile.PaletteFormatException.class, () -> PaletteFile.decode(notUtf8)).getMessage()
                .contains("UTF-8"));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            // Nesting as deep as the cap allows, in a member that is skipped and where an entry should be: no stack
            // overflow, no hang, a clean refusal or a clean read.
            int depth = PaletteFile.MAX_BYTES / 2 - 100;
            String deep = "[".repeat(depth) + "]".repeat(depth);
            String skipped = "{\"format\": \"sculptory:palette\", \"version\": 1, \"x\": " + deep + ", \"entries\": ["
                    + "{\"state\": \"minecraft:stone\", \"weight\": 1}]}";
            if (skipped.length() <= PaletteFile.MAX_BYTES) {
                try {
                    PaletteFile.decode(skipped);
                } catch (PaletteFile.PaletteFormatException refused) {
                    // a reader with a nesting limit refuses it: also fine
                }
            }
            String asEntry = file(deep.substring(0, deep.length() / 2 - 50) + deep.substring(deep.length() / 2 + 50));
            problem(asEntry);
            problem("{".repeat(PaletteFile.MAX_BYTES / 2));
        });
    }

    // ---------------------------------------------------------------- states

    @Test
    void aSaveKeepsTheServersExactStatesAndRefusesUnknownOrRepeatedOnes() throws Exception {
        BlockPalette saved = PaletteFile.canonical(new BlockPalette(List.of(
                new BlockPalette.Entry("minecraft:grass_block", 4),
                new BlockPalette.Entry("minecraft:sea_pickle[waterlogged=true]", 1))), STATES);
        assertEquals(List.of(new BlockPalette.Entry("minecraft:grass_block[snowy=false]", 4),
                new BlockPalette.Entry("minecraft:sea_pickle[pickles=1,waterlogged=true]", 1)), saved.entries(),
                "left-out properties are filled in; a waterlogged state stays waterlogged");
        String unknown = assertThrows(PaletteFile.PaletteFormatException.class, () -> PaletteFile.canonical(
                new BlockPalette(List.of(new BlockPalette.Entry("minecraft:stone", 1),
                        new BlockPalette.Entry("modded:gone", 1))), STATES)).getMessage();
        assertEquals("unknown block state: modded:gone", unknown);
        assertTrue(assertThrows(PaletteFile.PaletteFormatException.class, () -> PaletteFile.canonical(
                new BlockPalette(List.of(new BlockPalette.Entry("minecraft:grass_block", 1),
                        new BlockPalette.Entry("minecraft:grass_block[snowy=false]", 2))), STATES)).getMessage()
                .startsWith("listed twice"), "two texts for one state");
        assertTrue(assertThrows(PaletteFile.PaletteFormatException.class, () -> PaletteFile.canonical(
                BlockPalette.of("minecraft:stone[bogus=1]", 1), STATES)).getMessage().startsWith("unknown"));
        assertTrue(assertThrows(PaletteFile.PaletteFormatException.class, () -> PaletteFile.canonical(
                BlockPalette.of("not a state", 1), STATES)).getMessage().startsWith("unknown"));
    }

    @Test
    void aLoadLeavesOutAndCountsUnknownStatesAndMergesRepeats() throws Exception {
        PaletteFile.Loaded loaded = PaletteFile.resolve(content(
                new BlockPalette.Entry("minecraft:stone", 3),
                new BlockPalette.Entry("modded:gone", 5),
                new BlockPalette.Entry("minecraft:grass_block", 700),
                new BlockPalette.Entry("minecraft:grass_block[snowy=false]", 600),
                new BlockPalette.Entry("minecraft:stone[bogus=1]", 1),
                new BlockPalette.Entry("broken[", 1),
                new BlockPalette.Entry("modded:also_gone", 1)), STATES, NO_FIXES);
        assertEquals(List.of(new BlockPalette.Entry("minecraft:stone", 3),
                new BlockPalette.Entry("minecraft:grass_block[snowy=false]", 1000)), loaded.palette().entries(),
                "the order kept, the same state merged with its weights added up to 1000");
        assertEquals(4, loaded.dropped());
        assertEquals(List.of("modded:gone", "minecraft:stone[bogus=1]", "broken["), loaded.droppedStates(),
                "the first three named as the file has them");
        String none = assertThrows(PaletteFile.PaletteFormatException.class, () -> PaletteFile.resolve(
                content(new BlockPalette.Entry("modded:gone", 1)), STATES, NO_FIXES)).getMessage();
        assertEquals("none of its 1 block exist on this server (modded:gone)", none);
    }

    @Test
    void statesFromAnOlderGameAreUpgradedFirst() throws Exception {
        List<String> asked = new ArrayList<>();
        DataFixHook renames = new DataFixHook() {
            @Override
            public int targetDataVersion() {
                return 3955;
            }

            @Override
            public String fixBlockState(String state, int fromDataVersion) {
                asked.add(state + "@" + fromDataVersion);
                if (state.equals("minecraft:crash")) throw new IllegalStateException("the fixer can't read it");
                return state.equals("minecraft:old_rock") ? "minecraft:stone" : state;
            }

            @Override
            public NbtCompound fixBlockEntity(NbtCompound blockEntity, int fromDataVersion) {
                return blockEntity;
            }
        };
        List<BlockPalette.Entry> entries = List.of(new BlockPalette.Entry("minecraft:old_rock", 2),
                new BlockPalette.Entry("minecraft:crash", 1), new BlockPalette.Entry("minecraft:dirt", 1));
        PaletteFile.Loaded old = PaletteFile.resolve(new PaletteFile.Content(entries, OptionalInt.of(3700)), STATES,
                renames);
        assertEquals(List.of(new BlockPalette.Entry("minecraft:stone", 2), new BlockPalette.Entry("minecraft:dirt", 1)),
                old.palette().entries());
        assertEquals(1, old.dropped());
        assertEquals(List.of("minecraft:crash"), old.droppedStates());
        assertEquals(3, asked.size());
        asked.clear();
        PaletteFile.Loaded current = PaletteFile.resolve(new PaletteFile.Content(entries, OptionalInt.of(3955)), STATES,
                renames);
        assertEquals(List.of(), asked, "a file of this game's version is not run through the fixer");
        assertEquals(2, current.dropped());
        PaletteFile.resolve(new PaletteFile.Content(entries, OptionalInt.empty()), STATES, renames);
        assertEquals(List.of(), asked, "nor one that doesn't say");
    }
}
