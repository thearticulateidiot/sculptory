package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor.Face;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import org.lwjgl.glfw.GLFW;

/**
 * The 2026-09-29 feedback round's terrain brushes through the real client: Raise, Lower, Smooth and Flatten in the
 * Surface mode on a cliff, a cave ceiling and an overhang; Raise held still on a wall; Flatten from the ground and from
 * a wall; torches kept on a smoothed wall; the Terrain (from above) mode on grass; a radius-32 Smooth's time. Each
 * stroke is checked for where it changed the world (only on the side the brush works), then undone and redone exactly.
 */
final class BrushChecks {
    static final String CLIFF = "cliff";
    static final String CAVE = "cave";
    static final String OVERHANG = "overhang";
    static final String BUMPY = "bumpy";
    static final String GRASS = "grass";

    /** The cliff's east face: blocks at x <= FACE, air east of it. */
    private static final int FACE = 15;
    /** Torches on the cliff face beside the bump Smooth works on. */
    private static final int[][] TORCHES = {{16, 12, 18}, {16, 8, 22}, {16, 13, 21}};
    private static final String TORCH = "minecraft:wall_torch[facing=east]";

    static final ScenarioGroup GROUP = ScenarioGroup.solo(
            List.of(new Fixtures.Fixture(CLIFF, BrushChecks::buildCliff),
                    new Fixtures.Fixture(CAVE, BrushChecks::buildCave),
                    new Fixtures.Fixture(OVERHANG, BrushChecks::buildOverhang),
                    new Fixtures.Fixture(BUMPY, BrushChecks::buildBumpy),
                    new Fixtures.Fixture(GRASS, BrushChecks::buildGrass)),
            List.of(Scenario.visual("brush-surface-cliff", CLIFF,
                            "Surface mode on a cliff: Raise, Raise held still, Smooth beside torches, Lower, Flatten "
                                    + "from the wall, a radius-32 Smooth's time",
                            BrushChecks::cliff),
                    Scenario.of("brush-surface-ceiling", CAVE, "Surface mode under a cave ceiling: Lower and Raise",
                            BrushChecks::ceiling),
                    Scenario.of("brush-surface-overhang", OVERHANG,
                            "Surface mode on an overhang: Smooth its edge, Raise under it", BrushChecks::overhang),
                    Scenario.of("brush-flatten-ground", BUMPY, "Flatten from the ground on bumpy grass",
                            BrushChecks::flattenGround),
                    Scenario.of("brush-terrain-mode", GRASS, "Raise in the Terrain (from above) mode on grass",
                            BrushChecks::terrainMode)));

    private BrushChecks() {}

    // ---- Fixtures ----

    /** A stone cliff 24 high facing east, with a bump and torches (Smooth) and bumps and dents (Flatten). */
    private static void buildCliff(Fixtures.Build b) {
        b.fill(8, 0, 1, FACE, 23, 38, "minecraft:stone");
        b.fill(16, 9, 19, 16, 11, 21, "minecraft:stone");
        for (int[] torch : TORCHES) {
            b.set(torch[0], torch[1], torch[2], TORCH);
        }
        b.fill(16, 9, 34, 16, 10, 35, "minecraft:stone");
        b.set(17, 10, 35, "minecraft:stone");
        b.fill(FACE, 11, 36, FACE, 12, 36, "minecraft:air");
    }

    /** A stone ceiling 6 thick 8 above the floor, on four pillars, open on every side. */
    private static void buildCave(Fixtures.Build b) {
        b.fill(4, 8, 4, 35, 13, 35, "minecraft:stone");
        for (int[] corner : new int[][] {{4, 4}, {33, 4}, {4, 33}, {33, 33}}) {
            b.fill(corner[0], 0, corner[1], corner[0] + 2, 7, corner[1] + 2, "minecraft:stone");
        }
    }

    /** A stone block 16 high with a lip 6 deep sticking out east at its top 5 layers. */
    private static void buildOverhang(Fixtures.Build b) {
        b.fill(8, 0, 4, 15, 15, 35, "minecraft:stone");
        b.fill(16, 11, 4, 21, 15, 35, "minecraft:stone");
    }

