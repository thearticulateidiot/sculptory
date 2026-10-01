package dev.sculptory.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/** Value equality with canonical lowercase hexadecimal storage. */
public record Sha256(String hex) implements Comparable<Sha256> {
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    public Sha256 {
        Objects.requireNonNull(hex);
        if (!HEX.matcher(hex).matches()) throw new IllegalArgumentException("Expected lowercase SHA-256");
    }

    /** Wraps an existing 32-byte digest. */
    public static Sha256 ofBytes(byte[] bytes) {
        Objects.requireNonNull(bytes);
        if (bytes.length != 32) throw new IllegalArgumentException("Expected 32 bytes");
        return new Sha256(HexFormat.of().formatHex(bytes));
    }

    /** Hashes {@code data}. */
    public static Sha256 digest(byte[] data) {
        Objects.requireNonNull(data);
        try {
            return ofBytes(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    public byte[] bytes() {
        return HexFormat.of().parseHex(hex);
    }

    @Override
    public int compareTo(Sha256 other) {
        return hex.compareTo(other.hex);
    }
}
