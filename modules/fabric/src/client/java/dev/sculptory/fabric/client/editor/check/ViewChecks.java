package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.biomes.BiomeView;
import dev.sculptory.fabric.client.editor.commands.EditorCommands;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldCursor.Face;
import dev.sculptory.fabric.client.editor.tools.generate.GenerateTool;
import dev.sculptory.fabric.client.editor.tools.generate.LineDraft;
import dev.sculptory.fabric.client.editor.tools.generate.SmartSurfaceDraft;
import dev.sculptory.fabric.client.editor.tools.place.PlaceSettings;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.editor.tools.place.Placement;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.world.BoxFace;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.lwjgl.glfw.GLFW;

/**
 * What the newest tools show before they build, through the real client, so the render-mod runs (Sodium, Iris) have
 * pictures of them: a paste ghost turned by a free angle with its rotation rings, Biome view's tint and legend, Generate's
 * Smart Surface ghost and its mesh view, the Heightmap brush's preview square, a Line extended to the ground, and a
 * selection's face handle seen through the wall in front of it. Each also checks what it built (and its exact undo)
 * where it builds.
 */
final class ViewChecks {
    static final String VIEWS = "views";
    static final String TERRAIN = "terrain-tools";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String GOLD = "minecraft:gold_block";
    private static final String STONE = "minecraft:stone";
    private static final String BRICKS = "minecraft:stone_bricks";

    /** The bar of planks the free-rotation scenario copies (one layer, a gold block at its east end). */
    private static final int[] BAR = {4, 0, 4, 10, 0, 6};
    /** The selection whose east handle sits behind the wall at x 15. */
    private static final int[] HANDLE_BOX = {8, 0, 26, 12, 2, 30};
    private static final int WALL_X = 15;
    /** The Smart Surface outline: four pillars (x, z, height). */
    private static final int[][] PILLARS = {{4, 4, 2}, {16, 4, 5}, {16, 16, 1}, {4, 16, 3}};
    /** The Line's two pillars, 6 high, at z 10. */
    private static final int LINE_WEST = 24;
    private static final int LINE_EAST = 36;
    private static final int LINE_Z = 10;
    private static final int LINE_TOP = 5;

    static final ScenarioGroup GROUP = ScenarioGroup.solo(
            List.of(new Fixtures.Fixture(VIEWS, b -> {
                        b.fill(BAR[0], BAR[1], BAR[2], BAR[3], BAR[4], BAR[5], PLANKS);
                        b.set(BAR[3], BAR[1], BAR[2], GOLD);
                        b.fill(WALL_X, 0, 23, WALL_X, 4, 33, STONE);
                    }),
                    new Fixtures.Fixture(TERRAIN, b -> {
                        for (int[] pillar : PILLARS) {
                            b.fill(pillar[0], 0, pillar[1], pillar[0], pillar[2] - 1, pillar[1], STONE);
                        }
                        b.fill(LINE_WEST, 0, LINE_Z, LINE_WEST, LINE_TOP, LINE_Z, STONE);
                        b.fill(LINE_EAST, 0, LINE_Z, LINE_EAST, LINE_TOP, LINE_Z, STONE);
                    })),
            List.of(Scenario.visual("free-rotation", VIEWS, "A paste ghost turned 30° (typed angle, Exact count): its"
                            + " rotation rings; the paste keeps every block; one undo", ViewChecks::freeRotation),
                    Scenario.visual("biome-view", VIEWS, "View > Biome view: the ground tinted by biome and the"
                            + " Biomes in view legend; off again", ViewChecks::biomeView),
                    Scenario.visual("select-handle-through", VIEWS, "A selection's face handle behind a wall shows"
                            + " through it, is hovered and dragged there", ViewChecks::handleThroughWall),
                    Scenario.visual("smart-surface", TERRAIN, "Generate Smart Surface over four pillars: the ghost,"
                            + " the mesh view, Enter builds it; one undo", ViewChecks::smartSurface),
                    Scenario.visual("heightmap-brush", TERRAIN, "Heightmap brush: the preview square on the floor,"
                            + " one click stamps the hill; one undo", ViewChecks::heightmap),
                    Scenario.visual("line-ground", TERRAIN, "Generate Line between two pillar tops with Extend to"
                            + " ground: the ghost and the wall it builds down to the floor; one undo",
                            ViewChecks::lineToGround)));

    private ViewChecks() {}

    // ---- Free rotation ----