    /** Grass over dirt, with bumps one and two high and holes, except flat around (20, 20). */
    private static void buildBumpy(Fixtures.Build b) {
        b.fill(0, -2, 0, 39, -2, 39, "minecraft:dirt");
        b.fill(0, -1, 0, 39, -1, 39, "minecraft:grass_block");
        for (int x = 8; x <= 32; x++) {
            for (int z = 8; z <= 32; z++) {
                if (Math.abs(x - 20) <= 1 && Math.abs(z - 20) <= 1) {
                    continue;
                }
                if ((x * 7 + z * 13) % 5 == 0) {
                    b.set(x, -1, z, "minecraft:dirt");
                    b.set(x, 0, z, "minecraft:grass_block");
                    if ((x + z) % 3 == 0) {
                        b.set(x, 0, z, "minecraft:dirt");
                        b.set(x, 1, z, "minecraft:grass_block");
                    }
                } else if ((x * 11 + z * 3) % 9 == 0) {
                    b.set(x, -1, z, "minecraft:air");
                }
            }
        }
    }

    /** A flat grass field over dirt. */
    private static void buildGrass(Fixtures.Build b) {
        b.fill(0, -2, 0, 39, -2, 39, "minecraft:dirt");
        b.fill(0, -1, 0, 39, -1, 39, "minecraft:grass_block");
    }

    // ---- Scenarios ----

