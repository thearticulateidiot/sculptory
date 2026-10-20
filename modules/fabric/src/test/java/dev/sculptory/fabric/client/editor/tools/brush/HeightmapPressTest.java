package dev.sculptory.fabric.client.editor.tools.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushKernel;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SymmetricStep;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.brush.BrushPredictor;
import dev.sculptory.fabric.client.editor.brush.BrushTerrain;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.render.BrushCursor;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.FabricEditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.ProtocolV2;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.engine.Perm;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * A Heightmap press over the cursor ray the editor casts every frame (a voxel walk over the world as the tool's overlay
 * shows it), with the server writing each dab as it arrives and the client seeing its blocks: a click held still stamps
 * once, predicted or written by the server; a shake adds nothing; a drag lays its stamps on the ground as it was; the
 * next press builds on the last. The flowing brushes still repeat while held.
 */
class HeightmapPressTest {
    private static final long MS = 1_000_000L;
    /** The first air above the flat ground. */
    private static final int GROUND = 62;
    /** How far the ground reaches each way: past a radius-128 stamp. */
    private static final int EXTENT = 140;

    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");

    /** From a camera far south and 38 blocks up, down to the middle of the ground's top on column (x, z). */
    private static Ray at(int x, int z) {
        double fromX = 0.5, fromY = 100, fromZ = -180.5;
        return new Ray(fromX, fromY, fromZ, x + 0.5 - fromX, GROUND - fromY, z + 0.5 - fromZ);
    }

    /** From the same camera, {@code degrees} below the horizon straight north. */
    private static Ray pitched(double degrees) {
        double a = Math.toRadians(degrees);
        return new Ray(0.5, 100, -180.5, 0, -Math.sin(a), Math.cos(a));
    }

    /** The pitch of {@link #at}(0, 0). */
    private static final double DOWN = Math.toDegrees(Math.atan2(100 - GROUND, 181));

    // ---------------------------------------------------------------- a click held still

    /**
     * A reported bug: holding a click on the Heightmap brush for more than a moment stamped again and again. Each stamp
     * raised a hill under the cursor, the ray cast every frame met its near side, the aim jumped toward the camera by more
     * than the spacing, and another stamp went out. Predicted (radius 8 and 32) or written by the server (64, 128).
     */
    @Test
    void aClickHeldStillStampsOnce() throws ProtocolException {
        for (int radius : new int[] {8, 32, 64, 128}) {
            Live live = new Live(BrushTool.HEIGHTMAP);
            live.set(live.tool.settings().radius, radius);
            Ray ray = at(0, 0);
            WorldCursor pressed = live.cast(ray);
            live.press(ray);
            boolean predicted = false;
            for (int i = 0; i < 40; i++) {
                live.frame(ray, 50);
                predicted |= live.tool.predictor() != null;
                assertEquals(pressed, live.cast(ray), radius + ": the ray looks through the stamp");
            }
            assertNotNull(live.tool.rayOverlay());
            live.release(ray);
            assertEquals(radius <= 8, predicted, radius + ": predicted on the client");
            assertEquals(1, live.dabs().size(), radius + ": one stamp: " + live.dabs());
            assertEquals(1, live.begins(), radius + ": one stroke, one undo step");
            assertEquals(GROUND + 15, live.top(0, 0), radius + ": the hill's top, 16 blocks over the ground");
            assertNull(live.tool.rayOverlay(), radius + ": after the press the cursor sees the hill");
            assertTrue(live.cast(ray).pos().z() < -2, radius + ": and lands on it, nearer: " + live.cast(ray).pos());
        }
    }

