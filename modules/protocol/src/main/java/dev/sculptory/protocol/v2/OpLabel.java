package dev.sculptory.protocol.v2;

import dev.sculptory.core.edit.OpSpec;

/**
 * Which tool a {@code RunOp} comes from, when the op alone doesn't say: the server names the job and its history entry
 * after the tool ("Road · 1,284 blocks") instead of the op ("Paste · …"). {@link #NONE} keeps the op's own name.
 * Written by ordinal, so the order is frozen with the protocol ({@code WireGoldenTest}); new labels go at the end.
 *
 * <p>Each label fits one kind of op ({@link #fits}): a road, roof or line (Generate's or the Shape brush's) is a paste
 * of a generated clipboard, an extrude is a stack, a carve an erase, a smear a move, a flood or drain a fill. Protocol 6
 * adds Extrude's path and taper and Generate's sweep and heightmap (pastes of generated clipboards), Generate's stamp (a
 * multi-paste), the Selection window's Rotate… (a move in place) and the Biome tool's fill (a biome fill). The server
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
    SHAPE_LINE("Shape line"),
    /** Protocol 6: Extrude along a path (a paste of the generated extrusion). */
    PATH_EXTRUDE("Extrude path"),
    /** Protocol 6: Extrude to a point (a paste of the generated taper). */
    TAPER("Taper"),
    /** Protocol 6: Generate's Sweep (a paste of the generated sweep). */
    SWEEP("Sweep"),
    /** Protocol 6: Generate's Heightmap area import (a paste of the generated terrain). */
    HEIGHTMAP("Heightmap"),
    /** Protocol 6: Generate's Stamp (a multi-paste of the clipboard or an asset). */
    STAMP("Stamp"),
    /** Protocol 6: the Selection window's Rotate… (a move in place). */
    ROTATE("Rotate"),
    /** Protocol 6: the Biome tool's Fill selection (a biome fill). */
    BIOME_FILL("Biome fill");

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
            case ROAD, ROOF, LINE, SHAPE_LINE, PATH_EXTRUDE, TAPER, SWEEP, HEIGHTMAP -> op instanceof OpSpec.Paste;
            case EXTRUDE -> op instanceof OpSpec.Stack;
            case CARVE -> op instanceof OpSpec.Erase;
            case SMEAR, ROTATE -> op instanceof OpSpec.Move;
            case FLOOD, DRAIN -> op instanceof OpSpec.Fill;
            case STAMP -> op instanceof OpSpec.PasteMany;
            case BIOME_FILL -> op instanceof OpSpec.BiomeFill;
        };
    }
}
