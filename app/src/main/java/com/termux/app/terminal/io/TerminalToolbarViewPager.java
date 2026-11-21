package com.termux.app.terminal.io;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

public class TerminalToolbarViewPager extends ViewPager {

    /** Matches {@code ViewPager.MIN_DISTANCE_FOR_FLING} (25dp). */
    private static final float MIN_FLING_DISTANCE_DP = 25f;
    /** Matches {@code ViewPager.MIN_FLING_VELOCITY} (400dp/s). */
    private static final float MIN_FLING_VELOCITY_DP_PER_SEC = 400f;
    /** Distance required to trigger a right-swipe when released without fling velocity. */
    private static final float MIN_DRAG_DISTANCE_DP = 56f;

    private final StartEdgeRightSwipeTracker mRightSwipeTracker;
    private final int mMaxFlingVelocity;
    @Nullable
    private VelocityTracker mVelocityTracker;
    @Nullable
    private Runnable mOnSwipeRightAtStartListener;

    public TerminalToolbarViewPager(@NonNull Context context) {
        this(context, null);
    }

    public TerminalToolbarViewPager(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        ViewConfiguration vc = ViewConfiguration.get(context);
        float density = context.getResources().getDisplayMetrics().density;
        float touchSlop = vc.getScaledTouchSlop();
        mMaxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        mRightSwipeTracker = new StartEdgeRightSwipeTracker(
            touchSlop,
            MIN_FLING_DISTANCE_DP * density,
            MIN_FLING_VELOCITY_DP_PER_SEC * density,
            Math.max(touchSlop * 4f, MIN_DRAG_DISTANCE_DP * density),
            ViewConfiguration.getLongPressTimeout()
        );
    }

