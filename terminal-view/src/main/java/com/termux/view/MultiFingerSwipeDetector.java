package com.termux.view;

/**
 * Detects a horizontal swipe performed with an exact number of fingers, used for switching
 * sessions. The input is primitive (pointer count, centroid, delta) so the state machine can be
 * unit tested on the JVM.
 *
 * <p>The baseline is captured when the required number of fingers is down, not on the first move,
 * so that a fast swipe that only produces a single move event is still recognised.
 */
public final class MultiFingerSwipeDetector {

    public static final int SWIPE_NONE = 0;
    /** Swipe towards the left edge, i.e. towards the previous session. */
    public static final int SWIPE_LEFT = -1;
    /** Swipe towards the right edge, i.e. towards the next session. */
    public static final int SWIPE_RIGHT = 1;

    private final int mPointerCount;
    private final float mMinHorizontal;
    private final float mMaxVertical;

    private boolean mHasBaseline;
    private boolean mFired;
    private float mBaseX, mBaseY;

    public MultiFingerSwipeDetector(int pointerCount, float minHorizontal, float maxVertical) {
        mPointerCount = pointerCount;
        mMinHorizontal = minHorizontal;
        mMaxVertical = maxVertical;
    }

    /** Start a new gesture, from {@code ACTION_DOWN}. */
    public void onDown() {
        mHasBaseline = false;
        mFired = false;
    }

    /**
     * Record the centroid when a pointer goes down. The baseline is captured once the required
     * number of fingers is down, so the first move already has a reference to measure against.
     */
    public void onPointerCount(int pointerCount, float centroidX, float centroidY) {
        if (pointerCount == mPointerCount) {
            mBaseX = centroidX;
            mBaseY = centroidY;
            mHasBaseline = true;
            mFired = false;
        } else if (pointerCount > mPointerCount) {
            mHasBaseline = false;
        }
    }

    /**
     * Feed the current centroid of all pointers, from {@code ACTION_MOVE}.
     *
     * @return {@link #SWIPE_LEFT} or {@link #SWIPE_RIGHT} exactly once when the swipe is recognised,
     *         otherwise {@link #SWIPE_NONE}.
     */
    public int onMove(int pointerCount, float centroidX, float centroidY) {
        if (pointerCount != mPointerCount || !mHasBaseline || mFired) return SWIPE_NONE;

        int swipe = classify(pointerCount, mPointerCount, centroidX - mBaseX, centroidY - mBaseY,
            mMinHorizontal, mMaxVertical);
        if (swipe != SWIPE_NONE) mFired = true;
        return swipe;
    }

    public void onCancel() {
        mHasBaseline = false;
        mFired = false;
    }

    /** Pure classification, exposed for tests. */
    public static int classify(int pointerCount, int requiredPointers, float dx, float dy,
                               float minHorizontal, float maxVertical) {
        if (pointerCount != requiredPointers) return SWIPE_NONE;
        if (Math.abs(dx) < minHorizontal) return SWIPE_NONE;
        if (Math.abs(dy) > maxVertical) return SWIPE_NONE;
        return dx < 0 ? SWIPE_LEFT : SWIPE_RIGHT;
    }
}
