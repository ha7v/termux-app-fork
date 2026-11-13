package com.termux.terminal;

/**
 * Tests for mouse button reporting, in particular the middle and right buttons used by the touch
 * gesture layer for two/three-finger taps.
 */
public class MouseReportingTest extends TerminalTestCase {

    private void enableMouse(int trackingDecset) {
        withTerminalSized(10, 5);
        enterString("\033[?" + trackingDecset + "h");
        mOutput.getOutputAndClear();
    }

    private void enableSgrMouse(int trackingDecset) {
        withTerminalSized(10, 5);
        enterString("\033[?" + trackingDecset + "h\033[?1006h");
        mOutput.getOutputAndClear();
    }

    public void testSgrReportsMiddleAndRightButton() {
        enableSgrMouse(1000);
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_MIDDLE_BUTTON, 3, 2, true);
        assertEquals("\033[<1;3;2M", mOutput.getOutputAndClear());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_RIGHT_BUTTON, 4, 2, true);
        assertEquals("\033[<2;4;2M", mOutput.getOutputAndClear());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_MIDDLE_BUTTON, 3, 2, false);
        assertEquals("\033[<1;3;2m", mOutput.getOutputAndClear());
    }

    public void testX10ReportsMiddleAndRightButton() {
        enableMouse(1000);
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_MIDDLE_BUTTON, 3, 2, true);
        assertEquals(x10(32 + 1, 3, 2), mOutput.getOutputAndClear());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_RIGHT_BUTTON, 4, 2, true);
        assertEquals(x10(32 + 2, 4, 2), mOutput.getOutputAndClear());
    }

    /** Release of any button is encoded as button 3 in the non-SGR protocol. */
    public void testX10ReleaseUsesButtonThree() {
        enableMouse(1000);
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_RIGHT_BUTTON, 4, 2, false);
        assertEquals(x10(32 + 3, 4, 2), mOutput.getOutputAndClear());
    }

    public void testDecset1003AnyEventTrackingAndMutualExclusion() {
        withTerminalSized(10, 5);
        assertFalse(mTerminal.isMouseTrackingActive());
        assertFalse(mTerminal.isMouseTrackingAnyEvent());

        // Enable 1003: clicks, button-drag (32), and hover motion (35) must all be active.
        enterString("\033[?1003h\033[?1006h");
        assertTrue(mTerminal.isMouseTrackingActive());
        assertTrue(mTerminal.isMouseTrackingAnyEvent());

        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, 5, 3, true);
        assertEquals("\033[<32;5;3M", mOutput.getOutputAndClear());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_MOTION_NO_BUTTON, 6, 4, true);
        assertEquals("\033[<35;6;4M", mOutput.getOutputAndClear());

        // Switching to 1002 clears 1003: button-drag stays active, hover motion is suppressed.
        enterString("\033[?1002h");
        assertTrue(mTerminal.isMouseTrackingActive());
        assertFalse(mTerminal.isMouseTrackingAnyEvent());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_MOTION_NO_BUTTON, 6, 4, true);
        assertEquals("", mOutput.getOutputAndClear());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, 5, 3, true);
        assertEquals("\033[<32;5;3M", mOutput.getOutputAndClear());

        // Switching to 1000 clears 1002: both motion types are suppressed, clicks still work.
        enterString("\033[?1000h");
        assertTrue(mTerminal.isMouseTrackingActive());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, 5, 3, true);
        assertEquals("", mOutput.getOutputAndClear());
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 2, 2, true);
        assertEquals("\033[<0;2;2M", mOutput.getOutputAndClear());
    }

    private static String x10(int button, int column, int row) {
        return "\033[M" + (char) button + (char) (32 + column) + (char) (32 + row);
    }
}
