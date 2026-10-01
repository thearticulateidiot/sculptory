package dev.sculptory.fabric.client.editor.demo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The video's chapter list from a run: a line per chapter that played, {@code m:ss Title}, as YouTube reads chapters
 * from a video's description (the first at 0:00, each at least {@link #MIN_GAP_MS} long). Times count from the Enter
 * press, which is the frame the "Press Enter" prompt goes, so the recording is trimmed to start there.
 */
public final class DemoChapters {
    public static final String FILE = "chapters.txt";
    /** YouTube's shortest chapter. */
    public static final long MIN_GAP_MS = 10_000;

    private DemoChapters() {}

    /** The chapter that holds each step id. */
    public static <C> Map<String, DemoChapter<C>> byStep(List<DemoChapter<C>> chapters) {
        return chapters.stream().flatMap(chapter -> chapter.steps().stream().map(step -> Map.entry(step.id(), chapter)))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * The chapter lines: each chapter at the start of its first step that played. A chapter none of whose steps played
     * is left out; one starting under {@link #MIN_GAP_MS} after the line before is folded into it (YouTube would drop
     * the whole list). The first line reads 0:00.
     *
     * @param title a chapter's title from its key
     */
    public static <C> List<String> lines(List<DemoChapter<C>> chapters, List<DemoRunner.Result> results,
            Function<String, String> title) {
        List<String> lines = new ArrayList<>();
        long last = Long.MIN_VALUE;
        for (DemoChapter<C> chapter : chapters) {
            Optional<Long> start = chapter.steps().stream()
                    .flatMap(step -> results.stream().filter(r -> r.id().equals(step.id())))
                    .filter(DemoRunner.Result::played)
                    .map(DemoRunner.Result::offsetMs)
                    .findFirst();
            if (start.isEmpty()) {
                continue;
            }
            long at = lines.isEmpty() ? 0 : start.get();
            if (!lines.isEmpty() && at - last < MIN_GAP_MS) {
                continue;
            }
            lines.add(clock(at) + " " + title.apply(chapter.titleKey()));
            last = at;
        }
        return lines;
    }

    /** {@code m:ss}, or {@code h:mm:ss} from an hour. */
    public static String clock(long ms) {
        long seconds = Math.max(0, ms) / 1000;
        if (seconds >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
