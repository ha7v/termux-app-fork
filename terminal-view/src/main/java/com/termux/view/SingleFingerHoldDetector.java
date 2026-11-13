package com.termux.view;

/**
 * Pure Java state machine for single-finger hold gestures, free of Android dependencies so the
 * timing, jitter deadzone and mode transitions can be unit tested on the JVM.
 *
 * <h3>Behaviour</h3>
 * <ul>
 *   <li>Moving beyond {@code touchSlop} before Stage 1 ({@code ~450ms}) aborts hold detection so
 *       normal scrolling runs unhindered.</li>
 *   <li><b>Normal mode ({@code !mouseTracking})</b>: Stage 1 immediately enters Termux text
 *       selection ({@link Action#START_TEXT_SELECTION}). Lifting within {@code jitterSlop} keeps
 *       the initial word selected with handles; dragging past {@code jitterSlop} extends the
 *       selection live ({@link Action#UPDATE_TEXT_SELECTION}) until lift
 *       ({@link Action#FINISH_TEXT_SELECTION}).</li>
 *   <li><b>Mouse-tracking mode ({@code mouseTracking})</b>:
 *     <ul>
 *       <li>Stage 1 arms mouse hold ({@link Action#ARM_MOUSE_HOLD}) and starts the Stage 2 timer.</li>
 *       <li>Lifting before Stage 2 without leaving {@code jitterSlop} sends a right click
 *           ({@link Action#SEND_MOUSE_RIGHT_CLICK}).</li>
 *       <li>Moving outside {@code jitterSlop} before Stage 2 cancels Stage 2 and starts a left-button
 *           mouse drag from the anchor ({@link Action#START_MOUSE_DRAG}, then
 *           {@link Action#UPDATE_MOUSE_DRAG}, ending with {@link Action#FINISH_MOUSE_DRAG}).</li>
 *       <li>Staying within {@code jitterSlop} until Stage 2 ({@code ~1200ms}) escalates out of TUI
 *           mouse capture into Termux native text selection
 *           ({@link Action#ESCALATE_TO_TEXT_SELECTION}), which can then be dragged or lifted just
 *           like normal-mode selection.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public final class SingleFingerHoldDetector {

    public enum Action {
        NONE,
        /** Movement before Stage 1 exceeded touch slop; cancel pending hold timers and let scroll run. */
        ABORT_TO_SCROLL,
        /** Stage 1 reached in non-mouse mode: highlight word at anchor and enter selection drag state. */
        START_TEXT_SELECTION,
        /** Stage 1 reached in mouse mode: give light haptic feedback and schedule Stage 2 timer. */
        ARM_MOUSE_HOLD,
        /** Stage 2 reached in mouse mode: bypass mouse tracking and start Termux native selection. */
        ESCALATE_TO_TEXT_SELECTION,
        /** Finger moved past jitter slop while armed in mouse mode: press left button at anchor and drag. */
        START_MOUSE_DRAG,
        /** Subsequent move during an active mouse drag. */
        UPDATE_MOUSE_DRAG,
        /** Finger lifted at the end of an active mouse drag: release left button. */
        FINISH_MOUSE_DRAG,
        /** Gesture cancelled during an active mouse drag: release left button safely. */
        CANCEL_MOUSE_DRAG,
        /** Finger lifted while armed in mouse mode before Stage 2 without dragging: send right click. */
        SEND_MOUSE_RIGHT_CLICK,
        /** Finger moved past jitter slop while in text selection: extend selection to current point. */
        UPDATE_TEXT_SELECTION,
        /** Finger lifted after text selection started: activate handles and floating action toolbar. */
        FINISH_TEXT_SELECTION,
        /** Gesture cancelled (e.g. second finger down) before text selection was finalized. */
        CANCEL_TEXT_SELECTION
    }

    private enum State {
        IDLE,
        WAITING_STAGE_1,
        MOUSE_HOLD_ARMED,
        MOUSE_DRAGGING,
        SELECTING_TEXT
    }

    private final float mTouchSlop;
    private final float mJitterSlop;

    private State mState = State.IDLE;
    private boolean mMouseTracking;
    private boolean mConsumedCurrentGesture;
    private boolean mSelectionDragged;

    private float mDownX, mDownY;
    private float mAnchorX, mAnchorY;
    private float mLastX, mLastY;

    public SingleFingerHoldDetector(float touchSlop, float jitterSlop) {
        mTouchSlop = touchSlop;
        mJitterSlop = jitterSlop;
    }

    /** Begin tracking a new single-finger touch from {@code ACTION_DOWN}. */
    public void onDown(float x, float y, boolean mouseTrackingActive) {
        mState = State.WAITING_STAGE_1;
        mMouseTracking = mouseTrackingActive;
        mConsumedCurrentGesture = false;
        mSelectionDragged = false;
        mDownX = mAnchorX = mLastX = x;
        mDownY = mAnchorY = mLastY = y;
    }

    /** Reset without starting a hold (e.g. when {@code ACTION_DOWN} happens while already selecting). */
    public void reset() {
        mState = State.IDLE;
        mConsumedCurrentGesture = false;
        mSelectionDragged = false;
    }

    /** Called when the Stage 1 timer (~450ms) fires. */
    public Action onStage1Timeout() {
        if (mState != State.WAITING_STAGE_1) return Action.NONE;
        mConsumedCurrentGesture = true;
        mAnchorX = mLastX;
        mAnchorY = mLastY;
        if (mMouseTracking) {
            mState = State.MOUSE_HOLD_ARMED;
            return Action.ARM_MOUSE_HOLD;
        } else {
            mState = State.SELECTING_TEXT;
            mSelectionDragged = false;
            return Action.START_TEXT_SELECTION;
        }
    }

    /** Called when the Stage 2 timer (~1200ms) fires in mouse-tracking mode. */
    public Action onStage2Timeout() {
        if (mState != State.MOUSE_HOLD_ARMED) return Action.NONE;
        mAnchorX = mLastX;
        mAnchorY = mLastY;
        mState = State.SELECTING_TEXT;
        mSelectionDragged = false;
        return Action.ESCALATE_TO_TEXT_SELECTION;
    }

    /** Feed pointer movement from {@code ACTION_MOVE}. */
    public Action onMove(float x, float y) {
        mLastX = x;
        mLastY = y;
        switch (mState) {
            case WAITING_STAGE_1:
                if (distance(x, y, mDownX, mDownY) >= mTouchSlop) {
                    mState = State.IDLE;
                    return Action.ABORT_TO_SCROLL;
                }
                return Action.NONE;

            case MOUSE_HOLD_ARMED:
                if (distance(x, y, mAnchorX, mAnchorY) >= mJitterSlop) {
                    mState = State.MOUSE_DRAGGING;
                    return Action.START_MOUSE_DRAG;
                }
                return Action.NONE;

            case MOUSE_DRAGGING:
                return Action.UPDATE_MOUSE_DRAG;

            case SELECTING_TEXT:
                if (!mSelectionDragged) {
                    if (distance(x, y, mAnchorX, mAnchorY) < mJitterSlop) {
                        return Action.NONE;
                    }
                    mSelectionDragged = true;
                }
                return Action.UPDATE_TEXT_SELECTION;

            case IDLE:
            default:
                return Action.NONE;
        }
    }

    /** Finish the gesture on {@code ACTION_UP}. */
    public Action onUp(float x, float y) {
        mLastX = x;
        mLastY = y;
        State prev = mState;
        mState = State.IDLE;
        switch (prev) {
            case WAITING_STAGE_1:
                return Action.ABORT_TO_SCROLL;
            case MOUSE_HOLD_ARMED:
                return Action.SEND_MOUSE_RIGHT_CLICK;
            case MOUSE_DRAGGING:
                return Action.FINISH_MOUSE_DRAG;
            case SELECTING_TEXT:
                return Action.FINISH_TEXT_SELECTION;
            case IDLE:
            default:
                return Action.NONE;
        }
    }

    /**
     * Cancel any active hold or drag (e.g. on {@code ACTION_CANCEL}, {@code ACTION_POINTER_DOWN},
     * or view detach). When cancelled by a second finger going down, pass
     * {@code clearConsumedFlag = true} so multi-finger handling owns the gesture cleanly.
     */
    public Action onCancel(boolean clearConsumedFlag) {
        State prev = mState;
        mState = State.IDLE;
        mSelectionDragged = false;
        if (clearConsumedFlag) mConsumedCurrentGesture = false;
        switch (prev) {
            case MOUSE_DRAGGING:
                return Action.CANCEL_MOUSE_DRAG;
            case SELECTING_TEXT:
                return Action.CANCEL_TEXT_SELECTION;
            case WAITING_STAGE_1:
            case MOUSE_HOLD_ARMED:
                return Action.ABORT_TO_SCROLL;
            case IDLE:
            default:
                return Action.NONE;
        }
    }

    /**
     * True once Stage 1 has fired in the current single-finger gesture (remains true across
     * {@link #onUp} until the next {@link #onDown} so trailing tap/scroll callbacks ignore it).
     */
    public boolean consumedCurrentGesture() {
        return mConsumedCurrentGesture;
    }

    /** True while actively holding/dragging an unfinalized Termux text selection. */
    public boolean isDraggingTextSelection() {
        return mState == State.SELECTING_TEXT;
    }

    /** True once the finger has moved past jitter slop during {@link State#SELECTING_TEXT}. */
    public boolean hasDraggedSelectionBeyondSlop() {
        return mState == State.SELECTING_TEXT && mSelectionDragged;
    }

    public float getAnchorX() {
        return mAnchorX;
    }

    public float getAnchorY() {
        return mAnchorY;
    }

    public float getLastX() {
        return mLastX;
    }

    public float getLastY() {
        return mLastY;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.hypot(dx, dy);
    }
}
