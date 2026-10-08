package dev.sculptory.core.generate;

import java.util.Objects;

/**
 * What a kernel carrying existing blocks made ({@link PathExtrudeKernel}, {@link TaperKernel}, {@link SweepKernel}): the
 * cells, and how many of the blocks it was given it left out because they hold a block entity (a chest, a sign), which
 * a generated clipboard can't carry.
 */
public record GeneratedResult(GeneratedSource source, long leftOut) {
    public GeneratedResult {
        Objects.requireNonNull(source);
        if (leftOut < 0) throw new IllegalArgumentException("Negative count");
    }
}
