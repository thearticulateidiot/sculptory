package dev.sculptory.fabric.client.editor.tools.scatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.scatter.Outcome;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.ScatterPreviewResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The 250 ms debounce, the staleness indicator and the HUD summary. */
class ScatterPreviewLogicTest {
    private static final long MS = 1_000_000L;

    // ---- Debounce ----

    @Test
    void aChangeIsPreviewed250MillisecondsLaterAndEachChangeRestartsTheWait() {
        PreviewScheduler scheduler = new PreviewScheduler();
        long t = 1_000 * MS;
        assertFalse(scheduler.due(t), "nothing changed yet");
        scheduler.changed(t);
        assertTrue(scheduler.waiting());
        assertFalse(scheduler.due(t + 249 * MS));
        scheduler.changed(t + 200 * MS);
        assertFalse(scheduler.due(t + 300 * MS), "the second change restarted the wait");
        assertTrue(scheduler.due(t + 450 * MS));
        scheduler.sent(t + 450 * MS);
        assertFalse(scheduler.waiting());
        assertFalse(scheduler.due(t + 5_000 * MS), "nothing is retried on its own");
    }

    @Test
    void aRefreshIsDueAtOnceButPreviewsStayAPartSoTheServerTakesThem() {
        PreviewScheduler scheduler = new PreviewScheduler();
        long t = 1_000 * MS;
        scheduler.refresh(t);
        assertTrue(scheduler.due(t));
        scheduler.sent(t);
        scheduler.refresh(t + 20 * MS);
        assertFalse(scheduler.due(t + 20 * MS), "the server takes one preview per tick");
        assertTrue(scheduler.due(t + PreviewScheduler.MIN_INTERVAL_MILLIS * MS));
        scheduler.cancel();
        assertFalse(scheduler.due(t + 1_000 * MS));
    }

    // ---- Staleness ----

    @Test
    void aPlanGoesOutOfDateWhenTheWorldChangesAroundItOrItsTimeRunsOut() {
        long ttl = EditorSession.SCATTER_PLAN_TTL_NANOS;
        long received = 5_000 * MS;
        assertEquals(PlanStaleness.FRESH, PlanStaleness.of(received + 1_000 * MS, received, ttl, 3, 3));
        assertEquals(PlanStaleness.CHANGED, PlanStaleness.of(received + 1_000 * MS, received, ttl, 3, 4));
        long almost = received + ttl - PlanStaleness.MARGIN_NANOS - MS;
        assertEquals(PlanStaleness.FRESH, PlanStaleness.of(almost, received, ttl, 3, 3));
        assertEquals(PlanStaleness.EXPIRED, PlanStaleness.of(almost + MS, received, ttl, 3, 3),
                "expired a little before the server drops it");
        assertEquals(PlanStaleness.EXPIRED, PlanStaleness.of(received + ttl, received, ttl, 3, 9), "expiry wins");
        assertFalse(PlanStaleness.FRESH.stale());
        assertTrue(PlanStaleness.CHANGED.stale());
    }

    // ---- HUD summary ----

    @Test
    void theSummaryCountsPlacementsBlocksAndSkippedColumnsInCheckOrder() {
        TreeMap<String, Integer> rejected = new TreeMap<>(Map.of("COLLISION", 3, "SPACING", 55, "SLOPE", 20,
                "FUTURE_REASON", 2, "WORK_LIMIT", 0));
        List<ScatterPlan.Placement> placements = new ArrayList<>();
        for (int i = 0; i < 142; i++) {
            placements.add(new ScatterPlan.Placement(new dev.sculptory.core.BlockPos(i, 64, 0), 0,
                    dev.sculptory.core.transform.Transform.IDENTITY));
        }
        ScatterPreviewResult plan = new ScatterPreviewResult(1, UUID.randomUUID(), rejected, 38_000, null, placements);
        Translator english = new Translator() {
            @Override
            public String translate(String key, Object... args) {
                String text = switch (key) {
                    case ScatterSummary.SUMMARY -> "%s placements · %s blocks";
                    case ScatterSummary.SKIPPED -> "skipped: %s";
                    default -> key.startsWith(ScatterSummary.OUTCOME_PREFIX)
                            ? key.substring(ScatterSummary.OUTCOME_PREFIX.length()) : key;
                };
                return String.format(text, args);
            }

            @Override
            public boolean has(String key) {
                return !key.endsWith("future_reason");
            }
        };
        assertEquals("142 placements · 38,000 blocks  ·  skipped: slope 20, spacing 55, collision 3, future reason 2",
                ScatterSummary.line(english, plan));
    }

    @Test
    void everyOutcomeHasAReadableNameAndEveryScatterKeyHasEnglishText() throws IOException {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in, "en_us.json is on the test classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        assertEquals(Outcome.values().length, ScatterSummary.outcomeKeys().size());
        for (String key : ScatterSummary.outcomeKeys()) assertTrue(lang.has(key), "no text for " + key);
        // Every literal key the scatter tool's sources use.
        Path sources = Path.of("src/client/java/dev/sculptory/fabric/client/editor/tools/scatter");
        Pattern literal = Pattern.compile("\"(sculptory\\.[a-z0-9_.]+)\"");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.list(sources)) {
            for (Path file : files.toList()) {
                Matcher matcher = literal.matcher(Files.readString(file));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    if (key.endsWith(".")) continue; // a prefix
                    if (!lang.has(key)) missing.add(file.getFileName() + ": " + key);
                }
            }
        }
        // The settings window's labels, section titles and density choices.
        ScatterToolSettings settings = new ScatterToolSettings();
        for (dev.sculptory.fabric.client.editor.settings.Section section : settings.schema().sections()) {
            if (!section.titleKey().isEmpty() && !lang.has(section.titleKey())) missing.add(section.titleKey());
        }
        for (var def : settings.schema().defs()) {
            if (!lang.has(def.labelKey())) missing.add(def.labelKey());
        }
        for (ScatterToolSettings.DensityMode mode : ScatterToolSettings.DensityMode.values()) {
            String key = settings.densityMode.labelKey() + "." + mode.name().toLowerCase(java.util.Locale.ROOT);
            if (!lang.has(key)) missing.add(key);
        }
        assertEquals(List.of(), missing);
    }
}
