package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.view.SingleFingerHoldDetector.Action;

import org.junit.Test;

/** Unit tests for {@link SingleFingerHoldDetector}. Runs on the JVM. */
public class SingleFingerHoldDetectorTest {

    private static final float TOUCH_SLOP = 16f;
    private static final float JITTER_SLOP = 20f;

    private static SingleFingerHoldDetector detector() {
        return new SingleFingerHoldDetector(TOUCH_SLOP, JITTER_SLOP);
    }

    @Test
    public void movePastTouchSlopBeforeStage1AbortsToScroll() {
        SingleFingerHoldDetector d = detector();
        d.onDown(100f, 100f, false);
        assertEquals(Action.NONE, d.onMove(100f, 110f));
        assertEquals(Action.ABORT_TO_SCROLL, d.onMove(100f, 100f + TOUCH_SLOP));
        // Late Stage 1 timer must become a no-op.
        assertEquals(Action.NONE, d.onStage1Timeout());
        assertFalse(d.consumedCurrentGesture());
    }

    @Test
    public void normalModeHoldAndLiftSelectsWordAndPersistsConsumedFlag() {
        SingleFingerHoldDetector d = detector();
        d.onDown(100f, 200f, false);
        assertEquals(Action.START_TEXT_SELECTION, d.onStage1Timeout());
        assertTrue(d.consumedCurrentGesture());
        assertTrue(d.isDraggingTextSelection());
        assertFalse(d.hasDraggedSelectionBeyondSlop());

        // Minor finger tremor on lift stays within jitter slop and does not collapse word selection.
        assertEquals(Action.NONE, d.onMove(108f, 206f));
        assertFalse(d.hasDraggedSelectionBeyondSlop());

        assertEquals(Action.FINISH_TEXT_SELECTION, d.onUp(108f, 206f));
        assertFalse(d.isDraggingTextSelection());
        // Consumed flag stays true until next ACTION_DOWN so trailing tap/scroll callbacks ignore it.
        assertTrue(d.consumedCurrentGesture());
    }

    @Test
    public void normalModeHoldThenSlideExtendsSelectionContinuously() {
        SingleFingerHoldDetector d = detector();
        d.onDown(100f, 200f, false);
        assertEquals(Action.START_TEXT_SELECTION, d.onStage1Timeout());

        // Cross jitter slop -> begins extending selection.
        assertEquals(Action.UPDATE_TEXT_SELECTION, d.onMove(100f + JITTER_SLOP, 200f));
        assertTrue(d.hasDraggedSelectionBeyondSlop());

        // Subsequent small moves continue updating smoothly even back inside initial jitter radius.
        assertEquals(Action.UPDATE_TEXT_SELECTION, d.onMove(105f, 202f));
        assertEquals(Action.FINISH_TEXT_SELECTION, d.onUp(150f, 240f));
    }

    @Test
    public void mouseModeStage1ThenLiftSendsRightClick() {
        SingleFingerHoldDetector d = detector();
        d.onDown(80f, 120f, true);
        assertEquals(Action.ARM_MOUSE_HOLD, d.onStage1Timeout());
        assertTrue(d.consumedCurrentGesture());

        // Slight jitter before lifting still counts as a stationary hold -> right click.
        assertEquals(Action.NONE, d.onMove(85f, 125f));
        assertEquals(Action.SEND_MOUSE_RIGHT_CLICK, d.onUp(85f, 125f));
        assertEquals(80f, d.getAnchorX(), 0.001f);
        assertEquals(120f, d.getAnchorY(), 0.001f);

        // Stage 2 firing late is ignored.
        assertEquals(Action.NONE, d.onStage2Timeout());
    }

    @Test
    public void mouseModeStage1ThenSlideStartsMouseLeftDragAndCancelsStage2() {
        SingleFingerHoldDetector d = detector();
        d.onDown(80f, 120f, true);
        assertEquals(Action.ARM_MOUSE_HOLD, d.onStage1Timeout());

        // Move past jitter slop -> starts left-button mouse drag from anchor.
        assertEquals(Action.START_MOUSE_DRAG, d.onMove(80f + JITTER_SLOP, 120f));
        // Stage 2 timeout must not hijack an active mouse drag into text selection.
        assertEquals(Action.NONE, d.onStage2Timeout());

        assertEquals(Action.UPDATE_MOUSE_DRAG, d.onMove(140f, 160f));
        assertEquals(Action.FINISH_MOUSE_DRAG, d.onUp(140f, 160f));
    }

    @Test
    public void mouseModeStage2EscalatesToTermuxNativeSelection() {
        SingleFingerHoldDetector d = detector();
        d.onDown(80f, 120f, true);
        assertEquals(Action.ARM_MOUSE_HOLD, d.onStage1Timeout());
        // Tremor within jitter slop before Stage 2:
        assertEquals(Action.NONE, d.onMove(86f, 124f));

        // 1.2s Stage 2 fires -> breaks out of TUI mouse capture into Termux selection!
        assertEquals(Action.ESCALATE_TO_TEXT_SELECTION, d.onStage2Timeout());
        assertTrue(d.isDraggingTextSelection());
        assertEquals(86f, d.getAnchorX(), 0.001f);
        assertEquals(124f, d.getAnchorY(), 0.001f);

        // Can now slide to extend Termux selection and lift to show handles.
        assertEquals(Action.UPDATE_TEXT_SELECTION, d.onMove(86f + JITTER_SLOP, 124f));
        assertEquals(Action.FINISH_TEXT_SELECTION, d.onUp(160f, 150f));
    }

    @Test
    public void secondFingerCancelClearsSelectionAndConsumedFlag() {
        SingleFingerHoldDetector d = detector();
        d.onDown(100f, 100f, false);
        assertEquals(Action.START_TEXT_SELECTION, d.onStage1Timeout());
        assertTrue(d.consumedCurrentGesture());

        assertEquals(Action.CANCEL_TEXT_SELECTION, d.onCancel(true));
        assertFalse(d.consumedCurrentGesture());
        assertFalse(d.isDraggingTextSelection());
    }
}
