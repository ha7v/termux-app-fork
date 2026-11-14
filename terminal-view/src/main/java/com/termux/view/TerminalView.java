package com.termux.view;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.ActionMode;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Scroller;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.KeyboardProtocol;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.view.textselection.TextSelectionCursorController;

/** View displaying and interacting with a {@link TerminalSession}. */
public final class TerminalView extends View {

    /** Log terminal view key and IME events. */
    private static boolean TERMINAL_VIEW_KEY_LOGGING_ENABLED = false;

    /** The currently displayed terminal session, whose emulator is {@link #mEmulator}. */
    public TerminalSession mTermSession;
    /** Our terminal emulator whose session is {@link #mTermSession}. */
    public TerminalEmulator mEmulator;

    public TerminalRenderer mRenderer;

    public TerminalViewClient mClient;

    private TextSelectionCursorController mTextSelectionCursorController;

    private Handler mTerminalCursorBlinkerHandler;
    private TerminalCursorBlinkerRunnable mTerminalCursorBlinkerRunnable;
    private int mTerminalCursorBlinkerRate;
    private boolean mCursorInvisibleIgnoreOnce;
    public static final int TERMINAL_CURSOR_BLINK_RATE_MIN = 100;
    public static final int TERMINAL_CURSOR_BLINK_RATE_MAX = 2000;

    /** The top row of text to display. Ranges from -activeTranscriptRows to 0. */
    int mTopRow;
    int[] mDefaultSelectors = new int[]{-1,-1,-1,-1};

    float mScaleFactor = 1.f;
    final GestureAndScaleRecognizer mGestureRecognizer;

    /** Detects two/three-finger taps, which the platform GestureDetector does not report. */
    final MultiFingerTapDetector mMultiFingerTapDetector;

    /** Keep track of where mouse touch event started which we report as mouse scroll. */
    private int mMouseScrollStartX = -1, mMouseScrollStartY = -1;
    /** Keep track of the time when a touch event leading to sending mouse scroll events started. */
    private long mMouseStartDownTime = -1;

    final Scroller mScroller;

    /** What was left in from scrolling movement. */
    float mScrollRemainder;

    /** Whether the current gesture triggered a pinch, so it must not also swipe or count as a tap. */
    boolean mGestureWasScale;

    /** Whether a left button press was forwarded to a mouse-aware program and not released yet. */
    boolean mForwardedButtonDown;
    /** Last forwarded pointer position, used to release the button on cancel/detach. */
    float mForwardedX, mForwardedY;

    /** Whether the current gesture was a two-finger swipe, so it must not scale or count as a tap. */
    boolean mGestureWasSwipe;

    /** Detects a two-finger horizontal swipe for switching sessions (with pinch-zoom arbitration). */
    final MultiFingerSwipeDetector mMultiFingerSwipeDetector;

    /** Single-finger two-stage hold detector for selection and mouse right-click / drag. */
    final SingleFingerHoldDetector mHoldDetector;
    private static final long HOLD_STAGE_1_TIMEOUT_MS = 450L;
    private static final long HOLD_STAGE_2_TIMEOUT_MS = 1200L;
    private static final long DRAG_AUTO_SCROLL_INTERVAL_MS = 60L;

    /** Whether {@code onUp} already consumed a tap (e.g. Ctrl+Tap on URL or mouse left click). */
    boolean mConsumedTapInOnUp;

    /** Tracks pending primary-button clicks on an external mouse. */
    private boolean mMouseModifiedTapPending;
    private boolean mMousePrimaryTapPending;
    private float mMouseDownX, mMouseDownY;
    private int mLastHoverCol = -1, mLastHoverRow = -1;
    private int mLastDragCol = -1, mLastDragRow = -1;
    private final float mTouchSlop;

