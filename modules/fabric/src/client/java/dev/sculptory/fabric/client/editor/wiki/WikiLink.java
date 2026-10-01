package dev.sculptory.fabric.client.editor.wiki;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a wiki link goes: another page ({@code other-page.md}, optionally
 * {@code #anchor}), a section of this page ({@code #anchor}) or a website ({@code https://…}). Anything else (a path out
 * of the wiki, a picture, a mail address) is {@link Kind#INVALID}: the reader shows its text without a link.
 *
 * @param pageId the page for {@link Kind#PAGE}, else null
 * @param anchor the section for {@link Kind#PAGE} (may be null) and {@link Kind#SECTION}, else null
 * @param url the address for {@link Kind#EXTERNAL}, else null
 * @param raw the target as written
 */
public record WikiLink(Kind kind, String pageId, String anchor, String url, String raw) {
    public enum Kind { PAGE, SECTION, EXTERNAL, INVALID }

    /** Page ids: lower case letters, digits and single hyphens (docs/wiki/{@code <page-id>}.md). */
    public static final Pattern PAGE_ID = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern PAGE = Pattern.compile("(?:\\./)?(" + PAGE_ID.pattern() + ")\\.md(?:#(.*))?");

    public WikiLink {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(raw);
    }

    /** The link a Markdown target stands for. */
    public static WikiLink parse(String target) {
        String raw = target == null ? "" : target.strip();
        if (raw.startsWith("https://") || raw.startsWith("http://")) {
            return raw.chars().anyMatch(Character::isWhitespace) || raw.length() <= "https://".length()
                    ? invalid(raw) : new WikiLink(Kind.EXTERNAL, null, null, raw, raw);
        }
        if (raw.startsWith("#")) {
            return raw.length() > 1 ? new WikiLink(Kind.SECTION, null, raw.substring(1), null, raw) : invalid(raw);
        }
        Matcher page = PAGE.matcher(raw);
        if (page.matches()) {
            String anchor = page.group(2);
            if (anchor != null && anchor.isEmpty()) {
                anchor = null;
            }
            return new WikiLink(Kind.PAGE, page.group(1), anchor, null, raw);
        }
        return invalid(raw);
    }

    /** A link to page {@code pageId}, at {@code anchor} (null for its top). */
    public static WikiLink page(String pageId, String anchor) {
        return new WikiLink(Kind.PAGE, Objects.requireNonNull(pageId), anchor, null,
                pageId + ".md" + (anchor == null ? "" : "#" + anchor));
    }

    private static WikiLink invalid(String raw) {
        return new WikiLink(Kind.INVALID, null, null, null, raw);
    }

    public boolean isValid() {
        return kind != Kind.INVALID;
    }
}
