package dev.sculptory.fabric.client.editor.wiki;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.wiki.WikiBlock.Span;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A wiki page laid out as one text column of a given width, in page units ({@link PageScale}) relative to the
 * column's top left: what to draw (in order), where its links and pictures are, and where each heading starts. Built
 * by {@link #build}; pure apart from asking {@link WikiPictures} for picture sizes.
 *
 * <p>Text lines are {@link #LINE_GAP} apart (about 1.45 times the font's height), blocks {@link #BLOCK_GAP}, with
 * more room above headings, which are drawn larger ({@code #}, {@code ##}) or bold ({@code ###}). Pictures are
 * thumbnails ({@link #thumbnail}) with their alt text as a small caption; a click on one shows it full size
 * ({@link PictureOverlay}). Bold and italic text is drawn with Minecraft's formatting codes ({@code §l}, {@code §o}),
 * which its font measures and draws; the parser keeps {@code §} out of page text.
 */
final class PageLayout {
    /** Extra space between two lines of text (the font is 9 high: a line every 13). */
    static final int LINE_GAP = 4;
    /** Space between blocks (a paragraph starts 19 below the last line of the one before)... */
    static final int BLOCK_GAP = 10;
    /** ...and between list items (15 apart, where lines are 13). */
    static final int ITEM_GAP = 6;
    /** Space above a {@code #}/{@code ##} and a {@code ###} heading, and between a heading and what follows it. */
    static final int HEADING_GAP = 16;
    static final int SUBHEADING_GAP = 12;
    static final int AFTER_HEADING = 6;
    /** Space inside a code chip left and right of its text, and above and below it. */
    static final int CHIP_PAD = 3;
    static final int CHIP_PAD_Y = 2;
    /** Space inside a table cell. */
    static final int CELL_PAD = 4;
    /** A callout: an accent strip at its left, its text this far in, and this much space above and below it. */
    static final int CALLOUT_STRIP = 2;
    static final int CALLOUT_INDENT = 9;
    static final int CALLOUT_PAD = 2;
    /** A bullet list's marker column. */
    static final int BULLET_WIDTH = 10;
    /** A picture's thumbnail is at most this share of the column wide... */
    static final double THUMBNAIL_SHARE = 0.4;
    /** ...but may be this wide in a narrow column... */
    static final int THUMBNAIL_MIN = 80;
    /** ...and a picture this many times wider than high may take the column's width (it stays low). */
    static final int STRIP_ASPECT = 4;
    /** Space between a picture and its caption. */
    static final int CAPTION_GAP = 3;
    /** Where a heading goes when a link jumps to it: this far below the top of the view. */
    static final int ANCHOR_MARGIN = 3;

    sealed interface Op permits TextOp, FillOp, OutlineOp, PictureOp, UnderlineOp {
        int top();

        int bottom();
    }

    /** A text drawn {@code scale} times the page's text size (1 for body text), with its top left at (x, y). */
    record TextOp(int x, int y, String text, int color, boolean shadow, int lineHeight, float scale) implements Op {
        public int top() {
            return y;
        }

        public int bottom() {
            return y + lineHeight;
        }
    }

    record FillOp(Rect rect, int color) implements Op {
        public int top() {
            return rect.y();
        }

        public int bottom() {
            return rect.bottom();
        }
    }

    record OutlineOp(Rect rect, int color) implements Op {
        public int top() {
            return rect.y();
        }

        public int bottom() {
            return rect.bottom();
        }
    }

    record PictureOp(Rect rect, WikiPictures.Picture picture) implements Op {
        public int top() {
            return rect.y();
        }

        public int bottom() {
            return rect.bottom();
        }
    }

    /** A link's underline, drawn only while the pointer is over that link. */
    record UnderlineOp(Rect rect, WikiLink link, int color) implements Op {
        public int top() {
            return rect.y();
        }

        public int bottom() {
            return rect.bottom();
        }
    }

    /** Where a link's text is (one box per piece of it). */
    record LinkBox(Rect rect, WikiLink link) {}

    /** Where a picture's thumbnail is (with its frame), with the picture and its alt text. */
    record PictureBox(Rect rect, String alt, String path, WikiPictures.Picture picture) {}

    final int width;
    final List<Op> ops;
    final List<LinkBox> links;
    final List<PictureBox> pictures;
    /** Heading anchors to the y a jump to them scrolls to. */
    final Map<String, Integer> anchors;
    final int height;

    private PageLayout(int width, List<Op> ops, List<LinkBox> links, List<PictureBox> pictures,
            Map<String, Integer> anchors, int height) {
        this.width = width;
        this.ops = List.copyOf(ops);
        this.links = List.copyOf(links);
        this.pictures = List.copyOf(pictures);
        this.anchors = Map.copyOf(anchors);
        this.height = height;
    }

    /** The link under a point (relative to the column), if any. */
    Optional<WikiLink> linkAt(double x, double y) {
        for (LinkBox box : links) {
            if (box.rect().contains(x, y)) {
                return Optional.of(box.link());
            }
        }
        return Optional.empty();
    }

    Optional<PictureBox> pictureAt(double x, double y) {
        for (PictureBox box : pictures) {
            if (box.rect().contains(x, y)) {
                return Optional.of(box);
            }
        }
        return Optional.empty();
    }

    /** Only a line of text (a page that isn't there). */
    static PageLayout line(String text, int width, TextMeasure measure, Theme theme) {
        Builder builder = new Builder(Math.max(1, width), measure, theme, WikiPictures.NONE, Translator.KEYS,
                PageScale.PLAIN);
        int y = 0;
        int pitch = measure.lineHeight() + LINE_GAP;
        for (String line : TextLayout.wrap(measure, text, builder.width)) {
            builder.ops.add(new TextOp(0, y, line, theme.textDim, theme.textShadow, measure.lineHeight(), 1));
            y += pitch;
        }
        return builder.done(y - LINE_GAP);
    }

    /**
     * Lays {@code page} out as a column {@code width} wide. With {@code skipTitle} its leading {@code #} title is left
     * out (the window shows it above the page); its anchor still goes to the top.
     */
    static PageLayout build(WikiPage page, int width, TextMeasure measure, Theme theme, WikiPictures pictures,
            Translator tr, boolean skipTitle, PageScale scale) {
        Builder builder = new Builder(Math.max(1, width), measure, theme, pictures, tr, scale);
        List<WikiBlock> blocks = page.blocks();
        if (skipTitle && !blocks.isEmpty() && blocks.get(0) instanceof WikiBlock.Heading title && title.level() == 1) {
            builder.anchors.put(title.anchor(), 0);
            blocks = blocks.subList(1, blocks.size());
        }
        int bottom = builder.blocks(blocks, 0, builder.width, 0);
        return builder.done(bottom);
    }

    /** The text as drawn in a style: Minecraft's bold and italic codes in front of it. */
    static String styled(String text, boolean bold, boolean italic) {
        if (!bold && !italic) {
            return text;
        }
        return (bold ? "§l" : "") + (italic ? "§o" : "") + text;
    }

    /**
     * A picture's thumbnail size in page units in a column {@code column} wide: at most {@link #THUMBNAIL_SHARE} of
     * the column (or {@link #THUMBNAIL_MIN}, or the column in a narrower one; a strip at least {@link #STRIP_ASPECT}
     * times wider than high may take the whole column), no higher than three quarters of that width, and never larger
     * than its own size: one of its pixels to one of the screen's ({@code pagePixels} per page unit).
     */
    static int[] thumbnail(int pictureWidth, int pictureHeight, int column, double pagePixels) {
        int boxWidth = pictureWidth >= STRIP_ASPECT * pictureHeight ? column
                : Math.max((int) (column * THUMBNAIL_SHARE), Math.min(column, THUMBNAIL_MIN));
        boxWidth = Math.max(1, Math.min(column, boxWidth));
        int boxHeight = Math.max(1, boxWidth * 3 / 4);
        double nativeWidth = pictureWidth / pagePixels;
        double nativeHeight = pictureHeight / pagePixels;
        double fit = Math.min(1, Math.min(boxWidth / nativeWidth, boxHeight / nativeHeight));
        int width = Math.max(1, (int) Math.floor(nativeWidth * fit + 1e-6));
        int height = Math.max(1, (int) Math.round(nativeHeight * width / nativeWidth));
        return new int[] {width, Math.min(height, Math.max(1, (int) Math.ceil(nativeHeight)))};
    }

    private static final class Builder {
        final int width;
        final TextMeasure measure;
        final Theme theme;
        final WikiPictures pictureSource;
        final Translator tr;
        final PageScale scale;
        final List<Op> ops = new ArrayList<>();
        final List<LinkBox> links = new ArrayList<>();
        final List<PictureBox> pictures = new ArrayList<>();
        final Map<String, Integer> anchors = new HashMap<>();

        Builder(int width, TextMeasure measure, Theme theme, WikiPictures pictures, Translator tr, PageScale scale) {
            this.width = width;
            this.measure = measure;
            this.theme = theme;
            this.pictureSource = pictures;
            this.tr = tr;
            this.scale = scale;
        }

        PageLayout done(int height) {
            return new PageLayout(width, ops, links, pictures, anchors, Math.max(0, height));
        }

        /** How text is drawn: its colour, bold, shadow and size against the page's text. */
        record Style(int color, boolean bold, boolean shadow, float scale) {}

        Style body(int color) {
            return new Style(color, false, false, 1);
        }

        int lineHeight(float textScale) {
            return (int) Math.ceil(measure.lineHeight() * textScale - 1e-4);
        }

        // ---- Blocks ----

        /** Lays out {@code blocks} from {@code y} in the column at {@code x}, {@code width} wide; returns the bottom. */
        int blocks(List<WikiBlock> blocks, int x, int width, int y) {
            WikiBlock previous = null;
            for (WikiBlock block : blocks) {
                if (previous != null) {
                    y += gapBetween(previous, block);
                }
                y = block(block, x, width, y);
                previous = block;
            }
            return y;
        }

        private static int gapBetween(WikiBlock previous, WikiBlock block) {
            if (block instanceof WikiBlock.Heading heading) {
                return heading.level() <= 2 ? HEADING_GAP : SUBHEADING_GAP;
            }
            if (previous instanceof WikiBlock.Heading) {
                return AFTER_HEADING;
            }
            return BLOCK_GAP;
        }

        private int block(WikiBlock block, int x, int width, int y) {
            return switch (block) {
                case WikiBlock.Heading heading -> heading(heading, x, width, y);
                case WikiBlock.Paragraph paragraph -> flow(paragraph.spans(), x, width, y, body(theme.text));
                case WikiBlock.ListBlock list -> list(list, x, width, y, 0);
                case WikiBlock.Table table -> table(table, x, width, y);
                case WikiBlock.Callout callout -> callout(callout, x, width, y);
                case WikiBlock.Rule rule -> {
                    ops.add(new FillOp(new Rect(x, y + 3, width, 1), theme.separator));
                    yield y + 7;
                }
                case WikiBlock.Picture picture -> picture(picture, x, width, y);
            };
        }

        /** {@code #} and {@code ##} larger, {@code ###} bold at the text's size; no rule under them. */
        private int heading(WikiBlock.Heading heading, int x, int width, int y) {
            anchors.put(heading.anchor(), Math.max(0, y - ANCHOR_MARGIN));
            boolean large = heading.level() <= 2;
            Style style = new Style(theme.titleText, true, large && theme.titleShadow,
                    large ? scale.headingScale() : 1);
            return flow(heading.spans(), x, width, y, style);
        }

        private int list(WikiBlock.ListBlock list, int x, int width, int y, int level) {
            int markerWidth;
            if (list.ordered()) {
                int last = list.start() + list.items().size() - 1;
                markerWidth = Math.max(measure.width(list.start() + "."), measure.width(last + ".")) + 5;
            } else {
                markerWidth = BULLET_WIDTH;
            }
            int textX = x + markerWidth;
            int textWidth = Math.max(1, width - markerWidth);
            int lineHeight = measure.lineHeight();
            int number = list.start();
            boolean first = true;
            for (WikiBlock.Item item : list.items()) {
                if (!first) {
                    y += ITEM_GAP;
                }
                first = false;
                if (list.ordered()) {
                    ops.add(new TextOp(x, y, number + ".", theme.textDim, theme.textShadow, lineHeight, 1));
                } else {
                    Rect dot = new Rect(x + 2, y + 3, 3, 3);
                    ops.add(level == 0 ? new FillOp(dot, theme.textDim) : new OutlineOp(dot, theme.textDim));
                }
                number++;
                y = flow(item.spans(), textX, textWidth, y, body(theme.text));
                if (item.children() != null) {
                    y = list(item.children(), textX, textWidth, y + ITEM_GAP, level + 1);
                }
            }
            return y;
        }

        private int table(WikiBlock.Table table, int x, int width, int y) {
            int columns = Math.max(1, table.columns());
            List<List<List<Span>>> rows = new ArrayList<>();
            rows.add(table.header());
            rows.addAll(table.rows());
            int[] natural = new int[columns];
            int[] minimum = new int[columns];
            for (int r = 0; r < rows.size(); r++) {
                List<List<Span>> row = rows.get(r);
                for (int c = 0; c < row.size(); c++) {
                    Style style = new Style(theme.text, r == 0, false, 1);
                    List<List<Piece>> words = words(row.get(c), style);
                    natural[c] = Math.max(natural[c], lineWidth(words) + 2 * CELL_PAD + 1);
                    for (List<Piece> word : words) {
                        minimum[c] = Math.max(minimum[c], wordWidth(word) + 2 * CELL_PAD + 1);
                    }
                }
            }
            int[] widths = columnWidths(natural, minimum, width - 1);
            int tableWidth = 1;
            for (int w : widths) {
                tableWidth += w;
            }
            int top = y;
            int rowTop = y + 1;
            List<Integer> rowLines = new ArrayList<>();
            for (int r = 0; r < rows.size(); r++) {
                List<List<Span>> row = rows.get(r);
                int start = ops.size();
                int bottom = rowTop + CELL_PAD + measure.lineHeight();
                int cellX = x + 1;
                for (int c = 0; c < columns; c++) {
                    if (c < row.size()) {
                        Style style = new Style(r == 0 ? theme.titleText : theme.text, r == 0, false, 1);
                        int cellBottom = flow(row.get(c), cellX + CELL_PAD, Math.max(1, widths[c] - 1 - 2 * CELL_PAD),
                                rowTop + CELL_PAD, style);
                        bottom = Math.max(bottom, cellBottom);
                    }
                    cellX += widths[c];
                }
                int rowBottom = bottom + CELL_PAD;
                if (r == 0) {
                    ops.add(start, new FillOp(new Rect(x, rowTop, tableWidth, rowBottom - rowTop), theme.headingBand));
                }
                rowLines.add(rowBottom);
                rowTop = rowBottom + 1;
            }
            int tableBottom = rowTop;
            ops.add(new OutlineOp(new Rect(x, top, tableWidth, tableBottom - top), theme.separator));
            for (int i = 0; i < rowLines.size() - 1; i++) {
                ops.add(new FillOp(new Rect(x, rowLines.get(i), tableWidth, 1), theme.separator));
            }
            int lineX = x;
            for (int c = 0; c < columns - 1; c++) {
                lineX += widths[c];
                ops.add(new FillOp(new Rect(lineX, top, 1, tableBottom - top), theme.separator));
            }
            return tableBottom;
        }

        /**
         * Column widths (each including one grid line): the natural widths when they fit; otherwise each column's
         * longest word plus a share of what is left, in proportion to how much more it would like.
         */
        static int[] columnWidths(int[] natural, int[] minimum, int width) {
            int columns = natural.length;
            int naturalSum = 0;
            int minimumSum = 0;
            for (int c = 0; c < columns; c++) {
                naturalSum += natural[c];
                minimumSum += minimum[c];
            }
            int[] widths = new int[columns];
            if (naturalSum <= width) {
                return natural.clone();
            }
            if (minimumSum >= width) {
                int given = 0;
                for (int c = 0; c < columns; c++) {
                    widths[c] = Math.max(1, minimumSum == 0 ? width / columns
                            : (int) ((long) minimum[c] * width / minimumSum));
                    given += widths[c];
                }
                widths[columns - 1] += Math.max(0, width - given);
                return widths;
            }
            int extra = width - minimumSum;
            int wanted = naturalSum - minimumSum;
            int given = 0;
            for (int c = 0; c < columns; c++) {
                widths[c] = minimum[c] + (int) ((long) extra * (natural[c] - minimum[c]) / Math.max(1, wanted));
                given += widths[c];
            }
            widths[columns - 1] += Math.max(0, width - given);
            return widths;
        }

        /** A callout: an accent strip at the left of its blocks, no box. */
        private int callout(WikiBlock.Callout callout, int x, int width, int y) {
            int bottom = blocks(callout.blocks(), x + CALLOUT_INDENT, Math.max(1, width - CALLOUT_INDENT),
                    y + CALLOUT_PAD) + CALLOUT_PAD;
            ops.add(new FillOp(new Rect(x, y, CALLOUT_STRIP, bottom - y), theme.accentDim));
            return bottom;
        }

        /** A thumbnail in a subtle frame, centred, with the alt text under it as a small caption. */
        private int picture(WikiBlock.Picture picture, int x, int width, int y) {
            Optional<WikiPictures.Picture> loaded;
            try {
                loaded = pictureSource.picture(picture.path());
            } catch (RuntimeException e) {
                loaded = Optional.empty();
            }
            if (loaded.isEmpty()) {
                return flow(List.of(Span.plain(tr.translate("sculptory.wiki.picture_not_found", picture.path()))),
                        x, width, y, body(theme.textDim));
            }
            WikiPictures.Picture shown = loaded.get();
            int[] size = thumbnail(shown.width(), shown.height(), Math.max(1, width - 2), scale.pagePixels());
            Rect frame = new Rect(x + (width - size[0] - 2) / 2, y, size[0] + 2, size[1] + 2);
            ops.add(new PictureOp(frame.inset(1), shown));
            ops.add(new OutlineOp(frame, theme.windowBorder));
            pictures.add(new PictureBox(frame, picture.alt(), picture.path(), shown));
            String alt = picture.alt().strip();
            if (alt.isEmpty()) {
                return frame.bottom();
            }
            return caption(alt, x, width, Math.max(frame.width(), width * 3 / 5), frame.bottom() + CAPTION_GAP);
        }

        /** Lines of plain text centred in a box {@code boxWidth} wide in the column, at the UI's own text size. */
        private int caption(String text, int x, int width, int boxWidth, int y) {
            float textScale = scale.captionScale();
            int box = Math.min(width, boxWidth);
            int wrapWidth = Math.max(1, (int) Math.floor(box / textScale + 1e-4));
            int lineHeight = lineHeight(textScale);
            int pitch = lineHeight + Math.max(1, Math.round(LINE_GAP * textScale));
            int lineY = y;
            for (String line : TextLayout.wrap(measure, text, wrapWidth)) {
                int lineWidth = scaled(measure.width(line), textScale);
                ops.add(new TextOp(x + Math.max(0, (width - lineWidth) / 2), lineY, line, theme.textDim,
                        theme.textShadow, lineHeight, textScale));
                lineY += pitch;
            }
            return lineY - pitch + lineHeight;
        }

        // ---- Text ----

        /** One run of a word in one style: a code chip, or text without spaces. */
        private record Piece(String text, Span span, boolean bold, float scale) {}

        private List<List<Piece>> words(List<Span> spans, Style style) {
            List<List<Piece>> words = new ArrayList<>();
            List<Piece> word = null;
            boolean space = false;
            for (Span span : spans) {
                boolean spanBold = style.bold() || span.bold();
                if (span.code()) {
                    if (space || word == null) {
                        word = new ArrayList<>();
                        words.add(word);
                    }
                    word.add(new Piece(span.text(), span, spanBold, style.scale()));
                    space = false;
                    continue;
                }
                String text = span.text();
                int i = 0;
                while (i < text.length()) {
                    if (text.charAt(i) == ' ') {
                        space = true;
                        i++;
                        continue;
                    }
                    int end = text.indexOf(' ', i);
                    if (end < 0) {
                        end = text.length();
                    }
                    if (space || word == null) {
                        word = new ArrayList<>();
                        words.add(word);
                    }
                    word.add(new Piece(text.substring(i, end), span, spanBold, style.scale()));
                    space = false;
                    i = end;
                }
            }
            return words;
        }

        private static int scaled(int fontWidth, float textScale) {
            return textScale == 1 ? fontWidth : (int) Math.ceil(fontWidth * textScale - 1e-4);
        }

        private int pieceWidth(Piece piece, String text) {
            if (piece.span().code()) {
                return scaled(measure.width(text), piece.scale()) + 2 * CHIP_PAD;
            }
            return scaled(measure.width(styled(text, piece.bold(), piece.span().italic())), piece.scale());
        }

        private int wordWidth(List<Piece> word) {
            int width = 0;
            for (Piece piece : word) {
                width += pieceWidth(piece, piece.text());
            }
            return width;
        }

        /** The width of the words on one line. */
        private int lineWidth(List<List<Piece>> words) {
            int width = 0;
            for (int i = 0; i < words.size(); i++) {
                width += wordWidth(words.get(i)) + (i > 0 ? measure.width(" ") : 0);
            }
            return width;
        }

        /**
         * Lays {@code spans} out as wrapped lines from {@code y} in the column at {@code x}; returns the bottom of the
         * last line. Lines break between words; a word longer than a line is broken where it has to be.
         */
        int flow(List<Span> spans, int x, int width, int y, Style style) {
            List<List<Piece>> words = words(spans, style);
            int lineHeight = lineHeight(style.scale());
            if (words.isEmpty()) {
                return y + lineHeight;
            }
            int pitch = lineHeight + LINE_GAP;
            int space = scaled(measure.width(" "), style.scale());
            int lineY = y;
            int used = 0;
            boolean lineEmpty = true;
            for (List<Piece> word : words) {
                int wordWidth = wordWidth(word);
                int gap = lineEmpty ? 0 : space;
                if (!lineEmpty && used + gap + wordWidth > width) {
                    lineY += pitch;
                    used = 0;
                    gap = 0;
                    lineEmpty = true;
                }
                used += gap;
                if (wordWidth <= width - used) {
                    for (Piece piece : word) {
                        used += place(piece, piece.text(), x + used, lineY, style, lineHeight);
                    }
                    lineEmpty = false;
                    continue;
                }
                // Longer than the line: as much as fits on each line.
                for (Piece piece : word) {
                    String rest = piece.text();
                    while (!rest.isEmpty()) {
                        int room = width - used;
                        int fits = fit(piece, rest, room);
                        if (fits == 0 && !lineEmpty) {
                            lineY += pitch;
                            used = 0;
                            lineEmpty = true;
                            continue;
                        }
                        fits = Math.max(fits, rest.offsetByCodePoints(0, 1));
                        used += place(piece, rest.substring(0, fits), x + used, lineY, style, lineHeight);
                        lineEmpty = false;
                        rest = rest.substring(fits);
                        if (!rest.isEmpty()) {
                            lineY += pitch;
                            used = 0;
                            lineEmpty = true;
                        }
                    }
                }
            }
            return lineY + lineHeight;
        }

        /** How many characters of {@code text} fit {@code room} in the piece's style. */
        private int fit(Piece piece, String text, int room) {
            int low = 0;
            int high = text.length();
            while (low < high) {
                int mid = (low + high + 1) / 2;
                if (pieceWidth(piece, text.substring(0, mid)) <= room) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            return low;
        }

        /**
         * Draws a piece at (x, y) and returns its width. Links are in the accent colour (underlined under the
         * pointer); a code chip is a key cap: a lighter box with a border, its text bright.
         */
        private int place(Piece piece, String text, int x, int y, Style style, int lineHeight) {
            Span span = piece.span();
            int width = pieceWidth(piece, text);
            boolean link = span.link() != null;
            int color = link ? theme.accentHover : style.color();
            String drawn = styled(text, piece.bold(), span.italic());
            if (span.code()) {
                Rect chip = new Rect(x, y - CHIP_PAD_Y, width, lineHeight + 2 * CHIP_PAD_Y - 1);
                ops.add(new FillOp(chip, theme.controlHover));
                ops.add(new OutlineOp(chip, theme.windowBorderActive));
                ops.add(new TextOp(x + CHIP_PAD, y, drawn, link ? color : theme.titleText, false, lineHeight,
                        piece.scale()));
                if (link) {
                    links.add(new LinkBox(chip, span.link()));
                }
            } else {
                ops.add(new TextOp(x, y, drawn, color, style.shadow(), lineHeight, piece.scale()));
                if (link) {
                    links.add(new LinkBox(new Rect(x, y - 1, width, lineHeight + 2), span.link()));
                }
            }
            if (link) {
                ops.add(new UnderlineOp(new Rect(x, y + lineHeight, width, 1), span.link(), color));
            }
            return width;
        }
    }
}
