package dev.sculptory.fabric.client.editor.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Brush strokes counted from each brush's own count of strokes begun, read once per frame. */
class StrokeCountTest {
    @Test
    void aStrokeCountsOnceItsPressIsLetGo() {
        EditorProbe.StrokeCount count = new EditorProbe.StrokeCount();
        count.observe(4, false);
        assertEquals(0, count.ended(), "strokes before the first frame seen don't count");
        count.observe(5, true);
        assertEquals(0, count.ended(), "still pressed");
        count.observe(6, true);
        assertEquals(0, count.ended(), "a restart mid-press (a setting changed) is still the same press");
        count.observe(6, false);
        assertEquals(2, count.ended(), "each server stroke is its own history entry");
        count.observe(6, false);
        assertEquals(2, count.ended());
    }

    @Test
    void aClickPressedAndLetGoBetweenTwoFramesStillCounts() {
        EditorProbe.StrokeCount count = new EditorProbe.StrokeCount();
        count.observe(0, false);
        count.observe(1, false);
        assertEquals(1, count.ended());
    }

    @Test
    void aPressThatNeverBeganAStrokeCountsNothing() {
        EditorProbe.StrokeCount count = new EditorProbe.StrokeCount();
        count.observe(3, false);
        count.observe(3, true);
        count.observe(3, false);
        assertEquals(0, count.ended(), "a press in the sky, or one the server refused before its first dab");
    }
}
