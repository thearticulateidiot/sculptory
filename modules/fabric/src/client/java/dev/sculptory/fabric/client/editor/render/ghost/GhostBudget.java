package dev.sculptory.fabric.client.editor.render.ghost;

import dev.sculptory.fabric.client.editor.world.Aabb;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

/**
 * Decides, each frame, which sections of a preview get full meshes and which are simplified to bounding boxes.
 * Pure logic, separate from GL so it can be tested.
 *
 * <p>Rules, applied nearest section first:
 * <ol>
 *   <li>Sections without block cells draw nothing (their erase cells still get outlines).</li>
 *   <li>With a shader pack active, every section is a box.</li>
 *   <li>Sections farther than {@link GhostConfig#fullDetailDistance()} are boxes. A section that already has a mesh
 *       keeps it until {@value #HYSTERESIS} blocks beyond that, so moving the camera along the boundary does not
 *       rebuild meshes over and over.</li>
 *   <li>A section whose blocks or vertex bytes would exceed the remaining caps is a box; later (farther) sections that
 *       still fit are meshed.</li>
 * </ol>
 * Erase cells are outlined one by one for near sections up to {@link GhostConfig#maxEraseOutlines()} cells; other
 * sections get a single box around their erase cells.
 */
public final class GhostBudget {
    /** Vertex bytes assumed per block before a section is meshed (about 1.25 visible faces of 128 bytes). */
    public static final long ESTIMATED_BYTES_PER_BLOCK = 160;
    /** Extra distance, in blocks, before an existing mesh is dropped. */
    public static final double HYSTERESIS = 8.0;

    private GhostBudget() {}

    /** How a section is drawn. */
    public enum Draw {
        /** No block cells: nothing but erase outlines. */
        NONE,
        MESH,
        BOX
    }

    /**
     * One section's input.
     *
     * @param blocks block cells
     * @param erases erase cells
     * @param distance camera distance to the section's placed cells, in blocks
     * @param meshBytes the section's actual vertex bytes when known (its mesh of its current content, or the last such
     *     mesh, since freed), otherwise -1
     * @param meshed whether it currently has a mesh (possibly outdated)
     */
    public record Candidate(int blocks, int erases, double distance, long meshBytes, boolean meshed) {
        /** Bytes counted against the memory cap: actual if known, else an estimate. */
        public long expectedBytes() {
            return meshBytes >= 0 ? meshBytes : blocks * ESTIMATED_BYTES_PER_BLOCK;
        }
    }

    /**
     * The decision for every candidate, index-aligned with the input list.
     *
     * @param draws how each section is drawn
     * @param outlineEraseCells whether each section's erase cells are outlined one by one (else one box, if any)
     * @param nearestFirst candidate indices by increasing distance: the order to request meshes in
     */
    public record Plan(Draw[] draws, boolean[] outlineEraseCells, int[] nearestFirst, GhostStatus status) {
        public Draw draw(int index) {
            return draws[index];
        }

        public boolean outlineEraseCells(int index) {
            return outlineEraseCells[index];
        }
    }

    /**
     * Plans one frame of a volume that is alone in the renderer.
     *
     * @param outlinesOnly a shader pack is active: draw every section as a box
     */
    public static Plan plan(List<Candidate> candidates, GhostConfig config, boolean outlinesOnly) {
        return plan(candidates, config, outlinesOnly, 0);
    }

    /**
     * Plans one frame of one volume.
     *
     * @param outlinesOnly a shader pack is active: draw every section as a box
     * @param bytesElsewhere vertex bytes the renderer already holds for other volumes: the memory cap is one budget
     *     for the whole renderer, so they are taken off it first
     */
    public static Plan plan(List<Candidate> candidates, GhostConfig config, boolean outlinesOnly, long bytesElsewhere) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(config, "config");
        int count = candidates.size();
        Draw[] draws = new Draw[count];
        boolean[] eraseOutlines = new boolean[count];
        int[] order = IntStream.range(0, count)
                .boxed()
                .sorted(Comparator.comparingDouble((Integer i) -> candidates.get(i).distance()).thenComparingInt(i -> i))
                .mapToInt(Integer::intValue)
                .toArray();

        long totalBlocks = 0;
        long meshedBlocks = 0;
        long meshedBytes = 0;
        long outlinedErases = 0;
        int boxes = 0;
        GhostStatus.Reason reason = GhostStatus.Reason.NONE;
        for (int index : order) {
            Candidate candidate = candidates.get(index);
            totalBlocks += candidate.blocks();
            boolean near = candidate.distance() <= config.fullDetailDistance();

            if (candidate.erases() > 0 && near && outlinedErases + candidate.erases() <= config.maxEraseOutlines()) {
                eraseOutlines[index] = true;
                outlinedErases += candidate.erases();
            }

            if (candidate.blocks() <= 0) {
                draws[index] = Draw.NONE;
                continue;
            }
            GhostStatus.Reason boxedBecause;
            double limit = config.fullDetailDistance() + (candidate.meshed() ? HYSTERESIS : 0);
            if (outlinesOnly) {
                boxedBecause = GhostStatus.Reason.SHADER_PACK;
            } else if (candidate.distance() > limit) {
                boxedBecause = GhostStatus.Reason.DISTANCE;
            } else if (meshedBlocks + candidate.blocks() > config.maxMeshedBlocks()) {
                boxedBecause = GhostStatus.Reason.BLOCK_CAP;
            } else if (meshedBytes + candidate.expectedBytes() > config.maxVertexBytes() - Math.max(0, bytesElsewhere)) {
                boxedBecause = GhostStatus.Reason.MEMORY_CAP;
            } else {
                draws[index] = Draw.MESH;
                meshedBlocks += candidate.blocks();
                meshedBytes += candidate.expectedBytes();
                continue;
            }
            draws[index] = Draw.BOX;
            boxes++;
            if (boxedBecause.compareTo(reason) > 0) {
                reason = boxedBecause;
            }
        }
        return new Plan(draws, eraseOutlines, order, new GhostStatus(totalBlocks, meshedBlocks, boxes, reason));
    }

    /**
     * The {@code bytesElsewhere} for one volume of a renderer: the other volumes' vertex bytes, but never so many that
     * the volume could not keep the bytes it holds already. So a volume keeps its meshes and grows only into what the
     * others leave, and two volumes sharing the cap never take meshes from each other frame after frame (only distance,
     * the block cap and idle eviction free a mesh). The total can pass the cap only by meshes that were in flight.
     *
     * @param total vertex bytes of every volume, this one included
     * @param own this volume's vertex bytes
     */
    public static long bytesElsewhere(long total, long own, long cap) {
        return Math.max(0, Math.min(total - own, cap - own));
    }

    /** Distance from a point to the nearest point of a box (0 inside it). */
    public static double distance(Aabb box, double x, double y, double z) {
        double dx = Math.max(0, Math.max(box.minX() - x, x - box.maxX()));
        double dy = Math.max(0, Math.max(box.minY() - y, y - box.maxY()));
        double dz = Math.max(0, Math.max(box.minZ() - z, z - box.maxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