    /** Lowering, the ray would fall into the pit the stamp cut, farther from the camera: held still, it stamps once too. */
    @Test
    void aLoweringClickHeldStillStampsOnce() throws ProtocolException {
        Live live = new Live(BrushTool.HEIGHTMAP, 24);
        live.set(live.tool.settings().radius, 8);
        live.set(live.tool.settings().heightmapMode, BrushSettings.StampMode.LOWER);
        Ray ray = at(0, 0);
        WorldCursor pressed = live.cast(ray);
        live.press(ray);
        for (int i = 0; i < 40; i++) {
            live.frame(ray, 50);
            assertEquals(pressed, live.cast(ray), "the ray looks through the pit");
        }
        live.release(ray);
        assertEquals(1, live.dabs().size(), "one stamp: " + live.dabs());
        assertEquals(GROUND - 17, live.top(0, 0), "the pit's floor, 16 blocks down");
        assertTrue(live.cast(ray).pos().z() > 2, "after the press the cursor falls into it: " + live.cast(ray).pos());
    }

    /** A shake of a tenth of a degree each way moves the hit about 1.5 blocks, past a radius-4 stamp's spacing. */
    @Test
    void aShakeUnderTheThresholdAddsNothingAndAMovePaints() throws ProtocolException {
        Live live = new Live(BrushTool.HEIGHTMAP);
        live.set(live.tool.settings().radius, 4);
        Ray still = pitched(DOWN);
        live.press(still);
        live.frame(still, 50);
        for (int i = 0; i < 20; i++) {
            Ray shaken = pitched(DOWN + (i % 2 == 0 ? -0.1 : 0.1));
            live.drag(shaken);
            live.frame(shaken, 50);
        }
        assertEquals(1, live.dabs().size(), "one stamp: " + live.dabs());
        Ray moved = pitched(DOWN + 1);
        live.drag(moved);
        live.frame(moved, 50);
        live.release(moved);
        assertTrue(live.dabs().size() > 1, "the move painted: " + live.dabs());
        assertEquals(1, live.begins());
    }

