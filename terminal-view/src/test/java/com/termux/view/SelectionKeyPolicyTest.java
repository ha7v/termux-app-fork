package com.termux.view;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

/**
 * Unit tests for {@link SelectionKeyPolicy}. Runs on the JVM without an Android device; the key
 * codes are compile-time constants and the class under test has no instance state.
 */
public class SelectionKeyPolicyTest {

    @Test
    public void modifierKeysDoNotClearSelection() {
        // A hardware Ctrl+C sends the Ctrl keydown before the C keydown; clearing on Ctrl would
        // destroy the selection before the C key could copy it.
        assertFalse(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_CTRL_LEFT, true, 0));
        assertFalse(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_CTRL_RIGHT, true, 0));
        assertFalse(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_SHIFT_LEFT, true, 0));
        assertFalse(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_ALT_LEFT, true, 0));
    }

    @Test
    public void printableKeysClearSelection() {
        assertTrue(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_A, false, 'a'));
        assertTrue(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_SPACE, false, ' '));
        assertTrue(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_1, false, '1'));
    }

    @Test
    public void editingAndEscapeKeysClearSelection() {
        assertTrue(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_ESCAPE, false, 0));
        assertTrue(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_DEL, false, 0));
        assertTrue(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_FORWARD_DEL, false, 0));
        assertTrue(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_ENTER, false, 0));
        assertTrue(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_NUMPAD_ENTER, false, 0));
        assertTrue(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_TAB, false, 0));
    }

    @Test
    public void navigationAndFunctionKeysLeaveSelectionAlone() {
        assertFalse(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_DPAD_LEFT, false, 0));
        assertFalse(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_DPAD_UP, false, 0));
        assertFalse(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_PAGE_UP, false, 0));
        assertFalse(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_MOVE_HOME, false, 0));
        assertFalse(SelectionKeyPolicy.shouldClearSelection(KeyEvent.KEYCODE_F1, false, 0));
    }
}