    private static void cliff(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box wall = a.box(0, -1, 0, 39, 30, 39);
        Cells before = run.server(wall);

        // Raise, a short press on the face.
        brush(run, ToolId.RAISE, 3);
        faceView(d, a, 10, 5);
        Cells raised = stroke(run, wall, a.x(FACE), a.y(10), a.z(5), Face.EAST, 400, "Raise on the wall");
        List<Cells.Change> changes = before.diff(raised);
        run.check("Raise on the wall adds blocks", Changes.count(changes, Changes::filled) > 0,
                changes.size() + " cells changed");
        run.check("Raise on the wall only adds blocks in front of the face",
                Changes.offenders(changes, c -> Changes.filled(c) && c.x() > a.x(FACE)).isEmpty(),
                Changes.offenders(changes, c -> Changes.filled(c) && c.x() > a.x(FACE)));
        run.check("Raise on the wall stays near where it was aimed",
                Changes.offenders(changes, Changes.near(a.x(FACE) + 1, a.y(10) + 0.5, a.z(5) + 0.5, 6)).isEmpty(),
                Changes.offenders(changes, Changes.near(a.x(FACE) + 1, a.y(10) + 0.5, a.z(5) + 0.5, 6)));
        run.clientMatches("Raise on the wall", raised);
        angledView(d, a, 10, 5);
        d.pointAt(a.x(FACE) + 1, a.y(10) + 0.5, a.z(5) + 0.5);
        run.picture("raise-wall", "From the side: a lump of stone grown out of the cliff face, the green Raise ring"
                + " on it");
        run.undoRedo("Raise on the wall", before, raised);

        // Raise held still: it keeps growing out of the wall, and only out of it.
        faceView(d, a, 12, 12);
        d.aim(a.x(FACE), a.y(12), a.z(12), Face.EAST);
        long version = d.historyVersion();
        d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        d.millis(700);
        Cells early = run.server(wall);
        d.millis(1800);
        Cells late = run.server(wall);
        d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.awaitEdit(version, "Raise held");
        Cells held = run.server(wall);
        long earlyCount = raised.diff(early).size();
        long lateCount = raised.diff(late).size();
        List<Cells.Change> heldChanges = raised.diff(held);
        run.check("Raise held still keeps growing", earlyCount > 0 && lateCount > earlyCount,
                "cells after 0.7 s: " + earlyCount + ", after 2.5 s: " + lateCount);
        Predicate<Cells.Change> outward = c -> Changes.filled(c) && c.x() > a.x(FACE);
        run.check("Raise held still grows only out of the face", Changes.offenders(heldChanges, outward).isEmpty(),
                Changes.offenders(heldChanges, outward));
        int reach = heldChanges.stream().mapToInt(c -> c.x() - a.x(FACE)).max().orElse(0);
        Predicate<Cells.Change> column = c -> Math.abs(c.y() - a.y(12)) <= 5 && Math.abs(c.z() - a.z(12)) <= 5;
        run.check("Raise held still stays in front of where it was aimed",
                Changes.offenders(heldChanges, column).isEmpty(), "reaches " + reach + " blocks out; "
                        + Changes.offenders(heldChanges, column));
        run.note("Raise held 2.5 s on the wall: " + heldChanges.size() + " cells, " + reach + " blocks out");
        run.clientMatches("Raise held", held);
        angledView(d, a, 12, 12);
        run.picture("raise-held", "From the side: a bigger lump grown straight out of the cliff where Raise was"
                + " held 2.5 s");
        run.undoRedo("Raise held", raised, held);

        // Smooth over the bump, torches beside it.
        brush(run, ToolId.SMOOTH, 4);
        faceView(d, a, 10, 20);
        Cells smoothed = stroke(run, wall, a.x(16), a.y(10), a.z(20), Face.EAST, 600, "Smooth by the torches");
        List<Cells.Change> smoothChanges = held.diff(smoothed);
        run.check("Smooth changes the bump", !smoothChanges.isEmpty(), smoothChanges.size() + " cells changed");
        StringBuilder lost = new StringBuilder();
        for (int[] torch : TORCHES) {
            String state = Cells.describe(smoothed.at(a.x(torch[0]), a.y(torch[1]), a.z(torch[2])));
            if (!state.equals(TORCH)) {
                lost.append(torch[0]).append(' ').append(torch[1]).append(' ').append(torch[2]).append(": ")
                        .append(state).append("; ");
            }
        }
        run.check("torches on the smoothed wall are kept", lost.isEmpty(), lost.toString());
        run.clientMatches("Smooth by the torches", smoothed);
        angledView(d, a, 10, 20);
        run.picture("smooth-torches", "From the side: the bump on the cliff smoothed; the three wall torches around"
                + " it still there");
        run.undoRedo("Smooth by the torches", held, smoothed);

        // Lower into the wall.
        brush(run, ToolId.LOWER, 3);
        faceView(d, a, 10, 28);
        Cells lowered = stroke(run, wall, a.x(FACE), a.y(10), a.z(28), Face.EAST, 400, "Lower on the wall");
        List<Cells.Change> lowerChanges = smoothed.diff(lowered);
        Predicate<Cells.Change> inward = c -> Changes.emptied(c) && c.x() <= a.x(FACE);
        run.check("Lower on the wall digs in", Changes.count(lowerChanges, Changes::emptied) > 0,
                lowerChanges.size() + " cells changed");
        run.check("Lower on the wall only removes blocks behind the face",
                Changes.offenders(lowerChanges, inward).isEmpty(), Changes.offenders(lowerChanges, inward));
        run.clientMatches("Lower on the wall", lowered);
        angledView(d, a, 10, 28);
        run.picture("lower-wall", "From the side: a hollow dug into the cliff face");
        run.undoRedo("Lower on the wall", smoothed, lowered);

        // Flatten from the wall: bumps in front of the face go, dents in it fill.
        brush(run, ToolId.FLATTEN, 3);
        faceView(d, a, 10, 34);
        Cells clicked = click(run, wall, a.x(FACE), a.y(10), a.z(33), Face.EAST, "Flatten click on the wall");
        run.note("one Flatten click (radius 3, default strength) on the wall changed " + lowered.diff(clicked).size()
                + " cells");
        Cells flat = drag(run, wall, a.x(FACE), a.y(10), a.z(32), Face.EAST, a.x(FACE) + 1, a.y(10) + 0.5,
                a.z(36) + 0.5, "Flatten drag on the wall");
        List<Cells.Change> flatChanges = lowered.diff(flat);
        Predicate<Cells.Change> onPlane = c -> Changes.emptied(c) ? c.x() > a.x(FACE)
                : Changes.filled(c) && c.x() <= a.x(FACE);
        run.check("Flatten from the wall changes the bumps and dents", !flatChanges.isEmpty(),
                flatChanges.size() + " cells changed");
        run.check("Flatten from the wall levels to the face's plane", Changes.offenders(flatChanges, onPlane).isEmpty(),
                Changes.offenders(flatChanges, onPlane));
        run.check("Flatten from the wall removes the bump next to where it was aimed",
                flat.at(a.x(16), a.y(10), a.z(34)).isAir(),
                "16 10 34 is " + Cells.describe(flat.at(a.x(16), a.y(10), a.z(34))));
        run.clientMatches("Flatten from the wall", flat);
        angledView(d, a, 10, 34);
        run.picture("flatten-wall", "From the side: the bumps on the cliff face where Flatten was dragged flattened"
                + " into the face");
        run.undoRedo("Flatten from the wall", clicked, flat);

        // A radius-32 Smooth: how long the server takes (the ball reaches past the area; undone right after).
        brush(run, ToolId.SMOOTH, 32);
        faceView(d, a, 12, 20);
        Box ball = Box.of(a.at(FACE - 33, -8, -13), a.at(FACE + 33, 45, 53));
        Cells ballBefore = run.server(ball);
        d.aim(a.x(FACE), a.y(12), a.z(20), Face.EAST);
        long r32version = d.historyVersion();
        d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        d.millis(1_000);
        long released = System.currentTimeMillis();
        d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.awaitEdit(r32version, "radius-32 Smooth");
        long took = System.currentTimeMillis() - released;
        Cells ballAfter = run.server(ball);
        int changed = ballBefore.diff(ballAfter).size();
        run.note(String.format(Locale.ROOT, "radius-32 Surface Smooth held 1 s: the server finished %d ms after the"
                + " release (%d cells changed)", took, changed));
        run.check("a radius-32 Smooth held 1 s changes the cliff", changed > 0, changed + " cells, " + took
                + " ms after the release");
        angledView(d, a, 12, 20);
        run.picture("smooth-r32", "From the side: the cliff after a radius-32 Smooth held 1 s (rounded over a wide"
                + " area)");
        if (changed > 0) {
            run.undo("radius-32 Smooth: undo");
            run.exact("radius-32 Smooth: undo restores everything it touched", ballBefore);
        }
    }

