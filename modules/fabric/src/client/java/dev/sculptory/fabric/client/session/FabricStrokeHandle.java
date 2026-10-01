package dev.sculptory.fabric.client.session;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A stroke on a {@link FabricEditorSession}. Dabs are queued and sent once per client tick, at most
 * {@link C2S.Dabs#MAX_DABS} per message. At most {@link #MAX_UNACKED} dabs are ever unacknowledged or queued:
 * beyond that, a new dab replaces the newest queued one, so only the latest position is kept.
 *
 * <p>Prediction belongs to the brush tool: it installs a {@link BatchHook}, which sees each batch just before
 * it is sent, applies it locally under a vanilla prediction sequence and returns that sequence.
 * Render thread only.
 */
public final class FabricStrokeHandle implements StrokeHandle {
    /** Most dabs in flight or queued. */
    public static final int MAX_UNACKED = 16;
    /** Without an acknowledgement for this long, the client asks the server to resend the chunks. */
    public static final long ACK_TIMEOUT_NANOS = 5_000_000_000L;

    /** Sees each batch just before it is sent. */
    @FunctionalInterface
    public interface BatchHook {
        /**
         * Predicts {@code batch} locally and returns the prediction sequence it was applied under
         * ({@code PendingUpdateManager.getSequence()}), or 0 when nothing was predicted.
         */
        int beforeSend(FabricStrokeHandle stroke, List<Dab> batch);
    }

    private final FabricEditorSession session;
    private final int strokeId;
    private final ToolId tool;
    private final BrushSpec spec;
    private final StrokeParams params;
    private final ArrayDeque<Dab> queued = new ArrayDeque<>();
    private final ArrayDeque<Dab> unacked = new ArrayDeque<>();
    /** Dabs sent that the server has not reported written ({@code StrokeStatus.appliedIndex}). */
    private final ArrayDeque<Dab> unapplied = new ArrayDeque<>();
    private BatchHook hook;
    private long oldestUnackedAt;
    private int ackedIndex = -1;
    private int appliedIndex = -1;
    private boolean active = true;
    private boolean endSent;
    private boolean ended;
    private RejectReason rejectReason;

    FabricStrokeHandle(FabricEditorSession session, int strokeId, ToolId tool, BrushSpec spec, StrokeParams params) {
        this.session = session;
        this.strokeId = strokeId;
        this.tool = Objects.requireNonNull(tool);
        this.spec = Objects.requireNonNull(spec);
        this.params = Objects.requireNonNull(params);
    }

    public void setBatchHook(BatchHook hook) {
        this.hook = hook;
    }

    @Override
    public int strokeId() {
        return strokeId;
    }

    public ToolId tool() {
        return tool;
    }

    public BrushSpec spec() {
        return spec;
    }

    public StrokeParams params() {
        return params;
    }

    /** The last dab index the server acknowledged, or -1. */
    public int ackedIndex() {
        return ackedIndex;
    }

    /** Dabs sent but not yet acknowledged. */
    public int unacknowledged() {
        return unacked.size();
    }

    /** Dabs of a Shape stroke sent that the server has not reported written yet (0 for other strokes). */
    public int unapplied() {
        return unapplied.size();
    }

    /** The sent dabs the server has not reported written yet, oldest first. */
    public List<Dab> unappliedDabs() {
        return List.copyOf(unapplied);
    }

    /** The last dab index the server reported written, or -1. */
    public int appliedIndex() {
        return appliedIndex;
    }

    /** Dabs waiting for the next tick. */
    public int queued() {
        return queued.size();
    }

    /** Why the server refused the stroke, or {@code null}. */
    public RejectReason rejectReason() {
        return rejectReason;
    }

    /** True once the server confirmed the end (or the session reset). */
    public boolean ended() {
        return ended;
    }

    @Override
    public void dab(Dab d) {
        Objects.requireNonNull(d);
        if (!active) return;
        int room = MAX_UNACKED - unacked.size();
        if (!queued.isEmpty() && queued.size() >= Math.max(1, room)) queued.pollLast();
        queued.addLast(d);
    }

    @Override
    public void end() {
        if (endSent) return;
        if (active) flush(session.now(), true);
        active = false;
        endSent = true;
        queued.clear();
        session.sendStrokeEnd(strokeId);
    }

    @Override
    public void cancel() {
        queued.clear();
        active = false;
        if (!endSent) {
            endSent = true;
            session.sendStrokeEnd(strokeId);
        }
    }

    @Override
    public boolean active() {
        return active;
    }

    /** Once per client tick: sends the queued dabs the window allows, then checks the ack timeout. */
    void tick(long now) {
        if (active) flush(now, false);
        if (!unacked.isEmpty() && now - oldestUnackedAt > ACK_TIMEOUT_NANOS) {
            for (Box box : resyncBoxes(unacked)) session.resync(box);
            unacked.clear();
        }
    }

    private void flush(long now, boolean ignoreWindow) {
        if (queued.isEmpty()) return;
        int room = ignoreWindow ? MAX_UNACKED : MAX_UNACKED - unacked.size();
        int count = Math.min(Math.min(room, C2S.Dabs.MAX_DABS), queued.size());
        if (count <= 0) return;
        List<Dab> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) batch.add(queued.pollFirst());
        int seq = hook != null ? hook.beforeSend(this, List.copyOf(batch)) : 0;
        RejectReason failure = session.sendDabs(strokeId, Math.max(0, seq), batch);
        if (failure != null) {
            reject(failure);
            return;
        }
        if (unacked.isEmpty()) oldestUnackedAt = now;
        unacked.addAll(batch);
        // Only the Shape brush's server reports written batches (and only its tool paces on them).
        if (spec.tool() == BrushTool.SHAPE) unapplied.addAll(batch);
    }

    void onStatus(S2C.StrokeStatus status, long now) {
        acknowledge(status.ackedIndex(), now);
        if (status.appliedIndex() > appliedIndex) {
            appliedIndex = status.appliedIndex();
            while (!unapplied.isEmpty() && unapplied.peekFirst().index() <= appliedIndex) unapplied.pollFirst();
        }
        switch (status.status()) {
            case OK -> { }
            case REJECTED -> {
                reject(status.reason());
                unapplied.clear();
            }
            case ENDED -> {
                // The server may still be writing its dabs: those stay unapplied until it reports them.
                active = false;
                ended = true;
                queued.clear();
                unacked.clear();
            }
        }
    }

    /** No more writes of this stroke will be reported: its dabs no longer count as unapplied. */
    void forgetWrites() {
        unapplied.clear();
    }

    /** The session went away: nothing more will be sent or acknowledged. */
    void disconnect() {
        active = false;
        endSent = true;
        ended = true;
        queued.clear();
        unacked.clear();
        unapplied.clear();
    }

    /**
     * Refused before or while running; prediction stops until the next press. The first refusal of a stroke is
     * toasted with its reason even when the player already let go: the brush lane can refuse an admitted dab
     * after the release (its {@code StrokeStatus REJECTED} still comes before the stroke's {@code ENDED}). Later
     * refusals of the same stroke are quiet.
     */
    void reject(RejectReason reason) {
        active = false;
        queued.clear();
        if (rejectReason != null) return;
        rejectReason = reason;
        session.strokeRejected(this, reason);
    }

    /** Refused before it began, without the reason's toast: the session shows its own. */
    void refuseQuietly(RejectReason reason) {
        active = false;
        queued.clear();
        if (rejectReason == null) rejectReason = reason;
    }

    private void acknowledge(int index, long now) {
        if (index <= ackedIndex) return;
        ackedIndex = index;
        boolean removed = false;
        while (!unacked.isEmpty() && unacked.peekFirst().index() <= ackedIndex) {
            unacked.pollFirst();
            removed = true;
        }
        if (removed && !unacked.isEmpty()) oldestUnackedAt = now;
    }

    /**
     * Resync requests for the chunks the unacknowledged dabs could have touched: one box per 8x8-chunk tile
     * those footprints reach, bounded to the footprint inside the tile, so each request is at most 64 chunks
     * (the server's cap) and overlaps the stroke's footprint. At most
     * {@link FabricEditorSession#MAX_RESYNC_REQUESTS} boxes, in tile order. With brush symmetry the copies' footprints
     * count too (the server records them), after the dabs' own: the tiles the dabs themselves reach come first (with
     * any copy chunks inside them), then the tiles only copies reach, so a distant centre never pushes the dabs' own
     * area past the cap.
     */
    List<Box> resyncBoxes(Iterable<Dab> dabs) {
        // The Shape brush's shapes reach half their longest side (a tall shape may lie on its side).
        int r = spec.reach();
        // tile key -> {minCx, maxCx, minCz, maxCz, minY, maxY}
        TreeMap<Long, int[]> own = new TreeMap<>();
        TreeMap<Long, int[]> copies = new TreeMap<>();
        for (Dab d : dabs) addFootprint(own, null, d, r);
        for (Dab d : dabs) {
            List<Dab> images;
            try {
                images = spec.symmetry().copies(d);
            } catch (IllegalArgumentException outOfRange) {
                continue;
            }
            for (int i = 1; i < images.size(); i++) addFootprint(copies, own, images.get(i), r);
        }
        List<Box> boxes = new ArrayList<>();
        for (TreeMap<Long, int[]> tiles : List.of(own, copies)) {
            for (int[] b : tiles.values()) {
                if (boxes.size() == FabricEditorSession.MAX_RESYNC_REQUESTS) return boxes;
                boxes.add(new Box(new BlockPos(b[0] * 16, b[4], b[2] * 16), new BlockPos(b[1] * 16 + 15, b[5], b[3] * 16 + 15)));
            }
        }
        return boxes;
    }

    /**
     * Adds the chunks of a dab's footprint to their tiles' bounds in {@code tiles}, or in {@code first} for tiles it
     * already holds.
     */
    private static void addFootprint(TreeMap<Long, int[]> tiles, TreeMap<Long, int[]> first, Dab d, int r) {
        int minY = clamp((long) d.blockY() - r);
        int maxY = clamp((long) d.blockY() + r);
        for (int cx = (d.blockX() - r) >> 4; cx <= (d.blockX() + r) >> 4; cx++) {
            for (int cz = (d.blockZ() - r) >> 4; cz <= (d.blockZ() + r) >> 4; cz++) {
                long tile = ((long) (cx >> 3) << 32) | ((cz >> 3) & 0xFFFFFFFFL);
                int[] b = first != null && first.containsKey(tile) ? first.get(tile) : tiles.computeIfAbsent(tile,
                        k -> new int[] {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
                                Integer.MAX_VALUE, Integer.MIN_VALUE});
                b[0] = Math.min(b[0], cx);
                b[1] = Math.max(b[1], cx);
                b[2] = Math.min(b[2], cz);
                b[3] = Math.max(b[3], cz);
                b[4] = Math.min(b[4], minY);
                b[5] = Math.max(b[5], maxY);
            }
        }
    }

    private static int clamp(long value) {
        return (int) Math.max(Integer.MIN_VALUE / 2, Math.min(Integer.MAX_VALUE / 2, value));
    }
}
