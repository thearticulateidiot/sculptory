package dev.sculptory.fabric.client.editor.tools.generate;

import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.SparseUpload;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.Reply;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.fabric.client.session.ToolResult;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.server.engine.Perm;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a generated source: checks it against the player's
 * limits, asks first when it is large, encodes it off the client thread, uploads it as the player's clipboard and pastes
 * it where it was generated, as one job and one undo step named by its label. Shared by the Generate tool's Road, Roof
 * and Line and the Shape brush's Line. Client thread only.
 */
final class GeneratedCommit {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    /**
     * What to build.
     *
     * @param opNameKey the confirmation's name for a large build
     * @param sentKey the notice once the paste is accepted ([blocks])
     */
    record Request(GeneratedSource source, OpLabel label, String opNameKey, String sentKey, PasteOptions options) {
        Request {
            Objects.requireNonNull(source);
            Objects.requireNonNull(label);
            Objects.requireNonNull(opNameKey);
            Objects.requireNonNull(sentKey);
            Objects.requireNonNull(options);
        }
    }

    private final GenerateTool.Services services;
    /** A build in progress: the payload being encoded, and what it is for. */
    private CompletableFuture<byte[]> encoding;
    private Request encodingOf;

    GeneratedCommit(GenerateTool.Services services) {
        this.services = Objects.requireNonNull(services);
    }

    /**
     * Builds {@code request}'s source (not empty): refused with a toast over the player's clipboard or op limit (unless
     * they bypass limits), confirmed first over {@link SelectionActions#CONFIRM_VOLUME} blocks.
     */
    void send(ToolContext c, Request request) {
        EditorSession session;
        try {
            session = c.session();
        } catch (IllegalStateException noSession) {
            return;
        }
        Permissions permissions = session.permissions();
        Limits limits = permissions.limits();
        long cells = request.source().cells();
        if (!permissions.has(Perm.LIMIT_BYPASS)) {
            long limit = Math.min(limits.maxClipboardVolume(), limits.maxOpVolume());
            if (cells > limit) {
                c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large", SessionNotices.count(cells),
                        SessionNotices.count(limit)));
                return;
            }
        }
        StateSpace states;
        try {
            states = c.states();
        } catch (IllegalStateException noSession) {
            return;
        }
        Runnable send = () -> {
            encodingOf = request;
            encoding = CompletableFuture.supplyAsync(() -> SparseUpload.encode(request.source(), states), services.background());
            poll(c);
        };
        if (cells > SelectionActions.CONFIRM_VOLUME) {
            services.confirm(request.opNameKey(), cells, send);
        } else {
            send.run();
        }
    }

    /** Drops a build being encoded (the tool was put away). */
    void cancel() {
        encoding = null;
        encodingOf = null;
    }

    /** Sends the payload once it is encoded: the upload, then the paste. */
    void poll(ToolContext c) {
        if (encoding == null || !encoding.isDone()) return;
        CompletableFuture<byte[]> done = encoding;
        Request request = encodingOf;
        encoding = null;
        encodingOf = null;
        byte[] bytes;
        try {
            bytes = done.join();
        } catch (CompletionException | CancellationException e) {
            LOG.warn("Sculptory: encoding a generated clipboard failed", e.getCause() != null ? e.getCause() : e);
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large",
                    SessionNotices.count(request.source().cells()), SessionNotices.count(limits(c).maxClipboardVolume())));
            return;
        }
        EditorSession session;
        try {
            session = c.session();
        } catch (IllegalStateException noSession) {
            return;
        }
        Box bounds = request.source().bounds();
        long cells = request.source().cells();
        session.uploadGenerated(bounds, cells, bytes).result().thenAccept(reply -> {
            if (reply instanceof Reply.Ok<ClipboardCache.Entry> ok) paste(c, session, ok.value(), bounds, cells, request);
            // A refusal or failure is toasted by the session.
        });
    }

    private static void paste(ToolContext c, EditorSession session, ClipboardCache.Entry entry, Box bounds, long cells,
                              Request request) {
        OpSpec.Paste paste = new OpSpec.Paste(new SourceRef.Clipboard(entry.clipboardId()), bounds.min(), Transform.IDENTITY,
                request.options());
        session.send(new ToolAction.RunOp(paste, false, request.label())).thenAccept(result -> {
            switch (result) {
                case ToolResult.Accepted accepted -> c.notify(Notice.of(Notice.Level.INFO, request.sentKey(),
                        SessionNotices.count(cells)));
                case ToolResult.Rejected rejected -> c.notify(SessionNotices.rejection(rejected.reason(),
                        SessionNotices.Subject.EDIT, session.permissions().limits()));
                case ToolResult.Done ignored -> {
                }
            }
        });
    }

    private static Limits limits(ToolContext c) {
        try {
            return c.session().permissions().limits();
        } catch (IllegalStateException noSession) {
            return Limits.DEFAULTS;
        }
    }
}
