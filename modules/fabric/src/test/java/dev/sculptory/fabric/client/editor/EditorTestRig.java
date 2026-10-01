package dev.sculptory.fabric.client.editor;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.clipboard.ClipboardActions;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.mock.MockWorldReader;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.RayOverlay;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
import dev.sculptory.fabric.client.editor.tool.RegionWork;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tools.EditorToolSet;
import dev.sculptory.fabric.client.editor.tools.brush.BrushServices;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.editor.tools.select.SelectTool;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.editor.world.BoxFace;
import dev.sculptory.fabric.client.editor.world.GizmoPick;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

/** A complete editor on the mock session, with a fake platform and host, for controller and UI tests. */
final class EditorTestRig {
    final MockEditorSession session = new MockEditorSession();
    final StateSpace states = new FakeStateSpace();
    final WorldReader world = new MockWorldReader(states);
    final List<Notice> notices = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    final FakePlatform platform = new FakePlatform();
    final EditorKeymap keymap = EditorKeymap.defaults();
    boolean looking;
    SelectionActions.Confirmer confirmer = (message, onConfirm) -> events.add("confirm " + message);

    final EditorBackend backend = new EditorBackend() {
        @Override
        public Optional<EditorSession> session() {
            return Optional.of(session);
        }

        @Override
        public StateSpace states() {
            return states;
        }

        @Override
        public WorldReader world() {
            return world;
        }
    };

