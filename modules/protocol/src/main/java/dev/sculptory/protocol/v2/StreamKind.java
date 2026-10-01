package dev.sculptory.protocol.v2;

/** What a stream carries. Wire order: append only. */
public enum StreamKind {
    CLIPBOARD_PREVIEW,
    ASSET_PREVIEW,
    SCHEM_FILE,
    SCHEM_UPLOAD,
    /** M3: a scatter plan's placements, for ghost previews ({@link ScatterPlacements}). */
    SCATTER_PLACEMENTS,
    /** A {@code CellSet.encode()} upload announced by {@code SelectionUpload} (client to server). */
    SELECTION_UPLOAD,
    /** A sparse clipboard payload ({@code BSGU}) announced by {@code GeneratedUpload} (client to server). */
    GENERATED_UPLOAD,
    /**
     * Protocol 5: the cells a scatter plan's grown trees and features write, for ghost previews (a sparse
     * {@code BSGU} payload; metas {@code planId} and {@code reqId}).
     */
    SCATTER_GENERATED
}