    /**
     * Listener invoked when the user performs a horizontal right swipe starting on page 0 (the
     * extra keys toolbar), where {@link ViewPager} otherwise hits its left boundary with no effect.
     */
    public void setOnSwipeRightAtStartListener(@Nullable Runnable listener) {
        mOnSwipeRightAtStartListener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mOnSwipeRightAtStartListener == null) {
            return super.dispatchTouchEvent(ev);
        }

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                recycleVelocityTracker();
                boolean atStart = getCurrentItem() == 0 && getScrollX() == 0;
                mRightSwipeTracker.onDown(atStart, ev.getPointerId(0), ev.getEventTime(), ev.getX(), ev.getY());
                if (atStart) {
                    mVelocityTracker = VelocityTracker.obtain();
                    mVelocityTracker.addMovement(ev);
                }
                return super.dispatchTouchEvent(ev);
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                mRightSwipeTracker.onSecondaryPointerDown();
                recycleVelocityTracker();
                return super.dispatchTouchEvent(ev);
            }

            case MotionEvent.ACTION_MOVE: {
                if (mRightSwipeTracker.isEligible()) {
                    if (mVelocityTracker != null) {
                        mVelocityTracker.addMovement(ev);
                    }
                    int idx = ev.findPointerIndex(mRightSwipeTracker.getActivePointerId());
                    if (idx >= 0) {
                        mRightSwipeTracker.onMovePosition(ev.getX(idx));
                    }
                }
                boolean handled = super.dispatchTouchEvent(ev);
                if (mRightSwipeTracker.isEligible()) {
                    mRightSwipeTracker.onPagerStateAfterMove(getCurrentItem(), getScrollX());
                    if (!mRightSwipeTracker.isEligible()) {
                        recycleVelocityTracker();
                    }
                }
                return handled;
            }

            case MotionEvent.ACTION_UP: {
                boolean triggerSwipeRight = false;
                if (mRightSwipeTracker.isEligible()) {
                    float vx = 0f;
                    if (mVelocityTracker != null) {
                        mVelocityTracker.addMovement(ev);
                        mVelocityTracker.computeCurrentVelocity(1000, mMaxFlingVelocity);
                        vx = mVelocityTracker.getXVelocity(mRightSwipeTracker.getActivePointerId());
                    }
                    int actionIdx = ev.getActionIndex();
                    triggerSwipeRight = mRightSwipeTracker.onUp(
                        ev.getPointerId(actionIdx),
                        getCurrentItem(),
                        getScrollX(),
                        ev.getX(actionIdx),
                        ev.getY(actionIdx),
                        vx
                    );
                } else {
                    mRightSwipeTracker.reset();
                }
                recycleVelocityTracker();
                boolean handled = super.dispatchTouchEvent(ev);
                if (triggerSwipeRight && mOnSwipeRightAtStartListener != null) {
                    mOnSwipeRightAtStartListener.run();
                }
                return handled;
            }

            case MotionEvent.ACTION_CANCEL: {
                mRightSwipeTracker.reset();
                recycleVelocityTracker();
                return super.dispatchTouchEvent(ev);
            }

            default:
                return super.dispatchTouchEvent(ev);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        boolean intercepted = super.onInterceptTouchEvent(ev);
        if (intercepted && mRightSwipeTracker.isEligible()) {
            mRightSwipeTracker.onPagerIntercepted(ev.getEventTime());
            if (!mRightSwipeTracker.isEligible()) {
                recycleVelocityTracker();
            }
        }
        return intercepted;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_MOVE &&
            mRightSwipeTracker.isEligible() && !mRightSwipeTracker.isCaptured()) {
            int idx = ev.findPointerIndex(mRightSwipeTracker.getActivePointerId());
            if (idx >= 0) {
                mRightSwipeTracker.onDirectTouchMove(ev.getEventTime(), ev.getX(idx), ev.getY(idx));
                if (!mRightSwipeTracker.isEligible()) {
                    recycleVelocityTracker();
                }
            }
        }
        return super.onTouchEvent(ev);
    }

    @Override
    protected void onDetachedFromWindow() {
        mRightSwipeTracker.reset();
        recycleVelocityTracker();
        super.onDetachedFromWindow();
    }

    private void recycleVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    /**
     * Pure-Java state machine that detects a single-finger horizontal right swipe starting from
     * page 0 of the toolbar without interfering with extra-key taps, swipe-up popups, long-press
     * key repeats/locks, page-1-to-page-0 back swipes, or cancelled system edge gestures.
     */
    static final class StartEdgeRightSwipeTracker {

        private static final int INVALID_POINTER = -1;

        private final float mTouchSlop;
        private final float mMinFlingDistance;
        private final float mMinFlingVelocity;
        private final float mMinDragDistance;
        private final long mLongPressTimeoutMs;

        private boolean mEligible;
        private boolean mCaptured;
        private int mActivePointerId = INVALID_POINTER;
        private long mDownTimeMs;
        private float mDownX;
        private float mDownY;

        StartEdgeRightSwipeTracker(float touchSlop, float minFlingDistance, float minFlingVelocity,
                                   float minDragDistance, long longPressTimeoutMs) {
            mTouchSlop = touchSlop;
            mMinFlingDistance = minFlingDistance;
            mMinFlingVelocity = minFlingVelocity;
            mMinDragDistance = minDragDistance;
            mLongPressTimeoutMs = longPressTimeoutMs;
        }

        void onDown(boolean atStartPage, int pointerId, long eventTimeMs, float x, float y) {
            mEligible = atStartPage;
            mCaptured = false;
            mActivePointerId = atStartPage ? pointerId : INVALID_POINTER;
            mDownTimeMs = eventTimeMs;
            mDownX = x;
            mDownY = y;
        }

        void onSecondaryPointerDown() {
            reset();
        }

        void onMovePosition(float x) {
            if (mEligible && x - mDownX < -mTouchSlop) {
                // Dragged left towards the text-input page; even if the user later drags back
                // right to cancel, do not treat that cancellation as a session switch.
                reset();
            }
        }

        void onPagerIntercepted(long eventTimeMs) {
            if (!mEligible || mCaptured) return;
            if (eventTimeMs - mDownTimeMs >= mLongPressTimeoutMs) {
                // Finger stayed on a button long enough to trigger auto-repeat or modifier lock.
                reset();
                return;
            }
            mCaptured = true;
        }

        /** Handles horizontal drag start when no child button consumed {@code ACTION_DOWN}. */
        void onDirectTouchMove(long eventTimeMs, float x, float y) {
            if (!mEligible || mCaptured) return;
            float absDx = Math.abs(x - mDownX);
            float absDy = Math.abs(y - mDownY);
            if (absDx > mTouchSlop && absDx * 0.5f > absDy) {
                onPagerIntercepted(eventTimeMs);
            } else if (absDy > mTouchSlop) {
                reset();
            }
        }

        void onPagerStateAfterMove(int currentItem, int scrollX) {
            if (mEligible && (currentItem != 0 || scrollX > 0)) {
                reset();
            }
        }

        boolean onUp(int pointerId, int currentItem, int scrollX, float x, float y, float velocityX) {
            boolean canCheck = mEligible && mCaptured &&
                pointerId == mActivePointerId && currentItem == 0 && scrollX == 0;
            float downX = mDownX;
            float downY = mDownY;
            reset();
            if (!canCheck) return false;

            float dx = x - downX;
            float dy = y - downY;
            if (dx <= 0f || dx <= Math.abs(dy) * 1.5f) return false;

            boolean isFling = dx >= mMinFlingDistance && velocityX >= mMinFlingVelocity;
            boolean isDrag = dx >= mMinDragDistance;
            return isFling || isDrag;
        }

        void reset() {
            mEligible = false;
            mCaptured = false;
            mActivePointerId = INVALID_POINTER;
        }

        boolean isEligible() {
            return mEligible;
        }

        boolean isCaptured() {
            return mCaptured;
        }

        int getActivePointerId() {
            return mActivePointerId;
        }
    }

    public static class PageAdapter extends PagerAdapter {

        final TermuxActivity mActivity;
        String mSavedTextInput;

        public PageAdapter(TermuxActivity activity, String savedTextInput) {
            this.mActivity = activity;
            this.mSavedTextInput = savedTextInput;
        }

        @Override
        public int getCount() {
            return 2;
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup collection, int position) {
            LayoutInflater inflater = LayoutInflater.from(mActivity);
            View layout;
            if (position == 0) {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_extra_keys, collection, false);
                ExtraKeysView extraKeysView = (ExtraKeysView) layout;
                extraKeysView.setExtraKeysViewClient(mActivity.getTermuxTerminalExtraKeys());
                extraKeysView.setButtonTextAllCaps(mActivity.getProperties().shouldExtraKeysTextBeAllCaps());
                mActivity.setExtraKeysView(extraKeysView);
                if (mActivity.getTerminalToolbarTextColor() != 0)
                    extraKeysView.setButtonTextColor(mActivity.getTerminalToolbarTextColor());
                extraKeysView.reload(mActivity.getTermuxTerminalExtraKeys().getExtraKeysInfo(),
                    mActivity.getTerminalToolbarDefaultHeight());

                // apply extra keys fix if enabled in prefs
                if (mActivity.getProperties().isUsingFullScreen() && mActivity.getProperties().isUsingFullScreenWorkAround()) {
                    FullScreenWorkAround.apply(mActivity);
                }

            } else {
                layout = inflater.inflate(R.layout.view_terminal_toolbar_text_input, collection, false);
                final EditText editText = layout.findViewById(R.id.terminal_toolbar_text_input);
                if (mActivity.getTerminalToolbarTextColor() != 0)
                    editText.setTextColor(mActivity.getTerminalToolbarTextColor());

                if (mSavedTextInput != null) {
                    editText.setText(mSavedTextInput);
                    mSavedTextInput = null;
                }

                editText.setOnEditorActionListener((v, actionId, event) -> {
                    TerminalSession session = mActivity.getCurrentSession();
                    if (session != null) {
                        if (session.isRunning()) {
                            String textToSend = editText.getText().toString();
                            if (textToSend.length() == 0) {
                                // The empty input is the enter key.
                                session.write("\r");
                            } else {
                                // Send the text as a paste like the other paste paths do, so that
                                // programs that enabled bracketed paste mode receive it in one go.
                                TerminalEmulator emulator = session.getEmulator();
                                if (emulator != null) {
                                    emulator.paste(textToSend);
                                } else {
                                    session.write(textToSend);
                                }
                            }
                        } else {
                            mActivity.getTermuxTerminalSessionClient().removeFinishedSession(session);
                        }
                        editText.setText("");
                    }
                    return true;
                });
            }
            collection.addView(layout);
            return layout;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup collection, int position, @NonNull Object view) {
            collection.removeView((View) view);
        }

    }

    public static class OnPageChangeListener extends ViewPager.SimpleOnPageChangeListener {

        final TermuxActivity mActivity;
        final ViewPager mTerminalToolbarViewPager;

        public OnPageChangeListener(TermuxActivity activity, ViewPager viewPager) {
            this.mActivity = activity;
            this.mTerminalToolbarViewPager = viewPager;
        }

        @Override
        public void onPageSelected(int position) {
            if (position == 0) {
                mActivity.getTerminalView().requestFocus();
            } else {
                // The flags that disable the soft keyboard may be set for the terminal view, but the
                // text input view can only be typed into if the window is IME focusable.
                KeyboardUtils.clearDisableSoftKeyboardFlags(mActivity);
                final EditText editText = mTerminalToolbarViewPager.findViewById(R.id.terminal_toolbar_text_input);
                if (editText != null) editText.requestFocus();
            }
        }

    }

}
