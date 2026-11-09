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

    @Test
    public void leftSwipeIsPrevious() {
        MultiFingerSwipeDetector d = detector();
        d.onDown();
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(3, 100f, 100f));
        assertEquals(MultiFingerSwipeDetector.SWIPE_LEFT, d.onMove(3, 100f - MIN_H, 100f));
    }

    @Test
    public void rightSwipeIsNext() {
        MultiFingerSwipeDetector d = detector();
        d.onDown();
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(3, 100f, 100f));
        assertEquals(MultiFingerSwipeDetector.SWIPE_RIGHT, d.onMove(3, 100f + MIN_H, 100f));
    }

    @Test
    public void swipeFiresOnlyOnce() {
        MultiFingerSwipeDetector d = detector();
        d.onDown();
        d.onMove(3, 100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_LEFT, d.onMove(3, 40f, 100f));
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(3, 20f, 100f));
    }

    @Test
    public void wrongFingerCountIsIgnored() {
        MultiFingerSwipeDetector d = detector();
        d.onDown();
        d.onMove(2, 100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(2, 20f, 100f));
    }

    @Test
    public void verticalMovementIsNotAHorizontalSwipe() {
        MultiFingerSwipeDetector d = detector();
        d.onDown();
        d.onMove(3, 100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(3, 20f, 100f + MAX_V + 1f));
    }

    @Test
    public void smallMovementIsNotASwipe() {
        MultiFingerSwipeDetector d = detector();
        d.onDown();
        d.onMove(3, 100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(3, 100f - MIN_H + 1f, 100f));
    }

    @Test
    public void baselineIsTheFirstMoveOfTheGesture() {
        MultiFingerSwipeDetector d = detector();
        d.onDown();
        // The first move only sets the baseline, so a large jump from the down point is not a swipe.
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(3, 500f, 100f));
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(3, 500f - MIN_H + 1f, 100f));
        assertEquals(MultiFingerSwipeDetector.SWIPE_LEFT, d.onMove(3, 500f - MIN_H, 100f));
    }

    @Test
    public void stateDoesNotLeakIntoTheNextGesture() {
        MultiFingerSwipeDetector d = detector();
        d.onDown();
        d.onMove(3, 100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_RIGHT, d.onMove(3, 100f + MIN_H, 100f));

        d.onDown();
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(3, 200f, 200f));
        assertEquals(MultiFingerSwipeDetector.SWIPE_LEFT, d.onMove(3, 200f - MIN_H, 200f));
    }
}
