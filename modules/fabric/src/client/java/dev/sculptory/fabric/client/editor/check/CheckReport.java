package dev.sculptory.fabric.client.editor.check;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The play check's results: one line per check (PASS, FAIL or SKIPPED, with the reason), the pictures with what a
 * reviewer should look for in each, and setup notes. Written to {@code report.txt} after every scenario, so a run that
 * dies part way still leaves what it found. Pure: no game types.
 */
public final class CheckReport {
    public static final String FILE = "report.txt";

    public enum Status { PASS, FAIL, SKIPPED }

    /** One check of a scenario. */
    public record Result(String scenario, String check, Status status, String detail) {
        public Result {
            Objects.requireNonNull(scenario);
            Objects.requireNonNull(check);
            Objects.requireNonNull(status);
            detail = detail == null ? "" : detail.replaceAll("\\s*\\R\\s*", " ").strip();
        }

        String line() {
            return String.format(Locale.ROOT, "%-7s %s / %s%s", status, scenario, check,
                    detail.isEmpty() ? "" : " -- " + detail);
        }
    }

    /** A screenshot and what to look for in it. */
    public record Picture(String file, String scenario, String lookFor) {
        String line() {
            return file + "  [" + scenario + "] " + lookFor;
        }
    }

    private final String title;
    private final List<String> notes = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();
    private final List<Picture> pictures = new ArrayList<>();

    public CheckReport(String title) {
        this.title = Objects.requireNonNull(title);
    }

    public synchronized void note(String line) {
        notes.add(line);
    }

    public synchronized void add(Result result) {
        results.add(result);
    }

    public void pass(String scenario, String check, String detail) {
        add(new Result(scenario, check, Status.PASS, detail));
    }

    public void fail(String scenario, String check, String detail) {
        add(new Result(scenario, check, Status.FAIL, detail));
    }

    public void skip(String scenario, String check, String detail) {
        add(new Result(scenario, check, Status.SKIPPED, detail));
    }

    public synchronized void picture(Picture picture) {
        pictures.add(picture);
    }

    public synchronized List<Result> results() {
        return List.copyOf(results);
    }

    public synchronized List<Picture> pictures() {
        return List.copyOf(pictures);
    }

    public synchronized long count(Status status) {
        return results.stream().filter(result -> result.status() == status).count();
    }

    /** The report's text. */
    public synchronized String text() {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(title).append('\n');
        out.append(String.format(Locale.ROOT, "# %d checks: %d PASS, %d FAIL, %d SKIPPED; %d pictures%n",
                results.size(), count(Status.PASS), count(Status.FAIL), count(Status.SKIPPED), pictures.size()));
        for (String note : notes) {
            out.append("# ").append(note).append('\n');
        }
        out.append("\n== Failed ==\n");
        results.stream().filter(result -> result.status() == Status.FAIL)
                .forEach(result -> out.append(result.line()).append('\n'));
        out.append("\n== Results ==\n");
        results.forEach(result -> out.append(result.line()).append('\n'));
        out.append("\n== Pictures (what to look for) ==\n");
        pictures.forEach(picture -> out.append(picture.line()).append('\n'));
        return out.toString();
    }

    /** Writes {@code report.txt} into {@code dir} (replaced whole, never half written). */
    public Path write(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(FILE);
        Path partial = dir.resolve(FILE + ".partial");
        Files.writeString(partial, text(), StandardCharsets.UTF_8);
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return file;
    }
}