    /** Ctrl+Scroll while held still restarts the stroke with the new size but stamps nothing more; a move then paints. */
    @Test
    void aRadiusChangeWhileHeldStillStampsNothingMore() throws ProtocolException {
        Live live = new Live(BrushTool.HEIGHTMAP);
        live.set(live.tool.settings().radius, 8);
        live.press(at(0, 0));
        for (int i = 0; i < 5; i++) live.frame(at(0, 0), 50);
        assertTrue(live.scroll(1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertTrue(live.scroll(1, Modifiers.CONTROL | Modifiers.SHIFT));
        assertEquals(16, live.setting(live.tool.settings().radius));
        for (int i = 0; i < 20; i++) live.frame(at(0, 0), 50);
        assertEquals(1, live.dabs().size(), "one stamp: " + live.dabs());
        for (int x = 1; x <= 12; x++) live.dragTo(at(x, 0));
        live.release(at(12, 0));
        assertTrue(live.dabs().size() > 1, "the drag painted");
        List<C2S.StrokeBegin> begins = live.sent(C2S.StrokeBegin.class);
        assertEquals(8, begins.get(0).spec().radius());
        assertEquals(16, begins.get(begins.size() - 1).spec().radius());
    }

    // ---------------------------------------------------------------- drags

    /**
     * A drag lays its stamps on the ground the cursor points at, a quarter of the radius apart, as it was before the
     * press: never on the near side of the hills it raised, there and back again. Predicted and server-written.
     */
    @Test
    void aDragLaysItsStampsOnTheGroundAsItWas() throws ProtocolException {
        for (int radius : new int[] {8, 64}) {
            Live live = new Live(BrushTool.HEIGHTMAP);
            live.set(live.tool.settings().radius, radius);
            int span = Math.min(2 * radius, 64); // within the ray's reach from the camera
            live.press(at(-span, 0));
            live.frame(at(-span, 0), 50);
            for (int x = -span + 1; x <= span; x++) live.dragTo(at(x, 0));
            int there = live.dabs().size();
            // One where the press began and one every quarter of the radius (the last may fall a hair short of the end).
            int along = 2 * span / (radius / 4);
            assertTrue(there == along || there == along + 1, radius + ": " + 2 * span + " blocks, " + radius / 4
                    + " apart: " + live.dabs());
            for (int x = span - 1; x >= -span; x--) live.dragTo(at(x, 0));
            live.release(at(-span, 0));
            List<Dab> dabs = live.dabs();
            assertTrue(dabs.size() >= 2 * there - 2, radius + ": and back over its hills: " + dabs.size());
            for (Dab dab : dabs) {
                assertTrue(onGround(dab), radius + ": on the ground as it was: " + dab);
                assertTrue(Math.abs(dab.z16() - 8) <= 1, radius + ": where the cursor points: " + dab);
            }
            assertEquals(1, live.begins(), radius + ": one stroke");
            assertTrue(live.top(0, 0) > GROUND + 10, radius + ": a ridge was raised");
        }
    }

    /** Esc mid-drag stops the stroke: what was stamped stays, the cursor sees it again, and nothing more goes out. */
    @Test
    void escMidDragStopsAndTheCursorSeesTheHillsAgain() throws ProtocolException {
        Live live = new Live(BrushTool.HEIGHTMAP);
        live.set(live.tool.settings().radius, 8);
        live.press(at(-8, 0));
        live.frame(at(-8, 0), 50);
        for (int x = -7; x <= 0; x++) live.dragTo(at(x, 0));
        int stamped = live.dabs().size();
        assertTrue(stamped >= 3, "stamps: " + stamped);
        assertTrue(live.tool.onAction(live.view, EditorAction.CANCEL));
        assertNull(live.tool.rayOverlay());
        assertTrue(live.cast(at(0, 0)).pos().y() >= GROUND, "the ray meets the hills again");
        for (int x = 1; x <= 8; x++) live.dragTo(at(x, 0));
        live.release(at(8, 0));
        assertEquals(stamped, live.dabs().size(), "nothing after Esc");
        assertEquals(1, live.begins());
    }

    /** Each press looks through its own stamp only: a second click at the same spot lands on the first hill. */
    @Test
    void theNextPressBuildsOnTheLastPresssHill() throws ProtocolException {
        Live live = new Live(BrushTool.HEIGHTMAP);
        live.set(live.tool.settings().radius, 8);
        for (int press = 0; press < 2; press++) {
            live.press(at(0, 0));
            for (int i = 0; i < 10; i++) live.frame(at(0, 0), 50);
            live.release(at(0, 0));
        }
        List<Dab> dabs = live.dabs();
        assertEquals(2, dabs.size());
        assertTrue(onGround(dabs.get(0)));
        assertTrue(dabs.get(1).y16() > 16 * GROUND, "on the first hill's near side: " + dabs.get(1));
        assertTrue(dabs.get(1).z16() < 0, "nearer the camera: " + dabs.get(1));
        assertEquals(2, live.begins(), "two presses, two undo steps");
    }

    // ---------------------------------------------------------------- the other brushes

    /**
     * The flowing brushes are as they were: a Raise held still repeats its dab every 150 ms and its ray sees what it
     * raised. Paint held still paints once: it changes no height the cursor meets.
     */
    @Test
    void aHeldRaiseStillRepeatsAndAHeldPaintPaintsOnce() throws ProtocolException {
        Live raise = new Live(BrushTool.RAISE);
        raise.set(raise.tool.settings().radius, 4);
        raise.press(at(0, 0));
        for (int i = 0; i < 40; i++) {
            raise.frame(at(0, 0), 50);
            assertNull(raise.tool.rayOverlay());
        }
        raise.release(at(0, 0));
        assertTrue(raise.dabs().size() >= 12, "two seconds of flow: " + raise.dabs().size());
        assertTrue(raise.top(0, 0) > GROUND - 1, "the ground rose");

        Live paint = new Live(BrushTool.PAINT);
        paint.set(paint.tool.settings().material, BlockDescriptor.parse("minecraft:dirt"));
        paint.press(at(0, 0));
        for (int i = 0; i < 40; i++) paint.frame(at(0, 0), 50);
        paint.release(at(0, 0));
        assertEquals(1, paint.dabs().size(), "one dab: " + paint.dabs());
        assertNull(paint.tool.rayOverlay());
    }

    // ---------------------------------------------------------------- fixtures

    /** Whether a dab lies on the flat ground's top (the hit point, to a sixteenth of a block). */
    private static boolean onGround(Dab dab) {
        return Math.abs(dab.y16() - 16 * GROUND) <= 1;
    }

    /**
     * One brush over flat ground on a Fabric session whose predictions land in the client world, with a server that
     * writes each batch of dabs as it arrives into its own world and sends the client the blocks it changed (as the
     * game's block updates), then reports them written.
     */
    private final class Live {
        final FakeWorld world;
        final FakeWorld server;
        final FakeTransport transport = new FakeTransport(states);
        final FabricEditorSession fabric = new FabricEditorSession(transport, () -> states, new AtomicLong()::get, "test");
        final FakeServices services = new FakeServices();
        final List<Notice> notices = new ArrayList<>();
        final EditorContext ctx;
        final TerrainBrushTool tool;
        final ToolContext view;
        /** The server's state of each stroke, and how far it has read the client's messages. */
        private final Int2ObjectOpenHashMap<BrushSpec> specs = new Int2ObjectOpenHashMap<>();
        private final Int2ObjectOpenHashMap<StrokeState> strokes = new Int2ObjectOpenHashMap<>();
        private int read;
        long now = 1_000 * MS;

        Live(BrushTool kind) throws ProtocolException {
            this(kind, 6);
        }

        /** Over flat stone {@code depth} blocks deep. */
        Live(BrushTool kind, int depth) throws ProtocolException {
            world = flat(depth);
            server = flat(depth);
            fabric.onJoin();
            fabric.onFrame(Codec.encodeS2C(new S2C.Welcome(ProtocolV2.VERSION, Features.of(Features.STROKES),
                    Limits.DEFAULTS, Perm.mask(EnumSet.allOf(Perm.class)), 1L), states));
            services.target = new BrushTerrain.RecordingTarget(world);
            EditorBackend backend = new EditorBackend() {
                @Override
                public Optional<EditorSession> session() {
                    return Optional.of(fabric);
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
            tool = new TerrainBrushTool(kind, services);
            ctx.tools().register(tool);
            view = ctx.contextFor(tool.descriptor().id());
            assertTrue(ctx.tools().activate(tool.descriptor().id(), view));
        }

        private FakeWorld flat(int depth) {
            FakeWorld flat = new FakeWorld(states);
            flat.fill(new Box(new BlockPos(-EXTENT, GROUND - depth, -EXTENT), new BlockPos(EXTENT, GROUND - 1, EXTENT)),
                    stone);
            flat.setHeightHints(true);
            return flat;
        }

        /** The editor's pick for the tool: the ray over the world as the tool's overlay shows it. */
        WorldCursor cast(Ray ray) {
            services.ray = ray;
            return ShapeBrushToolTest.RayCast.terrain(world, tool.rayOverlay(), ray);
        }

        void press(Ray ray) {
            assertTrue(tool.onPointer(view, new PointerEvent(PointerEvent.Kind.PRESS, PointerEvent.LEFT, 0, 0, 0,
                    cast(ray))));
        }

        void drag(Ray ray) {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.DRAG, PointerEvent.LEFT, 0, 0, 0, cast(ray)));
        }

        void release(Ray ray) throws ProtocolException {
            tool.onPointer(view, new PointerEvent(PointerEvent.Kind.RELEASE, PointerEvent.LEFT, 0, 0, 0, cast(ray)));
            fabric.tick();
            serve();
        }

        /** A drag event to {@code ray}, then a tenth of a second of frames. */
        void dragTo(Ray ray) throws ProtocolException {
            drag(ray);
            frame(ray, 100);
        }

        /** One frame: the cursor cast over the world as it is now, a client tick that sends and predicts, the server. */
        void frame(Ray ray, long millis) throws ProtocolException {
            now += millis * MS;
            tool.frame(view, new FrameInfo(now, 0f, 0, 0, cast(ray)));
            fabric.tick();
            serve();
        }

        /** The server reads what the client sent, writes each dab (the client sees the blocks), and reports it written. */
        private void serve() throws ProtocolException {
            for (; read < transport.sent.size(); read++) {
                C2S message = transport.sent.get(read);
                if (message instanceof C2S.StrokeBegin begin) {
                    specs.put(begin.strokeId(), begin.spec());
                    strokes.put(begin.strokeId(), new StrokeState());
                } else if (message instanceof C2S.Dabs batch) {
                    BrushSpec spec = specs.get(batch.strokeId());
                    BrushKernel kernel = BrushKernels.forTool(spec.tool());
                    for (Dab dab : batch.dabs()) {
                        kernel.applyStep(spec, SymmetricStep.of(spec, dab, server).dabs(), strokes.get(batch.strokeId()),
                                server, (x, y, z, h) -> {
                                    server.set(x, y, z, h);
                                    world.set(x, y, z, h);
                                });
                    }
                    int last = batch.dabs().get(batch.dabs().size() - 1).index();
                    fabric.onFrame(Codec.encodeS2C(new S2C.StrokeStatus(batch.strokeId(), last, S2C.StrokeStatus.Status.OK,
                            null, last), states));
                }
            }
        }

        boolean scroll(double amount, int modifiers) {
            return tool.onScroll(view, new ScrollEvent(amount, modifiers));
        }

        <T> void set(SettingDef<T> def, T value) {
            ToolId id = tool.descriptor().id();
            ctx.updateSettings(id, ctx.settings(id).with(def, value));
        }

        <T> T setting(SettingDef<T> def) {
            return ctx.settings(tool.descriptor().id()).get(def);
        }

        /** The highest non-air cell of column (x, z) in the client world. */
        int top(int x, int z) {
            for (int y = 200; y >= world.bottomY(); y--) {
                if (world.get(x, y, z) != states.air()) return y;
            }
            return world.bottomY() - 1;
        }

        <T extends C2S> List<T> sent(Class<T> kind) {
            return transport.sent.stream().filter(kind::isInstance).map(kind::cast).toList();
        }

        /** Server strokes begun: one per press (and per restart). */
        int begins() {
            return sent(C2S.StrokeBegin.class).size();
        }

        List<Dab> dabs() {
            return sent(C2S.Dabs.class).stream().flatMap(b -> b.dabs().stream()).toList();
        }
    }

    private static final class FakeServices implements BrushServices {
        final EditorKeymap keymap = EditorKeymap.defaults();
        BrushPredictor.Target target;
        Ray ray;

        @Override
        public BrushPredictor.Target predictionTarget() {
            return target;
        }

        @Override
        public void showCursor(BrushCursor cursor) {}

        @Override
        public void clearCursor() {}

        @Override
        public long changeStamp() {
            return 0;
        }

        @Override
        public boolean windowFocused() {
            return true;
        }

        @Override
        public Optional<KeyAction> scrollAction(int modifiers) {
            return keymap.match(KeyChord.scroll(modifiers));
        }

        @Override
        public String keyLabel(KeyAction action) {
            return keymap.display(action);
        }

        @Override
        public long nextSeed() {
            return 42L;
        }

        @Override
        public Optional<Ray> cursorRay() {
            return Optional.ofNullable(ray);
        }
    }

    private static final class FakeTransport implements FabricEditorSession.Transport {
        final StateSpace states;
        final List<C2S> sent = new ArrayList<>();

        FakeTransport(StateSpace states) {
            this.states = states;
        }

        @Override
        public boolean canSend() {
            return true;
        }

        @Override
        public void send(byte[] frame) {
            try {
                sent.add(Codec.decodeC2S(frame, states));
            } catch (ProtocolException e) {
                throw new AssertionError("The client sent an undecodable frame", e);
            }
        }
    }
}
