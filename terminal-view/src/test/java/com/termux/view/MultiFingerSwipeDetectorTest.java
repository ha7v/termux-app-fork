package com.termux.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit tests for {@link MultiFingerSwipeDetector}. Runs on the JVM. */
public class MultiFingerSwipeDetectorTest {

    private static final int FINGERS = 3;
    private static final float MIN_H = 30f;
    private static final float MAX_V = 15f;

    private static MultiFingerSwipeDetector detector() {
        return new MultiFingerSwipeDetector(FINGERS, MIN_H, MAX_V);
    }

    /** Simulates the three fingers going down at (x, y). */
    private static MultiFingerSwipeDetector ready(float x, float y) {
        MultiFingerSwipeDetector d = detector();
        d.onDown();
        d.onPointerCount(2, x, y);
        d.onPointerCount(3, x, y);
        return d;
    }

    @Test
    public void leftSwipeIsPrevious() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_LEFT, d.onMove(3, 100f - MIN_H, 100f));
    }

    @Test
    public void rightSwipeIsNext() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_RIGHT, d.onMove(3, 100f + MIN_H, 100f));
    }

    @Test
    public void fastSwipeWithASingleMoveIsRecognised() {
        // The baseline is captured when the fingers go down, so one move event is enough.
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_LEFT, d.onMove(3, 100f - MIN_H, 100f));
    }

    @Test
    public void swipeFiresOnlyOnce() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_LEFT, d.onMove(3, 40f, 100f));
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(3, 20f, 100f));
    }

    @Test
    public void wrongFingerCountIsIgnored() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(2, 20f, 100f));
    }

    @Test
    public void verticalMovementIsNotAHorizontalSwipe() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(3, 20f, 100f + MAX_V + 1f));
    }

    @Test
    public void smallMovementIsNotASwipe() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(3, 100f - MIN_H + 1f, 100f));
    }

    @Test
    public void stateDoesNotLeakIntoTheNextGesture() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_RIGHT, d.onMove(3, 100f + MIN_H, 100f));

        d.onDown();
        d.onPointerCount(3, 200f, 200f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_LEFT, d.onMove(3, 200f - MIN_H, 200f));
    }
}
