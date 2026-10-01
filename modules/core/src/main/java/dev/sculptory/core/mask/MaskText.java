package dev.sculptory.core.mask;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The text form of an {@link EditMask}, as the client saves it (the mask chip's rules, a brush preset's
 * {@code mask.rules}): {@link #decode} reads what {@link #encode} writes.
 *
 * <p>One line: {@code v1}, then {@code " invert"} when the whole mask is inverted, then each entry as
 * {@code " | "} and the rule, with a leading {@code !} for Not: {@code is <set>}, {@code on_top_of <set>},
 * {@code under <set>}, {@code next_to <set>} (a {@link BlockSet#format block set}), {@code touches_air},
 * {@code not_air}, {@code solid}, {@code height <min> <max>}, {@code slope <min> <max>},
 * {@code inside box <x0> <y0> <z0> <x1> <y1> <z1>}, {@code inside shape <kind> <facing> <x0> ... <z1>},
 * {@code chance <percent> <seed>}. For example {@code v1 | is minecraft:grass_block;#minecraft:logs | !height 0 63}.
 * A cell-set region ({@link Region.Cells}, {@link Region.Uploaded}) has no text form.
 */
public final class MaskText {
    private static final String VERSION = "v1";
    private static final String SEPARATOR = " | ";

    private MaskText() {}

    /**
     * The text form of {@code mask}.
     *
     * @throws IllegalArgumentException for an {@link MaskRule.Inside} over a cell set
     */
    public static String encode(EditMask mask) {
        Objects.requireNonNull(mask);
        StringBuilder text = new StringBuilder(VERSION);
        if (mask.invertAll()) text.append(" invert");
        for (MaskEntry entry : mask.entries()) {
            text.append(SEPARATOR);
            if (entry.not()) text.append('!');
            text.append(rule(entry.rule()));
        }
        return text.toString();
    }

    private static String rule(MaskRule rule) {
        return switch (rule) {
            case MaskRule.Is is -> "is " + is.blocks().format();
            case MaskRule.OnTopOf on -> "on_top_of " + on.blocks().format();
            case MaskRule.Under under -> "under " + under.blocks().format();
            case MaskRule.NextTo next -> "next_to " + next.blocks().format();
            case MaskRule.Exposed exposed -> "touches_air";
            case MaskRule.NotAir notAir -> "not_air";
            case MaskRule.Solid solid -> "solid";
            case MaskRule.Height height -> "height " + height.minY() + " " + height.maxY();
            case MaskRule.Slope slope -> "slope " + slope.minStep() + " " + slope.maxStep();
            case MaskRule.Inside inside -> "inside " + region(inside.region());
            case MaskRule.Chance chance -> "chance " + chance.percent() + " " + chance.seed();
        };
    }

    private static String region(Region region) {
        return switch (region) {
            case Region.Cuboid cuboid -> "box " + box(cuboid.box());
            case Region.Shape shape -> "shape " + shape.kind().name().toLowerCase(Locale.ROOT) + " "
                    + shape.facing().name().toLowerCase(Locale.ROOT) + " " + box(shape.box());
            case Region.Cells cells -> throw new IllegalArgumentException("A cell-set region has no text form");
            case Region.Uploaded uploaded -> throw new IllegalArgumentException("An uploaded region has no text form");
        };
    }

    private static String box(Box box) {
        BlockPos a = box.min(), b = box.max();
        return a.x() + " " + a.y() + " " + a.z() + " " + b.x() + " " + b.y() + " " + b.z();
    }

    /**
     * Parses {@link #encode} text.
     *
     * @throws IllegalArgumentException if the text is malformed
     */
    public static EditMask decode(String text) {
        Objects.requireNonNull(text);
        String[] parts = text.split(" \\| ", -1);
        String head = parts[0];
        boolean invert;
        if (head.equals(VERSION)) {
            invert = false;
        } else if (head.equals(VERSION + " invert")) {
            invert = true;
        } else {
            throw new IllegalArgumentException("Not a mask: " + text);
        }
        if (parts.length - 1 > EditMask.MAX_ENTRIES) throw new IllegalArgumentException("Over " + EditMask.MAX_ENTRIES + " rules");
        List<MaskEntry> entries = new ArrayList<>(parts.length - 1);
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            boolean not = part.startsWith("!");
            entries.add(new MaskEntry(parseRule(not ? part.substring(1) : part), not));
        }
        return new EditMask(entries, invert);
    }

    private static MaskRule parseRule(String text) {
        String[] words = text.split(" ", -1);
        try {
            return switch (words[0]) {
                case "is" -> new MaskRule.Is(set(words));
                case "on_top_of" -> new MaskRule.OnTopOf(set(words));
                case "under" -> new MaskRule.Under(set(words));
                case "next_to" -> new MaskRule.NextTo(set(words));
                case "touches_air" -> none(words, new MaskRule.Exposed());
                case "not_air" -> none(words, new MaskRule.NotAir());
                case "solid" -> none(words, new MaskRule.Solid());
                case "height" -> {
                    count(words, 3);
                    yield new MaskRule.Height(Integer.parseInt(words[1]), Integer.parseInt(words[2]));
                }
                case "slope" -> {
                    count(words, 3);
                    yield new MaskRule.Slope(Integer.parseInt(words[1]), Integer.parseInt(words[2]));
                }
                case "inside" -> new MaskRule.Inside(parseRegion(words));
                case "chance" -> {
                    count(words, 3);
                    yield new MaskRule.Chance(Integer.parseInt(words[1]), Long.parseLong(words[2]));
                }
                default -> throw new IllegalArgumentException("Unknown mask rule: " + text);
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed mask rule: " + text, e);
        }
    }

    private static BlockSet set(String[] words) {
        count(words, 2);
        return BlockSet.parse(words[1]);
    }

    private static MaskRule none(String[] words, MaskRule rule) {
        count(words, 1);
        return rule;
    }

    private static Region parseRegion(String[] words) {
        if (words.length < 2) throw new IllegalArgumentException("A region is missing");
        return switch (words[1]) {
            case "box" -> {
                count(words, 8);
                yield new Region.Cuboid(box(words, 2));
            }
            case "shape" -> {
                count(words, 10);
                yield new Region.Shape(box(words, 4), ShapeKind.valueOf(words[2].toUpperCase(Locale.ROOT)),
                        Facing.valueOf(words[3].toUpperCase(Locale.ROOT)));
            }
            default -> throw new IllegalArgumentException("Unknown region kind: " + words[1]);
        };
    }

    private static Box box(String[] words, int at) {
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) v[i] = Integer.parseInt(words[at + i]);
        return Box.of(new BlockPos(v[0], v[1], v[2]), new BlockPos(v[3], v[4], v[5]));
    }

    private static void count(String[] words, int n) {
        if (words.length != n) throw new IllegalArgumentException("Malformed mask rule: " + String.join(" ", words));
    }
}
