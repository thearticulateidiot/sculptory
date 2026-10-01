package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.commands.Command;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tutorial.Arg;
import dev.sculptory.fabric.client.editor.tutorial.Condition;
import dev.sculptory.fabric.client.editor.tutorial.EditorProbe;
import dev.sculptory.fabric.client.editor.tutorial.LessonEdits;
import dev.sculptory.fabric.client.editor.tutorial.Lesson;
import dev.sculptory.fabric.client.editor.tutorial.Lessons;
import dev.sculptory.fabric.client.editor.tutorial.Step;
import dev.sculptory.fabric.client.editor.tutorial.StepTexts;
import dev.sculptory.fabric.client.editor.tutorial.Target;
import dev.sculptory.fabric.client.editor.tutorial.TutorialStore;
import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.wiki.WikiPage;
import dev.sculptory.fabric.client.editor.wiki.WikiParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The lessons as data: 4-10 steps each, every text in English with as many placeholders as the step fills in, keys
 * shown as bound, valid highlight targets (a palette slot the step's key selects, a menu row of that menu, a registered
 * window, a setting the tool has), frozen wiki page ids, and conditions that start on the real editor and don't tick
 * on an idle frame (only the few that ask for a state the idle editor is already in).
 */
class TutorialLessonsTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("%(.)");
    /** en_us.json as written (placeholders unfilled). */
    private static final JsonObject LANG = lang();

    private static JsonObject lang() {
        try (InputStream in = TutorialLessonsTest.class.getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final EditorTestRig rig = new EditorTestRig();
    private EditorUi ui;

    @BeforeEach
    void build() {
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, McFontText.INSTANCE,
                new ToastStack(() -> 0L), new EditorUi.Services(English.INSTANCE, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, new UiScale()));
        rig.controller.setUi(ui);
        ui.setEditing(true);
        rig.mode.enter();
        ui.layout(640, 360);
        ui.render(new RecordingGraphics(), 0, 0, 0, false);
    }

    @Test
    void nineteenLessonsInThreeGroupsOfFourToTenStepsWithStableIds() {
        List<Lesson> lessons = Lessons.all();
        assertEquals(List.of("getting_around", "menus", "select", "edit", "history", "settings",
                "terrain", "weather", "paint", "patterns", "shapes", "symmetry",
                "library", "flip", "export", "build", "scatter", "tinker", "builder"),
                lessons.stream().map(Lesson::id).toList());
        assertEquals(List.of("basics", "brushes", "building"),
                Lessons.groups().stream().map(Lessons.Group::id).toList());
        assertEquals(lessons, Lessons.groups().stream().flatMap(group -> group.lessons().stream()).toList(),
                "the list is the groups in order");
        for (Lessons.Group group : Lessons.groups()) {
            assertTrue(English.INSTANCE.has(group.titleKey()), group.titleKey());
            for (Lesson lesson : group.lessons()) {
                assertEquals(group, Lessons.groupOf(lesson).orElseThrow());
            }
        }
        for (Lesson lesson : lessons) {
            assertTrue(lesson.size() >= 4 && lesson.size() <= 10, lesson.id() + ": " + lesson.size() + " steps");
            assertTrue(TutorialStore.isLessonId(lesson.id()), lesson.id());
            Set<String> names = new HashSet<>();
            for (Step step : lesson.steps()) {
                assertTrue(names.add(step.name()), lesson.id() + " has step " + step.name() + " twice");
            }
            assertEquals(Lessons.byId(lesson.id()).orElseThrow(), lesson);
        }
        assertEquals("menus", Lessons.after(lessons.get(0)).orElseThrow().id());
        assertTrue(Lessons.after(lessons.get(lessons.size() - 1)).isEmpty());
    }

    @Test
    void everyTextIsInEnglishWithOnePlaceholderPerArgument() {
        for (Lesson lesson : Lessons.all()) {
            assertTrue(English.INSTANCE.has(lesson.titleKey()), lesson.titleKey());
            assertTrue(English.INSTANCE.has(lesson.summaryKey()), lesson.summaryKey());
            for (Step step : lesson.steps()) {
                assertTrue(English.INSTANCE.has(step.textKey()), step.textKey());
                String raw = LANG.get(step.textKey()).getAsString();
                int placeholders = 0;
                Matcher matcher = PLACEHOLDER.matcher(raw);
                while (matcher.find()) {
                    String kind = matcher.group(1);
                    assertTrue(kind.equals("s") || kind.equals("%"), step.textKey() + ": only %s and %%, not %" + kind);
                    if (kind.equals("s")) {
                        placeholders++;
                    }
                }
                assertEquals(step.args().size(), placeholders, step.textKey() + ": \"" + raw + "\"");
                for (Arg arg : step.args()) {
                    if (arg instanceof Arg.Text text) {
                        assertTrue(English.INSTANCE.has(text.key()), text.key());
                    }
                }
            }
        }
    }

    @Test
    void textsShowTheKeysAsBoundAndNoKeyNamesOrPlaceholdersAreLeft() {
        StepTexts texts = new StepTexts(English.INSTANCE, rig.keymap,
                () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"));
        for (Lesson lesson : Lessons.all()) {
            for (Step step : lesson.steps()) {
                String text = texts.text(step);
                assertFalse(text.contains("sculptory."), step.textKey() + ": " + text);
                assertFalse(text.contains("%s"), step.textKey() + ": " + text);
                assertTrue(Character.isUpperCase(text.charAt(0)), step.textKey() + ": " + text);
                assertTrue(text.endsWith(".") || text.endsWith(")"), step.textKey() + " ends a sentence: " + text);
            }
        }
        Step pick = Lessons.byId("select").orElseThrow().step(0);
        assertEquals("Press 1 to pick the Select tool (or click it in the palette at the bottom).", texts.text(pick));
        rig.keymap.bind(KeyAction.TOOL_1, List.of(dev.sculptory.fabric.client.editor.input.KeyChord.parse("ctrl+q")));
        assertEquals("Press Ctrl+Q to pick the Select tool (or click it in the palette at the bottom).",
                texts.text(pick), "a rebound key reads as bound");
        rig.keymap.bind(KeyAction.TOOL_1, List.of());
        assertTrue(texts.text(pick).startsWith("Press (no key) to pick"), texts.text(pick));
        assertEquals("Press B to leave the editor, then B again to come back. The lesson waits for you.",
                texts.text(Lessons.byId("getting_around").orElseThrow().steps().stream()
                        .filter(step -> step.name().equals("leave")).findFirst().orElseThrow()));
        Step power = Lessons.byId("builder").orElseThrow().steps().stream()
                .filter(step -> step.name().equals("power")).findFirst().orElseThrow();
        assertEquals("Press B to leave the editor. Hold G: a ring of powers opens; let go on Place in air. Then press B"
                + " to come back here.", texts.text(power), "the ring key as the client reports it (G by default)");
        texts.setRingKey(() -> "Mouse 4");
        assertTrue(texts.text(power).startsWith("Press B to leave the editor. Hold Mouse 4:"), texts.text(power));
    }

    @Test
    void everyTargetIsSomethingTheEditorHas() {
        Set<String> windows = new TreeSet<>();
        ui.windows().windows().forEach(window -> windows.add(window.id()));
        for (Lesson lesson : Lessons.all()) {
            for (Step step : lesson.steps()) {
                String where = lesson.id() + "." + step.name();
                switch (step.target()) {
                    case null -> {
                    }
                    case Target.PaletteSlot slot -> {
                        assertTrue(ui.paletteButton(slot.slot()).isPresent(), where);
                        step.args().stream().filter(Arg.Key.class::isInstance).map(arg -> ((Arg.Key) arg).action())
                                .filter(action -> action.toolSlot() > 0)
                                .forEach(action -> assertEquals(slot.slot(), action.toolSlot(),
                                        where + ": the key it names selects the slot it points at"));
                    }
                    case Target.MenuTitle title -> assertTrue(title.menu().ordinal() < ui.menuBar().entries().size());
                    case Target.MenuRow row -> {
                        Command command = ui.commands().get(row.commandId()).orElseThrow(() -> new AssertionError(where));
                        assertEquals(row.menu(), command.menu(), where);
                        assertTrue(ui.commands().isVisible(command), where + ": shown in its menu");
                    }
                    case Target.WindowFrame frame -> assertTrue(windows.contains(frame.windowId()), where);
                    case Target.SettingRow row -> assertTrue(rig.ctx.settings(row.tool()).schema().def(row.key())
                            .isPresent(), where + ": " + row.tool() + " has " + row.key());
                    case Target.TopBar item -> assertTrue(ui.topBarBounds(item.item()).isPresent(), where);
                    case Target.ExportDialog dialog -> assertEquals("export.export", where,
                            "the Export… dialog is pointed at by the export step alone");
                }
            }
        }
    }

    @Test
    void wikiLinksUseTheFrozenPageIds() {
        int links = 0;
        for (Lesson lesson : Lessons.all()) {
            for (Step step : lesson.steps()) {
                if (step.wiki() == null) {
                    continue;
                }
                links++;
                assertTrue(Lessons.WIKI_PAGES.contains(step.wiki().page()), step.textKey() + ": " + step.wiki());
                if (step.wiki().anchor() != null) {
                    assertEquals("terrain-brushes#paint", step.wiki().page() + "#" + step.wiki().anchor(),
                            "the only anchor the contract fixes");
                }
            }
        }
        assertTrue(links >= Lessons.all().size(), "every lesson links somewhere: " + links);
        assertEquals(33, Lessons.WIKI_PAGES.size(),
                "the 28 frozen ids, and tools, tinker, builder-mode, weather and navigation");
    }

    @Test
    void everyWikiLinkOpensAPageOfTheRepositorysWikiAtASectionItHas() throws IOException {
        Path wiki = repositoryRoot().resolve("docs").resolve("wiki");
        for (Lesson lesson : Lessons.all()) {
            for (Step step : lesson.steps()) {
                if (step.wiki() == null) {
                    continue;
                }
                Path page = wiki.resolve(step.wiki().page() + ".md");
                assertTrue(Files.isRegularFile(page), step.textKey() + ": " + page);
                if (step.wiki().anchor() != null) {
                    WikiPage parsed = WikiParser.parse(step.wiki().page(), Files.readString(page, StandardCharsets.UTF_8));
                    assertTrue(parsed.hasAnchor(step.wiki().anchor()), step.textKey() + ": #" + step.wiki().anchor());
                }
            }
        }
    }

    /** The repository's root: the nearest folder up from here with settings.gradle and docs. */
    private static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle")) && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("No repository root above " + Path.of("").toAbsolutePath());
    }

    @Test
    void everyConditionStartsOnTheEditorAndOnlyStateStepsTheIdleEditorIsInAreMetAtOnce() {
        EditorProbe probe = new EditorProbe(ui, rig.ctx, rig.controller, rig.platform, new LessonEdits());
        Set<String> metAtOnce = new TreeSet<>();
        for (Lesson lesson : Lessons.all()) {
            for (Step step : lesson.steps()) {
                if (step.isRead()) {
                    continue;
                }
                probe.frame(false);
                Condition.Check check = step.condition().start(probe);
                boolean met = false;
                for (int frame = 0; frame < 3; frame++) {
                    probe.frame(false);
                    met |= check.met(probe);
                }
                if (met) {
                    metAtOnce.add(lesson.id() + "." + step.name());
                }
            }
        }
        assertEquals(Set.of("select.pick", "select.box_again", "select.clear", "shapes.box_again", "symmetry.off",
                "patterns.random", "weather.erode_mode"),
                metAtOnce, "Select is active, nothing is selected and every setting is at its default");
    }
}
