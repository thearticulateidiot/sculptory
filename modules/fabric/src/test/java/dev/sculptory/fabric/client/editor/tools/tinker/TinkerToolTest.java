package dev.sculptory.fabric.client.editor.tools.tinker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityView;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.core.tinker.TinkerKind;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolDescriptor;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tools.EditorToolSet;
import dev.sculptory.fabric.client.editor.tools.PlaceholderTool;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.session.Capabilities;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.HistoryMirror;
import dev.sculptory.fabric.client.session.JobTracker;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.client.session.SessionState;
import dev.sculptory.fabric.client.session.StrokeHandle;
import dev.sculptory.fabric.client.session.StrokeParams;
import dev.sculptory.fabric.client.session.Subscription;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.fabric.client.session.ToolResult;
import dev.sculptory.fabric.client.tinker.TinkerController;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * The Tinker tool and its shared controller: slot and key, the label, Scroll and Shift+Scroll on blocks (the property
 * remembered per block type, one change in flight and the rest merged, a refusal dropping what waited), Scroll on
 * entities, clicks opening the panel, permissions and servers without Tinker, and Apply to all like it in the selection.
 */
class TinkerToolTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int straight = states.state("minecraft:oak_stairs[facing=east]");
    private final int innerLeft = states.state("minecraft:oak_stairs[facing=east,shape=inner_left]");
    private final int innerRight = states.state("minecraft:oak_stairs[facing=east,shape=inner_right]");

    private static WorldCursor on(int x, int y, int z) {
        return new WorldCursor(new BlockPos(x, y, z), WorldCursor.Face.UP, x + 0.5, y + 1, z + 0.5, false);
    }

    private static Permissions without(Perm... missing) {
        EnumSet<Perm> granted = EnumSet.allOf(Perm.class);
        for (Perm perm : missing) granted.remove(perm);
        return new Permissions(Perm.mask(granted), Limits.DEFAULTS);
    }

    private static PlaceholderTool select() {
        return new PlaceholderTool(new ToolDescriptor(ToolId.SELECT, "sculptory.tool.select", "minecraft:wooden_axe",
                Perm.USE), "sculptory.hint.select");
    }

    // ---------------------------------------------------------------- fixtures

    /** A session that records Tinker requests and answers them when the test says so. */
    private static final class FakeSession implements EditorSession {
        record BlockCall(BlockPos pos, int expected, int target, SignText sign, CompletableFuture<Reply<Boolean>> reply) {}

        record EntityCall(UUID id, List<EntityEdit> edits, CompletableFuture<Reply<EntityView>> reply) {}

        final MockEditorSession mock = new MockEditorSession();
        final List<BlockCall> blocks = new ArrayList<>();
        final List<EntityCall> entities = new ArrayList<>();
        boolean offered = true;

        @Override
        public boolean tinkerOffered() {
            return offered;
        }

        @Override
        public CompletionStage<Reply<Boolean>> tinkerBlock(BlockPos pos, int expected, int target, SignText sign) {
            CompletableFuture<Reply<Boolean>> reply = new CompletableFuture<>();
            blocks.add(new BlockCall(pos, expected, target, sign, reply));
            return reply;
        }

        @Override
        public CompletionStage<Reply<EntityView>> tinkerEntity(UUID id, List<EntityEdit> edits) {
            CompletableFuture<Reply<EntityView>> reply = new CompletableFuture<>();
            entities.add(new EntityCall(id, List.copyOf(edits), reply));
            return reply;
        }

        @Override
        public SessionState state() {
            return mock.state();
        }

        @Override
        public Capabilities capabilities() {
            return mock.capabilities();
        }

        @Override
        public Permissions permissions() {
            return mock.permissions();
        }

        @Override
        public CompletionStage<ToolResult> send(ToolAction a) {
            return mock.send(a);
        }

        @Override
        public StrokeHandle beginStroke(ToolId tool, BrushSpec spec, StrokeParams p) {
            return mock.beginStroke(tool, spec, p);
        }

        @Override
        public void undo() {
            mock.undo();
        }

        @Override
        public void redo() {
            mock.redo();
        }

        @Override
        public void jumpTo(long historyId) {
            mock.jumpTo(historyId);
        }

        @Override
        public JobTracker jobs() {
            return mock.jobs();
        }

        @Override
        public HistoryMirror history() {
            return mock.history();
        }

        @Override
        public ClipboardCache clipboards() {
            return mock.clipboards();
        }

        @Override
        public Subscription onNotice(Consumer<Notice> l) {
            return mock.onNotice(l);
        }
    }

    /** Services with an entity the test puts under the cursor, and a record of panel openings. */
    private static final class FakeServices implements TinkerTool.Services {
        TinkerController.EntityTarget entity;
        int panels;
        SignText sign;

        @Override
        public Optional<TinkerController.EntityTarget> entityAt(WorldCursor cursor) {
            return Optional.ofNullable(entity);
        }

        @Override
        public String blockName(StateSpace states, int state) {
            String id = states.blockId(state).value();
            return id.substring(id.indexOf(':') + 1);
        }

        @Override
        public Optional<SignText> signText(BlockPos pos) {
            return Optional.ofNullable(sign);
        }

        @Override
        public String translate(String key, Object... args) {
            return Translator.KEYS.translate(key, args);
        }

        @Override
        public void openPanel() {
            panels++;
        }

        @Override
        public String keyLabel(KeyAction action) {
            return EditorKeymap.defaults().display(action);
        }

        @Override
        public long nanoTime() {
            return 0;
        }
    }

    /** The Tinker tool, active in an editor context over a world with stairs, stone and a recording session. */
    private final class Rig {
        final FakeWorld world = new FakeWorld(states);
        final FakeSession session = new FakeSession();
        final FakeServices services = new FakeServices();
        final List<Notice> notices = new ArrayList<>();
        final EditorContext ctx;
        final TinkerTool tool;
        final ToolContext view;

        Rig() {
            world.set(0, 64, 0, straight);
            world.set(1, 64, 0, straight);
            world.set(2, 64, 0, stone);
            world.set(3, 64, 0, states.state("minecraft:oak_slab"));
            EditorBackend backend = new EditorBackend() {
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
            ctx = new EditorContext(() -> backend, notices::add);
            ctx.tools().register(select());
            SelectionActions actions = new SelectionActions(() -> ctx.contextFor(ToolId.SELECT),
                    () -> states.describe(stone), (message, onConfirm) -> onConfirm.run(), Translator.KEYS, () -> 1L);
            tool = new TinkerTool(services, actions, () -> ctx.contextFor(ToolId.TINKER));
            ctx.tools().register(tool);
            view = ctx.contextFor(ToolId.TINKER);
            assertTrue(ctx.tools().activate(ToolId.TINKER, view));
        }

        void hover(WorldCursor cursor) {
            tool.frame(view, new FrameInfo(0, 0f, 100, 80, cursor));
        }

        boolean scroll(double amount, int modifiers) {
            return tool.onScroll(view, new ScrollEvent(amount, modifiers));
        }

        boolean click(WorldCursor cursor) {
            return tool.onPointer(view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 100, 80, 0, cursor));
        }

        TinkerController controller() {
            return tool.controller();
        }

        List<String> noticeKeys() {
            return notices.stream().map(Notice::key).toList();
        }
    }

    private static EntityView standView(float yaw) {
        return EntityView.fromSaved(TinkerKind.ARMOR_STAND, NbtCompound.builder().put("Pos", EntityNbt.doubles(0.5, 65, 0.5))
                .put("Rotation", dev.sculptory.core.nbt.NbtList.of(dev.sculptory.core.nbt.NbtTag.FLOAT,
                        List.of(new dev.sculptory.core.nbt.NbtTag.NbtFloat(yaw),
                                new dev.sculptory.core.nbt.NbtTag.NbtFloat(0)))).build(), "", "");
    }

    // ---------------------------------------------------------------- the slot and key

    @Test
    void theFourteenthSlotAndItsKey() {
        assertEquals(15, ToolRegistry.PALETTE_SLOTS);
        ToolRegistry registry = new ToolRegistry();
        EditorToolSet.register(registry, select());
        assertInstanceOf(TinkerTool.class, registry.slot(14).orElseThrow());
        assertEquals(ToolId.TINKER, registry.slot(14).orElseThrow().descriptor().id());
        assertEquals(KeyAction.TOOL_14, KeyAction.toolSlot(14));
        assertEquals(14, KeyAction.TOOL_14.toolSlot());
        assertEquals(0, KeyAction.TOOL_SIZE.toolSlot(), "the actions after the slots are no slots");
        assertEquals(List.of(KeyChord.parse("right_bracket")), KeyAction.TOOL_14.defaultChords());
        assertEquals("]", EditorKeymap.defaults().display(KeyAction.TOOL_14));
        TinkerTool tool = new TinkerTool(TinkerTool.Services.headless(), null, null);
        assertEquals(Perm.REGION, tool.descriptor().permission());
        assertEquals(RaycastMode.BLOCKS, tool.raycastMode(null));
        assertTrue(tool.schema().defs().isEmpty(), "no settings");
    }

    // ---------------------------------------------------------------- blocks

    @Test
    void theLabelShowsTheBlockAndOneProperty() {
        Rig rig = new Rig();
        rig.hover(on(0, 64, 0));
        assertEquals("oak_stairs · shape: straight", rig.controller().label());
        rig.hover(on(2, 64, 0));
        assertEquals("stone · sculptory.tinker.no_properties", rig.controller().label());
        rig.hover(WorldCursor.miss(0, 0, 0));
        assertEquals("", rig.controller().label());
        assertEquals(List.of("sculptory.hint.tinker.aim"), rig.tool.hints(rig.view).stream()
                .map(KeyHint::descriptionKey).toList());
        rig.hover(on(0, 64, 0));
        assertEquals(List.of("sculptory.hint.tinker.value", "sculptory.hint.tinker.property",
                "sculptory.hint.tinker.panel"), rig.tool.hints(rig.view).stream().map(KeyHint::descriptionKey).toList());
    }

    @Test
    void scrollSendsTheNextValueAndMergesWhatComesWhileItIsOnItsWay() {
        Rig rig = new Rig();
        rig.hover(on(0, 64, 0));
        assertTrue(rig.tool.takesScroll(rig.view, 0), "Scroll is the tool's over a block with properties");
        assertTrue(rig.scroll(1, 0));
        assertEquals(1, rig.session.blocks.size());
        FakeSession.BlockCall first = rig.session.blocks.get(0);
        assertEquals(new BlockPos(0, 64, 0), first.pos());
        assertEquals(straight, first.expected());
        assertEquals(innerLeft, first.target());
        assertEquals("oak_stairs · shape: inner left", rig.controller().label(), "shown at once");
        rig.scroll(1, 0);
        rig.scroll(1, 0);
        assertEquals(1, rig.session.blocks.size(), "one in flight; the rest waits");
        assertEquals("oak_stairs · shape: outer left", rig.controller().label());
        first.reply().complete(Reply.ok(Boolean.TRUE));
        assertEquals(2, rig.session.blocks.size(), "what waited goes as one change");
        FakeSession.BlockCall second = rig.session.blocks.get(1);
        assertEquals(innerLeft, second.expected(), "expected: what the first change left");
        assertEquals(states.state("minecraft:oak_stairs[facing=east,shape=outer_left]"), second.target());
        second.reply().complete(Reply.ok(Boolean.TRUE));
        assertEquals("oak_stairs · shape: outer left", rig.controller().label(),
                "shown while the world catches up");
        rig.world.set(0, 64, 0, second.target());
        assertEquals("oak_stairs · shape: outer left", rig.controller().label());
        rig.scroll(-1, 0);
        assertEquals(states.state("minecraft:oak_stairs[facing=east,shape=inner_right]"),
                rig.session.blocks.get(2).target(), "scrolling down goes back");
    }

    @Test
    void aRefusalDropsWhatWaitedForThatBlock() {
        Rig rig = new Rig();
        rig.hover(on(0, 64, 0));
        rig.scroll(1, 0);
        rig.scroll(1, 0);
        rig.session.blocks.get(0).reply().complete(Reply.refused(RejectReason.INVALID, "the block changed meanwhile"));
        assertEquals(1, rig.session.blocks.size(), "the waiting change counted on the refused one");
        assertEquals("oak_stairs · shape: straight", rig.controller().label(), "back to the world");
        assertFalse(rig.controller().busy(new BlockPos(0, 64, 0)));
    }

    @Test
    void shiftScrollPicksThePropertyAndItIsRememberedPerBlockType() {
        Rig rig = new Rig();
        rig.hover(on(0, 64, 0));
        assertTrue(rig.scroll(1, Modifiers.SHIFT));
        assertTrue(rig.session.blocks.isEmpty(), "picking a property changes nothing");
        assertEquals("oak_stairs · facing: east", rig.controller().label());
        rig.hover(on(1, 64, 0));
        assertEquals("oak_stairs · facing: east", rig.controller().label(), "another stair shows the same property");
        rig.scroll(1, 0);
        assertEquals(states.state("minecraft:oak_stairs[facing=south]"), rig.session.blocks.get(0).target());
        rig.hover(on(3, 64, 0));
        assertEquals("oak_slab · type: bottom", rig.controller().label(), "a slab has its own choice");
        rig.scroll(-1, Modifiers.SHIFT);
        assertEquals("oak_slab · waterlogged: false", rig.controller().label(), "going round backwards");
    }

    @Test
    void scrollIsFlySpeedOverNothingToChangeAndWithCtrlOrAlt() {
        Rig rig = new Rig();
        rig.hover(on(2, 64, 0));
        assertFalse(rig.tool.takesScroll(rig.view, 0), "stone has no property");
        assertFalse(rig.scroll(1, 0));
        rig.hover(WorldCursor.miss(0, 0, 0));
        assertFalse(rig.tool.takesScroll(rig.view, 0));
        rig.hover(on(0, 64, 0));
        assertFalse(rig.tool.takesScroll(rig.view, Modifiers.CONTROL));
        assertFalse(rig.tool.takesScroll(rig.view, Modifiers.ALT));
        assertTrue(rig.tool.takesScroll(rig.view, Modifiers.SHIFT));
        assertTrue(rig.session.blocks.isEmpty());
    }

    @Test
    void withoutRegionOrWithoutTinkerOnTheServerNothingIsSent() {
        Rig rig = new Rig();
        rig.session.mock.setPermissions(without(Perm.REGION));
        rig.hover(on(0, 64, 0));
        assertTrue(rig.scroll(1, 0));
        assertTrue(rig.click(on(0, 64, 0)));
        assertTrue(rig.session.blocks.isEmpty());
        assertEquals(0, rig.services.panels);
        assertEquals(List.of("sculptory.notice.needs_permission", "sculptory.notice.needs_permission"),
                rig.noticeKeys());
        rig.notices.clear();
        rig.session.mock.setPermissions(without());
        rig.session.offered = false;
        rig.scroll(1, 0);
        assertTrue(rig.session.blocks.isEmpty());
        assertEquals(List.of(TinkerController.NOT_OFFERED), rig.noticeKeys());
        rig.click(on(0, 64, 0));
        assertEquals(0, rig.services.panels);
    }

    @Test
    void aClickOpensThePanelAndSignTextGoesAsIs() {
        Rig rig = new Rig();
        assertTrue(rig.click(on(0, 64, 0)));
        assertEquals(1, rig.services.panels);
        assertEquals(new TinkerController.BlockTarget(new BlockPos(0, 64, 0)), rig.tool.panelTarget().orElseThrow());
        SignText text = SignText.EMPTY.withSide(true, SignText.Side.EMPTY.withLine(0, "Hello").withColor("red"));
        rig.controller().setSign(new BlockPos(0, 64, 0), text);
        FakeSession.BlockCall call = rig.session.blocks.get(0);
        assertEquals(straight, call.expected());
        assertEquals(straight, call.target(), "the text alone");
        assertSame(text, call.sign());
        assertFalse(rig.click(WorldCursor.miss(0, 0, 0)), "nothing under the cursor");
    }

    @Test
    void applyToAllSendsThePropertyPatternOverTheSelection() {
        Rig rig = new Rig();
        rig.hover(on(0, 64, 0));
        rig.scroll(1, Modifiers.SHIFT); // facing
        assertFalse(rig.tool.applyToSelection(new BlockPos(0, 64, 0)), "no selection");
        assertTrue(rig.noticeKeys().contains("sculptory.notice.select_first"));
        Box box = new Box(new BlockPos(0, 64, 0), new BlockPos(3, 64, 0));
        rig.ctx.contextFor(ToolId.SELECT).setSelection(box);
        assertTrue(rig.tool.applyToSelection(new BlockPos(0, 64, 0)));
        List<ToolAction> sent = rig.session.mock.sent();
        assertEquals(1, sent.size());
        OpSpec.Fill fill = assertInstanceOf(OpSpec.Fill.class, ((ToolAction.RunOp) sent.get(0)).op());
        assertEquals(new Region.Cuboid(box), fill.region());
        assertEquals(new Pattern.SetProperty(straight, "facing"), fill.pattern(), "the stair's facing, east");
        assertEquals(CellMask.ANY, fill.mask());
        rig.hover(on(2, 64, 0));
        assertFalse(rig.tool.applyToSelection(new BlockPos(2, 64, 0)), "stone has nothing to apply");
    }

    // ---------------------------------------------------------------- entities

    @Test
    void scrollTurnsArmorStandsAndDisplaysAndItemFramesItems() {
        Rig rig = new Rig();
        UUID stand = UUID.randomUUID();
        rig.services.entity = new TinkerController.EntityTarget(stand, TinkerKind.ARMOR_STAND, "Armor Stand", 30f, 0, "",
                new double[] {0, 64, 0, 1, 66, 1});
        rig.hover(on(0, 64, 0));
        assertEquals("Armor Stand · sculptory.tinker.facing[30]", rig.controller().label());
        assertTrue(rig.tool.takesScroll(rig.view, 0));
        rig.scroll(1, 0);
        assertEquals(List.of(new EntityEdit.Yaw(45)), rig.session.entities.get(0).edits());
        rig.scroll(1, Modifiers.SHIFT);
        rig.scroll(1, Modifiers.SHIFT);
        assertEquals(1, rig.session.entities.size(), "one in flight");
        assertEquals("Armor Stand · sculptory.tinker.facing[47]", rig.controller().label(), "the turns waiting count");
        rig.session.entities.get(0).reply().complete(Reply.ok(standView(45)));
        assertEquals(List.of(new EntityEdit.Yaw(47)), rig.session.entities.get(1).edits(), "merged: the latest turn");

        UUID frame = UUID.randomUUID();
        rig.services.entity = new TinkerController.EntityTarget(frame, TinkerKind.ITEM_FRAME, "Item Frame", 0f, 7, "",
                new double[] {0, 64, 0, 1, 65, 0.1});
        rig.hover(on(0, 64, 0));
        assertEquals("Item Frame · sculptory.tinker.item_turned[315]", rig.controller().label());
        rig.session.entities.get(1).reply().complete(Reply.ok(standView(47)));
        rig.scroll(1, 0);
        assertEquals(List.of(new EntityEdit.ItemRotation(0)), rig.session.entities.get(2).edits(), "going round");

        rig.services.entity = new TinkerController.EntityTarget(UUID.randomUUID(), TinkerKind.PAINTING, "Painting", 0f, 0,
                "minecraft:kebab", new double[] {0, 64, 0, 1, 65, 0.1});
        rig.hover(on(0, 64, 0));
        assertEquals("Painting · kebab", rig.controller().label());
        assertFalse(rig.tool.takesScroll(rig.view, 0), "Scroll does nothing over a painting");
    }

    @Test
    void aClickOnAnEntityAsksWhatThePanelShowsAndEditsMergeByKind() {
        Rig rig = new Rig();
        UUID stand = UUID.randomUUID();
        rig.services.entity = new TinkerController.EntityTarget(stand, TinkerKind.ARMOR_STAND, "Armor Stand", 0f, 0, "",
                new double[] {0, 64, 0, 1, 66, 1});
        assertTrue(rig.click(on(0, 64, 0)));
        assertEquals(1, rig.services.panels);
        assertEquals(List.of(), rig.session.entities.get(0).edits(), "a look");
        assertTrue(rig.controller().view(stand).isEmpty());
        rig.controller().edit(stand, List.of(new EntityEdit.Toggle(EntityEdit.Flag.SMALL, true)));
        rig.controller().edit(stand, List.of(new EntityEdit.Pose(EntityEdit.Part.HEAD, 10, 0, 0)));
        rig.controller().edit(stand, List.of(new EntityEdit.Toggle(EntityEdit.Flag.SMALL, false)));
        rig.controller().edit(stand, List.of(new EntityEdit.Pose(EntityEdit.Part.LEFT_ARM, 5, 0, 0)));
        EntityView view = standView(0);
        rig.session.entities.get(0).reply().complete(Reply.ok(view));
        assertSame(view, rig.controller().view(stand).orElseThrow());
        assertEquals(List.of(new EntityEdit.Pose(EntityEdit.Part.HEAD, 10, 0, 0),
                new EntityEdit.Toggle(EntityEdit.Flag.SMALL, false), new EntityEdit.Pose(EntityEdit.Part.LEFT_ARM, 5, 0, 0)),
                rig.session.entities.get(1).edits(), "one request, the latest of each setting");
        rig.session.entities.get(1).reply().complete(Reply.refused(RejectReason.INVALID,
                "the entity is gone (removed or out of reach)"));
        assertTrue(rig.controller().view(stand).isEmpty(), "a gone entity has no view");
        assertNull(rig.controller().hovered().filter(TinkerController.BlockTarget.class::isInstance).orElse(null));
    }

    // ---------------------------------------------------------------- the shared controller, as builder mode drives it

    @Test
    void aimPicksTheEntityBeforeTheBlockAndOutlinesWhatIsHovered() {
        Rig rig = new Rig();
        TinkerController controller = rig.controller();
        controller.aim(on(0, 64, 0));
        assertEquals(new TinkerController.BlockTarget(new BlockPos(0, 64, 0)), controller.hovered().orElseThrow());
        assertArrayEquals(new double[] {0, 64, 0, 1, 65, 1}, controller.outline().orElseThrow());
        assertEquals("oak_stairs · shape: straight", controller.label());
        rig.services.entity = new TinkerController.EntityTarget(UUID.randomUUID(), TinkerKind.ARMOR_STAND, "Armor Stand",
                0f, 0, "", new double[] {0.2, 64, 0.2, 0.8, 66, 0.8});
        controller.aim(on(0, 64, 0));
        assertEquals(rig.services.entity, controller.hovered().orElseThrow(), "the entity the ray meets first wins");
        assertArrayEquals(new double[] {0.2, 64, 0.2, 0.8, 66, 0.8}, controller.outline().orElseThrow());
        rig.services.entity = null;
        controller.aim(WorldCursor.miss(0, 0, 0));
        assertTrue(controller.hovered().isEmpty(), "nothing under the crosshair");
        assertTrue(controller.outline().isEmpty());
        assertEquals("", controller.label());
        controller.aim(on(0, 64, 0));
        controller.aim(null);
        assertTrue(controller.hovered().isEmpty(), "Alt released");
        assertFalse(controller.takesScroll());
    }

    @Test
    void arrowsAndTurnsStepFromTheChangeOnItsWayNotFromTheLastView() {
        Rig rig = new Rig();
        TinkerController controller = rig.controller();
        UUID id = UUID.randomUUID();
        controller.look(id);
        rig.session.entities.get(0).reply().complete(Reply.ok(standView(0)));
        double[] start = controller.positionOf(id, new double[3]);
        assertArrayEquals(new double[] {0.5, 65, 0.5}, start, "from the view");
        for (int click = 0; click < 2; click++) {
            double[] at = controller.positionOf(id, start);
            controller.edit(id, List.of(new EntityEdit.Position(at[0] + 1.0 / 16, at[1], at[2])));
        }
        assertEquals(2, rig.session.entities.size(), "the first move is on its way, the second waits");
        assertEquals(0.625, controller.positionOf(id, start)[0], 1e-9, "shown as two steps already");
        controller.edit(id, List.of(new EntityEdit.Yaw(controller.yawOf(id, 0f) + 15)));
        controller.edit(id, List.of(new EntityEdit.Yaw(controller.yawOf(id, 0f) + 15)));
        assertEquals(30f, controller.yawOf(id, 0f), "two turns");
        rig.session.entities.get(1).reply().complete(Reply.ok(standView(0)));
        assertEquals(3, rig.session.entities.size(), "what waited went as one request");
        List<EntityEdit> sent = rig.session.entities.get(2).edits();
        EntityEdit.Position moved = (EntityEdit.Position) sent.get(0);
        assertEquals(0.625, moved.x(), 1e-9, "two clicks: +2/16, not +1/16 twice from the same view");
        assertEquals(30f, ((EntityEdit.Yaw) sent.get(1)).degrees());
    }

    @Test
    void clickGivesThePanelTargetOnlyWhenAChangeMayBeSent() {
        Rig rig = new Rig();
        TinkerController controller = rig.controller();
        assertTrue(controller.click().isEmpty(), "nothing aimed at");
        controller.aim(on(0, 64, 0));
        assertEquals(new TinkerController.BlockTarget(new BlockPos(0, 64, 0)), controller.click().orElseThrow());
        assertTrue(rig.noticeKeys().isEmpty());
        rig.session.mock.setPermissions(without(Perm.REGION));
        assertTrue(controller.click().isEmpty(), "without region");
        assertEquals(List.of("sculptory.notice.needs_permission"), rig.noticeKeys());
        rig.notices.clear();
        rig.session.mock.setPermissions(without());
        rig.session.offered = false;
        assertFalse(controller.allowed(), "a server without Tinker");
        assertTrue(controller.click().isEmpty());
        assertEquals(List.of(TinkerController.NOT_OFFERED, TinkerController.NOT_OFFERED), rig.noticeKeys());
        assertTrue(rig.session.blocks.isEmpty(), "nothing sent");
        rig.session.offered = true;
        assertTrue(controller.scroll(1, false), "the notch is Tinker's");
        assertEquals(1, rig.session.blocks.size());
        assertTrue(controller.scroll(1, true), "Shift+Scroll picks a property without a change");
        assertEquals(1, rig.session.blocks.size());
    }
}
