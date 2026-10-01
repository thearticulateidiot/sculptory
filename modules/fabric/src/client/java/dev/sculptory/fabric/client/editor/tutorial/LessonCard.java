package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.hud.Panel;
import dev.sculptory.fabric.client.editor.ui.Align;
import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The lesson card: a small panel at the top centre of the screen while a lesson runs. It never blocks the editor (the
 * player does each step in the world with the real tools). It shows the lesson's title, "Step 2 of 6" and the step's
 * instruction (with a check once done), and the buttons Back, Skip step (Next on a step that only explains, or once the
 * step is done) and Exit, and Learn more when the step links to the wiki. At the lesson's end it shows "Lesson done"
 * with Undo what this lesson made / Keep it, the undo's progress with Stop, then Next lesson / Back to lessons.
 * {@link #refresh} rebuilds it when the {@link TutorialRunner} changed.
 */
public final class LessonCard {
    /** The card's width in UI units (narrower screens get a narrower card). */
    public static final int WIDTH = 250;
    private static final int PADDING = 6;
    private static final String CARD = Lesson.PREFIX + "card.";

    private final TutorialRunner runner;
    private final StepTexts texts;
    private final Translator tr;
    private final Theme theme;
    private final Supplier<WikiOpener> wiki;
    private final Runnable backToLessons;
    private final Label title = Label.heading("");
    private final Label progress = Label.dim("");
    private final Label body = Label.of("");
    private final FlowRow buttons = new FlowRow();
    private final Button learnMore;
    private final Button back;
    private final Button skip;
    private final Button exit;
    private final Button undo;
    private final Button keep;
    private final Button stop;
    private final Button nextLesson;
    private final Button lessons;
    private final Column content;
    private final Node node;
    private int shownVersion = -1;

    /**
     * @param clock         the time now in milliseconds (the undo's start)
     * @param wiki          opens Learn more's page
     * @param onExit        Exit: pauses the lesson
     * @param backToLessons Back to lessons: shows the Tutorial window
     */
    public LessonCard(TutorialRunner runner, StepTexts texts, Translator translator, Theme theme, LongSupplier clock,
            Supplier<WikiOpener> wiki, Runnable onExit, Runnable backToLessons) {
        this.runner = Objects.requireNonNull(runner);
        this.texts = Objects.requireNonNull(texts);
        this.tr = Objects.requireNonNull(translator);
        this.theme = Objects.requireNonNull(theme);
        this.wiki = Objects.requireNonNull(wiki);
        this.backToLessons = Objects.requireNonNull(backToLessons);
        Objects.requireNonNull(clock);
        learnMore = button("learn_more", this::openWiki);
        learnMore.setStyle(Button.Style.FLAT);
        back = button("back", runner::back);
        skip = button("skip", this::skipOrNext);
        exit = button("exit", Objects.requireNonNull(onExit));
        undo = button("undo", () -> runner.undoLesson(clock.getAsLong()));
        keep = button("keep", runner::keep);
        keep.setStyle(Button.Style.PRIMARY);
        stop = button("stop", runner::stopUndo);
        nextLesson = button("next_lesson", runner::nextLesson);
        nextLesson.setStyle(Button.Style.PRIMARY);
        lessons = button("lessons", () -> {
            runner.close();
            this.backToLessons.run();
        });
        title.setGrow(1);
        Row header = Row.of(title, progress);
        header.setGap(theme.gap);
        header.setCrossAlign(Align.CENTER);
        body.setWrap(true);
        buttons.setGap(theme.gap);
        buttons.setAlign(Align.END);
        content = Column.of(header, body, buttons);
        content.setGap(theme.gap + 2);
        node = new Panel(content, Insets.all(PADDING), theme.popupBackground, theme.accentDim);
        fitWidth(WIDTH);
    }

    /** Makes the card {@link #WIDTH} wide, or {@code room} where the screen has less. */
    public void fitWidth(int room) {
        int width = Math.max(4 * PADDING, Math.min(WIDTH, room));
        content.setFixedWidth(width - 2 * PADDING);
    }

    private Button button(String name, Runnable action) {
        Button button = new Button(tr.translate(CARD + name), action);
        String tooltip = CARD + name + ".tooltip";
        if (tr.has(tooltip)) {
            button.setTooltip(tr.translate(tooltip));
        }
        return button;
    }

    public Node node() {
        return node;
    }

    /** Rebuilds the card if the lesson moved on since it was last shown. */
    public void refresh() {
        if (shownVersion == runner.version()) {
            return;
        }
        shownVersion = runner.version();
        rebuild();
    }

    private void rebuild() {
        List<Node> shown = new ArrayList<>();
        body.setColor(0);
        Lesson lesson = runner.lesson().orElse(null);
        title.setText(lesson == null ? "" : tr.translate(lesson.titleKey()));
        switch (runner.phase()) {
            case STEP -> {
                Step step = runner.step().orElseThrow();
                progress.setText(tr.translate(CARD + "step", Integer.toString(runner.stepIndex() + 1),
                        Integer.toString(lesson.size())));
                String text = texts.text(step);
                if (runner.stepDone()) {
                    body.setText("✓ " + text);
                    body.setColor(theme.noticeSuccess);
                } else {
                    body.setText(text);
                }
                // Learn more once the editor has a wiki to open.
                if (step.wiki() != null && wiki.get() != WikiOpener.NONE) {
                    shown.add(learnMore);
                }
                back.setEnabled(runner.stepIndex() > 0);
                shown.add(back);
                boolean next = step.isRead() || runner.stepDone();
                skip.setText(tr.translate(CARD + (next ? "next" : "skip")));
                skip.setTooltip(tr.translate(CARD + (next ? "next" : "skip") + ".tooltip"));
                skip.setStyle(next ? Button.Style.PRIMARY : Button.Style.DEFAULT);
                shown.add(skip);
                shown.add(exit);
            }
            case FINISHED -> {
                progress.setText(tr.translate(CARD + "done"));
                body.setText(made(runner.made()));
                shown.add(undo);
                shown.add(keep);
            }
            case UNDOING -> {
                progress.setText(tr.translate(CARD + "done"));
                LessonUndo running = runner.undo().orElseThrow();
                body.setText(tr.translate(CARD + "undoing", Integer.toString(Math.min(running.total(),
                        running.undone() + 1)), Integer.toString(running.total())));
                shown.add(stop);
            }
            case AFTER -> {
                progress.setText(tr.translate(CARD + "done"));
                boolean hasNext = lesson != null && runner.nextAfter(lesson).isPresent();
                String report = runner.undo().map(this::report).orElseGet(() -> runner.made() > 0
                        ? tr.translate(CARD + "kept") : tr.translate(CARD + "nothing_made"));
                body.setText(hasNext ? report : report + " " + tr.translate(CARD + "last"));
                lessons.setStyle(hasNext ? Button.Style.DEFAULT : Button.Style.PRIMARY);
                shown.add(lessons);
                if (hasNext) {
                    shown.add(nextLesson);
                }
            }
            case IDLE -> {
                progress.setText("");
                body.setText("");
            }
        }
        buttons.clear();
        buttons.add(shown);
    }

    private String made(int count) {
        return count == 1 ? tr.translate(CARD + "made_one") : tr.translate(CARD + "made", Integer.toString(count));
    }

    /** What the undo did, in words. */
    private String report(LessonUndo done) {
        String undone = Integer.toString(done.undone());
        String total = Integer.toString(done.total());
        return switch (done.outcome()) {
            case DONE -> done.total() == 1 ? tr.translate(CARD + "undid_one") : tr.translate(CARD + "undid_all", total);
            case FOREIGN -> tr.translate(CARD + "undid_foreign", undone, total);
            case FAILED -> tr.translate(CARD + "undid_failed", undone, total);
            case INTERRUPTED -> tr.translate(CARD + "undid_interrupted", undone, total);
            case STOPPED -> tr.translate(CARD + "undid_stopped", undone, total);
            case RUNNING -> tr.translate(CARD + "undoing", undone, total);
        };
    }

    private void skipOrNext() {
        if (runner.step().map(Step::isRead).orElse(false) || runner.stepDone()) {
            runner.next();
        } else {
            runner.skip();
        }
    }

    private void openWiki() {
        runner.step().map(Step::wiki).ifPresent(link -> wiki.get().open(link.page(), link.anchor()));
    }

    // ---- For tests and the screenshot tour ----

    public Label titleLabel() {
        return title;
    }

    public Label progressLabel() {
        return progress;
    }

    public Label bodyLabel() {
        return body;
    }

    /** The buttons shown now, in order. */
    public List<Button> shownButtons() {
        return buttons.children().stream().filter(Button.class::isInstance).map(Button.class::cast).toList();
    }

    public Button learnMoreButton() {
        return learnMore;
    }

    public Button backButton() {
        return back;
    }

    /** Skip step, or Next. */
    public Button skipButton() {
        return skip;
    }

    public Button exitButton() {
        return exit;
    }

    public Button undoButton() {
        return undo;
    }

    public Button keepButton() {
        return keep;
    }

    public Button stopButton() {
        return stop;
    }

    public Button nextLessonButton() {
        return nextLesson;
    }

    public Button lessonsButton() {
        return lessons;
    }
}
