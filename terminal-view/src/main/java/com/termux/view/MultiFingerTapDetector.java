package com.termux.view;

/**
 * Detects two- and three-finger taps, which the platform {@code GestureDetector} does not report
 * because it cancels taps as soon as a second pointer goes down.
 *
 * <p>A tap is a gesture whose maximum pointer count is exactly 2 or 3, whose first pointer never
 * moves beyond the touch slop, and that ends before the long-press timeout. This keeps it disjoint
 * from single-finger taps and drags, two-finger scrolling and pinch-to-zoom.
 *
 * <p>The input is primitive (action, pointer count, position, time) rather than
 * {@code MotionEvent}, so the whole state machine can be unit tested on the JVM.
 */
public final class MultiFingerTapDetector {

    public static final int TAP_NONE = 0;
    public static final int TAP_TWO_FINGERS = 2;
    public static final int TAP_THREE_FINGERS = 3;

    private final float mTouchSlop;
    private final long mLongPressTimeout;

    private boolean mTracking;
    private long mDownTime;
    private int mMaxPointerCount;
    private float mDownX, mDownY;
    private float mMaxDisplacement;

    public MultiFingerTapDetector(float touchSlop, long longPressTimeout) {
        mTouchSlop = touchSlop;
        mLongPressTimeout = longPressTimeout;
    }

    /** Start tracking a gesture, from {@code ACTION_DOWN}. */
    public void onDown(long eventTime, float x, float y) {
        mTracking = true;
        mDownTime = eventTime;
        mMaxPointerCount = 1;
        mDownX = x;
        mDownY = y;
        mMaxDisplacement = 0f;
    }

    /** Record the pointer count after an {@code ACTION_POINTER_DOWN}. */
    public void onPointerCount(int pointerCount) {
        if (!mTracking) return;
        if (pointerCount > mMaxPointerCount) mMaxPointerCount = pointerCount;
    }

    /** Track the first pointer's movement, from {@code ACTION_MOVE}. */
    public void onMove(float x, float y) {
        if (!mTracking) return;
        float dx = x - mDownX;
        float dy = y - mDownY;
        float displacement = (float) Math.sqrt(dx * dx + dy * dy);
        if (displacement > mMaxDisplacement) mMaxDisplacement = displacement;
    }

    /**
     * Finish the gesture, from {@code ACTION_UP} of the last pointer.
     *
     * @return {@link #TAP_TWO_FINGERS}, {@link #TAP_THREE_FINGERS} or {@link #TAP_NONE}.
     */
    public int onUp(long eventTime) {
        if (!mTracking) return TAP_NONE;
        int result = classifyTap(mMaxPointerCount, mMaxDisplacement, mTouchSlop, eventTime - mDownTime, mLongPressTimeout);
        mTracking = false;
        return result;
    }

    public void onCancel() {
        mTracking = false;
    }

    public boolean isTracking() {
        return mTracking;
    }

    /** The maximum pointer count seen in the current or last gesture, reset by {@link #onDown}. */
    public int getMaxPointerCount() {
        return mMaxPointerCount;
    }

    /** The x of the {@code ACTION_DOWN} point, used to report mouse events at the tap location. */
    public float getDownX() {
        return mDownX;
    }

    /** The y of the {@code ACTION_DOWN} point, used to report mouse events at the tap location. */
    public float getDownY() {
        return mDownY;
    }

    /** Pure classification, exposed for tests. */
    public static int classifyTap(int maxPointerCount, float maxDisplacement, float touchSlop,
                                  long duration, long longPressTimeout) {
        if (maxPointerCount < 2 || maxPointerCount > 3) return TAP_NONE;
        if (maxDisplacement >= touchSlop) return TAP_NONE;
        if (duration >= longPressTimeout) return TAP_NONE;
        return maxPointerCount;
    }
}
