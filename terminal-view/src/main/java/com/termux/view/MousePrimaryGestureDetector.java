package com.termux.view;

/**
 * Pure Java state machine for external mouse ({@code SOURCE_MOUSE}) primary-button gestures, free
 * of Android dependencies so all modes and timer/slop transitions can be unit tested on the JVM.
 *
 * <h3>Behaviour</h3>
 * <ul>
 *   <li><b>Modified URL click ({@code modifiedIntercept})</b>: {@code Ctrl+Click} or
 *       {@code Ctrl+Shift+Click} on a URL triggers {@link Action#PERFORM_MODIFIED_TAP} on release
 *       if the pointer stays within {@code touchSlop}.</li>
 *   <li><b>Normal mode ({@code !mouseTracking}) or {@code Shift+Left} bypass ({@code shiftBypass})</b>:
 *     <ul>
 *       <li>Dragging past {@code touchSlop} immediately starts character-granular Termux selection
 *           from the press point ({@link Action#START_CHAR_DRAG_SELECTION}).</li>
 *       <li>Holding stationary within {@code touchSlop} for Stage 1 ({@code ~450ms}) highlights the
 *           word under the cursor ({@link Action#START_WORD_SELECTION}), which can then be dragged
 *           past {@code jitterSlop} to extend.</li>
 *       <li>Releasing quickly without dragging dismisses an existing selection
 *           ({@link Action#DISMISS_SELECTION}), or performs a normal single click
 *           ({@link Action#PERFORM_SINGLE_TAP}) in non-mouse mode.</li>
 *     </ul>
 *   </li>
 *   <li><b>TUI mouse-tracking mode ({@code mouseTracking && !shiftBypass})</b>:
 *     <ul>
 *       <li>When no selection is active, {@code ACTION_DOWN} immediately presses the TUI left
 *           button and arms a Stage 2 ({@code ~1200ms}) escalation timer
 *           ({@link Action#PRESS_TUI_AND_SCHEDULE_ESCALATION}).</li>
 *       <li>Moving past {@code touchSlop} cancels the escalation timer and continues TUI mouse
 *           dragging ({@link Action#MOVE_TUI_AND_CANCEL_ESCALATION}, {@link Action#MOVE_TUI}).</li>
 *       <li>Holding stationary within {@code touchSlop} until Stage 2 ({@code ~1200ms}) releases
 *           the TUI mouse button and escalates into Termux native word selection
 *           ({@link Action#ESCALATE_TUI_TO_WORD_SELECTION}).</li>
 *       <li>When a Termux selection is already active, a quick click dismisses it without sending a
 *           stray click to the TUI ({@link Action#DISMISS_SELECTION}), while holding stationary for
 *           Stage 2 starts a new word selection.</li>
 *     </ul>
 *   </li>
 * </ul>
 */
public final class MousePrimaryGestureDetector {

    public enum Action {
        NONE,
        STOP_TIMERS,
        SCHEDULE_HOLD_STAGE_1,
        SCHEDULE_HOLD_STAGE_2,
        PRESS_TUI_AND_SCHEDULE_ESCALATION,
        MOVE_TUI,
        MOVE_TUI_AND_CANCEL_ESCALATION,
        RELEASE_TUI,
        START_CHAR_DRAG_SELECTION,
        START_WORD_SELECTION,
        ESCALATE_TUI_TO_WORD_SELECTION,
        UPDATE_TEXT_SELECTION,
        FINISH_TEXT_SELECTION,
        CANCEL_TEXT_SELECTION,
        DISMISS_SELECTION,
        PERFORM_MODIFIED_TAP,
        PERFORM_SINGLE_TAP
    }

    private enum State {
        IDLE,
        MODIFIED_TAP_PENDING,
        WAITING_NORMAL_OR_SHIFT,
        WAITING_DISMISS_OR_ESCALATE,
        DISMISS_ONLY_ON_UP,
        TUI_FORWARDING_ARMED,
        TUI_DRAGGING,
        SELECTING_CHAR_DRAG,
        SELECTING_WORD_HOLD
    }

    private final float mTouchSlop;
    private final float mJitterSlop;

    private State mState = State.IDLE;
    private boolean mShiftBypass;
    private boolean mHadActiveSelection;
    private boolean mSelectionDragged;

    private float mDownX, mDownY;
    private float mAnchorX, mAnchorY;
    private float mLastX, mLastY;

    public MousePrimaryGestureDetector(float touchSlop, float jitterSlop) {
        mTouchSlop = touchSlop;
        mJitterSlop = jitterSlop;
    }

    public Action onDown(float x, float y, boolean mouseTracking, boolean modifiedIntercept,
                         boolean shiftBypass, boolean hadActiveSelection) {
        mDownX = mAnchorX = mLastX = x;
        mDownY = mAnchorY = mLastY = y;
        mShiftBypass = shiftBypass;
        mHadActiveSelection = hadActiveSelection;
        mSelectionDragged = false;

        if (modifiedIntercept) {
            mState = State.MODIFIED_TAP_PENDING;
            return Action.NONE;
        }
        if (!mouseTracking || shiftBypass) {
            mState = State.WAITING_NORMAL_OR_SHIFT;
            return Action.SCHEDULE_HOLD_STAGE_1;
        }
        if (hadActiveSelection) {
            mState = State.WAITING_DISMISS_OR_ESCALATE;
            return Action.SCHEDULE_HOLD_STAGE_2;
        }
        mState = State.TUI_FORWARDING_ARMED;
        return Action.PRESS_TUI_AND_SCHEDULE_ESCALATION;
    }

