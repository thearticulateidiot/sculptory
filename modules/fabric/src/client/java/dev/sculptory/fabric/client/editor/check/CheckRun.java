package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a scenario's script works with: the {@link CheckDriver}, its fixture area, and the checks it records. A check
 * that fails is recorded and the script goes on ({@link #check}); one the rest depends on stops it ({@link #require}).
 */
public final class CheckRun {
    /** Stops the scenario after a failed {@link #require} (already recorded). */
    static final class Stop extends RuntimeException {
        Stop(String message) {
            super(message, null, false, false);
        }
    }

    private final CheckDriver driver;
    private final CheckReport report;
    private final CheckConfig config;
    private final Map<String, Area> areas;
    private final Scenario scenario;

    CheckRun(CheckDriver driver, CheckReport report, CheckConfig config, Map<String, Area> areas, Scenario scenario) {
        this.driver = driver;
        this.report = report;
        this.config = config;
        this.areas = areas;
        this.scenario = scenario;
    }

    public CheckDriver driver() {
        return driver;
    }

    public CheckConfig config() {
        return config;
    }

    /** The scenario's fixture area. */
    public Area area() {
        Area area = areas.get(Objects.requireNonNull(scenario.area(), "the scenario has no area"));
        if (area == null) {
            throw new CheckDriver.Failed("the fixture area " + scenario.area() + " was not built");
        }
        return area;
    }

    public Area area(String name) {
        Area area = areas.get(name);
        if (area == null) {
            throw new CheckDriver.Failed("the fixture area " + name + " was not built");
        }
        return area;
    }

    // ---- Checks ----

    /** Records a check; returns whether it passed. */
    public boolean check(String what, boolean ok, String detail) {
        if (ok) {
            report.pass(scenario.name(), what, detail);
        } else {
            report.fail(scenario.name(), what, detail);
        }
        return ok;
    }

    public boolean check(String what, boolean ok) {
        return check(what, ok, "");
    }

    /**
     * Records a check and stops the scenario when it failed; {@code detail} (why it would fail) is recorded only
     * then.
     */
    public void require(String what, boolean ok, String detail) {
        if (!check(what, ok, ok ? "" : detail)) {
            throw new Stop(what);
        }
    }

    public void skip(String what, String why) {
        report.skip(scenario.name(), what, why);
    }

    public void note(String line) {
        report.note(scenario.name() + ": " + line);
    }

    // ---- Worlds ----

    public Cells server(Box box) {
        return driver.serverCells(box);
    }

    public Cells client(Box box) {
        return driver.clientCells(box);
    }

    /**
     * Checks that the server's world holds exactly {@code expected} in its box, and that the client's shows the same;
     * returns the server's cells.
     */
    public Cells exact(String what, Cells expected) {
        Cells now = server(expected.box());
        List<Cells.Change> changes = expected.diff(now);
        check(what, changes.isEmpty(), Cells.summary(changes, 6));
        clientMatches(what, now);
        return now;
    }

    /** Checks that the client's world comes to show {@code server} (the server's cells). */
    public boolean clientMatches(String what, Cells server) {
        List<Cells.Change> left = driver.awaitClient(server);
        return check("the client shows the server's world: " + what, left.isEmpty(),
                left.isEmpty() ? "" : "server -> client: " + Cells.summary(left, 6));
    }

    // ---- Edits ----

    /** Runs {@code action} (an edit the player makes) and waits until the server has done it. */
    public void edit(String what, Runnable action) {
        long version = driver.historyVersion();
        action.run();
        driver.awaitEdit(version, what);
    }

    /** Ctrl+Z (the bound key), waiting for the server. */
    public void undo(String what) {
        edit(what, () -> driver.action(KeyAction.UNDO));
    }

    /** Ctrl+Y (the bound key), waiting for the server. */
    public void redo(String what) {
        edit(what, () -> driver.action(KeyAction.REDO));
    }

    /**
     * Undo, redo and undo again around an edit: the area is exactly {@code before} after each undo and exactly
     * {@code after} after the redo, on the server and in the client.
     */
    public void undoRedo(String what, Cells before, Cells after) {
        if (before.same(after)) {
            // No history entry to step: an undo now would undo the edit before.
            skip(what + ": undo and redo", "the edit changed nothing");
            return;
        }
        undo(what + ": undo");
        exact(what + ": undo restores the area exactly", before);
        redo(what + ": redo");
        exact(what + ": redo puts the edit back exactly", after);
    }

    public void picture(String name, String lookFor) {
        driver.picture(name, lookFor);
    }

    /** A picture with the pointer left where it is (it is about what the pointer hovers). */
    public void pictureAtPointer(String name, String lookFor) {
        driver.pictureAtPointer(name, lookFor);
    }
}
