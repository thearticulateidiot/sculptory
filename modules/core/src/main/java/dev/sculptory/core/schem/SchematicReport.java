package dev.sculptory.core.schem;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What a schematic import could not keep. Nothing is dropped silently: every loss is counted here.
 *
 * @param unknownStates palette entries (as written in the file) that the state space does not know, with the
 *     number of cells each covered; those cells became air
 * @param blockEntities block entities attached to cells
 * @param blockEntitiesSkipped block entities dropped (outside the box, no id, on a state without a block
 *     entity, duplicate position, or a failed data fix); see {@link #warnings()}
 * @param entities entities read into the clipboard
 * @param entitiesSkipped entities in the file that were not read (every entity of a version 1 file; no or a bad
 *     {@code Id} or {@code Pos}, a position outside the box, or a failed data fix); see {@link #warnings()}
 * @param biomesSkipped whether the file had biome data (never imported in v1)
 * @param newerDataVersion whether the file comes from a newer game version than the data fixer's target
 * @param warnings human-readable details, at most {@link #MAX_WARNINGS} (then a final "... more" line)
 * @param ticksSkipped scheduled block and fluid updates the file held (Litematica's pending ticks), never imported
 */
public record SchematicReport(SortedMap<String, Long> unknownStates, int blockEntities, int blockEntitiesSkipped,
                              int entities, int entitiesSkipped, boolean biomesSkipped, boolean newerDataVersion,
                              List<String> warnings, int ticksSkipped) {
    public static final int MAX_WARNINGS = 64;

    public SchematicReport {
        unknownStates = Collections.unmodifiableSortedMap(new TreeMap<>(unknownStates));
        warnings = List.copyOf(warnings);
        if (ticksSkipped < 0) throw new IllegalArgumentException("ticksSkipped " + ticksSkipped);
    }

    /** A report of a file without scheduled updates. */
    public SchematicReport(SortedMap<String, Long> unknownStates, int blockEntities, int blockEntitiesSkipped,
                           int entities, int entitiesSkipped, boolean biomesSkipped, boolean newerDataVersion,
                           List<String> warnings) {
        this(unknownStates, blockEntities, blockEntitiesSkipped, entities, entitiesSkipped, biomesSkipped,
                newerDataVersion, warnings, 0);
    }

    /** Cells that became air because their state was unknown. */
    public long unknownCells() {
        long cells = 0;
        for (long count : unknownStates.values()) cells += count;
        return cells;
    }

    /** True when the import lost nothing. */
    public boolean isLossless() {
        return unknownStates.isEmpty() && blockEntitiesSkipped == 0 && entitiesSkipped == 0 && !biomesSkipped
                && ticksSkipped == 0;
    }
}
