package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.HistoryMirror;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.SessionState;
import dev.sculptory.fabric.client.session.Subscription;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Tutorial mode: the lessons' {@link TutorialRunner}, the
 * {@link LessonCard}, the {@link TutorialWindow}, the step highlight, and {@link LessonEdits} following the player's
 * history for "Undo what this lesson made". The editor UI calls {@link #frame} once per frame while the editor is
 * open, {@link #renderHighlight} above the windows and menus, and {@link #editorShown} when the editor opens or closes.
 */
public final class Tutorial {
    /** What the tutorial needs from the editor. */
    public interface Host {
        /** Opens the Tutorial window (bringing it to the front). */
        void showWindow();

        /** Closes the Tutorial window. */
        void closeWindow();

        Optional<EditorSession> session();

        /** Undoes one entry, as Ctrl+Z does. */
        void undo();

        void notify(Notice notice);
    }

    /**
     * How long after a connection's first history (or after the tutorial starts following it) saved entries may still
     * load under it. Loading takes a moment after joining; on a very slow disk the server says it can take a minute.
     */
    public static final long LOAD_WINDOW_MS = 120_000;
    public static final String NOTICE_PAUSED = Lesson.PREFIX + "notice.paused";
    public static final String NOTICE_UNDID = Lesson.PREFIX + "notice.undid";

    private final TutorialProbe probe;
    private final TargetResolver targets;
    private final Host host;
    private final Theme theme;
    private final LessonEdits edits;
    private final StepTexts texts;
    private final TutorialRunner runner;
    private final LessonCard card;
    private final TutorialWindow window;
    private WikiOpener wiki = WikiOpener.NONE;
    private HistoryMirror watched;
    private Subscription watching;
    /** The connection whose history is being followed (see {@link #watchHistory}). */
    private Object connection;
    /** Until when saved entries may still load under the history followed. */
    private long loadsUntil = Long.MIN_VALUE;
    private long now;
    /** The last lesson undo whose result was toasted. */
    private LessonUndo reported;

    public Tutorial(List<Lesson> lessons, TutorialProbe probe, LessonEdits edits, TargetResolver targets, Host host,
            StepTexts texts, Translator translator, Theme theme) {
        this.probe = Objects.requireNonNull(probe);
        this.edits = Objects.requireNonNull(edits);
        this.targets = Objects.requireNonNull(targets);
        this.host = Objects.requireNonNull(host);
        this.theme = Objects.requireNonNull(theme);
        this.texts = Objects.requireNonNull(texts);
        runner = new TutorialRunner(lessons, probe, edits, new LessonUndo.History() {
            @Override
            public boolean available() {
                return host.session().filter(session -> session.state() == SessionState.READY).isPresent();
            }

            @Override
            public boolean busy() {
                return host.session().map(EditorSession::historyBusy).orElse(false);
            }

            @Override
            public void undo() {
                host.undo();
            }
        });
        runner.setEarlierWork(() -> host.session().map(Tutorial::working).orElse(false));
        card = new LessonCard(runner, texts, translator, theme, () -> now, () -> wiki, this::exit, host::showWindow);
        window = new TutorialWindow(runner, translator, theme, host::closeWindow);
        watchHistory();
    }

    public TutorialRunner runner() {
        return runner;
    }

    public LessonCard card() {
        return card;
    }

    public TutorialWindow window() {
        return window;
    }

    public LessonEdits edits() {
        return edits;
    }

    /** Where progress is kept ({@code editor-tutorial.json}); reads it now. */
    public void setStore(TutorialStore store) {
        runner.setStore(store);
    }

    /** Played when a step ticks itself off (a vanilla UI sound in game). */
    public void setStepSound(Runnable sound) {
        runner.setStepSound(sound);
    }

    /** Opens the wiki for Learn more (the wiki reader, once the editor has one). */
    public void setWiki(WikiOpener wiki) {
        this.wiki = Objects.requireNonNull(wiki);
    }

    /** Whether the lesson card shows now. */
    public boolean cardShown() {
        return runner.shown();
    }

    /** Help > Tutorial: the Tutorial window. */
    public void open() {
        host.showWindow();
    }

    /** Exit on the card: pauses the lesson, and says how to go on. */
    public void exit() {
        boolean pausing = runner.phase() == TutorialRunner.Phase.STEP;
        runner.exit();
        if (pausing) {
            host.notify(Notice.of(Notice.Level.INFO, NOTICE_PAUSED));
        }
    }

    /**
     * Builder mode lives outside the editor; the client passes in whether any of its powers is on and how its ring
     * key is bound (for the builder-mode lesson).
     */
    public void setBuilderMode(BooleanSupplier powerOn, Supplier<String> ringKey) {
        if (probe instanceof EditorProbe editor) {
            editor.setBuilderPowerOn(powerOn);
        }
        texts.setRingKey(ringKey);
    }

    /** The editor opened or closed. The lesson waits while it is closed (nothing is checked or shown). */
    public void editorShown(boolean shown) {
        if (shown && probe instanceof EditorProbe editor) {
            editor.editorShown();
        }
    }

    /** Once per frame while the editor is open: follows the history, checks the step, updates the card. */
    public void frame(long nowMs) {
        now = nowMs;
        watchHistory();
        if (loadsUntil == Long.MIN_VALUE) {
            loadsUntil = nowMs + LOAD_WINDOW_MS;
        } else if (nowMs > loadsUntil) {
            edits.setLoadsPossible(false);
        }
        runner.frame(nowMs);
        // Once an undo of the lesson's changes ends, the Notifications keep what it did.
        runner.undo().filter(undo -> !undo.running() && undo != reported).ifPresent(undo -> {
            reported = undo;
            host.notify(Notice.of(Notice.Level.INFO, NOTICE_UNDID, Integer.toString(undo.undone()),
                    Integer.toString(undo.total())));
        });
        card.refresh();
    }

    /** Refreshes the Tutorial window (call while it is open). */
    public void refreshWindow() {
        window.refresh();
    }

    /** Where the step's highlight goes now, if the step has one on screen. */
    public Optional<Rect> highlight() {
        if (!runner.shown() || runner.stepDone()) {
            return Optional.empty();
        }
        return runner.step().map(Step::target).flatMap(targets::resolve);
    }

    /** Draws the step's highlight (above the windows and menus). */
    public void renderHighlight(UiGraphics g, long nowMs) {
        highlight().ifPresent(rect -> Highlight.draw(g, theme, rect, nowMs));
    }

    /** Whether the session has edits still running (jobs, brush strokes, undo or redo steps). */
    private static boolean working(EditorSession session) {
        return session.historyBusy() || session.strokesPending()
                || session.jobs().jobs().stream().anyMatch(job -> !job.finished());
    }

    /**
     * Follows the current session's history: every history state it receives goes to {@link LessonEdits}. The first
     * state of each connection (the history the server holds for the player, after a reconnect or a world change too)
     * starts over, none of it the lesson's, and so does every state while not connected (the history cleared).
     */
    private void watchHistory() {
        EditorSession session = host.session().orElse(null);
        HistoryMirror mirror = session == null ? null : session.history();
        if (mirror == watched) {
            return;
        }
        if (watching != null) {
            watching.close();
            watching = null;
        }
        watched = mirror;
        if (mirror == null) {
            edits.reset(LessonEdits.Snapshot.EMPTY);
            return;
        }
        edits.reset(LessonEdits.Snapshot.of(mirror));
        newConnection(session);
        watching = mirror.onChange(() -> {
            LessonEdits.Snapshot state = LessonEdits.Snapshot.of(mirror);
            Object current = connection(session);
            if (current == null || current != connection) {
                newConnection(session);
                // A reconnect: the saved history may still load under what the server just sent, so nothing new is
                // the lesson's until it has (or the time for it is over).
                edits.holdForLoad();
                edits.reset(state);
            } else {
                edits.observe(state, mirror.lastCause());
            }
        });
    }

    /** Follows a connection from its first history: its saved entries may still load for a while. */
    private void newConnection(EditorSession session) {
        connection = connection(session);
        edits.setLoadsPossible(true);
        // The window starts at the next editor frame (the editor may be closed now).
        loadsUntil = Long.MIN_VALUE;
    }

    /** The session's connection (its capabilities, new at each handshake), or null while not connected. */
    private static Object connection(EditorSession session) {
        return session.state() == SessionState.READY ? session.capabilities() : null;
    }
}