    private final Runnable mHoldStage1Runnable = this::onHoldStage1Timeout;
    private final Runnable mHoldStage2Runnable = this::onHoldStage2Timeout;
    private final Runnable mDragAutoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mHoldDetector.hasDraggedSelectionBeyondSlop() || mEmulator == null || mEmulator.isAlternateBufferActive()) {
                return;
            }
            int dir = computeDragAutoScrollDirection(mHoldDetector.getLastY());
            if (dir == 0) return;

            int minTopRow = -mEmulator.getScreen().getActiveTranscriptRows();
            int nextTopRow = Math.min(0, Math.max(minTopRow, mTopRow + dir));
            if (nextTopRow != mTopRow) {
                setTopRow(nextTopRow);
                updateHoldDragSelection(mHoldDetector.getLastX(), mHoldDetector.getLastY());
                if (!awakenScrollBars()) invalidate();
            }
            if ((dir < 0 && mTopRow > minTopRow) || (dir > 0 && mTopRow < 0)) {
                postDelayed(this, DRAG_AUTO_SCROLL_INTERVAL_MS);
            }
        }
    };

    /** If non-zero, this is the last unicode code point received if that was a combining character. */
    int mCombiningAccent;

    /**
     * The currently active {@link BaseInputConnection} created in
     * {@link #onCreateInputConnection(EditorInfo)}, or {@code null} if none has been created yet.
     * Used to reach the local {@link Editable} that holds in-progress IME composing text, which
     * must be committed before input that bypasses the IME is written to the terminal.
     */
    private BaseInputConnection mInputConnection;

    /**
     * True while {@link InputConnection#finishComposingText()} is flushing already-typed composing
     * text so latched ExtraKeys modifiers ({@code CTRL}/{@code ALT}/{@code SHIFT}) are preserved
     * for the subsequent key event (e.g. {@code ExtraKeys SHIFT + Enter}) instead of being consumed
     * by or applied to the previously composed word.
     */
    private boolean mIsFinishingComposingText;

    /**
     * The cursor update mode last requested by the IME via
     * {@link InputConnection#requestCursorUpdates(int)}. A non-zero
     * {@link InputConnection#CURSOR_UPDATE_MONITOR} means that the cursor anchor info must be kept
     * up to date as the terminal content changes.
     */
    private int mCursorUpdateMode;

    /** Whether a cursor anchor info update has already been posted for the next frame. */
    private boolean mCursorAnchorInfoUpdatePending;

    /**
     * The current AutoFill type returned for {@link View#getAutofillType()} by {@link #getAutofillType()}.
     *
     * The default is {@link #AUTOFILL_TYPE_NONE} so that AutoFill UI, like toolbar above keyboard
     * is not shown automatically, like on Activity starts/View create. This value should be updated
     * to required value, like {@link #AUTOFILL_TYPE_TEXT} before calling
     * {@link AutofillManager#requestAutofill(View)} so that AutoFill UI shows. The updated value
     * set will automatically be restored to {@link #AUTOFILL_TYPE_NONE} in
     * {@link #autofill(AutofillValue)} so that AutoFill UI isn't shown anymore by calling
     * {@link #resetAutoFill()}.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private int mAutoFillType = AUTOFILL_TYPE_NONE;

    /**
     * The current AutoFill type returned for {@link View#getImportantForAutofill()} by
     * {@link #getImportantForAutofill()}.
     *
     * The default is {@link #IMPORTANT_FOR_AUTOFILL_NO} so that view is not considered important
     * for AutoFill. This value should be updated to required value, like
     * {@link #IMPORTANT_FOR_AUTOFILL_YES} before calling {@link AutofillManager#requestAutofill(View)}
     * so that Android and apps consider the view as important for AutoFill to process the request.
     * The updated value set will automatically be restored to {@link #IMPORTANT_FOR_AUTOFILL_NO} in
     * {@link #autofill(AutofillValue)} by calling {@link #resetAutoFill()}.
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    private int mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_NO;

    /**
     * The current AutoFill hints returned for {@link View#getAutofillHints()} ()} by {@link #getAutofillHints()} ()}.
     *
     * The default is an empty `string[]`. This value should be updated to required value. The
     * updated value set will automatically be restored an empty `string[]` in
     * {@link #autofill(AutofillValue)} by calling {@link #resetAutoFill()}.
     */
    private String[] mAutoFillHints = new String[0];

    private final boolean mAccessibilityEnabled;

    /** The {@link KeyEvent} is generated from a virtual keyboard, like manually with the {@link KeyEvent#KeyEvent(int, int)} constructor. */
    public final static int KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD = KeyCharacterMap.VIRTUAL_KEYBOARD; // -1

    /** The {@link KeyEvent} is generated from a non-physical device, like if 0 value is returned by {@link KeyEvent#getDeviceId()}. */
    public final static int KEY_EVENT_SOURCE_SOFT_KEYBOARD = 0;

    private static final String LOG_TAG = "TerminalView";

    public TerminalView(Context context, AttributeSet attributes) { // NO_UCD (unused code)
        super(context, attributes);
        ViewConfiguration viewConfiguration = ViewConfiguration.get(context);
        final int touchSlop = viewConfiguration.getScaledTouchSlop();
        mTouchSlop = touchSlop;
        // A multi-finger tap is forgiving: a generous slop and duration so a natural two-finger tap
        // is not rejected for a little movement or a slightly slow lift.
        mMultiFingerTapDetector = new MultiFingerTapDetector(touchSlop * 4, 1500);
        mMultiFingerSwipeDetector = new MultiFingerSwipeDetector(2, touchSlop * 3, touchSlop * 4);
        mHoldDetector = new SingleFingerHoldDetector(touchSlop, touchSlop * 1.25f);
        mGestureRecognizer = new GestureAndScaleRecognizer(context, new GestureAndScaleRecognizer.Listener() {

            boolean scrolledWithFinger;

            @Override
            public boolean onUp(MotionEvent event) {
                mScrollRemainder = 0.0f;
                if (mHoldDetector.consumedCurrentGesture()) {
                    scrolledWithFinger = false;
                    return true;
                }
                // A two/three-finger gesture is a tap (handled by the detector) or a swipe/pinch,
                // never a single-finger click.
                if (mEmulator != null && !event.isFromSource(InputDevice.SOURCE_MOUSE) && !isSelectingText() && !scrolledWithFinger && mMultiFingerTapDetector.getMaxPointerCount() < 2) {
                    if (mClient.onModifiedSingleTap(event)) {
                        mConsumedTapInOnUp = true;
                        return true;
                    }
                    if (mEmulator.isMouseTrackingActive()) {
                        // Quick event processing when mouse tracking is active - do not wait for
                        // check of double tapping for zooming.
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, true);
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, false);
                        mConsumedTapInOnUp = true;
                        return true;
                    }
                }
                scrolledWithFinger = false;
                return false;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                if (mEmulator == null) return true;
                if (mHoldDetector.consumedCurrentGesture() || mMultiFingerTapDetector.getMaxPointerCount() >= 2) {
                    return true;
                }
                if (mConsumedTapInOnUp) {
                    mConsumedTapInOnUp = false;
                    return true;
                }

                if (isSelectingText()) {
                    stopTextSelectionMode();
                    return true;
                }
                if (mClient.onModifiedSingleTap(event)) {
                    return true;
                }
                requestFocus();
                mClient.onSingleTapUp(event);
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e, float distanceX, float distanceY) {
                if (mEmulator == null) return true;
                if (mHoldDetector.consumedCurrentGesture()) return true;
                if (e.getPointerCount() == 1 && mMultiFingerTapDetector.getMaxPointerCount() < 2) {
                    // Immediate single-finger drag (< 0.45s) scrolls, in every mode.
                    scrolledWithFinger = true;
                    distanceY += mScrollRemainder;
                    int deltaRows = (int) (distanceY / mRenderer.mFontLineSpacing);
                    mScrollRemainder = distanceY - deltaRows * mRenderer.mFontLineSpacing;
                    doScroll(e, deltaRows);
                }
                return true;
            }

            @Override
            public boolean onScale(float focusX, float focusY, float scale) {
                if (mEmulator == null || isSelectingText()) return true;
                if (mMultiFingerSwipeDetector.isSwipeLocked() || mMultiFingerTapDetector.getMaxPointerCount() > 2) {
                    return true;
                }
                mScaleFactor *= scale;
                if (mMultiFingerSwipeDetector.isScaleLocked()) {
                    mGestureWasScale = true;
                    mScaleFactor = mClient.onScale(mScaleFactor);
                }
                return true;
            }

            @Override
            public boolean onFling(final MotionEvent e2, float velocityX, float velocityY) {
                if (mEmulator == null) return true;
                // A hold gesture or multi-finger gesture must not fling the view.
                if (mHoldDetector.consumedCurrentGesture() || mMultiFingerTapDetector.getMaxPointerCount() >= 2) return true;
                // Do not start scrolling until last fling has been taken care of:
                if (!mScroller.isFinished()) return true;

                final boolean mouseTrackingAtStartOfFling = mEmulator.isMouseTrackingActive();
                float SCALE = 0.25f;
                if (mouseTrackingAtStartOfFling) {
                    mScroller.fling(0, 0, 0, -(int) (velocityY * SCALE), 0, 0, -mEmulator.mRows / 2, mEmulator.mRows / 2);
                } else {
                    mScroller.fling(0, mTopRow, 0, -(int) (velocityY * SCALE), 0, 0, -mEmulator.getScreen().getActiveTranscriptRows(), 0);
                }

                post(new Runnable() {
                    private int mLastY = 0;

                    @Override
                    public void run() {
                        if (mouseTrackingAtStartOfFling != mEmulator.isMouseTrackingActive()) {
                            mScroller.abortAnimation();
                            return;
                        }
                        if (mScroller.isFinished()) return;
                        boolean more = mScroller.computeScrollOffset();
                        int newY = mScroller.getCurrY();
                        int diff = mouseTrackingAtStartOfFling ? (newY - mLastY) : (newY - mTopRow);
                        doScroll(e2, diff);
                        mLastY = newY;
                        if (more) post(this);
                    }
                });

                return true;
            }

            @Override
            public boolean onDown(float x, float y) {
                // Reset the accumulated pinch factor for each new gesture, otherwise small drifts
                // across gestures add up and trigger an unwanted zoom.
                mScaleFactor = 1.f;
                mGestureWasScale = false;
                scrolledWithFinger = false;
                mConsumedTapInOnUp = false;
                // Why is true not returned here?
                // https://developer.android.com/training/gestures/detector.html#detect-a-subset-of-supported-gestures
                // Although setting this to true still does not solve the following errors when long pressing in terminal view text area
                // ViewDragHelper: Ignoring pointerId=0 because ACTION_DOWN was not received for this pointer before ACTION_MOVE
                // Commenting out the call to mGestureDetector.onTouchEvent(event) in GestureAndScaleRecognizer#onTouchEvent() removes
                // the error logging, so issue is related to GestureDetector
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                // Do not treat is as a single confirmed tap - it may be followed by zoom.
                return false;
            }

            @Override
            public void onLongPress(MotionEvent event) {
                // Handled by SingleFingerHoldDetector.
            }
        });
        mScroller = new Scroller(context);
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        mAccessibilityEnabled = am.isEnabled();
    }



    /**
     * @param client The {@link TerminalViewClient} interface implementation to allow
     *                           for communication between {@link TerminalView} and its client.
     */
    public void setTerminalViewClient(TerminalViewClient client) {
        this.mClient = client;
    }

    /**
     * Sets whether terminal view key logging is enabled or not.
     *
     * @param value The boolean value that defines the state.
     */
    public void setIsTerminalViewKeyLoggingEnabled(boolean value) {
        TERMINAL_VIEW_KEY_LOGGING_ENABLED = value;
    }



    /**
     * Attach a {@link TerminalSession} to this view.
     *
     * @param session The {@link TerminalSession} this view will be displaying.
     */
    public boolean attachSession(TerminalSession session) {
        if (session == mTermSession) return false;

        cancelSingleFingerHold(true);
        stopTextSelectionMode();
        if (mEmulator != null) {
            mEmulator.reportFocusChange(false);
        }
        resetMouseMotionState();

        mTermSession = session;
        mEmulator = null;
        mCombiningAccent = 0;

        // The emulator's cached value will be read in `updateSize()` when emulator is set.
        setTopRow(0, false);

        updateSize();

        // Wait with enabling the scrollbar until we have a terminal to get scroll position from.
        setVerticalScrollBarEnabled(true);

        return true;
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        // The IME has to request cursor updates again for the new connection, like TextView does.
        mCursorUpdateMode = 0;

        // Ensure that inputType is only set if TerminalView is selected view with the keyboard and
        // an alternate view is not selected, like an EditText. This is necessary if an activity is
        // initially started with the alternate view or if activity is returned to from another app
        // and the alternate view was the one selected the last time.
        if (mClient.isTerminalViewSelected()) {
            final int keyboardMode = mClient.getKeyboardMode();
            if (keyboardMode == TerminalViewClient.KEYBOARD_MODE_IME) {
                // Report a real text field so that IMEs that need one, like CJK IMEs for candidate
                // selection, provide full composition and candidate UI. The composing text is kept
                // in the local editable of the input connection below and is only sent to the
                // terminal once it is committed, so no partial text reaches the terminal.
                // NO_SUGGESTIONS keeps the IME from showing its autocorrect/suggestion strip, which
                // does not apply to terminal input, but leaves composing and the CJK candidate bar
                // alone. A visible password field would suppress suggestions too, but it disables
                // composing as well, which breaks CJK input.
                outAttrs.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            } else if (keyboardMode == TerminalViewClient.KEYBOARD_MODE_SECURE) {
                // Some keyboards seems do not reset the internal state on TYPE_NULL.
                // Affects mostly Samsung stock keyboards.
                // https://github.com/termux/termux-app/issues/686
                // However, this is not a valid value as per AOSP since `InputType.TYPE_CLASS_*` is
                // not set and it logs a warning:
                // W/InputAttributes: Unexpected input class: inputType=0x00080090 imeOptions=0x02000000
                // https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:packages/inputmethods/LatinIME/java/src/com/android/inputmethod/latin/InputAttributes.java;l=79
                outAttrs.inputType = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
            } else {
                // Using InputType.NULL is the most correct input type and avoids issues with other hacks.
                //
                // Previous keyboard issues:
                // https://github.com/termux/termux-packages/issues/25
                // https://github.com/termux/termux-app/issues/87.
                // https://github.com/termux/termux-app/issues/126.
                // https://github.com/termux/termux-app/issues/137 (japanese chars and TYPE_NULL).
                outAttrs.inputType = InputType.TYPE_NULL;
            }
        } else {
            // Corresponds to android:inputType="text"
            outAttrs.inputType =  InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
        }

        // Note that IME_ACTION_NONE cannot be used as that makes it impossible to input newlines using the on-screen
        // keyboard on Android TV (see https://github.com/termux/termux-app/issues/221).
        // IME_FLAG_NO_EXTRACT_UI stops the IME from showing its full-screen/half-screen extracted text overlay over
        // the terminal in landscape or large screen mode, where the terminal itself is already big enough.
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_FLAG_NO_EXTRACT_UI;

        mInputConnection = new BaseInputConnection(this, true) {

            @Override
            public boolean requestCursorUpdates(int cursorUpdateMode) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient.logInfo(LOG_TAG, "IME: requestCursorUpdates(" + cursorUpdateMode + ")");
                }
                mCursorUpdateMode = cursorUpdateMode;
                if ((cursorUpdateMode & InputConnection.CURSOR_UPDATE_IMMEDIATE) != 0)
                    updateCursorAnchorInfo();
                return true;
            }

            @Override
            public boolean finishComposingText() {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient.logInfo(LOG_TAG, "IME: finishComposingText()");
                super.finishComposingText();

                mIsFinishingComposingText = true;
                try {
                    sendTextToTerminal(getEditable());
                } finally {
                    mIsFinishingComposingText = false;
                }
                getEditable().clear();
                return true;
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient.logInfo(LOG_TAG, "IME: commitText(\"" + text + "\", " + newCursorPosition + ")");
                }
                super.commitText(text, newCursorPosition);

                if (mEmulator == null) return true;

                Editable content = getEditable();
                sendTextToTerminal(content);
                content.clear();
                return true;
            }

            @Override
            public boolean deleteSurroundingText(int leftLength, int rightLength) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                    mClient.logInfo(LOG_TAG, "IME: deleteSurroundingText(" + leftLength + ", " + rightLength + ")");
                }
                // The stock Samsung keyboard with 'Auto check spelling' enabled sends leftLength > 1.
                KeyEvent deleteKey = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL);
                for (int i = 0; i < leftLength; i++) sendKeyEvent(deleteKey);
                return super.deleteSurroundingText(leftLength, rightLength);
            }

            void sendTextToTerminal(CharSequence text) {
                final int textLengthInChars = text.length();
                if (textLengthInChars == 0) return;

                // Modifiers may be latched on the extra keys bar, and reading them clears them, so they
                // are read once here and passed on to inputCodePoint() below. Do not consume latched
                // modifiers when flushing an already-typed composition in finishComposingText(), or
                // ExtraKeys SHIFT/CTRL/ALT meant for the following key (e.g. Shift+Enter) is lost.
                final boolean consumeModifiers = !mIsFinishingComposingText;
                final boolean controlDown = consumeModifiers && mClient.readControlKey();
                // The client may want to act on the selected text, like copying it on Ctrl+C, and it
                // has to get the chance before the selection is cleared below. Key events get the
                // same chance in onKeyDown().
                if (consumeModifiers && isSelectingText() && mClient.onTextInputWhileSelectingText(text, controlDown)) return;
                final boolean altDown = consumeModifiers && mClient.readAltKey();
                final boolean shiftDown = consumeModifiers && mClient.readShiftKey();
                stopTextSelectionMode();
                for (int i = 0; i < textLengthInChars; i++) {
                    char firstChar = text.charAt(i);
                    int codePoint;
                    if (Character.isHighSurrogate(firstChar)) {
                        if (++i < textLengthInChars) {
                            codePoint = Character.toCodePoint(firstChar, text.charAt(i));
                        } else {
                            // At end of string, with no low surrogate following the high:
                            codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR;
                        }
                    } else {
                        codePoint = firstChar;
                    }

                    int unshiftedCodePoint = codePoint;
                    // Check onKeyDown() for details.
                    if (shiftDown)
                        codePoint = Character.toUpperCase(codePoint);

                    boolean ctrlHeld = false;
                    if (codePoint == '\n' || codePoint == '\r') {
                        // The AOSP keyboard and descendants send \n as text when the enter key is pressed,
                        // instead of a key event like most other keyboard apps. A terminal expects \r for
                        // the enter key, and must not misclassify it as synthetic Ctrl+M under Kitty/modifyOtherKeys.
                        codePoint = '\r';
                        unshiftedCodePoint = '\r';
                    } else if (codePoint == '\t') {
                        // Preserve Tab as '\t' rather than converting it into synthetic Ctrl+I.
                        unshiftedCodePoint = '\t';
                    } else if (codePoint <= 31 && codePoint != 27) {
                        // E.g. penti keyboard for ctrl input.
                        ctrlHeld = true;
                        switch (codePoint) {
                            case 31:
                                codePoint = '_';
                                break;
                            case 30:
                                codePoint = '^';
                                break;
                            case 29:
                                codePoint = ']';
                                break;
                            case 28:
                                codePoint = '\\';
                                break;
                            default:
                                codePoint += 96;
                                break;
                        }
                        unshiftedCodePoint = codePoint;
                    }

                    inputCodePoint(KEY_EVENT_SOURCE_SOFT_KEYBOARD, codePoint, unshiftedCodePoint, ctrlHeld || controlDown, altDown, shiftDown);
                }
            }

        };
        return mInputConnection;
    }

    /**
     * Commit any in-progress IME composing text before input that bypasses the IME is written to the
     * terminal, so that it is not committed after that input. Called from
     * {@link #onKeyDown(int, KeyEvent)} and {@link #inputCodePoint(int, int, boolean, boolean)}, the
     * two entry points that do not go through the input connection.
     *
     * The composing span is set by {@link BaseInputConnection#setComposingText(CharSequence, int)}
     * and removed by {@link BaseInputConnection#finishComposingText()}, so it is both the "is
     * composing" signal and the guard against recursing back here from the
     * {@link #inputCodePoint(int, int, boolean, boolean)} calls that committing makes.
     */
    private void finalizeComposingIfActive() {
        if (mInputConnection == null) return;
        final Editable editable = mInputConnection.getEditable();
        if (BaseInputConnection.getComposingSpanStart(editable) < 0) return;
        mInputConnection.finishComposingText();
        // Some IMEs keep the committed text in their own composing buffer, so restart the input
        // connection to make them drop it.
        post(this::restartInput);
    }

    /**
     * Report the terminal cursor position and the view coordinates to the IME, so that IME windows
     * like the candidate bar can be shown next to the terminal cursor instead of at the bottom of the
     * screen. Only called if the IME requested cursor updates via
     * {@link InputConnection#requestCursorUpdates(int)}.
     */
    private void updateCursorAnchorInfo() {
        if (mEmulator == null || mRenderer == null) return;

        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;

        final int visibleRow = mEmulator.getCursorRow() - mTopRow;
        CursorAnchorInfo.Builder builder = new CursorAnchorInfo.Builder();
        if (visibleRow >= 0 && visibleRow < mEmulator.mRows) {
            // Use the same geometry as `TerminalRenderer`, where the baseline of a row is at
            // `(row + 1) * mFontLineSpacing` and its cell is `mFontLineSpacing` high and starts at
            // `mFontLineSpacingAndAscent` above the baseline.
            final float left = mEmulator.getCursorCol() * mRenderer.mFontWidth;
            final float top = visibleRow * mRenderer.mFontLineSpacing + mRenderer.mFontLineSpacingAndAscent;
            final float bottom = top + mRenderer.mFontLineSpacing;
            final float baseline = (visibleRow + 1) * mRenderer.mFontLineSpacing;
            builder.setInsertionMarkerLocation(left, top, baseline, bottom, CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION);
        } else {
            // The cursor is scrolled out of view, so there is nothing for the IME to anchor to.
            builder.setInsertionMarkerLocation(0, 0, 0, 0, CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION);
        }

        // The IME expects the insertion marker in view local coordinates, so that the matrix maps
        // them to screen coordinates.
        Matrix matrix = new Matrix(getMatrix());
        int[] location = new int[2];
        getLocationOnScreen(location);
        matrix.postTranslate(location[0], location[1]);
        builder.setMatrix(matrix);

        imm.updateCursorAnchorInfo(this, builder.build());
    }

    /**
     * Post a cursor anchor info update for the next frame, coalescing the updates of a frame into a
     * single one, since the terminal content can change many times per frame.
     */
    private void scheduleCursorAnchorInfoUpdate() {
        if (mCursorAnchorInfoUpdatePending) return;
        mCursorAnchorInfoUpdatePending = true;
        postOnAnimation(() -> {
            mCursorAnchorInfoUpdatePending = false;
            updateCursorAnchorInfo();
        });
    }

    @Override
    protected int computeVerticalScrollRange() {
        return mEmulator == null ? 1 : mEmulator.getScreen().getActiveRows();
    }

    @Override
    protected int computeVerticalScrollExtent() {
        return mEmulator == null ? 1 : mEmulator.mRows;
    }

    @Override
    protected int computeVerticalScrollOffset() {
        return mEmulator == null ? 1 : mEmulator.getScreen().getActiveRows() + mTopRow - mEmulator.mRows;
    }

    public void onScreenUpdated() {
        onScreenUpdated(false);
    }

    public void onScreenUpdated(boolean skipScrolling) {
        if (mEmulator == null) return;

        if ((mCursorUpdateMode & InputConnection.CURSOR_UPDATE_MONITOR) != 0)
            scheduleCursorAnchorInfoUpdate();

        int rowsInHistory = mEmulator.getScreen().getActiveTranscriptRows();
        if (mTopRow < -rowsInHistory) setTopRow(-rowsInHistory);

        if (isSelectingText() || mHoldDetector.isDraggingTextSelection() || mEmulator.isAutoScrollDisabled()) {
            // Do not scroll when selecting text.
            int rowShift = mEmulator.getScrollCounter();
            if (-mTopRow + rowShift > rowsInHistory) {
                // .. unless we're hitting the end of history transcript, in which
                // case we abort text selection and scroll to end.
                if (isSelectingText())
                    stopTextSelectionMode();
                if (mHoldDetector.isDraggingTextSelection())
                    cancelSingleFingerHold(false);

                if (mEmulator.isAutoScrollDisabled()) {
                    setTopRow(-rowsInHistory);
                    skipScrolling = true;
                }
            } else {
                skipScrolling = true;
                setTopRow(mTopRow - rowShift);
                decrementYTextSelectionCursors(rowShift);
            }
        }

        if (!skipScrolling && mTopRow != 0) {
            // Scroll down if not already there.
            if (mTopRow < -3) {
                // Awaken scroll bars only if scrolling a noticeable amount
                // - we do not want visible scroll bars during normal typing
                // of one row at a time.
                awakenScrollBars();
            }
            setTopRow(0);
        }

        mEmulator.clearScrollCounter();

        invalidate();
        if (mAccessibilityEnabled) setContentDescription(getText());
    }

    /** This must be called by the hosting activity in {@link Activity#onContextMenuClosed(Menu)}
     * when context menu for the {@link TerminalView} is started by
     * {@link TextSelectionCursorController#ACTION_MORE} is closed. */
    public void onContextMenuClosed(Menu menu) {
        // Unset the stored text since it shouldn't be used anymore and should be cleared from memory
        unsetStoredSelectedText();
    }

    /**
     * Sets the text size, which in turn sets the number of rows and columns.
     *
     * @param textSize the new font size, in density-independent pixels.
     */
    public void setTextSize(int textSize) {
        mRenderer = new TerminalRenderer(textSize, mRenderer == null ? Typeface.MONOSPACE : mRenderer.mTypeface);
        updateSize();
    }

    public void setTypeface(Typeface newTypeface) {
        mRenderer = new TerminalRenderer(mRenderer.mTextSize, newTypeface);
        updateSize();
        invalidate();
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return true;
    }

    @Override
    public boolean isOpaque() {
        return true;
    }

    /**
     * Get the zero indexed column and row of the terminal view for the
     * position of the event.
     *
     * @param event The event with the position to get the column and row for.
     * @param relativeToScroll If true the column number will take the scroll
     * position into account. E.g. if scrolled 3 lines up and the event
     * position is in the top left, column will be -3 if relativeToScroll is
     * true and 0 if relativeToScroll is false.
     * @return Array with the column and row.
     */
    public int[] getColumnAndRow(MotionEvent event, boolean relativeToScroll) {
        int column = (int) (event.getX() / mRenderer.mFontWidth);
        int row = (int) ((event.getY() - mRenderer.mFontLineSpacingAndAscent) / mRenderer.mFontLineSpacing);
        if (relativeToScroll) {
            row += mTopRow;
        }
        return new int[] { column, row };
    }

    /** Send a single mouse event code to the terminal. */
    void sendMouseEventCode(MotionEvent e, int button, boolean pressed) {
        int[] columnAndRow = getColumnAndRow(e, false);
        int x = columnAndRow[0] + 1;
        int y = columnAndRow[1] + 1;
        if (pressed && (button == TerminalEmulator.MOUSE_WHEELDOWN_BUTTON || button == TerminalEmulator.MOUSE_WHEELUP_BUTTON)) {
            if (mMouseStartDownTime == e.getDownTime()) {
                x = mMouseScrollStartX;
                y = mMouseScrollStartY;
            } else {
                mMouseStartDownTime = e.getDownTime();
                mMouseScrollStartX = x;
                mMouseScrollStartY = y;
            }
        }
        dispatchMouseEventAtCell(button, x, y, pressed);
    }

    /** Send a single mouse event at a view-relative pixel position. */
    void sendMouseEventCodeAt(int button, float x, float y, boolean pressed) {
        int column = (int) (x / mRenderer.mFontWidth) + 1;
        int row = (int) ((y - mRenderer.mFontLineSpacingAndAscent) / mRenderer.mFontLineSpacing) + 1;
        dispatchMouseEventAtCell(button, column, row, pressed);
    }

    /** Like xterm, only report left button drag motion when the pointer moves to a different cell. */
    private void dispatchMouseEventAtCell(int button, int column, int row, boolean pressed) {
        int clampedCol = Math.max(1, Math.min(mEmulator.mColumns, column));
        int clampedRow = Math.max(1, Math.min(mEmulator.mRows, row));
        if (button == TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED) {
            if (clampedCol == mLastDragCol && clampedRow == mLastDragRow) {
                return;
            }
            mLastDragCol = clampedCol;
            mLastDragRow = clampedRow;
        } else if (button == TerminalEmulator.MOUSE_LEFT_BUTTON) {
            mLastDragCol = pressed ? clampedCol : -1;
            mLastDragRow = pressed ? clampedRow : -1;
        }
        mEmulator.sendMouseEvent(button, clampedCol, clampedRow, pressed);
    }

    private void resetMouseMotionState() {
        mLastHoverCol = -1;
        mLastHoverRow = -1;
        mLastDragCol = -1;
        mLastDragRow = -1;
    }

    /**
     * Feed the single-finger hold detector and multi-finger tap/swipe detectors.
     *
     * <ul>
     *   <li>Single finger: ~0.45s hold selects text (or arms mouse right-click / left-drag in TUI
     *       mouse mode, escalating to Termux selection at ~1.2s).</li>
     *   <li>Two fingers: tap pastes, horizontal swipe switches sessions, pinch zooms font size.</li>
     *   <li>Three fingers: tap opens the session drawer.</li>
     * </ul>
     */
    private void updateTouchGestures(MotionEvent event) {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mMultiFingerTapDetector.onDown(event.getEventTime(), event.getX(), event.getY());
                mMultiFingerSwipeDetector.onDown();
                mGestureWasSwipe = false;
                stopHoldTimersAndAutoScroll();
                if (isSelectingText()) {
                    mHoldDetector.reset();
                } else {
                    mHoldDetector.onDown(event.getX(), event.getY(), mEmulator.isMouseTrackingActive());
                    postDelayed(mHoldStage1Runnable, HOLD_STAGE_1_TIMEOUT_MS);
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // Keep the parent (the drawer layout) from cancelling a multi-finger gesture; on some
                // ROMs its drag helper otherwise sends ACTION_CANCEL as soon as a second finger is
                // down, which would drop the two/three-finger gesture before it can be recognised.
                requestParentDisallowIntercept(true);
                cancelSingleFingerHold(true);
                int pointerCount = event.getPointerCount();
                mMultiFingerTapDetector.onPointerCount(pointerCount);
                mMultiFingerSwipeDetector.onPointerCount(pointerCount, centroidX(event), centroidY(event), fingerSpan(event));
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient.logInfo(LOG_TAG, "pointer down, count=" + pointerCount);
                break;
            case MotionEvent.ACTION_MOVE:
                mMultiFingerTapDetector.onMove(event.getX(), event.getY());
                if (event.getPointerCount() == 1 && mMultiFingerTapDetector.getMaxPointerCount() == 1) {
                    handleHoldAction(mHoldDetector.onMove(event.getX(), event.getY()));
                } else {
                    int swipe = mMultiFingerSwipeDetector.onMove(event.getPointerCount(), centroidX(event), centroidY(event), fingerSpan(event));
                    if (swipe != MultiFingerSwipeDetector.SWIPE_NONE) {
                        mGestureWasSwipe = true;
                        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                            mClient.logInfo(LOG_TAG, "two-finger swipe " + (swipe == MultiFingerSwipeDetector.SWIPE_RIGHT ? "right" : "left"));
                        }
                        mClient.onSessionSwipe(swipe == MultiFingerSwipeDetector.SWIPE_RIGHT);
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (mForwardedButtonDown) {
                    releaseForwardedButton();
                }
                break;
            case MotionEvent.ACTION_UP:
                stopHoldTimersAndAutoScroll();
                if (mMultiFingerTapDetector.getMaxPointerCount() == 1) {
                    handleHoldAction(mHoldDetector.onUp(event.getX(), event.getY()));
                    mMultiFingerTapDetector.onCancel();
                } else {
                    if (mForwardedButtonDown) releaseForwardedButton();
                    int tap = mMultiFingerTapDetector.onUp(event.getEventTime());
                    if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
                        mClient.logInfo(LOG_TAG, "up: maxPointers=" + mMultiFingerTapDetector.getMaxPointerCount() + ", tap=" + tap);
                    }
                    if (tap != MultiFingerTapDetector.TAP_NONE && !isSelectingText() && !mGestureWasScale && !mGestureWasSwipe) {
                        handleMultiFingerTap(tap);
                    }
                }
                requestParentDisallowIntercept(false);
                break;
            case MotionEvent.ACTION_CANCEL:
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient.logInfo(LOG_TAG, "touch cancelled");
                mMultiFingerTapDetector.onCancel();
                mMultiFingerSwipeDetector.onCancel();
                cancelSingleFingerHold(true);
                if (mForwardedButtonDown) releaseForwardedButton();
                requestParentDisallowIntercept(false);
                break;
        }
    }

    private void onHoldStage1Timeout() {
        handleHoldAction(mHoldDetector.onStage1Timeout());
    }

    private void onHoldStage2Timeout() {
        handleHoldAction(mHoldDetector.onStage2Timeout());
    }

    private void handleHoldAction(SingleFingerHoldDetector.Action action) {
        if (mEmulator == null) return;
        switch (action) {
            case NONE:
                break;
            case ABORT_TO_SCROLL:
                stopHoldTimersAndAutoScroll();
                break;
            case ARM_MOUSE_HOLD:
                performLightHoldHaptic();
                postDelayed(mHoldStage2Runnable, HOLD_STAGE_2_TIMEOUT_MS - HOLD_STAGE_1_TIMEOUT_MS);
                break;
            case START_TEXT_SELECTION:
            case ESCALATE_TO_TEXT_SELECTION:
                stopHoldTimersAndAutoScroll();
                if (dispatchClientLongPress(mHoldDetector.getAnchorX(), mHoldDetector.getAnchorY())) {
                    mHoldDetector.onCancel(false);
                    break;
                }
                requestParentDisallowIntercept(true);
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                beginHoldDragSelection(mHoldDetector.getAnchorX(), mHoldDetector.getAnchorY());
                break;
            case START_MOUSE_DRAG:
                stopHoldTimersAndAutoScroll();
                requestParentDisallowIntercept(true);
                if (mEmulator.isMouseTrackingActive()) {
                    mForwardedButtonDown = true;
                    mForwardedX = mHoldDetector.getAnchorX();
                    mForwardedY = mHoldDetector.getAnchorY();
                    sendMouseEventCodeAt(TerminalEmulator.MOUSE_LEFT_BUTTON, mForwardedX, mForwardedY, true);
                    mForwardedX = mHoldDetector.getLastX();
                    mForwardedY = mHoldDetector.getLastY();
                    sendMouseEventCodeAt(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, mForwardedX, mForwardedY, true);
                }
                break;
            case UPDATE_MOUSE_DRAG:
                if (mForwardedButtonDown && mEmulator.isMouseTrackingActive()) {
                    mForwardedX = mHoldDetector.getLastX();
                    mForwardedY = mHoldDetector.getLastY();
                    sendMouseEventCodeAt(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, mForwardedX, mForwardedY, true);
                }
                break;
            case FINISH_MOUSE_DRAG:
            case CANCEL_MOUSE_DRAG:
                releaseForwardedButton();
                break;
            case SEND_MOUSE_RIGHT_CLICK:
                stopHoldTimersAndAutoScroll();
                if (mEmulator.isMouseTrackingActive()) {
                    float rx = mHoldDetector.getAnchorX();
                    float ry = mHoldDetector.getAnchorY();
                    sendMouseEventCodeAt(TerminalEmulator.MOUSE_RIGHT_BUTTON, rx, ry, true);
                    sendMouseEventCodeAt(TerminalEmulator.MOUSE_RIGHT_BUTTON, rx, ry, false);
                }
                break;
            case UPDATE_TEXT_SELECTION:
                updateHoldDragSelection(mHoldDetector.getLastX(), mHoldDetector.getLastY());
                updateDragAutoScroll(mHoldDetector.getLastY());
                break;
            case FINISH_TEXT_SELECTION:
                stopHoldTimersAndAutoScroll();
                if (mTextSelectionCursorController != null) {
                    requestFocus();
                    mTextSelectionCursorController.finishDragSelection();
                    mClient.copyModeChanged(isSelectingText());
                }
                break;
            case CANCEL_TEXT_SELECTION:
                stopHoldTimersAndAutoScroll();
                if (mTextSelectionCursorController != null) {
                    mTextSelectionCursorController.cancelDragSelection();
                }
                break;
        }
    }

    private void cancelSingleFingerHold(boolean clearConsumedFlag) {
        stopHoldTimersAndAutoScroll();
        handleHoldAction(mHoldDetector.onCancel(clearConsumedFlag));
    }

    private void stopHoldTimersAndAutoScroll() {
        removeCallbacks(mHoldStage1Runnable);
        removeCallbacks(mHoldStage2Runnable);
        removeCallbacks(mDragAutoScrollRunnable);
    }

    private void performLightHoldHaptic() {
        int constant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
            ? HapticFeedbackConstants.CLOCK_TICK
            : HapticFeedbackConstants.KEYBOARD_TAP;
        performHapticFeedback(constant);
    }

    private boolean dispatchClientLongPress(float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent synthetic = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
        try {
            return mClient.onLongPress(synthetic);
        } finally {
            synthetic.recycle();
        }
    }

    private void beginHoldDragSelection(float x, float y) {
        if (mEmulator == null || mRenderer == null) return;
        int col = (int) (x / mRenderer.mFontWidth);
        int row = (int) ((y - mRenderer.mFontLineSpacingAndAscent) / mRenderer.mFontLineSpacing) + mTopRow;
        getTextSelectionCursorController().beginDragSelection(col, row);
    }

    private void updateHoldDragSelection(float x, float y) {
        if (mEmulator == null || mRenderer == null || mTextSelectionCursorController == null) return;
        int col = (int) (x / mRenderer.mFontWidth);
        int row = (int) ((y - mRenderer.mFontLineSpacingAndAscent) / mRenderer.mFontLineSpacing) + mTopRow;
        mTextSelectionCursorController.updateAnchoredDragSelection(col, row);
    }

    private int computeDragAutoScrollDirection(float y) {
        if (mRenderer == null) return 0;
        float edgeZone = mRenderer.mFontLineSpacing * 1.5f;
        if (y < edgeZone) return -1;
        if (y > getHeight() - edgeZone) return 1;
        return 0;
    }

    private void updateDragAutoScroll(float y) {
        if (mEmulator == null || mEmulator.isAlternateBufferActive()) {
            removeCallbacks(mDragAutoScrollRunnable);
            return;
        }
        int dir = computeDragAutoScrollDirection(y);
        if (dir == 0) {
            removeCallbacks(mDragAutoScrollRunnable);
        } else {
            removeCallbacks(mDragAutoScrollRunnable);
            postDelayed(mDragAutoScrollRunnable, DRAG_AUTO_SCROLL_INTERVAL_MS);
        }
    }

    private void requestParentDisallowIntercept(boolean disallow) {
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(disallow);
    }

    private static float centroidX(MotionEvent event) {
        float sum = 0f;
        for (int i = 0; i < event.getPointerCount(); i++) sum += event.getX(i);
        return sum / event.getPointerCount();
    }

    private static float centroidY(MotionEvent event) {
        float sum = 0f;
        for (int i = 0; i < event.getPointerCount(); i++) sum += event.getY(i);
        return sum / event.getPointerCount();
    }

    private static float fingerSpan(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        float dx = event.getX(1) - event.getX(0);
        float dy = event.getY(1) - event.getY(0);
        return (float) Math.hypot(dx, dy);
    }

    /**
     * Release a forwarded left button after a cancelled gesture. If the program stopped accepting
     * mouse events, sending a release would be typed into the shell as garbage, so it is skipped.
     */
    private void releaseForwardedButton() {
        if (!mForwardedButtonDown) return;
        mForwardedButtonDown = false;
        if (mEmulator != null && mEmulator.isMouseTrackingActive())
            sendMouseEventCodeAt(TerminalEmulator.MOUSE_LEFT_BUTTON, mForwardedX, mForwardedY, false);
    }

    private void handleMultiFingerTap(int fingers) {
        if (fingers == MultiFingerTapDetector.TAP_TWO_FINGERS) {
            if (mTermSession != null) mTermSession.onPasteTextFromClipboard();
        } else if (fingers == MultiFingerTapDetector.TAP_THREE_FINGERS) {
            mClient.onThreeFingerTap();
        }
    }

    /** Perform a scroll, either from dragging the screen or by scrolling a mouse wheel. */
    void doScroll(MotionEvent event, int rowsDown) {
        boolean up = rowsDown < 0;
        int amount = Math.abs(rowsDown);
        for (int i = 0; i < amount; i++) {
            if (mEmulator.isMouseTrackingActive()) {
                sendMouseEventCode(event, up ? TerminalEmulator.MOUSE_WHEELUP_BUTTON : TerminalEmulator.MOUSE_WHEELDOWN_BUTTON, true);
            } else if (mEmulator.isAlternateBufferActive()) {
                // Send up and down key events for scrolling, which is what some terminals do to make scroll work in
                // e.g. less, which shifts to the alt screen without mouse handling.
                handleKeyCode(up ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN, 0);
            } else {
                setTopRow(Math.min(0, Math.max(-(mEmulator.getScreen().getActiveTranscriptRows()), mTopRow + (up ? -1 : 1))));
                if (!awakenScrollBars()) invalidate();
            }
        }
    }

    /** Overriding {@link View#onGenericMotionEvent(MotionEvent)}. */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (mEmulator != null && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_SCROLL) {
                // Handle mouse wheel scrolling.
                boolean up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0.0f;
                doScroll(event, up ? -3 : 3);
                return true;
            } else if (action == MotionEvent.ACTION_HOVER_MOVE && mEmulator.isMouseTrackingAnyEvent()) {
                // DECSET 1003: report hover motion, once per cell change.
                int[] colAndRow = getColumnAndRow(event, false);
                int col = Math.max(1, Math.min(mEmulator.mColumns, colAndRow[0] + 1));
                int row = Math.max(1, Math.min(mEmulator.mRows, colAndRow[1] + 1));
                if (col != mLastHoverCol || row != mLastHoverRow) {
                    mLastHoverCol = col;
                    mLastHoverRow = row;
                    mEmulator.sendMouseEvent(TerminalEmulator.MOUSE_MOTION_NO_BUTTON, col, row, true);
                }
                return true;
            }
        }
        return false;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    @TargetApi(23)
    public boolean onTouchEvent(MotionEvent event) {
        if (mEmulator == null) return true;
        final int action = event.getAction();

        updateTouchGestures(event);

        if (isSelectingText()) {
            updateFloatingToolbarVisibility(event);
            mGestureRecognizer.onTouchEvent(event);
            return true;
        } else if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                if (action == MotionEvent.ACTION_DOWN) showContextMenu();
                return true;
            } else if (event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
                ClipboardManager clipboardManager = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clipData = clipboardManager.getPrimaryClip();
                if (clipData != null) {
                    ClipData.Item clipItem = clipData.getItemAt(0);
                    if (clipItem != null) {
                        CharSequence text = clipItem.coerceToText(getContext());
                        if (!TextUtils.isEmpty(text)) mEmulator.paste(text.toString());
                    }
                }
                return true;
            } else {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        requestFocus();
                        mMouseModifiedTapPending = false;
                        mMousePrimaryTapPending = false;
                        if (event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)) {
                            mMouseDownX = event.getX();
                            mMouseDownY = event.getY();
                            if (mClient.shouldInterceptModifiedTap(event)) {
                                mMouseModifiedTapPending = true;
                            } else if (mEmulator.isMouseTrackingActive()) {
                                mForwardedX = event.getX();
                                mForwardedY = event.getY();
                                sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON, true);
                                mForwardedButtonDown = true;
                            } else {
                                mMousePrimaryTapPending = true;
                            }
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if ((mMouseModifiedTapPending || mMousePrimaryTapPending) &&
                            Math.hypot(event.getX() - mMouseDownX, event.getY() - mMouseDownY) >= mTouchSlop) {
                            mMouseModifiedTapPending = false;
                            mMousePrimaryTapPending = false;
                        }
                        if (!mMouseModifiedTapPending && mForwardedButtonDown && mEmulator.isMouseTrackingActive()) {
                            mForwardedX = event.getX();
                            mForwardedY = event.getY();
                            sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (mMouseModifiedTapPending) {
                            mMouseModifiedTapPending = false;
                            mClient.onModifiedSingleTap(event);
                        } else if (mForwardedButtonDown) {
                            releaseForwardedButton();
                        } else if (mMousePrimaryTapPending) {
                            mMousePrimaryTapPending = false;
                            mClient.onSingleTapUp(event);
                        }
                        break;
                    case MotionEvent.ACTION_CANCEL:
                        mMouseModifiedTapPending = false;
                        mMousePrimaryTapPending = false;
                        releaseForwardedButton();
                        break;
                }
                return true;
            }
        }

        mGestureRecognizer.onTouchEvent(event);
        return true;
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyPreIme(keyCode=" + keyCode + ", event=" + event + ")");
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            cancelRequestAutoFill();
            if (isSelectingText()) {
                stopTextSelectionMode();
                return true;
            } else if (mClient.shouldBackButtonBeMappedToEscape()) {
                // Intercept back button to treat it as escape:
                switch (event.getAction()) {
                    case KeyEvent.ACTION_DOWN:
                        return onKeyDown(keyCode, event);
                    case KeyEvent.ACTION_UP:
                        return onKeyUp(keyCode, event);
                }
            }
        } else if (mClient.shouldUseCtrlSpaceWorkaround() &&
                   keyCode == KeyEvent.KEYCODE_SPACE && event.isCtrlPressed()) {
            /* ctrl+space does not work on some ROMs without this workaround.
               However, this breaks it on devices where it works out of the box. */
            return onKeyDown(keyCode, event);
        }
        return super.onKeyPreIme(keyCode, event);
    }

    /**
     * Key presses in software keyboards will generally NOT trigger this listener, although some
     * may elect to do so in some situations. Do not rely on this to catch software key presses.
     * Gboard calls this when shouldEnforceCharBasedInput() is disabled (InputType.TYPE_NULL) instead
     * of calling commitText(), with deviceId=-1. However, Hacker's Keyboard, OpenBoard, LG Keyboard
     * call commitText().
     *
     * This function may also be called directly without android calling it, like by
     * `TerminalExtraKeys` which generates a KeyEvent manually which uses {@link KeyCharacterMap#VIRTUAL_KEYBOARD}
     * as the device (deviceId=-1), as does Gboard. That would normally use mappings defined in
     * `/system/usr/keychars/Virtual.kcm`. You can run `dumpsys input` to find the `KeyCharacterMapFile`
     * used by virtual keyboard or hardware keyboard. Note that virtual keyboard device is not the
     * same as software keyboard, like Gboard, etc. Its a fake device used for generating events and
     * for testing.
     *
     * We handle shift key in `commitText()` to convert codepoint to uppercase case there with a
     * call to {@link Character#toUpperCase(int)}, but here we instead rely on getUnicodeChar() for
     * conversion of keyCode, for both hardware keyboard shift key (via effectiveMetaState) and
     * `mClient.readShiftKey()`, based on value in kcm files.
     * This may result in different behaviour depending on keyboard and android kcm files set for the
     * InputDevice for the event passed to this function. This will likely be an issue for non-english
     * languages since `Virtual.kcm` in english only by default or at least in AOSP. For both hardware
     * shift key (via effectiveMetaState) and `mClient.readShiftKey()`, `getUnicodeChar()` is used
     * for shift specific behaviour which usually is to uppercase.
     *
     * For fn key on hardware keyboard, android checks kcm files for hardware keyboards, which is
     * `Generic.kcm` by default, unless a vendor specific one is defined. The event passed will have
     * {@link KeyEvent#META_FUNCTION_ON} set. If the kcm file only defines a single character or unicode
     * code point `\\uxxxx`, then only one event is passed with that value. However, if kcm defines
     * a `fallback` key for fn or others, like `key DPAD_UP { ... fn: fallback PAGE_UP }`, then
     * android will first pass an event with original key `DPAD_UP` and {@link KeyEvent#META_FUNCTION_ON}
     * set. But this function will not consume it and android will pass another event with `PAGE_UP`
     * and {@link KeyEvent#META_FUNCTION_ON} not set, which will be consumed.
     *
     * Now there are some other issues as well, firstly ctrl and alt flags are not passed to
     * `getUnicodeChar()`, so modified key values in kcm are not used. Secondly, if the kcm file
     * for other modifiers like shift or fn define a non-alphabet, like { fn: '\u0015' } to act as
     * DPAD_LEFT, the `getUnicodeChar()` will correctly return `21` as the code point but action will
     * not happen because the `handleKeyCode()` function that transforms DPAD_LEFT to `\033[D`
     * escape sequence for the terminal to perform the left action would not be called since its
     * called before `getUnicodeChar()` and terminal will instead get `21 0x15 Negative Acknowledgement`.
     * The solution to such issues is calling `getUnicodeChar()` before the call to `handleKeyCode()`
     * if user has defined a custom kcm file, like done in POC mentioned in #2237. Note that
     * Hacker's Keyboard calls `commitText()` so don't test fn/shift with it for this function.
     * https://github.com/termux/termux-app/pull/2237
     * https://github.com/agnostic-apollo/termux-app/blob/terminal-code-point-custom-mapping/terminal-view/src/main/java/com/termux/view/TerminalView.java
     *
     * Key Character Map (kcm) and Key Layout (kl) files info:
     * https://source.android.com/devices/input/key-character-map-files
     * https://source.android.com/devices/input/key-layout-files
     * https://source.android.com/devices/input/keyboard-devices
     * AOSP kcm and kl files:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/data/keyboards
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/packages/InputDevices/res/raw
     *
     * KeyCodes:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/view/KeyEvent.java
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/native/include/android/keycodes.h
     *
     * `dumpsys input`:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/services/inputflinger/reader/EventHub.cpp;l=1917
     *
     * Loading of keymap:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/services/inputflinger/reader/EventHub.cpp;l=1644
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/Keyboard.cpp;l=41
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/InputDevice.cpp
     * OVERLAY keymaps for hardware keyboards may be combined as well:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=165
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=831
     *
     * Parse kcm file:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=727
     * Parse key value:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=981
     *
     * `KeyEvent.getUnicodeChar()`
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/view/KeyEvent.java;l=2716
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/view/KeyCharacterMap.java;l=368
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/jni/android_view_KeyCharacterMap.cpp;l=117
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/native/libs/input/KeyCharacterMap.cpp;l=231
     *
     * Keyboard layouts advertised by applications, like for hardware keyboards via #ACTION_QUERY_KEYBOARD_LAYOUTS
     * Config is stored in `/data/system/input-manager-state.xml`
     * https://github.com/ris58h/custom-keyboard-layout
     * Loading from apps:
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=1221
     * Set:
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/hardware/input/InputManager.java;l=89
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/core/java/android/hardware/input/InputManager.java;l=543
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:packages/apps/Settings/src/com/android/settings/inputmethod/KeyboardLayoutDialogFragment.java;l=167
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=1385
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/PersistentDataStore.java
     * Get overlay keyboard layout
     * https://cs.android.com/android/platform/superproject/+/master:frameworks/base/services/core/java/com/android/server/input/InputManagerService.java;l=2158
     * https://cs.android.com/android/platform/superproject/+/android-11.0.0_r40:frameworks/base/services/core/jni/com_android_server_input_InputManagerService.cpp;l=616
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyDown(keyCode=" + keyCode + ", isSystem()=" + event.isSystem() + ", event=" + event + ")");
        if (mEmulator == null) return true;
        // Input from a hardware keyboard or the extra keys bar bypasses the IME, so commit an
        // in-progress composition first. Modifier keys must not, or pressing shift to compose a CJK
        // double consonant would commit the syllable in progress.
        if (!KeyEvent.isModifierKey(keyCode)) finalizeComposingIfActive();
        if (isSelectingText()) {
            // The client gets the key before the selection is cleared below, so that it can act on
            // the selected text, like copying it on Ctrl+C.
            if (mClient.onKeyDownWhileSelectingText(keyCode, event)) {
                invalidate();
                return true;
            }
            // Modifier keys do not produce input, so they must not clear the selection. Otherwise
            // the Ctrl of a hardware Ctrl+C (whose keydown arrives before the C keydown) would clear
            // the selection before the C key could copy it.
            if (SelectionKeyPolicy.shouldClearSelection(keyCode, KeyEvent.isModifierKey(keyCode), event.getUnicodeChar(0)))
                stopTextSelectionMode();
        }

        if (mClient.onKeyDown(keyCode, event, mTermSession)) {
            invalidate();
            return true;
        } else if (event.isSystem() && (!mClient.shouldBackButtonBeMappedToEscape() || keyCode != KeyEvent.KEYCODE_BACK)) {
            return super.onKeyDown(keyCode, event);
        } else if (event.getAction() == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            mTermSession.write(event.getCharacters());
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_LANGUAGE_SWITCH) {
            return super.onKeyDown(keyCode, event);
        }

        final int metaState = event.getMetaState();
        final boolean controlDown = event.isCtrlPressed() || mClient.readControlKey();
        final boolean leftAltDown = (metaState & KeyEvent.META_ALT_LEFT_ON) != 0 || mClient.readAltKey();
        final boolean shiftDown = event.isShiftPressed() || mClient.readShiftKey();
        final boolean rightAltDownFromEvent = (metaState & KeyEvent.META_ALT_RIGHT_ON) != 0;

        int keyMod = 0;
        if (controlDown) keyMod |= KeyHandler.KEYMOD_CTRL;
        if (event.isAltPressed() || leftAltDown) keyMod |= KeyHandler.KEYMOD_ALT;
        if (shiftDown) keyMod |= KeyHandler.KEYMOD_SHIFT;
        if (event.isNumLockOn()) keyMod |= KeyHandler.KEYMOD_NUM_LOCK;
        // https://github.com/termux/termux-app/issues/731
        if (!event.isFunctionPressed() && handleKeyCode(keyCode, keyMod)) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) mClient.logInfo(LOG_TAG, "handleKeyCode() took key event");
            return true;
        }

        // Clear Ctrl since we handle that ourselves:
        int bitsToClear = KeyEvent.META_CTRL_MASK;
        if (rightAltDownFromEvent) {
            // Let right Alt/Alt Gr be used to compose characters.
        } else {
            // Use left alt to send to terminal (e.g. Left Alt+B to jump back a word), so remove:
            bitsToClear |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        }
        int effectiveMetaState = event.getMetaState() & ~bitsToClear;

        if (shiftDown) effectiveMetaState |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        if (mClient.readFnKey()) effectiveMetaState |= KeyEvent.META_FUNCTION_ON;

        int result = event.getUnicodeChar(effectiveMetaState);
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "KeyEvent#getUnicodeChar(" + effectiveMetaState + ") returned: " + result);
        if (result == 0) {
            return false;
        }

        int unshiftedCodePoint = event.getUnicodeChar(0) & KeyCharacterMap.COMBINING_ACCENT_MASK;

        int oldCombiningAccent = mCombiningAccent;
        if ((result & KeyCharacterMap.COMBINING_ACCENT) != 0) {
            // If entered combining accent previously, write it out:
            if (mCombiningAccent != 0)
                inputCodePoint(event.getDeviceId(), mCombiningAccent, mCombiningAccent, controlDown, leftAltDown, shiftDown);
            mCombiningAccent = result & KeyCharacterMap.COMBINING_ACCENT_MASK;
        } else {
            if (mCombiningAccent != 0) {
                int combinedChar = KeyCharacterMap.getDeadChar(mCombiningAccent, result);
                if (combinedChar > 0) {
                    result = combinedChar;
                    unshiftedCodePoint = combinedChar;
                }
                mCombiningAccent = 0;
            }
            inputCodePoint(event.getDeviceId(), result, unshiftedCodePoint, controlDown, leftAltDown, shiftDown);
        }

        if (mCombiningAccent != oldCombiningAccent) invalidate();

        return true;
    }

    public void inputCodePoint(int eventSource, int codePoint, boolean controlDownFromEvent, boolean leftAltDownFromEvent) {
        inputCodePoint(eventSource, codePoint, codePoint, controlDownFromEvent, leftAltDownFromEvent, false);
    }

    public void inputCodePoint(int eventSource, int codePoint, int unshiftedCodePoint,
                               boolean controlDownFromEvent, boolean leftAltDownFromEvent, boolean shiftDownFromEvent) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED) {
            mClient.logInfo(LOG_TAG, "inputCodePoint(eventSource=" + eventSource + ", codePoint=" + codePoint + ", controlDownFromEvent=" + controlDownFromEvent + ", leftAltDownFromEvent="
                + leftAltDownFromEvent + ")");
        }

        if (mTermSession == null) return;

        // Same as onKeyDown(), for input that does not go through a key event, like the extra keys bar.
        finalizeComposingIfActive();

        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        if (mEmulator != null)
            mEmulator.setCursorBlinkState(true);

        final boolean consumeModifiers = !mIsFinishingComposingText;
        final boolean controlDown = controlDownFromEvent || (consumeModifiers && mClient.readControlKey());
        final boolean altDown = leftAltDownFromEvent || (consumeModifiers && mClient.readAltKey());

        if (mClient.onCodePoint(codePoint, controlDown, mTermSession)) return;

        final boolean shiftDown = shiftDownFromEvent || (consumeModifiers && mClient.readShiftKey());
        if (!shiftDownFromEvent && shiftDown && codePoint >= 32) {
            codePoint = Character.toUpperCase(codePoint);
        }

        // Normalize bluetooth keyboard modifier characters before modern or legacy encoding.
        if (eventSource > KEY_EVENT_SOURCE_SOFT_KEYBOARD) {
            switch (codePoint) {
                case 0x02DC: // SMALL TILDE.
                    codePoint = 0x007E; // TILDE (~).
                    break;
                case 0x02CB: // MODIFIER LETTER GRAVE ACCENT.
                    codePoint = 0x0060; // GRAVE ACCENT (`).
                    break;
                case 0x02C6: // MODIFIER LETTER CIRCUMFLEX ACCENT.
                    codePoint = 0x005E; // CIRCUMFLEX ACCENT (^).
                    break;
            }
        }

        if (mEmulator != null) {
            String protocolEncoded = KeyboardProtocol.encodeCodePoint(
                codePoint, unshiftedCodePoint, controlDown, altDown, shiftDown,
                mEmulator.getKittyKeyboardFlags(), mEmulator.getModifyOtherKeysMode());
            if (protocolEncoded != null) {
                mTermSession.write(protocolEncoded);
                return;
            }
        }

        if (controlDown) {
            if (codePoint >= 'a' && codePoint <= 'z') {
                codePoint = codePoint - 'a' + 1;
            } else if (codePoint >= 'A' && codePoint <= 'Z') {
                codePoint = codePoint - 'A' + 1;
            } else if (codePoint == ' ' || codePoint == '2') {
                codePoint = 0;
            } else if (codePoint == '[' || codePoint == '3') {
                codePoint = 27; // ^[ (Esc)
            } else if (codePoint == '\\' || codePoint == '4') {
                codePoint = 28;
            } else if (codePoint == ']' || codePoint == '5') {
                codePoint = 29;
            } else if (codePoint == '^' || codePoint == '6') {
                codePoint = 30; // control-^
            } else if (codePoint == '_' || codePoint == '7' || codePoint == '/') {
                // "Ctrl-/ sends 0x1f which is equivalent of Ctrl-_ since the days of VT102"
                // - http://apple.stackexchange.com/questions/24261/how-do-i-send-c-that-is-control-slash-to-the-terminal
                codePoint = 31;
            } else if (codePoint == '8') {
                codePoint = 127; // DEL
            }
        }

        if (codePoint > -1) {
            // If left alt, send escape before the code point to make e.g. Alt+B and Alt+F work in readline:
            mTermSession.writeCodePoint(altDown, codePoint);
        }
    }

    /** Input the specified keyCode if applicable and return if the input was consumed. */
    public boolean handleKeyCode(int keyCode, int keyMod) {
        // Ensure cursor is shown when a key is pressed down like long hold on (arrow) keys
        if (mEmulator != null)
            mEmulator.setCursorBlinkState(true);

        if (handleKeyCodeAction(keyCode, keyMod))
            return true;

        TerminalEmulator term = mTermSession.getEmulator();
        String code = KeyHandler.getCode(keyCode, keyMod, term.isCursorKeysApplicationMode(), term.isKeypadApplicationMode(),
            term.getKittyKeyboardFlags(), term.getModifyOtherKeysMode());
        if (code == null) return false;
        mTermSession.write(code);
        return true;
    }

    public boolean handleKeyCodeAction(int keyCode, int keyMod) {
        boolean shiftDown = (keyMod & KeyHandler.KEYMOD_SHIFT) != 0;

        switch (keyCode) {
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                // shift+page_up and shift+page_down should scroll scrollback history instead of
                // scrolling command history or changing pages
                if (shiftDown) {
                    long time = SystemClock.uptimeMillis();
                    MotionEvent motionEvent = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 0, 0, 0);
                    doScroll(motionEvent, keyCode == KeyEvent.KEYCODE_PAGE_UP ? -mEmulator.mRows : mEmulator.mRows);
                    motionEvent.recycle();
                    return true;
                }
        }

       return false;
    }

    /**
     * Called when a key is released in the view.
     *
     * @param keyCode The keycode of the key which was released.
     * @param event   A {@link KeyEvent} describing the event.
     * @return Whether the event was handled.
     */
    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
            mClient.logInfo(LOG_TAG, "onKeyUp(keyCode=" + keyCode + ", event=" + event + ")");

        // Do not return for KEYCODE_BACK and send it to the client since user may be trying
        // to exit the activity.
        if (mEmulator == null && keyCode != KeyEvent.KEYCODE_BACK) return true;

        if (mClient.onKeyUp(keyCode, event)) {
            invalidate();
            return true;
        } else if (event.isSystem()) {
            // Let system key events through.
            return super.onKeyUp(keyCode, event);
        }

        return true;
    }

    /** Report window focus changes to the terminal for DECSET 1004 focus events. */
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (mEmulator != null) {
            mEmulator.reportFocusChange(hasWindowFocus);
        }
    }

    /**
     * This is called during layout when the size of this view has changed. If you were just added to the view
     * hierarchy, you're called with the old values of 0.
     */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        updateSize();
    }

    /** Check if the terminal size in rows and columns should be updated. */
    public void updateSize() {
        int viewWidth = getWidth();
        int viewHeight = getHeight();
        if (viewWidth == 0 || viewHeight == 0 || mTermSession == null) return;

        // Set to 80 and 24 if you want to enable vttest.
        int newColumns = Math.max(4, (int) (viewWidth / mRenderer.mFontWidth));
        int newRows = Math.max(4, (viewHeight - mRenderer.mFontLineSpacingAndAscent) / mRenderer.mFontLineSpacing);

        if (mEmulator == null || (newColumns != mEmulator.mColumns || newRows != mEmulator.mRows)) {
            mTermSession.updateSize(newColumns, newRows, (int) mRenderer.getFontWidth(), mRenderer.getFontLineSpacing());
            mEmulator = mTermSession.getEmulator();
            mClient.onEmulatorSet();

            // Update mTerminalCursorBlinkerRunnable inner class mEmulator on session change
            if (mTerminalCursorBlinkerRunnable != null)
                mTerminalCursorBlinkerRunnable.setEmulator(mEmulator);

            // Restore cached top row value if session/emulator was switched back from a
            // different session or after activity restart. The top row value also needs to be
            // maintained after opening/closing soft keyboard.
            int topRow = 0;
            if (mEmulator != null) {
                mEmulator.reportFocusChange(hasWindowFocus());
                int rowsInHistory = mEmulator.getScreen().getActiveTranscriptRows();
                int cachedTopRow = mEmulator.getTopRow();
                if (cachedTopRow >= -rowsInHistory) {
                    topRow = cachedTopRow;
                }
            }
            setTopRow(topRow);

            scrollTo(0, 0);
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mEmulator == null) {
            canvas.drawColor(0XFF000000);
        } else {
            // render the terminal view and highlight any selected text
            int[] sel = mDefaultSelectors;
            if (mTextSelectionCursorController != null) {
                mTextSelectionCursorController.getSelectors(sel);
            }

            mRenderer.render(mEmulator, canvas, mTopRow, sel[0], sel[1], sel[2], sel[3]);

            // render the text selection handles
            renderTextSelection();
        }
    }

    public TerminalSession getCurrentSession() {
        return mTermSession;
    }

    private CharSequence getText() {
        return mEmulator.getScreen().getSelectedText(0, mTopRow, mEmulator.mColumns, mTopRow + mEmulator.mRows);
    }

    public int getCursorX(float x) {
        return (int) (x / mRenderer.mFontWidth);
    }

    public int getCursorY(float y) {
        return (int) (((y - 40) / mRenderer.mFontLineSpacing) + mTopRow);
    }

    public int getPointX(int cx) {
        if (cx > mEmulator.mColumns) {
            cx = mEmulator.mColumns;
        }
        return Math.round(cx * mRenderer.mFontWidth);
    }

    public int getPointY(int cy) {
        return Math.round((cy - mTopRow) * mRenderer.mFontLineSpacing);
    }

    public int getTopRow() {
        return mTopRow;
    }

    public void setTopRow(int topRow) {
        setTopRow(topRow, true);
    }

    public void setTopRow(int topRow, boolean updateEmulator) {
        mTopRow = topRow;
        if (updateEmulator && mEmulator != null) {
            mEmulator.setTopRow(mTopRow);
        }
    }



    /**
     * Define functions required for AutoFill API
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void autofill(AutofillValue value) {
        if (value.isText()) {
            mTermSession.write(value.getTextValue().toString());
        }

        resetAutoFill();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int getAutofillType() {
        return mAutoFillType;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public String[] getAutofillHints() {
        return mAutoFillHints;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public AutofillValue getAutofillValue() {
        return AutofillValue.forText("");
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int getImportantForAutofill() {
        return mAutoFillImportance;
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private synchronized void resetAutoFill() {
        // Restore none type so that AutoFill UI isn't shown anymore.
        mAutoFillType = AUTOFILL_TYPE_NONE;
        mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_NO;
        mAutoFillHints = new String[0];
    }

    public AutofillManager getAutoFillManagerService() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null;

        try {
            Context context = getContext();
            if (context == null) return null;
            return context.getSystemService(AutofillManager.class);
        } catch (Exception e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to get AutofillManager service", e);
            return null;
        }
    }

    public boolean isAutoFillEnabled() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;

        try {
            AutofillManager autofillManager = getAutoFillManagerService();
            return autofillManager != null && autofillManager.isEnabled();
        } catch (Exception e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to check if Autofill is enabled", e);
            return false;
        }
    }

    public synchronized void requestAutoFillUsername() {
        requestAutoFill(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new String[]{View.AUTOFILL_HINT_USERNAME} :
                null);
    }

    public synchronized void requestAutoFillPassword() {
        requestAutoFill(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? new String[]{View.AUTOFILL_HINT_PASSWORD} :
            null);
    }

    public synchronized void requestAutoFill(String[] autoFillHints) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (autoFillHints == null || autoFillHints.length < 1) return;

        try {
            AutofillManager autofillManager = getAutoFillManagerService();
            if (autofillManager != null && autofillManager.isEnabled()) {
                // Update type that will be returned by `getAutofillType()` so that AutoFill UI is shown.
                mAutoFillType = AUTOFILL_TYPE_TEXT;
                // Update importance that will be returned by `getImportantForAutofill()` so that
                // AutoFill considers the view as important.
                mAutoFillImportance = IMPORTANT_FOR_AUTOFILL_YES;
                // Update hints that will be returned by `getAutofillHints()` for which to show AutoFill UI.
                mAutoFillHints = autoFillHints;
                autofillManager.requestAutofill(this);
            }
        } catch (Exception e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to request Autofill", e);
        }
    }

    public synchronized void cancelRequestAutoFill() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        if (mAutoFillType == AUTOFILL_TYPE_NONE) return;

        try {
            AutofillManager autofillManager = getAutoFillManagerService();
            if (autofillManager != null && autofillManager.isEnabled()) {
                resetAutoFill();
                autofillManager.cancel();
            }
        } catch (Exception e) {
            mClient.logStackTraceWithMessage(LOG_TAG, "Failed to cancel Autofill request", e);
        }
    }





    /**
     * Set terminal cursor blinker rate. It must be between {@link #TERMINAL_CURSOR_BLINK_RATE_MIN}
     * and {@link #TERMINAL_CURSOR_BLINK_RATE_MAX}, otherwise it will be disabled.
     *
     * The {@link #setTerminalCursorBlinkerState(boolean, boolean)} must be called after this
     * for changes to take effect if not disabling.
     *
     * @param blinkRate The value to set.
     * @return Returns {@code true} if setting blinker rate was successfully set, otherwise [@code false}.
     */
    public synchronized boolean setTerminalCursorBlinkerRate(int blinkRate) {
        boolean result;

        // If cursor blinking rate is not valid
        if (blinkRate != 0 && (blinkRate < TERMINAL_CURSOR_BLINK_RATE_MIN || blinkRate > TERMINAL_CURSOR_BLINK_RATE_MAX)) {
            mClient.logError(LOG_TAG, "The cursor blink rate must be in between " + TERMINAL_CURSOR_BLINK_RATE_MIN + "-" + TERMINAL_CURSOR_BLINK_RATE_MAX + ": " + blinkRate);
            mTerminalCursorBlinkerRate = 0;
            result = false;
        } else {
            mClient.logVerbose(LOG_TAG, "Setting cursor blinker rate to " + blinkRate);
            mTerminalCursorBlinkerRate = blinkRate;
            result = true;
        }

        if (mTerminalCursorBlinkerRate == 0) {
            mClient.logVerbose(LOG_TAG, "Cursor blinker disabled");
            stopTerminalCursorBlinker();
        }

        return result;
    }

    /**
     * Sets whether cursor blinker should be started or stopped. Cursor blinker will only be
     * started if {@link #mTerminalCursorBlinkerRate} does not equal 0 and is between
     * {@link #TERMINAL_CURSOR_BLINK_RATE_MIN} and {@link #TERMINAL_CURSOR_BLINK_RATE_MAX}.
     *
     * This should be called when the view holding this activity is resumed or stopped so that
     * cursor blinker does not run when activity is not visible. If you call this on onResume()
     * to start cursor blinking, then ensure that {@link #mEmulator} is set, otherwise wait for the
     * {@link TerminalViewClient#onEmulatorSet()} event after calling {@link #attachSession(TerminalSession)}
     * for the first session added in the activity since blinking will not start if {@link #mEmulator}
     * is not set, like if activity is started again after exiting it with double back press. Do not
     * call this directly after {@link #attachSession(TerminalSession)} since {@link #updateSize()}
     * may return without setting {@link #mEmulator} since width/height may be 0. Its called again in
     * {@link #onSizeChanged(int, int, int, int)}. Calling on onResume() if emulator is already set
     * is necessary, since onEmulatorSet() may not be called after activity is started after device
     * display timeout with double tap and not power button.
     *
     * It should also be called on the
     * {@link com.termux.terminal.TerminalSessionClient#onTerminalCursorStateChange(boolean)}
     * callback when cursor is enabled or disabled so that blinker is disabled if cursor is not
     * to be shown. It should also be checked if activity is visible if blinker is to be started
     * before calling this.
     *
     * It should also be called after terminal is reset with {@link TerminalSession#reset()} in case
     * cursor blinker was disabled before reset due to call to
     * {@link com.termux.terminal.TerminalSessionClient#onTerminalCursorStateChange(boolean)}.
     *
     * How cursor blinker starting works is by registering a {@link Runnable} with the looper of
     * the main thread of the app which when run, toggles the cursor blinking state and re-registers
     * itself to be called with the delay set by {@link #mTerminalCursorBlinkerRate}. When cursor
     * blinking needs to be disabled, we just cancel any callbacks registered. We don't run our own
     * "thread" and let the thread for the main looper do the work for us, whose usage is also
     * required to update the UI, since it also handles other calls to update the UI as well based
     * on a queue.
     *
     * Note that when moving cursor in text editors like nano, the cursor state is quickly
     * toggled `-> off -> on`, which would call this very quickly sequentially. So that if cursor
     * is moved 2 or more times quickly, like long hold on arrow keys, it would trigger
     * `-> off -> on -> off -> on -> ...`, and the "on" callback at index 2 is automatically
     * cancelled by next "off" callback at index 3 before getting a chance to be run. For this case
     * we log only if {@link #TERMINAL_VIEW_KEY_LOGGING_ENABLED} is enabled, otherwise would clutter
     * the log. We don't start the blinking with a delay to immediately show cursor in case it was
     * previously not visible.
     *
     * @param start If cursor blinker should be started or stopped.
     * @param startOnlyIfCursorEnabled If set to {@code true}, then it will also be checked if the
     *                                 cursor is even enabled by {@link TerminalEmulator} before
     *                                 starting the cursor blinker.
     */
    public synchronized void setTerminalCursorBlinkerState(boolean start, boolean startOnlyIfCursorEnabled) {
        // Stop any existing cursor blinker callbacks
        stopTerminalCursorBlinker();

        if (mEmulator == null) return;

        mEmulator.setCursorBlinkingEnabled(false);

        if (start) {
            // If cursor blinker is not enabled or is not valid
            if (mTerminalCursorBlinkerRate < TERMINAL_CURSOR_BLINK_RATE_MIN || mTerminalCursorBlinkerRate > TERMINAL_CURSOR_BLINK_RATE_MAX)
                return;
            // If cursor blinder is to be started only if cursor is enabled
            else if (startOnlyIfCursorEnabled && ! mEmulator.isCursorEnabled()) {
                if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                    mClient.logVerbose(LOG_TAG, "Ignoring call to start cursor blinker since cursor is not enabled");
                return;
            }

            // Start cursor blinker runnable
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mClient.logVerbose(LOG_TAG, "Starting cursor blinker with the blink rate " + mTerminalCursorBlinkerRate);
            if (mTerminalCursorBlinkerHandler == null)
                mTerminalCursorBlinkerHandler = new Handler(Looper.getMainLooper());
            mTerminalCursorBlinkerRunnable = new TerminalCursorBlinkerRunnable(mEmulator, mTerminalCursorBlinkerRate);
            mEmulator.setCursorBlinkingEnabled(true);
            mTerminalCursorBlinkerRunnable.run();
        }
    }

    /**
     * Cancel the terminal cursor blinker callbacks
     */
    private void stopTerminalCursorBlinker() {
        if (mTerminalCursorBlinkerHandler != null && mTerminalCursorBlinkerRunnable != null) {
            if (TERMINAL_VIEW_KEY_LOGGING_ENABLED)
                mClient.logVerbose(LOG_TAG, "Stopping cursor blinker");
            mTerminalCursorBlinkerHandler.removeCallbacks(mTerminalCursorBlinkerRunnable);
        }
    }

    private class TerminalCursorBlinkerRunnable implements Runnable {

        private TerminalEmulator mEmulator;
        private final int mBlinkRate;

        // Initialize with false so that initial blink state is visible after toggling
        boolean mCursorVisible = false;

        public TerminalCursorBlinkerRunnable(TerminalEmulator emulator, int blinkRate) {
            mEmulator = emulator;
            mBlinkRate = blinkRate;
        }

        public void setEmulator(TerminalEmulator emulator) {
            mEmulator = emulator;
        }

        public void run() {
            try {
                if (mEmulator != null) {
                    // Toggle the blink state and then invalidate() the view so
                    // that onDraw() is called, which then calls TerminalRenderer.render()
                    // which checks with TerminalEmulator.shouldCursorBeVisible() to decide whether
                    // to draw the cursor or not
                    mCursorVisible = !mCursorVisible;
                    //mClient.logVerbose(LOG_TAG, "Toggling cursor blink state to " + mCursorVisible);
                    mEmulator.setCursorBlinkState(mCursorVisible);
                    invalidate();
                }
            } finally {
                // Recall the Runnable after mBlinkRate milliseconds to toggle the blink state
                mTerminalCursorBlinkerHandler.postDelayed(this, mBlinkRate);
            }
        }
    }



    /**
     * Define functions required for text selection and its handles.
     */
    TextSelectionCursorController getTextSelectionCursorController() {
        if (mTextSelectionCursorController == null) {
            mTextSelectionCursorController = new TextSelectionCursorController(this);

            final ViewTreeObserver observer = getViewTreeObserver();
            if (observer != null) {
                observer.addOnTouchModeChangeListener(mTextSelectionCursorController);
            }
        }

        return mTextSelectionCursorController;
    }

    private void showTextSelectionCursors(MotionEvent event) {
        getTextSelectionCursorController().show(event);
    }

    private boolean hideTextSelectionCursors() {
        return getTextSelectionCursorController().hide();
    }

    private void renderTextSelection() {
        if (mTextSelectionCursorController != null)
            mTextSelectionCursorController.render();
    }

    public boolean isSelectingText() {
        if (mTextSelectionCursorController != null) {
            return mTextSelectionCursorController.isActive();
        } else {
            return false;
        }
    }

    /** Get the currently selected text if selecting. */
    public String getSelectedText() {
        if (isSelectingText() && mTextSelectionCursorController != null)
            return mTextSelectionCursorController.getSelectedText();
        else
            return null;
    }

    /**
     * Copy the selected text to the clipboard and stop selecting, like the Copy action of the
     * selection menu does.
     *
     * @return {@code true} if text was selected, otherwise {@code false}.
     */
    public boolean copySelectionToClipboard() {
        if (!isSelectingText()) return false;
        mTermSession.onCopyTextToClipboard(getSelectedText());
        stopTextSelectionMode();
        return true;
    }

    /** Get the selected text stored before "MORE" button was pressed on the context menu. */
    @Nullable
    public String getStoredSelectedText() {
        return mTextSelectionCursorController != null ? mTextSelectionCursorController.getStoredSelectedText() : null;
    }

    /** Unset the selected text stored before "MORE" button was pressed on the context menu. */
    public void unsetStoredSelectedText() {
        if (mTextSelectionCursorController != null) mTextSelectionCursorController.unsetStoredSelectedText();
    }

    private ActionMode getTextSelectionActionMode() {
        if (mTextSelectionCursorController != null) {
            return mTextSelectionCursorController.getActionMode();
        } else {
            return null;
        }
    }

    public void startTextSelectionMode(MotionEvent event) {
        if (!requestFocus()) {
            return;
        }

        showTextSelectionCursors(event);
        mClient.copyModeChanged(isSelectingText());

        invalidate();
    }

    public void stopTextSelectionMode() {
        if (hideTextSelectionCursors()) {
            mClient.copyModeChanged(isSelectingText());
            invalidate();
        }
    }

    private void decrementYTextSelectionCursors(int decrement) {
        if (mTextSelectionCursorController != null) {
            mTextSelectionCursorController.decrementYTextSelectionCursors(decrement);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (mTextSelectionCursorController != null) {
            getViewTreeObserver().addOnTouchModeChangeListener(mTextSelectionCursorController);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        cancelSingleFingerHold(true);
        mMouseModifiedTapPending = false;
        resetMouseMotionState();
        releaseForwardedButton();
        mMultiFingerTapDetector.onCancel();
        mMultiFingerSwipeDetector.onCancel();
        mGestureWasSwipe = false;

        if (mTextSelectionCursorController != null) {
            // Might solve the following exception
            // android.view.WindowLeaked: Activity com.termux.app.TermuxActivity has leaked window android.widget.PopupWindow
            stopTextSelectionMode();

            getViewTreeObserver().removeOnTouchModeChangeListener(mTextSelectionCursorController);
            mTextSelectionCursorController.onDetached();
        }
    }



    /**
     * Define functions required for long hold toolbar.
     */
    private final Runnable mShowFloatingToolbar = new Runnable() {
        @RequiresApi(api = Build.VERSION_CODES.M)
        @Override
        public void run() {
            if (getTextSelectionActionMode() != null) {
                getTextSelectionActionMode().hide(0);  // hide off.
            }
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.M)
    private void showFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            int delay = ViewConfiguration.getDoubleTapTimeout();
            postDelayed(mShowFloatingToolbar, delay);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    void hideFloatingToolbar() {
        if (getTextSelectionActionMode() != null) {
            removeCallbacks(mShowFloatingToolbar);
            getTextSelectionActionMode().hide(-1);
        }
    }

    public void updateFloatingToolbarVisibility(MotionEvent event) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && getTextSelectionActionMode() != null) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    hideFloatingToolbar();
                    break;
                case MotionEvent.ACTION_UP:  // fall through
                case MotionEvent.ACTION_CANCEL:
                    showFloatingToolbar();
            }
        }
    }

    /**
     * Restart the input connection so that the attributes returned by
     * {@link #onCreateInputConnection(EditorInfo)}, like the {@code inputType} defined by
     * {@link TerminalViewClient#getKeyboardMode()}, are reported to the IME again. Must be called
     * when the keyboard mode is changed.
     */
    public void restartInput() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.restartInput(this);
    }

}
