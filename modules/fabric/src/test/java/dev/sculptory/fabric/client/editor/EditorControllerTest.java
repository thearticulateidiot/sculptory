package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class EditorControllerTest {
    private final EditorTestRig rig = new EditorTestRig();
    private final EditorController controller = rig.controller;
    private final EditorContext ctx = rig.ctx;

    private static Permissions without(Perm... missing) {
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        for (Perm perm : missing) {
            granted.remove(perm);
        }
        return new Permissions(Perm.mask(granted), Limits.DEFAULTS);
    }

    private ToolId active() {
        return ctx.tools().active().orElseThrow().descriptor().id();
    }

    @Test
    void enteringActivatesSelectFirst() {
        assertTrue(rig.mode.enter());
        assertEquals(ToolId.SELECT, active());
        assertEquals(1, rig.platform.flyMultiplier);
    }

    @Test
    void numberKeysSwitchTools() {
        rig.mode.enter();
        assertTrue(controller.selectSlot(2));
        assertEquals(ToolId.RAISE, active());
        controller.run(KeyAction.TOOL_1, 0);
        assertEquals(ToolId.SELECT, active());
    }

    @Test
    void placeAndScatterAreSlotsEightAndNine() {
        rig.mode.enter();
        assertTrue(controller.selectSlot(9), "Scatter arrived in M3");
        assertEquals(ToolId.SCATTER, active());
        assertTrue(controller.activeScatterTool().isPresent());
        assertTrue(rig.notices.isEmpty(), "nothing is coming soon any more: " + rig.notices);
        assertTrue(controller.selectSlot(8), "Place arrived in M2");
        assertEquals(ToolId.PLACE, active());
        assertFalse(rig.place.placing(), "nothing copied yet: the Place tool waits");
        assertTrue(controller.selectTool(ToolId.SCATTER));
        assertEquals(ToolId.SCATTER, active());
    }

    @Test
    void undoGoesToTheToolFirstThenTheServerHistory() {
        rig.mode.enter();
        controller.selectSlot(9);
        // The Scatter tool has no painted stroke to undo: the press reaches the server history.
        assertFalse(controller.action(EditorAction.UNDO, Modifiers.CONTROL));
        controller.run(KeyAction.UNDO, Modifiers.CONTROL);
        assertEquals(List.of("sculptory.notice.nothing_to_undo"), rig.noticeKeys());
    }

    @Test
    void toolsWithoutPermissionAreRefusedWithTheNode() {
        rig.session.setPermissions(without(Perm.BRUSH));
        rig.mode.enter();
        assertFalse(controller.selectSlot(3));
        assertEquals(List.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.tool_needs_permission",
                "sculptory.tool.lower", "sculptory.brush")), rig.notices);
        assertTrue(controller.unavailability(ctx.tools().slot(1).orElseThrow()).isEmpty());
    }

    @Test
    void withoutTheSelectPermissionTheFirstUsableToolIsActivated() {
        rig.session.setPermissions(without(Perm.REGION));
        rig.mode.enter();
        assertEquals(ToolId.RAISE, active());
    }

    @Test
    void losingAToolsPermissionSwitchesToolsAndSaysWhy() {
        rig.mode.enter();
        controller.selectSlot(2);
        rig.session.setPermissions(without(Perm.BRUSH));
        controller.tick();
        assertEquals(ToolId.SELECT, active());
        assertEquals(List.of("sculptory.notice.tool_needs_permission"), rig.noticeKeys());
    }

    @Test
    void theLastToolComesBackAfterASuspension() {
        rig.mode.enter();
        controller.selectSlot(4);
        rig.mode.suspend(EditorMode.SuspendTarget.CHAT);
        assertTrue(ctx.tools().active().isEmpty());
        rig.mode.tick(new EditorMode.Observation(true, false, "world", EditorMode.ScreenState.EDITOR));
        rig.mode.tick(new EditorMode.Observation(true, false, "world", EditorMode.ScreenState.NONE));
        assertEquals(ToolId.SMOOTH, active());
        assertFalse(rig.platform.flyRestored, "a suspension keeps the fly speed");
        rig.mode.exit(ExitReason.TOGGLED);
        assertTrue(rig.platform.flyRestored, "leaving restores it");
    }

    @Test
    void brushToolsListTheirKeys() {
        rig.mode.enter();
        controller.selectSlot(6);
        List<KeyHint> hints = controller.hints();
        assertEquals(List.of(
                new KeyHint("LMB drag", "sculptory.hint.brush.paint"),
                new KeyHint("Middle-click", "sculptory.hint.brush.pick_material"),
                new KeyHint("Ctrl+Scroll", "sculptory.hint.brush.radius"),
                new KeyHint("Alt+Scroll", "sculptory.hint.brush.strength"),
                new KeyHint("F1", "sculptory.hint.help")), hints);
    }

    @Test
    void undoAndRedoLeaveTheirToastToTheSessionsAcceptance() {
        rig.mode.enter();
        controller.run(KeyAction.UNDO, 0);
        assertEquals(List.of("sculptory.notice.nothing_to_undo"), rig.noticeKeys());
        ctx.setSelection(new Box(new BlockPos(0, 0, 0), new BlockPos(4, 4, 4)));
        rig.actions.fill();
        rig.session.finishJobs();
        rig.notices.clear();
        List<Notice> sessionNotices = new ArrayList<>();
        rig.session.onNotice(sessionNotices::add);
        controller.run(KeyAction.UNDO, Modifiers.CONTROL);
        controller.run(KeyAction.REDO, Modifiers.CONTROL);
        controller.run(KeyAction.REDO, Modifiers.CONTROL);
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.nothing_to_redo")), rig.notices,
                "the key press itself only answers when there is nothing to do");
        assertEquals(List.of(
                Notice.of(Notice.Level.INFO, "sculptory.notice.undo", "Fill"),
                Notice.of(Notice.Level.INFO, "sculptory.notice.redo", "Fill")), sessionNotices,
                "the session announces each step it had accepted (the mock accepts at once)");
        assertTrue(rig.session.history().canUndo());
    }

    @Test
    void pressesWhileAStepIsInFlightAreLeftToTheSession() {
        rig.mode.enter();
        rig.session.setHistoryBusy(true);
        controller.run(KeyAction.UNDO, Modifiers.CONTROL);
        controller.run(KeyAction.REDO, Modifiers.CONTROL);
        assertTrue(rig.notices.isEmpty(), "the mirror lags behind a step in flight, so it is not used to refuse");
    }

    @Test
    void leavingTheEditorDropsQueuedUndoPresses() {
        rig.mode.enter();
        rig.session.setHistoryBusy(true);
        rig.mode.escape();
        assertFalse(rig.session.historyBusy(), "queued undos must not keep running after Esc");

        rig.mode.enter();
        rig.session.setHistoryBusy(true);
        rig.mode.suspend(EditorMode.SuspendTarget.CHAT);
        assertFalse(rig.session.historyBusy(), "nor while chat is open");
    }

    @Test
    void selectionKeysWorkWithAnyTool() {
        rig.mode.enter();
        controller.selectSlot(2);
        Box box = new Box(new BlockPos(0, 0, 0), new BlockPos(2, 2, 2));
        ctx.setSelection(box);
        controller.run(KeyAction.NUDGE_UP, Modifiers.SHIFT);
        assertEquals(box.offset(0, 10, 0), ctx.selection().orElseThrow());
        controller.run(KeyAction.ERASE_SELECTION, 0);
        ToolAction.RunOp erase = assertInstanceOf(ToolAction.RunOp.class, rig.session.sent().get(0));
        assertEquals(new OpSpec.Erase(box.offset(0, 10, 0), CellMask.ANY), erase.op());
        controller.run(KeyAction.DESELECT, 0);
        assertTrue(ctx.selection().isEmpty());
    }

    @Test
    void clipboardKeysWithNothingToWorkOnSayWhat() {
        rig.mode.enter();
        controller.run(KeyAction.PASTE, Modifiers.CONTROL);
        controller.run(KeyAction.COPY, Modifiers.CONTROL);
        controller.run(KeyAction.ROTATE_CW, 0);
        controller.run(KeyAction.COMMIT, 0);
        assertEquals(List.of("sculptory.notice.nothing_copied", "sculptory.notice.select_first",
                "sculptory.notice.transform_needs_place"), rig.noticeKeys());
        assertEquals(ToolId.SELECT, active());
    }

    @Test
    void theLibraryAndUiSizeKeysReachTheUi() {
        List<String> toggled = new ArrayList<>();
        controller.setUi(new EditorController.Ui() {
            @Override
            public void toggleHelp() {}

            @Override
            public void toggleWindow(String id) {
                toggled.add(id);
            }

            @Override
            public void toggleWindowsHidden() {}

            @Override
            public void toolChanged() {}

            @Override
            public void stepUiSize(int direction) {
                toggled.add("ui size " + direction);
            }

            @Override
            public void resetUiSize() {
                toggled.add("ui size reset");
            }
        });
        rig.mode.enter();
        controller.run(KeyAction.LIBRARY, 0);
        assertEquals(List.of(EditorController.LIBRARY_WINDOW), toggled);
        toggled.clear();
        controller.run(KeyAction.UI_SMALLER, Modifiers.CONTROL);
        controller.run(KeyAction.UI_LARGER, Modifiers.CONTROL);
        controller.run(KeyAction.UI_RESET, Modifiers.CONTROL);
        assertEquals(List.of("ui size -1", "ui size 1", "ui size reset"), toggled, "the UI size keys reach the UI");
    }

    /** A keymap action as the router dispatches it: offered to the tool first, then to the editor. */
    private void key(KeyAction action, int modifiers) {
        ctx.setModifiers(modifiers);
        if (action.editorAction().isEmpty() || !controller.action(action.editorAction().get(), modifiers)) {
            controller.run(action, modifiers);
        }
        ctx.setModifiers(0);
    }

    /** One rung of the Esc ladder below the UI. */
    private void escape() {
        if (!controller.action(EditorAction.CANCEL, 0)) {
            controller.exitEditor();
        }
    }

    @Test
    void ctrlCCopiesAndCtrlVPlacesThenEscGoesBackToTheTool() {
        rig.mode.enter();
        Box box = new Box(new BlockPos(0, 60, 0), new BlockPos(3, 63, 3));
        ctx.setSelection(box);
        key(KeyAction.COPY, Modifiers.CONTROL);
        assertEquals(List.of(new MockEditorSession.Call("copy", box)), rig.session.calls());
        Notice copied = rig.notices.get(rig.notices.size() - 1);
        assertEquals(Notice.of(Notice.Level.SUCCESS, "sculptory.notice.copied", "64",
                rig.keymap.display(KeyAction.PASTE)), copied);
        var entry = rig.session.clipboards().current().orElseThrow();
        assertEquals(new BlockPos(2, 0, 2), entry.anchor(), "anchored at the bottom centre");

        key(KeyAction.PASTE, Modifiers.CONTROL);
        assertEquals(ToolId.PLACE, active());
        assertTrue(rig.place.placing());
        assertEquals(1, rig.placeRequests.size());

        escape();
        assertFalse(rig.place.placing(), "Esc cancels the placement first");
        controller.tick();
        assertEquals(ToolId.SELECT, active(), "and the Select tool comes back");
        escape();
        assertEquals(EditorState.INACTIVE, rig.mode.state(), "the next Esc leaves the editor");
    }

    @Test
    void cutNeedsTheRegionPermissionAndStartsAnEraseJob() {
        rig.mode.enter();
        ctx.setSelection(new Box(new BlockPos(0, 60, 0), new BlockPos(1, 61, 1)));
        rig.session.setPermissions(without(Perm.REGION));
        controller.run(KeyAction.CUT, Modifiers.CONTROL);
        assertEquals(Notice.of(Notice.Level.WARNING, "sculptory.notice.needs_permission", Perm.REGION.node()),
                rig.notices.get(rig.notices.size() - 1));
        assertTrue(rig.session.calls().isEmpty());
        rig.session.setPermissions(without());
        controller.run(KeyAction.CUT, Modifiers.CONTROL);
        assertEquals("cut", rig.session.calls().get(0).kind());
        assertEquals("Cut", rig.session.jobs().jobs().get(0).label());
        assertEquals("sculptory.notice.cut", rig.notices.get(rig.notices.size() - 1).key());
    }

    @Test
    void eachCopyReplacesTheClipboard() {
        rig.mode.enter();
        ctx.setSelection(new Box(new BlockPos(0, 60, 0), new BlockPos(1, 61, 1)));
        controller.run(KeyAction.COPY, Modifiers.CONTROL);
        var first = rig.session.clipboards().current().orElseThrow();
        ctx.setSelection(new Box(new BlockPos(5, 60, 5), new BlockPos(9, 61, 5)));
        controller.run(KeyAction.COPY, Modifiers.CONTROL);
        var second = rig.session.clipboards().current().orElseThrow();
        assertFalse(first.clipboardId().equals(second.clipboardId()));
        assertTrue(rig.session.clipboards().get(first.clipboardId()).isEmpty(), "the old clipboard id is dead");
        assertEquals(List.of(second), rig.session.clipboards().entries());
        assertEquals(new BlockPos(5, 2, 1), second.dims());
    }

    @Test
    void moveFromTheSelectionCommitsAMoveAndMovesTheSelection() {
        rig.mode.enter();
        Box box = new Box(new BlockPos(0, 60, 0), new BlockPos(2, 61, 2));
        ctx.setSelection(box);
        assertTrue(rig.clipboard.move());
        assertEquals(ToolId.PLACE, active());
        key(KeyAction.COMMIT, 0);
        assertEquals("sculptory.notice.move_first", rig.notices.get(rig.notices.size() - 1).key());
        key(KeyAction.NUDGE_FORWARD, 0);
        key(KeyAction.NUDGE_FORWARD, Modifiers.SHIFT);
        key(KeyAction.COMMIT, 0);
        ToolAction.RunOp run = assertInstanceOf(ToolAction.RunOp.class, rig.session.sent().get(rig.session.sent().size() - 1));
        OpSpec.Move move = assertInstanceOf(OpSpec.Move.class, run.op());
        assertEquals(box, move.box());
        assertEquals(new BlockPos(0, 0, 11), move.offset(), "yaw 0 faces south (+Z): 1 + 10 blocks");
        assertEquals(box.offset(0, 0, 11), ctx.selection().orElseThrow(), "the selection follows the move");
        controller.tick();
        assertEquals(ToolId.SELECT, active());
    }

    @Test
    void scrollStepsTheFlySpeed() {
        rig.mode.enter();
        controller.flySpeed(1, 0);
        controller.flySpeed(1, 0);
        assertEquals(2, rig.platform.flyMultiplier);
        assertEquals("2×", ctx.flySpeed().display());
        for (int i = 0; i < 20; i++) {
            controller.flySpeed(-1, 0);
        }
        assertEquals(0.25, rig.platform.flyMultiplier);
        assertEquals("0.25×", ctx.flySpeed().display());
    }

    @Test
    void middleClickPicksTheBlockUnderTheCursor() {
        rig.mode.enter();
        BlockDescriptor oak = BlockDescriptor.parse("minecraft:oak_log[axis=x]");
        rig.platform.blocks.put(new BlockPos(1, 2, 3), oak);
        rig.platform.pickBlockAt(1, 2, 3);
        controller.eyedropper(10, 10);
        assertEquals(oak, ctx.activeBlock());
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.picked_block", "minecraft:oak_log")),
                rig.notices);
        assertEquals(RaycastMode.BLOCKS, rig.platform.pickModes.get(rig.platform.pickModes.size() - 1));

        rig.platform.nextPick = CursorPick.NONE;
        controller.eyedropper(10, 10);
        assertEquals(oak, ctx.activeBlock(), "the sky picks nothing");
        assertEquals(new NamespacedId("minecraft:oak_log"), ctx.activeBlock().block());
    }

    /** With the Scatter tool, middle-click adds the block to the mix (a flower too: the pick sees plants). */
    @Test
    void middleClickWithScatterAddsTheBlockToTheMix() {
        rig.mode.enter();
        BlockDescriptor before = ctx.activeBlock();
        assertTrue(controller.selectSlot(9));
        BlockDescriptor poppy = BlockDescriptor.parse("minecraft:poppy");
        rig.platform.blocks.put(new BlockPos(1, 2, 3), poppy);
        rig.platform.pickBlockAt(1, 2, 3);
        rig.notices.clear();
        controller.eyedropper(10, 10);
        assertEquals(RaycastMode.BLOCKS, rig.platform.pickModes.get(rig.platform.pickModes.size() - 1));
        assertEquals(before, ctx.activeBlock(), "the active block is left alone");
        assertEquals(List.of(new ScatterSource.Block("minecraft:poppy")), controller.activeScatterTool().orElseThrow()
                .mix().variants().stream().map(variant -> variant.source()).toList());
        assertTrue(rig.notices.stream().noneMatch(n -> n.key().equals("sculptory.notice.picked_block")));
    }

    @Test
    void vanillaKeysSuspendOrToggleTheEditor() {
        rig.mode.enter();
        rig.platform.systemKeys.put(GLFW.GLFW_KEY_E, EditorPlatform.SystemKey.INVENTORY);
        rig.platform.systemKeys.put(GLFW.GLFW_KEY_B, EditorPlatform.SystemKey.TOGGLE_EDITOR);
        assertFalse(controller.systemKey(GLFW.GLFW_KEY_K, 0));
        assertTrue(controller.systemKey(GLFW.GLFW_KEY_E, 0));
        assertEquals(EditorState.SUSPENDED, rig.mode.state());
        rig.mode.tick(new EditorMode.Observation(true, false, "world", EditorMode.ScreenState.EDITOR));
        assertTrue(rig.events.contains("open INVENTORY"));
        rig.mode.tick(new EditorMode.Observation(true, false, "world", EditorMode.ScreenState.NONE));
        assertTrue(controller.systemKey(GLFW.GLFW_KEY_B, 0));
        assertEquals(EditorState.INACTIVE, rig.mode.state());
    }

    @Test
    void pointerEventsCarryTheWorldCursorForTheToolsRaycastMode() {
        rig.mode.enter();
        rig.platform.pickBlockAt(5, 6, 7);
        assertTrue(controller.pointer(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 10, 10, 0));
        assertEquals(RaycastMode.BLOCKS, rig.platform.pickModes.get(0), "Select aims at every block");
        assertEquals(Box.of(new BlockPos(5, 6, 7)), ctx.selection().orElseThrow());
        assertTrue(controller.hasPointerCapture());
        controller.pointer(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 10, 10, 0);
        assertFalse(controller.hasPointerCapture());
        controller.selectSlot(2);
        controller.pointer(PointerEvent.Kind.MOVE, -1, 10, 10, 0);
        assertEquals(RaycastMode.TERRAIN, rig.platform.pickModes.get(rig.platform.pickModes.size() - 1));
    }

    /**
     * A Shape brush press, once its first shape went out, hands its ray overlay (the world before it wrote) to every
     * pick while it lasts, the release's included; the eyedropper and later picks see the world as it is.
     */
    @Test
    void picksSeeTheWorldThroughTheActiveToolsRayOverlayWhileAPressLasts() {
        rig.mode.enter();
        assertTrue(controller.selectSlot(10), "the Shape brush");
        rig.platform.pickBlockAt(0, 64, 0);
        controller.frame(10, 10, 0, 0);
        assertTrue(controller.pointer(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 10, 10, 0));
        controller.frame(10, 10, 16_000_000L, 0); // the first shape goes out: the press keeps the world under it
        controller.frame(10, 10, 32_000_000L, 0);
        controller.eyedropper(10, 10);
        controller.pointer(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 10, 10, 0);
        controller.frame(10, 10, 48_000_000L, 0);
        List<Boolean> overlaid = rig.platform.pickOverlays.stream().map(java.util.Optional::isPresent).toList();
        assertEquals(List.of(false, false, false, true, false, true, false), overlaid,
                "frame, press, the first shape's frame, then the press's own view; the eyedropper's and after: none");
        assertEquals("Shape", rig.session.history().undoLabel(), "one shape, one undo step");
        rig.session.undo();
        assertFalse(rig.session.history().canUndo(), "and only one");
    }

    @Test
    void aimAtWaterAndLavaIsOffUntilItsKeyTurnsItOnForEveryPick() {
        rig.mode.enter();
        assertFalse(controller.aimsAtFluids(), "off by default: tools aim through water and lava");
        controller.pointer(PointerEvent.Kind.MOVE, -1, 10, 10, 0);

        controller.run(KeyAction.AIM_AT_FLUIDS, 0);
        assertTrue(controller.aimsAtFluids());
        assertTrue(rig.platform.aimAtFluids);
        assertEquals(List.of("sculptory.notice.fluid_aim_on"), rig.noticeKeys());
        assertEquals(KeyHint.text("sculptory.hint.fluid_aim"), controller.hints().get(0), "the hint line says so");
        controller.frame(10, 10, 0, 0);
        controller.pointer(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 10, 10, 0);
        controller.pointer(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 10, 10, 0);
        controller.selectSlot(2);
        controller.pointer(PointerEvent.Kind.MOVE, -1, 10, 10, 0);
        controller.eyedropper(10, 10);
        assertEquals(List.of(false, true, true, true, true, true), rig.platform.pickFluids,
                "the frame, Select, a brush and middle-click all pick with it");
        assertEquals(List.of(RaycastMode.BLOCKS, RaycastMode.BLOCKS, RaycastMode.BLOCKS, RaycastMode.BLOCKS,
                RaycastMode.TERRAIN, RaycastMode.BLOCKS), rig.platform.pickModes, "each tool keeps its raycast mode");

        rig.notices.clear();
        controller.run(KeyAction.AIM_AT_FLUIDS, 0);
        assertFalse(controller.aimsAtFluids());
        assertEquals(List.of("sculptory.notice.fluid_aim_off"), rig.noticeKeys());
        assertTrue(controller.hints().stream().noneMatch(hint -> hint.descriptionKey().equals("sculptory.hint.fluid_aim")));
        controller.setAimAtFluids(false);
        assertEquals(1, rig.notices.size(), "no toast when nothing changes");
    }

    @Test
    void aimAtWaterAndLavaOutlivesClosingTheEditor() {
        rig.mode.enter();
        controller.setAimAtFluids(true);
        rig.mode.exit(ExitReason.TOGGLED);
        rig.mode.enter();
        assertTrue(controller.aimsAtFluids(), "kept for the game session, like the fly speed");
    }

    @Test
    void escapeLeavesThroughTheModeWithTheHint() {
        rig.mode.enter();
        controller.exitEditor();
        assertEquals(EditorState.INACTIVE, rig.mode.state());
        assertTrue(rig.noticeKeys().contains("sculptory.notice.editor_closed"));
        assertTrue(ctx.tools().active().isEmpty());
    }

    @Test
    void deactivationReasonsReachTheTool() {
        rig.mode.enter();
        controller.onExited(DeactivateReason.PERMISSION_LOST);
        assertTrue(ctx.tools().active().isEmpty());
        controller.onEntered();
        assertEquals(ToolId.SELECT, active(), "the remembered tool comes back");
    }
}