    private static void freeRotation(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -1, 0, 39, 4, 22);
        Cells before = run.server(area);
        Box bar = a.box(BAR[0], BAR[1], BAR[2], BAR[3], BAR[4], BAR[5]);
        d.selectTool(ToolId.SELECT);
        PlaceChecks.selectTopFaces(run, bar);
        PlaceChecks.copy(run);
        d.onClient(() -> d.ctx().setSelection(null));
        d.setting(ToolId.PLACE, PlaceSettings.METHOD_KEY, "EXACT");
        PlaceTool place = PlaceChecks.startPlacing(run, "paste");
        d.view(a.x(24) + 0.5, a.y(9), a.z(20) + 0.5, a.x(24) + 0.5, a.y(0), a.z(12) + 0.5);
        d.aim(a.x(24), a.y(-1), a.z(12), Face.UP);
        d.frames(5);
        d.click(0);
        run.require("the paste's ghost dropped where it was clicked",
                d.onClient(() -> place.placement().map(Placement::targetBox).isPresent()), "no placement");

        // The angle typed into Tool Settings' Yaw field (what its Enter calls): 30° about the vertical.
        boolean turned = d.onClient(() -> place.setAngles(3000, 0, 0));
        run.require("Yaw 30 turns the ghost", turned, d.toastNote());
        int[] angles = d.onClient(() -> place.angles().orElse(new int[0]));
        run.check("the placement is turned 30° about the vertical", Arrays.equals(angles, new int[] {3000, 0, 0}),
                Arrays.toString(angles));
        boolean baked = d.until(10_000, () -> place.bakedVolume().isPresent());
        run.check("the turned ghost is baked once the turn rests", baked, d.toastNote());
        d.frames(5);
        run.picture("rings", "The plank bar's ghost (gold block at one end) turned 30° on the floor, its three rotation"
                + " rings and move arrows around the pivot");

