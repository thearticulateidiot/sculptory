package dev.sculptory.fabric.client.editor.tools.scatter;

import java.util.Arrays;
import java.util.Objects;

/**
 * How each scatter placement is drawn, nearest to the camera first: a ghost (the variant's shared meshes under a
 * model matrix), its footprint's outline, a dot, or nothing. Pure, so the caps are testable.
 *
 * <p>A ghost costs one draw per section of its volume, whatever the placement count (meshes are shared), so ghosts are
 * capped both in number ({@link Caps#ghosts}) and in draws ({@link Caps#ghostDraws}); a placement that does not fit
 * falls through to an outline, and a nearer one's leftover room may still take a later, cheaper ghost. Outlines and
 * dots are capped in number and by distance. {@link #tune} adapts the ghost cap to the time the ghost pass took.
 */
public final class ScatterLod {
    /** How a placement is drawn. */
    public enum Tier {
        GHOST,
        BOX,
        DOT,
        NONE
    }

    /** The limits of one plan. */
    public record Caps(int ghosts, int ghostDraws, double ghostDistance, int boxes, double boxDistance, int dots,
                       double dotDistance) {
        public Caps {
            if (ghosts < 0 || ghostDraws < 0 || boxes < 0 || dots < 0) throw new IllegalArgumentException("Negative cap");
        }

        public Caps withGhosts(int newGhosts) {
            return new Caps(newGhosts, ghostDraws, ghostDistance, boxes, boxDistance, dots, dotDistance);
        }
    }

    /** At most 2,000 ghosts (3,000 section draws) within 128 blocks, 2,000 outlines within 256, 20,000 dots within 512. */
    public static final Caps DEFAULT = new Caps(2_000, 3_000, 128, 2_000, 256, 20_000, 512);

    /** CPU time per frame the ghost pass may take before {@link #tune} lowers the ghost cap. */
    public static final long GHOST_BUDGET_NANOS = 4_000_000L;
    public static final int MIN_GHOSTS = 64;

    /**
     * The decision for every placement.
     *
     * @param tiers index-aligned with the input
     * @param wantedGhosts placements that could have been ghosts without the count and draw caps
     */
    public record Plan(Tier[] tiers, int ghosts, int boxes, int dots, int hidden, int wantedGhosts) {
        public Tier tier(int index) {
            return tiers[index];
        }

        /** Whether the ghost caps held back placements that could have been ghosts. */
        public boolean ghostsCapped() {
            return wantedGhosts > ghosts;
        }
    }

    private ScatterLod() {}

    /**
     * Plans a frame.
     *
     * @param centres x, y, z of each placement's centre, 3 per placement
     * @param draws each placement's section draws as a ghost, or -1 when it cannot be one (its variant has no preview)
     */
    public static Plan plan(double[] centres, int[] draws, double cameraX, double cameraY, double cameraZ, Caps caps) {
        Objects.requireNonNull(caps);
        int count = draws.length;
        if (centres.length != 3 * count) throw new IllegalArgumentException("3 coordinates per placement");
        long[] order = new long[count];
        double[] distances = new double[count];
        for (int i = 0; i < count; i++) {
            double dx = centres[3 * i] - cameraX;
            double dy = centres[3 * i + 1] - cameraY;
            double dz = centres[3 * i + 2] - cameraZ;
            distances[i] = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // Sixteenths of a block, so equal distances keep their input order.
            long key = (long) Math.min(Integer.MAX_VALUE, distances[i] * 16);
            order[i] = key << 32 | i;
        }
        Arrays.sort(order);
        Tier[] tiers = new Tier[count];
        int ghosts = 0, ghostDraws = 0, boxes = 0, dots = 0, hidden = 0, wanted = 0;
        for (long packed : order) {
            int i = (int) packed;
            double distance = distances[i];
            boolean ghostable = draws[i] >= 0 && distance <= caps.ghostDistance();
            if (ghostable) wanted++;
            if (ghostable && ghosts < caps.ghosts() && ghostDraws + draws[i] <= caps.ghostDraws()) {
                tiers[i] = Tier.GHOST;
                ghosts++;
                ghostDraws += draws[i];
            } else if (boxes < caps.boxes() && distance <= caps.boxDistance()) {
                tiers[i] = Tier.BOX;
                boxes++;
            } else if (dots < caps.dots() && distance <= caps.dotDistance()) {
                tiers[i] = Tier.DOT;
                dots++;
            } else {
                tiers[i] = Tier.NONE;
                hidden++;
            }
        }
        return new Plan(tiers, ghosts, boxes, dots, hidden, wanted);
    }

    /**
     * The ghost cap for the next frames, from the time the last ghost pass took: a quarter less when over
     * {@link #GHOST_BUDGET_NANOS}, an eighth more (at least 16) when under half of it and the cap held ghosts back.
     * Stays within {@link #MIN_GHOSTS} and {@code max}.
     */
    public static int tune(int cap, long ghostNanos, boolean capped, int max) {
        int next = cap;
        if (ghostNanos > GHOST_BUDGET_NANOS) {
            next = cap * 3 / 4;
        } else if (capped && ghostNanos < GHOST_BUDGET_NANOS / 2) {
            next = cap + Math.max(16, cap / 8);
        }
        return Math.max(Math.min(MIN_GHOSTS, max), Math.min(max, next));
    }
}
