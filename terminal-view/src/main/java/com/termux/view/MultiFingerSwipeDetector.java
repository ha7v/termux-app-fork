package com.termux.view;

/**
 * Detects a horizontal swipe performed with an exact number of fingers (two fingers for switching
 * sessions) while arbitrating against pinch-to-zoom on the same finger count.
 *
 * <p>When {@code requiredPointers == 2}, both the initial centroid and the initial inter-finger
 * span are captured as soon as the second finger goes down. Whichever threshold is crossed first
 * locks the gesture intent for the remainder of the touch:
 * <ul>
 *   <li>If the relative span change {@code |span - baseSpan| / baseSpan} reaches
 *       {@code scaleSpanRatioThreshold} (e.g. {@code 0.23f}, ±23%), the gesture locks to
 *       <b>scale</b> and suppresses horizontal session swipes.</li>
 *   <li>If the centroid translates horizontally past {@code minHorizontal} while staying within
 *       {@code maxVertical} and keeping span drift within {@code scaleSpanRatioThreshold}, the
 *       gesture locks to <b>swipe</b>, emits {@link #SWIPE_LEFT} or {@link #SWIPE_RIGHT} once, and
 *       suppresses pinch scaling.</li>
 * </ul>
 *
 * <p>All inputs are primitives so the state machine can be unit tested directly on the JVM.
 */
public final class MultiFingerSwipeDetector {

    public static final int SWIPE_NONE = 0;
    /** Swipe towards the left edge, i.e. towards the previous session. */
    public static final int SWIPE_LEFT = -1;
    /** Swipe towards the right edge, i.e. towards the next session. */
    public static final int SWIPE_RIGHT = 1;

    /** Default relative span change (±23%) required before locking a two-finger gesture to zoom. */
    public static final float DEFAULT_SCALE_SPAN_RATIO_THRESHOLD = 0.23f;

    private static final int INTENT_UNDECIDED = 0;
    private static final int INTENT_SWIPE = 1;
    private static final int INTENT_SCALE = 2;
    private static final int INTENT_CANCELLED = 3;

    private final int mPointerCount;
    private final float mMinHorizontal;
    private final float mMaxVertical;
    private final float mScaleSpanRatioThreshold;

    private boolean mHasBaseline;
    private int mIntent = INTENT_UNDECIDED;
    private float mBaseX, mBaseY, mBaseSpan;

    public MultiFingerSwipeDetector(int pointerCount, float minHorizontal, float maxVertical) {
        this(pointerCount, minHorizontal, maxVertical, DEFAULT_SCALE_SPAN_RATIO_THRESHOLD);
    }

    public MultiFingerSwipeDetector(int pointerCount, float minHorizontal, float maxVertical,
                                    float scaleSpanRatioThreshold) {
        mPointerCount = pointerCount;
        mMinHorizontal = minHorizontal;
        mMaxVertical = maxVertical;
        mScaleSpanRatioThreshold = scaleSpanRatioThreshold;
    }

    /** Start a new gesture, from {@code ACTION_DOWN}. */
    public void onDown() {
        mHasBaseline = false;
        mIntent = INTENT_UNDECIDED;
        mBaseSpan = 0f;
    }

    /**
     * Record the centroid and finger span when a pointer goes down. The baseline is captured once
     * the required number of fingers is down, so even a fast single-move swipe has a reference.
     */
    public void onPointerCount(int pointerCount, float centroidX, float centroidY, float span) {
        if (pointerCount == mPointerCount && mIntent == INTENT_UNDECIDED) {
            mBaseX = centroidX;
            mBaseY = centroidY;
            mBaseSpan = span;
            mHasBaseline = true;
        } else if (pointerCount > mPointerCount) {
            mHasBaseline = false;
            mIntent = INTENT_CANCELLED;
        }
    }

    /**
     * Feed the current centroid and span of the active pointers, from {@code ACTION_MOVE}.
     *
     * @return {@link #SWIPE_LEFT} or {@link #SWIPE_RIGHT} exactly once when a horizontal swipe wins
     *         arbitration, otherwise {@link #SWIPE_NONE}.
     */
    public int onMove(int pointerCount, float centroidX, float centroidY, float span) {
        if (pointerCount != mPointerCount || !mHasBaseline || mIntent != INTENT_UNDECIDED) {
            return SWIPE_NONE;
        }

        if (mBaseSpan > 1f && mScaleSpanRatioThreshold > 0f) {
            float spanRatioDelta = Math.abs(span - mBaseSpan) / mBaseSpan;
            if (spanRatioDelta >= mScaleSpanRatioThreshold) {
                mIntent = INTENT_SCALE;
                return SWIPE_NONE;
            }
        }

        int swipe = classify(pointerCount, mPointerCount, centroidX - mBaseX, centroidY - mBaseY,
            mMinHorizontal, mMaxVertical);
        if (swipe != SWIPE_NONE) {
            mIntent = INTENT_SWIPE;
        }
        return swipe;
    }

    public void onCancel() {
        mHasBaseline = false;
        mIntent = INTENT_CANCELLED;
    }

    /** True once the two-finger gesture crossed the span threshold and locked to pinch-zoom. */
    public boolean isScaleLocked() {
        return mIntent == INTENT_SCALE;
    }

    /** True once the gesture locked to a horizontal swipe, suppressing pinch-zoom. */
    public boolean isSwipeLocked() {
        return mIntent == INTENT_SWIPE;
    }

    /** Pure classification, exposed for tests. */
    public static int classify(int pointerCount, int requiredPointers, float dx, float dy,
                               float minHorizontal, float maxVertical) {
        if (pointerCount != requiredPointers) return SWIPE_NONE;
        float absDx = Math.abs(dx);
        float absDy = Math.abs(dy);
        if (absDx < minHorizontal) return SWIPE_NONE;
        if (absDy > maxVertical) return SWIPE_NONE;
        if (absDx <= absDy * 1.5f) return SWIPE_NONE;
        return dx < 0 ? SWIPE_LEFT : SWIPE_RIGHT;
    }
}
