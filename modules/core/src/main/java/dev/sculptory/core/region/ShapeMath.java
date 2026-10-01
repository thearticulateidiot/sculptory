package dev.sculptory.core.region;

import java.math.BigInteger;

/**
 * The exact voxelization of a {@link Region.Shape}.
 *
 * <p>A cell at offset {@code k} (0 to {@code S - 1}) from the box's minimum corner on an axis of size {@code S} has
 * the doubled centre offset {@code N = 2k + 1 - S}, so its normalized coordinate is {@code N / S}, in (-1, 1). Along
 * the facing axis {@code f}, {@code T = 2j + 1}, where {@code j} counts cells from the facing end, equals
 * {@code 2 Sf t}. Each shape's inequality is multiplied out so every test compares integers ({@code P}, {@code Q}:
 * the two axes other than the facing axis):
 * <ul>
 *   <li>ellipsoid: {@code Nx² Sy² Sz² + Ny² Sx² Sz² + Nz² Sx² Sy² <= Sx² Sy² Sz²};</li>
 *   <li>cylinder: {@code P² Sq² + Q² Sp² <= Sp² Sq²};</li>
 *   <li>cone: {@code 4 Sf² (P² Sq² + Q² Sp²) <= T² Sp² Sq²};</li>
 *   <li>pyramid: {@code 2 Sf |P| <= T Sp} and {@code 2 Sf |Q| <= T Sq}.</li>
 * </ul>
 * A row's x interval is solved from the same inequality with integer square roots, so {@link #rowSpan} and
 * {@link #contains} always agree. Boxes of at most {@link #LONG_VOLUME} cells evaluate in {@code long} with overflow
 * checks (every intermediate stays below 2^62); larger boxes, and any overflow, in {@link BigInteger}.
 */
final class ShapeMath {
    /** The largest box evaluated in {@code long}: 8 × volume² (the largest intermediate) stays below 2^62. */
    static final long LONG_VOLUME = 1L << 29;
    /** A row without cells: its minimum exceeds its maximum. */
    static final long EMPTY = pack(Integer.MAX_VALUE, Integer.MIN_VALUE);

    private static final BigInteger TWO = BigInteger.TWO;
    private static final BigInteger FOUR = BigInteger.valueOf(4);

    private final ShapeKind kind;
    /** The facing axis (0 x, 1 y, 2 z) and its sign. */
    private final int f;
    private final int sign;
    private final long[] s;
    private final boolean big;

    ShapeMath(ShapeKind kind, Facing facing, long sx, long sy, long sz, boolean forceBig) {
        this.kind = kind;
        this.f = facing.axis();
        this.sign = facing.sign();
        this.s = new long[] {sx, sy, sz};
        long volume;
        try {
            volume = Math.multiplyExact(Math.multiplyExact(sx, sy), sz);
        } catch (ArithmeticException overflow) {
            volume = Long.MAX_VALUE;
        }
        this.big = forceBig || volume > LONG_VOLUME;
    }

    static long pack(int min, int max) {
        return ((long) min << 32) | (max & 0xFFFFFFFFL);
    }

    static int min(long span) {
        return (int) (span >> 32);
    }

    static int max(long span) {
        return (int) span;
    }

    /** Whether the cell at offsets (kx, ky, kz) from the box's minimum corner, each inside the box, is in the shape. */
    boolean contains(long kx, long ky, long kz) {
        if (!big) {
            try {
                return containsLong(kx, ky, kz);
            } catch (ArithmeticException overflow) {
                // Not expected below LONG_VOLUME; the exact path decides.
            }
        }
        return containsBig(kx, ky, kz);
    }

    /** The x offsets of row (ky, kz), both inside the box, packed with {@link #pack}, or {@link #EMPTY}. */
    long rowSpan(long ky, long kz) {
        if (!big) {
            try {
                return rowSpanLong(ky, kz);
            } catch (ArithmeticException overflow) {
                // As above.
            }
        }
        return rowSpanBig(ky, kz);
    }

    // =================================================================== long

    private boolean containsLong(long kx, long ky, long kz) {
        long[] n = {2 * kx + 1 - s[0], 2 * ky + 1 - s[1], 2 * kz + 1 - s[2]};
        if (kind == ShapeKind.ELLIPSOID) {
            long a = sq(s[0]), b = sq(s[1]), c = sq(s[2]);
            long lhs = Math.addExact(Math.addExact(mul(sq(n[0]), mul(b, c)), mul(sq(n[1]), mul(a, c))),
                    mul(sq(n[2]), mul(a, b)));
            return lhs <= mul(a, mul(b, c));
        }
        int p = (f + 1) % 3, q = (f + 2) % 3;
        long sp2 = sq(s[p]), sq2 = sq(s[q]);
        long t = 2 * fromFacingEnd(f == 0 ? kx : f == 1 ? ky : kz) + 1;
        return switch (kind) {
            case CYLINDER -> Math.addExact(mul(sq(n[p]), sq2), mul(sq(n[q]), sp2)) <= mul(sp2, sq2);
            case CONE -> mul(mul(4, sq(s[f])), Math.addExact(mul(sq(n[p]), sq2), mul(sq(n[q]), sp2)))
                    <= mul(sq(t), mul(sp2, sq2));
            case PYRAMID -> mul(2 * s[f], Math.abs(n[p])) <= mul(t, s[p]) && mul(2 * s[f], Math.abs(n[q])) <= mul(t, s[q]);
            case ELLIPSOID -> throw new AssertionError();
        };
    }

