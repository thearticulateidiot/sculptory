package dev.sculptory.core.brush;

/**
 * Which cells of a step may be written: the global mask, judged against the world before the step
 * ({@code MaskedKernel.Judge}), when a planned step is worked out in pieces ({@link PlannedStep#planner}).
 */
@FunctionalInterface
public interface CellFilter {
    boolean accepts(int x, int y, int z);
}
