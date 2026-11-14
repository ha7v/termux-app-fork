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

    /** Hover motion (35) in the legacy X10 encoding. */
    public void testDecset1003HoverMotionX10Encoding() {
        withTerminalSized(10, 5);
        enterString("\033[?1003h");
        mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_MOTION_NO_BUTTON, 4, 2, true);
        assertEquals(x10(32 + 35, 4, 2), mOutput.getOutputAndClear());
    }

    /** SGR encoding stays byte-identical to String.format() across every button and digit-count boundary. */
    public void testSgrEncodingAcrossValueRanges() {
        withTerminalSized(1200, 1100);
        enterString("\033[?1003h\033[?1006h");
        int[] values = {1, 2, 9, 10, 11, 99, 100, 101, 999, 1000, 1001, 1099, 1100};
        int[] buttons = {TerminalEmulator.MOUSE_LEFT_BUTTON, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED,
            TerminalEmulator.MOUSE_MOTION_NO_BUTTON, TerminalEmulator.MOUSE_WHEELUP_BUTTON,
            TerminalEmulator.MOUSE_WHEELDOWN_BUTTON};
        for (int button : buttons) {
            for (int column : values) {
                for (int row : values) {
                    for (boolean pressed : new boolean[]{true, false}) {
                        mTerminal.sendMouseEvent(button, column, row, pressed);
                        String expected = "\033[<" + button + ";" + column + ";" + row + (pressed ? 'M' : 'm');
                        assertEquals(expected, mOutput.getOutputAndClear());
                    }
                }
            }
        }
    }

    public void testSgrCoordinatesAreLocaleIndependent() {
        java.util.Locale prev = java.util.Locale.getDefault();
        try {
            // Locales such as ar-EG format %d with Arabic-Indic digits, which no terminal application can parse.
            java.util.Locale.setDefault(new java.util.Locale("ar", "EG"));
            withTerminalSized(300, 120);
            enterString("\033[?1000h\033[?1006h");
            mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 275, 108, true);
            assertEquals("\033[<0;275;108M", mOutput.getOutputAndClear());
            mTerminal.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, 199, 99, false);
            assertEquals("\033[<0;199;99m", mOutput.getOutputAndClear());
        } finally {
            java.util.Locale.setDefault(prev);
        }
    }

    private static String x10(int button, int column, int row) {
        return "\033[M" + (char) button + (char) (32 + column) + (char) (32 + row);
    }
}
