package dev.sculptory.fabric.engine;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityView;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.protocol.v2.RejectReason;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Tinker: changes one block or one entity in place, each change one step of the
 * player's history (exact, conflict-safe undo and redo, saved in the crash-safe journal). Called on the server thread
 * by the protocol dispatcher for the editor's Tinker tool, and meant to be called the same way for builder mode (tools
 * outside the editor). Every change needs {@code use} and {@code region}, is written without block updates, and
 * respects protection (spawn protection, claims, the world border). A refusal throws {@link EditRejected} and changes
 * nothing; "Apply to all like it in the selection" is not here but an ordinary region op, a {@code Fill} with the
 * property pattern ({@code Pattern.SetProperty}).
 */
public interface TinkerService {
    /**
     * Changes the block at {@code pos}, which the player saw as state {@code expected}, to {@code target} (the same block
     * with other property values; {@code expected} itself to change only the sign text), and, when {@code sign} is not
     * null, sets the text of its sign or hanging sign (the sides that differ from the live sign are replaced). When the
     * block is one half of a door or a two-block plant, a changed property other than {@code half} is changed on its
     * other half too, in the same step.
     *
     * @throws EditRejected {@code INVALID} when the block is no longer {@code expected} (nothing is written), the two
     *     states are of different blocks, the block has no sign for {@code sign}, or the position is outside the world;
     *     {@code UNLOADED}, {@code PROTECTED}, {@code AREA_BUSY} (a job holds its section), {@code NO_PERMISSION},
     *     {@code DISABLED}; {@code QUEUE_FULL} while the player's brush stroke is still being written
     */
    void block(ServerPlayerEntity player, BlockPos pos, int expected, int target, SignText sign) throws EditRejected;

    /**
     * Applies {@code edits} to the entity with UUID {@code id} in the player's world (an armor stand, item frame, glow
     * item frame, painting or block, item or text display) and returns what the panel shows of it afterwards; with no
     * edits, only returns that.
     *
     * @throws EditRejected {@code INVALID} when there is no such entity (removed meanwhile), Tinker does not change its
     *     kind, it rides another entity, an edit does not fit it (or names an unknown painting or item), or the edited
     *     entity cannot be placed (a painting that would not fit its wall); {@code PROTECTED} where it stands or would
     *     stand; {@code UNLOADED}, {@code NO_PERMISSION}, {@code DISABLED}, {@code QUEUE_FULL} as for blocks
     */
    EntityView entity(ServerPlayerEntity player, UUID id, List<EntityEdit> edits) throws EditRejected;

    /** Refuses everything with {@code DISABLED} (no engine running). */
    TinkerService DISABLED = new TinkerService() {
        @Override
        public void block(ServerPlayerEntity player, BlockPos pos, int expected, int target, SignText sign)
                throws EditRejected {
            throw new EditRejected(RejectReason.DISABLED);
        }

        @Override
        public EntityView entity(ServerPlayerEntity player, UUID id, List<EntityEdit> edits) throws EditRejected {
            throw new EditRejected(RejectReason.DISABLED);
        }
    };
}
