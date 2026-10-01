package dev.sculptory.core.history;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Undo and redo of entities, with the rule {@link HistoryPrograms} applies to cells. Per recorded entity, by UUID, with {@code expected} what the step (or its undo) left and {@code target}
 * what is being restored ({@code null} meaning absent on either side), and {@code live} the entity with that UUID the
 * world holds now:
 * <ul>
 *   <li>{@code live} already matches the target (both absent, or the same entity unchanged): nothing to do;</li>
 *   <li>{@code live} matches what the step left: it is removed (if present) and the target placed (if any), with the
 *       recorded UUID;</li>
 *   <li>anything else is a conflict (an entity changed since the step, or one where the step left none): kept, not
 *       touched, and counted with the kept blocks, unless the policy is {@link ConflictPolicy#OVERWRITE} (Undo anyway),
 *       which removes it and places the target all the same.</li>
 * </ul>
 * Matching goes through an {@link EntityMatcher} (on the server: without volatile keys such as motion and timers). A
 * step the matcher cannot tell from no change (both sides present and matching: all it did was move or turn the entity,
 * a Tinker edit) is decided with the matcher's {@link EntityMatcher#placementAware() placement-aware} form, which also
 * compares where the entity stands and looks; otherwise its undo would find the target already there and do nothing.
 * Pure decisions; the server removes, places and records.
 */
public final class EntityHistory {
    private EntityHistory() {}

    /** What to do with one recorded entity. */
    public enum Action {
        /** The world already holds the target. */
        SKIP,
        /** Remove the live entity (if any) and place the target (if any). */
        APPLY,
        /** Keep the live entity and count it. */
        CONFLICT
    }

    /**
     * One entity an undo, redo or re-apply decides about.
     *
     * @param expected what the step left ({@code null}: no entity); unused by a re-apply
     * @param target what is restored ({@code null}: no entity)
     */
    public record Step(UUID id, EntityState expected, EntityState target) {
        public Step {
            Objects.requireNonNull(id);
            if (expected == null && target == null) throw new IllegalArgumentException("A step with nothing to do");
        }

        /** A recorded state that says where to look for the entity: the target's if any, else the expected one. */
        public EntityState where() {
            return target != null ? target : expected;
        }
    }

    /** The steps of an undo ({@code redo} false) or a redo of one entry. */
    public static List<Step> steps(HistoryEntry entry, boolean redo) {
        List<Step> steps = new ArrayList<>(entry.record().entities().size());
        for (EntityChange change : entry.record().entities()) {
            if (change.unchanged()) continue;
            steps.add(new Step(change.id(), change.expected(redo), change.target(redo)));
        }
        return steps;
    }

    /**
     * The steps of an Undo anyway (Redo anyway) over a run of entries, listed in the order their steps were applied:
     * per entity, the target of the last entry of the run recording it (the last writer wins, as for cells), with that
     * entry's expected side for where to look. Decided with {@link ConflictPolicy#OVERWRITE}.
     */
    public static List<Step> reapplySteps(List<HistoryEntry> run, boolean redo) {
        LinkedHashMap<UUID, Step> last = new LinkedHashMap<>();
        for (HistoryEntry entry : run) {
            for (EntityChange change : entry.record().entities()) {
                if (change.unchanged()) continue;
                last.remove(change.id()); // keeps the order of the last recording
                last.put(change.id(), new Step(change.id(), change.expected(redo), change.target(redo)));
            }
        }
        return new ArrayList<>(last.values());
    }

    /**
     * Decides about one entity.
     *
     * @param live the entity with the step's UUID as the world holds it now, or {@code null} when there is none
     */
    public static Action decide(Step step, EntityState live, ConflictPolicy policy, EntityMatcher matcher) {
        Objects.requireNonNull(step);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(matcher);
        // A step that only moved or turned the entity (Tinker) looks like no change to the matcher, which leaves where
        // an entity stands out: then where it stands counts.
        if (step.expected() != null && step.target() != null && matcher.matches(step.expected(), step.target())) {
            matcher = matcher.placementAware();
        }
        if (matches(step.target(), live, matcher)) return Action.SKIP;
        if (policy == ConflictPolicy.OVERWRITE || matches(step.expected(), live, matcher)) return Action.APPLY;
        return Action.CONFLICT;
    }

    /** Whether {@code live} is what {@code recorded} says: both absent, or both present and matching. */
    static boolean matches(EntityState recorded, EntityState live, EntityMatcher matcher) {
        if (recorded == null || live == null) return recorded == live;
        return matcher.matches(recorded, live);
    }
}
