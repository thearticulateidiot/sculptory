package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Padding;
import dev.sculptory.fabric.client.editor.ui.widget.AbstractButton;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Tutorial window (Help > Tutorial, View > Tutorial, the quick start card's Start tutorial): one slim row per
 * lesson, its number (a check mark once finished), its title and a dim "6 steps"; the lesson's one-line summary is the
 * row's tooltip. A click on a row (or Enter on it) starts the lesson, from its first step again once finished. While a
 * lesson is paused or in progress from an earlier game a single "Resume" banner at the top continues it; otherwise a
 * short dim line says how lessons work. Starting or resuming a lesson closes the window, so the lesson card and the
 * world are in view. {@link #refresh} is called every frame while it is open.
 */
public final class TutorialWindow {
    /** A lesson row's height (and the Resume banner's): roomy, so the list reads easily. */
    public static final int ROW_HEIGHT = 20;
    /** The column at a row's left holding its number or check mark. */
    static final int MARK_WIDTH = 16;
    private static final String WINDOW = Lesson.PREFIX + "window.";

    private final TutorialRunner runner;
    private final Translator tr;
    private final ResumeBanner resume;
    private final Label intro;
    private final List<LessonRow> rows = new ArrayList<>();
    private final Node root;
    private int shownVersion = -1;

    /** @param started a lesson started or resumed from here (the window closes) */
    public TutorialWindow(TutorialRunner runner, Translator translator, Theme theme, Runnable started) {
        this.runner = Objects.requireNonNull(runner);
        this.tr = Objects.requireNonNull(translator);
        Objects.requireNonNull(started);
        resume = new ResumeBanner(tr.translate(WINDOW + "resume"));
        resume.setOnClick(() -> {
            runner.resume();
            started.run();
        });
        resume.setTooltip(tr.translate(WINDOW + "resume.tooltip"));
        intro = Label.dim(tr.translate(WINDOW + "intro"));
        intro.setWrap(true);
        Column list = new Column();
        list.setGap(0);
        int number = 1;
        Lessons.Group shownGroup = null;
        for (Lesson lesson : runner.lessons()) {
            // A dim header before each group's first lesson (Basics, Brushes, Building).
            Lessons.Group group = Lessons.groupOf(lesson).orElse(null);
            if (group != null && group != shownGroup) {
                shownGroup = group;
                Label header = Label.dim(tr.translate(group.titleKey()));
                list.add(new Padding(new Insets(theme.rowInset, number == 1 ? 0 : theme.gap, 0, 0), header));
            }
            LessonRow row = new LessonRow(lesson, number++);
            row.setOnClick(() -> {
                runner.start(lesson);
                started.run();
            });
            rows.add(row);
            list.add(row);
        }
        ScrollPane scroll = new ScrollPane(list);
        // The list takes the room the banner (or the intro) leaves; they keep their height.
        scroll.setFixedHeight(0);
        scroll.setGrow(1);
        resume.setFixedHeight(ROW_HEIGHT);
        Column column = Column.of(resume, intro, scroll);
        column.setGap(theme.gap + 2);
        root = column;
        refresh();
    }

    public Node node() {
        return root;
    }

    /** Shows the progress again if it changed. */
    public void refresh() {
        if (shownVersion == runner.version()) {
            return;
        }
        shownVersion = runner.version();
        Optional<TutorialProgress.Current> current = runner.resumable();
        resume.setVisible(current.isPresent());
        intro.setVisible(current.isEmpty());
        // While a lesson's changes are being undone, no lesson starts.
        boolean canStart = runner.phase() != TutorialRunner.Phase.UNDOING;
        resume.setEnabled(canStart);
        current.ifPresent(at -> runner.find(at.lesson()).ifPresent(lesson -> resume.at = tr.translate(
                WINDOW + "resume.at", tr.translate(lesson.titleKey()), Integer.toString(at.step() + 1),
                Integer.toString(lesson.size()))));
        for (LessonRow row : rows) {
            boolean done = runner.progress().isCompleted(row.lesson.id());
            row.done = done;
            row.setEnabled(canStart);
            row.setTooltip(tr.translate(row.lesson.summaryKey()) + "\n"
                    + (done ? tr.translate(WINDOW + "finished") + " · " + tr.translate(WINDOW + "restart.tooltip")
                            : tr.translate(WINDOW + "start.tooltip")));
        }
    }

    // ---- For tests ----

    /** The Resume banner (it continues the lesson in progress). */
    public AbstractButton resumeButton() {
        return resume;
    }

    /** Whether the Resume banner shows. */
    public boolean resumeShown() {
        return resume.isVisible();
    }

    /** Where the banner resumes: "Select, step 4 of 8" (it shows "Resume" before it). */
    public String resumeText() {
        return resume.at;
    }

    /** The banner's text as drawn in the last frame (shortened with "..." where it doesn't fit). */
    public String resumeShownText() {
        return resume.drawn;
    }

    public Label introLabel() {
        return intro;
    }

    /** Lesson {@code id}'s row: a click starts the lesson (again, once finished). */
    public AbstractButton lessonRow(String id) {
        return row(id);
    }

    /** Lesson {@code id}'s title as drawn in the last frame (shortened with "..." where it doesn't fit). */
    public String shownTitle(String id) {
        return row(id).drawnTitle;
    }

    public String title(String id) {
        return row(id).title;
    }

    /** Whether lesson {@code id} shows its check mark. */
    public boolean checked(String id) {
        return row(id).done;
    }

    private LessonRow row(String id) {
        return rows.stream().filter(row -> row.lesson.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No lesson " + id));
    }

    // ---- Nodes ----

    /** One lesson: its number or check mark, its title, its length (dim, at the right). */
    private final class LessonRow extends AbstractButton {
        final Lesson lesson;
        final String number;
        final String title;
        final String steps;
        boolean done;
        String drawnTitle = "";

        LessonRow(Lesson lesson, int number) {
            super(null);
            this.lesson = lesson;
            this.number = Integer.toString(number);
            this.title = tr.translate(lesson.titleKey());
            this.steps = tr.translate(WINDOW + "steps", Integer.toString(lesson.size()));
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            Theme theme = ctx.theme();
            int width = theme.rowInset + MARK_WIDTH + ctx.text().width(title) + 3 * theme.gap
                    + ctx.text().width(steps) + theme.rowInset;
            return new Size(width, ROW_HEIGHT);
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            Theme theme = ctx.theme();
            boolean enabled = isEffectivelyEnabled();
            if (enabled && ctx.isHovered(this)) {
                g.fill(bounds, isPressed() ? theme.controlPressed : theme.rowHover);
            }
            if (ctx.isFocused(this)) {
                g.outline(bounds, theme.focusRing);
            }
            int y = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
            int x = bounds.x() + theme.rowInset;
            if (done) {
                g.text("✓", x + 1, y, enabled ? theme.noticeSuccess : theme.textDisabled, theme.textShadow);
            } else {
                g.text(number, x, y, enabled ? theme.textDim : theme.textDisabled, theme.textShadow);
            }
            int stepsX = bounds.right() - theme.rowInset - ctx.text().width(steps);
            g.text(steps, stepsX, y, enabled ? theme.textDim : theme.textDisabled, theme.textShadow);
            int titleX = x + MARK_WIDTH;
            drawnTitle = TextLayout.ellipsize(ctx.text(), title, stepsX - 2 * theme.gap - titleX);
            g.text(drawnTitle, titleX, y, enabled ? theme.text : theme.textDisabled, theme.textShadow);
        }
    }

    /** The lesson in progress, as one slim accent banner: "Resume" and where. */
    private static final class ResumeBanner extends AbstractButton {
        final String label;
        String at = "";
        String drawn = "";

        ResumeBanner(String label) {
            super(null);
            this.label = label;
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            Theme theme = ctx.theme();
            return new Size(2 * theme.controlPaddingX + ctx.text().width("§l" + label) + theme.gap
                    + ctx.text().width(at), ROW_HEIGHT);
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            Theme theme = ctx.theme();
            boolean enabled = isEffectivelyEnabled();
            boolean hovered = enabled && ctx.isHovered(this);
            g.fill(bounds, !enabled ? theme.controlDisabled : isPressed() && hovered ? theme.accentDim
                    : hovered ? theme.accentHover : theme.accent);
            if (ctx.isFocused(this)) {
                g.outline(bounds, theme.focusRing);
            }
            int y = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
            int x = bounds.x() + theme.controlPaddingX;
            int color = enabled ? theme.textOnAccent : theme.textDisabled;
            String bold = "§l" + label;
            g.text(bold, x, y, color, theme.textShadow);
            int atX = x + ctx.text().width(bold) + theme.gap;
            drawn = TextLayout.ellipsize(ctx.text(), at, bounds.right() - theme.controlPaddingX - atX);
            g.text(drawn, atX, y, color, theme.textShadow);
        }
    }
}
