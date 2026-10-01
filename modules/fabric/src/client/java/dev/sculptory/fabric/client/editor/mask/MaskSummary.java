package dev.sculptory.fabric.client.editor.mask;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import java.util.ArrayList;
import java.util.List;

/** Masks and block sets as the player reads them: "Not on top of Grass Block · Height 0 to 63". */
public final class MaskSummary {
    /** Entries of a block set named before "+n". */
    static final int NAMED_ENTRIES = 2;

    private MaskSummary() {}

    /** Every rule, joined by " · "; "" for none. */
    public static String rules(List<MaskEntry> entries, BlockCatalog blocks, Translator tr) {
        List<String> parts = new ArrayList<>(entries.size());
        for (MaskEntry entry : entries) parts.add(entry(entry, blocks, tr));
        return String.join(" · ", parts);
    }

    /** One rule, with "Not " before it when flipped. */
    public static String entry(MaskEntry entry, BlockCatalog blocks, Translator tr) {
        String rule = rule(entry.rule(), blocks, tr);
        return entry.not() ? tr.translate("sculptory.mask.not_rule", rule) : rule;
    }

    static String rule(MaskRule rule, BlockCatalog blocks, Translator tr) {
        String name = tr.translate(RuleKind.of(rule).nameKey());
        return switch (rule) {
            case MaskRule.Is is -> name + " " + blocks(is.blocks(), blocks);
            case MaskRule.OnTopOf on -> name + " " + blocks(on.blocks(), blocks);
            case MaskRule.Under under -> name + " " + blocks(under.blocks(), blocks);
            case MaskRule.NextTo next -> name + " " + blocks(next.blocks(), blocks);
            case MaskRule.Height height -> tr.translate("sculptory.mask.summary.height", height.minY(), height.maxY());
            case MaskRule.Slope slope -> tr.translate("sculptory.mask.summary.slope", slope.minStep(), slope.maxStep());
            case MaskRule.Chance chance -> tr.translate("sculptory.mask.summary.chance", chance.percent());
            default -> name;
        };
    }

    /** A block set: its first entries by name ("Stone, #logs"), then "+n" for the rest. */
    public static String blocks(BlockSet set, BlockCatalog blocks) {
        List<String> names = new ArrayList<>();
        for (BlockSet.Entry entry : set.entries()) {
            if (names.size() == NAMED_ENTRIES) break;
            names.add(entry(entry, blocks));
        }
        int rest = set.entries().size() - names.size();
        return String.join(", ", names) + (rest > 0 ? " +" + rest : "");
    }

    /** One entry: a block's name, a tag as {@code #path} ({@code #minecraft:logs} as {@code #logs}), a state's block. */
    public static String entry(BlockSet.Entry entry, BlockCatalog blocks) {
        return switch (entry) {
            case BlockSet.Block block -> blocks.name(BlockDescriptor.of(block.id()));
            case BlockSet.Tag tag -> "#" + (tag.tag().value().startsWith("minecraft:")
                    ? tag.tag().value().substring("minecraft:".length()) : tag.tag().value());
            case BlockSet.State state -> blocks.name(state.state()) + "*";
        };
    }
}