        run.edit("turned paste", () -> d.action(KeyAction.COMMIT));
        Cells pasted = run.server(area);
        List<Cells.Change> changes = before.diff(pasted);
        Predicate<Cells.Change> placed = c -> c.before().isAir()
                && (c.after().isOf(Blocks.OAK_PLANKS) || c.after().isOf(Blocks.GOLD_BLOCK));
        long blocks = bar.volume();
        run.check("Exact count: the turned paste places every block of the bar, each into air", changes.size() == blocks
                && Changes.offenders(changes, placed).isEmpty(), changes.size() + " of " + blocks + " cells; "
                + Changes.offenders(changes, placed));
        run.check("one gold block among them", changes.stream().filter(c -> c.after().isOf(Blocks.GOLD_BLOCK))
                .count() == 1, "");
        run.clientMatches("the turned paste", pasted);
        run.picture("pasted", "The plank bar pasted turned 30°: a stepped diagonal of planks, the gold block at one end");
        run.undo("undo the turned paste");
        run.exact("undo takes the turned paste back", before);
    }

    // ---- Biome view ----

    private static void biomeView(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        d.view(a.x(20) + 0.5, a.y(22), a.z(46) + 0.5, a.x(20) + 0.5, a.y(0), a.z(18) + 0.5);
        d.onClient(() -> d.ui().commands().run(EditorCommands.VIEW_BIOMES));
        try {
            boolean tinted = d.until(10_000, () -> d.ui().biomeView().tint()
                    .map(tint -> tint.size() > 0 && !tint.legend().isEmpty()).orElse(false));
            List<String> legend = d.onClient(() -> d.ui().biomeView().tint().map(BiomeView.Tint::legend)
                    .orElse(List.of()).stream().map(entry -> entry.name() + " " + entry.columns()).toList());
            run.check("Biome view tints the ground in view and lists its biomes", tinted, "legend " + legend);
            run.check("the Biomes in view window opens with it",
                    d.onClient(() -> d.ui().windows().isOpen(EditorWindows.BIOME_LEGEND)), "");
            d.frames(5);
            run.picture("tint-legend", "The floor and everything in view tinted in its biome's colour; the Biomes in"
                    + " view window listing " + legend);
        } finally {
            d.onClient(() -> {
                if (d.ui().biomeView().enabled()) {
                    d.ui().commands().run(EditorCommands.VIEW_BIOMES);
                }
            });
        }
        d.frames(2);
        run.check("Biome view off again closes the legend", d.onClient(() -> !d.ui().biomeView().enabled()
                && !d.ui().windows().isOpen(EditorWindows.BIOME_LEGEND)), "");
    }

    // ---- A handle through a wall ----

    private static void handleThroughWall(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box box = a.box(HANDLE_BOX[0], HANDLE_BOX[1], HANDLE_BOX[2], HANDLE_BOX[3], HANDLE_BOX[4], HANDLE_BOX[5]);
        d.selectTool(ToolId.SELECT);
        d.onClient(() -> d.ctx().setSelection(box));
        // From the east, above the wall's top, so the wall stands between the camera and the box's east face.
        d.view(a.x(25) + 0.5, a.y(6), a.z(28) + 0.5, a.x(12) + 0.5, a.y(1), a.z(28) + 0.5);
        double cx = box.max().x() + 1;
        double cy = (box.min().y() + box.max().y() + 1) / 2.0;
        double cz = (box.min().z() + box.max().z() + 1) / 2.0;
        d.pointAt(cx, cy, cz);
        d.frames(5);
        WorldCursor cursor = d.cursor();
        BoxFace hovered = d.onClient(() -> d.ctx().hoveredFace());
        run.check("the pointer on the east handle meets the wall first", !cursor.missed()
                && cursor.pos().x() == a.x(WALL_X), cursor.missed() ? "nothing" : cursor.pos().toString());
        run.require("the east handle behind the wall is hovered", hovered == BoxFace.EAST, "hovered " + hovered);
        run.pictureAtPointer("handle-through-wall", "The selection box behind the stone wall: its handles show dimmed"
                + " through the wall, the hovered east one lit");

        // Dragged two blocks east, still behind the wall.
        d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        d.dragTo(cx + 2, cy, cz, 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        Optional<Box> resized = d.onClient(() -> d.ctx().selection());
        run.check("dragging the hidden handle east grows the box east only", resized.isPresent()
                && resized.get().max().x() > box.max().x() && resized.get().min().equals(box.min())
                && resized.get().max().y() == box.max().y() && resized.get().max().z() == box.max().z(),
                "box " + resized.map(Box::toString).orElse("none"));
        d.onClient(() -> d.ctx().setSelection(null));
    }

    // ---- Smart Surface ----

    private static void smartSurface(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -1, 0, 22, 12, 22);
        Cells before = run.server(area);
        d.activeBlock("minecraft:moss_block");
        d.selectTool(ToolId.GENERATE);
        d.setting(ToolId.GENERATE, "kind", "SMART_SURFACE");
        d.setting(ToolId.GENERATE, "smart.mesh_view", false);
        // generateTool() hops onto the client thread itself, so it is not called from there.
        GenerateTool generate = d.generateTool();
        SmartSurfaceDraft draft = d.onClient(generate::smartSurface);
        d.clearPoints(draft::points);
        d.view(a.x(10) + 0.5, a.y(20), a.z(32) + 0.5, a.x(10) + 0.5, a.y(0), a.z(10) + 0.5);
        for (int[] pillar : PILLARS) {
            d.aim(a.x(pillar[0]), a.y(pillar[2] - 1), a.z(pillar[1]), Face.UP);
            d.click(0);
        }
        run.require("four outline points", d.onClient(() -> draft.points().nodes().size()) == PILLARS.length,
                d.onClient(() -> draft.points().nodes().size()) + " points" + d.toastNote());
        boolean ready = d.until(20_000, () -> !draft.previewRunning()
                && draft.preview().map(built -> built.ghost() != null).orElse(false));
        run.require("the surface's ghost shows", ready, d.toastNote());
        d.parkPointer();
        run.picture("ghost", "A moss sheet stretched over the four stone pillars (heights 2, 5, 1, 3), rising to each"
                + " pillar's top; the outline and its point markers");
        d.setting(ToolId.GENERATE, "smart.mesh_view", true);
        d.frames(10);
        run.picture("mesh-view", "The same sheet with Mesh view on: its triangles' edges drawn as white lines");
        d.setting(ToolId.GENERATE, "smart.mesh_view", false);

        run.edit("build the surface", () -> d.action(KeyAction.COMMIT));
        Cells built = run.server(area);
        List<Cells.Change> changes = before.diff(built);
        Predicate<Cells.Change> inside = c -> c.after().isOf(Blocks.MOSS_BLOCK) && c.x() >= a.x(3) && c.x() <= a.x(17)
                && c.z() >= a.z(3) && c.z() <= a.z(17);
        run.check("Enter builds a moss sheet within the outline", changes.size() > 40
                && Changes.offenders(changes, inside).isEmpty(), changes.size() + " cells; "
                + Changes.offenders(changes, inside));
        run.clientMatches("the surface", built);
        run.picture("built", "The moss sheet built over the pillars");
        run.undo("undo the surface");
        run.exact("undo takes the surface back", before);
        d.clearPoints(draft::points);
        d.activeBlock(STONE);
    }

    // ---- Heightmap brush ----

    private static void heightmap(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -1, 18, 39, 30, 39);
        Cells before = run.server(area);
        int radius = 8;
        int x = 28;
        int z = 29;
        d.selectTool(ToolId.HEIGHTMAP);
        d.setting(ToolId.HEIGHTMAP, "radius", radius);
        d.setting(ToolId.HEIGHTMAP, "heightmap.mode", "RAISE");
        d.view(a.x(x) + 0.5, a.y(16), a.z(46) + 0.5, a.x(x) + 0.5, a.y(0), a.z(z) + 0.5);
        int mark = d.toastMark();
        d.aim(a.x(x), a.y(-1), a.z(z), Face.UP);
        d.frames(10);
        run.check("the brush has a picture to stamp (the built-in hill)", d.toastsSince(mark).stream()
                .noneMatch(toast -> toast.notice().key().equals("sculptory.notice.heightmap_missing")), d.toastNote());
        run.pictureAtPointer("preview", "The Heightmap brush's square on the floor, shaded by the hill picture (lighter"
                + " is higher), a tick at the picture's top edge");

        Cells stamped = BrushChecks.click(run, area, a.x(x), a.y(-1), a.z(z), Face.UP, "the stamp");
        List<Cells.Change> changes = before.diff(stamped);
        Predicate<Cells.Change> underSquare = c -> Math.abs(c.x() - a.x(x)) <= radius
                && Math.abs(c.z() - a.z(z)) <= radius && c.y() >= a.y(0) && !c.after().isAir();
        int top = changes.stream().mapToInt(Cells.Change::y).max().orElse(a.y(-1));
        run.check("one click raises a hill inside the square", !changes.isEmpty()
                && Changes.offenders(changes, underSquare).isEmpty(), changes.size() + " cells; "
                + Changes.offenders(changes, underSquare));
        run.check("the hill rises in the middle", top - a.y(0) >= 3, "top " + (top - a.y(0)) + " above the floor");
        run.picture("stamped", "A rounded hill of stone raised from the floor where the square was");
        run.undo("undo the stamp");
        run.exact("undo takes the hill back", before);
    }

    // ---- Line, extended to the ground ----

    private static void lineToGround(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(20, -1, 0, 39, 10, 16);
        Cells before = run.server(area);
        d.selectTool(ToolId.GENERATE);
        d.setting(ToolId.GENERATE, "kind", "LINE");
        d.setting(ToolId.GENERATE, "line.path", "STRAIGHT");
        d.setting(ToolId.GENERATE, "line.thickness", 1);
        d.setting(ToolId.GENERATE, "line.material", "BLOCK");
        d.setting(ToolId.GENERATE, "line.block", BRICKS);
        d.setting(ToolId.GENERATE, "line.extend_to_ground", true);
        GenerateTool generate = d.generateTool();
        LineDraft line = d.onClient(generate::line);
        d.clearPoints(line::points);
        d.view(a.x(30) + 0.5, a.y(10), a.z(26) + 0.5, a.x(30) + 0.5, a.y(2), a.z(LINE_Z) + 0.5);
        d.aim(a.x(LINE_WEST), a.y(LINE_TOP), a.z(LINE_Z), Face.UP);
        d.click(0);
        d.aim(a.x(LINE_EAST), a.y(LINE_TOP), a.z(LINE_Z), Face.UP);
        d.click(0);
        boolean ready = d.until(20_000, () -> !line.previewRunning()
                && line.preview().map(built -> built.ghost() != null).orElse(false));
        run.require("the line's ghost shows", ready, d.toastNote());
        d.parkPointer();
        run.picture("ghost", "A stone-brick line between the two pillar tops and, under it, a ghost wall filled down to"
                + " the floor");

        run.edit("build the line", () -> d.action(KeyAction.COMMIT));
        Cells built = run.server(area);
        List<Cells.Change> changes = before.diff(built);
        BlockState bricks = Fixtures.Build.state(BRICKS);
        Predicate<Cells.Change> wall = c -> c.after().equals(bricks) && c.x() >= a.x(LINE_WEST) && c.x() <= a.x(LINE_EAST)
                && Math.abs(c.z() - a.z(LINE_Z)) <= 1 && c.y() >= a.y(0) && c.y() <= a.y(LINE_TOP);
        int mid = (LINE_WEST + LINE_EAST) / 2;
        boolean grounded = true;
        for (int y = 0; y <= LINE_TOP; y++) {
            grounded &= built.at(a.x(mid), a.y(y), a.z(LINE_Z)).equals(bricks);
        }
        run.check("the line and the fill under it are stone bricks between the pillars", !changes.isEmpty()
                && Changes.offenders(changes, wall).isEmpty(), changes.size() + " cells; "
                + Changes.offenders(changes, wall));
        run.check("Extend to ground fills the middle column from the line down to the floor", grounded, "");
        run.clientMatches("the line", built);
        run.picture("built", "A stone-brick wall between the two pillars, from their tops down to the floor");
        run.undo("undo the line");
        run.exact("undo takes the line and its fill back", before);
        d.clearPoints(line::points);
        d.setting(ToolId.GENERATE, "line.extend_to_ground", false);
        d.setting(ToolId.GENERATE, "kind", "PATH");
    }
}
