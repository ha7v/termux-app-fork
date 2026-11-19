package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.view.MousePrimaryGestureDetector.Action;

import org.junit.Test;

/** Unit tests for {@link MousePrimaryGestureDetector}. Runs on the JVM. */
public class MousePrimaryGestureDetectorTest {

    private static final float TOUCH_SLOP = 16f;
    private static final float JITTER_SLOP = 20f;

    private static MousePrimaryGestureDetector detector() {
        return new MousePrimaryGestureDetector(TOUCH_SLOP, JITTER_SLOP);
    }

    @Test
    public void normalModeDragStartsCharSelectionImmediately() {
        MousePrimaryGestureDetector d = detector();
        assertEquals(Action.SCHEDULE_HOLD_STAGE_1, d.onDown(50f, 80f, false, false, false, false));
        assertEquals(Action.NONE, d.onMove(55f, 82f));

        assertEquals(Action.START_CHAR_DRAG_SELECTION, d.onMove(50f + TOUCH_SLOP, 80f));
        assertEquals(50f, d.getAnchorX(), 0.001f);
        assertEquals(80f, d.getAnchorY(), 0.001f);
        assertTrue(d.isDraggingTextSelection());
        assertTrue(d.hasDraggedSelectionBeyondSlop());

        // Late Stage 1 timer is ignored once dragging started.
        assertEquals(Action.NONE, d.onStage1Timeout());
        assertEquals(Action.UPDATE_TEXT_SELECTION, d.onMove(120f, 100f));
        assertEquals(Action.FINISH_TEXT_SELECTION, d.onUp(120f, 100f));
        assertFalse(d.isDraggingTextSelection());
    }

    @Test
    public void normalModeStationaryHoldSelectsWordAndAllowsExtending() {
        MousePrimaryGestureDetector d = detector();
        assertEquals(Action.SCHEDULE_HOLD_STAGE_1, d.onDown(100f, 140f, false, false, false, false));
        assertEquals(Action.START_WORD_SELECTION, d.onStage1Timeout());
        assertTrue(d.isDraggingTextSelection());
        assertFalse(d.hasDraggedSelectionBeyondSlop());

        // Slight tremor within jitter slop keeps word selection intact.
        assertEquals(Action.NONE, d.onMove(108f, 145f));
        assertFalse(d.hasDraggedSelectionBeyondSlop());

        // Dragging past jitter slop extends word selection.
        assertEquals(Action.UPDATE_TEXT_SELECTION, d.onMove(100f + JITTER_SLOP, 140f));
        assertTrue(d.hasDraggedSelectionBeyondSlop());
        assertEquals(Action.FINISH_TEXT_SELECTION, d.onUp(180f, 160f));
    }

    @Test
    public void normalModeQuickClickPerformsSingleTapOrDismissesExistingSelection() {
        MousePrimaryGestureDetector d = detector();
        assertEquals(Action.SCHEDULE_HOLD_STAGE_1, d.onDown(40f, 40f, false, false, false, false));
        assertEquals(Action.PERFORM_SINGLE_TAP, d.onUp(42f, 41f));

        // When a selection is already active, quick click dismisses it instead of firing tap.
        assertEquals(Action.SCHEDULE_HOLD_STAGE_1, d.onDown(40f, 40f, false, false, false, true));
        assertEquals(Action.DISMISS_SELECTION, d.onUp(40f, 40f));
    }

    @Test
    public void tuiModeForwardsClickAndDragAndCancelsEscalationOnDrag() {
        MousePrimaryGestureDetector d = detector();
        assertEquals(Action.PRESS_TUI_AND_SCHEDULE_ESCALATION, d.onDown(60f, 90f, true, false, false, false));
        assertEquals(Action.MOVE_TUI, d.onMove(64f, 92f));

        // Moving past touch slop cancels escalation so a slow TUI drag never hijacks selection.
        assertEquals(Action.MOVE_TUI_AND_CANCEL_ESCALATION, d.onMove(60f + TOUCH_SLOP, 90f));
        assertEquals(Action.NONE, d.onStage2Timeout());
        assertEquals(Action.MOVE_TUI, d.onMove(140f, 120f));
        assertEquals(Action.RELEASE_TUI, d.onUp(140f, 120f));
    }

    @Test
    public void tuiModeStationaryHoldEscalatesToNativeSelectionAndReleasesTuiButton() {
        MousePrimaryGestureDetector d = detector();
        assertEquals(Action.PRESS_TUI_AND_SCHEDULE_ESCALATION, d.onDown(70f, 110f, true, false, false, false));
        assertEquals(Action.MOVE_TUI, d.onMove(74f, 112f));

        // 1.2s stationary hold breaks out of TUI mouse capture and releases TUI button cleanly.
        assertEquals(Action.ESCALATE_TUI_TO_WORD_SELECTION, d.onStage2Timeout());
        assertTrue(d.isDraggingTextSelection());
        assertEquals(74f, d.getAnchorX(), 0.001f);
        assertEquals(112f, d.getAnchorY(), 0.001f);

        // Subsequent drag updates Termux selection instead of TUI mouse!
        assertEquals(Action.UPDATE_TEXT_SELECTION, d.onMove(74f + JITTER_SLOP, 112f));
        assertEquals(Action.FINISH_TEXT_SELECTION, d.onUp(150f, 130f));
    }

    @Test
    public void tuiModeShiftBypassSelectsImmediatelyWithoutForwardingToTui() {
        MousePrimaryGestureDetector d = detector();
        assertEquals(Action.SCHEDULE_HOLD_STAGE_1, d.onDown(80f, 120f, true, false, true, false));
        assertTrue(d.usedShiftBypass());
        assertEquals(Action.START_CHAR_DRAG_SELECTION, d.onMove(80f + TOUCH_SLOP, 120f));
        assertEquals(Action.UPDATE_TEXT_SELECTION, d.onMove(200f, 120f));
        assertEquals(Action.FINISH_TEXT_SELECTION, d.onUp(200f, 120f));
    }

    @Test
    public void tuiModeWithActiveSelectionDismissesCleanlyWithoutForwardingClick() {
        MousePrimaryGestureDetector d = detector();
        assertEquals(Action.SCHEDULE_HOLD_STAGE_2, d.onDown(90f, 90f, true, false, false, true));
        assertEquals(Action.DISMISS_SELECTION, d.onUp(90f, 90f));
    }

    @Test
    public void modifiedInterceptTakesPrecedenceOverShiftAndTui() {
        MousePrimaryGestureDetector d = detector();
        assertEquals(Action.NONE, d.onDown(50f, 50f, true, true, true, false));
        assertEquals(Action.PERFORM_MODIFIED_TAP, d.onUp(52f, 52f));

        // Dragging past slop cancels modified tap.
        assertEquals(Action.NONE, d.onDown(50f, 50f, true, true, false, false));
        assertEquals(Action.NONE, d.onMove(50f + TOUCH_SLOP, 50f));
        assertEquals(Action.NONE, d.onUp(100f, 50f));
    }
}
