package dev.sculptory.mc.perm;

/** A permission check's answer: a definite yes or no, or {@link #UNKNOWN} when the permissions backend failed. */
public enum Tri {
    TRUE, FALSE, UNKNOWN
}