    private static void ceiling(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box cave = a.box(0, -1, 0, 39, 16, 39);
        Cells before = run.server(cave);
        // Inside the cave, looking up at the ceiling.
        d.view(a.x(20) + 0.5, a.y(2), a.z(12) + 0.5, a.x(23) + 0.5, a.y(8), a.z(20) + 0.5);
        brush(run, ToolId.LOWER, 3);
        Cells lowered = stroke(run, cave, a.x(20), a.y(8), a.z(20), Face.DOWN, 400, "Lower on the ceiling");
        List<Cells.Change> changes = before.diff(lowered);
        Predicate<Cells.Change> up = c -> Changes.emptied(c) && c.y() >= a.y(8);
        run.check("Lower on the ceiling digs up into it", Changes.count(changes, Changes::emptied) > 0,
                changes.size() + " cells changed");
        run.check("Lower on the ceiling only removes ceiling blocks", Changes.offenders(changes, up).isEmpty(),
                Changes.offenders(changes, up));
        run.clientMatches("Lower on the ceiling", lowered);
        run.picture("lower-ceiling", "From inside the cave: a hollow dug up into the ceiling");
        run.undoRedo("Lower on the ceiling", before, lowered);

        brush(run, ToolId.RAISE, 3);
        Cells raised = stroke(run, cave, a.x(26), a.y(8), a.z(20), Face.DOWN, 400, "Raise on the ceiling");
        List<Cells.Change> raiseChanges = lowered.diff(raised);
        Predicate<Cells.Change> down = c -> Changes.filled(c) && c.y() < a.y(8) && c.y() > a.y(0);
        run.check("Raise on the ceiling adds blocks", Changes.count(raiseChanges, Changes::filled) > 0,
                raiseChanges.size() + " cells changed");
        run.check("Raise on the ceiling only hangs blocks under it", Changes.offenders(raiseChanges, down).isEmpty(),
                Changes.offenders(raiseChanges, down));
        run.clientMatches("Raise on the ceiling", raised);
        run.picture("raise-ceiling", "From inside the cave: a lump hanging from the ceiling beside the hollow");
        run.undoRedo("Raise on the ceiling", lowered, raised);
    }

