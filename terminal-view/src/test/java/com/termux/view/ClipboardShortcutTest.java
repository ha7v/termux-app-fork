package com.termux.view;

import static com.termux.view.ClipboardShortcut.BARE_CTRL_V_ALWAYS;
import static com.termux.view.ClipboardShortcut.BARE_CTRL_V_BRACKETED;
import static com.termux.view.ClipboardShortcut.BARE_CTRL_V_OFF;
import static com.termux.view.ClipboardShortcut.Action;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Unit tests for {@link ClipboardShortcut}. Runs on the JVM without an Android device because the
 * class under test has no Android dependencies.
 */
public class ClipboardShortcutTest {

    private static Action resolve(char c, boolean ctrl, boolean shift, boolean alt,
                                  boolean hasSelection, boolean bracketedPaste, int mode) {
        return ClipboardShortcut.resolve(c, ctrl, shift, alt, hasSelection, bracketedPaste, mode);
    }

    // --- copy -------------------------------------------------------------------------------------

    @Test
    public void ctrlCWithSelectionCopies() {
        assertEquals(Action.COPY, resolve('c', true, false, false, true, false, BARE_CTRL_V_BRACKETED));
    }

    @Test
    public void ctrlShiftCWithSelectionCopies() {
        assertEquals(Action.COPY, resolve('c', true, true, false, true, false, BARE_CTRL_V_BRACKETED));
    }

    @Test
    public void ctrlCWithoutSelectionIsPassedThrough() {
        // Must reach the program as SIGINT instead of being consumed.
        assertEquals(Action.NONE, resolve('c', true, false, false, false, false, BARE_CTRL_V_BRACKETED));
    }

    @Test
    public void copyShortcutIsCaseInsensitive() {
        assertEquals(Action.COPY, resolve('C', true, false, false, true, false, BARE_CTRL_V_BRACKETED));
    }

    // --- paste ------------------------------------------------------------------------------------

    @Test
    public void ctrlShiftVAlwaysPastes() {
        for (int mode : new int[]{BARE_CTRL_V_OFF, BARE_CTRL_V_BRACKETED, BARE_CTRL_V_ALWAYS}) {
            assertEquals("mode=" + mode,
                Action.PASTE, resolve('v', true, true, false, false, false, mode));
        }
    }

    @Test
    public void bareCtrlVOffIsPassedThrough() {
        assertEquals(Action.NONE, resolve('v', true, false, false, false, true, BARE_CTRL_V_OFF));
    }

    @Test
    public void bareCtrlVBracketedPastesOnlyWhenProgramEnabledIt() {
        assertEquals(Action.PASTE, resolve('v', true, false, false, false, true, BARE_CTRL_V_BRACKETED));
        assertEquals(Action.NONE, resolve('v', true, false, false, false, false, BARE_CTRL_V_BRACKETED));
    }

    @Test
    public void bareCtrlVAlwaysPastes() {
        assertEquals(Action.PASTE, resolve('v', true, false, false, false, false, BARE_CTRL_V_ALWAYS));
    }

    // --- not a clipboard shortcut -----------------------------------------------------------------

    @Test
    public void altCombinationsAreLeftToTheCaller() {
        // Ctrl+Alt+V is an application shortcut handled elsewhere; it must not be consumed here.
        assertEquals(Action.NONE, resolve('v', true, false, true, false, true, BARE_CTRL_V_ALWAYS));
        assertEquals(Action.NONE, resolve('c', true, false, true, true, false, BARE_CTRL_V_BRACKETED));
    }

    @Test
    public void keysWithoutCtrlArePassedThrough() {
        assertEquals(Action.NONE, resolve('c', false, false, false, true, false, BARE_CTRL_V_BRACKETED));
        assertEquals(Action.NONE, resolve('v', false, true, false, false, true, BARE_CTRL_V_ALWAYS));
    }

    @Test
    public void otherCtrlKeysArePassedThrough() {
        assertEquals(Action.NONE, resolve('x', true, false, false, true, false, BARE_CTRL_V_BRACKETED));
        assertEquals(Action.NONE, resolve('a', true, true, false, true, false, BARE_CTRL_V_BRACKETED));
    }
}
