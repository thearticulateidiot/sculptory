package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor.Face;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import org.lwjgl.glfw.GLFW;

/**
 * Two real clients on the project test server (port 25580): player A ({@code BuilderDev}, op) and player B
 * ({@code Guest}, not op), each running its half of the script and waiting for the other through {@link Sync} files.
 * A builds a floor with commands and edits it; B's client must show A's edit; B's editor is refused until A ops B;
 * both fill overlapping boxes at once and both clients must then show the same world; each undo takes back only its
 * own player's work; a deop closes B's editor. A stops the server at the end.
 */
final class TwoPlayerChecks {
    /** The floor: 32 x 32 at this height, 64 blocks east and south of spawn (outside spawn protection). */
    private static final int Y0 = 150;
    private static final int SIZE = 32;
    private static final String GUEST = "Guest";
    private static final long JOIN_TIMEOUT_MS = 300_000;
    private static final long STEP_TIMEOUT_MS = 120_000;

    static final ScenarioGroup GROUP_A = new ScenarioGroup(Set.of(CheckConfig.Role.A), List.of(),
            List.of(new Scenario("two-players", "Player A (op): edits B watches, ops and deops B, overlapping edits,"
                    + " per-player undo", false, null, TwoPlayerChecks::playerA)));
    static final ScenarioGroup GROUP_B = new ScenarioGroup(Set.of(CheckConfig.Role.B), List.of(),
            List.of(new Scenario("two-players", "Player B (not op): sees A's edits, is refused, then edits beside A",
                    false, null, TwoPlayerChecks::playerB)));

    /** A's first box, then the two overlapping boxes (relative to the floor's corner). */
    private static final int[] FIRST = {4, 4, 9, 9};
    private static final int[] BOX_A = {10, 10, 17, 13};
    private static final int[] BOX_B = {14, 12, 21, 15};

    private TwoPlayerChecks() {}

    private static Sync sync(CheckRun run) {
        return new Sync(run.config().dir().getParent().resolve("sync"),
                ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    // ---- Player A ----

    private static void playerA(CheckRun run) {
        CheckDriver d = run.driver();
        Sync sync = sync(run);
        boolean joined = d.until(JOIN_TIMEOUT_MS, () -> d.client().getNetworkHandler() != null
                && d.client().getNetworkHandler().getPlayerListEntry(GUEST) != null);
        run.require("player B (" + GUEST + ") joins the server", joined, "not in the player list after "
                + JOIN_TIMEOUT_MS / 1000 + " s");
        int[] spawn = d.onClient(() -> {
            var pos = d.client().world.getSpawnPos();
            return new int[] {pos.getX(), pos.getZ()};
        });
        int x0 = ((spawn[0] + 64) >> 4) << 4;
        int z0 = ((spawn[1] + 64) >> 4) << 4;
        for (String command : List.of("gamerule doDaylightCycle false", "gamerule doWeatherCycle false",
                "gamerule randomTickSpeed 0", "gamerule doMobSpawning false", "time set noon", "weather clear",
                "effect give @a minecraft:night_vision infinite 0 true",
                String.format(Locale.ROOT, "forceload add %d %d %d %d", x0, z0, x0 + SIZE - 1, z0 + SIZE - 1))) {
            d.command(command);
        }
        String rain = d.clearClientWeather();
        run.check("the weather is clear on A's client", rain.isEmpty(), rain);
        d.view(x0 + 10.5, Y0 + 14, z0 + 32.5, x0 + 12.5, Y0, z0 + 12.5);
        d.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air", x0, Y0, z0, x0 + SIZE - 1,
                Y0 + 12, z0 + SIZE - 1));
        d.command(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:stone", x0, Y0 - 3, z0,
                x0 + SIZE - 1, Y0 - 1, z0 + SIZE - 1));
        d.command(String.format(Locale.ROOT, "tp %s %.1f %d %.1f 0 55", GUEST, x0 + 16.5, Y0 + 16, z0 + 28.5));
        Box floor = Box.of(new dev.sculptory.core.BlockPos(x0, Y0 - 1, z0),
                new dev.sculptory.core.BlockPos(x0 + SIZE - 1, Y0 - 1, z0 + SIZE - 1));
        boolean built = d.until(20_000, () -> Cells.of(d.client().world, floor).count(state -> !state.isOf(
                net.minecraft.block.Blocks.STONE)) == 0);
        run.require("the floor built with /fill shows", built, "");
        sync.signal("a-ready", x0 + " " + z0);
        run.require("B is ready", sync.await("b-ready", STEP_TIMEOUT_MS).isPresent(), "no b-ready");

