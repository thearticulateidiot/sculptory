package dev.sculptory.fabric.client.editor;

import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.session.Notice;

/** Why editor mode ended, which decides the tool deactivation reason and the toast (if any). */
public enum ExitReason {
    /** B pressed in the editor: the player knows how they got out, so no toast. */
    TOGGLED(DeactivateReason.EDITOR_CLOSED, null, null),
    /** The last rung of the Esc ladder. */
    ESCAPE(DeactivateReason.EDITOR_CLOSED, Notice.Level.INFO, "sculptory.notice.editor_closed"),
    /** The player left the world or server. */
    DISCONNECTED(DeactivateReason.WORLD_CHANGED, null, null),
    /** The death screen replaced the editor. */
    DIED(DeactivateReason.WORLD_CHANGED, null, null),
    /** The player changed dimension (or the client world was replaced). */
    WORLD_CHANGED(DeactivateReason.WORLD_CHANGED, Notice.Level.INFO, "sculptory.notice.editor_closed_world"),
    /** The server removed the edit permission. */
    PERMISSION_LOST(DeactivateReason.PERMISSION_LOST, Notice.Level.WARNING, "sculptory.notice.editor_closed_permission"),
    /** The Sculptory session stopped being ready (handshake lost, server stopped answering). */
    SESSION_LOST(DeactivateReason.WORLD_CHANGED, Notice.Level.WARNING, "sculptory.notice.editor_closed_session"),
    /** Another screen (a container, a sign, the pause menu) replaced the editor. */
    FOREIGN_SCREEN(DeactivateReason.EDITOR_CLOSED, Notice.Level.INFO, "sculptory.notice.editor_closed");

    private final DeactivateReason deactivateReason;
    private final Notice.Level level;
    private final String noticeKey;

    ExitReason(DeactivateReason deactivateReason, Notice.Level level, String noticeKey) {
        this.deactivateReason = deactivateReason;
        this.level = level;
        this.noticeKey = noticeKey;
    }

    public DeactivateReason deactivateReason() {
        return deactivateReason;
    }

    /** The toast to show after exiting, or {@code null} for none. */
    public Notice notice() {
        return noticeKey == null ? null : Notice.of(level, noticeKey);
    }
}
