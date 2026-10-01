package dev.sculptory.fabric.client.editor.hud;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.NotificationsWindow;
import dev.sculptory.fabric.client.session.Notice;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** Every text the key sheet, the quick start card and the Notifications window show has English. */
class HudTranslationsTest {
    /** Records the keys asked for. */
    private static final class Recording implements Translator {
        final Set<String> keys = new TreeSet<>();

        @Override
        public String translate(String key, Object... args) {
            keys.add(key);
            return Translator.KEYS.translate(key, args);
        }

        @Override
        public boolean has(String key) {
            return false;
        }
    }

    @Test
    void everyKeyTheNewPartsAskForHasEnglish() throws IOException {
        Recording tr = new Recording();
        EditorKeymap keymap = EditorKeymap.defaults();
        HelpSheet.build(keymap, tr, KeySheetTest.VANILLA);

        KeySheet sheet = new KeySheet(KeySheetTest.TEXT, Theme.DARK, keymap, tr, () -> KeySheetTest.VANILLA,
                slot -> Optional.empty(), () -> {});
        sheet.open();
        sheet.layout(640, 360);
        sheet.setFilter("zzz");
        sheet.layout(640, 360);

        new QuickStart(KeySheetTest.TEXT, Theme.DARK, keymap, tr, () -> KeySheetTest.VANILLA).show();

        ToastStack toasts = new ToastStack(() -> 0L, () -> LocalTime.NOON);
        NotificationsWindow empty = new NotificationsWindow(toasts.log(), tr);
        empty.refresh();
        toasts.show(Notice.Level.INFO, "twice");
        toasts.show(Notice.Level.INFO, "twice");
        new NotificationsWindow(toasts.log(), tr).refresh();

        tr.keys.add("sculptory.key.focus_next_window");
        tr.keys.add(EditorWindows.titleKey(EditorWindows.NOTIFICATIONS));
        tr.keys.add("sculptory.notice.no_window_to_focus");

        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in, "en_us.json is on the test classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (String key : tr.keys) {
            assertTrue(lang.has(key), key);
        }
        assertTrue(tr.keys.size() > 40, "the parts were built: " + tr.keys.size());
    }
}
