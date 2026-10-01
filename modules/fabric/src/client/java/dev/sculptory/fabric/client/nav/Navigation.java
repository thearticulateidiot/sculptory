package dev.sculptory.fabric.client.nav;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.region.Facing;
import dev.sculptory.fabric.client.editor.CursorPick;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.protocol.v2.NavigateMode;
import dev.sculptory.protocol.v2.S2C;
import java.util.Objects;
import java.util.Optional;

/**
 * Jump and Through on the client: {@code J} asks the server to stand
 * the player on the block looked at, {@code Shift+J} to take them through the wall looked at. The client only names the
 * block, the face and the look direction; the server decides the spot and moves the player ({@code NavigateService}),
 * and explains its refusals with notices of its own ({@code sculptory.navigate.*}). What is decided here: nothing
 * looked at, no Sculptory server, a server without Jump. Free of Minecraft types; {@link JumpKey} is the play key,
 * {@code EditorController.jump} the editor's.
 */
public final class Navigation {
    public static final String NOTICE_PREFIX = "sculptory.navigate.";
    public static final String NOTHING_LOOKED_AT = NOTICE_PREFIX + "nothing_looked_at";
    public static final String NOT_CONNECTED = NOTICE_PREFIX + "not_connected";
    public static final String NOT_OFFERED = NOTICE_PREFIX + "not_offered";
    public static final String RATE_LIMITED = NOTICE_PREFIX + "rate_limited";
    /** A refusal the server did not explain: [reason]. */
    public static final String REFUSED = NOTICE_PREFIX + "refused";

    private Navigation() {}

    /** Sends a {@code Navigate}: returns why it was not sent (prefixed with the reason name), or {@code null}. */
    @FunctionalInterface
    public interface Sender {
        String send(NavigateMode mode, BlockPos hit, Facing side, float dx, float dy, float dz);
    }

    /**
     * Jump ({@code through} false) or Through at what the cursor (or crosshair) picked, along its ray: the notice for a
     * problem found here, or empty once the request is sent.
     *
     * @param sender the session's, or {@code null} without one
     */
    public static Optional<Notice> fromPick(Sender sender, CursorPick pick, boolean through) {
        Objects.requireNonNull(pick);
        WorldCursor cursor = pick.cursor();
        if (cursor.missed() || pick.ray().isEmpty()) return Optional.of(Notice.of(Notice.Level.INFO, NOTHING_LOOKED_AT));
        Ray ray = pick.ray().get();
        return request(sender, through, cursor.pos(), Facing.valueOf(cursor.face().name()), ray.dirX(), ray.dirY(), ray.dirZ());
    }

    /**
     * Jump or Through at block {@code hit}, looked at on {@code side} along (dx, dy, dz): the notice for a problem
     * found here, or empty once the request is sent.
     */
    public static Optional<Notice> request(Sender sender, boolean through, BlockPos hit, Facing side, double dx,
                                           double dy, double dz) {
        Objects.requireNonNull(hit);
        Objects.requireNonNull(side);
        if (sender == null) return Optional.of(Notice.of(Notice.Level.WARNING, NOT_CONNECTED));
        float fx = (float) dx, fy = (float) dy, fz = (float) dz;
        if (!Float.isFinite(fx) || !Float.isFinite(fy) || !Float.isFinite(fz) || (fx == 0 && fy == 0 && fz == 0)) {
            return Optional.of(Notice.of(Notice.Level.INFO, NOTHING_LOOKED_AT));
        }
        String problem = sender.send(through ? NavigateMode.THROUGH : NavigateMode.JUMP, hit, side, fx, fy, fz);
        if (problem == null) return Optional.empty();
        if (problem.startsWith("NOT_OFFERED")) return Optional.of(Notice.of(Notice.Level.WARNING, NOT_OFFERED));
        return Optional.of(Notice.of(Notice.Level.WARNING, NOT_CONNECTED));
    }

    /**
     * The toast of the server's answer: none for a landing (the view moving says it), none for the refusals the server
     * explains with a notice of its own (no permission, too far, unloaded, no spot), one for the rest.
     */
    public static Optional<Notice> result(S2C.NavigateResult result) {
        Objects.requireNonNull(result);
        if (result.reason() == null) return Optional.empty();
        return switch (result.reason()) {
            case NO_PERMISSION, TOO_LARGE, UNLOADED, INVALID -> Optional.empty();
            case RATE_LIMITED -> Optional.of(Notice.of(Notice.Level.INFO, RATE_LIMITED));
            default -> Optional.of(Notice.of(Notice.Level.WARNING, REFUSED, result.reason().name()));
        };
    }
}
