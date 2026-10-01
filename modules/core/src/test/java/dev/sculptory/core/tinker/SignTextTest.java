package dev.sculptory.core.tinker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Sign text as Tinker edits it: plain lines, cleaned like vanilla's sign editor, colours by dye name. */
class SignTextTest {
    @Test
    void linesLoseFormattingCodesAndControlCharactersAndAreCut() {
        assertEquals("Hello", SignText.clean("§cHel§llo"));
        assertEquals("ab", SignText.clean("a\nb"));
        assertEquals("tab", SignText.clean("t\ta\u0000b\u007f"));
        assertEquals("", SignText.clean("§"), "a code at the end");
        assertEquals("x".repeat(SignText.MAX_LINE_CHARS), SignText.clean("x".repeat(SignText.MAX_LINE_CHARS + 50)));
        String emoji = "😀";
        assertEquals(emoji, SignText.clean(emoji), "a surrogate pair stays whole");
        assertEquals("a", SignText.clean("a\uD83D"), "a lone surrogate goes");
        String cut = SignText.clean("x".repeat(SignText.MAX_LINE_CHARS - 1) + emoji);
        assertEquals(SignText.MAX_LINE_CHARS - 1, cut.length(), "a pair that does not fit is not split");
    }

    @Test
    void sidesAreCleanedAndChecked() {
        SignText.Side side = new SignText.Side(List.of("§aOne", "Two", "", "Four"), "blue", true);
        assertEquals(List.of("One", "Two", "", "Four"), side.lines());
        assertEquals("Three", side.withLine(2, "Three").lines().get(2));
        assertEquals("red", side.withColor("red").color());
        assertEquals(false, side.withGlowing(false).glowing());
        assertThrows(IllegalArgumentException.class, () -> new SignText.Side(List.of("a", "b", "c"), "blue", false));
        assertThrows(IllegalArgumentException.class, () -> new SignText.Side(List.of("", "", "", ""), "chartreuse", false));
        SignText text = SignText.EMPTY.withSide(false, side);
        assertEquals(side, text.side(false));
        assertEquals(SignText.Side.EMPTY, text.side(true));
        assertEquals(16, SignText.COLORS.size());
        assertEquals("black", SignText.Side.EMPTY.color());
    }
}