    private long rowSpanLong(long ky, long kz) {
        long sx = s[0];
        long ny = 2 * ky + 1 - s[1], nz = 2 * kz + 1 - s[2];
        if (kind == ShapeKind.ELLIPSOID) {
            long b = sq(s[1]), c = sq(s[2]);
            long k = Math.subtractExact(Math.subtractExact(mul(b, c), mul(sq(ny), c)), mul(sq(nz), b));
            return k < 0 ? EMPTY : centred(isqrt(mul(sq(sx), k) / mul(b, c)), sx);
        }
        if (f == 0) {
            // The row runs along the facing axis: y and z are the fixed cross-section coordinates.
            long sp2 = sq(s[1]), sq2 = sq(s[2]);
            long radial = Math.addExact(mul(sq(ny), sq2), mul(sq(nz), sp2));
            long tMin = switch (kind) {
                case CYLINDER -> radial <= mul(sp2, sq2) ? 0 : Long.MAX_VALUE;
                case CONE -> ceilSqrt(Math.ceilDiv(mul(mul(4, sq(sx)), radial), mul(sp2, sq2)));
                case PYRAMID -> Math.max(Math.ceilDiv(mul(2 * sx, Math.abs(ny)), s[1]),
                        Math.ceilDiv(mul(2 * sx, Math.abs(nz)), s[2]));
                case ELLIPSOID -> throw new AssertionError();
            };
            return fromApex(tMin, sx);
        }
        // The row crosses the facing axis: the facing coordinate and the other cross-section coordinate (o) are fixed.
        long sf = s[f], so = s[3 - f];
        long no = f == 1 ? nz : ny;
        long t = 2 * fromFacingEnd(f == 1 ? ky : kz) + 1;
        long m = switch (kind) {
            case CYLINDER -> isqrt(mul(sq(sx), Math.subtractExact(sq(so), sq(no))) / sq(so));
            case CONE -> {
                long e = Math.subtractExact(mul(sq(t), sq(so)), mul(mul(4, sq(sf)), sq(no)));
                yield e < 0 ? -1 : isqrt(mul(sq(sx), e) / mul(mul(4, sq(sf)), sq(so)));
            }
            case PYRAMID -> mul(2 * sf, Math.abs(no)) > mul(t, so) ? -1 : mul(t, sx) / (2 * sf);
            case ELLIPSOID -> throw new AssertionError();
        };
        return centred(m, sx);
    }

    // =================================================================== BigInteger

    private boolean containsBig(long kx, long ky, long kz) {
        BigInteger[] n = {big(2 * kx + 1 - s[0]), big(2 * ky + 1 - s[1]), big(2 * kz + 1 - s[2])};
        if (kind == ShapeKind.ELLIPSOID) {
            BigInteger a = sq(big(s[0])), b = sq(big(s[1])), c = sq(big(s[2]));
            BigInteger lhs = sq(n[0]).multiply(b).multiply(c).add(sq(n[1]).multiply(a).multiply(c))
                    .add(sq(n[2]).multiply(a).multiply(b));
            return lhs.compareTo(a.multiply(b).multiply(c)) <= 0;
        }
        int p = (f + 1) % 3, q = (f + 2) % 3;
        BigInteger bp = big(s[p]), bq = big(s[q]), bf = big(s[f]);
        BigInteger sp2 = sq(bp), sq2 = sq(bq);
        BigInteger t = big(2 * fromFacingEnd(f == 0 ? kx : f == 1 ? ky : kz) + 1);
        return switch (kind) {
            case CYLINDER -> sq(n[p]).multiply(sq2).add(sq(n[q]).multiply(sp2)).compareTo(sp2.multiply(sq2)) <= 0;
            case CONE -> FOUR.multiply(sq(bf)).multiply(sq(n[p]).multiply(sq2).add(sq(n[q]).multiply(sp2)))
                    .compareTo(sq(t).multiply(sp2).multiply(sq2)) <= 0;
            case PYRAMID -> TWO.multiply(bf).multiply(n[p].abs()).compareTo(t.multiply(bp)) <= 0
                    && TWO.multiply(bf).multiply(n[q].abs()).compareTo(t.multiply(bq)) <= 0;
            case ELLIPSOID -> throw new AssertionError();
        };
    }