    final EditorContext ctx = new EditorContext(() -> backend, notices::add);
    final EditorMode mode = new EditorMode(new EditorMode.Host() {
        @Override
        public Optional<EditorSession> session() {
            return Optional.of(session);
        }

        @Override
        public Object currentWorld() {
            return "world";
        }

        @Override
        public void showEditorScreen() {
            events.add("show");
        }

        @Override
        public void hideEditorScreen() {
            events.add("hide");
        }

        @Override
        public void openVanillaScreen(EditorMode.SuspendTarget target) {
            events.add("open " + target);
        }

        @Override
        public void setEditorVisuals(boolean editing) {
        }

        @Override
        public void activated() {
            controller.onEntered();
        }

        @Override
        public void deactivated(DeactivateReason reason) {
            controller.onExited(reason);
        }

        @Override
        public void toast(Notice notice) {
            notices.add(notice);
        }
    });
    final SelectionActions actions = new SelectionActions(() -> ctx.contextFor(ToolId.SELECT), ctx::activeBlock,
            (message, onConfirm) -> confirmer.confirm(message, onConfirm), Translator.KEYS, () -> 7L);
    final SelectTool select = new SelectTool(actions, new SelectTool.Services() {
        @Override
        public Optional<Ray> cursorRay() {
            return ctx.cursor().ray();
        }

        @Override
        public Optional<ScreenProjector> projector() {
            return Optional.empty();
        }

        @Override
        public Optional<double[]> eye() {
            return Optional.empty();
        }

        @Override
        public float cameraYaw() {
            return 0;
        }

        @Override
        public void setHoveredFace(BoxFace face) {
            ctx.setHoveredFace(face);
        }

        @Override
        public String keyLabel(KeyAction action) {
            return keymap.display(action);
        }
    });
    EditorController controller;
    final List<PlaceTool.Request> placeRequests = new ArrayList<>();
    final PlaceTool place = new PlaceTool(new PlaceTool.Services() {
        @Override
        public Optional<Ray> cursorRay() {
            return ctx.cursor().ray();
        }

        @Override
        public Optional<ScreenProjector> projector() {
            return Optional.empty();
        }

        @Override
        public Optional<double[]> eye() {
            return Optional.empty();
        }

        @Override
        public float cameraYaw() {
            return 0;
        }

        @Override
        public void showGizmo(double x, double y, double z, GizmoPick.Handle hovered) {}

        @Override
        public void clearGizmo() {}

        @Override
        public void showGhosts(List<GhostPlacement> placements) {}

        @Override
        public String ghostStatus() {
            return "";
        }

        @Override
        public void releaseGhost(GhostVolume volume) {}

        @Override
        public String keyLabel(KeyAction action) {
            return keymap.display(action);
        }

        @Override
        public Executor background() {
            return Runnable::run;
        }

        @Override
        public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
            events.add("confirm " + opNameKey + " " + blocks);
        }

        @Override
        public void finished() {
            controller.placeFinished();
        }
    }, () -> false);
    final List<String> io = new ArrayList<>();
    final ClipboardActions clipboard = new ClipboardActions(new ClipboardActions.Host() {
        @Override
        public Optional<EditorSession> session() {
            return Optional.of(session);
        }

        @Override
        public Optional<Box> selection() {
            return ctx.selection();
        }

        @Override
        public Optional<Region> selectionRegion() {
            return ctx.selectionRegion();
        }

        @Override
        public RegionWork regionWork() {
            return ctx.regionWork();
        }

        @Override
        public void notify(Notice notice) {
            notices.add(notice);
        }

        @Override
        public boolean place(PlaceTool.Request request) {
            placeRequests.add(request);
            return controller.place(request);
        }

        @Override
        public Optional<PlaceTool> activePlaceTool() {
            return controller.activePlaceTool();
        }

        @Override
        public String keyLabel(KeyAction action) {
            return keymap.display(action);
        }

        @Override
        public Path exportDirectory() {
            return exportDirectory;
        }

        @Override
        public Executor io() {
            return task -> {
                io.add("io");
                task.run();
            };
        }

        @Override
        public Executor mainThread() {
            return Runnable::run;
        }
    });
    Path exportDirectory = Path.of("build", "tmp", "exports-unset");

    EditorTestRig() {
        EditorToolSet.register(ctx.tools(), select, BrushServices.headless(), place);
        controller = new EditorController(ctx, mode, platform, actions, keymap, Translator.KEYS, () -> looking);
        controller.setClipboard(clipboard);
    }

    List<String> noticeKeys() {
        return notices.stream().map(Notice::key).toList();
    }

    /** A platform whose picks, blocks and keys are set by the test. */
    final class FakePlatform implements EditorPlatform {
        CursorPick nextPick = CursorPick.NONE;
        final Map<BlockPos, BlockDescriptor> blocks = new HashMap<>();
        final Map<Integer, SystemKey> systemKeys = new HashMap<>();
        double flyMultiplier = 1;
        boolean flyRestored;
        final List<RaycastMode> pickModes = new ArrayList<>();
        /** The tool's ray overlay at each world pick. */
        final List<Optional<RayOverlay>> pickOverlays = new ArrayList<>();
        /** Where each world pick was made, as "x,y" in screen coordinates. */
        final List<String> pickPoints = new ArrayList<>();
        /** "Aim at water and lava", and whether it was on at each world pick. */
        boolean aimAtFluids;
        final List<Boolean> pickFluids = new ArrayList<>();

        void pickBlockAt(int x, int y, int z) {
            nextPick = new CursorPick(Optional.empty(),
                    new WorldCursor(new BlockPos(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false));
        }

        @Override
        public CursorPick pick(double x, double y, RaycastMode mode, boolean crosshair, RayOverlay overlay) {
            pickModes.add(mode);
            pickOverlays.add(Optional.ofNullable(overlay));
            pickPoints.add(x + "," + y);
            pickFluids.add(aimAtFluids);
            return nextPick;
        }

        @Override
        public boolean aimsAtFluids() {
            return aimAtFluids;
        }

        @Override
        public void setAimAtFluids(boolean aim) {
            aimAtFluids = aim;
        }

        @Override
        public Optional<BlockDescriptor> blockAt(BlockPos pos) {
            return Optional.ofNullable(blocks.get(pos));
        }

        @Override
        public String blockName(BlockDescriptor block) {
            return block.block().value();
        }

        @Override
        public void applyFlySpeed(double multiplier) {
            flyMultiplier = multiplier;
            flyRestored = false;
        }

        @Override
        public void restoreFlySpeed() {
            flyRestored = true;
        }

        @Override
        public float cameraYaw() {
            return 0;
        }

        @Override
        public Optional<ScreenProjector> projector() {
            return Optional.empty();
        }

        @Override
        public Optional<double[]> eye() {
            return Optional.ofNullable(eye);
        }

        /** Where the camera is (the tutorial's first lesson asks the player to move); unknown until set. */
        double[] eye;

        void eyeAt(double x, double y, double z) {
            eye = new double[] {x, y, z};
        }

        @Override
        public SystemKey systemKey(int key, int scanCode) {
            return systemKeys.getOrDefault(key, SystemKey.NONE);
        }

        /** The players "online" for the Access dialog's picker (tests set it). */
        final List<String> onlinePlayers = new ArrayList<>();

        @Override
        public List<String> onlinePlayerNames() {
            return List.copyOf(onlinePlayers);
        }
    }
}
