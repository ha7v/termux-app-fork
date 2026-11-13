package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit tests for {@link MultiFingerSwipeDetector} and two-finger swipe vs pinch arbitration. */
public class MultiFingerSwipeDetectorTest {

    private static final int FINGERS = 2;
    private static final float MIN_H = 30f;
    private static final float MAX_V = 18f;
    private static final float BASE_SPAN = 200f;

    private static MultiFingerSwipeDetector detector() {
        return new MultiFingerSwipeDetector(FINGERS, MIN_H, MAX_V);
    }

    /** Simulates two fingers going down with centroid (x, y) and inter-finger span {@link #BASE_SPAN}. */
    private static MultiFingerSwipeDetector ready(float x, float y) {
        MultiFingerSwipeDetector d = detector();
        d.onDown();
        d.onPointerCount(2, x, y, BASE_SPAN);
        return d;
    }

    @Test
    public void leftSwipeIsPrevious() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_LEFT, d.onMove(2, 100f - MIN_H, 100f, BASE_SPAN));
        assertTrue(d.isSwipeLocked());
        assertFalse(d.isScaleLocked());
    }

    @Test
    public void rightSwipeIsNext() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_RIGHT, d.onMove(2, 100f + MIN_H, 100f, BASE_SPAN));
        assertTrue(d.isSwipeLocked());
    }

    @Test
    public void fastSwipeWithASingleMoveIsRecognised() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_LEFT, d.onMove(2, 100f - MIN_H, 100f, BASE_SPAN));
    }

    @Test
    public void swipeFiresOnlyOnceAndStaysSwipeLocked() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_LEFT, d.onMove(2, 40f, 100f, BASE_SPAN));
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(2, 20f, 100f, BASE_SPAN * 1.4f));
        assertTrue(d.isSwipeLocked());
        assertFalse(d.isScaleLocked());
    }

    @Test
    public void naturalSpanDriftWithinToleranceAllowsHorizontalSwipe() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        // +20% span drift is within ±23% tolerance, so a horizontal translation still swipes.
        float driftedSpan = BASE_SPAN * 1.20f;
        assertEquals(MultiFingerSwipeDetector.SWIPE_RIGHT, d.onMove(2, 100f + MIN_H, 100f, driftedSpan));
        assertTrue(d.isSwipeLocked());
        assertFalse(d.isScaleLocked());
    }

    @Test
    public void pinchBeyondSpanThresholdLocksToScaleAndBlocksSwipe() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        // +25% span change exceeds ±23% threshold -> locks to pinch zoom.
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(2, 105f, 100f, BASE_SPAN * 1.25f));
        assertTrue(d.isScaleLocked());
        assertFalse(d.isSwipeLocked());

        // Subsequent horizontal centroid drift during the pinch must not switch sessions.
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(2, 100f + MIN_H * 2f, 100f, BASE_SPAN * 1.30f));
        assertTrue(d.isScaleLocked());
    }

    @Test
    public void pinchInwardBeyondSpanThresholdLocksToScale() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        // -24% span change exceeds ±23% threshold -> locks to pinch zoom.
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(2, 98f, 100f, BASE_SPAN * 0.76f));
        assertTrue(d.isScaleLocked());
    }

    @Test
    public void thirdFingerCancelsTwoFingerSwipeAndScale() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        d.onPointerCount(3, 100f, 100f, BASE_SPAN);
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(2, 100f + MIN_H * 2f, 100f, BASE_SPAN));
        assertFalse(d.isSwipeLocked());
        assertFalse(d.isScaleLocked());
    }

    @Test
    public void diagonalOrVerticalMovementIsNotAHorizontalSwipe() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        // Exceeds maxVertical:
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(2, 20f, 100f + MAX_V + 1f, BASE_SPAN));
        // Diagonal within maxVertical where absDx <= 1.5 * absDy (e.g. dx=24 < MIN_H or dx=25, dy=17):
        MultiFingerSwipeDetector d2 = new MultiFingerSwipeDetector(2, 20f, 30f);
        d2.onDown();
        d2.onPointerCount(2, 100f, 100f, BASE_SPAN);
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d2.onMove(2, 124f, 117f, BASE_SPAN));
    }

    @Test
    public void smallMovementIsNotASwipe() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(2, 100f - MIN_H + 1f, 100f, BASE_SPAN));
    }

    @Test
    public void stateDoesNotLeakIntoTheNextGesture() {
        MultiFingerSwipeDetector d = ready(100f, 100f);
        assertEquals(MultiFingerSwipeDetector.SWIPE_NONE, d.onMove(2, 100f, 100f, BASE_SPAN * 1.30f));
        assertTrue(d.isScaleLocked());

        d.onDown();
        assertFalse(d.isScaleLocked());
        d.onPointerCount(2, 200f, 200f, BASE_SPAN);
        assertEquals(MultiFingerSwipeDetector.SWIPE_LEFT, d.onMove(2, 200f - MIN_H, 200f, BASE_SPAN));
        assertTrue(d.isSwipeLocked());
    }
}
