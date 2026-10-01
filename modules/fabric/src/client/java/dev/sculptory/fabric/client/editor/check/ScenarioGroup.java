package dev.sculptory.fabric.client.editor.check;

import java.util.List;
import java.util.Set;

/**
 * Scenarios that belong together, with the fixture areas they work in and the roles that play them.
 */
public record ScenarioGroup(Set<CheckConfig.Role> roles, List<Fixtures.Fixture> fixtures, List<Scenario> scenarios) {
    public ScenarioGroup {
        roles = Set.copyOf(roles);
        fixtures = List.copyOf(fixtures);
        scenarios = List.copyOf(scenarios);
    }

    /** Singleplayer scenarios. */
    public static ScenarioGroup solo(List<Fixtures.Fixture> fixtures, List<Scenario> scenarios) {
        return new ScenarioGroup(Set.of(CheckConfig.Role.SOLO), fixtures, scenarios);
    }
}
