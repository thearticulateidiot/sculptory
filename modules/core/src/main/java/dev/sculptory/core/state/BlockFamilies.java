package dev.sculptory.core.state;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.edit.Pattern;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Block families by name, for Better Replace's "Whole family":
 * {@code oak_planks} to {@code spruce_planks} swaps every {@code oak_*} block for its {@code spruce_*} counterpart
 * ({@code oak_stairs} to {@code spruce_stairs}, {@code oak_log} to {@code spruce_log}...). The client computes the
 * swaps and sends them as a {@link Pattern.Remap}.
 *
 * <p><b>Family words.</b> A block's path is split into words at {@code _}; its family is the longest run of words that
 * names a family ({@link #FAMILIES}: the vanilla woods, stones, sandstones, bricks, coppers and the 16 colours), the
 * leftmost of equally long runs. So {@code dark_oak_log} is {@code dark_oak} (never {@code oak}), {@code stripped_oak_log}
 * is {@code oak}, {@code red_sandstone_stairs} is {@code red_sandstone} (not the colour {@code red}). {@code bricks} and
 * {@code tiles} count as {@code brick} and {@code tile}, so {@code stone_bricks} and {@code stone_brick_stairs} are both
 * {@code stone_brick}. Any {@code <word run>_planks} names a family too, so a modded wood's planks
 * ({@code mymod:fir_planks}) bring their family along.
 *
 * <p><b>Counterparts.</b> A family member's counterpart has the other family's words in place of its own, in its own
 * namespace (or else in the other family's block's namespace), when {@code states} knows it: {@code stripped_oak_log} to {@code stripped_spruce_log},
 * {@code stone_bricks} to {@code deepslate_bricks}, {@code mymod:oak_table} to {@code mymod:spruce_table}. When it does
 * not, a few words stand in for each other: {@code log}, {@code stem} and {@code block} ({@code oak_log} to
 * {@code crimson_stem} or {@code bamboo_block}), {@code wood} and {@code hyphae}; and a family's base block (its bare
 * name, its {@code _planks} or its {@code _block}, full blocks only) goes to the other family's base block
 * ({@code oak_planks} to {@code stone}, {@code quartz_block} to {@code purpur_block}). A member without a counterpart is
 * left out (and stays as it is), and so are a colour family's plants (a red tulip is no dyed block).
 */
public final class BlockFamilies {
    /**
     * The family word runs: vanilla woods, stones and their bricks, sandstones, prismarine, nether and end bricks,
     * purpur, quartz, the copper stages (waxed or not) and the 16 dye colours.
     */
    public static final Set<String> FAMILIES = Set.of(
            // Woods (pale_oak arrives in 1.21.4; harmless before).
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo",
            "crimson", "warped",
            // Stones.
            "stone", "cobblestone", "mossy_cobblestone", "smooth_stone", "stone_brick", "mossy_stone_brick",
            "granite", "polished_granite", "diorite", "polished_diorite", "andesite", "polished_andesite",
            "deepslate", "cobbled_deepslate", "polished_deepslate", "deepslate_brick", "deepslate_tile",
            "tuff", "polished_tuff", "tuff_brick", "brick", "mud_brick",
            "sandstone", "smooth_sandstone", "cut_sandstone", "red_sandstone", "smooth_red_sandstone",
            "cut_red_sandstone", "prismarine", "prismarine_brick", "dark_prismarine", "nether_brick",
            "red_nether_brick", "blackstone", "polished_blackstone", "polished_blackstone_brick", "end_stone",
            "end_stone_brick", "purpur", "quartz", "smooth_quartz",
            // Copper, by stage.
            "copper", "exposed_copper", "weathered_copper", "oxidized_copper",
            "waxed_copper", "waxed_exposed_copper", "waxed_weathered_copper", "waxed_oxidized_copper",
            "cut_copper", "exposed_cut_copper", "weathered_cut_copper", "oxidized_cut_copper",
            "waxed_cut_copper", "waxed_exposed_cut_copper", "waxed_weathered_cut_copper", "waxed_oxidized_cut_copper",
            // Dye colours.
            "white", "light_gray", "gray", "black", "brown", "red", "orange", "yellow", "lime", "green", "cyan",
            "light_blue", "blue", "purple", "magenta", "pink");

    /** The dye colours among {@link #FAMILIES}. */
    private static final Set<String> COLOURS = Set.of("white", "light_gray", "gray", "black", "brown", "red", "orange",
            "yellow", "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink");

    /** The longest family word run, in words. */
    private static final int MAX_RUN = 4;
    /** Words that stand in for each other when a counterpart is missing. */
    private static final List<List<String>> STAND_INS = List.of(List.of("log", "stem", "block"),
            List.of("wood", "hyphae"));
    /** The rest of a base block's path after its family words: none, {@code planks} or {@code block}. */
    private static final List<List<String>> BASE_FORMS = List.of(List.of(), List.of("planks"), List.of("block"));

    private BlockFamilies() {}

    /**
     * The family word run of {@code block}'s path ({@code oak} for {@code minecraft:oak_stairs}, {@code dark_oak} for
     * {@code minecraft:dark_oak_planks}; the longest run of words that names a family is tried first), or empty when it
     * belongs to no family.
     */
    public static Optional<String> familyToken(NamespacedId block) {
        Objects.requireNonNull(block);
        Match match = match(path(block), Set.of());
        return match == null ? Optional.empty() : Optional.of(match.token());
    }

    /**
     * The swaps that turn {@code from}'s family into {@code to}'s: one per block of {@code states} in {@code from}'s
     * family whose counterpart in {@code to}'s family exists. Empty when either has no family or both have the same.
     * Sorted by block id.
     */
    public static List<Pattern.BlockSwap> swaps(NamespacedId from, NamespacedId to, StateSpace states) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        Objects.requireNonNull(states);
        Optional<String> fromToken = familyToken(from);
        Optional<String> toToken = familyToken(to);
        if (fromToken.isEmpty() || toToken.isEmpty() || fromToken.get().equals(toToken.get())) return List.of();
        // Modded woods named by their planks count as families while their own blocks are matched.
        Set<String> extra = Set.of(fromToken.get(), toToken.get());
        Map<String, Integer> blocks = blocks(states);
        TreeMap<NamespacedId, NamespacedId> swaps = new TreeMap<>();
        // A colour's plants (red tulips, brown mushrooms) are flowers, not dyed blocks.
        boolean colour = COLOURS.contains(fromToken.get());
        for (String value : new TreeSet<>(blocks.keySet())) {
            if (colour && StateFlags.has(states.flags(blocks.get(value)), StateFlags.VEGETATION)) continue;
            NamespacedId id = new NamespacedId(value);
            String[] words = path(id);
            Match match = match(words, extra);
            if (match == null || !match.token().equals(fromToken.get())) continue;
            NamespacedId counterpart = counterpart(id, words, match, toToken.get(), namespace(to), blocks, states);
            if (counterpart != null && !counterpart.equals(id)) swaps.put(id, counterpart);
            if (swaps.size() == Pattern.Remap.MAX_SWAPS) break;
        }
        List<Pattern.BlockSwap> list = new ArrayList<>(swaps.size());
        swaps.forEach((a, b) -> list.add(new Pattern.BlockSwap(a, b)));
        return List.copyOf(list);
    }

    /** A family word run found in a path: words {@code [start, end)}, whose last word was plural in the path. */
    private record Match(String token, int start, int end, boolean plural) {}

    /** The path's words (after the namespace). */
    private static String[] path(NamespacedId id) {
        String value = id.value();
        return value.substring(value.indexOf(':') + 1).split("_");
    }

    /** {@code bricks} and {@code tiles} as {@code brick} and {@code tile}. */
    private static String singular(String word) {
        return switch (word) {
            case "bricks" -> "brick";
            case "tiles" -> "tile";
            default -> word;
        };
    }

    /**
     * The longest (then leftmost) run of {@code words} naming a family ({@link #FAMILIES}, {@code extra}, or the words
     * before a final {@code planks}), or {@code null}.
     */
    private static Match match(String[] words, Set<String> extra) {
        String[] singular = new String[words.length];
        for (int i = 0; i < words.length; i++) singular[i] = singular(words[i]);
        for (int length = Math.min(MAX_RUN, words.length); length >= 1; length--) {
            for (int start = 0; start + length <= words.length; start++) {
                String token = String.join("_", Arrays.copyOfRange(singular, start, start + length));
                if (FAMILIES.contains(token) || extra.contains(token)) {
                    int last = start + length - 1;
                    return new Match(token, start, start + length, !words[last].equals(singular[last]));
                }
            }
        }
        // Any <run>_planks is a wood family, modded ones too.
        if (words.length >= 2 && words[words.length - 1].equals("planks")) {
            return new Match(String.join("_", Arrays.copyOf(words, words.length - 1)), 0, words.length - 1, false);
        }
        return null;
    }

    /**
     * The counterpart of {@code id} in the {@code toToken} family, or {@code null}: the other family's words in place of
     * the match (plural if the match's last word was, or the other way when only that exists), then with stand-in
     * words, then between base blocks.
     */
    private static NamespacedId counterpart(NamespacedId id, String[] words, Match match, String toToken,
                                            String toNamespace, Map<String, Integer> blocks, StateSpace states) {
        // In the block's own namespace first, then in the other family's (a modded wood's stairs to vanilla stairs).
        List<String> namespaces = namespace(id).equals(toNamespace) ? List.of(toNamespace)
                : List.of(namespace(id), toNamespace);
        List<String> before = List.of(words).subList(0, match.start());
        List<String> after = List.of(words).subList(match.end(), words.length);
        List<List<String>> tokens = tokenForms(toToken, match.plural());
        // 1. The same words around the other family's.
        for (List<String> token : tokens) {
            NamespacedId found = known(namespaces, before, token, after, blocks);
            if (found != null) return found;
        }
        // 2. One stand-in word ("log" for "stem", "wood" for "hyphae").
        for (int i = 0; i < after.size(); i++) {
            for (List<String> group : STAND_INS) {
                if (!group.contains(after.get(i))) continue;
                for (String standIn : group) {
                    if (standIn.equals(after.get(i))) continue;
                    List<String> changed = new ArrayList<>(after);
                    changed.set(i, standIn);
                    for (List<String> token : tokens) {
                        NamespacedId found = known(namespaces, before, token, changed, blocks);
                        if (found != null) return found;
                    }
                }
            }
        }
        // 3. Base block to base block, full blocks only (never the bamboo plant to oak planks).
        if (before.isEmpty() && BASE_FORMS.contains(after) && solid(states, blocks.get(id.value()))) {
            for (List<String> base : BASE_FORMS) {
                if (base.equals(after)) continue;
                for (List<String> token : tokens) {
                    NamespacedId found = known(namespaces, before, token, base, blocks);
                    if (found != null && solid(states, blocks.get(found.value()))) return found;
                }
            }
        }
        return null;
    }

    /** The other family's words: as they are and with the last one plural ({@code brick}/{@code tile}), preferred first. */
    private static List<List<String>> tokenForms(String token, boolean plural) {
        List<String> words = List.of(token.split("_"));
        String last = words.get(words.size() - 1);
        if (!last.equals("brick") && !last.equals("tile")) return List.of(words);
        List<String> pluralWords = new ArrayList<>(words);
        pluralWords.set(words.size() - 1, last + "s");
        return plural ? List.of(pluralWords, words) : List.of(words, List.copyOf(pluralWords));
    }

    private static NamespacedId known(List<String> namespaces, List<String> before, List<String> token,
                                      List<String> after, Map<String, Integer> blocks) {
        List<String> all = new ArrayList<>(before.size() + token.size() + after.size());
        all.addAll(before);
        all.addAll(token);
        all.addAll(after);
        String path = String.join("_", all);
        for (String namespace : namespaces) {
            String text = namespace + ":" + path;
            // Looked up by text: a made-up path need not be a valid id.
            if (blocks.containsKey(text)) return new NamespacedId(text);
        }
        return null;
    }

    private static String namespace(NamespacedId id) {
        return id.value().substring(0, id.value().indexOf(':'));
    }

    private static boolean solid(StateSpace states, Integer handle) {
        return handle != null && StateFlags.has(states.flags(handle), StateFlags.TERRAIN_SOLID);
    }

    /** Every block of {@code states} with its first (default-ordered) state's handle, by id. */
    private static Map<String, Integer> blocks(StateSpace states) {
        Map<String, Integer> blocks = new HashMap<>();
        for (int h = 0; h < states.size(); h++) blocks.putIfAbsent(states.blockId(h).value(), h);
        return blocks;
    }
}