    private long rowSpanBig(long ky, long kz) {
        long sx = s[0];
        BigInteger bx = big(sx);
        BigInteger ny = big(2 * ky + 1 - s[1]), nz = big(2 * kz + 1 - s[2]);
        if (kind == ShapeKind.ELLIPSOID) {
            BigInteger b = sq(big(s[1])), c = sq(big(s[2]));
            BigInteger k = b.multiply(c).subtract(sq(ny).multiply(c)).subtract(sq(nz).multiply(b));
            if (k.signum() < 0) return EMPTY;
            return centred(clamp(sq(bx).multiply(k).divide(b.multiply(c)).sqrt(), sx), sx);
        }
        if (f == 0) {
            BigInteger sy = big(s[1]), sz = big(s[2]);
            BigInteger sp2 = sq(sy), sq2 = sq(sz);
            BigInteger radial = sq(ny).multiply(sq2).add(sq(nz).multiply(sp2));
            BigInteger tMin = switch (kind) {
                case CYLINDER -> radial.compareTo(sp2.multiply(sq2)) <= 0 ? BigInteger.ZERO : null;
                case CONE -> ceilSqrt(ceilDiv(FOUR.multiply(sq(bx)).multiply(radial), sp2.multiply(sq2)));
                case PYRAMID -> ceilDiv(TWO.multiply(bx).multiply(ny.abs()), sy)
                        .max(ceilDiv(TWO.multiply(bx).multiply(nz.abs()), sz));
                case ELLIPSOID -> throw new AssertionError();
            };
            // T never exceeds 2 Sx - 1, so any larger minimum means an empty row.
            return tMin == null ? EMPTY : fromApex(clamp(tMin, 2 * sx), sx);
        }
        BigInteger bf = big(s[f]), bo = big(s[3 - f]);
        BigInteger no = f == 1 ? nz : ny;
        BigInteger t = big(2 * fromFacingEnd(f == 1 ? ky : kz) + 1);
        BigInteger m = switch (kind) {
            case CYLINDER -> sq(bx).multiply(sq(bo).subtract(sq(no))).divide(sq(bo)).sqrt();
            case CONE -> {
                BigInteger e = sq(t).multiply(sq(bo)).subtract(FOUR.multiply(sq(bf)).multiply(sq(no)));
                yield e.signum() < 0 ? null : sq(bx).multiply(e).divide(FOUR.multiply(sq(bf)).multiply(sq(bo))).sqrt();
            }
            case PYRAMID -> TWO.multiply(bf).multiply(no.abs()).compareTo(t.multiply(bo)) > 0
                    ? null : t.multiply(bx).divide(TWO.multiply(bf));
            case ELLIPSOID -> throw new AssertionError();
        };
        return m == null ? EMPTY : centred(clamp(m, sx), sx);
    }

    // =================================================================== shared

    /** Cells from the facing end along the facing axis, for the offset {@code k} on that axis. */
    private long fromFacingEnd(long k) {
        return sign > 0 ? s[f] - 1 - k : k;
    }

    /**
     * The row of a symmetric interval: every x whose doubled offset {@code N} satisfies {@code |N| <= m} (N has the
     * parity of {@code size - 1}). Empty for a negative {@code m}.
     */
    private static long centred(long m, long size) {
        long limit = Math.min(m, size - 1);
        if (((size - 1 - limit) & 1) != 0) limit--;
        if (limit < 0) return EMPTY;
        return pack((int) ((size - 1 - limit) / 2), (int) ((size - 1 + limit) / 2));
    }

    /** The row along the facing axis: every cell with {@code T = 2j + 1 >= tMin}, j counted from the facing end. */
    private long fromApex(long tMin, long size) {
        long jMin = Math.max(0, Math.ceilDiv(tMin - 1, 2));
        if (jMin > size - 1) return EMPTY;
        return sign > 0 ? pack(0, (int) (size - 1 - jMin)) : pack((int) jMin, (int) (size - 1));
    }

    private static long mul(long a, long b) {
        return Math.multiplyExact(a, b);
    }

    private static long sq(long a) {
        return Math.multiplyExact(a, a);
    }

    /** The largest {@code r} with {@code r² <= n}, for {@code n >= 0}; integer Newton steps from above. */
    static long isqrt(long n) {
        if (n < 2) return n;
        long x = 1L << ((65 - Long.numberOfLeadingZeros(n)) >>> 1);
        while (true) {
            long y = (x + n / x) >>> 1;
            if (y >= x) return x;
            x = y;
        }
    }

    /** The smallest {@code r} with {@code r² >= n}, for {@code n >= 0}. */
    private static long ceilSqrt(long n) {
        long r = isqrt(n);
        return r * r == n ? r : r + 1;
    }

    private static BigInteger big(long value) {
        return BigInteger.valueOf(value);
    }

    private static BigInteger sq(BigInteger value) {
        return value.multiply(value);
    }

    private static BigInteger ceilSqrt(BigInteger n) {
        BigInteger r = n.sqrt();
        return sq(r).compareTo(n) == 0 ? r : r.add(BigInteger.ONE);
    }

    /** Ceiling division of a non-negative value by a positive one. */
    private static BigInteger ceilDiv(BigInteger a, BigInteger b) {
        return a.add(b).subtract(BigInteger.ONE).divide(b);
    }

    private static long clamp(BigInteger value, long max) {
        return value.min(big(max)).longValueExact();
    }
}
