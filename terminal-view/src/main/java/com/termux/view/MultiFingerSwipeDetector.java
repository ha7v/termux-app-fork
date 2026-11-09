package com.termux.view;

/**
 * Detects a horizontal swipe performed with an exact number of fingers, used for switching
 * sessions. The input is primitive (pointer count, centroid, delta) so the state machine can be
 * unit tested on the JVM.
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

    private boolean mTracking;
    private boolean mFired;
    private float mBaseX, mBaseY;

    public MultiFingerSwipeDetector(int pointerCount, float minHorizontal, float maxVertical) {
        mPointerCount = pointerCount;
        mMinHorizontal = minHorizontal;
        mMaxVertical = maxVertical;
    }

    /** Start a new gesture, from {@code ACTION_DOWN}. */
    public void onDown() {
        mTracking = false;
        mFired = false;
    }

    /**
     * Feed the current centroid of all pointers, from {@code ACTION_MOVE}.
     *
     * @return {@link #SWIPE_LEFT} or {@link #SWIPE_RIGHT} exactly once when the swipe is recognised,
     *         otherwise {@link #SWIPE_NONE}.
     */
    public int onMove(int pointerCount, float centroidX, float centroidY) {
        if (pointerCount != mPointerCount) {
            mTracking = false;
            return SWIPE_NONE;
        }
        if (!mTracking) {
            // The baseline is the centroid when the required number of fingers first moves together.
            mTracking = true;
            mFired = false;
            mBaseX = centroidX;
            mBaseY = centroidY;
            return SWIPE_NONE;
        }
        if (mFired) return SWIPE_NONE;

        int swipe = classify(pointerCount, mPointerCount, centroidX - mBaseX, centroidY - mBaseY,
            mMinHorizontal, mMaxVertical);
        if (swipe != SWIPE_NONE) mFired = true;
        return swipe;
    }

    public void onCancel() {
        mTracking = false;
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
