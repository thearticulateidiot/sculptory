package dev.sculptory.fabric.client.editor.wiki;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.tool.ToolId;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The repository's wiki ({@code docs/wiki}, bundled into the mod): every page within the Markdown subset, every link to
 * a page or section that exists, every picture there (a PNG at most {@value #MAX_WIDTH} px wide and
 * {@value #MAX_BYTES} bytes), every frozen page id present and every tool's page and section there. Passes while {@code docs/wiki} has no pages yet.
 */
class WikiRepositoryTest {
    static final int MAX_WIDTH = 1200;
    static final int MAX_BYTES = 300 * 1024;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    @Test
    void theRepositoryWikiHasNoBrokenLinksMissingPicturesOrConstructsOutsideTheSubset() throws IOException {
        Path wiki = repositoryRoot().resolve("docs").resolve("wiki");
        if (pageFiles(wiki).isEmpty()) {
            return; // No pages yet: nothing to check.
        }
        assertEquals(List.of(), check(wiki, true), "docs/wiki");
    }

    @Test
    void theSampleWikiPasses(@TempDir Path temp) throws IOException {
        Path wiki = copySample(temp);
        png(wiki.resolve("images/constructs-settings.png"), 400, 120);
        assertEquals(List.of(), check(wiki, false));
    }

    @Test
    void eachKindOfMistakeIsCaught(@TempDir Path temp) throws IOException {
        Path wiki = copySample(temp);
        png(wiki.resolve("images/constructs-settings.png"), 400, 120);
        write(wiki.resolve("broken.md"), """
                # Broken

                A [missing page](nowhere.md), a [missing section](shape-brush.md#nowhere), [here](#nowhere),
                [out of the wiki](../README.md) and <b>HTML</b>.

                ![missing](images/missing.png)

                ![too wide](images/broken-wide.png)

                ![not a png](images/broken-text.png)
                """);
        png(wiki.resolve("images/broken-wide.png"), 1300, 10);
        write(wiki.resolve("images/broken-text.png"), "not a picture");
        write(wiki.resolve("Bad Name.md"), "# Bad\n");
        List<String> problems = check(wiki, false);
        for (String expected : List.of(
                "broken.md:3: no page nowhere",
                "broken.md:3: shape-brush.md has no section #nowhere",
                "broken.md:3: this page has no section #nowhere",
                "broken.md:4: a link goes to a wiki page",
                "broken.md:4: HTML is not supported",
                "broken.md:6: no picture images/missing.png",
                "broken.md:8: images/broken-wide.png is 1300 px wide",
                "broken.md:10: images/broken-text.png is not a PNG",
                "Bad Name.md: a page's file name is its id")) {
            assertTrue(problems.stream().anyMatch(problem -> problem.startsWith(expected)),
                    () -> expected + " not in " + problems);
        }
    }

    @Test
    void theRepositoryWikiNeedsEveryFrozenPageAndEveryToolsSection(@TempDir Path temp) throws IOException {
        write(temp.resolve("home.md"), "# Home\n");
        write(temp.resolve("terrain-brushes.md"), "# Terrain brushes\n");
        List<String> problems = check(temp, true);
        assertTrue(problems.contains("missing page: shape-brush.md (a frozen page id)"), problems.toString());
        assertTrue(problems.contains("terrain-brushes.md has no section #paint (the Paint tool's ?)"),
                problems.toString());
        assertTrue(problems.stream().noneMatch(problem -> problem.startsWith("missing page: home.md")));
    }

    // ---- The check ----

    /**
     * What is wrong with the wiki in {@code wiki}, as "file:line: problem". With {@code repository}, also every frozen
     * page id and every tool's page and section ({@link WikiPages}) must be there.
     */
    static List<String> check(Path wiki, boolean repository) throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, WikiPage> pages = new TreeMap<>();
        for (Path file : pageFiles(wiki)) {
            String name = file.getFileName().toString();
            String id = name.substring(0, name.length() - ".md".length());
            if (!WikiLink.PAGE_ID.matcher(id).matches()) {
                problems.add(name + ": a page's file name is its id: lower case, digits and hyphens");
                continue;
            }
            pages.put(id, WikiParser.parse(id, Files.readString(file, StandardCharsets.UTF_8)));
        }
        for (WikiPage page : pages.values()) {
            String file = page.id() + ".md";
            for (WikiProblem problem : page.problems()) {
                problems.add(file + ":" + problem.line() + ": " + problem.message());
            }
            for (WikiPage.LinkUse use : page.links()) {
                WikiLink link = use.link();
                String where = file + ":" + use.line() + ": ";
                if (link.kind() == WikiLink.Kind.PAGE) {
                    WikiPage target = pages.get(link.pageId());
                    if (target == null) {
                        problems.add(where + "no page " + link.pageId() + " (" + link.raw() + ")");
                    } else if (link.anchor() != null && !target.hasAnchor(link.anchor())) {
                        problems.add(where + target.id() + ".md has no section #" + link.anchor());
                    }
                } else if (link.kind() == WikiLink.Kind.SECTION && !page.hasAnchor(link.anchor())) {
                    problems.add(where + "this page has no section #" + link.anchor());
                }
            }
            for (WikiPage.PictureUse use : page.pictures()) {
                pictureProblem(wiki, use.path()).ifPresent(problem ->
                        problems.add(file + ":" + use.line() + ": " + problem));
            }
        }
        if (repository) {
            for (String id : WikiPages.FROZEN) {
                if (!pages.containsKey(id)) {
                    problems.add("missing page: " + id + ".md (a frozen page id)");
                }
            }
            List<Map.Entry<ToolId, WikiPages.Target>> tools = new ArrayList<>(WikiPages.tools().entrySet());
            tools.sort(Comparator.comparing(entry -> entry.getKey().value()));
            for (Map.Entry<ToolId, WikiPages.Target> tool : tools) {
                WikiPages.Target target = tool.getValue();
                WikiPage page = pages.get(target.pageId());
                if (page != null && target.anchor() != null && !page.hasAnchor(target.anchor())) {
                    problems.add(target.pageId() + ".md has no section #" + target.anchor() + " (the "
                            + toolName(tool.getKey().value()) + " tool's ?)");
                }
            }
        }
        return problems.stream().distinct().toList();
    }

    private static String toolName(String id) {
        return Character.toUpperCase(id.charAt(0)) + id.substring(1);
    }

    /** What is wrong with the picture at {@code path}, if anything. Reads only its header. */
    private static Optional<String> pictureProblem(Path wiki, String path) throws IOException {
        Path file = wiki.resolve(path).normalize();
        if (!file.startsWith(wiki.normalize()) || !Files.isRegularFile(file)) {
            return Optional.of("no picture " + path);
        }
        long size = Files.size(file);
        byte[] header = new byte[24];
        int read;
        try (InputStream in = Files.newInputStream(file)) {
            read = in.readNBytes(header, 0, header.length);
        }
        for (int i = 0; i < PNG.length; i++) {
            if (read < 24 || header[i] != PNG[i]) {
                return Optional.of(path + " is not a PNG");
            }
        }
        int width = (header[16] & 0xFF) << 24 | (header[17] & 0xFF) << 16 | (header[18] & 0xFF) << 8 | header[19] & 0xFF;
        if (width > MAX_WIDTH) {
            return Optional.of(path + " is " + width + " px wide (at most " + MAX_WIDTH + ")");
        }
        if (size > MAX_BYTES) {
            return Optional.of(path + " is " + size / 1024 + " KB (at most " + MAX_BYTES / 1024 + ")");
        }
        return Optional.empty();
    }

    private static List<Path> pageFiles(Path wiki) throws IOException {
        if (!Files.isDirectory(wiki)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(wiki)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".md") && Files.isRegularFile(file))
                    .sorted()
                    .toList();
        }
    }

    /** The repository's root: the nearest folder up from here with settings.gradle and docs. */
    static Path repositoryRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle")) && Files.isDirectory(dir.resolve("docs"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("No repository root above " + Path.of("").toAbsolutePath());
    }

    private static Path copySample(Path temp) throws IOException {
        Path wiki = temp.resolve("wiki");
        Files.createDirectories(wiki.resolve("images"));
        for (Path file : pageFiles(DirectoryWikiSource.sample())) {
            Files.copy(file, wiki.resolve(file.getFileName().toString()));
        }
        return wiki;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void png(Path file, int width, int height) throws IOException {
        Files.createDirectories(file.getParent());
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", file.toFile());
    }
}