    private static void overhang(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box rock = a.box(0, -1, 0, 39, 24, 39);
        Cells before = run.server(rock);
        d.view(a.x(32) + 0.5, a.y(6), a.z(20) + 0.5, a.x(21) + 0.5, a.y(11), a.z(20) + 0.5);
        brush(run, ToolId.SMOOTH, 3);
        Cells smoothed = stroke(run, rock, a.x(21), a.y(11), a.z(20), Face.EAST, 600, "Smooth the overhang's edge");
        List<Cells.Change> changes = before.diff(smoothed);
        run.check("Smooth on the overhang's edge changes it", !changes.isEmpty(), changes.size() + " cells changed");
        Predicate<Cells.Change> near = Changes.near(a.x(21) + 1, a.y(11) + 0.5, a.z(20) + 0.5, 5);
        run.check("Smooth on the overhang stays near where it was aimed", Changes.offenders(changes, near).isEmpty(),
                Changes.offenders(changes, near));
        run.clientMatches("Smooth the overhang's edge", smoothed);
        run.picture("smooth-overhang", "The overhang's lower front edge rounded off where Smooth was aimed");
        run.undoRedo("Smooth the overhang's edge", before, smoothed);

        brush(run, ToolId.RAISE, 3);
        d.view(a.x(30) + 0.5, a.y(3), a.z(12) + 0.5, a.x(18) + 0.5, a.y(11), a.z(12) + 0.5);
        Cells raised = stroke(run, rock, a.x(18), a.y(11), a.z(12), Face.DOWN, 400, "Raise under the overhang");
        List<Cells.Change> raiseChanges = smoothed.diff(raised);
        Predicate<Cells.Change> below = c -> Changes.filled(c) && c.y() < a.y(11);
        run.check("Raise under the overhang adds blocks", Changes.count(raiseChanges, Changes::filled) > 0,
                raiseChanges.size() + " cells changed");
        run.check("Raise under the overhang only hangs blocks under it", Changes.offenders(raiseChanges, below).isEmpty(),
                Changes.offenders(raiseChanges, below));
        run.clientMatches("Raise under the overhang", raised);
        run.picture("raise-overhang", "A lump hanging under the overhang");
        run.undoRedo("Raise under the overhang", smoothed, raised);
    }

    private static void flattenGround(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box ground = a.box(0, -4, 0, 39, 6, 39);
        Cells before = run.server(ground);
        d.view(a.x(20) + 0.5, a.y(9), a.z(31) + 0.5, a.x(20) + 0.5, a.y(0), a.z(20) + 0.5);
        brush(run, ToolId.FLATTEN, 4);
        Cells clicked = click(run, ground, a.x(20), a.y(-1), a.z(20), Face.UP, "Flatten click on the ground");
        run.note("one Flatten click (radius 4, default strength) on bumpy ground changed " + before.diff(clicked).size()
                + " cells");
        Cells flat = drag(run, ground, a.x(18), a.y(-1), a.z(20), Face.UP, a.x(22) + 0.5, a.y(0), a.z(20) + 0.5,
                "Flatten drag on the ground");
        List<Cells.Change> changes = before.diff(flat);
        Predicate<Cells.Change> onPlane = c -> Changes.emptied(c) ? c.y() >= a.y(0)
                : Changes.filled(c) && c.y() <= a.y(-1);
        run.check("Flatten from the ground changes bumps and holes", !changes.isEmpty(),
                changes.size() + " cells changed");
        run.check("Flatten from the ground levels to the ground's plane", Changes.offenders(changes, onPlane).isEmpty(),
                Changes.offenders(changes, onPlane));
        Box inner = a.box(18, 0, 18, 22, 2, 22);
        long bumpsBefore = before.count(inner, state -> !state.isAir());
        long bumpsAfter = flat.count(inner, state -> !state.isAir());
        run.check("Flatten from the ground removes the bumps near where it was aimed", bumpsAfter < bumpsBefore,
                "blocks above the ground within 2 of the aim: " + bumpsBefore + " before, " + bumpsAfter + " after");
        run.clientMatches("Flatten from the ground", flat);
        run.picture("flatten-ground", "A levelled patch in the bumpy grass around where Flatten was clicked");
        run.undoRedo("Flatten from the ground", clicked, flat);
    }

