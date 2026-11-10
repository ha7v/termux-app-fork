package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Unit tests for {@link MultiFingerTapDetector}. Runs on the JVM. */
public class MultiFingerTapDetectorTest {

    private static final float SLOP = 10f;
    private static final long LONG_PRESS = 500L;

    private static MultiFingerTapDetector detector() {
        return new MultiFingerTapDetector(SLOP, LONG_PRESS);
    }

    // --- pure classification ---------------------------------------------------------------------

    @Test
    public void twoFingersWithoutMovementIsATap() {
        assertEquals(MultiFingerTapDetector.TAP_TWO_FINGERS,
            MultiFingerTapDetector.classifyTap(2, 0f, SLOP, 100L, LONG_PRESS));
    }

    @Test
    public void threeFingersWithoutMovementIsATap() {
        assertEquals(MultiFingerTapDetector.TAP_THREE_FINGERS,
            MultiFingerTapDetector.classifyTap(3, 0f, SLOP, 100L, LONG_PRESS));
    }

    @Test
    public void oneOrFourFingersIsNotATap() {
        assertEquals(MultiFingerTapDetector.TAP_NONE,
            MultiFingerTapDetector.classifyTap(1, 0f, SLOP, 100L, LONG_PRESS));
        assertEquals(MultiFingerTapDetector.TAP_NONE,
            MultiFingerTapDetector.classifyTap(4, 0f, SLOP, 100L, LONG_PRESS));
    }

    @Test
    public void movementBeyondSlopIsNotATap() {
        assertEquals(MultiFingerTapDetector.TAP_NONE,
            MultiFingerTapDetector.classifyTap(2, SLOP, SLOP, 100L, LONG_PRESS));
        assertEquals(MultiFingerTapDetector.TAP_NONE,
            MultiFingerTapDetector.classifyTap(3, SLOP + 1f, SLOP, 100L, LONG_PRESS));
    }

    @Test
    public void longPressDurationIsNotATap() {
        assertEquals(MultiFingerTapDetector.TAP_NONE,
            MultiFingerTapDetector.classifyTap(2, 0f, SLOP, LONG_PRESS, LONG_PRESS));
    }

    // --- stateful sequences ----------------------------------------------------------------------

    @Test
    public void twoFingerSequenceIsATap() {
        MultiFingerTapDetector d = detector();
        d.onDown(1000L, 50f, 50f);
        d.onPointerCount(2);
        assertEquals(MultiFingerTapDetector.TAP_TWO_FINGERS, d.onUp(1080L));
        assertFalse(d.isTracking());
    }

    @Test
    public void twoFingerDragIsNotATap() {
        MultiFingerTapDetector d = detector();
        d.onDown(1000L, 50f, 50f);
        d.onPointerCount(2);
        d.onMove(50f, 80f); // 30px > slop
        assertEquals(MultiFingerTapDetector.TAP_NONE, d.onUp(1080L));
    }

    @Test
    public void threeFingerSequenceIsATap() {
        MultiFingerTapDetector d = detector();
        d.onDown(1000L, 50f, 50f);
        d.onPointerCount(2);
        d.onPointerCount(3);
        assertEquals(MultiFingerTapDetector.TAP_THREE_FINGERS, d.onUp(1080L));
    }

    @Test
    public void cancelledGestureIsNotATap() {
        MultiFingerTapDetector d = detector();
        d.onDown(1000L, 50f, 50f);
        d.onPointerCount(2);
        d.onCancel();
        assertEquals(MultiFingerTapDetector.TAP_NONE, d.onUp(1080L));
    }

    @Test
    public void stateDoesNotLeakIntoTheNextGesture() {
        MultiFingerTapDetector d = detector();
        d.onDown(1000L, 50f, 50f);
        d.onPointerCount(3);
        assertEquals(MultiFingerTapDetector.TAP_THREE_FINGERS, d.onUp(1080L));

        // A new single-finger gesture must not inherit the previous pointer count.
        d.onDown(2000L, 10f, 10f);
        assertTrue(d.isTracking());
        assertEquals(1, d.getMaxPointerCount());
        assertEquals(MultiFingerTapDetector.TAP_NONE, d.onUp(2050L));
    }
}
