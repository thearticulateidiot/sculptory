package dev.sculptory.fabric.client.editor.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import net.minecraft.client.font.FontStorage;
import net.minecraft.client.font.TextRenderer;
import org.junit.jupiter.api.Test;

/**
 * A {@link TextInput} asked to select its text before its vanilla field exists (a slider's value field, opened by a
 * double-click, Ctrl+click or Enter) holds the text selected once the field is made, so typing replaces it: "20" typed
 * over 100 gives 20, not "10020" (found in the play check). Uses the real vanilla field with a text renderer made
 * without a running client (fonts without glyphs).
 */
class TextInputSelectAllTest {
    private static TextRenderer renderer() {
        return new TextRenderer(id -> new FontStorage(null, id), false);
    }

    private static TextInput typedOver(String before, String typed, boolean selectAll) {
        UiContext ctx = new UiContext(McFontText.INSTANCE, Theme.DARK);
        TextInput input = new TextInput(before, null);
        if (selectAll) {
            input.selectAll();
        }
        input.layout(ctx, new Rect(0, 0, 80, 16));
        input.attach(renderer(), ctx);
        ctx.setFocus(input);
        for (char chr : typed.toCharArray()) {
            input.charTyped(ctx, chr, 0);
        }
        return input;
    }

    @Test
    void textSelectedBeforeTheFieldExistsIsReplacedByWhatIsTyped() {
        assertEquals("20", typedOver("100", "20", true).text());
        assertEquals("0.5", typedOver("0.80", "0.5", true).text());
    }

    @Test
    void withoutSelectingTypingGoesOnAtTheEnd() {
        assertEquals("10020", typedOver("100", "20", false).text());
    }
}
