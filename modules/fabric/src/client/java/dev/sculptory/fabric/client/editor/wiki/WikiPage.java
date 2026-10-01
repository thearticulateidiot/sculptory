package dev.sculptory.fabric.client.editor.wiki;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A parsed wiki page ({@link WikiParser}).
 *
 * @param id the page id (its file name without {@code .md})
 * @param title the text of its first {@code #} heading, or the id when it has none
 * @param blocks what it shows, in order
 * @param headings its headings in order (each with its unique anchor)
 * @param links every link on it, with the line it is on
 * @param pictures every picture on it, with the line it is on
 * @param problems what is outside the Markdown subset (the page still shows)
 */
public record WikiPage(String id, String title, List<WikiBlock> blocks, List<WikiBlock.Heading> headings,
        List<LinkUse> links, List<PictureUse> pictures, List<WikiProblem> problems) {
    public record LinkUse(int line, WikiLink link) {}

    public record PictureUse(int line, String path) {}

    public WikiPage {
        Objects.requireNonNull(id);
        Objects.requireNonNull(title);
        blocks = List.copyOf(blocks);
        headings = List.copyOf(headings);
        links = List.copyOf(links);
        pictures = List.copyOf(pictures);
        problems = List.copyOf(problems);
    }

    /** The heading with this anchor, if the page has one. */
    public Optional<WikiBlock.Heading> heading(String anchor) {
        return headings.stream().filter(heading -> heading.anchor().equals(anchor)).findFirst();
    }

    public boolean hasAnchor(String anchor) {
        return heading(anchor).isPresent();
    }
}
