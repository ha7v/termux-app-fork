package com.termux.app.terminal.io;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.app.terminal.io.TerminalToolbarViewPager.StartEdgeRightSwipeTracker;

import org.junit.Test;

/** Unit tests for {@link StartEdgeRightSwipeTracker}. */
public class TerminalToolbarViewPagerTest {

    private static final float TOUCH_SLOP = 8f;
    private static final float MIN_FLING_DIST = 25f;
    private static final float MIN_FLING_VEL = 400f;
    private static final float MIN_DRAG_DIST = 56f;
    private static final long LONG_PRESS_TIMEOUT_MS = 400L;

    private static StartEdgeRightSwipeTracker tracker() {
        return new StartEdgeRightSwipeTracker(
            TOUCH_SLOP, MIN_FLING_DIST, MIN_FLING_VEL, MIN_DRAG_DIST, LONG_PRESS_TIMEOUT_MS);
    }

    @Test
    public void quickRightFlingOnFirstPageTriggers() {
        StartEdgeRightSwipeTracker t = tracker();
        t.onDown(true, 0, 100L, 40f, 20f);
        t.onMovePosition(52f);
        t.onPagerIntercepted(130L);
        t.onPagerStateAfterMove(0, 0);
        assertTrue(t.onUp(0, 0, 0, 40f + MIN_FLING_DIST + 5f, 22f, MIN_FLING_VEL + 100f));
    }

    @Test
    public void slowDeliberateRightDragOnFirstPageTriggersWithoutFlingVelocity() {
        StartEdgeRightSwipeTracker t = tracker();
        t.onDown(true, 0, 100L, 40f, 20f);
        t.onMovePosition(52f);
        t.onPagerIntercepted(150L);
        t.onPagerStateAfterMove(0, 0);
        // User pauses at the end of the drag (velocityX == 0) after exceeding MIN_DRAG_DIST.
        assertTrue(t.onUp(0, 0, 0, 40f + MIN_DRAG_DIST + 4f, 24f, 0f));
    }

    @Test
    public void rightSwipeStartingFromTextInputPageDoesNotTrigger() {
        StartEdgeRightSwipeTracker t = tracker();
        // Starting on page 1 (text input): swiping right only returns to page 0.
        t.onDown(false, 0, 100L, 200f, 20f);
        t.onPagerIntercepted(130L);
        assertFalse(t.onUp(0, 0, 0, 300f, 20f, 800f));
    }

    @Test
    public void buttonTapOrUninterceptedSwipeUpPopupDoesNotTrigger() {
        StartEdgeRightSwipeTracker t = tracker();
        t.onDown(true, 0, 100L, 40f, 20f);
        // ViewPager never intercepted (e.g. tap or vertical swipe-up for extra-key popup):
        assertFalse(t.onUp(0, 0, 0, 110f, -30f, 600f));
    }

    @Test
    public void slipAfterLongPressRepeatOrLockDoesNotTrigger() {
        StartEdgeRightSwipeTracker t = tracker();
        t.onDown(true, 0, 100L, 40f, 20f);
        // Held arrow key / CTRL beyond LONG_PRESS_TIMEOUT_MS before sliding right:
        t.onPagerIntercepted(100L + LONG_PRESS_TIMEOUT_MS);
        assertFalse(t.isEligible());
        assertFalse(t.onUp(0, 0, 0, 120f, 20f, 800f));
    }

    @Test
    public void draggingLeftTowardsTextInputThenBackRightDoesNotTrigger() {
        StartEdgeRightSwipeTracker t = tracker();
        t.onDown(true, 0, 100L, 150f, 20f);
        t.onMovePosition(135f); // dx = -15 < -TOUCH_SLOP
        assertFalse(t.isEligible());
        assertFalse(t.onUp(0, 0, 0, 230f, 20f, 900f));

        // Also rejected if ViewPager reported non-zero scrollX at any point:
        StartEdgeRightSwipeTracker t2 = tracker();
        t2.onDown(true, 0, 100L, 150f, 20f);
        t2.onPagerIntercepted(120L);
        t2.onPagerStateAfterMove(0, 4);
        assertFalse(t2.isEligible());
        assertFalse(t2.onUp(0, 0, 0, 230f, 20f, 900f));
    }

    @Test
    public void secondaryPointerOrCancelSuppressesTriggerAndResetsCleanly() {
        StartEdgeRightSwipeTracker t = tracker();
        t.onDown(true, 0, 100L, 40f, 20f);
        t.onPagerIntercepted(120L);
        t.onSecondaryPointerDown();
        assertFalse(t.onUp(0, 0, 0, 120f, 20f, 800f));

        // Next gesture works normally after cancel/reset:
        t.onDown(true, 0, 500L, 40f, 20f);
        t.onPagerIntercepted(530L);
        t.reset(); // Simulates ACTION_CANCEL (e.g. Android system back gesture)
        assertFalse(t.onUp(0, 0, 0, 120f, 20f, 800f));

        t.onDown(true, 0, 800L, 40f, 20f);
        t.onPagerIntercepted(830L);
        assertTrue(t.onUp(0, 0, 0, 110f, 20f, 600f));
    }

    @Test
    public void directTouchOnEmptyToolbarAreaAlsoWorks() {
        StartEdgeRightSwipeTracker t = tracker();
        t.onDown(true, 0, 100L, 40f, 20f);
        t.onDirectTouchMove(130L, 55f, 22f);
        assertTrue(t.isCaptured());
        assertTrue(t.onUp(0, 0, 0, 80f, 22f, 500f));
    }

    @Test
    public void diagonalMovementIsRejected() {
        StartEdgeRightSwipeTracker t = tracker();
        t.onDown(true, 0, 100L, 40f, 20f);
        t.onPagerIntercepted(130L);
        // dx = 45, dy = 32 -> dx <= |dy| * 1.5
        assertFalse(t.onUp(0, 0, 0, 85f, 52f, 700f));
    }
}
