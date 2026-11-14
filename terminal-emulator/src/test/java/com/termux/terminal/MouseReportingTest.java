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
