package dev.sculptory.fabric.client.editor.devtools;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.RotationShowcase;
import dev.sculptory.core.generate.SparseUpload;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Resampling;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.net.ClientNet;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.FabricEditorSession;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.fabric.client.session.ToolResult;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/**
 * {@code /bsclient rotation compare [slopes]}: the free-rotation comparison, for a playtest world. It uploads the
 * comparison build ({@link RotationShowcase}) as the player's clipboard (replacing it; the chat line says so) and pastes
 * it, as one stamp (one undo step), in a grid starting two blocks south-east of the player: one row per compared angle
 * set (yaw 30°; yaw 45° with pitch 20°; roll 25°), and in each row the build unturned, then turned with Nearest, Exact
 * count and Smooth. With {@code slopes} the turned copies also get stairs and slabs on their slopes. Every copy goes
 * through the server like any paste (permissions, limits, history).
 */
public final class RotationCompare {
    /** The ways the columns after the unturned build show, in {@link RotationShowcase#WAYS}'s order. */
    static final List<Resampling> WAYS = List.of(Resampling.NEAREST, Resampling.EXACT, Resampling.SMOOTH);

    private final Supplier<StateSpace> states;

    private RotationCompare(Supplier<StateSpace> states) {
        this.states = states;
    }

    /** Registers the command. Call once from the client initializer. */
    public static void register(Supplier<StateSpace> states) {
        RotationCompare command = new RotationCompare(states);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(command.command()));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> command() {
        return literal("bsclient").then(literal("rotation").then(literal("compare")
                .executes(context -> compare(context.getSource(), false))
                .then(literal("slopes").executes(context -> compare(context.getSource(), true)))));
    }

    private int compare(FabricClientCommandSource source, boolean slopes) {
        FabricEditorSession session = ClientNet.session();
        StateSpace space;
        try {
            space = states.get();
        } catch (RuntimeException notJoined) {
            space = null;
        }
        if (session == null || space == null) {
            source.sendError(Component.literal("Sculptory is not connected to this server"));
            return 0;
        }
        try {
            if (!session.permissions().has(Perm.CLIPBOARD) || !session.permissions().has(Perm.REGION)) {
                source.sendError(Component.literal("The comparison needs the clipboard and region permissions"));
                return 0;
            }
        } catch (IllegalStateException noSession) {
            source.sendError(Component.literal("Sculptory is not connected to this server"));
            return 0;
        }
        GeneratedSource build;
        try {
            build = showcase(space, playerCorner(source));
        } catch (IllegalArgumentException unknown) {
            source.sendError(Component.literal("The comparison build has a block this game does not know: "
                    + unknown.getMessage()));
            return 0;
        }
        Box bounds = build.bounds();
        byte[] bytes = SparseUpload.encode(build, space);
        source.sendFeedback(Component.literal("Rotation comparison: uploading the test build (it replaces your clipboard)"));
        session.uploadGenerated(bounds, build.cells(), bytes).result().thenAccept(reply -> {
            if (!(reply instanceof Reply.Ok<ClipboardCache.Entry> ok)) return; // the session toasts a refusal
            OpSpec.PasteMany stamp = stamp(ok.value(), bounds.min(), slopes);
            session.send(new ToolAction.RunOp(stamp, false)).thenAccept(result -> {
                if (result instanceof ToolResult.Rejected rejected) {
                    source.sendError(Component.literal("Rotation comparison refused: " + rejected.reason()));
                } else if (result instanceof ToolResult.Accepted) {
                    source.sendFeedback(Component.literal("Rotation comparison: " + stamp.placements().size()
                            + " copies from " + bounds.min().x() + " " + bounds.min().y() + " " + bounds.min().z()
                            + ". Columns going east: unturned, " + String.join(", ", RotationShowcase.WAYS)
                            + (slopes ? " (with smooth slopes)" : "") + ". Rows going south: yaw 30, yaw 45 + pitch 20,"
                            + " roll 25. One undo step."));
                }
            });
        });
        return 1;
    }

    /** Two blocks south-east of the player's feet. */
    private static BlockPos playerCorner(FabricClientCommandSource source) {
        net.minecraft.core.BlockPos feet = source.getPlayer().blockPosition();
        return new BlockPos(feet.getX() + 2, feet.getY(), feet.getZ() + 2);
    }

    /** The comparison build with its minimum corner at {@code at}, the first grid cell's place. */
    static GeneratedSource showcase(StateSpace states, BlockPos at) {
        GeneratedSource.Builder builder = GeneratedSource.builder(Long.MAX_VALUE);
        for (RotationShowcase.Cell cell : RotationShowcase.cells()) {
            int state = states.parse(cell.state());
            if (state < 0) throw new IllegalArgumentException(cell.state());
            builder.set(at.x() + cell.x(), at.y() + cell.y(), at.z() + cell.z(), state);
        }
        return builder.build();
    }

    /**
     * The stamp: for each grid cell, the build turned that cell's way, placed so its centre stays at the centre of the
     * cell's own copy of the build (the unturned one at {@code corner}, offset by {@link RotationShowcase#placement}).
     */
    static OpSpec.PasteMany stamp(ClipboardCache.Entry clipboard, BlockPos corner, boolean slopes) {
        BlockPos dims = clipboard.dims();
        BlockPos anchor = clipboard.anchor();
        List<OpSpec.PasteMany.Placement> placements = new ArrayList<>();
        for (int row = 0; row < RotationShowcase.ANGLES.size(); row++) {
            int[] a = RotationShowcase.ANGLES.get(row);
            for (int column = 0; column <= RotationShowcase.WAYS.size(); column++) {
                Orientation o = column == 0 ? Orientation.IDENTITY
                        : Orientation.free(Transform.IDENTITY, a[0], a[1], a[2], WAYS.get(column - 1), slopes);
                BlockPos cell = corner.add(RotationShowcase.placement(column, row));
                placements.add(new OpSpec.PasteMany.Placement(origin(o, dims, anchor, cell), o));
            }
        }
        return new OpSpec.PasteMany(new SourceRef.Clipboard(clipboard.clipboardId()), placements, PasteOptions.DEFAULT,
                Symmetry.NONE);
    }

    /**
     * The paste origin that puts the build's centre, turned by {@code o}, where it is when the build stands unturned with
     * its minimum corner at {@code cell}: the anchor lands on the origin, and the centre lands {@code mapPoint(centre) -
     * mapCell(anchor)} from it.
     */
    static BlockPos origin(Orientation o, BlockPos dims, BlockPos anchor, BlockPos cell) {
        double[] centre = o.mapPoint(dims.x() / 2.0, dims.y() / 2.0, dims.z() / 2.0, dims.x(), dims.y(), dims.z());
        BlockPos landed = o.mapCell(anchor.x(), anchor.y(), anchor.z(), dims.x(), dims.y(), dims.z());
        return new BlockPos(
                (int) Math.round(cell.x() + dims.x() / 2.0 - centre[0]) + landed.x(),
                (int) Math.round(cell.y() + dims.y() / 2.0 - centre[1]) + landed.y(),
                (int) Math.round(cell.z() + dims.z() / 2.0 - centre[2]) + landed.z());
    }
}
