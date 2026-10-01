package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Padding;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A small popup asking for one line of text (a file path, a preset name): a heading, the field, a message under it
 * that {@code check} rewrites as the player types (dim while the text is usable, red when not), an optional hint, and
 * Cancel and a primary button. Enter or the button submits while the check passes; Esc, a click outside or Cancel
 * closes it. The field has focus when it opens.
 */
public final class TextPrompt {
    /** What the check says about the typed text: whether it can be submitted, and the message to show. */
    public record Check(boolean ok, String message) {
        public Check {
            Objects.requireNonNull(message);
        }
    }

    /** The prompt's fixed texts; {@code hint} may be {@code ""} for none. */
    public record Texts(String title, String placeholder, String hint, String submit, String cancel) {
        public Texts {
            Objects.requireNonNull(title);
            Objects.requireNonNull(placeholder);
            Objects.requireNonNull(hint);
            Objects.requireNonNull(submit);
            Objects.requireNonNull(cancel);
        }
    }

    /**
     * Content under the field (a format choice, say) that may change the text: it gets the field and a way to run the
     * check again after {@link TextInput#setText}, which does not.
     */
    @FunctionalInterface
    public interface Extra {
        Node build(TextInput field, Runnable recheck);
    }

    private TextPrompt() {}

    /** Opens the prompt below {@code anchor}; {@code onSubmit} gets the text as typed. */
    public static PopupLayer.Popup open(UiContext ctx, Rect anchor, int width, Texts texts, String initial,
                                        int maxLength, Function<String, Check> check, Consumer<String> onSubmit) {
        return open(ctx, anchor, width, texts, initial, maxLength, check, onSubmit, null);
    }

    /**
     * As {@link #open(UiContext, Rect, int, Texts, String, int, Function, Consumer)}, with {@code extra} (or nothing
     * when {@code null}) under the field.
     */
    public static PopupLayer.Popup open(UiContext ctx, Rect anchor, int width, Texts texts, String initial,
                                        int maxLength, Function<String, Check> check, Consumer<String> onSubmit,
                                        Extra extra) {
        PopupLayer.Popup[] popup = new PopupLayer.Popup[1];
        TextInput[] input = new TextInput[1];
        Label status = Label.dim("");
        status.setWrap(true);
        Button submit = new Button(texts.submit(), null);
        submit.setStyle(Button.Style.PRIMARY);
        Consumer<String> validate = text -> {
            Check result = check.apply(text);
            status.setText(result.message());
            status.setColor(result.ok() ? ctx.theme().textDim : ctx.theme().danger);
            submit.setEnabled(result.ok());
        };
        Runnable run = () -> {
            String typed = input[0].text();
            if (!check.apply(typed).ok()) {
                return;
            }
            ctx.popups().close(popup[0]);
            onSubmit.accept(typed);
        };
        input[0] = new TextInput(initial, validate);
        input[0].setMaxLength(maxLength);
        input[0].setPlaceholder(texts.placeholder());
        input[0].setOnSubmit(text -> run.run());
        submit.setOnClick(run);
        Button cancel = new Button(texts.cancel(), () -> ctx.popups().close(popup[0]));
        Column content = Column.of(Label.heading(texts.title()), input[0], status);
        if (extra != null) {
            content.add(extra.build(input[0], () -> validate.accept(input[0].text())));
        }
        if (!texts.hint().isEmpty()) {
            content.add(Label.dim(texts.hint()).setWrap(true));
        }
        content.add(Row.of(Spacer.flexible(), cancel, submit));
        content.setGap(5);
        content.setFixedWidth(width - 12);
        validate.accept(initial);
        popup[0] = ctx.popups().open(null, new Padding(Insets.all(6), content), anchor, width, null);
        ctx.setFocus(input[0]);
        return popup[0];
    }
}
