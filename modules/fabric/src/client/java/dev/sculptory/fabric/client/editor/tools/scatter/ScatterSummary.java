package dev.sculptory.fabric.client.editor.tools.scatter;

import dev.sculptory.core.scatter.Outcome;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.session.ScatterPreviewResult;
import dev.sculptory.fabric.client.session.SessionNotices;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * The scatter preview's HUD summary: "142 placements · 38,000 blocks · skipped: slope 20, spacing 55, collision 3".
 * Skipped columns are listed in the planner's check order ({@link Outcome}), each under a readable name
 * ({@code sculptory.scatter.outcome.<name>}); names this client does not know yet follow, lower-cased. Pure.
 */
public final class ScatterSummary {
    public static final String SUMMARY = "sculptory.scatter.summary";
    public static final String SKIPPED = "sculptory.scatter.summary.skipped";
    public static final String OUTCOME_PREFIX = "sculptory.scatter.outcome.";

    private ScatterSummary() {}

    public static String line(Translator tr, ScatterPreviewResult plan) {
        Objects.requireNonNull(tr);
        String head = tr.translate(SUMMARY, SessionNotices.count(plan.placements().size()),
                SessionNotices.count(plan.totalCells()));
        String skipped = skipped(tr, plan.rejectedCounts());
        return skipped.isEmpty() ? head : head + "  ·  " + tr.translate(SKIPPED, skipped);
    }

    /** "slope 20, spacing 55, collision 3", or "" when nothing was skipped. */
    public static String skipped(Translator tr, Map<String, Integer> counts) {
        StringJoiner list = new StringJoiner(", ");
        TreeMap<String, Integer> unknown = new TreeMap<>(counts);
        for (Outcome outcome : Outcome.values()) {
            Integer count = unknown.remove(outcome.name());
            if (count != null && count > 0) list.add(entry(tr, outcome.name(), count));
        }
        for (Map.Entry<String, Integer> entry : unknown.entrySet()) {
            if (entry.getValue() > 0) list.add(entry(tr, entry.getKey(), entry.getValue()));
        }
        return list.toString();
    }

    private static String entry(Translator tr, String outcome, int count) {
        return outcomeName(tr, outcome) + " " + SessionNotices.count(count);
    }

    /** The readable name of an outcome, e.g. "no ground" for {@code NO_SURFACE}. */
    public static String outcomeName(Translator tr, String outcome) {
        String lower = outcome.toLowerCase(Locale.ROOT);
        return tr.translateOr(OUTCOME_PREFIX + lower, lower.replace('_', ' '));
    }

    /** Every outcome's translation key, for completeness checks. */
    public static List<String> outcomeKeys() {
        List<String> keys = new ArrayList<>();
        for (Outcome outcome : Outcome.values()) keys.add(OUTCOME_PREFIX + outcome.name().toLowerCase(Locale.ROOT));
        return keys;
    }
}
