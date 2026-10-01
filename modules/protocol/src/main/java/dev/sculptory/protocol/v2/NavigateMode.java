package dev.sculptory.protocol.v2;

/**
 * What a {@code C2S.Navigate} asks for (protocol 5). Written by
 * ordinal: append only.
 */
public enum NavigateMode {
    /** Jump ({@code J}): onto the top of the block looked at. */
    JUMP,
    /** Through ({@code Shift+J}): through the wall looked at, to the first free spot behind it. */
    THROUGH
}