        try {
            editsA(run, sync, x0, z0);
        } finally {
            // Whatever happened: B deopped, the chunks let go and the server stopped once B is done.
            d.command("deop " + GUEST);
            sync.signal("a-end", "");
            sync.await("b-done", STEP_TIMEOUT_MS);
            d.command("forceload remove all");
            d.command("stop");
        }
    }

    /** A's edits once B is ready. */
    private static void editsA(CheckRun run, Sync sync, int x0, int z0) {
        CheckDriver d = run.driver();
        // A fills a box; B must see it.
        d.enterEditor();
        d.selectTool(ToolId.SELECT);
        Box first = box(x0, z0, FIRST);
        selectLayer(run, first);
        d.activeBlock("minecraft:glass");
        run.edit("A's Fill", () -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill"));
        run.check("A's client shows its Fill", d.awaitClient(filled(d, first, "minecraft:glass")).isEmpty());
        sync.signal("a-fill1", "");
        String seen = sync.await("b-fill1", STEP_TIMEOUT_MS).orElse("no answer");
        run.check("B's client shows A's Fill", seen.startsWith("PASS"), seen);

        // Op B: its editor opens.
        d.command("op " + GUEST);
        sync.signal("a-opped", "");
        String editor = sync.await("b-editor", STEP_TIMEOUT_MS).orElse("no answer");
        run.check("B's editor opens once A ops B", editor.startsWith("PASS"), editor);

        // Overlapping Fills at once.
        Box boxA = box(x0, z0, BOX_A);
        Box boxB = box(x0, z0, BOX_B);
        selectLayer(run, boxA);
        d.activeBlock("minecraft:oak_planks");
        run.require("B is armed", sync.await("b-armed", STEP_TIMEOUT_MS).isPresent(), "no b-armed");
        long version = d.historyVersion();
        sync.signal("go", "");
        d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill");
        d.awaitEdit(version, "A's overlapping Fill");
        run.require("B's overlapping Fill is done", sync.await("b-filled", STEP_TIMEOUT_MS).isPresent(), "");
        Box both = union(boxA, boxB);
        d.millis(1_000);
        Cells afterFills = d.clientCells(both);
        String mine = describe(afterFills);
        sync.signal("a-snapshot", mine);
        String theirs = sync.await("b-snapshot", STEP_TIMEOUT_MS).orElse("");
        run.check("after the overlapping Fills both clients show the same world", mine.equals(theirs),
                "A: " + head(mine) + " / B: " + head(theirs));
        run.check("every cell of both boxes holds one of the two Fills", onlyFills(d.clientCells(both), boxA, boxB),
                head(mine));
        run.picture("overlap", "A's planks and B's cobblestone boxes overlapping on the floor");

        // A undoes: B's cobblestone stays whole, A's planks outside it go.
        run.undo("A's undo");
        Cells.Editor expected = d.clientCells(both).edit();
        for (Box b : List.of(boxA)) {
            forEach(b, (x, y, z) -> expected.set(x, y, z, net.minecraft.block.Blocks.AIR.getDefaultState()));
        }
        forEach(boxB, (x, y, z) -> expected.set(x, y, z, net.minecraft.block.Blocks.COBBLESTONE.getDefaultState()));
        List<Cells.Change> left = d.awaitClient(expected.done());
        run.check("A's undo takes back A's planks and keeps all of B's cobblestone", left.isEmpty(),
                Cells.summary(left, 4));
        sync.signal("a-undone", "");
        String bSaw = sync.await("b-saw-undo", STEP_TIMEOUT_MS).orElse("no answer");
        run.check("B's client shows A's undo the same way", bSaw.startsWith("PASS"), bSaw);
        run.require("B undid", sync.await("b-undone", STEP_TIMEOUT_MS).isPresent(), "");
        List<Cells.Change> after = d.awaitClient(afterBothUndos(afterFills, boxA, boxB));
        run.check("after B's undo too, each undo took back its own Fill (B's restoring what its Fill found)",
                after.isEmpty(), Cells.summary(after, 4));

        // Deop B: its editor closes.
        d.command("deop " + GUEST);
        sync.signal("a-deopped", "");
        String closed = sync.await("b-closed", STEP_TIMEOUT_MS).orElse("no answer");
        run.check("deop closes B's editor", closed.startsWith("PASS"), closed);
        run.undo("A's first Fill: undo");
    }

    // ---- Player B ----

    private static void playerB(CheckRun run) {
        CheckDriver d = run.driver();
        Sync sync = sync(run);
        String corner = sync.await("a-ready", JOIN_TIMEOUT_MS).orElse(null);
        run.require("player A sets the floor up", corner != null, "no a-ready");
        String[] parts = corner.strip().split(" ");
        int x0 = Integer.parseInt(parts[0]);
        int z0 = Integer.parseInt(parts[1]);
        d.onClient(() -> {
            ClientPlayerEntity player = d.client().player;
            player.getAbilities().flying = true;
            player.sendAbilitiesUpdate();
        });
        d.until(10_000, () -> Math.abs(d.client().player.getY() - (Y0 + 16)) < 0.5);
        d.turn(x0 + 16.5, Y0 + 16 + 1.62, z0 + 28.5, x0 + 14.5, Y0, z0 + 12.5);
        try {
            stepsB(run, sync, x0, z0);
        } finally {
            sync.signal("b-done", "");
        }
    }

    /** B's part once A has set the floor up. */
    private static void stepsB(CheckRun run, Sync sync, int x0, int z0) {
        CheckDriver d = run.driver();

        // A's "weather clear" reached this client too.
        String rain = d.clearClientWeather();
        run.check("the weather is clear on B's client", rain.isEmpty(), rain);

        // Not op: the editor key is refused, with the reason, as a toast the player can read over gameplay.
        // Vanilla's "Chat messages can't be verified" toast (ten seconds after joining) draws over it, so wait it out.
        run.check("vanilla's join toast has gone", d.awaitJoinToastGone(20_000), "still shown after 20 s");
        int mark = d.toastMark();
        d.key(GLFW.GLFW_KEY_B, 0);
        Optional<CheckDriver.Toast> refused = d.awaitToast(mark, "sculptory.notice.no_permission", 5_000);
        run.check("B (not op) pressing B gets \"no permission\"", refused.isPresent() && !d.onClient(() ->
                d.editor().mode().isActive()), refused.map(CheckDriver.Toast::text).orElse("no toast" + d.toastNote()));
        String readable = d.translate("sculptory.notice.no_permission");
        run.check("the refusal is the readable message", refused.map(t -> t.text().equals(readable)).orElse(false),
                "shown \"" + refused.map(CheckDriver.Toast::text).orElse("nothing") + "\", expected \"" + readable
                        + "\"");
        List<String> onScreen = d.shownMessages();
        run.check("the refusal is on B's screen", onScreen.contains(readable), "on screen: " + onScreen);
        run.picture("refused", "B's screen over gameplay (no editor): the toast at the top right reading \"" + readable
                + "\"");
        sync.signal("b-ready", "");

        // A's Fill shows in B's client.
        run.require("A filled", awaitA(sync, "a-fill1"), "no a-fill1");
        long started = System.currentTimeMillis();
        List<Cells.Change> missing = d.awaitClient(filled(d, box(x0, z0, FIRST), "minecraft:glass"));
        sync.signal("b-fill1", missing.isEmpty() ? "PASS after " + (System.currentTimeMillis() - started) + " ms"
                : "FAIL " + Cells.summary(missing, 4));
        run.check("B's client shows A's Fill", missing.isEmpty(), Cells.summary(missing, 4));

        // Opped: the editor opens.
        run.require("A opped B", awaitA(sync, "a-opped"), "no a-opped");
        boolean allowed = d.until(20_000, () -> d.editor().mode().entryRefusal().isEmpty());
        if (allowed) {
            d.enterEditor();
            d.standardEditor();
        }
        boolean open = d.onClient(() -> d.editor().mode().isActive());
        sync.signal("b-editor", open ? "PASS" : "FAIL " + d.onClient(() -> d.editor().mode().entryRefusal()
                .map(n -> n.key()).orElse("the editor did not open")));
        run.require("B's editor opens once opped", open, d.toastNote());

        // B's overlapping Fill, at A's go (an op now, B moves itself over its box).
        d.selectTool(ToolId.SELECT);
        Box boxB = box(x0, z0, BOX_B);
        d.overlook((boxB.min().x() + boxB.max().x()) / 2.0, Y0 - 1, (boxB.min().z() + boxB.max().z()) / 2.0);
        selectLayer(run, boxB);
        d.activeBlock("minecraft:cobblestone");
        sync.signal("b-armed", "");
        run.require("A says go", awaitA(sync, "go"), "no go");
        long version = d.historyVersion();
        d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill");
        d.awaitEdit(version, "B's overlapping Fill");
        sync.signal("b-filled", "");
        Box both = union(box(x0, z0, BOX_A), boxB);
        d.millis(1_000);
        Cells afterFills = d.clientCells(both);
        String mine = describe(afterFills);
        sync.signal("b-snapshot", mine);
        run.picture("overlap", "B's view of the two overlapping boxes");

        // A's undo keeps B's cobblestone.
        run.require("A undid", awaitA(sync, "a-undone"), "no a-undone");
        Cells.Editor expected = d.clientCells(both).edit();
        forEach(box(x0, z0, BOX_A), (x, y, z) -> expected.set(x, y, z, net.minecraft.block.Blocks.AIR.getDefaultState()));
        forEach(boxB, (x, y, z) -> expected.set(x, y, z, net.minecraft.block.Blocks.COBBLESTONE.getDefaultState()));
        List<Cells.Change> left = d.awaitClient(expected.done());
        sync.signal("b-saw-undo", left.isEmpty() ? "PASS" : "FAIL " + Cells.summary(left, 4));
        run.check("B's client shows A's undo keeping B's cobblestone", left.isEmpty(), Cells.summary(left, 4));

        // B's own undo empties both boxes.
        run.undo("B's undo");
        List<Cells.Change> after = d.awaitClient(afterBothUndos(afterFills, box(x0, z0, BOX_A), boxB));
        run.check("B's undo takes back B's cobblestone, restoring what B's Fill found", after.isEmpty(),
                Cells.summary(after, 4));
        sync.signal("b-undone", "");

        // Deopped: the editor closes.
        run.require("A deopped B", awaitA(sync, "a-deopped"), "no a-deopped");
        boolean closed = d.until(20_000, () -> !d.editor().mode().isActive());
        sync.signal("b-closed", closed ? "PASS" : "FAIL the editor stayed open");
        run.check("deop closes B's editor", closed, d.toastNote());
    }

    /** Waits for A's signal {@code name}; false as soon as A has ended (A's script stopped) or after the step timeout. */
    private static boolean awaitA(Sync sync, String name) {
        long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (sync.peek(name).isPresent()) {
                return true;
            }
            if (sync.peek("a-end").isPresent()) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ---- Helpers ----

    /**
     * Both boxes after A's undo and then B's (docs: an undo skips cells changed since, and restores what its edit
     * found): empty, but for the overlap when B's Fill came second. A's undo skipped it (B had changed it) and B's then
     * put back what B's Fill found there, A's planks. When A's Fill came second, A's undo put back B's cobblestone and
     * B's undo emptied it.
     */
    private static Cells afterBothUndos(Cells afterFills, Box boxA, Box boxB) {
        Cells.Editor expected = afterFills.edit();
        forEach(afterFills.box(), (x, y, z) -> {
            boolean overlap = boxA.contains(x, y, z) && boxB.contains(x, y, z);
            boolean bSecond = Cells.describe(afterFills.at(x, y, z)).equals("minecraft:cobblestone");
            expected.set(x, y, z, overlap && bSecond ? Fixtures.Build.state("minecraft:oak_planks")
                    : net.minecraft.block.Blocks.AIR.getDefaultState());
        });
        return expected.done();
    }

    private static Box box(int x0, int z0, int[] xz) {
        return Box.of(new dev.sculptory.core.BlockPos(x0 + xz[0], Y0, z0 + xz[1]),
                new dev.sculptory.core.BlockPos(x0 + xz[2], Y0, z0 + xz[3]));
    }

    private static Box union(Box a, Box b) {
        return Box.of(new dev.sculptory.core.BlockPos(Math.min(a.min().x(), b.min().x()),
                        Math.min(a.min().y(), b.min().y()), Math.min(a.min().z(), b.min().z())),
                new dev.sculptory.core.BlockPos(Math.max(a.max().x(), b.max().x()),
                        Math.max(a.max().y(), b.max().y()), Math.max(a.max().z(), b.max().z())));
    }

    /** Selects a one-high box on the floor: a drag over the floor under it, then PgUp. */
    private static void selectLayer(CheckRun run, Box layer) {
        CheckDriver d = run.driver();
        d.aim(layer.min().x(), layer.min().y() - 1, layer.min().z(), Face.UP);
        d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        d.dragTo(layer.max().x() + 0.5, layer.min().y(), layer.max().z() + 0.5, 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.action(KeyAction.NUDGE_UP);
        Optional<Box> selected = d.onClient(() -> d.ctx().selection());
        run.require("the selection is " + layer, selected.equals(Optional.of(layer)),
                selected.map(Box::toString).orElse("nothing"));
    }

    /** The client's cells of {@code box} with every cell {@code state}. */
    private static Cells filled(CheckDriver d, Box box, String state) {
        Cells.Editor expected = d.clientCells(box).edit();
        BlockState target = Fixtures.Build.state(state);
        forEach(box, (x, y, z) -> expected.set(x, y, z, target));
        return expected.done();
    }

    private static boolean onlyFills(Cells cells, Box a, Box b) {
        boolean[] ok = {true};
        forEach(cells.box(), (x, y, z) -> {
            String state = Cells.describe(cells.at(x, y, z));
            boolean inA = a.contains(x, y, z);
            boolean inB = b.contains(x, y, z);
            boolean fine = inA && inB ? state.equals("minecraft:oak_planks") || state.equals("minecraft:cobblestone")
                    : inA ? state.equals("minecraft:oak_planks") : !inB || state.equals("minecraft:cobblestone");
            ok[0] &= fine;
        });
        return ok[0];
    }

    /** The cells as text, one "x y z state" per non-air cell. */
    private static String describe(Cells cells) {
        StringBuilder out = new StringBuilder();
        forEach(cells.box(), (x, y, z) -> {
            BlockState state = cells.at(x, y, z);
            if (!state.isAir()) {
                out.append(x).append(' ').append(y).append(' ').append(z).append(' ').append(Cells.describe(state))
                        .append('\n');
            }
        });
        return out.toString();
    }

    private static String head(String text) {
        String[] lines = text.split("\n");
        return lines.length + " blocks" + (lines.length > 0 ? ", first " + lines[0] : "");
    }

    private static void forEach(Box box, SelectModeChecks.CellVisitor visitor) {
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    visitor.visit(x, y, z);
                }
            }
        }
    }
}
