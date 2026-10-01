package dev.sculptory.fabric.gametest;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import net.minecraft.test.TestFunction;
import org.slf4j.LoggerFactory;

/**
 * Runs only some GameTests. Fabric API 0.116.4 has no filter of its own, so {@code TestServerFilterMixin} passes the
 * test list through {@link #apply} before the headless test server is built.
 *
 * <p>The system property {@value #PROPERTY} (set by {@code -PgameTestFilter=...} on {@code runGameTest} and
 * {@code runFidelityGameTest}) is a comma-separated list. A test runs when its name ({@code <class>.<method>}, in lower
 * case, as the log and report show it) or its batch id contains one of the entries, ignoring case. Without the
 * property every test runs. A filter that matches nothing fails the run, so a typo never passes with nothing tested.
 */
public final class GameTestFilter {
    public static final String PROPERTY = "sculptory.gametest.filter";

    private GameTestFilter() {}

    public static Collection<TestFunction> apply(Collection<TestFunction> tests) {
        String filter = System.getProperty(PROPERTY, "").trim();
        if (filter.isEmpty()) return tests;
        List<String> entries = Arrays.stream(filter.split(","))
                .map(entry -> entry.trim().toLowerCase(Locale.ROOT))
                .filter(entry -> !entry.isEmpty())
                .toList();
        List<TestFunction> kept = tests.stream().filter(test -> matches(test, entries)).toList();
        if (kept.isEmpty()) {
            throw new IllegalArgumentException("No GameTest matches " + PROPERTY + "=" + filter + " (of " + tests.size()
                    + " tests; entries match test names <class>.<method> and batch ids)");
        }
        LoggerFactory.getLogger("sculptory").info("GameTest filter '{}': running {} of {} tests", filter,
                kept.size(), tests.size());
        return kept;
    }

    private static boolean matches(TestFunction test, List<String> entries) {
        String name = test.templatePath().toLowerCase(Locale.ROOT);
        String batch = test.batchId().toLowerCase(Locale.ROOT);
        for (String entry : entries) {
            if (name.contains(entry) || batch.contains(entry)) return true;
        }
        return false;
    }
}
