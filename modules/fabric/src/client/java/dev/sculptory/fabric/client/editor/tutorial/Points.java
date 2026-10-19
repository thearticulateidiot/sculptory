package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.fabric.client.editor.tools.generate.PathModel.Role;
import dev.sculptory.fabric.client.editor.tools.points.PointEditor;
import dev.sculptory.fabric.client.editor.tools.points.PointEditor.EditKind;

/**
 * One Generate kind's points as a step reads them: how many there are, which is selected, and the changes its point
 * editor has made so far (counters that only grow, so a step counts the clicks made since it began, not the points left
 * from before).
 *
 * @param outline       the outline (or open path's) points
 * @param inside        the inside points (Smart Surface)
 * @param selected      the selected outline point, or -1
 * @param anySelected   whether any point, outline or inside, is selected
 * @param placed        outline points added or inserted
 * @param inserted      outline points inserted (a click on the drawn path, or the insert key)
 * @param insidePlaced  inside points added
 * @param moved         outline points moved (a drag counts once, as it ends)
 * @param insideMoved   inside points moved
 */
public record Points(int outline, int inside, int selected, boolean anySelected, long placed, long inserted,
        long insidePlaced, long moved, long insideMoved) {
    /** No points and nothing made: a kind without points, or no Generate tool. */
    public static final Points NONE = new Points(0, 0, -1, false, 0, 0, 0, 0, 0);

    public static Points of(PointEditor editor) {
        return new Points(editor.points().size(), editor.points().insideCount(), editor.selected(),
                editor.selection().isPresent(),
                editor.count(EditKind.ADD, Role.OUTLINE) + editor.count(EditKind.INSERT, Role.OUTLINE),
                editor.count(EditKind.INSERT, Role.OUTLINE), editor.count(EditKind.ADD, Role.INSIDE),
                editor.count(EditKind.MOVE, Role.OUTLINE), editor.count(EditKind.MOVE, Role.INSIDE));
    }

    /** A middle point of the path is selected: neither its first nor its last. */
    public boolean middleSelected() {
        return selected > 0 && selected < outline - 1;
    }
}
