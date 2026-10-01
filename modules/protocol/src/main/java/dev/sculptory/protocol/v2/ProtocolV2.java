package dev.sculptory.protocol.v2;

/** Protocol v2 constants. */
public final class ProtocolV2 {
    private ProtocolV2() {}

    /**
     * The wire version. 3: {@code RejectReason.ASSET_NOT_LOADED} (M3 review); a v2 peer could not decode it, so the two
     * refuse each other at the handshake ({@code Incompatible}) instead of failing on a frame later. 4: library management
     * messages and the listing's {@code writable} flag (M4), plus the batch's scatter and brush wire additions. 5: build
     * ids in the handshake ({@code Welcome.serverBuild}, and the optional build at the end of {@code Incompatible}); a v4
     * peer rejects a {@code Welcome} with it (trailing bytes), so a v4 client gets the plain {@code Incompatible} instead.
     * v5 also carries mix patterns: the {@code Arranged} pattern tag and a
     * palette's pattern. v5 is the version of the batch after 2026-09-29, frozen when that batch is released.
     * Also in 5: Tinker ({@code TinkerBlock}, {@code TinkerEntity}, {@code TinkerResult} and the property pattern).
     * Also in 5: the flip upside down (bit 4 of the transform byte, a {@code Stack} op's trailing {@code upsideDown} bool)
     * and the export format at the end of {@code ExportClipboard} ({@code .schem}, {@code .litematic} or {@code .nbt}).
     * v5 is the version of the batch after 2026-09-29, frozen when that batch is released.
     * Builder mode joins v5 ({@code BuilderPowers}, {@code BuilderPlace}, {@code BuilderBreak}, {@code BuilderDragEnd};
     * codes 44-47; 40-43 are Tinker's).
     */
    public static final int VERSION = 5;
    /** Client-to-server frame cap; vanilla rejects custom payloads over 32,767 bytes. */
    public static final int MAX_C2S_FRAME = 32_000;
    /** Server-to-client frame cap, kept well under vanilla's 1 MiB so control messages interleave. */
    public static final int MAX_S2C_FRAME = 256 * 1024;
}
