package dev.sculptory.protocol.v2;

import dev.sculptory.core.edit.OpSpec;

/**
 * Which tool a {@code RunOp} comes from, when the op alone doesn't say: the server names the job and its history entry
 * after the tool ("Road · 1,284 blocks") instead of the op ("Paste · …"). {@link #NONE} keeps the op's own name.
 * Written by ordinal, so the order is frozen with protocol 5 ({@code WireGoldenTest}); new labels go at the end.
 *
 * <p>Each label fits one kind of op ({@link #fits}): a road, roof or line (Generate's or the Shape brush's) is a paste
 * of a generated clipboard, an extrude is a stack, a carve an erase, a smear a move, a flood or drain a fill. The server
 * refuses a label on another op.
 */
public enum OpLabel {
    NONE(null),
    ROAD("Road"),
    ROOF("Roof"),
    EXTRUDE("Extrude"),
    CARVE("Carve"),
    SMEAR("Smear"),
    FLOOD("Flood"),
    DRAIN("Drain"),
    LINE("Line"),
    SHAPE_LINE("Shape line");

    private final String text;

    OpLabel(String text) {
        this.text = text;
    }

    /** The label's text as the server writes it into a job and history entry name, or {@code null} for {@link #NONE}. */
    public String text() {
        return text;
    }

    /** Whether this label may name {@code op} ({@link #NONE} names anything). */
    public boolean fits(OpSpec op) {
        return switch (this) {
            case NONE -> true;
            case ROAD, ROOF, LINE, SHAPE_LINE -> op instanceof OpSpec.Paste;
            case EXTRUDE -> op instanceof OpSpec.Stack;
            case CARVE -> op instanceof OpSpec.Erase;
            case SMEAR -> op instanceof OpSpec.Move;
            case FLOOD, DRAIN -> op instanceof OpSpec.Fill;
        };
    }
}
