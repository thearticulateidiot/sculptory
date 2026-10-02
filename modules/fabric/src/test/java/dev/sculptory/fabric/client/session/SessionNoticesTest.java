package dev.sculptory.fabric.client.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.net.ServerDispatcher;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SessionNoticesTest {
    private static final Limits LIMITS = new Limits(2_097_152L, 500_000L, 32, 20, 1L << 20, 2);
    private static final Pattern PLACEHOLDER = Pattern.compile("%(\\d+\\$)?s");
    private static JsonObject lang;

    @BeforeAll
    static void loadLanguage() throws IOException {
        try (InputStream in = SessionNoticesTest.class.getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in, "en_us.json is on the test classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    /** The key has an English text whose placeholders match the notice's arguments exactly. */
    private static void assertTranslatable(Notice notice) {
        assertTrue(lang.has(notice.key()), "en_us.json has no text for " + notice.key());
        String text = lang.get(notice.key()).getAsString();
        Matcher m = PLACEHOLDER.matcher(text);
        int placeholders = 0;
        while (m.find()) placeholders++;
        assertEquals(notice.args().size(), placeholders, notice.key() + " = \"" + text + "\" for " + notice.args());
        assertFalse(text.contains("_"), notice.key() + " should read as plain English: " + text);
    }

    private static JobTracker.Job finished(JobOutcome outcome, long changed, long isProtected, long conflicts, long stripped) {
        return new JobTracker.Job(new UUID(1, 1), "Fill", 10, 10, Phase.FINALIZE, outcome, changed, isProtected, conflicts,
                stripped);
    }

    @Test
    void everyReasonHasAPlainEnglishMessageForEverySubject() {
        for (RejectReason reason : RejectReason.values()) {
            for (SessionNotices.Subject subject : SessionNotices.Subject.values()) {
                Notice notice = SessionNotices.rejection(reason, subject, LIMITS);
                assertTranslatable(notice);
                assertTrue(notice.level() == Notice.Level.WARNING || notice.level() == Notice.Level.INFO, notice.toString());
            }
        }
    }

    @Test
    void theArgumentFreeReasonKeysNeedNoArguments() {
        // Callers without the server limits (the Select tool today) show these with no arguments.
        for (RejectReason reason : RejectReason.values()) {
            assertTranslatable(Notice.of(Notice.Level.WARNING, SessionNotices.reasonKey(reason)));
        }
    }

    @Test
    void tooLargeQuotesTheLimitThatApplies() {
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.reject.too_large.blocks", "2,097,152"),
                SessionNotices.rejection(RejectReason.TOO_LARGE, SessionNotices.Subject.EDIT, LIMITS));
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.reject.too_large.blocks", "500,000"),
                SessionNotices.rejection(RejectReason.TOO_LARGE, SessionNotices.Subject.COPY, LIMITS));
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.reject.too_large.radius", "32"),
                SessionNotices.rejection(RejectReason.TOO_LARGE, SessionNotices.Subject.STROKE, LIMITS));
    }

    @Test
    void emptyHistorySaysWhichWay() {
        assertEquals(Notice.of(Notice.Level.INFO, "sculptory.notice.nothing_to_undo"),
                SessionNotices.rejection(RejectReason.HISTORY_EMPTY, SessionNotices.Subject.UNDO, LIMITS));
        assertEquals(Notice.of(Notice.Level.INFO, "sculptory.notice.nothing_to_redo"),
                SessionNotices.rejection(RejectReason.HISTORY_EMPTY, SessionNotices.Subject.REDO, LIMITS));
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.reject.no_permission"),
                SessionNotices.rejection(RejectReason.NO_PERMISSION, SessionNotices.Subject.EDIT, LIMITS));
    }

    @Test
    void jobResultsCarryTheServerCounts() {
        assertEquals(List.of(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.job_finished", "Fill", "12,000", "7")),
                SessionNotices.jobResult(finished(JobOutcome.COMPLETED, 12_000, 4, 3, 0), false));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.job_cancelled", "Fill", "300")),
                SessionNotices.jobResult(finished(JobOutcome.CANCELLED, 300, 0, 0, 0), false));
        assertEquals(List.of(Notice.of(Notice.Level.ERROR, "sculptory.notice.job_failed", "Fill", "0")),
                SessionNotices.jobResult(finished(JobOutcome.FAILED, 0, 0, 0, 0), true));
        assertEquals(List.of(
                        Notice.of(Notice.Level.SUCCESS, "sculptory.notice.job_finished", "Fill", "5", "0"),
                        Notice.of(Notice.Level.WARNING, "sculptory.notice.job_stripped_nbt", "Fill", "1")),
                SessionNotices.jobResult(finished(JobOutcome.COMPLETED, 5, 0, 0, 1), false));
        for (Notice notice : SessionNotices.jobResult(finished(JobOutcome.COMPLETED, 5, 1, 0, 1), false)) {
            assertTranslatable(notice);
        }
        assertTranslatable(SessionNotices.jobResult(finished(JobOutcome.CANCELLED, 5, 0, 0, 0), false).get(0));
        assertTranslatable(SessionNotices.jobResult(finished(JobOutcome.FAILED, 5, 0, 0, 0), false).get(0));
    }

    @Test
    void quietResultsSpeakOnlyWhenSomethingWasSkipped() {
        assertTrue(SessionNotices.jobResult(finished(JobOutcome.COMPLETED, 50, 0, 0, 0), true).isEmpty());
        assertEquals(1, SessionNotices.jobResult(finished(JobOutcome.COMPLETED, 45, 0, 5, 0), true).size());
        assertTrue(SessionNotices.jobResult(new JobTracker.Job(new UUID(2, 2), "Fill", 1, 10, Phase.APPLY, null), false)
                .isEmpty(), "a running job has no result");
    }

    @Test
    void undoAndRedoRefusedWhileAnEditRunsSayToWait() {
        Notice undo = SessionNotices.rejection(RejectReason.QUEUE_FULL, SessionNotices.Subject.UNDO, LIMITS);
        Notice redo = SessionNotices.rejection(RejectReason.QUEUE_FULL, SessionNotices.Subject.REDO, LIMITS);
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.reject.queue_full.undo"), undo);
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.reject.queue_full.redo"), redo);
        assertEquals("Wait for the running edit to finish, then undo again", lang.get(undo.key()).getAsString());
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.reject.queue_full"),
                SessionNotices.rejection(RejectReason.QUEUE_FULL, SessionNotices.Subject.EDIT, LIMITS));
    }

    @Test
    void acceptedHistoryStepsNameTheirEntry() {
        assertEquals(Notice.of(Notice.Level.INFO, "sculptory.notice.undo", "Fill"), SessionNotices.historyStep(true, "Fill"));
        assertEquals(Notice.of(Notice.Level.INFO, "sculptory.notice.redo", "Fill"), SessionNotices.historyStep(false, "Fill"));
        assertTranslatable(SessionNotices.historyStep(true, "Fill"));
        assertTranslatable(SessionNotices.historyStep(false, "Fill"));
    }

    @Test
    void otherSessionNoticesAreTranslated() {
        assertTranslatable(Notice.of(Notice.Level.WARNING, "sculptory.notice.history_jump_stalled"));
        assertTranslatable(Notice.of(Notice.Level.ERROR, "sculptory.notice.no_block_states"));
        // FabricEditorSession passes the server's and the client's protocol ranges; EditorMode passes nothing.
        assertTranslatable(Notice.of(Notice.Level.ERROR, "sculptory.notice.incompatible", "3", "2-2"));
        // With the server's build: the server's build and protocol, then the client's.
        assertTranslatable(Notice.of(Notice.Level.ERROR, "sculptory.notice.incompatible_build", "0.3.0+ab", "5", "0.2.0",
                "4"));
        // Same protocol, another build: the server's build, then the client's.
        assertTranslatable(Notice.of(Notice.Level.WARNING, "sculptory.notice.different_build", "0.2.0+ab", "0.2.0+cd"));
        assertTranslatable(Notice.of(Notice.Level.WARNING, "sculptory.notice.editor_incompatible"));
        // Sent by the server (ServerDispatcher).
        assertTranslatable(Notice.of(Notice.Level.WARNING, "sculptory.notice.resync_clamped", "64", "80"));
        for (String key : List.of(ServerDispatcher.RESYNC_NO_PERMISSION, ServerDispatcher.RESYNC_NO_STROKE,
                ServerDispatcher.RESYNC_OUT_OF_RANGE)) {
            assertTranslatable(Notice.of(Notice.Level.WARNING, key));
        }
        assertTranslatable(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_state", "mod:block"));
        assertTranslatable(Notice.of(Notice.Level.WARNING, "sculptory.notice.history_evicted", "3"));
        assertTranslatable(Notice.of(Notice.Level.WARNING, "sculptory.notice.history_too_large", "3"));
        assertFalse(lang.has("sculptory.notice.dab_rejected"), "stroke refusals are toasted per reason instead");
        // Sent by the server (SculptoryMod's EditEvents) and by the brush tool.
        assertTranslatable(Notice.of(Notice.Level.WARNING,
                dev.sculptory.fabric.engine.impl.EditEvents.SYMMETRY_NO_GROUND, "1", "64"));
        assertTranslatable(Notice.of(Notice.Level.WARNING,
                dev.sculptory.fabric.client.editor.tools.brush.TerrainBrushTool.SPEC_TOO_LARGE, "32000"));
        assertFalse(lang.has("sculptory.palette.truncated"), "Palette Paint holds a whole palette");
    }

    @Test
    void everyM2ServerNoticeReadsAsEnglish() {
        // Keys and argument counts as ServerDispatcher and ServerClipboards send them.
        assertTranslatable(Notice.of(Notice.Level.WARNING, ServerDispatcher.NOTICE_REQUEST_REFUSED, "INVALID", "detail"));
        assertTranslatable(Notice.of(Notice.Level.WARNING, ServerDispatcher.NOTICE_PREVIEW_REFUSED, "INVALID", "detail"));
        assertTranslatable(Notice.of(Notice.Level.INFO, ServerDispatcher.NOTICE_LIBRARY_TRUNCATED, "trees", "512"));
        assertTranslatable(Notice.of(Notice.Level.WARNING, "sculptory.notice.import_unknown_states", "12", "2",
                "mod:a, mod:b"));
        assertTranslatable(Notice.of(Notice.Level.WARNING, "sculptory.notice.import_block_entities_skipped", "3"));
        assertTranslatable(Notice.of(Notice.Level.INFO, "sculptory.notice.import_entities_skipped", "3"));
        assertTranslatable(Notice.of(Notice.Level.INFO, "sculptory.notice.import_biomes_skipped"));
        assertTranslatable(Notice.of(Notice.Level.WARNING, "sculptory.notice.import_newer_version", "4100"));
        assertTranslatable(Notice.of(Notice.Level.WARNING,
                dev.sculptory.fabric.engine.impl.ServerClipboards.NOTICE_IMPORT_OPERATOR_NBT, "2", "1"));
        assertTranslatable(Notice.of(Notice.Level.WARNING,
                dev.sculptory.fabric.engine.impl.ServerClipboards.NOTICE_EXPORT_OPERATOR_NBT, "2", "1"));
    }

    @Test
    void refusalsAreRewordedWithTheirDetailForEveryReason() {
        for (RejectReason reason : RejectReason.values()) {
            Notice reworded = ClipboardTransfers.detailNotice(reason, "some detail");
            assertEquals(Notice.of(Notice.Level.WARNING, SessionNotices.reasonKey(reason) + ".detail", "some detail"),
                    reworded);
            assertTranslatable(reworded);
        }
        for (Reply.Failure failure : Reply.Failure.values()) {
            assertTranslatable(Notice.of(Notice.Level.WARNING, failure.noticeKey(), "Upload"));
        }
        assertTranslatable(Notice.of(Notice.Level.WARNING, ClipboardTransfers.UPLOAD_ERROR, "x.schem", "error"));
    }

    @Test
    void theClientsOwnClipboardToastsReadAsEnglish() {
        assertTranslatable(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.copied", "12,345", "Ctrl+V"));
        assertTranslatable(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.cut", "12,345", "Ctrl+V"));
        assertTranslatable(Notice.of(Notice.Level.INFO, "sculptory.notice.nothing_copied", "Ctrl+C"));
        assertTranslatable(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.loaded", "trees/oak.schem", "27", "Ctrl+V"));
        assertTranslatable(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.saved", "trees/oak.schem"));
        assertTranslatable(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.exported", "C:/game/x.schem"));
        assertTranslatable(Notice.of(Notice.Level.ERROR, "sculptory.notice.export_failed", "disk full"));
        assertTranslatable(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.uploaded", "x.schem", "8"));
        assertTranslatable(Notice.of(Notice.Level.INFO, "sculptory.notice.upload_one", "x.schem"));
        assertTranslatable(Notice.of(Notice.Level.INFO, "sculptory.notice.not_schem"));
        assertTranslatable(Notice.of(Notice.Level.WARNING, "sculptory.notice.file_too_large", "x.schem", "16"));
        assertTranslatable(Notice.of(Notice.Level.WARNING, "sculptory.notice.file_unreadable", "x.schem", "why"));
        assertTranslatable(Notice.of(Notice.Level.WARNING, "sculptory.notice.bad_library_path", "why"));
        assertTranslatable(Notice.of(Notice.Level.INFO, "sculptory.notice.clipboard_cleared"));
        assertTranslatable(Notice.of(Notice.Level.INFO, "sculptory.notice.asset_not_indexed", "x.schem"));
        assertTranslatable(Notice.of(Notice.Level.INFO, "sculptory.notice.transform_needs_place", "Ctrl+V"));
        assertTranslatable(Notice.of(Notice.Level.INFO, "sculptory.notice.clipboard_changed", "Ctrl+V"));
        for (String key : List.of("stack_no_turn", "move_first", "stack_first", "preview_loading", "place_aim_first",
                "asset_reloading")) {
            assertTranslatable(Notice.of(Notice.Level.INFO, "sculptory.notice." + key));
        }
    }
}
