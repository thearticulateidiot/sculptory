package dev.sculptory.fabric.client.editor.wiki;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/** The pages of a folder ({@code <id>.md}), like the mod's resources: the sample wiki and the repository's docs/wiki. */
public final class DirectoryWikiSource implements WikiSource {
    private final Path directory;

    public DirectoryWikiSource(Path directory) {
        this.directory = directory;
    }

    /** The sample wiki in the test resources ({@code wiki-sample}): every construct of the subset. */
    public static Path sample() {
        URL url = DirectoryWikiSource.class.getResource("/wiki-sample/home.md");
        if (url == null) {
            throw new IllegalStateException("wiki-sample is not on the test classpath");
        }
        try {
            return Path.of(url.toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static WikiLibrary sampleLibrary() {
        return new WikiLibrary(new DirectoryWikiSource(sample()));
    }

    @Override
    public List<String> pageIds() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".md"))
                    .map(name -> name.substring(0, name.length() - 3))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<String> read(String id) {
        Path file = directory.resolve(id + ".md");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
