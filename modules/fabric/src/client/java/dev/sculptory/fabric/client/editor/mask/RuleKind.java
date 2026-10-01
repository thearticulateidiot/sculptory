package dev.sculptory.fabric.client.editor.mask;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.MaskRule;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * The rules the Mask window offers, in its "Add rule" order, with their names and the rule a new
 * one starts as.
 */
public enum RuleKind {
    IS,
    ON_TOP_OF,
    UNDER,
    NEXT_TO,
    TOUCHES_AIR,
    NOT_AIR,
    SOLID,
    HEIGHT,
    SLOPE,
    INSIDE,
    CHANCE;

    /** The block a new block-set rule names. */
    static final BlockSet DEFAULT_BLOCKS = BlockSet.of(new BlockSet.Block(new NamespacedId("minecraft:stone")));
    /** Heights a new height rule and the sliders span: the overworld's build range. */
    public static final int MIN_Y = -64;
    public static final int MAX_Y = 319;

    /** The translation key of its name ("Is one of these blocks"). */
    public String nameKey() {
        return "sculptory.mask.rule." + name().toLowerCase(Locale.ROOT);
    }

    /** Whether its rule names blocks ({@link BlockSet}). */
    public boolean namesBlocks() {
        return this == IS || this == ON_TOP_OF || this == UNDER || this == NEXT_TO;
    }

    /** The kind of {@code rule}. */
    public static RuleKind of(MaskRule rule) {
        return switch (rule) {
            case MaskRule.Is is -> IS;
            case MaskRule.OnTopOf on -> ON_TOP_OF;
            case MaskRule.Under under -> UNDER;
            case MaskRule.NextTo next -> NEXT_TO;
            case MaskRule.Exposed exposed -> TOUCHES_AIR;
            case MaskRule.NotAir notAir -> NOT_AIR;
            case MaskRule.Solid solid -> SOLID;
            case MaskRule.Height height -> HEIGHT;
            case MaskRule.Slope slope -> SLOPE;
            case MaskRule.Inside inside -> INSIDE;
            case MaskRule.Chance chance -> CHANCE;
        };
    }

    /**
     * A new rule of this kind: {@code blocks} for a rule that names blocks (stone when {@code null}), a fresh seed from
     * {@code seeds} for a chance.
     */
    public MaskRule create(BlockSet blocks, LongSupplier seeds) {
        Objects.requireNonNull(seeds);
        BlockSet set = blocks == null ? DEFAULT_BLOCKS : blocks;
        return switch (this) {
            case IS -> new MaskRule.Is(set);
            case ON_TOP_OF -> new MaskRule.OnTopOf(set);
            case UNDER -> new MaskRule.Under(set);
            case NEXT_TO -> new MaskRule.NextTo(set);
            case TOUCHES_AIR -> new MaskRule.Exposed();
            case NOT_AIR -> new MaskRule.NotAir();
            case SOLID -> new MaskRule.Solid();
            case HEIGHT -> new MaskRule.Height(MIN_Y, 63);
            case SLOPE -> new MaskRule.Slope(0, 2);
            case INSIDE -> EditMaskModel.insideSelection();
            case CHANCE -> new MaskRule.Chance(50, seeds.getAsLong());
        };
    }

    /** The block set a rule names, or {@code null}. */
    public static BlockSet blocksOf(MaskRule rule) {
        return switch (rule) {
            case MaskRule.Is is -> is.blocks();
            case MaskRule.OnTopOf on -> on.blocks();
            case MaskRule.Under under -> under.blocks();
            case MaskRule.NextTo next -> next.blocks();
            default -> null;
        };
    }

    /** {@code rule} (a rule naming blocks) naming {@code blocks} instead. */
    public static MaskRule withBlocks(MaskRule rule, BlockSet blocks) {
        return switch (rule) {
            case MaskRule.Is is -> new MaskRule.Is(blocks);
            case MaskRule.OnTopOf on -> new MaskRule.OnTopOf(blocks);
            case MaskRule.Under under -> new MaskRule.Under(blocks);
            case MaskRule.NextTo next -> new MaskRule.NextTo(blocks);
            default -> throw new IllegalArgumentException(rule + " names no blocks");
        };
    }
}
