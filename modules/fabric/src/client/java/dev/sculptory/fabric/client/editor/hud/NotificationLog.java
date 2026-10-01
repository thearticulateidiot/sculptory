package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.session.Notice;
import java.time.LocalTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * The last {@value #CAPACITY} toast messages, newest first, for the Notifications window: what a toast said can be read
 * again after it went. A message repeated straight after itself (the same text and level) is one entry with a count
 * and the latest time. Kept for the game session only.
 */
public final class NotificationLog {
    public static final int CAPACITY = 100;

    /** One message: its level, text, when it was (last) shown, and how many times in a row. */
    public record Entry(Notice.Level level, String text, LocalTime time, int count) {
        public Entry {
            Objects.requireNonNull(level);
            Objects.requireNonNull(text);
            Objects.requireNonNull(time);
        }
    }

    private final Deque<Entry> entries = new ArrayDeque<>();
    private int version;

    public void add(Notice.Level level, String text, LocalTime time) {
        Entry newest = entries.peekFirst();
        if (newest != null && newest.level() == level && newest.text().equals(text)) {
            entries.removeFirst();
            entries.addFirst(new Entry(level, text, time, newest.count() + 1));
        } else {
            entries.addFirst(new Entry(level, text, time, 1));
            while (entries.size() > CAPACITY) {
                entries.removeLast();
            }
        }
        version++;
    }

    /** The messages, newest first. */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public void clear() {
        entries.clear();
        version++;
    }

    /** Changes with every message and clear, so a view can tell when to show the entries again. */
    public int version() {
        return version;
    }
}
