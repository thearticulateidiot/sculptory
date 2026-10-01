package dev.sculptory.fabric.client.editor.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sculptory.fabric.client.editor.commands.CommandRanking.Match;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class CommandRankingTest {
    /** A slice of the menus, in menu order. */
    private static final List<String> NAMES = List.of(
            "Library…", "Save selection as asset…", "Export clipboard as .schem…", "Close editor",
            "Undo", "Redo", "Undo anyway", "History…", "Copy", "Cut", "Paste", "Rotate right", "Rotate left",
            "Flip left–right", "Flip front–back", "Clear clipboard",
            "Deselect", "Convert to box", "Fill", "Replace…", "Erase", "Hollow", "Walls", "Move", "Stack",
            "Set symmetry centre",
            "Select", "Raise", "Lower", "Smooth", "Flatten", "Paint",
            "Tool Settings", "Selection", "Clipboard", "Library", "History", "Keys", "Hide all windows",
            "Reset layout", "Aim at water and lava",
            "Find a command…", "Key sheet", "Change keys…",
            "Radius", "Falloff", "Preset: Big hill");

    private static List<String> rank(String query, List<String> recent) {
        return CommandRanking.rank(query, NAMES, Function.identity(), Function.identity(), recent);
    }

    private static String best(String query) {
        return rank(query, List.of()).get(0);
    }

    @Test
    void theDesignsExamplesComeFirst() {
        assertEquals("Hollow", best("hol"));
        assertEquals("Undo anyway", best("undo a"));
        assertEquals("Library…", best("lib"), "the File menu's Library… before View's Library (menu order)");
        assertEquals("Library", rank("lib", List.of()).get(1));
        assertEquals("Radius", best("rad"));
        assertEquals("Preset: Big hill", best("big"), "a word inside the name");
    }

    @Test
    void caseIsIgnoredAndSpacesAreTrimmed() {
        assertEquals("Hollow", best("  HOL "));
        assertEquals("Undo anyway", best("UNDO   A"));
    }

    @Test
    void aPrefixBeatsAWordStartWhichBeatsLettersInOrder() {
        assertEquals(Match.PREFIX, CommandRanking.match("fl", "Flip left–right"));
        assertEquals(Match.WORD_START, CommandRanking.match("sym cen", "Set symmetry centre"));
        assertEquals(Match.SUBSEQUENCE, CommandRanking.match("flft", "Flip left–right"));
        assertEquals(Match.NONE, CommandRanking.match("zz", "Flip left–right"));
        assertEquals(Match.WORD_START, CommandRanking.match("right", "Flip left–right"), "words split at dashes");

        List<String> fl = rank("fl", List.of());
        assertEquals(List.of("Flip left–right", "Flip front–back", "Flatten"), fl.subList(0, 3),
                "prefix matches in menu order");
        assertEquals("Fill", fl.get(3), "then the word starts and letters in order");
    }

    @Test
    void wordStartsMustKeepTheirOrder() {
        assertEquals(Match.WORD_START, CommandRanking.match("exp clip", "Export clipboard as .schem…"));
        assertEquals(Match.NONE, CommandRanking.match("clip exp", "Export clipboard as .schem…"));
        assertEquals(Match.WORD_START, CommandRanking.match("wat lav", "Aim at water and lava"));
    }

    @Test
    void recentUseBreaksTiesBeforeMenuOrder() {
        assertEquals(List.of("Undo", "Undo anyway"), rank("undo", List.of()));
        assertEquals(List.of("Undo anyway", "Undo"), rank("undo", List.of("Undo anyway")));
        assertEquals("Hollow", rank("hol", List.of("Undo anyway")).get(0), "recency never lifts a worse match");
    }

    @Test
    void anEmptyQueryListsRecentCommandsThenEverythingInMenuOrder() {
        List<String> all = rank("", List.of("Fill", "Radius"));
        assertEquals(NAMES.size(), all.size());
        assertEquals(List.of("Fill", "Radius", "Library…", "Save selection as asset…"), all.subList(0, 4));
        assertEquals(NAMES, rank(" ", List.of()));
    }

    @Test
    void nothingMatchesNonsense() {
        assertEquals(List.of(), rank("qqq", List.of()));
    }
}
