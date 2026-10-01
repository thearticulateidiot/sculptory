package dev.sculptory.fabric.client.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.region.Facing;
import dev.sculptory.fabric.client.editor.CursorPick;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.engine.impl.NavigateService;
import dev.sculptory.protocol.v2.NavigateMode;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Jump and Through on the client: what is sent, what is refused here, and which answers are toasted. */
class NavigationTest {
    /** A request as sent. */
    private record Sent(NavigateMode mode, BlockPos hit, Facing side, float dx, float dy, float dz) {}

    private final List<Sent> sent = new ArrayList<>();
    private String answer;
    private final Navigation.Sender sender = (mode, hit, side, dx, dy, dz) -> {
        sent.add(new Sent(mode, hit, side, dx, dy, dz));
        return answer;
    };

    private static CursorPick hit(int x, int y, int z, WorldCursor.Face face, double dx, double dy, double dz) {
        return new CursorPick(Optional.of(new Ray(0.5, 70.5, 0.5, dx, dy, dz)),
                new WorldCursor(new BlockPos(x, y, z), face, x + 0.5, y + 1, z + 0.5, false));
    }

    @Test
    void jumpAndThroughSendTheBlockTheFaceAndTheLookDirection() {
        assertEquals(Optional.empty(), Navigation.fromPick(sender, hit(3, 64, 4, WorldCursor.Face.UP, 0, -1, 0), false));
        assertEquals(Optional.empty(), Navigation.fromPick(sender, hit(9, 70, 0, WorldCursor.Face.WEST, 1, 0, 0), true));
        assertEquals(List.of(new Sent(NavigateMode.JUMP, new BlockPos(3, 64, 4), Facing.UP, 0f, -1f, 0f),
                new Sent(NavigateMode.THROUGH, new BlockPos(9, 70, 0), Facing.WEST, 1f, 0f, 0f)), sent);
        // The ray's direction is sent as it is (a unit vector).
        Navigation.fromPick(sender, hit(1, 1, 1, WorldCursor.Face.NORTH, 3, 0, 4), false);
        Sent last = sent.get(sent.size() - 1);
        assertEquals(0.6f, last.dx(), 1e-6);
        assertEquals(0.8f, last.dz(), 1e-6);
    }

    @Test
    void problemsFoundHereAreToastedAndNothingIsSent() {
        CursorPick miss = new CursorPick(Optional.of(new Ray(0, 70, 0, 1, 0, 0)), WorldCursor.miss(100, 70, 0));
        assertEquals(Navigation.NOTHING_LOOKED_AT, Navigation.fromPick(sender, miss, false).orElseThrow().key());
        assertEquals(Navigation.NOTHING_LOOKED_AT, Navigation.fromPick(sender, CursorPick.NONE, true).orElseThrow().key());
        assertEquals(Navigation.NOT_CONNECTED,
                Navigation.fromPick(null, hit(0, 64, 0, WorldCursor.Face.UP, 0, -1, 0), false).orElseThrow().key());
        assertEquals(Navigation.NOTHING_LOOKED_AT, Navigation.request(sender, false, BlockPos.ORIGIN, Facing.UP, 0, 0, 0)
                .orElseThrow().key(), "no direction");
        assertEquals(Navigation.NOTHING_LOOKED_AT, Navigation.request(sender, false, BlockPos.ORIGIN, Facing.UP, Double.NaN,
                0, 0).orElseThrow().key());
        assertTrue(sent.isEmpty());
        answer = "NOT_OFFERED: the server has no Jump";
        assertEquals(Navigation.NOT_OFFERED,
                Navigation.fromPick(sender, hit(0, 64, 0, WorldCursor.Face.UP, 0, -1, 0), false).orElseThrow().key());
        answer = "NOT_READY: no Sculptory session";
        assertEquals(Navigation.NOT_CONNECTED,
                Navigation.fromPick(sender, hit(0, 64, 0, WorldCursor.Face.UP, 0, -1, 0), false).orElseThrow().key());
    }

    @Test
    void theServerExplainsItsOwnRefusalsAndTheRestAreToasted() {
        assertEquals(Optional.empty(), Navigation.result(S2C.NavigateResult.landed(1, new BlockPos(0, 64, 0))));
        for (RejectReason explained : List.of(RejectReason.NO_PERMISSION, RejectReason.TOO_LARGE, RejectReason.UNLOADED,
                RejectReason.INVALID)) {
            assertEquals(Optional.empty(), Navigation.result(S2C.NavigateResult.refused(2, explained)), explained.name());
        }
        assertEquals(Navigation.RATE_LIMITED,
                Navigation.result(S2C.NavigateResult.refused(3, RejectReason.RATE_LIMITED)).orElseThrow().key());
        Notice disabled = Navigation.result(S2C.NavigateResult.refused(4, RejectReason.DISABLED)).orElseThrow();
        assertEquals(Navigation.REFUSED, disabled.key());
        assertEquals(List.of("DISABLED"), disabled.args());
    }

    @Test
    void theEditorKeysAreJAndShiftJ() {
        assertEquals(List.of(KeyChord.parse("j")), KeyAction.JUMP.defaultChords());
        assertEquals(List.of(KeyChord.parse("shift+j")), KeyAction.JUMP_THROUGH.defaultChords());
        EditorKeymap keymap = EditorKeymap.defaults();
        assertEquals(Optional.of(KeyAction.JUMP), keymap.match(KeyChord.parse("j")));
        assertEquals(Optional.of(KeyAction.JUMP_THROUGH), keymap.match(KeyChord.parse("shift+j")));
    }

    @Test
    void theEnglishTextCoversNavigation() throws IOException {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in);
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (String key : List.of(JumpKey.KEY, "sculptory.key.jump", "sculptory.key.jump_through",
                Navigation.NOTHING_LOOKED_AT, Navigation.NOT_CONNECTED, Navigation.NOT_OFFERED, Navigation.RATE_LIMITED,
                Navigation.REFUSED, NavigateService.NEEDS_NODE, NavigateService.NEEDS_MODE, NavigateService.TOO_FAR,
                NavigateService.UNLOADED, NavigateService.NO_SPOT, NavigateService.NO_SPOT_THROUGH, NavigateService.OUTSIDE)) {
            assertTrue(lang.has(key), key);
        }
    }
}
