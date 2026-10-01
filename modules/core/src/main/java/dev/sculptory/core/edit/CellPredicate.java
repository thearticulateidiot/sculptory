package dev.sculptory.core.edit;

/** A {@link CellMask} bound to one {@code StateSpace}. */
@FunctionalInterface
public interface CellPredicate {
    CellPredicate ALWAYS = (x, y, z, before) -> true;

    boolean test(int x, int y, int z, int before);
}
