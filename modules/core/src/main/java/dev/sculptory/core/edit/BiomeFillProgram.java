package dev.sculptory.core.edit;

import dev.sculptory.core.Box;
import dev.sculptory.core.biome.QuartSelection;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.RegionTooLargeException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A biome fill ({@link OpSpec.BiomeFill}, protocol 6) compiled: the quarts it sets ({@link #quarts()}: every quart the
 * region and its symmetric copies touch) and the biome. It writes no block, so it has no sections; the server sets the
 * biomes chunk column by chunk column in the job's biome stage. Its {@link #estimatedCells()} are the quarts it sets,
 * what the op volume limit counts.
 */
public final class BiomeFillProgram implements EditProgram {
    private static final long[] NONE = new long[0];

    private final QuartSelection quarts;
    private final String biomeId;
    private final Box bounds;

    private BiomeFillProgram(QuartSelection quarts, String biomeId) {
        this.quarts = quarts;
        this.biomeId = biomeId;
        this.bounds = quarts.bounds();
    }

    /**
     * The fill of every copy's region within the build height {@code [bottomY, topYExclusive)}.
     *
     * @throws EditTooLargeException over {@code maxQuarts} quarts
     * @throws IllegalArgumentException when no copy touches a quart inside the build height
     */
    static BiomeFillProgram compile(OpSpec.BiomeFill fill, List<OpSymmetry.Copy> copies, int bottomY,
                                    int topYExclusive, long maxQuarts, long maxSections) {
        Objects.requireNonNull(fill);
        // Boxes are counted exactly before anything is listed (every copy, saturating).
        boolean boxes = true;
        long estimate = 0;
        for (OpSymmetry.Copy copy : copies) {
            Region region = OpRegions.region(copy.op());
            boxes &= region instanceof Region.Cuboid;
            long quarts = QuartSelection.estimate(region, fill.columns(), bottomY, topYExclusive);
            estimate = quarts > Long.MAX_VALUE - estimate ? Long.MAX_VALUE : estimate + quarts;
        }
        if (boxes && estimate > maxQuarts) throw tooLarge(estimate, maxQuarts);
        // Any other region is listed under caps that stop it as soon as it passes them: the quarts left in the budget,
        // and the chunk columns a job may reach.
        long sectionsPerColumn = (((long) topYExclusive - 1) >> 4) - (bottomY >> 4) + 1;
        int maxChunks = (int) Math.max(1, Math.min(Integer.MAX_VALUE, maxSections / Math.max(1, sectionsPerColumn)));
        List<QuartSelection> parts = new ArrayList<>(copies.size());
        long listed = 0;
        for (OpSymmetry.Copy copy : copies) {
            QuartSelection part;
            try {
                part = QuartSelection.touched(OpRegions.region(copy.op()), fill.columns(), bottomY, topYExclusive,
                        maxChunks, maxQuarts == Long.MAX_VALUE ? Long.MAX_VALUE : maxQuarts - listed);
            } catch (RegionTooLargeException e) {
                throw new EditTooLargeException(e.getMessage(), e.cells(), e.limit());
            }
            listed += part.count();
            parts.add(part);
        }
        QuartSelection quarts = QuartSelection.union(parts);
        if (quarts.isEmpty()) throw new IllegalArgumentException("Outside the build height");
        if (quarts.count() > maxQuarts) throw tooLarge(quarts.count(), maxQuarts);
        return new BiomeFillProgram(quarts, fill.biomeId());
    }

    private static EditTooLargeException tooLarge(long quarts, long max) {
        return new EditTooLargeException(quarts + " biome cells > budget of " + max, quarts, max);
    }

    /** The quarts the fill sets. */
    public QuartSelection quarts() {
        return quarts;
    }

    /** The biome it sets them to. */
    public String biomeId() {
        return biomeId;
    }

    @Override
    public String label() {
        return "Biome fill";
    }

    /** Every block of every quart it sets. */
    @Override
    public Box bounds() {
        return bounds;
    }

    @Override
    public long estimatedCells() {
        return quarts.count();
    }

    @Override
    public long[] sourceSections() {
        return NONE;
    }

    @Override
    public long[] sectionOrder() {
        return NONE;
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
        // No block is written: the biomes are set in the job's biome stage.
    }
}
