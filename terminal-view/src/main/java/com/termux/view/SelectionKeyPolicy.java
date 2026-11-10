package com.termux.view;

import android.view.KeyEvent;

/**
 * Pure policy for how a key press interacts with the text selection.
 *
 * <p>Kept separate from {@link TerminalView} and free of instance state so it can be unit tested,
 * and so the selection lifecycle (isSelecting / copy / clear) has a single place to grow when touch
 * gestures that create selections are added later.
 */
public final class SelectionKeyPolicy {

    private SelectionKeyPolicy() {}

    /**
     * Whether pressing this key should clear the text selection before the key is processed.
     *
     * <p>Modifier keys never clear it, so that a following shortcut such as Ctrl+C can still act on
     * the selection (on a hardware keyboard the Ctrl keydown arrives before the C keydown). Keys that
     * produce text input, and Escape/Backspace/Delete/Enter/Tab, do clear it. Navigation and function
     * keys leave the selection alone so the user can still copy after moving around.
     *
     * @param keyCode     the {@link KeyEvent} key code.
     * @param isModifier  whether the key is a modifier key, i.e. {@link KeyEvent#isModifierKey(int)}.
     * @param unicodeChar the unmodified character produced by the key, or 0 if none.
     */
    public static boolean shouldClearSelection(int keyCode, boolean isModifier, int unicodeChar) {
        if (isModifier) return false;
        switch (keyCode) {
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_FORWARD_DEL:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_TAB:
                return true;
            default:
                return unicodeChar >= 32;
        }
    }
}
