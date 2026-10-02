package dev.sculptory.fabric.client.editor;

import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.SessionState;
import dev.sculptory.server.engine.Perm;
import java.util.Objects;
import java.util.Optional;

/**
 * The editor-mode state machine: entering (with refusal), leaving, suspending for vanilla chat,
 * command and inventory screens, and leaving automatically on disconnect, death, dimension change,
 * lost permission, a lost session or a foreign screen. Pure Java: every Minecraft effect goes
 * through {@link Host}, so each transition is unit-tested.
 *
 * <p>Call {@link #tick} once per client tick with what the client looks like; the automatic exits
 * and the resume after a suspension happen there. Opening the vanilla screen of a suspension is
 * deferred to that tick too, so the key's character event (the 't' of T) is not typed into chat.
 */
public final class EditorMode {
    /**
     * The vanilla screen a suspension opens: chat, a command, the inventory, or Minecraft's "open this link?" screen for
     * a website linked from the wiki.
     */
    public enum SuspendTarget { CHAT, COMMAND, INVENTORY, LINK }

    /** Which screen is showing. */
    public enum ScreenState { NONE, EDITOR, OTHER }

    /**
     * What the client looks like at the end of a tick.
     *
     * @param world identity of the client world (compared with {@code equals}); a new value means a
     *     dimension change or a world reload
     */
    public record Observation(boolean inWorld, boolean playerDead, Object world, ScreenState screen) {
        public Observation {
            Objects.requireNonNull(screen);
        }
    }

    /** Minecraft-side effects of the state machine. */
    public interface Host {
        /** The Sculptory session for the current connection, if any. */
        Optional<EditorSession> session();

        /** Identity of the current client world; see {@link Observation#world()}. */
        Object currentWorld();

        void showEditorScreen();

        /** Closes the editor screen if it is the current screen; leaves any other screen alone. */
        void hideEditorScreen();

        void openVanillaScreen(SuspendTarget target);

        /** Hides (true) or restores (false) the hotbar, status bars, crosshair, hand and block outline. */
        void setEditorVisuals(boolean editing);

        /** The editor became usable: activate the tool, apply the fly speed. */
        void activated();

        /** The editor stopped being usable: deactivate the tool, end any mouse look. */
        void deactivated(DeactivateReason reason);

        void toast(Notice notice);
    }

    private final Host host;
    private EditorState state = EditorState.INACTIVE;
    private Object worldAtEntry;
    private SuspendTarget pendingSuspend;

    public EditorMode(Host host) {
        this.host = Objects.requireNonNull(host);
    }

    public EditorState state() {
        return state;
    }

    public boolean isActive() {
        return state == EditorState.ACTIVE;
    }

    /** True while the editor is on, including while a vanilla screen suspends it. */
    public boolean isEditing() {
        return state != EditorState.INACTIVE;
    }

    /**
     * Why entering would be refused right now, as a plain-language toast, or empty if the editor
     * may open.
     */
    public Optional<Notice> entryRefusal() {
        Optional<EditorSession> session = host.session();
        if (session.isEmpty()) {
            return Optional.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.no_server_support"));
        }
        return switch (session.get().state()) {
            case READY -> session.get().permissions().has(Perm.USE)
                    ? Optional.empty()
                    : Optional.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.no_permission"));
            case HANDSHAKING -> Optional.of(Notice.of(Notice.Level.INFO, "sculptory.notice.handshaking"));
            case INCOMPATIBLE -> Optional.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.editor_incompatible"));
            case NO_SERVER_SUPPORT, DISCONNECTED ->
                    Optional.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.no_server_support"));
        };
    }

    /** B: enters from gameplay, leaves from the editor; ignored while suspended (B types into chat). */
    public void toggle() {
        switch (state) {
            case INACTIVE -> enter();
            case ACTIVE -> exit(ExitReason.TOGGLED);
            case SUSPENDED -> {
            }
        }
    }

    /** Enters editor mode, or shows why it can't. Returns true if the editor is now active. */
    public boolean enter() {
        if (state != EditorState.INACTIVE) {
            return state == EditorState.ACTIVE;
        }
        Optional<Notice> refusal = entryRefusal();
        if (refusal.isPresent()) {
            host.toast(refusal.get());
            return false;
        }
        state = EditorState.ACTIVE;
        worldAtEntry = host.currentWorld();
        pendingSuspend = null;
        host.setEditorVisuals(true);
        host.showEditorScreen();
        host.activated();
        return true;
    }

    /** The last rung of the Esc ladder. */
    public void escape() {
        exit(ExitReason.ESCAPE);
    }

    /** Leaves editor mode. Does nothing if it is already off. */
    public void exit(ExitReason reason) {
        Objects.requireNonNull(reason);
        if (state == EditorState.INACTIVE) {
            return;
        }
        EditorState previous = state;
        state = EditorState.INACTIVE;
        pendingSuspend = null;
        worldAtEntry = null;
        if (previous == EditorState.ACTIVE) {
            host.deactivated(reason.deactivateReason());
        }
        host.setEditorVisuals(false);
        host.hideEditorScreen();
        Notice notice = reason.notice();
        if (notice != null) {
            host.toast(notice);
        }
    }

    /**
     * T, / or E in the editor: the tool is deactivated now and the vanilla screen opens at the next
     * {@link #tick}. The editor comes back once that screen closes.
     */
    public void suspend(SuspendTarget target) {
        Objects.requireNonNull(target);
        if (state != EditorState.ACTIVE) {
            return;
        }
        state = EditorState.SUSPENDED;
        pendingSuspend = target;
        host.deactivated(DeactivateReason.SUSPENDED);
    }

    /** The connection closed: leave at once, without waiting for a tick. */
    public void onDisconnect() {
        exit(ExitReason.DISCONNECTED);
    }

    /** Runs the automatic exits and the resume after a suspension. */
    public void tick(Observation now) {
        Objects.requireNonNull(now);
        if (state == EditorState.INACTIVE) {
            return;
        }
        ExitReason automatic = automaticExit(now);
        if (automatic != null) {
            exit(automatic);
            return;
        }
        if (state == EditorState.SUSPENDED) {
            if (pendingSuspend != null) {
                SuspendTarget target = pendingSuspend;
                pendingSuspend = null;
                host.openVanillaScreen(target);
            } else if (now.screen() != ScreenState.OTHER) {
                state = EditorState.ACTIVE;
                if (now.screen() == ScreenState.NONE) {
                    host.showEditorScreen();
                }
                host.activated();
            }
            return;
        }
        if (now.screen() != ScreenState.EDITOR) {
            exit(ExitReason.FOREIGN_SCREEN);
        }
    }

    private ExitReason automaticExit(Observation now) {
        if (!now.inWorld()) {
            return ExitReason.DISCONNECTED;
        }
        if (now.playerDead()) {
            return ExitReason.DIED;
        }
        if (!Objects.equals(now.world(), worldAtEntry)) {
            return ExitReason.WORLD_CHANGED;
        }
        Optional<EditorSession> session = host.session();
        if (session.isEmpty() || session.get().state() != SessionState.READY) {
            return ExitReason.SESSION_LOST;
        }
        if (!session.get().permissions().has(Perm.USE)) {
            return ExitReason.PERMISSION_LOST;
        }
        return null;
    }
}