    private static void terrainMode(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box field = a.box(0, -3, 0, 39, 12, 39);
        Cells before = run.server(field);
        d.view(a.x(20) + 0.5, a.y(9), a.z(32) + 0.5, a.x(20) + 0.5, a.y(0), a.z(20) + 0.5);
        brush(run, ToolId.RAISE, 4);
        d.setting(ToolId.RAISE, "mode", "TERRAIN");
        Cells raised = stroke(run, field, a.x(20), a.y(-1), a.z(20), Face.UP, 400, "Raise from above");
        d.setting(ToolId.RAISE, "mode", "SURFACE");
        List<Cells.Change> changes = before.diff(raised);
        run.check("Raise from above adds blocks", Changes.count(changes, Changes::filled) > 0,
                changes.size() + " cells changed");
        Predicate<Cells.Change> up = c -> Changes.filled(c) && c.y() >= a.y(0);
        run.check("Raise from above only builds on top of the ground", Changes.offenders(changes, up).isEmpty(),
                Changes.offenders(changes, up));
        // Columns: each raised column is filled from the ground up without gaps.
        StringBuilder gaps = new StringBuilder();
        for (Cells.Change change : changes) {
            for (int y = a.y(0); y < change.y(); y++) {
                if (raised.at(change.x(), y, change.z()).isAir()) {
                    gaps.append(change.x()).append(' ').append(y).append(' ').append(change.z()).append("; ");
                    break;
                }
            }
        }
        run.check("Raise from above raises whole columns", gaps.isEmpty(), gaps.toString());
        run.clientMatches("Raise from above", raised);
        run.picture("raise-terrain-mode", "A smooth grassy mound on the flat field (Terrain mode)");
        run.undoRedo("Raise from above", before, raised);
    }

    // ---- Helpers ----

    /** Selects a brush in the Surface mode (the default) with a radius. */
    private static void brush(CheckRun run, ToolId tool, int radius) {
        CheckDriver d = run.driver();
        d.selectTool(tool);
        d.setting(tool, "radius", radius);
        if (tool.equals(ToolId.RAISE) || tool.equals(ToolId.LOWER) || tool.equals(ToolId.SMOOTH)
                || tool.equals(ToolId.FLATTEN)) {
            d.setting(tool, "mode", "SURFACE");
        }
    }

    /** Looks straight at the cliff face at height {@code y} and depth {@code z}, from 14 blocks east. */
    private static void faceView(CheckDriver d, Area a, int y, int z) {
        d.view(a.x(FACE + 14) + 0.5, a.y(y) + 2, a.z(z) + 0.5, a.x(FACE) + 1, a.y(y) + 0.5, a.z(z) + 0.5);
    }

    /**
     * For a picture: the same point on the face from 35 degrees to the side and 20 degrees above, so a lump or a
     * hollow shows in depth (straight on it looks flat). The camera stands toward the area's middle along the wall.
     */
    private static void angledView(CheckDriver d, Area a, int y, int z) {
        double sideways = z < 20 ? 35 : -35;
        d.viewAngled(a.x(FACE) + 1, a.y(y) + 0.5, a.z(z) + 0.5, Face.EAST, sideways, 20, 14);
    }

    /**
     * A click (press, two frames, release) at a face, then the server's answer: the box's cells after. A click that
     * changes nothing makes no history entry, so this waits for the stroke to end rather than for the history.
     */
    static Cells click(CheckRun run, Box box, int x, int y, int z, Face face, String what) {
        CheckDriver d = run.driver();
        d.aim(x, y, z, face);
        d.click(0);
        d.awaitIdle(what);
        return run.server(box);
    }

    /** Presses at a face, drags to a point in 12 frames, releases, waits for the server; the box's cells after. */
    static Cells drag(CheckRun run, Box box, int x, int y, int z, Face face, double toX, double toY, double toZ,
            String what) {
        CheckDriver d = run.driver();
        d.aim(x, y, z, face);
        run.edit(what, () -> {
            d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
            d.dragTo(toX, toY, toZ, 12, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        });
        return run.server(box);
    }

    /** Aims at a face, presses for {@code holdMs}, releases, waits for the server; the box's cells after. */
    static Cells stroke(CheckRun run, Box box, int x, int y, int z, Face face, long holdMs, String what) {
        CheckDriver d = run.driver();
        d.aim(x, y, z, face);
        run.edit(what, () -> {
            d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
            d.millis(holdMs);
            d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        });
        return run.server(box);
    }
}
