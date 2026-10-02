package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.EditorMode.Observation;
import dev.sculptory.fabric.client.editor.EditorMode.ScreenState;
import dev.sculptory.fabric.client.editor.EditorMode.SuspendTarget;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.SessionState;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EditorModeTest {
    private static final Object OVERWORLD = "overworld";
    private static final Object NETHER = "nether";

    /** Records every Minecraft-side effect the state machine asks for. */
    private static final class FakeHost implements EditorMode.Host {
        final List<String> calls = new ArrayList<>();
        final List<String> toasts = new ArrayList<>();
        MockEditorSession session = new MockEditorSession();
        boolean hasSession = true;
        Object world = OVERWORLD;
        boolean editorScreenShown;

        @Override
        public Optional<EditorSession> session() {
            return hasSession ? Optional.of(session) : Optional.empty();
        }

        @Override
        public Object currentWorld() {
            return world;
        }

        @Override
        public void showEditorScreen() {
            editorScreenShown = true;
            calls.add("show");
        }

        @Override
        public void hideEditorScreen() {
            editorScreenShown = false;
            calls.add("hide");
        }

        @Override
        public void openVanillaScreen(SuspendTarget target) {
            editorScreenShown = false;
            calls.add("open " + target);
        }

        @Override
        public void setEditorVisuals(boolean editing) {
            calls.add("visuals " + editing);
        }

        @Override
        public void activated() {
            calls.add("activated");
        }

        @Override
        public void deactivated(DeactivateReason reason) {
            calls.add("deactivated " + reason);
        }

        @Override
        public void toast(Notice notice) {
            toasts.add(notice.key());
        }
    }

    private final FakeHost host = new FakeHost();
    private final EditorMode mode = new EditorMode(host);

    private static Observation screen(ScreenState state) {
        return new Observation(true, false, OVERWORLD, state);
    }

    private void enter() {
        assertTrue(mode.enter());
        host.calls.clear();
        host.toasts.clear();
    }

    private static Permissions without(Perm missing) {
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        granted.remove(missing);
        return new Permissions(Perm.mask(granted), Limits.DEFAULTS);
    }

    // ---- Entering ----

    @Test
    void entersWhenTheSessionIsReadyAndPermitted() {
        assertTrue(mode.enter());
        assertEquals(EditorState.ACTIVE, mode.state());
        assertEquals(List.of("visuals true", "show", "activated"), host.calls);
        assertTrue(host.toasts.isEmpty());
    }

    @Test
    void refusesWithoutASessionInPlainWords() {
        host.hasSession = false;
        assertFalse(mode.enter());
        assertEquals(EditorState.INACTIVE, mode.state());
        assertEquals(List.of("sculptory.notice.no_server_support"), host.toasts);
        assertTrue(host.calls.isEmpty(), "nothing changes on screen when entry is refused");
    }

    @Test
    void refusesWithoutTheEditPermission() {
        host.session.setPermissions(without(Perm.USE));
        assertFalse(mode.enter());
        assertEquals(List.of("sculptory.notice.no_permission"), host.toasts);
        assertEquals(EditorState.INACTIVE, mode.state());
    }

    @Test
    void refusalExplainsEachSessionState() {
        host.session.setState(SessionState.HANDSHAKING);
        mode.enter();
        host.session.setState(SessionState.INCOMPATIBLE);
        mode.enter();
        host.session.setState(SessionState.NO_SERVER_SUPPORT);
        mode.enter();
        host.session.setState(SessionState.DISCONNECTED);
        mode.enter();
        assertEquals(List.of("sculptory.notice.handshaking", "sculptory.notice.editor_incompatible",
                "sculptory.notice.no_server_support", "sculptory.notice.no_server_support"), host.toasts);
        assertEquals(EditorState.INACTIVE, mode.state());
    }

    // ---- Leaving ----

    @Test
    void toggleEntersAndLeavesWithoutAToast() {
        mode.toggle();
        assertEquals(EditorState.ACTIVE, mode.state());
        host.calls.clear();
        mode.toggle();
        assertEquals(EditorState.INACTIVE, mode.state());
        assertEquals(List.of("deactivated EDITOR_CLOSED", "visuals false", "hide"), host.calls);
        assertTrue(host.toasts.isEmpty());
    }

    @Test
    void escapeLeavesWithTheReopenHint() {
        enter();
        mode.escape();
        assertEquals(EditorState.INACTIVE, mode.state());
        assertEquals(List.of("sculptory.notice.editor_closed"), host.toasts);
        assertTrue(host.calls.contains("deactivated EDITOR_CLOSED"));
    }

    @Test
    void exitWhenInactiveDoesNothing() {
        mode.exit(ExitReason.ESCAPE);
        mode.onDisconnect();
        mode.tick(screen(ScreenState.NONE));
        assertTrue(host.calls.isEmpty());
        assertTrue(host.toasts.isEmpty());
    }

    // ---- Suspending ----

    @Test
    void suspendOpensTheVanillaScreenNextTickAndResumesWhenItCloses() {
        enter();
        mode.suspend(SuspendTarget.CHAT);
        assertEquals(EditorState.SUSPENDED, mode.state());
        assertEquals(List.of("deactivated SUSPENDED"), host.calls, "the chat screen opens only at the next tick");

        mode.tick(screen(ScreenState.EDITOR));
        assertEquals(List.of("deactivated SUSPENDED", "open CHAT"), host.calls);

        mode.tick(screen(ScreenState.OTHER));
        assertEquals(EditorState.SUSPENDED, mode.state(), "stays suspended while chat is open");

        mode.tick(screen(ScreenState.NONE));
        assertEquals(EditorState.ACTIVE, mode.state());
        assertEquals(List.of("deactivated SUSPENDED", "open CHAT", "show", "activated"), host.calls);
        assertTrue(host.toasts.isEmpty());
    }

    @Test
    void eachSuspendTargetOpensItsScreen() {
        for (SuspendTarget target : SuspendTarget.values()) {
            enter();
            mode.suspend(target);
            mode.tick(screen(ScreenState.EDITOR));
            assertTrue(host.calls.contains("open " + target));
            mode.exit(ExitReason.TOGGLED);
        }
    }

    @Test
    void toggleIsIgnoredWhileSuspended() {
        enter();
        mode.suspend(SuspendTarget.INVENTORY);
        mode.toggle();
        assertEquals(EditorState.SUSPENDED, mode.state(), "B typed into chat must not close the editor");
    }

    @Test
    void suspendingTwiceOrWhileInactiveIsIgnored() {
        mode.suspend(SuspendTarget.CHAT);
        assertEquals(EditorState.INACTIVE, mode.state());
        enter();
        mode.suspend(SuspendTarget.CHAT);
        mode.suspend(SuspendTarget.COMMAND);
        mode.tick(screen(ScreenState.EDITOR));
        assertEquals(List.of("deactivated SUSPENDED", "open CHAT"), host.calls);
    }

    @Test
    void disconnectWhileSuspendedLeavesWithoutDeactivatingTwice() {
        enter();
        mode.suspend(SuspendTarget.CHAT);
        mode.tick(screen(ScreenState.EDITOR));
        host.calls.clear();
        mode.tick(new Observation(false, false, null, ScreenState.OTHER));
        assertEquals(EditorState.INACTIVE, mode.state());
        assertEquals(List.of("visuals false", "hide"), host.calls);
    }

    // ---- Automatic exits ----

    @Test
    void aForeignScreenClosesTheEditor() {
        enter();
        mode.tick(screen(ScreenState.OTHER));
        assertEquals(EditorState.INACTIVE, mode.state());
        assertEquals(List.of("deactivated EDITOR_CLOSED", "visuals false", "hide"), host.calls);
        assertEquals(List.of("sculptory.notice.editor_closed"), host.toasts);
    }

    @Test
    void theEditorScreenClosedBySomethingElseEndsEditing() {
        enter();
        mode.tick(screen(ScreenState.NONE));
        assertEquals(EditorState.INACTIVE, mode.state());
    }

    @Test
    void stayingOnTheEditorScreenKeepsEditing() {
        enter();
        mode.tick(screen(ScreenState.EDITOR));
        mode.tick(screen(ScreenState.EDITOR));
        assertEquals(EditorState.ACTIVE, mode.state());
        assertTrue(host.calls.isEmpty());
    }

    @Test
    void revokedPermissionClosesTheEditor() {
        enter();
        host.session.setPermissions(without(Perm.USE));
        mode.tick(screen(ScreenState.EDITOR));
        assertEquals(EditorState.INACTIVE, mode.state());
        assertTrue(host.calls.contains("deactivated PERMISSION_LOST"));
        assertEquals(List.of("sculptory.notice.editor_closed_permission"), host.toasts);
    }

    @Test
    void losingTheSessionClosesTheEditor() {
        enter();
        host.session.setState(SessionState.HANDSHAKING);
        mode.tick(screen(ScreenState.EDITOR));
        assertEquals(EditorState.INACTIVE, mode.state());
        assertTrue(host.calls.contains("deactivated WORLD_CHANGED"));
        assertEquals(List.of("sculptory.notice.editor_closed_session"), host.toasts);
    }

    @Test
    void aVanishedSessionClosesTheEditor() {
        enter();
        host.hasSession = false;
        mode.tick(screen(ScreenState.EDITOR));
        assertEquals(EditorState.INACTIVE, mode.state());
        assertEquals(List.of("sculptory.notice.editor_closed_session"), host.toasts);
    }

    @Test
    void deathClosesTheEditorQuietly() {
        enter();
        mode.tick(new Observation(true, true, OVERWORLD, ScreenState.OTHER));
        assertEquals(EditorState.INACTIVE, mode.state());
        assertTrue(host.calls.contains("deactivated WORLD_CHANGED"));
        assertTrue(host.toasts.isEmpty(), "the death screen says enough");
    }

    @Test
    void changingDimensionClosesTheEditor() {
        enter();
        mode.tick(new Observation(true, false, NETHER, ScreenState.EDITOR));
        assertEquals(EditorState.INACTIVE, mode.state());
        assertTrue(host.calls.contains("deactivated WORLD_CHANGED"));
        assertEquals(List.of("sculptory.notice.editor_closed_world"), host.toasts);
    }

    @Test
    void disconnectClosesTheEditorAtOnce() {
        enter();
        mode.onDisconnect();
        assertEquals(EditorState.INACTIVE, mode.state());
        assertEquals(List.of("deactivated WORLD_CHANGED", "visuals false", "hide"), host.calls);
        assertTrue(host.toasts.isEmpty());
    }

    @Test
    void reenteringAfterAnExitWorks() {
        enter();
        mode.escape();
        assertTrue(mode.enter());
        assertEquals(EditorState.ACTIVE, mode.state());
        host.world = NETHER;
        mode.exit(ExitReason.TOGGLED);
        assertTrue(mode.enter(), "the new world becomes the entry world");
        mode.tick(new Observation(true, false, NETHER, ScreenState.EDITOR));
        assertEquals(EditorState.ACTIVE, mode.state());
    }
}
