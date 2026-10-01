package dev.sculptory.fabric.client.editor.tool;

import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.RegionTooLargeException;
import dev.sculptory.core.region.Regions;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Region work the client thread must not do itself: exact cell counts of large shapes (one row calculation per row,
 * which can take a second for a huge box) and other heavy computations on regions. The work runs on one background
 * thread; results come back on the client thread. Client thread only.
 *
 * <p>Counts go as the server's do ({@code Regions.cellsBetween}): along the longest side of the shape's box, one row
 * per cell of the other two sides ({@code Regions.shapeRows}). A box, a cell set or a small shape (at most
 * {@link #INLINE_ROWS} rows, in {@code long} arithmetic) is counted at once. A larger shape is counted in the
 * background: {@link #countNow} returns empty meanwhile (the latest shape asked for is counted next, so a drag asking
 * every frame queues one count at a time) and {@link #count} delivers the count when it is known. Counted shapes are
 * remembered.
 */
public final class RegionWork {
    /** Rows (the box's two shorter sides) a shape is counted in on the calling thread: well under a millisecond. */
    public static final long INLINE_ROWS = 16_384;
    /**
     * The most rows of a shape in a box of more than 2^29 cells that is counted at all ({@code BigInteger} arithmetic):
     * the most the server takes from anyone ({@code OpCompiler.MAX_BIG_SHAPE_ROWS_BYPASS}), about a second of work. A
     * smaller box always counts (at most 659,344 rows in {@code long} arithmetic, tens of milliseconds).
     */
    public static final long MAX_ROWS = OpCompiler.MAX_BIG_SHAPE_ROWS_BYPASS;
    private static final int REMEMBERED = 64;

    private final Executor background;
    private final Executor clientThread;
    private final Map<Region, Long> known = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Region, Long> eldest) {
            return size() > REMEMBERED;
        }
    };
    /** Counts asked for with {@link #count}, oldest first. */
    private final Map<Region, CompletableFuture<Long>> waiting = new LinkedHashMap<>();
    /** The latest shape {@link #countNow} could not answer. */
    private Region wanted;
    private Region running;

    /**
     * @param background where counts and {@link #supply} work run (one thread: the work is done in order)
     * @param clientThread where results are delivered
     */
    public RegionWork(Executor background, Executor clientThread) {
        this.background = Objects.requireNonNull(background);
        this.clientThread = Objects.requireNonNull(clientThread);
    }

    /** Work done at once on the calling thread (tests; a client without a background thread). */
    public static RegionWork direct() {
        return new RegionWork(Runnable::run, Runnable::run);
    }

    /** No region has more cells than its bounds' volume. */
    public static long atMost(Region region) {
        return region.bounds().volume();
    }

    /**
     * Whether a region's cells can be counted at all in reasonable time: a shape in a box of more than 2^29 cells with
     * more than {@link #MAX_ROWS} rows (a box stretched across much of the world) is not counted; the server refuses
     * such a shape from anyone, so callers refuse it as too large.
     */
    public static boolean countable(Region region) {
        return !(region instanceof Region.Shape shape) || shape.box().volume() <= OpCompiler.LONG_PATH_VOLUME
                || rows(shape) <= MAX_ROWS;
    }

    /** The rows a count of the shape goes over: its box's two shorter sides ({@code Regions.shapeRows}). */
    static long rows(Region.Shape shape) {
        return Regions.shapeRows(shape, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /** The exact count, as the server counts it (along the box's longest side). */
    private static long cells(Region region) {
        return region instanceof Region.Shape shape
                ? Regions.cellsBetween(shape, Integer.MIN_VALUE, Integer.MAX_VALUE) : region.cellCount();
    }

    /**
     * The exact cell count if it is cheap or already known; otherwise empty, and the region is counted in the
     * background (ask again later). Never known for a region that isn't {@link #countable}.
     */
    public OptionalLong countNow(Region region) {
        OptionalLong now = known(region);
        if (now.isPresent() || !countable(region)) {
            return now;
        }
        wanted = region;
        schedule();
        return known(region);
    }

    /**
     * The exact cell count, on the client thread: at once when it is cheap or known. Fails with a
     * {@link RegionTooLargeException} for a region that isn't {@link #countable}.
     */
    public CompletionStage<Long> count(Region region) {
        OptionalLong now = known(region);
        if (now.isPresent()) {
            return CompletableFuture.completedFuture(now.getAsLong());
        }
        if (!countable(region)) {
            return CompletableFuture.failedFuture(new RegionTooLargeException(atMost(region), MAX_ROWS));
        }
        CompletableFuture<Long> result = waiting.computeIfAbsent(region, key -> new CompletableFuture<>());
        schedule();
        return result;
    }

    /** Runs {@code work} on the background thread; its result (or failure) comes back on the client thread. */
    public <T> CompletionStage<T> supply(Supplier<T> work) {
        Objects.requireNonNull(work);
        CompletableFuture<T> result = new CompletableFuture<>();
        background.execute(() -> {
            try {
                T value = work.get();
                clientThread.execute(() -> result.complete(value));
            } catch (RuntimeException failure) {
                clientThread.execute(() -> result.completeExceptionally(failure));
            }
        });
        return result;
    }

    private OptionalLong known(Region region) {
        Objects.requireNonNull(region);
        if (cheap(region)) {
            return OptionalLong.of(cells(region));
        }
        Long count = known.get(region);
        return count == null ? OptionalLong.empty() : OptionalLong.of(count);
    }

    /** Whether counting {@code region} costs next to nothing. */
    static boolean cheap(Region region) {
        if (!(region instanceof Region.Shape shape)) {
            return true; // a box's volume, a cell set's size, an upload's count
        }
        return rows(shape) <= INLINE_ROWS && shape.box().volume() <= OpCompiler.LONG_PATH_VOLUME;
    }

    private void schedule() {
        if (running != null) {
            return;
        }
        // Counts someone waits for first (oldest first), then the latest one shown.
        Iterator<Region> asked = waiting.keySet().iterator();
        Region next = asked.hasNext() ? asked.next() : wanted;
        if (next == null) {
            return;
        }
        Region counting = next;
        running = counting;
        background.execute(() -> {
            long cells;
            try {
                cells = cells(counting);
            } catch (RuntimeException failure) {
                clientThread.execute(() -> failed(counting, failure));
                return;
            }
            clientThread.execute(() -> finished(counting, cells));
        });
    }

    private void finished(Region region, long cells) {
        running = null;
        known.put(region, cells);
        if (region.equals(wanted)) {
            wanted = null;
        }
        CompletableFuture<Long> result = waiting.remove(region);
        if (result != null) {
            result.complete(cells);
        }
        schedule();
    }

    private void failed(Region region, RuntimeException failure) {
        running = null;
        if (region.equals(wanted)) {
            wanted = null;
        }
        CompletableFuture<Long> result = waiting.remove(region);
        if (result != null) {
            result.completeExceptionally(failure);
        }
        schedule();
    }
}