    public Action onStage1Timeout() {
        if (mState != State.WAITING_NORMAL_OR_SHIFT) return Action.NONE;
        mAnchorX = mLastX;
        mAnchorY = mLastY;
        mState = State.SELECTING_WORD_HOLD;
        mSelectionDragged = false;
        return Action.START_WORD_SELECTION;
    }

    public Action onStage2Timeout() {
        if (mState == State.TUI_FORWARDING_ARMED) {
            mAnchorX = mLastX;
            mAnchorY = mLastY;
            mState = State.SELECTING_WORD_HOLD;
            mSelectionDragged = false;
            return Action.ESCALATE_TUI_TO_WORD_SELECTION;
        }
        if (mState == State.WAITING_DISMISS_OR_ESCALATE) {
            mAnchorX = mLastX;
            mAnchorY = mLastY;
            mState = State.SELECTING_WORD_HOLD;
            mSelectionDragged = false;
            return Action.START_WORD_SELECTION;
        }
        return Action.NONE;
    }

    public Action onMove(float x, float y) {
        mLastX = x;
        mLastY = y;
        switch (mState) {
            case MODIFIED_TAP_PENDING:
                if (distance(x, y, mDownX, mDownY) >= mTouchSlop) {
                    mState = State.IDLE;
                }
                return Action.NONE;

            case WAITING_NORMAL_OR_SHIFT:
                if (distance(x, y, mDownX, mDownY) >= mTouchSlop) {
                    mAnchorX = mDownX;
                    mAnchorY = mDownY;
                    mState = State.SELECTING_CHAR_DRAG;
                    mSelectionDragged = true;
                    return Action.START_CHAR_DRAG_SELECTION;
                }
                return Action.NONE;

            case WAITING_DISMISS_OR_ESCALATE:
                if (distance(x, y, mDownX, mDownY) >= mTouchSlop) {
                    mState = State.DISMISS_ONLY_ON_UP;
                    return Action.STOP_TIMERS;
                }
                return Action.NONE;

            case TUI_FORWARDING_ARMED:
                if (distance(x, y, mDownX, mDownY) >= mTouchSlop) {
                    mState = State.TUI_DRAGGING;
                    return Action.MOVE_TUI_AND_CANCEL_ESCALATION;
                }
                return Action.MOVE_TUI;

            case TUI_DRAGGING:
                return Action.MOVE_TUI;

            case SELECTING_CHAR_DRAG:
                return Action.UPDATE_TEXT_SELECTION;

            case SELECTING_WORD_HOLD:
                if (!mSelectionDragged) {
                    if (distance(x, y, mAnchorX, mAnchorY) < mJitterSlop) {
                        return Action.NONE;
                    }
                    mSelectionDragged = true;
                }
                return Action.UPDATE_TEXT_SELECTION;

            case DISMISS_ONLY_ON_UP:
            case IDLE:
            default:
                return Action.NONE;
        }
    }

    public Action onUp(float x, float y) {
        mLastX = x;
        mLastY = y;
        State prev = mState;
        mState = State.IDLE;
        mSelectionDragged = false;
        switch (prev) {
            case MODIFIED_TAP_PENDING:
                return Action.PERFORM_MODIFIED_TAP;
            case WAITING_NORMAL_OR_SHIFT:
                if (mHadActiveSelection) return Action.DISMISS_SELECTION;
                return mShiftBypass ? Action.STOP_TIMERS : Action.PERFORM_SINGLE_TAP;
            case WAITING_DISMISS_OR_ESCALATE:
            case DISMISS_ONLY_ON_UP:
                return Action.DISMISS_SELECTION;
            case TUI_FORWARDING_ARMED:
            case TUI_DRAGGING:
                return Action.RELEASE_TUI;
            case SELECTING_CHAR_DRAG:
            case SELECTING_WORD_HOLD:
                return Action.FINISH_TEXT_SELECTION;
            case IDLE:
            default:
                return Action.NONE;
        }
    }

    public Action onCancel() {
        State prev = mState;
        mState = State.IDLE;
        mSelectionDragged = false;
        switch (prev) {
            case TUI_FORWARDING_ARMED:
            case TUI_DRAGGING:
                return Action.RELEASE_TUI;
            case SELECTING_CHAR_DRAG:
            case SELECTING_WORD_HOLD:
                return Action.CANCEL_TEXT_SELECTION;
            case MODIFIED_TAP_PENDING:
            case WAITING_NORMAL_OR_SHIFT:
            case WAITING_DISMISS_OR_ESCALATE:
            case DISMISS_ONLY_ON_UP:
                return Action.STOP_TIMERS;
            case IDLE:
            default:
                return Action.NONE;
        }
    }

    public boolean isDraggingTextSelection() {
        return mState == State.SELECTING_CHAR_DRAG || mState == State.SELECTING_WORD_HOLD;
    }

    public boolean hasDraggedSelectionBeyondSlop() {
        return isDraggingTextSelection() && mSelectionDragged;
    }

    public boolean usedShiftBypass() {
        return mShiftBypass;
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
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }
}
